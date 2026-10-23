package dev.tracedown.common.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseFactory {

    /** Env override so constrained deployments can shrink per-service pools. */
    private val envPoolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull()

    /**
     * How long a session may sit inside a transaction doing nothing before
     * PostgreSQL ends it (`idle_in_transaction_session_timeout`), in seconds;
     * `DB_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`, default 60, 0 for no limit.
     *
     * An open transaction is not free for anyone else: it holds back vacuum,
     * and it holds back the event feed, which reads only what was written
     * below the oldest transaction still open. A connection a bug or a stalled
     * thread leaves in an open transaction would otherwise hold both forever.
     */
    val idleInTransactionTimeoutSeconds: Int =
        (System.getenv("DB_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS")?.toIntOrNull() ?: 60).coerceIn(0, MAX_TIMEOUT_SECONDS)

    /** The largest timeout PostgreSQL takes, in seconds (its limit is in milliseconds, an int). */
    private const val MAX_TIMEOUT_SECONDS = 2_147_483

    /**
     * [options] with the session's idle-in-transaction timeout added — as a
     * startup parameter, set when the session opens. Not an init statement:
     * with auto-commit off, a statement on a fresh connection opens a
     * transaction the pool then holds idle, and the first borrower's rollback
     * would undo it.
     */
    private val URL_OPTIONS = Regex("([?&])options=([^&]*)")

    /** [jdbcUrl] with the timeout merged into the `options` it names, when it names any. */
    fun withIdleTimeoutInUrl(jdbcUrl: String, seconds: Int): String {
        if (seconds <= 0) return jdbcUrl
        val match = URL_OPTIONS.find(jdbcUrl) ?: return jdbcUrl
        val merged = withIdleTimeout(java.net.URLDecoder.decode(match.groupValues[2], Charsets.UTF_8), seconds) ?: return jdbcUrl
        return jdbcUrl.replaceRange(match.range, match.groupValues[1] + "options=" + java.net.URLEncoder.encode(merged, Charsets.UTF_8))
    }

    fun withIdleTimeout(options: String?, seconds: Int): String? {
        if (seconds <= 0) return options
        val setting = "-c idle_in_transaction_session_timeout=${seconds}s"
        return if (options.isNullOrBlank()) setting else "$options $setting"
    }

    /** The size of the pool [init] made, for callers that bound their own share of it. */
    @Volatile
    var poolSize: Int = 10
        private set

    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
        maximumPoolSize: Int = envPoolSize ?: 10
    ): HikariDataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = withIdleTimeoutInUrl(jdbcUrl, idleInTransactionTimeoutSeconds)
            this.username = username
            this.password = password
            this.maximumPoolSize = maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // A URL that names its own `options` has them merged there (the
            // URL's would win over a property); otherwise they go in as one.
            if (!URL_OPTIONS.containsMatchIn(jdbcUrl)) {
                withIdleTimeout(null, idleInTransactionTimeoutSeconds)?.let { addDataSourceProperty("options", it) }
            }
            validate()
        })

        Database.connect(dataSource)
        poolSize = maximumPoolSize

        // Initialise every table object here, on this one thread, before the
        // caller launches jobs or serves requests: touched concurrently for
        // the first time, the tables' mutual references deadlock the JVM's
        // class initialisation with no error and no log. See [Tables].
        dev.tracedown.common.models.Tables.preload()

        return dataSource
    }
}
