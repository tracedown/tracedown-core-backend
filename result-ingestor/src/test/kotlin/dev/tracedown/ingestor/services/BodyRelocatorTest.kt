package dev.tracedown.ingestor.services

import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The relocator turns an agent-chosen body path into a server-derived,
 * tenant-scoped storage URI — and refuses to dereference an escape path.
 */
class BodyRelocatorTest {

    private fun relocatorAt(root: Path) = BodyRelocator(
        BodyStorageClient(confinement = BodyConfinement(filesystemRoot = root)),
    )

    @Test
    fun `persisted uri is server-derived and tenant-scoped`(@TempDir root: Path) {
        val relocator = relocatorAt(root)
        // Agent uploaded here (its own choice of namespace/filename).
        val agentPath = root.resolve("deadbeef/call_0_response.json")
        Files.createDirectories(agentPath.parent)
        Files.writeString(agentPath, "{}")

        val org = UUID.randomUUID()
        val svc = UUID.randomUUID()
        val res = UUID.randomUUID()
        val uri = relocator.relocate("file://$agentPath", org, svc, res, callIndex = 0)

        requireNotNull(uri)
        // Tenant identifiers appear in the persisted URI; the agent's namespace does not.
        assertTrue(uri.contains("$org/$svc/$res/"), "uri must be tenant-scoped: $uri")
        assertTrue(uri.endsWith("/call_0_response.json"))
        assertFalse(uri.contains("deadbeef"), "agent-chosen namespace must not survive")
        // The bytes now live at the canonical key, and the agent path is gone.
        assertTrue(Files.exists(root.resolve("$org/$svc/$res/call_0_response.json")))
        assertFalse(Files.exists(agentPath))
    }

    @Test
    fun `agent-supplied escape path is ignored and left untouched`(@TempDir root: Path) {
        val relocator = relocatorAt(root)
        val secret = Files.createTempFile("application", ".conf")
            .also { Files.writeString(it, "PLATFORM_AES_KEY=leak") }
        try {
            val uri = relocator.relocate(
                "file://$secret", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
            )
            // Escape path => no persisted URI, and the secret file is not touched.
            assertNull(uri)
            assertTrue(Files.exists(secret))
            assertTrue(Files.readString(secret).contains("PLATFORM_AES_KEY"))
            // Nothing was written into the body store.
            assertFalse(Files.walk(root).use { s -> s.anyMatch { Files.isRegularFile(it) } })
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `missing source body yields no uri`(@TempDir root: Path) {
        val relocator = relocatorAt(root)
        val uri = relocator.relocate(
            "file://${root.resolve("gone/call_0.json")}",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
        )
        assertNull(uri)
    }

    @Test
    fun `relocating a body that already moved is the same move`(@TempDir root: Path) {
        // An earlier attempt at the same result moved it, then its transaction
        // did not commit: the retry finds the body where it belongs.
        val client = BodyStorageClient(confinement = BodyConfinement(filesystemRoot = root))
        val agentPath = root.resolve("agent/call_0.json")
        Files.createDirectories(agentPath.parent)
        Files.writeString(agentPath, "{\"kept\":true}")
        val first = client.relocate("file://$agentPath", "org/svc/res/call_0.json")
        val again = client.relocate("file://$agentPath", "org/svc/res/call_0.json")
        assertTrue(first == again, "$first / $again")
        assertTrue(Files.readString(root.resolve("org/svc/res/call_0.json")).contains("kept"))
    }

    @Test
    fun `an object that already moved is the same move`() {
        val bucket = "relocate-${UUID.randomUUID().toString().take(8)}"
        dev.tracedown.ingestor.TestS3.bucket(bucket)
        val client = BodyStorageClient(
            s3Config = dev.tracedown.common.storage.S3Config(
                endpoint = dev.tracedown.ingestor.TestS3.endpoint,
                accessKey = dev.tracedown.ingestor.TestS3.USER,
                secretKey = dev.tracedown.ingestor.TestS3.PASSWORD,
            ),
            confinement = BodyConfinement(s3Bucket = bucket),
        )
        dev.tracedown.ingestor.TestS3.put(bucket, "agent/call_0.json", "{}".toByteArray())
        val first = client.relocate("s3://$bucket/agent/call_0.json", "org/svc/res/call_0.json")
        val again = client.relocate("s3://$bucket/agent/call_0.json", "org/svc/res/call_0.json")
        assertTrue(first == again, "$first / $again")
        assertTrue(dev.tracedown.ingestor.TestS3.get(bucket, "org/svc/res/call_0.json").isNotEmpty())
    }
}
