package dev.tracedown.common.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseFactory {

    /** Env override so constrained deployments can shrink per-service pools. */
    private val envPoolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull()

    fun init(
        jdbcUrl: String,
        username: String,
        password: String,
        maximumPoolSize: Int = envPoolSize ?: 10
    ): HikariDataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        })

        Database.connect(dataSource)

        return dataSource
    }
}
