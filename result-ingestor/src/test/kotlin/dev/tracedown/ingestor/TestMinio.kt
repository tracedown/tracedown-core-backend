package dev.tracedown.ingestor

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream

/** A shared MinIO container: the S3-compatible body store for body-store tests. */
object TestMinio {
    const val USER = "minioadmin"
    const val PASSWORD = "minioadmin-secret"

    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
        .withCommand("server", "/data")
        .withEnv("MINIO_ROOT_USER", USER)
        .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))

    init {
        container.start()
    }

    /** Loopback endpoint — allowed over http outside production, which is what tests run as. */
    val endpoint: String get() = "http://${container.host}:${container.getMappedPort(9000)}"

    val client: MinioClient by lazy { MinioClient.builder().endpoint(endpoint).credentials(USER, PASSWORD).build() }

    fun bucket(name: String) {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(name).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(name).build())
        }
    }

    fun put(bucket: String, key: String, content: ByteArray, contentType: String = "application/octet-stream") {
        client.putObject(
            PutObjectArgs.builder().bucket(bucket).`object`(key)
                .stream(ByteArrayInputStream(content), content.size.toLong(), -1L)
                .contentType(contentType)
                .build(),
        )
    }

    fun exists(bucket: String, key: String): Boolean = try {
        client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build())
        true
    } catch (e: ErrorResponseException) {
        false
    }
}
