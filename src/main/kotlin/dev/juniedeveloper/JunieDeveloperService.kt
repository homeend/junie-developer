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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service(Service.Level.APP)
class JunieDeveloperService(private val scope: CoroutineScope) {
    val platform: HostPlatform = HostPlatform.current()
    val library = JunieLibrary(JunieLibrary.defaultBaseDir(), platform)

    private val downloading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
        if (!select(entry, project)) return
        val what = entry?.name ?: DEFAULT_JUNIE
        scope.launch { resetPoolAndReport(project, "Air now uses $what.") }
    }

    /** Writes the Registry key only; false when [entry] cannot be used. */
    private fun select(entry: JunieEntry?, project: Project?): Boolean {
        val command = try {
            entry?.let { library.launcherFor(it, fallbackJava()).toString() }
        } catch (e: IOException) {
            notify(project, "Cannot use ${entry?.name}: ${e.message}", NotificationType.ERROR)
            return false
        }
        AirJunie.setCommand(command)
        fireChanged()
        AirJunie.blocker()?.let { notify(project, it, NotificationType.WARNING) }
        return true
    }

    /** Stops Air's pooled agents. */
    fun resetPool(project: Project?) {
        scope.launch { resetPoolAndReport(project, "Agent pool reset.") }
    }

    /** Returns true when the pool was really reset; [done] leads the message either way. */
    private suspend fun resetPoolAndReport(project: Project?, done: String): Boolean {
        try {
            val stopped = when (AirPoolResetter.resetPool()) {
                AirPoolResetter.Scope.JUNIE_ONLY -> "Running Junie agents were stopped"
                AirPoolResetter.Scope.ALL_AGENTS -> "All running agents were stopped (this Air cannot stop Junie alone)"
            }
            notify(project, "$done $stopped; send a new message to start a fresh one.", NotificationType.INFORMATION)
            return true
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
        return false
    }

    fun addJar(jar: Path): JunieEntry = library.addJar(jar).also { fireChanged() }

    /**
     * Removes [entry] in the background. If Air is running it, Air is switched back
     * to the default Junie and its agents are stopped first, because a running Junie
     * keeps its files locked on Windows.
     */
    fun remove(entry: JunieEntry, project: Project?) {
        scope.launch(Dispatchers.IO) {
            if (activeEntry()?.id == entry.id) {
                AirJunie.setCommand(null)
                fireChanged()
                resetPoolAndReport(project, "Air now uses $DEFAULT_JUNIE.")
            }
            try {
                library.remove(entry)
            } catch (e: IOException) {
                notify(project, "${entry.name} was not removed. ${e.message} Reset the agent pool and try again.",
                    NotificationType.ERROR)
            }
            fireChanged()
        }
    }

    /** Downloads and unpacks [release] for this machine in a cancellable background task. */
    fun download(release: JunieRelease, project: Project?, onInstalled: (JunieEntry) -> Unit = {}) {
        val asset = release.assetFor(platform) ?: run {
            notify(project, "Release ${release.tag} has no build for ${platform.os}-${platform.arch}.", NotificationType.ERROR)
            return
        }
        if (!downloading.add(asset.name)) {
            notify(project, "Junie ${release.tag} is already being downloaded.", NotificationType.INFORMATION)
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
                    GitHubReleases.download(release.tag, asset, zip, indicator)
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

            override fun onFinished() { downloading.remove(asset.name) }

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
        private const val DEFAULT_JUNIE = "the default Junie from the ACP registry"
        private const val MANUAL_RESET =
            "Reset it by hand: Find Action > Debug ACP Chat > Agent Pool > Reset Pool (needs idea.is.internal=true)."

        fun getInstance(): JunieDeveloperService = service()
    }
}
