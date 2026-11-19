package dev.juniedeveloper.air

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The switch itself: Air reads the Junie command from a Registry key every time it
 * starts an agent (`JunieCliSupport.commandOverride` in Air), so changing the key
 * needs no IDE restart, only fresh agent processes.
 */
object AirJunie {
    private val AIR_PLUGIN = PluginId.getId("com.intellij.air")

    val registryKey: String
        get() = if (SystemInfo.isWindows) "air.junie.command.windows" else "air.junie.command.unix"

    fun isAirAvailable(): Boolean =
        PluginManagerCore.getPlugin(AIR_PLUGIN) != null && !PluginManagerCore.isDisabled(AIR_PLUGIN)

    /** The command Air is currently told to use; empty means "Junie from the ACP registry". */
    fun currentCommand(): String = runCatching { Registry.get(registryKey).asString() }.getOrDefault("").trim()

    fun setCommand(executable: String?) {
        Registry.get(registryKey).setValue(executable.orEmpty())
    }

    /**
     * A `-Dair.junie.command.*` VM option wins over the Registry inside Air, even an
     * empty one, so while it is set this plugin cannot switch anything. Null when unset.
     */
    fun vmOptionOverride(): String? = System.getProperty(registryKey)

    /**
     * Air's "installed overlay" file. A Junie entry in it beats the command key, so
     * the selection made here would be ignored while it exists.
     */
    val overlayFile: Path
        get() = Path.of(System.getProperty("user.home"), ".jetbrains", "acp-registry-installed-overlay.json")

    fun overlayOverridesJunie(): Boolean = runCatching {
        Files.isRegularFile(overlayFile) && Files.readString(overlayFile).contains("\"acp.registry.junie\"")
    }.getOrDefault(false)

    /** Why the selection would not take effect right now, or null when nothing is in the way. */
    fun blocker(): String? = when {
        vmOptionOverride() != null ->
            "The IDE was started with a -D$registryKey VM option, which Air prefers over this selection. Remove it."
        overlayOverridesJunie() ->
            "$overlayFile has a Junie entry, which Air prefers over this selection. Remove that entry."
        else -> null
    }
}
