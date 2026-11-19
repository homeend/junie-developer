package dev.juniedeveloper.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The GitHub command-line tool, used as a second route to GitHub.
 *
 * `gh` makes the request itself with its own login, so it keeps working when the
 * plugin's direct requests are refused, for example with a 403 because a shared
 * VPN address has used up GitHub's anonymous request limit.
 */
class GhCli(private val explicitExecutable: String? = null) {
    class Output(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * The `gh` to run, or null when none is found. An IDE started from a desktop
     * launcher often has a shorter PATH than a terminal, so the usual install
     * locations and the login shell's PATH are tried as well.
     */
    val executable: String? by lazy {
        val candidates = if (explicitExecutable != null) listOf(explicitExecutable) else buildList {
            add("gh")
            addAll(wellKnownLocations().filter { Files.isRegularFile(Path.of(it)) })
            fromLoginShell()?.let { add(it) }
        }
        candidates.distinct().firstOrNull { works(it) }
    }

    /** True when `gh` can be started at all; says nothing about being logged in. */
    val isInstalled: Boolean get() = executable != null

    private fun works(candidate: String): Boolean =
        runCatching { start(candidate, listOf("--version"), 15) { false }.exitCode == 0 }.getOrDefault(false)

    private fun wellKnownLocations(): List<String> {
        val home = System.getProperty("user.home")
        return if (HostPlatform.current().isWindows) listOfNotNull(
            System.getenv("ProgramFiles")?.let { "$it\\GitHub CLI\\gh.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\GitHub CLI\\gh.exe" },
            "$home\\scoop\\shims\\gh.exe",
        ) else listOf(
            "/usr/bin/gh", "/usr/local/bin/gh", "/opt/homebrew/bin/gh", "/home/linuxbrew/.linuxbrew/bin/gh",
            "/snap/bin/gh", "$home/.local/bin/gh", "$home/bin/gh",
        )
    }

    private fun fromLoginShell(): String? {
        if (HostPlatform.current().isWindows) return null
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        return runCatching {
            start(shell, listOf("-lc", "command -v gh"), 15) { false }
                .takeIf { it.exitCode == 0 }?.stdout?.lines()?.lastOrNull { it.startsWith("/") }?.trim()
        }.getOrNull()
    }

    /** The token of the current `gh` login, or null. */
    fun authToken(): String? = runCatching {
        run(listOf("auth", "token"), 15).takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** `gh api <path>`: the response body, e.g. for `repos/OWNER/REPO/releases?per_page=100`. */
    fun api(path: String): String {
        val out = run(listOf("api", "-H", "Accept: application/vnd.github+json", path), 60)
        if (out.exitCode != 0) throw IOException("gh api failed: " + out.stderr.trim().ifEmpty { out.stdout.trim() }.take(300))
        return out.stdout
    }

    /** Downloads one release asset to [target] with `gh release download`. */
    fun downloadAsset(repository: String, tag: String, assetName: String, target: Path, isCancelled: () -> Boolean = { false }) {
        Files.deleteIfExists(target)
        val out = run(listOf("release", "download", tag, "-R", repository, "-p", assetName, "-O", target.toString()),
            timeoutSeconds = 3600, isCancelled = isCancelled)
        if (out.exitCode != 0) throw IOException("gh release download failed: " + out.stderr.trim().take(300))
        if (!Files.isRegularFile(target)) throw IOException("gh release download produced no file for $assetName")
    }

    fun run(args: List<String>, timeoutSeconds: Long, isCancelled: () -> Boolean = { false }): Output =
        start(executable ?: throw IOException("the GitHub CLI (gh) was not found"), args, timeoutSeconds, isCancelled)

    private fun start(command: String, args: List<String>, timeoutSeconds: Long, isCancelled: () -> Boolean): Output {
        val process = ProcessBuilder(listOf(command) + args).redirectErrorStream(false).start()
        process.outputStream.close()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) throw IOException("cancelled")
                if (System.nanoTime() > deadline) throw IOException("$command ${args.firstOrNull()} timed out after ${timeoutSeconds}s")
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
        return Output(process.exitValue(), stdout.get(10, TimeUnit.SECONDS), stderr.get(10, TimeUnit.SECONDS))
    }
}
