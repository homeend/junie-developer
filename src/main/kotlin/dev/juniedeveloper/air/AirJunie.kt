package dev.juniedeveloper.air

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry

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
     * A `-Dair.junie.command.*` VM option wins over the Registry inside Air, so while
     * one is set this plugin cannot switch anything. Null when there is none.
     */
    fun vmOptionOverride(): String? = System.getProperty(registryKey)?.trim()?.takeIf { it.isNotEmpty() }
}
