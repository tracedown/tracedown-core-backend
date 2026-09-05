package dev.tracedown.common.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.MaintNotificationsConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Builds the platform's Redis connections.
 *
 * Two behaviours here exist because the library defaults are wrong for a
 * service that must survive its store restarting:
 *
 * **Command timeout.** Lettuce's default is 60 seconds. A Redis that vanishes
 * *after* the connection was established does not fail commands — it queues
 * them and lets them sit for a minute. On the gateway that minute is spent
 * inside the synchronous `INCR` the rate limiter runs on *every* request,
 * before any of its fail-open / fail-closed handling is reached, so the whole
 * API stalls rather than degrades. [COMMAND_TIMEOUT] caps it at a few seconds:
 * a brief reconnect is still absorbed (auto-reconnect is on and commands are
 * accepted while disconnected), a real outage surfaces as an exception the
 * callers already handle.
 *
 * **Blocking reads are exempt.** `BRPOP` / `BLMOVE` legitimately hold a
 * connection open for their own pop timeout, which is longer than the command
 * budget above. Those consumers must use [createBlockingConnection], which
 * raises the timeout on that one connection to the pop timeout plus headroom.
 * A blocking read must also never share a connection with anything else —
 * commands pipelined behind it wait for it — so a dedicated connection is
 * required either way.
 *
 * **Initial connect.** `connect()` throws when the server is unreachable, and
 * services call it during module init, before Ktor binds. A Redis restart in
 * the middle of a deploy therefore turned into a restart loop rather than a
 * pause. The connect is retried for a bounded window instead. Callers that can
 * run without Redis (the gateway) should additionally hold their connection
 * behind `by lazy` so an outage never reaches module init at all.
 */
object RedisFactory {

    private val log = LoggerFactory.getLogger(RedisFactory::class.java)

    /** Per-command budget for ordinary (non-blocking) commands. */
    val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(
        System.getenv("REDIS_COMMAND_TIMEOUT_SECONDS")?.toLongOrNull()?.coerceAtLeast(1L) ?: 3L
    )

    /** Headroom added to a blocking read's own pop timeout before it is called late. */
    private val BLOCKING_TIMEOUT_MARGIN: Duration = Duration.ofSeconds(5)

    /** Bounded retry window for the initial connect, so a store restart is a pause not a crash. */
    private val CONNECT_ATTEMPTS: Int =
        System.getenv("REDIS_CONNECT_ATTEMPTS")?.toIntOrNull()?.coerceAtLeast(1) ?: 30
    private val CONNECT_RETRY_DELAY: Duration = Duration.ofMillis(
        System.getenv("REDIS_CONNECT_RETRY_MS")?.toLongOrNull()?.coerceAtLeast(50L) ?: 1000L
    )

    /** Creates a standard Redis connection for commands. */
    fun createConnection(redisUrl: String): StatefulRedisConnection<String, String> =
        connectWithRetry(redisUrl) { it.connect() }

    /**
     * Creates a connection dedicated to a blocking read (`BRPOP` / `BLMOVE`).
     *
     * [popTimeoutSeconds] is the pop timeout the consumer passes to the command;
     * the connection's command timeout is set above it so a healthy, idle queue
     * is never mistaken for an unresponsive server. Use one of these per
     * consumer and never issue other commands on it.
     */
    fun createBlockingConnection(
        redisUrl: String,
        popTimeoutSeconds: Long,
    ): StatefulRedisConnection<String, String> {
        val connection = connectWithRetry(redisUrl) { it.connect() }
        connection.timeout = Duration.ofSeconds(popTimeoutSeconds.coerceAtLeast(0)) + BLOCKING_TIMEOUT_MARGIN
        return connection
    }

    /** Creates a pub/sub Redis connection for subscribe/publish. */
    fun createPubSubConnection(redisUrl: String): StatefulRedisPubSubConnection<String, String> =
        connectWithRetry(redisUrl) { it.connectPubSub() }

    private fun <T> connectWithRetry(
        redisUrl: String,
        connect: (RedisClient) -> T,
    ): T {
        // Lettuce 7 dropped AbstractRedisClient.setDefaultTimeout; the client's
        // default now comes off the RedisURI, with the same meaning — it seeds
        // each new connection's timeout, which [createBlockingConnection] then
        // raises on its own connection. (ClientOptions.timeoutOptions with a
        // fixedTimeout is *not* the equivalent: a fixed timeout overrides the
        // connection's own and would cut the blocking reads short.)
        val redisUri = RedisURI.create(redisUrl)
        redisUri.timeout = COMMAND_TIMEOUT
        val client = RedisClient.create(redisUri)
        // Auto-reconnect with commands accepted while the link is down: a
        // sub-second reconnect stays invisible. COMMAND_TIMEOUT is what stops
        // that from becoming an unbounded wait.
        client.options = ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.ACCEPT_COMMANDS)
            // Lettuce 7 handshakes `CLIENT MAINT_NOTIFICATIONS` by default —
            // a hosted-Redis maintenance feature no open-source server has.
            // Against the redis:7 this platform ships, every connect answers
            // "ERR unknown subcommand" and logs the failure with a stack trace.
            // Harmless (the connection is usable either way) but alarming in
            // the logs, so don't ask for it.
            .maintNotificationsConfig(MaintNotificationsConfig.disabled())
            .build()

        var lastError: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                return connect(client)
            } catch (e: Exception) {
                lastError = e
                if (attempt == CONNECT_ATTEMPTS) break
                log.warn(
                    "redis not reachable (attempt {}/{}): {} — retrying in {}ms",
                    attempt, CONNECT_ATTEMPTS, e.message, CONNECT_RETRY_DELAY.toMillis(),
                )
                Thread.sleep(CONNECT_RETRY_DELAY.toMillis())
            }
        }
        runCatching { client.shutdown() }
        throw IllegalStateException(
            "redis unreachable after $CONNECT_ATTEMPTS attempts", lastError,
        )
    }
}
