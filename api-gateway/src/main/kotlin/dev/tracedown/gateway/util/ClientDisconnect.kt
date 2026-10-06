package dev.tracedown.gateway.util

import io.ktor.utils.io.ConnectionClosedException

/**
 * Whether [e] is the client going away, anywhere in its causes.
 *
 * With `cancelCallOnClose`, a client that closes its connection while its call
 * is still in the pipeline cancels the call: a `CancellationException` caused
 * by Ktor's [ConnectionClosedException]. A healthcheck that reads `/ping` and
 * hangs up at once lands in that window now and then. Nothing failed on this
 * side and there is nobody left to answer, so it is not an unhandled error.
 *
 * Matched on the cause, not on `CancellationException` alone: a `withTimeout`
 * that runs out inside a handler throws a subclass of it, and that one is ours.
 */
fun isClientDisconnect(e: Throwable): Boolean =
    generateSequence(e) { cause -> cause.cause?.takeIf { it !== cause } }
        .any { it is ConnectionClosedException }
