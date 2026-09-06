package dev.tracedown.ingestor.config

import dev.tracedown.common.storage.S3Config
import io.ktor.server.application.ApplicationEnvironment

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Body-storage settings for the ingestor. The ingestor takes ownership of the
 * storage location by relocating each agent-uploaded body to a server-derived,
 * tenant-scoped key, so it must reach the same backend the agent writes to:
 * the shared filesystem volume (default `/data/bodies`, matching the agent's
 * `PROBE_AGENT_STORAGE_DIR`) or the same S3 bucket/prefix.
 */
data class StorageConfig(
    val filesystemRoot: String,
    val s3: S3Config?,
    val s3Bucket: String?,
    val s3Prefix: String,
)

data class IngestorConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val popTimeoutSeconds: Long,
    val storage: StorageConfig,
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): IngestorConfig {
            val config = env.config
            return IngestorConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                popTimeoutSeconds = config.propertyOrNull("ingestor.popTimeoutSeconds")
                    ?.getString()?.toLong() ?: 5L,
                storage = StorageConfig(
                    filesystemRoot = config.propertyOrNull("storage.filesystemRoot")
                        ?.getString() ?: "/data/bodies",
                    s3 = config.propertyOrNull("storage.s3.endpoint")?.getString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { endpoint ->
                            S3Config(
                                endpoint = endpoint,
                                accessKey = config.property("storage.s3.accessKey").getString(),
                                secretKey = config.property("storage.s3.secretKey").getString(),
                                region = config.propertyOrNull("storage.s3.region")?.getString()?.takeIf { it.isNotBlank() } ?: "auto",
                                timeoutSeconds = config.propertyOrNull("storage.s3.timeoutSeconds")?.getString()?.toLongOrNull() ?: 30L,
                            )
                        },
                    s3Bucket = config.propertyOrNull("storage.s3.bucket")?.getString()
                        ?.takeIf { it.isNotBlank() },
                    s3Prefix = config.propertyOrNull("storage.s3.prefix")?.getString() ?: "",
                ),
            )
        }
    }
}
