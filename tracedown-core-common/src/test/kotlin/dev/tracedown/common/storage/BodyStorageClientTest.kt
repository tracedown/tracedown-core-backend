package dev.tracedown.common.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Confinement is the defense-in-depth guard: a client bound to a filesystem root
 * (or S3 bucket+prefix) refuses to touch any URI outside it, and relocation moves
 * bytes only to server-derived keys within that root.
 */
class BodyStorageClientTest {

    private fun confinedTo(root: Path) =
        BodyStorageClient(confinement = BodyConfinement(filesystemRoot = root))

    @Test
    fun `relocate moves a confined body to the server-derived key`(@TempDir root: Path) {
        val client = confinedTo(root)
        val source = root.resolve("agent-chosen/call_0_response.json")
        Files.createDirectories(source.parent)
        Files.writeString(source, "{\"body\":true}")

        val destKey = "org1/svc1/res1/call_0_response.json"
        val newUri = client.relocate("file://$source", destKey)

        assertEquals("file://${root.resolve(destKey)}", newUri)
        assertTrue(Files.exists(root.resolve(destKey)), "bytes moved to canonical key")
        assertFalse(Files.exists(source), "source removed after move")
        assertEquals("{\"body\":true}", Files.readString(root.resolve(destKey)))
    }

    @Test
    fun `relocate rejects an escape path outside the confined root`(@TempDir root: Path) {
        val client = confinedTo(root)
        // A compromised agent points at a platform config file outside the body store.
        val outside = Files.createTempFile("secret", ".conf").also { Files.writeString(it, "PLATFORM_AES_KEY=x") }
        try {
            assertThrows(StorageConfinementException::class.java) {
                client.relocate("file://$outside", "org1/svc1/res1/call_0.conf")
            }
            // The out-of-root file must be untouched (not read, not moved, not deleted).
            assertTrue(Files.exists(outside))
            assertEquals("PLATFORM_AES_KEY=x", Files.readString(outside))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `readBody rejects a file outside the confined root`(@TempDir root: Path) {
        val client = confinedTo(root)
        assertThrows(StorageConfinementException::class.java) {
            client.readBody("file:///etc/passwd")
        }
    }

    @Test
    fun `delete rejects a file outside the confined root`(@TempDir root: Path) {
        val client = confinedTo(root)
        val outside = Files.createTempFile("keep", ".txt").also { Files.writeString(it, "keep") }
        try {
            assertThrows(StorageConfinementException::class.java) { client.delete("file://$outside") }
            assertTrue(Files.exists(outside), "confinement must not delete out-of-root files")
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `unconfined client keeps legacy behavior`(@TempDir dir: Path) {
        // No confinement configured — reads whatever path it is given (legacy consumers).
        val client = BodyStorageClient()
        val file = dir.resolve("body.json")
        Files.writeString(file, "ok")
        val content = client.readBody("file://$file")
        assertTrue(content is BodyStorageClient.BodyContent.Inline)
        assertEquals("ok", (content as BodyStorageClient.BodyContent.Inline).content)
    }

    @Test
    fun `s3 confinement rejects a foreign bucket`() {
        val client = BodyStorageClient(
            s3Config = S3Config("https://x", "k", "s"),
            confinement = BodyConfinement(s3Bucket = "mine", s3KeyPrefix = "bodies"),
        )
        assertThrows(StorageConfinementException::class.java) {
            client.delete("s3://someone-elses-bucket/key")
        }
    }

    @Test
    fun `s3 delete failure propagates instead of reporting not-found`() {
        // Nothing listens on port 1: the delete fails at connect time. Retention
        // and purge only catch exceptions before dropping the row that names the
        // object, so a swallowed failure here orphaned the object in the bucket.
        val client = BodyStorageClient(s3Config = S3Config("http://127.0.0.1:1", "k", "s"))
        val e = assertThrows(StorageDeleteException::class.java) {
            client.delete("s3://bodies/org/svc/res/call_0_response.json")
        }
        assertTrue(e.message!!.contains("s3://bodies/org/svc/res/call_0_response.json"))
        assertTrue(e.cause != null, "the backend failure is kept as the cause")
    }
}
