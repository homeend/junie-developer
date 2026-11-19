package dev.juniedeveloper.air

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Stops the agents Air keeps warm, so the next message starts a fresh Junie with
 * the newly selected command.
 *
 * This is the same call the internal "Debug ACP Chat > Agent Pool > Reset Pool"
 * button makes: `AcpDebugBackendApiImpl.resetPool()` runs
 * `evictAcpAgentClientPool(agentId = null)`, a public top-level suspend function in
 * Air's `intellij.air.backend.acp` module (file `AcpAgentClientPoolDebug.kt`).
 *
 * Air's modules are `visibility="internal"`, so a plugin cannot depend on them and
 * the function is reached by reflection. It evicts every pooled agent, not only
 * Junie, exactly like the button. Verified against Air 263.7307.291; if Air renames
 * things this throws [PoolResetUnavailable] and the caller falls back to telling the
 * user to reset by hand.
 */
object AirPoolResetter {
    private val LOG = logger<AirPoolResetter>()

    private const val POOL_DEBUG_CLASS = "com.intellij.air.acp.pool.AcpAgentClientPoolDebugKt"
    private const val EVICT_METHOD = "evictAcpAgentClientPool"

    // Classes that live in Air modules able to see the pool module; whichever is
    // already loaded hands us a class loader.
    private const val HTTP_HANDLER_EP = "com.intellij.httpRequestHandler"
    private const val AIR_DEBUG_HTTP_HANDLER = "com.intellij.air.acp.debug.server.AcpDebugHttpHandler"

    class PoolResetUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun resetPool() {
        val method = try {
            Class.forName(POOL_DEBUG_CLASS, true, airClassLoader())
                .getMethod(EVICT_METHOD, String::class.java, kotlin.coroutines.Continuation::class.java)
        } catch (e: PoolResetUnavailable) {
            throw e
        } catch (e: ReflectiveOperationException) {
            throw PoolResetUnavailable("This Air version has no $EVICT_METHOD", e)
        } catch (e: LinkageError) {
            throw PoolResetUnavailable("Air's pool classes could not be loaded", e)
        }
        try {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                method.invoke(null, null, continuation)
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        LOG.info("Air agent pool reset")
    }

    private fun airClassLoader(): ClassLoader {
        if (!AirJunie.isAirAvailable()) throw PoolResetUnavailable("The Air plugin is not installed or is disabled")
        val area = ApplicationManager.getApplication().extensionArea
        if (area.hasExtensionPoint(HTTP_HANDLER_EP)) {
            area.getExtensionPoint<Any>(HTTP_HANDLER_EP).extensionList
                .firstOrNull { it.javaClass.name == AIR_DEBUG_HTTP_HANDLER }
                ?.let { return it.javaClass.classLoader }
        }
        throw PoolResetUnavailable("Air's debug module was not found in this IDE")
    }
}
