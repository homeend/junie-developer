package dev.juniedeveloper.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Unpacks Junie release zips.
 *
 * The Windows zips are written with backslash separators (`junie\app\junie.cfg`,
 * directories as `junie\runtime\legal\`), which the JDK would otherwise turn into
 * single flat file names, so separators are normalised here.
 */
object ZipExtractor {
    fun extract(zip: Path, targetDir: Path, onEntry: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val root = targetDir.toAbsolutePath().normalize()
        Files.createDirectories(root)
        ZipFile(zip.toFile()).use { zf ->
            val total = zf.size()
            var done = 0
            for (entry in zf.entries()) {
                val name = entry.name.replace('\\', '/')
                val out = root.resolve(name).normalize()
                if (!out.startsWith(root)) throw IOException("Zip entry escapes the target directory: ${entry.name}")
                if (entry.isDirectory || name.endsWith("/")) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    zf.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                }
                onEntry(++done, total)
            }
        }
    }

    /**
     * `java.util.zip` drops unix permissions, so on Linux/macOS the launcher and the
     * bundled Java runtime are made executable again by location.
     */
    fun restoreExecutableBits(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && looksExecutable(root, it) }
                .forEach { it.toFile().setExecutable(true, false) }
        }
    }

    private fun looksExecutable(root: Path, file: Path): Boolean {
        val parent = file.parent?.fileName?.toString()
        val name = file.fileName.toString()
        return parent == "bin" || parent == "MacOS" || file.parent == root ||
            name.endsWith(".sh") || name == "jspawnhelper" || name == "jexec"
    }
}
