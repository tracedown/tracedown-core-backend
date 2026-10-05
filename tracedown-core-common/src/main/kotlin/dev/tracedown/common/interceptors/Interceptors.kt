package dev.tracedown.common.interceptors

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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
     * and after-hooks all INSIDE it, so a hook's refusal undoes the block and a
     * failed block undoes whatever a hook wrote.
     *
     * One transaction is not, by itself, mutual exclusion. The pool runs at
     * REPEATABLE READ, where a transaction reads from the snapshot taken at its
     * first statement: two of these running at once each count from their own
     * snapshot and both find room. A check-and-act that has to hold under
     * concurrency needs two things from its block — a row lock that makes the
     * contenders take turns, and [isolation] set to READ COMMITTED, so the one
     * that waited counts again from what the other committed rather than from
     * the snapshot it took before waiting.
     *
     * [block] must NOT open its own `transaction { }`; it already runs inside
     * this one and its Exposed calls use the current transaction.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <T> injectableInTx(
        operation: String,
        ctx: InterceptorContext,
        isolation: Int? = null,
        crossinline block: () -> T,
    ): T {
        // A nested call joins the open transaction and the requested level is
        // silently ignored — the check-and-act it was asked for would then be
        // racy again. Say so where it is written rather than where it fails.
        check(isolation == null || TransactionManager.currentOrNull() == null) {
            "injectableInTx(\"$operation\") asked for an isolation level inside an open transaction"
        }
        return transaction(transactionIsolation = isolation) {
            runBefore(operation, ctx)
            var result: Any? = block()
            result = runAfter(operation, ctx, result)
            result as T
        }
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
