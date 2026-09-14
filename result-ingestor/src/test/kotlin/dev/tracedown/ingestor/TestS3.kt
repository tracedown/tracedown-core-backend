package dev.tracedown.ingestor

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Duration

/**
 * A shared SeaweedFS container: the S3-compatible body store the body-store
 * tests run against.
 *
 * It is started with an identity file, which is what makes it enforce SigV4 —
 * several tests turn on a wrong secret being refused, and refused with the code
 * S3 itself uses (`SignatureDoesNotMatch`, `InvalidAccessKeyId`), which is what
 * [dev.tracedown.common.storage.BodyStorageClient] maps to `invalid_credentials`.
 * It signs against whichever region the request names, so a store pointed at it
 * needs no region of its own.
 */
object TestS3 {
    const val USER = "tracedown-test-key"
    const val PASSWORD = "tracedown-test-secret"

    private const val S3_PORT = 8333

    /** One identity, holding the suite's credentials; `Admin` so it may create buckets. */
    private val IDENTITIES = """
        {
          "identities": [
            {
              "name": "tracedown-test",
              "credentials": [{ "accessKey": "$USER", "secretKey": "$PASSWORD" }],
              "actions": ["Admin"]
            }
          ]
        }
    """.trimIndent()

    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:3.98"))
        .withCopyToContainer(Transferable.of(IDENTITIES), "/etc/seaweedfs/s3.json")
        .withCommand("server", "-dir=/data", "-filer", "-s3", "-s3.config=/etc/seaweedfs/s3.json")
        .withExposedPorts(S3_PORT)
        // An anonymous GET answered with 403 is the identity file having been
        // read: until then the gateway either is not listening or lets anyone in.
        .waitingFor(
            Wait.forHttp("/").forPort(S3_PORT).forStatusCode(403)
                .withStartupTimeout(Duration.ofMinutes(3)),
        )

    init {
        container.start()
    }

    /** Loopback endpoint — allowed over http outside production, which is what tests run as. */
    val endpoint: String get() = "http://${container.host}:${container.getMappedPort(S3_PORT)}"

    val client: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
            .region(Region.of("auto"))
            .forcePathStyle(true)
            // The same pair BodyStorageClient sets: the SDK's default streaming
            // (trailer) checksums are not universally understood, R2 least of all.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build()
    }

    fun bucket(name: String) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(name).build())
        } catch (_: S3Exception) {
            client.createBucket(CreateBucketRequest.builder().bucket(name).build())
        }
    }

    fun put(bucket: String, key: String, content: ByteArray, contentType: String = "application/octet-stream") {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(content),
        )
    }

    /** The object's bytes — for asserting that something was NOT touched. */
    fun get(bucket: String, key: String): ByteArray =
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()

    fun exists(bucket: String, key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (_: S3Exception) {
        false
    }
}
