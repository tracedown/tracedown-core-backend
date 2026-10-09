package dev.tracedown.gateway.util

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.contentLength
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which request bodies the gateway keeps so they can be read more than once —
 * Ktor's DoubleReceive, installed once, here, for the whole application.
 *
 * Core keeps the body of a key-authenticated POST carrying an
 * `Idempotency-Key` (read for its fingerprint, then by its handler; see
 * [Idempotency]) and nothing else. A host that needs another body read twice
 * registers a predicate with [cacheAlso]; it must not install DoubleReceive
 * itself — a second installation stops the gateway from starting, and Core's
 * filter would leave the host's bodies uncached anyway.
 *
 * Every kept body is still bounded by the request-body cap: the cap's read runs
 * on every read of a kept body, and a body declared larger than the cap is
 * refused before anything reads it.
 */
object RequestBodyCache {

    private val predicates = CopyOnWriteArrayList<(ApplicationCall) -> Boolean>()

    private var maxBodyBytes: Long = AppConfig.DEFAULT_MAX_REQUEST_BODY_BYTES

    /** Also keeps the body of every call [predicate] accepts. May be called at any time. */
    fun cacheAlso(predicate: (ApplicationCall) -> Boolean) {
        predicates += predicate
    }

    /** Removes every registered predicate. For tests. */
    fun clearAll() = predicates.clear()

    /**
     * Installs DoubleReceive for the bodies Core and the registered
     * predicates ask for, up to [maxBodyBytes] declared. A body sent without
     * a length is kept as far as the cap's read takes it — DoubleReceive's own
     * `maxSize` would refuse to keep it at all, and the second read would fail.
     */
    fun install(application: Application, maxBodyBytes: Long) {
        this.maxBodyBytes = maxBodyBytes
        application.install(DoubleReceive) {
            excludeFromCache { call, _ -> !keeps(call) }
        }
    }

    private fun keeps(call: ApplicationCall): Boolean =
        (call.request.contentLength() ?: 0L) <= maxBodyBytes &&
            (Idempotency.carriesKey(call) || predicates.any { it(call) })
}
