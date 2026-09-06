package dev.tracedown.gateway

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared Redis testcontainer for gateway integration tests.
 * Reused across all test classes via static reference.
 */
object TestRedis {
    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8-alpine"))
        .withExposedPorts(6379)

    init {
        container.start()
    }

    val url: String get() = "redis://${container.host}:${container.getMappedPort(6379)}"
}
