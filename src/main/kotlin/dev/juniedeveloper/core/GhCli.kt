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
class GhCli(private val executable: String = "gh") {
    class Output(val exitCode: Int, val stdout: String, val stderr: String)

    /** True when `gh` can be started at all; says nothing about being logged in. */
    val isInstalled: Boolean by lazy { runCatching { run(listOf("--version"), 15).exitCode == 0 }.getOrDefault(false) }

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

    fun run(args: List<String>, timeoutSeconds: Long, isCancelled: () -> Boolean = { false }): Output {
        val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(false).start()
        process.outputStream.close()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) throw IOException("cancelled")
                if (System.nanoTime() > deadline) throw IOException("gh ${args.firstOrNull()} timed out after ${timeoutSeconds}s")
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
