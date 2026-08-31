package dev.tracedown.scheduler.config

import io.ktor.server.application.ApplicationEnvironment

/**
 * Typed configuration for the probe-scheduler, loaded from application.conf.
 */
data class SchedulerConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val aesKey: String,
    val gatewayUrl: String,
    val consistencySweepIntervalSeconds: Long,
    val threadPoolSize: Int,
    /** Maximum number of pending dispatches in the dispatch queue. */
    val dispatchQueueSize: Int,
    /** Number of concurrent dispatcher coroutines draining the queue. */
    val dispatchWorkers: Int,
    /** When true, domain verification is a no-op and no probe limits apply. */
    val trustedDomainMode: Boolean,
    /**
     * Maximum JDBC connections this replica may hold. Derived from
     * [dispatchWorkers] and [threadPoolSize] unless overridden — see
     * [poolSizeFor].
     */
    val dbPoolSize: Int,
    val probe: ProbeConfig,
) {
    data class DatabaseConfig(
        val url: String,
        val user: String,
        val password: String,
    )

    /** Probe execution limits per Lace spec §10. */
    data class ProbeConfig(
        /** Default per-request timeout in ms when service has no override. */
        val defaultTimeoutMs: Int,
        /** System-wide maximum timeout in ms (executor.maxTimeoutMs). Service overrides clamped to this. */
        val maxTimeoutMs: Int,
        /** Maximum redirect hops (executor.maxRedirects). */
        val maxRedirects: Int,
        /**
         * Fleet-wide kill switch for payload sealing. Whether a given dispatch
         * is sealed is a per-agent setting; this only exists so an operator can
         * turn the whole mechanism off from the environment, without editing
         * rows, if it ever misbehaves in production.
         */
        val payloadEncryptionEnabled: Boolean = true,
        /**
         * How probe targets that resolve to private or internal addresses are
         * treated: `auto` (the default), `allow-private` or `public-only`. Kept
         * as the raw configured string — the effective mode also depends on the
         * deployment environment, which is resolved at startup.
         * See dev.tracedown.common.net.ProbeTargetPolicy.
         */
        val targetPolicy: String = dev.tracedown.common.net.ProbeTargetPolicy.AUTO,
    )

    companion object {
        /**
         * Headroom above the dispatch workers.
         *
         * Covers the connections nothing else accounts for and that are each
         * held by one thing at a time: the consistency sweep, the health
         * challenge round, the two shed recorders, the schedule bootstrap, and
         * a spare. The Quartz threads themselves are deliberately *not* counted
         * — they enqueue and hand off, and nothing they call opens a
         * transaction any more.
         */
        const val POOL_HEADROOM = 8

        /**
         * Connections this replica needs.
         *
         * The pool defaulted to ten while fifty dispatch workers each ran about
         * six blocking transactions per tick. Under a full tick the workers hit
         * Hikari's 30-second connection timeout, the exception was caught and
         * logged, and the probe produced nothing. The pool has to be sized to
         * the concurrency that was configured, not to a number picked
         * independently of it — every dispatch worker can want a connection at
         * the same instant, because that is what "concurrent dispatches" means.
         *
         * Raising `dispatchWorkers` therefore raises this too, and an operator
         * whose Postgres cannot serve it should lower the workers rather than
         * pin the pool below them.
         */
        fun poolSizeFor(dispatchWorkers: Int): Int = dispatchWorkers + POOL_HEADROOM

        /**
         * [poolSizeFor], unless an operator named a size explicitly.
         *
         * The override is how a constrained deployment caps what this replica
         * takes from a shared Postgres. It reintroduces the original defect if
         * used alone — lower `dispatchWorkers` alongside it, or the workers go
         * back to queueing for connections they cannot have.
         */
        fun resolvePoolSize(explicit: Int?, dispatchWorkers: Int): Int =
            explicit?.takeIf { it > 0 } ?: poolSizeFor(dispatchWorkers)

        fun load(environment: ApplicationEnvironment): SchedulerConfig {
            val config = environment.config
            val threadPoolSize = config.propertyOrNull("scheduler.threadPoolSize")
                ?.getString()?.toIntOrNull() ?: 10
            val dispatchWorkers = config.propertyOrNull("scheduler.dispatchWorkers")
                ?.getString()?.toIntOrNull() ?: 50
            return SchedulerConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                aesKey = config.property("platform.aesKey").getString(),
                gatewayUrl = config.propertyOrNull("scheduler.gatewayUrl")
                    ?.getString() ?: "http://localhost:8080",
                consistencySweepIntervalSeconds = config.propertyOrNull("scheduler.consistencySweepIntervalSeconds")
                    ?.getString()?.toLongOrNull() ?: 300,
                threadPoolSize = threadPoolSize,
                dispatchQueueSize = config.propertyOrNull("scheduler.dispatchQueueSize")
                    ?.getString()?.toIntOrNull() ?: 100_000,
                dispatchWorkers = dispatchWorkers,
                trustedDomainMode = config.propertyOrNull("scheduler.trustedDomainMode")
                    ?.getString()?.toBoolean() ?: true,
                dbPoolSize = resolvePoolSize(
                    explicit = System.getenv("DB_POOL_SIZE")?.toIntOrNull()
                        ?: config.propertyOrNull("scheduler.dbPoolSize")?.getString()?.toIntOrNull(),
                    dispatchWorkers = dispatchWorkers,
                ),
                probe = ProbeConfig(
                    defaultTimeoutMs = config.propertyOrNull("probe.defaultTimeoutMs")
                        ?.getString()?.toIntOrNull() ?: 30_000,
                    maxTimeoutMs = config.propertyOrNull("probe.maxTimeoutMs")
                        ?.getString()?.toIntOrNull() ?: 300_000,
                    maxRedirects = config.propertyOrNull("probe.maxRedirects")
                        ?.getString()?.toIntOrNull() ?: 10,
                    payloadEncryptionEnabled = config.propertyOrNull("probe.payloadEncryptionEnabled")
                        ?.getString()?.toBooleanStrictOrNull() ?: true,
                    targetPolicy = config.propertyOrNull("probe.targetPolicy")
                        ?.getString()?.takeIf { it.isNotBlank() }
                        ?: dev.tracedown.common.net.ProbeTargetPolicy.AUTO,
                ),
            )
        }
    }
}
