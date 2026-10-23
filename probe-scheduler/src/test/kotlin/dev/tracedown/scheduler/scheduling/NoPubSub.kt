package dev.tracedown.scheduler.scheduling

import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.lang.reflect.Proxy

/**
 * A pub/sub connection that is never used — for a [ScheduleSyncService]
 * whose messages a test hands to `onMessage` itself. Every call answers
 * nothing.
 */
@Suppress("UNCHECKED_CAST")
fun noPubSub(): StatefulRedisPubSubConnection<String, String> =
    Proxy.newProxyInstance(
        StatefulRedisPubSubConnection::class.java.classLoader,
        arrayOf(StatefulRedisPubSubConnection::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as StatefulRedisPubSubConnection<String, String>
