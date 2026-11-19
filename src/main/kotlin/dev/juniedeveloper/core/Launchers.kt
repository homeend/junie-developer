package dev.juniedeveloper.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

object Launchers {
    // Same relative commands the ACP registry uses for the packaged Junie.
    private val KNOWN_ENTRY_POINTS = listOf(
        "junie/junie.exe",
        "junie-app/bin/junie",
        "Applications/junie.app/Contents/MacOS/junie",
    )

    /** The executable inside an unpacked release, or null when the layout is unknown. */
    fun findEntryPoint(installDir: Path): Path? {
        KNOWN_ENTRY_POINTS.map { installDir.resolve(it) }.firstOrNull { it.isRegularFile() }?.let { return it }
        if (!Files.isDirectory(installDir)) return null
        // Layout changed: look for a launcher by name, but never the bundled runtime's own binaries.
        return Files.walk(installDir, 6).use { paths ->
            paths.filter { p ->
                val name = p.fileName?.toString()
                p.isRegularFile() && (name == "junie.exe" || (name == "junie" && p.parent?.fileName?.toString() in setOf("bin", "MacOS")))
            }.findFirst().orElse(null)
        }
    }

    /**
     * A launcher for a Junie shadow JAR. Air passes an executable path, not a shell
     * command, so `java -jar` has to live in a script. Air sets `JBR_BIN_DIR`;
     * [fallbackJava] covers the case where it does not.
     *
     * `--enable-native-access` mirrors the packaged Junie's own launch options.
     */
    fun jarLauncherScript(jar: Path, fallbackJava: Path, windows: Boolean): String =
        if (windows) {
            listOf(
                "@echo off",
                "setlocal",
                "set \"JAVA_EXE=$fallbackJava\"",
                "if defined JBR_BIN_DIR if exist \"%JBR_BIN_DIR%\\java.exe\" set \"JAVA_EXE=%JBR_BIN_DIR%\\java.exe\"",
                "\"%JAVA_EXE%\" --enable-native-access=ALL-UNNAMED -jar \"$jar\" %*",
                "exit /b %ERRORLEVEL%",
                "",
            ).joinToString("\r\n")
        } else {
            listOf(
                "#!/bin/sh",
                "JAVA_EXE=${shQuote(fallbackJava.toString())}",
                "if [ -n \"\$JBR_BIN_DIR\" ] && [ -x \"\$JBR_BIN_DIR/java\" ]; then JAVA_EXE=\"\$JBR_BIN_DIR/java\"; fi",
                "exec \"\$JAVA_EXE\" --enable-native-access=ALL-UNNAMED -jar ${shQuote(jar.toString())} \"\$@\"",
                "",
            ).joinToString("\n")
        }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
