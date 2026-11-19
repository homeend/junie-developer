package dev.juniedeveloper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val windows = HostPlatform("windows", "amd64")
    private val linux = HostPlatform("linux", "amd64")

    private val releasesJson = """
        [
          {"tag_name":"4175.1","name":"Junie Nightly 4175.1","published_at":"2026-11-19T16:24:17Z","assets":[
            {"name":"junie-nightly-4175.1-linux-aarch64.zip","size":170,"browser_download_url":"https://x/la"},
            {"name":"junie-nightly-4175.1-linux-amd64.zip","size":171,"browser_download_url":"https://x/l"},
            {"name":"junie-nightly-4175.1-windows-amd64.zip","size":130,"browser_download_url":"https://x/w"},
            {"name":"junie.sh","size":1,"browser_download_url":"https://x/sh"}]},
          {"tag_name":"4171.3","name":"Junie EAP 26.11.3 (4171.3)","published_at":"2026-11-19T16:27:17Z","assets":[
            {"name":"junie-eap-4171.3-macos-aarch64.zip","size":155,"browser_download_url":"https://x/m"}]},
          {"tag_name":"0.1","name":null,"published_at":null,"assets":[]}
        ]""".trimIndent()

    @Test fun `parses releases and derives the channel from asset names`() {
        val releases = JunieRelease.parseList(releasesJson)
        assertEquals(listOf("4175.1", "4171.3", "0.1"), releases.map { it.tag })
        assertEquals(listOf("nightly", "eap", null), releases.map { it.channel })
        assertEquals("0.1", releases[2].title) // a null name falls back to the tag
    }

    @Test fun `picks the asset for the host platform only`() {
        val nightly = JunieRelease.parseList(releasesJson)[0]
        assertEquals("https://x/w", nightly.assetFor(windows)?.url)
        assertEquals("https://x/l", nightly.assetFor(linux)?.url)
        assertEquals("https://x/la", nightly.assetFor(HostPlatform("linux", "aarch64"))?.url)
        assertNull(nightly.assetFor(HostPlatform("macos", "amd64")))
    }

    @Test fun `search matches tag and title case-insensitively`() {
        val eap = JunieRelease.parseList(releasesJson)[1]
        assertTrue(eap.matches("4171"))
        assertTrue(eap.matches(" eap 26.11 "))
        assertTrue(eap.matches(""))
        assertFalse(eap.matches("nightly"))
    }

    @Test fun `maps JVM os and arch names to asset names`() {
        assertEquals(windows, HostPlatform.of("Windows 11", "amd64"))
        assertEquals(HostPlatform("macos", "aarch64"), HostPlatform.of("Mac OS X", "aarch64"))
        assertEquals(linux, HostPlatform.of("Linux", "x86_64"))
    }

    @Test fun `extracts a zip written with backslash separators into real directories`() {
        val zip = zip("win.zip",
            "junie\\junie.exe" to "exe",
            "junie\\runtime\\legal\\" to null,
            "junie\\app\\junie.cfg" to "cfg")
        val out = tmp.root.toPath().resolve("out")
        ZipExtractor.extract(zip, out)
        assertEquals("exe", out.resolve("junie/junie.exe").readText())
        assertEquals("cfg", out.resolve("junie/app/junie.cfg").readText())
        assertTrue(out.resolve("junie/runtime/legal").isDirectory())
        assertEquals(out.resolve("junie/junie.exe"), Launchers.findEntryPoint(out))
    }

    @Test fun `refuses zip entries that escape the target`() {
        val zip = zip("evil.zip", "..\\..\\evil.txt" to "x")
        try {
            ZipExtractor.extract(zip, tmp.root.toPath().resolve("safe/deep"))
            fail("expected IOException")
        } catch (expected: IOException) {
        }
        assertFalse(tmp.root.toPath().resolve("evil.txt").exists())
    }

    @Test fun `finds the linux launcher, not the bundled java`() {
        val zip = zip("lin.zip",
            "junie-app/lib/runtime/bin/java" to "java",
            "junie-app/bin/junie" to "launcher",
            "junie" to "wrapper")
        val out = tmp.root.toPath().resolve("lin")
        ZipExtractor.extract(zip, out)
        ZipExtractor.restoreExecutableBits(out)
        val entry = Launchers.findEntryPoint(out)
        assertEquals(out.resolve("junie-app/bin/junie"), entry)
        if (!HostPlatform.current().isWindows) {
            assertTrue(Files.isExecutable(entry!!))
            assertTrue(Files.isExecutable(out.resolve("junie-app/lib/runtime/bin/java")))
        }
    }

    @Test fun `finds a launcher in an unknown layout by name`() {
        val out = tmp.root.toPath().resolve("odd")
        Files.createDirectories(out.resolve("pkg/new/bin"))
        Files.createDirectories(out.resolve("pkg/runtime/bin"))
        out.resolve("pkg/runtime/bin/java").writeText("java")
        out.resolve("pkg/new/bin/junie").writeText("launcher")
        assertEquals(out.resolve("pkg/new/bin/junie"), Launchers.findEntryPoint(out))
        assertNull(Launchers.findEntryPoint(tmp.root.toPath().resolve("missing")))
    }

    @Test fun `windows jar launcher prefers JBR_BIN_DIR and forwards arguments`() {
        val script = Launchers.jarLauncherScript(Path.of("C:/j/junie all.jar"), Path.of("C:/jbr/bin/java.exe"), windows = true)
        assertTrue(script.contains("\r\n"))
        assertTrue(script.contains("%JBR_BIN_DIR%\\java.exe"))
        assertTrue(script.lines().any { it.contains("-jar \"") && it.contains("junie all.jar\" %*") })
    }

    @Test fun `unix jar launcher quotes paths and execs java`() {
        val script = Launchers.jarLauncherScript(Path.of("/tmp/it's/junie.jar"), Path.of("/opt/jbr/bin/java"), windows = false)
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(script.contains("-jar '/tmp/it'\\''s/junie.jar' \"\$@\""))
        assertTrue(script.contains("\$JBR_BIN_DIR/java"))
    }

    @Test fun `library keeps jars in place and writes their launcher on demand`() {
        val base = tmp.root.toPath().resolve("lib")
        val jar = tmp.newFile("junie-local.jar").toPath()
        val library = JunieLibrary(base, linux)
        val entry = library.addJar(jar)
        library.addJar(jar) // adding twice does not duplicate
        assertEquals(listOf(entry.id), library.entries().map { it.id })
        assertEquals(jar.toAbsolutePath().toString(), entry.path)

        val launcher = library.launcherFor(entry, Path.of("/opt/java"))
        assertEquals(launcher, library.expectedLauncher(entry))
        assertTrue(launcher.readText().contains(jar.toAbsolutePath().toString()))

        // a second instance (another IDE) sees the same library
        assertEquals(listOf(entry), JunieLibrary(base, linux).entries())

        library.remove(entry)
        assertTrue(library.entries().isEmpty())
        assertFalse(launcher.exists())
        assertTrue("the user's jar must survive removal", jar.isRegularFile())
    }

    @Test fun `removing a release deletes its files but nothing outside versions`() {
        val base = tmp.root.toPath().resolve("lib2")
        val library = JunieLibrary(base, windows)
        val release = JunieRelease.parseList(releasesJson)[0]
        val dir = library.installDirFor(release.assetFor(windows)!!)
        Files.createDirectories(dir.resolve("junie"))
        dir.resolve("junie/junie.exe").writeText("exe")
        val entry = library.addRelease(release, dir)
        assertTrue(library.isInstalled(release))
        assertEquals("nightly", entry.channel)
        assertNotNull(library.launcherFor(entry, Path.of("java")))

        library.remove(entry)
        assertFalse(dir.exists())
        assertTrue(library.versionsDir.exists())
        assertTrue(library.entries().isEmpty())

        // an entry pointing outside the library is forgotten, never deleted
        val outside = tmp.newFolder("precious").toPath()
        val rogue = entry.copy(id = "release-rogue", path = outside.toString())
        library.remove(rogue)
        assertTrue(outside.exists())
    }

    @Test fun `a release with a missing launcher fails loudly`() {
        val library = JunieLibrary(tmp.root.toPath().resolve("lib3"), windows)
        val entry = JunieEntry("release-x", EntryKind.RELEASE, "x", tmp.root.toPath().resolve("gone").toString())
        try {
            library.launcherFor(entry, Path.of("java"))
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }

    private fun zip(name: String, vararg entries: Pair<String, String?>): Path {
        val file = tmp.root.toPath().resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                content?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return file
    }
}
