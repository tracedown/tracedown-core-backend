package dev.tracedown.gateway

import com.typesafe.config.ConfigFactory
import dev.lacelang.lacetest.LaceTestSuite
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.lettuce.core.RedisClient
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.ServerSocket

/**
 * Lace integration test for rate limiting.
 *
 * Starts a separate gateway instance with rate limiting enabled at very low
 * limits (general: 5/60s, auth: 3/60s) to verify rate limit behavior.
 * Redis is flushed between each test script to ensure isolation.
 *
 * On its OWN Redis, not the shared [TestRedis]. Limiter keys are per client IP,
 * and every test class in this module authenticates from 127.0.0.1 — so on a
 * shared instance the 3-requests-per-minute auth ceiling set here applies to
 * LaceIntegrationTest and MeRoutesTest too, and their logins start coming back
 * throttled. The per-script flush below does not help: it runs between THIS
 * class's scripts, not between classes, and the classes interleave. That made
 * the whole module order-dependent, failing a shifting handful of auth/session
 * tests depending on which class reached Redis first.
 */
@Testcontainers
class RateLimitLaceTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_ratelimit_test")
            .withUsername("test")
            .withPassword("test")

        /** This class's own limiter store — see the note on the class. */
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)

        private val redisUrl: String
            get() = "redis://${redis.host}:${redis.getMappedPort(6379)}"

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0
        private lateinit var redisClient: RedisClient

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            serverPort = ServerSocket(0).use { it.localPort }
            redisClient = RedisClient.create(redisUrl)

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to redisUrl,
                "redis.b.url" to redisUrl,
                "redis.c.url" to "",
                // Enable rate limiting with low limits for testing
                "rateLimit.enabled" to "true",
                "rateLimit.general.maxRequests" to "5",
                "rateLimit.general.windowSeconds" to "60",
                "rateLimit.auth.maxRequests" to "3",
                "rateLimit.auth.windowSeconds" to "60",
            ))
            val mergedConfig = overrides.withFallback(ConfigFactory.load())

            val env = applicationEnvironment {
                config = HoconApplicationConfig(mergedConfig)
            }

            server = embeddedServer(Netty, env, configure = {
                connector { port = serverPort }
            })

            server.start(wait = false)
            Thread.sleep(2000)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            redisClient.shutdown()
        }

        /** Flush all rate limit keys from Redis between tests. */
        fun flushRateLimitKeys() {
            redisClient.connect().use { conn ->
                val keys = conn.sync().keys("rate:*")
                if (keys.isNotEmpty()) {
                    conn.sync().del(*keys.toTypedArray())
                }
            }
        }
    }

    @TestFactory
    fun rateLimitTests(): List<DynamicTest> {
        val baseDir = File("src/test/lace/rate-limit")
        val scripts = baseDir.listFiles()
            ?.filter { it.extension == "lace" }
            ?.sortedBy { it.name }
            ?: emptyList()

        return scripts.map { script ->
            DynamicTest.dynamicTest(script.name) {
                // Flush rate limit counters before each test for isolation
                flushRateLimitKeys()

                val suite = LaceTestSuite.builder()
                    .scriptsDir(script.parentFile.path)
                    .baseUrl("http://localhost:$serverPort")
                    .vars(emptyMap())
                    .build()

                val results = suite.dynamicTests()
                    .filter { it.displayName == script.name }
                results.forEach { it.executable.execute() }
            }
        }
    }
}
