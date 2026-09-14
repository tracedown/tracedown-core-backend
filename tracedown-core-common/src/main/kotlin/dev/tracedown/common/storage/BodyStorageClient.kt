package dev.tracedown.common.storage

import io.minio.CopyObjectArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import io.minio.RemoveObjectArgs
import io.minio.RemoveObjectsArgs
import io.minio.messages.DeleteRequest
import io.minio.SourceObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Raised when a storage URI points outside the configured backend root/bucket —
 * i.e. an attempt to read, delete, or relocate a body that was never legitimately
 * stored by the platform (a compromised agent handing back `file:///app/secret`
 * or `s3://someone-elses-bucket/key`). Defense-in-depth: the ingestor already
 * refuses to persist agent-chosen paths, but any client with a configured
 * [BodyConfinement] also refuses to dereference one.
 */
class StorageConfinementException(message: String) : SecurityException(message)

/**
 * Raised when a body could not be removed from its backend. Callers that are
 * about to drop the database row naming the object (retention, purge) treat
 * this as "the object is still there" and hand the URI to the deletion retry
 * table — a failure that is merely logged would leave the object in the bucket
 * with nothing referencing it, outside both retention and erasure.
 */
class StorageDeleteException(message: String, cause: Throwable) : RuntimeException(message, cause)

/** Raised when a stored body is larger than the caller asked to read. */
class BodyTooLargeException(val sizeBytes: Long) : RuntimeException("stored body is $sizeBytes bytes")

/**
 * Confines a [BodyStorageClient] to a single backend location. When set, every
 * URI the client touches is canonicalized and checked to fall within the
 * configured filesystem root (for `file://`) or bucket + key prefix (for
 * `s3://`); anything else raises [StorageConfinementException].
 *
 * Left null on a client, confinement is disabled (legacy behavior) — the client
 * trusts whatever URI it is handed. Only the result-ingestor, which relocates
 * bodies to server-derived keys, needs confinement; read/delete consumers see
 * only already-server-derived URIs.
 */
data class BodyConfinement(
    /** Absolute filesystem root that all `file://` bodies must live under. */
    val filesystemRoot: Path? = null,
    /** The one bucket `s3://` bodies may live in. */
    val s3Bucket: String? = null,
    /** Key prefix within [s3Bucket] that all bodies must sit under (may be empty). */
    val s3KeyPrefix: String = "",
    /**
     * Schemes (`file`, `s3`) this confinement deliberately does not cover, so a
     * URI of that scheme is trusted as it was before confinement existed.
     *
     * There is exactly one caller: the aggregate-worker, whose location was
     * never a setting an operator had to give until stores arrived. Deployments
     * upgrading into a confined worker have no `STORAGE_S3_BUCKET` or
     * `STORAGE_FILESYSTEM_ROOT` on it, and a worker that refused every URI would
     * purge the rows and leave the objects behind — silently, since a refusal is
     * "not ours to delete". It keeps the old behaviour for the scheme it was not
     * told about, and says so at startup.
     */
    val unconfinedSchemes: Set<String> = emptySet(),
) {
    /** Normalized prefix with no leading/trailing slashes. */
    val normalizedS3Prefix: String = s3KeyPrefix.trim('/')

    /**
     * Absolute, canonical filesystem root (or null when not filesystem-confined).
     * The root itself is resolved through symlinks — a mount laid out as a link
     * is a perfectly ordinary way to give a service a directory, and a root that
     * did not resolve would refuse every body under it. What is *inside* the root
     * is never resolved that way; see `confineFilePath`.
     */
    val normalizedRoot: Path? = filesystemRoot?.let {
        val absolute = it.toAbsolutePath().normalize()
        try {
            absolute.toRealPath()
        } catch (_: Exception) {
            absolute
        }
    }

    /**
     * The root exactly as configured, before symlinks were resolved. A body may
     * be named by either spelling — an agent writing to `/data/stores/eu` does
     * not know or care that the operator made it a link — so both are accepted
     * and this one is rebased onto [normalizedRoot].
     */
    val configuredRoot: Path? = filesystemRoot?.toAbsolutePath()?.normalize()
}

/**
 * Client for interacting with stored response bodies via protocol-aware URIs.
 *
 * Supports ``file://`` (local filesystem) and ``s3://`` (any S3-compatible store:
 * Cloudflare R2, MinIO, Backblaze B2, etc.).
 *
 * The S3 client is lazily initialized — no connection is made unless S3 URIs
 * are actually encountered.
 *
 * [httpClient] replaces the default HTTP client of the S3 backend; a body store
 * passes the SSRF-guarded one from [StoreEndpointGuard.httpClient].
 */
open class BodyStorageClient(
    private val s3Config: S3Config? = null,
    private val confinement: BodyConfinement? = null,
    private val httpClient: okhttp3.OkHttpClient? = null,
) {

    private val log = LoggerFactory.getLogger(BodyStorageClient::class.java)

    private val s3Client: MinioClient? by lazy {
        s3Config?.let { cfg ->
            val timeout = java.time.Duration.ofSeconds(cfg.timeoutSeconds.coerceAtLeast(1))
            MinioClient.builder()
                .endpoint(cfg.endpoint)
                .credentials(cfg.accessKey, cfg.secretKey)
                .region(cfg.region)
                // MinIO's own default client waits five minutes per phase.
                .httpClient(
                    httpClient ?: okhttp3.OkHttpClient.Builder()
                        .connectTimeout(timeout)
                        .readTimeout(timeout)
                        .writeTimeout(timeout)
                        .callTimeout(timeout.multipliedBy(2))
                        .build(),
                )
                .build()
        }
    }

    /**
     * Deletes the body at the given storage URI. Returns true if deleted, false
     * if the object was already gone; a missing object is never an error.
     *
     * A delete that *fails* — unreachable store, rejected credentials — throws
     * [StorageDeleteException] rather than returning false, so a caller cannot
     * mistake "still in the bucket" for "was not there".
     */
    open fun delete(uri: String): Boolean {
        return when (val parsed = StorageUri.parse(uri)) {
            is StorageUri.File -> deleteFile(confineFilePath(parsed.path).toString())
            is StorageUri.S3 -> {
                confineS3(parsed.bucket, parsed.key)
                deleteS3(parsed.bucket, parsed.key)
            }
        }
    }

    /**
     * Deletes many bodies and returns the ones that could not be deleted, URI to
     * reason. A missing object is not a failure.
     *
     * S3 keys go in bulk — one `DeleteObjects` request per bucket per
     * [S3_DELETE_CHUNK] keys — instead of a round trip each: retention removes
     * hundreds of bodies per batch, and at ~100 ms per object the whole tick
     * budget went on waiting for the store. A request the store rejects outright
     * marks every key it carried as failed; the caller records those for
     * [BodyDeletionRetryJob] the same way as a single failed delete.
     *
     * Files are deleted one by one — that is a local call — through [delete], so
     * a client that overrides the single delete keeps its behaviour here.
     *
     * A URI this client refuses on confinement is skipped, not failed: it names a
     * body kept outside platform storage (for example in an agent's own body
     * store), which is not the platform's to delete. Reporting it as failed would park it
     * in the deletion retry table forever; it is logged at debug and left alone.
     */
    open fun deleteAll(uris: Collection<String>): Map<String, String?> {
        val failed = LinkedHashMap<String, String?>()
        val skipped = mutableListOf<String>()
        // bucket → (key, uri)
        val s3ByBucket = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (uri in uris) {
            try {
                when (val parsed = StorageUri.parse(uri)) {
                    is StorageUri.File -> delete(uri)
                    is StorageUri.S3 -> {
                        confineS3(parsed.bucket, parsed.key)
                        s3ByBucket.getOrPut(parsed.bucket) { mutableListOf() }.add(parsed.key to uri)
                    }
                }
            } catch (e: StorageConfinementException) {
                // Not a failure — it names a body the platform does not own (an
                // agent's own store). Worth a WARN all the same: the queries that
                // feed this already exclude those, so one arriving here means a
                // row the platform thinks it owns points somewhere it does not.
                skipped.add(uri)
                log.warn("not deleting body outside platform storage {}: {}", uri, e.message)
            } catch (e: Exception) {
                failed[uri] = e.message
            }
        }
        if (skipped.isNotEmpty()) {
            log.warn("{} of {} bodies were outside platform storage and were not deleted", skipped.size, uris.size)
        }
        for ((bucket, entries) in s3ByBucket) {
            // No S3 backend configured throws before any request is made; that is
            // a failure of these keys, not of the whole call.
            try {
                failed.putAll(deleteS3Bulk(bucket, entries))
            } catch (e: Exception) {
                for ((_, uri) in entries) failed[uri] = e.message
            }
        }
        return failed
    }

    /**
     * Relocates a body from an agent-reported [sourceUri] to a server-derived
     * [destKey] within the confined backend, returning the canonical storage URI
     * that should be persisted.
     *
     * This is how the ingestor takes ownership of the storage location: the agent
     * uploads to a location it chose, but the platform never persists that path —
     * it moves the bytes to `{orgId}/{serviceId}/{resultId}/…` (tenant-scoped,
     * collision-free, non-attacker-controlled) and records only the new URI. The
     * source is canonicalized and confinement-checked first, so a compromised
     * agent handing back `file:///app/application.conf` or a foreign bucket is
     * rejected rather than copied into the body store.
     *
     * Requires a [BodyConfinement]; throws [IllegalStateException] otherwise.
     */
    open fun relocate(sourceUri: String, destKey: String): String {
        val conf = confinement
            ?: throw IllegalStateException("relocate requires a configured BodyConfinement")
        return when (val parsed = StorageUri.parse(sourceUri)) {
            is StorageUri.File -> relocateFile(conf, parsed.path, destKey)
            is StorageUri.S3 -> relocateS3(conf, parsed.bucket, parsed.key, destKey)
        }
    }

    /** Generates a presigned download URL (1 hour expiry) for the given storage URI. */
    fun presignedUrl(uri: String): String {
        return when (val parsed = StorageUri.parse(uri)) {
            is StorageUri.File -> "file://${parsed.path}"
            is StorageUri.S3 -> presignS3(parsed.bucket, parsed.key)
        }
    }

    /**
     * Reads the body content as a string.
     * For file:// URIs, reads from the filesystem.
     * For s3:// URIs, returns a presigned URL string prefixed with "redirect:".
     */
    fun readBody(uri: String): BodyContent {
        return when (val parsed = StorageUri.parse(uri)) {
            is StorageUri.File -> when (val read = readBytes(uri, BodyStoreRegistry.MAX_BODY_BYTES)) {
                is StoredBody.Found -> BodyContent.Inline(read.bytes.toString(Charsets.UTF_8))
                StoredBody.Missing -> BodyContent.NotFound
                is StoredBody.TooLarge -> throw BodyTooLargeException(read.sizeBytes)
            }
            is StorageUri.S3 -> {
                confineS3(parsed.bucket, parsed.key)
                BodyContent.Redirect(presignS3(parsed.bucket, parsed.key))
            }
        }
    }

    /**
     * Whether [uri] lies inside this client's confined location (root, or bucket
     * + prefix). False when the client is not confined at all, or the URI is
     * malformed. Nothing is read or touched.
     */
    fun contains(uri: String): Boolean {
        if (confinement == null) return false
        return try {
            when (val parsed = StorageUri.parse(uri)) {
                is StorageUri.File -> confineFilePath(parsed.path)
                is StorageUri.S3 -> confineS3(parsed.bucket, parsed.key)
            }
            true
        } catch (_: StorageConfinementException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** What [readBytes] found at a URI. */
    sealed class StoredBody {
        /** The body; [contentType] as the store reported it (filesystem stores report none). */
        class Found(val bytes: ByteArray, val contentType: String?) : StoredBody()
        data object Missing : StoredBody()
        class TooLarge(val sizeBytes: Long) : StoredBody()
    }

    /**
     * Reads the bytes at [uri], never more than [maxBytes]. Confinement applies
     * (throws [StorageConfinementException]); an unreachable store or refused
     * credentials throw as well. A missing object is [StoredBody.Missing].
     */
    open fun readBytes(uri: String, maxBytes: Long): StoredBody {
        return when (val parsed = StorageUri.parse(uri)) {
            is StorageUri.File -> {
                val file = confineFilePath(parsed.path)
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return StoredBody.Missing
                // The size is read for the cheap refusal, but never trusted as
                // the amount to allocate: a file that grows between the stat and
                // the read must not be able to pull the whole of itself into the
                // heap. The stream stops one byte past the cap either way.
                val size = Files.size(file)
                if (size > maxBytes) {
                    StoredBody.TooLarge(size)
                } else {
                    val bytes = openConfined(file).use { it.readNBytes(capacity(maxBytes)) }
                    if (bytes.size > maxBytes) StoredBody.TooLarge(bytes.size.toLong())
                    else StoredBody.Found(bytes, null)
                }
            }
            is StorageUri.S3 -> {
                confineS3(parsed.bucket, parsed.key)
                val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
                val stat = try {
                    client.statObject(StatObjectArgs.builder().bucket(parsed.bucket).`object`(parsed.key).build())
                } catch (e: ErrorResponseException) {
                    if (e.errorResponse()?.code() in MISSING_CODES || e.response()?.code == 404) return StoredBody.Missing
                    throw e
                }
                if (stat.size() > maxBytes) return StoredBody.TooLarge(stat.size())
                client.getObject(GetObjectArgs.builder().bucket(parsed.bucket).`object`(parsed.key).build()).use { input ->
                    val bytes = input.readNBytes(capacity(maxBytes))
                    if (bytes.size > maxBytes) StoredBody.TooLarge(bytes.size.toLong())
                    else StoredBody.Found(bytes, stat.contentType())
                }
            }
        }
    }

    /**
     * Writes [bytes] under the server-derived [key] inside this client's confined
     * location — the S3 bucket + prefix when an S3 backend is configured, the
     * filesystem root otherwise — and returns the URI to persist.
     */
    open fun put(key: String, bytes: ByteArray, contentType: String?): String {
        val conf = confinement ?: throw IllegalStateException("put requires a configured BodyConfinement")
        val cleanKey = sanitizeKey(key)
        val bucket = conf.s3Bucket
        val client = s3Client
        if (client != null && bucket != null) {
            val prefix = conf.normalizedS3Prefix
            val fullKey = if (prefix.isEmpty()) cleanKey else "$prefix/$cleanKey"
            val args = PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(fullKey)
                .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1L)
            if (!contentType.isNullOrBlank()) args.contentType(contentType)
            client.putObject(args.build())
            return "s3://$bucket/$fullKey"
        }
        val root = conf.normalizedRoot ?: throw StorageConfinementException("no filesystem root is confined")
        val dest = root.resolve(cleanKey).normalize()
        if (!dest.startsWith(root)) throw StorageConfinementException("dest key $key escapes confined root $root")
        dest.parent?.let { Files.createDirectories(it) }
        Files.write(dest, bytes)
        return "file://$dest"
    }

    /**
     * Copies a body from another store into this one: reads [sourceUri] through
     * [source] (confined to that store) and writes it under [destKey] here,
     * returning the URI to persist. Throws when the source is missing, larger
     * than [maxBytes], outside [source]'s confinement, or unreachable.
     *
     * The source is **not** removed. Whoever imports a body owns the order of
     * the two irreversible steps around it: the source may only go once the row
     * naming the copy has committed, or a crash in between loses the body
     * outright. See the ingestor's import path.
     *
     * The content type the source reports is not carried over. It is a value the
     * agent chose, echoed back by its own store, and the platform would then
     * serve it to a browser as its own — so the copy is stored as
     * `application/octet-stream` and the dashboard decides how to show it.
     */
    open fun copyFrom(source: BodyStorageClient, sourceUri: String, destKey: String, maxBytes: Long): String {
        val body = when (val read = source.readBytes(sourceUri, maxBytes)) {
            is StoredBody.Found -> read
            StoredBody.Missing -> throw IllegalStateException("source body does not exist: $sourceUri")
            is StoredBody.TooLarge -> throw BodyTooLargeException(read.sizeBytes)
        }
        return put(destKey, body.bytes, OCTET_STREAM)
    }

    /**
     * Read-only reachability check of the confined location: lists at most one
     * key under the S3 prefix, or checks that the filesystem root is a readable
     * directory. Returns null when it answered, otherwise a short reason code
     * (`blocked_endpoint`, `bucket_not_found`, `invalid_credentials`,
     * `access_denied`, `unreachable`, `unexpected_response`, `not_a_directory`,
     * `not_readable`).
     */
    open fun probe(): String? {
        val conf = confinement ?: return "not_configured"
        val bucket = conf.s3Bucket
        if (s3Config != null && bucket != null) {
            return try {
                val client = s3Client ?: return "not_configured"
                val prefix = conf.normalizedS3Prefix.let { if (it.isEmpty()) "" else "$it/" }
                val listing = client.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).maxKeys(1).build(),
                ).iterator()
                if (listing.hasNext()) listing.next().get()
                null
            } catch (e: Exception) {
                failureReason(e)
            }
        }
        val root = conf.normalizedRoot ?: return "not_configured"
        return when {
            !Files.isDirectory(root) -> "not_a_directory"
            !Files.isReadable(root) -> "not_readable"
            else -> null
        }
    }

    sealed class BodyContent {
        /**
         * [contentType] is set only when the reader knows it; platform storage
         * does not record one. [encoding] is `base64` when [content] is base64 of
         * bytes that are not valid UTF-8, and null when it is the text itself.
         */
        data class Inline(
            val content: String,
            val contentType: String? = null,
            val encoding: String? = null,
        ) : BodyContent()
        data class Redirect(val url: String) : BodyContent()
        data object NotFound : BodyContent()
    }

    private fun deleteFile(path: String): Boolean {
        val file = Path.of(path)
        return if (Files.exists(file)) {
            Files.delete(file)
            true
        } else {
            false
        }
    }

    private fun deleteS3(bucket: String, key: String): Boolean {
        val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
        // S3 DELETE on a missing key succeeds, so the "already gone" case never
        // reaches the catch. Anything that does is a real failure and must
        // propagate: this used to log and return false, which the retention and
        // purge jobs — they only catch exceptions — read as success and went on
        // to delete the row holding the object's only reference.
        try {
            client.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build()
            )
        } catch (e: Exception) {
            throw StorageDeleteException("failed to delete s3://$bucket/$key: ${e.message}", e)
        }
        return true
    }

    private fun deleteS3Bulk(bucket: String, entries: List<Pair<String, String>>): Map<String, String?> {
        val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
        val uriByKey = entries.toMap()
        val failed = LinkedHashMap<String, String?>()
        for (chunk in entries.chunked(S3_DELETE_CHUNK)) {
            try {
                // The result is lazy: iterating it is what sends the request.
                // Only per-key errors come back; a deleted or already-missing
                // key produces nothing.
                val errors = client.removeObjects(
                    RemoveObjectsArgs.builder()
                        .bucket(bucket)
                        .objects(chunk.map { (key, _) -> DeleteRequest.Object(key) })
                        .build()
                )
                for (result in errors) {
                    val error = result.get()
                    val uri = uriByKey[error.objectName()] ?: "s3://$bucket/${error.objectName()}"
                    failed[uri] = "${error.code()}: ${error.message()}"
                }
            } catch (e: Exception) {
                for ((key, uri) in chunk) {
                    if (uri !in failed) failed[uri] = "failed to delete s3://$bucket/$key: ${e.message}"
                }
            }
        }
        return failed
    }

    private fun presignS3(bucket: String, key: String): String {
        val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
        return client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Http.Method.GET)
                .bucket(bucket)
                .`object`(key)
                .expiry(3600)
                .build()
        )
    }

    // --- Confinement -------------------------------------------------------

    /**
     * Canonicalizes a `file://` path and, when confinement is configured, verifies
     * it lies within the configured root **without ever following a symlink under
     * it**. Returns the path to operate on.
     *
     * Resolving the path first and comparing the result (what this used to do) is
     * only a check, and a check is not a guarantee: between the check and the
     * open, a directory under the root can be replaced by a link to `/proc/self`
     * or `/etc`, and the operation then reads — or, importing, deletes — a file
     * the store never held. So every component from the root down is required not
     * to be a symlink, and the open that follows uses `NOFOLLOW_LINKS` and
     * re-checks the file's identity ([openConfined]).
     *
     * The root itself is allowed to be a link (it is resolved once, in
     * [BodyConfinement]) — that is the operator's own mount, not something an
     * agent can create.
     */
    private fun confineFilePath(path: String): Path {
        val conf = confinement ?: return Path.of(path)
        if (conf.normalizedRoot == null && "file" in conf.unconfinedSchemes) return Path.of(path)
        val root = conf.normalizedRoot
            ?: throw StorageConfinementException("file:// URI encountered but no filesystem root is confined: $path")
        if (path.contains('\\') || path.any { it.code == 0 }) {
            throw StorageConfinementException("file path is not a plain absolute path: $path")
        }
        val named = Path.of(path).toAbsolutePath().normalize()
        val configured = conf.configuredRoot
        val candidate = when {
            named.startsWith(root) -> named
            // Named by the root's own (unresolved) spelling: rebase it, so a
            // root the operator laid out as a symlink works either way.
            configured != null && named.startsWith(configured) -> root.resolve(configured.relativize(named))
            else -> throw StorageConfinementException("file path $named is outside confined root $root")
        }
        var walked = root
        for (component in root.relativize(candidate)) {
            walked = walked.resolve(component)
            val attributes = try {
                Files.readAttributes(walked, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.io.IOException) {
                // Not there (yet) — a write creates it; a read finds nothing.
                continue
            }
            if (attributes.isSymbolicLink) {
                throw StorageConfinementException("$walked under confined root $root is a symbolic link")
            }
        }
        return candidate
    }

    /**
     * Opens a confined file without following a link on the final component, and
     * verifies the opened file is still the one that was checked — the narrow
     * window [confineFilePath] cannot close on its own.
     */
    private fun openConfined(file: Path): InputStream {
        val before = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.isSymbolicLink || !before.isRegularFile) {
            throw StorageConfinementException("$file is not a regular file")
        }
        val stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
        val after = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            stream.close()
            throw e
        }
        if (after.fileKey() != null && after.fileKey() != before.fileKey()) {
            stream.close()
            throw StorageConfinementException("$file changed identity while it was being opened")
        }
        return stream
    }

    private fun confineS3(bucket: String, key: String) {
        val conf = confinement ?: return
        if (conf.s3Bucket == null && "s3" in conf.unconfinedSchemes) return
        val allowedBucket = conf.s3Bucket
            ?: throw StorageConfinementException("s3:// URI encountered but no bucket is confined: $bucket/$key")
        if (bucket != allowedBucket) {
            throw StorageConfinementException("bucket $bucket is not the confined bucket $allowedBucket")
        }
        val normalizedKey = key.trimStart('/')
        // `a/../b`, `a//b` and `a\b` all name one object to some stores and
        // another to others; none of them is a key this platform ever writes, and
        // each is a way to dress up a location outside the prefix as one inside.
        if (normalizedKey.isEmpty() || normalizedKey.contains('\\') ||
            normalizedKey.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw StorageConfinementException("key $key is not a plain object key")
        }
        val prefix = conf.normalizedS3Prefix
        if (prefix.isNotEmpty() && !normalizedKey.startsWith("$prefix/") && normalizedKey != prefix) {
            throw StorageConfinementException("key $normalizedKey is outside confined prefix $prefix")
        }
    }

    private fun relocateFile(conf: BodyConfinement, sourcePath: String, destKey: String): String {
        val root = conf.normalizedRoot
            ?: throw StorageConfinementException("filesystem relocation requires a confined root")
        // Confine + canonicalize the SOURCE (rejects escapes like /app/application.conf).
        val source = confineFilePath(sourcePath)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw StorageConfinementException("source body does not exist: $source")
        }
        // The dest key is server-derived; still normalize + confine it defensively.
        val dest = root.resolve(sanitizeKey(destKey)).normalize()
        if (!dest.startsWith(root)) {
            throw StorageConfinementException("dest key $destKey escapes confined root $root")
        }
        dest.parent?.let { Files.createDirectories(it) }
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
        return "file://$dest"
    }

    private fun relocateS3(conf: BodyConfinement, bucket: String, key: String, destKey: String): String {
        val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
        // Confine the SOURCE (rejects a foreign bucket / out-of-prefix key).
        confineS3(bucket, key)
        val allowedBucket = conf.s3Bucket
            ?: throw StorageConfinementException("s3 relocation requires a confined bucket")
        val prefix = conf.normalizedS3Prefix
        val cleanDest = sanitizeKey(destKey)
        val destFullKey = if (prefix.isEmpty()) cleanDest else "$prefix/$cleanDest"
        client.copyObject(
            CopyObjectArgs.builder()
                .bucket(allowedBucket)
                .`object`(destFullKey)
                .source(SourceObject.builder().bucket(bucket).`object`(key).build())
                .build()
        )
        deleteS3(bucket, key)
        return "s3://$allowedBucket/$destFullKey"
    }

    /** Rejects path-traversal in a server-derived key; keys are already trusted, this is belt-and-suspenders. */
    private fun sanitizeKey(key: String): String {
        val clean = key.trim('/')
        require(clean.isNotEmpty() && !clean.split('/').any { it == ".." || it.isEmpty() }) {
            "invalid storage key: $key"
        }
        return clean
    }
}

/** How many bytes to read for a cap of [maxBytes] — one past it, so "too large" is detectable. */
private fun capacity(maxBytes: Long): Int =
    (maxBytes + 1).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** What an imported body is stored as: never the type the agent reported. */
internal const val OCTET_STREAM = "application/octet-stream"

/** S3's ceiling for one DeleteObjects request. */
private const val S3_DELETE_CHUNK = 1000

/** S3 error codes that mean "there is nothing at this key". */
private val MISSING_CODES = setOf("NoSuchKey", "NoSuchBucket", "NoSuchObject", "NotFound")

/** A short, machine-readable reason for a failed store call (see [BodyStorageClient.probe]). */
internal fun failureReason(e: Throwable): String {
    if (StoreEndpointGuard.isBlocked(e)) return "blocked_endpoint"
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is ErrorResponseException) {
            return when (cause.errorResponse()?.code()) {
                "NoSuchBucket" -> "bucket_not_found"
                // A key the store does not know, or a signature it will not
                // accept, is a wrong credential — something the person who typed
                // it can fix. AccessDenied is a policy that refuses a credential
                // the store does recognise, which they usually cannot.
                "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken" -> "invalid_credentials"
                "AccessDenied" -> "access_denied"
                else -> "unexpected_response"
            }
        }
        if (cause is IOException) return "unreachable"
        cause = cause.cause?.takeIf { it !== cause }
    }
    return "unexpected_response"
}
