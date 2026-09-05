package dev.tracedown.common.interceptors

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Marks a function as an interception point for the [Interceptors] system.
 *
 * The annotation is for discoverability — grep for `@Injectable` to find all
 * hookable operations. The runtime mechanism is [Interceptors.injectable].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Injectable(val operation: String)

/**
 * Context passed to interceptor hooks.
 *
 * Contains the relevant IDs for the current operation. [extra] is mutable
 * so `before` hooks can pass data to `after` hooks (e.g. a start timestamp).
 */
data class InterceptorContext(
    val orgId: UUID? = null,
    val userId: UUID? = null,
    val workspaceId: UUID? = null,
    val projectId: UUID? = null,
    val serviceId: UUID? = null,
    val extra: MutableMap<String, Any> = mutableMapOf(),
)

/**
 * Generic interceptor registry for injectable operations.
 *
 * Operations are marked with [Injectable] and wrap their body in [injectable].
 * External modules register [before]/[after] hooks by operation key at startup.
 * When no hooks are registered, [injectable] executes the block directly with
 * zero overhead.
 *
 * - **before** hooks run before the operation. Throw to block execution.
 * - **after** hooks run after the operation. Each receives the result and returns
 *   it (possibly modified). Hooks are chained: each hook's output feeds the next.
 */
object Interceptors {

    private val beforeHooks = mutableMapOf<String, MutableList<(InterceptorContext) -> Unit>>()
    private val afterHooks = mutableMapOf<String, MutableList<(InterceptorContext, Any?) -> Any?>>()

    /** Registers a before-hook for an operation. Multiple hooks per operation are supported. */
    fun before(operation: String, hook: (InterceptorContext) -> Unit) {
        beforeHooks.getOrPut(operation) { mutableListOf() }.add(hook)
    }

    /** Registers an after-hook for an operation. The hook receives the result and must return it (possibly modified). */
    fun after(operation: String, hook: (InterceptorContext, Any?) -> Any?) {
        afterHooks.getOrPut(operation) { mutableListOf() }.add(hook)
    }

    /**
     * Wraps an operation with before/after interception.
     *
     * Before hooks run first (can throw to block). Then [block] executes.
     * Then after hooks run in registration order, each receiving and returning
     * the result. The final result is returned to the caller.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <T> injectable(operation: String, ctx: InterceptorContext, block: () -> T): T {
        runBefore(operation, ctx)
        var result: Any? = block()
        result = runAfter(operation, ctx, result)
        return result as T
    }

    /**
     * Transaction-scoped variant of [injectable] for check-and-act operations.
     *
     * Opens a single database transaction and runs the before-hooks, [block],
     * and after-hooks all INSIDE it. This makes a before-hook's read (e.g. a
     * COUNT of existing resources) and [block]'s write atomic — no other
     * connection can slip a row in between the check and the insert.
     *
     * [block] must NOT open its own `transaction { }`; it already runs inside
     * this one and its Exposed calls use the current transaction.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <T> injectableInTx(
        operation: String,
        ctx: InterceptorContext,
        crossinline block: () -> T,
    ): T = transaction {
        runBefore(operation, ctx)
        var result: Any? = block()
        result = runAfter(operation, ctx, result)
        result as T
    }

    /** Runs all before-hooks for an operation. */
    @PublishedApi
    internal fun runBefore(operation: String, ctx: InterceptorContext) {
        beforeHooks[operation]?.forEach { it(ctx) }
    }

    /** Chains all after-hooks for an operation, returning the final result. */
    @PublishedApi
    internal fun runAfter(operation: String, ctx: InterceptorContext, result: Any?): Any? {
        val hooks = afterHooks[operation] ?: return result
        var current = result
        for (hook in hooks) {
            current = hook(ctx, current)
        }
        return current
    }

    /** Removes all registered hooks. Intended for testing only. */
    fun clearAll() {
        beforeHooks.clear()
        afterHooks.clear()
    }
}
