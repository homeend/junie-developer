package dev.juniedeveloper.core

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class EntryKind { RELEASE, JAR }

/**
 * One Junie that Air can be pointed at.
 *
 * [path] is the unpacked install directory for a [EntryKind.RELEASE] and the JAR
 * file itself for a [EntryKind.JAR]. A JAR is referenced where it lies, never
 * copied, so rebuilding it is enough to get the new code.
 */
data class JunieEntry(
    val id: String,
    val kind: EntryKind,
    val name: String,
    val path: String,
    val tag: String? = null,
    val channel: String? = null,
    val addedAt: Long = 0,
)

/**
 * The on-disk library of Junie builds, kept under one directory in the user's home
 * so every IDE that has this plugin sees the same downloads.
 *
 *     <base>/library.json     the entries
 *     <base>/versions/<name>  unpacked releases
 *     <base>/launchers/       generated launchers for local JARs
 */
class JunieLibrary(val baseDir: Path, private val platform: HostPlatform = HostPlatform.current()) {
    private val indexFile: Path get() = baseDir.resolve("library.json")
    val versionsDir: Path get() = baseDir.resolve("versions")
    private val launchersDir: Path get() = baseDir.resolve("launchers")

    @Synchronized
    fun entries(): List<JunieEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            GSON.fromJson<List<JunieEntry>>(indexFile.readText(), LIST_TYPE).orEmpty()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(entries: List<JunieEntry>) {
        Files.createDirectories(baseDir)
        val tmp = baseDir.resolve("library.json.tmp")
        tmp.writeText(GSON.toJson(entries))
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun addJar(jar: Path): JunieEntry {
        val absolute = jar.toAbsolutePath().normalize()
        if (!absolute.isRegularFile()) throw IOException("Not a file: $absolute")
        val id = "jar-" + Integer.toHexString(absolute.toString().hashCode())
        entries().firstOrNull { it.id == id }?.let { return it }
        val entry = JunieEntry(id, EntryKind.JAR, absolute.fileName.toString(), absolute.toString(),
            addedAt = System.currentTimeMillis())
        save(entries().filterNot { it.id == id } + entry)
        return entry
    }

    /** Directory a release asset unpacks into, e.g. `versions/junie-nightly-4175.1-windows-amd64`. */
    fun installDirFor(asset: ReleaseAsset): Path = versionsDir.resolve(asset.name.removeSuffix(".zip"))

    fun isInstalled(release: JunieRelease): Boolean = entries().any { it.kind == EntryKind.RELEASE && it.tag == release.tag }

    @Synchronized
    fun addRelease(release: JunieRelease, installDir: Path): JunieEntry {
        val id = "release-" + installDir.fileName
        val entry = JunieEntry(id, EntryKind.RELEASE, release.title, installDir.toAbsolutePath().toString(),
            tag = release.tag, channel = release.channel, addedAt = System.currentTimeMillis())
        save(entries().filterNot { it.id == id } + entry)
        return entry
    }

    /** Forgets [entry]. A downloaded release is deleted from disk; a local JAR is left alone. */
    @Synchronized
    fun remove(entry: JunieEntry) {
        save(entries().filterNot { it.id == entry.id })
        when (entry.kind) {
            EntryKind.RELEASE -> {
                val dir = Path.of(entry.path).toAbsolutePath().normalize()
                // only ever delete inside our own versions directory
                if (dir.startsWith(versionsDir.toAbsolutePath().normalize()) && dir != versionsDir) {
                    dir.toFile().deleteRecursively()
                }
            }
            EntryKind.JAR -> Files.deleteIfExists(jarLauncherPath(entry))
        }
    }

    /**
     * The executable Air must be given for [entry]. For a JAR the launcher script is
     * (re)written on every call; [fallbackJava] is used when Air does not supply
     * `JBR_BIN_DIR`.
     */
    fun launcherFor(entry: JunieEntry, fallbackJava: Path): Path = when (entry.kind) {
        EntryKind.RELEASE -> Launchers.findEntryPoint(Path.of(entry.path))
            ?: throw IOException("No Junie launcher found in ${entry.path}")
        EntryKind.JAR -> {
            val jar = Path.of(entry.path)
            if (!jar.isRegularFile()) throw IOException("JAR no longer exists: $jar")
            val launcher = jarLauncherPath(entry)
            Files.createDirectories(launcher.parent)
            launcher.writeText(Launchers.jarLauncherScript(jar, fallbackJava, platform.isWindows))
            if (!platform.isWindows) launcher.toFile().setExecutable(true, false)
            launcher
        }
    }

    /** Where the launcher of [entry] is or would be, without touching the disk. */
    fun expectedLauncher(entry: JunieEntry): Path? = when (entry.kind) {
        EntryKind.RELEASE -> Launchers.findEntryPoint(Path.of(entry.path))
        EntryKind.JAR -> jarLauncherPath(entry)
    }

    private fun jarLauncherPath(entry: JunieEntry): Path =
        launchersDir.resolve(entry.id + if (platform.isWindows) ".cmd" else ".sh")

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val LIST_TYPE = object : TypeToken<List<JunieEntry>>() {}.type

        fun defaultBaseDir(): Path = Path.of(System.getProperty("user.home"), ".junie-developer")
    }
}
