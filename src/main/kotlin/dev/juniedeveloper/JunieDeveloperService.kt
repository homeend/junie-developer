package dev.juniedeveloper

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.containers.ContainerUtil
import dev.juniedeveloper.air.AirJunie
import dev.juniedeveloper.air.AirPoolResetter
import dev.juniedeveloper.core.HostPlatform
import dev.juniedeveloper.core.JunieEntry
import dev.juniedeveloper.core.JunieLibrary
import dev.juniedeveloper.core.JunieRelease
import dev.juniedeveloper.core.Launchers
import dev.juniedeveloper.core.ZipExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service(Service.Level.APP)
class JunieDeveloperService(private val scope: CoroutineScope) {
    val platform: HostPlatform = HostPlatform.current()
    val library = JunieLibrary(JunieLibrary.defaultBaseDir(), platform)

    private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) { listeners += listener }
    fun removeChangeListener(listener: () -> Unit) { listeners -= listener }
    private fun fireChanged() = ApplicationManager.getApplication().invokeLater { listeners.forEach { it() } }

    /** The library entry Air currently runs, or null for the default Junie or an unknown command. */
    fun activeEntry(): JunieEntry? {
        val command = AirJunie.currentCommand().ifEmpty { return null }
        return library.entries().firstOrNull { entry ->
            library.expectedLauncher(entry)?.let { samePath(it.toString(), command) } == true
        }
    }

    /** Points Air at [entry] (null = back to the ACP registry Junie) and restarts its agents. */
    fun activate(entry: JunieEntry?, project: Project?) {
        val command = try {
            entry?.let { library.launcherFor(it, fallbackJava()).toString() }
        } catch (e: IOException) {
            notify(project, "Cannot use ${entry?.name}: ${e.message}", NotificationType.ERROR)
            return
        }
        AirJunie.setCommand(command)
        fireChanged()
        val what = entry?.name ?: "the default Junie from the ACP registry"
        AirJunie.vmOptionOverride()?.let {
            notify(project, "Selected $what, but the IDE was started with -D${AirJunie.registryKey}=$it, " +
                "which Air prefers. Remove that VM option to let JunieDeveloper switch.", NotificationType.WARNING)
        }
        resetPool(project, "Air now uses $what.")
    }

    /** Stops Air's pooled agents; [done] is the message shown when it worked. */
    fun resetPool(project: Project?, done: String = "Air agent pool was reset.") {
        scope.launch {
            try {
                AirPoolResetter.resetPool()
                notify(project, "$done Running agents were stopped; send a new message to start a fresh one.",
                    NotificationType.INFORMATION)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AirPoolResetter.PoolResetUnavailable) {
                LOG.warn(e)
                notify(project, "$done The agent pool could not be reset automatically (${e.message}). " +
                    MANUAL_RESET, NotificationType.WARNING)
            } catch (e: Throwable) {
                LOG.warn("Air pool reset failed", e)
                notify(project, "$done Resetting the agent pool failed: ${e.message ?: e.javaClass.simpleName}. " +
                    MANUAL_RESET, NotificationType.WARNING)
            }
        }
    }

    fun addJar(jar: Path): JunieEntry = library.addJar(jar).also { fireChanged() }

    fun remove(entry: JunieEntry, project: Project?) {
        if (activeEntry()?.id == entry.id) activate(null, project)
        library.remove(entry)
        fireChanged()
    }

    /** Downloads and unpacks [release] for this machine in a cancellable background task. */
    fun download(release: JunieRelease, project: Project?, onInstalled: (JunieEntry) -> Unit = {}) {
        val asset = release.assetFor(platform) ?: run {
            notify(project, "Release ${release.tag} has no build for ${platform.os}-${platform.arch}.", NotificationType.ERROR)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Junie ${release.tag}", true) {
            override fun run(indicator: ProgressIndicator) {
                val versions = library.versionsDir
                Files.createDirectories(versions)
                val zip = versions.resolve(asset.name + ".part")
                val staging = versions.resolve(asset.name.removeSuffix(".zip") + ".unpacking")
                val target = library.installDirFor(asset)
                try {
                    indicator.text = "Downloading ${asset.name}"
                    GitHubReleases.download(asset.url, zip, indicator)
                    indicator.checkCanceled()
                    indicator.text = "Unpacking ${asset.name}"
                    staging.toFile().deleteRecursively()
                    ZipExtractor.extract(zip, staging) { done, total ->
                        indicator.checkCanceled()
                        indicator.fraction = done.toDouble() / total
                    }
                    if (!platform.isWindows) ZipExtractor.restoreExecutableBits(staging)
                    if (Launchers.findEntryPoint(staging) == null) {
                        throw IOException("${asset.name} does not contain a Junie launcher")
                    }
                    target.toFile().deleteRecursively()
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    val entry = library.addRelease(release, target)
                    fireChanged()
                    notify(project, "Junie ${release.tag} downloaded.", NotificationType.INFORMATION)
                    ApplicationManager.getApplication().invokeLater { onInstalled(entry) }
                } finally {
                    Files.deleteIfExists(zip)
                    staging.toFile().deleteRecursively()
                }
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Junie download failed", error)
                notify(project, "Downloading Junie ${release.tag} failed: ${error.message}", NotificationType.ERROR)
            }
        })
    }

    /** The IDE's own Java, used by JAR launchers when Air does not pass JBR_BIN_DIR. */
    private fun fallbackJava(): Path =
        Path.of(System.getProperty("java.home"), "bin", if (platform.isWindows) "java.exe" else "java")

    private fun samePath(a: String, b: String): Boolean =
        if (platform.isWindows) a.replace('/', '\\').equals(b.replace('/', '\\'), ignoreCase = true) else a == b

    fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("JunieDeveloper")
            .createNotification("JunieDeveloper", message, type).notify(project)
    }

    companion object {
        private val LOG = logger<JunieDeveloperService>()
        private const val MANUAL_RESET =
            "Reset it by hand: Find Action > Debug ACP Chat > Agent Pool > Reset Pool (needs idea.is.internal=true)."

        fun getInstance(): JunieDeveloperService = service()
    }
}
