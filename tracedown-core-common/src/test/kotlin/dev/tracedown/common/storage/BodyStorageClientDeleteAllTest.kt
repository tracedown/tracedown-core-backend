package dev.tracedown.common.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A URI the client refuses on confinement names a body outside platform storage.
 * Bulk deletion skips it rather than reporting it failed — a failure would be
 * queued for retry and retried forever, since the platform will never delete it.
 */
class BodyStorageClientDeleteAllTest {

    @Test
    fun `confinement-refused URIs are reported as refused, not failed`(@TempDir root: Path, @TempDir elsewhere: Path) {
        val client = BodyStorageClient(confinement = BodyConfinement(filesystemRoot = root))
        val own = root.resolve("own.json").also { Files.writeString(it, "{}") }
        val foreign = elsewhere.resolve("foreign.json").also { Files.writeString(it, "{}") }

        val outcome = client.deleteAll(
            listOf("file://$own", "file://$foreign", "s3://someone-elses-bucket/key.json"),
        )

        assertEquals(emptyMap<String, String?>(), outcome.failed, "a refusal is not a store failure")
        assertEquals(
            setOf("file://$foreign", "s3://someone-elses-bucket/key.json"), outcome.refused.keys,
            "both refusals are named, so a caller can tell them from a successful delete",
        )
        assertFalse(Files.exists(own), "the platform's own body is deleted")
        assertTrue(Files.exists(foreign), "a body outside the root is untouched")
    }

    @Test
    fun `an s3 body with no S3 backend configured fails that key, not the call`() {
        // Unconfined, so the URI is accepted — but there is no client to send it with.
        val outcome = BodyStorageClient().deleteAll(listOf("s3://bodies/key.json"))

        assertEquals(setOf("s3://bodies/key.json"), outcome.failed.keys)
        assertTrue(outcome.refused.isEmpty(), "an unconfined client refuses nothing")
    }
}
