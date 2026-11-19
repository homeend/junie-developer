package dev.juniedeveloper.air

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Stops the agents Air keeps running, so the next message starts a fresh Junie
 * with the newly selected command. Open chats lose their old process too.
 *
 * Preferred: `AcpAgentClientPool.evictClientsMatching(DEBUG_POOL_RESET) { key is Junie }`,
 * the production eviction path restricted to Junie, so other agents keep running.
 * Fallback: `evictAcpAgentClientPool(null)`, exactly what the internal
 * "Debug ACP Chat > Agent Pool > Reset Pool" button calls; it evicts every agent.
 *
 * `evictAcpAgentClientPool("acp.registry.junie")` is deliberately NOT used: its
 * non-null branch is a test helper that skips the chats' disconnect callbacks.
 *
 * Air's modules are `visibility="internal"` and its API is `@ApiStatus.Internal`,
 * so everything is reached by reflection and checked before anything is called.
 * Verified against Air 263.7307.291 (bytecode) and the Air source. If Air renames
 * things this throws [PoolResetUnavailable] and the caller tells the user to
 * reset by hand. See docs/air-internals.md.
 */
object AirPoolResetter {
    private val LOG = logger<AirPoolResetter>()

    const val JUNIE_AGENT_ID = "acp.registry.junie"

    private const val POOL_CLASS = "com.intellij.air.acp.pool.AcpAgentClientPool"
    private const val POOL_DEBUG_CLASS = "com.intellij.air.acp.pool.AcpAgentClientPoolDebugKt"
    private const val REASON_CLASS = "com.intellij.air.acp.AcpQuarantineReason"
    private const val CLIENT_KEY_CLASS = "com.intellij.air.acp.pool.AcpClientKey"
    private const val AGENT_ID_CLASS = "com.intellij.air.acp.api.AcpAgentId"

    // A class in an Air module that can see the pool module; it hands us a class loader.
    private const val HTTP_HANDLER_EP = "com.intellij.httpRequestHandler"
    private const val AIR_DEBUG_HTTP_HANDLER = "com.intellij.air.acp.debug.server.AcpDebugHttpHandler"

    enum class Scope { JUNIE_ONLY, ALL_AGENTS }

    class PoolResetUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Resets the pool and says how much was reset. */
    suspend fun resetPool(): Scope {
        val loader = airClassLoader()
        val junieOnly = try {
            junieOnlyEviction(loader)
        } catch (e: ReflectiveOperationException) {
            LOG.info("Junie-only eviction is not available in this Air, resetting the whole pool", e)
            null
        } catch (e: LinkageError) {
            LOG.info("Junie-only eviction is not available in this Air, resetting the whole pool", e)
            null
        }
        if (junieOnly != null) {
            callSuspend { junieOnly(it) }
            LOG.info("Air agent pool: Junie clients evicted")
            return Scope.JUNIE_ONLY
        }
        val evictAll = try {
            Class.forName(POOL_DEBUG_CLASS, true, loader)
                .getMethod("evictAcpAgentClientPool", String::class.java, Continuation::class.java)
        } catch (e: ReflectiveOperationException) {
            throw PoolResetUnavailable("this Air version has no known pool reset function", e)
        } catch (e: LinkageError) {
            throw PoolResetUnavailable("Air's pool classes could not be loaded", e)
        }
        callSuspend { evictAll.invoke(null, null, it) }
        LOG.info("Air agent pool: all clients evicted")
        return Scope.ALL_AGENTS
    }

    /** Resolves every reflective piece up front, so nothing can fail half-way through an eviction. */
    private fun junieOnlyEviction(loader: ClassLoader): (Continuation<Any?>) -> Any? {
        val poolClass = Class.forName(POOL_CLASS, true, loader)
        val reasonClass = Class.forName(REASON_CLASS, true, loader)
        val reason = reasonClass.enumConstants.first { (it as Enum<*>).name == "DEBUG_POOL_RESET" }
        val getAgentId: Method = Class.forName(CLIENT_KEY_CLASS, true, loader).getMethod("getAgentId")
        val getFullId: Method = Class.forName(AGENT_ID_CLASS, true, loader).getMethod("getFullId")
        val evict = poolClass.getMethod("evictClientsMatching",
            reasonClass, Function2::class.java, Continuation::class.java)
        val companion = poolClass.getField("Companion").get(null)
        val pool = companion.javaClass.getMethod("getInstance").invoke(companion)

        // Air's `suspend (AcpClientKey) -> Boolean`, which is a Function2 on the JVM.
        val isJunie = object : Function2<Any?, Continuation<Boolean>, Any?> {
            override fun invoke(key: Any?, continuation: Continuation<Boolean>): Any? =
                getFullId.invoke(getAgentId.invoke(key)) == JUNIE_AGENT_ID
        }
        return { continuation -> evict.invoke(pool, reason, isJunie, continuation) }
    }

    private suspend fun callSuspend(call: (Continuation<Any?>) -> Any?) {
        try {
            suspendCoroutineUninterceptedOrReturn<Any?> { call(it) }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun airClassLoader(): ClassLoader {
        if (!AirJunie.isAirAvailable()) throw PoolResetUnavailable("the Air plugin is not installed or is disabled")
        val area = ApplicationManager.getApplication().extensionArea
        if (area.hasExtensionPoint(HTTP_HANDLER_EP)) {
            area.getExtensionPoint<Any>(HTTP_HANDLER_EP).extensionList
                .firstOrNull { it.javaClass.name == AIR_DEBUG_HTTP_HANDLER }
                ?.let { return it.javaClass.classLoader }
        }
        throw PoolResetUnavailable("Air's debug module was not found in this IDE")
    }
}
