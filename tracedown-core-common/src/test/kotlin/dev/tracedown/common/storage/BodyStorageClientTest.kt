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
    fun `a symlink under the root is refused, however it is reached`(@TempDir root: Path) {
        val client = confinedTo(root)
        val outside = Files.createTempDirectory("outside")
        val secret = outside.resolve("application.conf").also { Files.writeString(it, "DATABASE_PASSWORD=hunter2") }
        try {
            // The classic swap: a directory under the root replaced by a link to
            // somewhere else, so a path that *looks* confined resolves outside.
            val linkedDir = root.resolve("run-1")
            Files.createSymbolicLink(linkedDir, outside)
            assertThrows(StorageConfinementException::class.java) {
                client.readBody("file://${linkedDir.resolve("application.conf")}")
            }
            assertThrows(StorageConfinementException::class.java) {
                client.readBytes("file://${linkedDir.resolve("application.conf")}", 1024)
            }
            assertThrows(StorageConfinementException::class.java) { client.delete("file://$linkedDir/application.conf") }

            // And the final component as a link, which NOFOLLOW covers.
            val linkedFile = root.resolve("call_0.json")
            Files.createSymbolicLink(linkedFile, secret)
            assertThrows(StorageConfinementException::class.java) { client.readBody("file://$linkedFile") }

            assertEquals("DATABASE_PASSWORD=hunter2", Files.readString(secret), "nothing outside was read or removed")
        } finally {
            Files.deleteIfExists(secret)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `a store root that is itself a symlink works`(@TempDir base: Path) {
        // An operator's mount is very often a link. The root is resolved once,
        // when the confinement is built; only what lies *under* it is refused.
        val real = base.resolve("real-store").also { Files.createDirectories(it) }
        val link = base.resolve("store-link")
        Files.createSymbolicLink(link, real)
        val client = confinedTo(link)
        val body = real.resolve("run-1/call_0.json")
        Files.createDirectories(body.parent)
        Files.writeString(body, "hello")

        assertTrue(client.contains("file://${link.resolve("run-1/call_0.json")}"))
        assertEquals(
            BodyStorageClient.BodyContent.Inline("hello"),
            client.readBody("file://${real.resolve("run-1/call_0.json")}"),
        )
    }

    @Test
    fun `a filesystem read is capped without reading the whole file`(@TempDir root: Path) {
        val client = confinedTo(root)
        val big = root.resolve("big.bin")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(5_000) }

        val read = client.readBytes("file://$big", maxBytes = 1_000)

        assertTrue(read is BodyStorageClient.StoredBody.TooLarge, "got $read")
        assertEquals(5_000L, (read as BodyStorageClient.StoredBody.TooLarge).sizeBytes)
    }

    @Test
    fun `readBody refuses an over-size body rather than buffering it`(@TempDir root: Path) {
        val client = confinedTo(root)
        val big = root.resolve("big.bin")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(BodyStoreRegistry.MAX_BODY_BYTES + 1) }

        assertThrows(BodyTooLargeException::class.java) { client.readBody("file://$big") }
    }

    @Test
    fun `contains refuses keys that are not plain object keys`() {
        val client = BodyStorageClient(confinement = BodyConfinement(s3Bucket = "bodies", s3KeyPrefix = "agents/eu"))
        assertTrue(client.contains("s3://bodies/agents/eu/run-1/call_0.json"))
        for (key in listOf(
            // Dot and empty segments name one object to some stores and another
            // to others, and each dresses up a location outside the prefix.
            "agents/eu/../../secrets/key",
            "agents/eu/./call_0.json",
            "agents/eu//call_0.json",
            "agents/eu/../eu-other/call_0.json",
            "agents\\eu\\call_0.json",
            // The neighbouring prefix that merely starts with the same letters.
            "agents/eu-other/call_0.json",
        )) {
            assertFalse(client.contains("s3://bodies/$key"), key)
        }
    }

    @Test
    fun `a prefix boundary is a path boundary, not a string one`() {
        val client = BodyStorageClient(confinement = BodyConfinement(s3Bucket = "bodies", s3KeyPrefix = "bodies"))
        assertTrue(client.contains("s3://bodies/bodies/call_0.json"))
        assertFalse(client.contains("s3://bodies/bodies-evil/call_0.json"))
        assertFalse(client.contains("s3://bodies/bodiesevil"))
    }

    @Test
    fun `an unconfined scheme keeps its old behaviour while the other stays confined`(@TempDir root: Path) {
        // The aggregate-worker upgraded without STORAGE_S3_BUCKET: s3 deletions
        // must keep working, file ones stay confined to the root it was given.
        val client = BodyStorageClient(
            confinement = BodyConfinement(filesystemRoot = root, unconfinedSchemes = setOf("s3")),
        )
        assertTrue(client.contains("s3://any-bucket/any/key"))
        assertFalse(client.contains("file:///etc/passwd"))
    }

    @Test
    fun `neither the store config nor the input prints its secret`() {
        val config = S3Config("https://s3.example.com", "AKIAEXAMPLE", "super-secret-key")
        assertFalse(config.toString().contains("super-secret-key"), config.toString())
        val input = BodyStoreInput(name = "eu", kind = "s3", secretAccessKey = "super-secret-key")
        assertFalse(input.toString().contains("super-secret-key"), input.toString())
        assertTrue(input.toString().contains("eu"))
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

    @Test
    fun `s3 delete gives up on a store that accepts and never answers`() {
        // A socket that completes the TCP handshake and then says nothing is
        // what a stalled store looks like. The SDK's stock client waits far
        // longer than a retention tick for it; the configured timeout has to
        // win instead, because retention deletes bodies one after another and
        // a single hung call parked the whole job with nothing in the log.
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val accepted = mutableListOf<java.net.Socket>()
        val acceptor = Thread {
            try {
                while (true) accepted.add(server.accept())
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            val client = BodyStorageClient(
                s3Config = S3Config("http://127.0.0.1:${server.localPort}", "k", "s", timeoutSeconds = 1),
            )
            val started = System.nanoTime()
            assertThrows(StorageDeleteException::class.java) {
                client.delete("s3://bodies/org/svc/res/call_0_response.json")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 15_000, "gave up after $elapsedMs ms, expected the 1s timeout to apply")
        } finally {
            accepted.forEach { runCatching { it.close() } }
            server.close()
            acceptor.interrupt()
        }
    }

    @Test
    fun `s3 bulk delete sends a page of keys as one request and reports nothing on success`() {
        // Retention hands a whole page of bodies over at once; at one round
        // trip per object a page cost a minute of wall clock, so the page must
        // travel as a single DeleteObjects request.
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val keysSeen = java.util.concurrent.atomic.AtomicInteger()
        fakeS3(status = 200) { body ->
            requests.incrementAndGet()
            keysSeen.addAndGet(Regex("<Key>").findAll(body).count())
        }.use { server ->
            val client = BodyStorageClient(s3Config = S3Config("http://127.0.0.1:${server.port}", "k", "s"))
            val uris = (1..600).map { "s3://bodies/org/svc/res-$it/call_0_response.json" }
            assertEquals(emptyMap<String, String?>(), client.deleteAll(uris).failed)
            assertEquals(1, requests.get(), "a page of 600 keys is one request")
            assertEquals(600, keysSeen.get())
        }
    }

    @Test
    fun `s3 bulk delete marks every key of a rejected request as failed`() {
        // The rows naming these objects are dropped afterwards, so a request the
        // store refused must surface each key: the caller queues them for the
        // retry job exactly as it would a single failed delete.
        fakeS3(status = 500) {}.use { server ->
            val client = BodyStorageClient(s3Config = S3Config("http://127.0.0.1:${server.port}", "k", "s", timeoutSeconds = 5))
            val uris = listOf("s3://bodies/a/1.json", "s3://bodies/a/2.json")
            val failed = client.deleteAll(uris).failed
            assertEquals(uris.toSet(), failed.keys)
            assertTrue(failed.getValue("s3://bodies/a/1.json")!!.contains("s3://bodies/a/1.json"))
        }
    }

    @Test
    fun `every store refusal keeps its own reason code`() {
        // What the settings page shows the person who typed the credentials in,
        // so each code has to survive the store's own vocabulary. Stores differ:
        // S3, R2, SeaweedFS and most others refuse a mistyped secret with
        // `SignatureDoesNotMatch`, a few answer `AccessDenied` instead.
        val codes = mapOf(
            "NoSuchBucket" to "bucket_not_found",
            "SignatureDoesNotMatch" to "invalid_credentials",
            "InvalidAccessKeyId" to "invalid_credentials",
            "InvalidToken" to "invalid_credentials",
            "AccessDenied" to "access_denied",
            "SomethingNew" to "unexpected_response",
        )
        for ((code, expected) in codes) {
            assertEquals(expected, failureReason(s3Error(code)), code)
            // Wrapped, as the delete path wraps it, the reason still comes out.
            assertEquals(expected, failureReason(StorageDeleteException("failed", s3Error(code))), code)
        }

        // A refusal by the endpoint guard is never mistaken for the store
        // answering, and a transport failure is never mistaken for an answer.
        assertEquals("blocked_endpoint", failureReason(StoreEndpointBlockedException("nope")))
        assertEquals(
            "blocked_endpoint",
            failureReason(
                software.amazon.awssdk.core.exception.SdkClientException.builder()
                    .cause(StoreEndpointBlockedException("nope")).build(),
            ),
        )
        assertEquals("unreachable", failureReason(java.net.ConnectException("refused")))
        assertEquals(
            "unreachable",
            failureReason(software.amazon.awssdk.core.exception.SdkClientException.builder().message("no route").build()),
        )
    }

    private fun s3Error(code: String): software.amazon.awssdk.services.s3.model.S3Exception =
        software.amazon.awssdk.services.s3.model.S3Exception.builder()
            .awsErrorDetails(
                software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                    .errorCode(code)
                    .serviceName("S3")
                    .build(),
            )
            .message(code)
            .build() as software.amazon.awssdk.services.s3.model.S3Exception

    /** A loopback S3 that answers every DeleteObjects request with [status]. */
    private class FakeS3(private val server: com.sun.net.httpserver.HttpServer) : AutoCloseable {
        val port: Int get() = server.address.port
        override fun close() = server.stop(0)
    }

    private fun fakeS3(status: Int, onDelete: (String) -> Unit): FakeS3 {
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0,
        )
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            if (ex.requestMethod == "POST" && ex.requestURI.query?.contains("delete") == true) {
                onDelete(body)
                val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"></DeleteResult>"""
                ex.responseHeaders.add("Content-Type", "application/xml")
                ex.sendResponseHeaders(status, xml.length.toLong())
                ex.responseBody.use { it.write(xml.toByteArray()) }
            } else {
                ex.sendResponseHeaders(405, -1)
                ex.close()
            }
        }
        server.start()
        return FakeS3(server)
    }
}
