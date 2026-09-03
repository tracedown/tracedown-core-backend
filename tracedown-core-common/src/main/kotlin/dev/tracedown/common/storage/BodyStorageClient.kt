package dev.tracedown.common.storage

import io.minio.CopyObjectArgs
import io.minio.CopySource
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import io.minio.http.Method
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
) {
    /** Normalized prefix with no leading/trailing slashes. */
    val normalizedS3Prefix: String = s3KeyPrefix.trim('/')

    /** Absolute, normalized filesystem root (or null when not filesystem-confined). */
    val normalizedRoot: Path? = filesystemRoot?.toAbsolutePath()?.normalize()
}

/**
 * Client for interacting with stored response bodies via protocol-aware URIs.
 *
 * Supports ``file://`` (local filesystem) and ``s3://`` (any S3-compatible store:
 * Cloudflare R2, MinIO, Backblaze B2, etc.).
 *
 * The S3 client is lazily initialized — no connection is made unless S3 URIs
 * are actually encountered.
 */
open class BodyStorageClient(
    private val s3Config: S3Config? = null,
    private val confinement: BodyConfinement? = null,
) {

    private val s3Client: MinioClient? by lazy {
        s3Config?.let { cfg ->
            MinioClient.builder()
                .endpoint(cfg.endpoint)
                .credentials(cfg.accessKey, cfg.secretKey)
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
            is StorageUri.File -> {
                val file = confineFilePath(parsed.path)
                if (Files.exists(file)) BodyContent.Inline(Files.readString(file))
                else BodyContent.NotFound
            }
            is StorageUri.S3 -> {
                confineS3(parsed.bucket, parsed.key)
                BodyContent.Redirect(presignS3(parsed.bucket, parsed.key))
            }
        }
    }

    sealed class BodyContent {
        data class Inline(val content: String) : BodyContent()
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

    private fun presignS3(bucket: String, key: String): String {
        val client = s3Client ?: throw IllegalStateException("S3 config not provided but s3:// URI encountered")
        return client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .`object`(key)
                .expiry(3600)
                .build()
        )
    }

    // --- Confinement -------------------------------------------------------

    /**
     * Canonicalizes a `file://` path and, when confinement is configured, verifies
     * it resolves within the configured root. Returns the path to operate on.
     * Symlinks are resolved for existing files ([Path.toRealPath]) so a symlink
     * inside the root can't point the operation at a file outside it.
     */
    private fun confineFilePath(path: String): Path {
        val conf = confinement ?: return Path.of(path)
        val root = conf.normalizedRoot
            ?: throw StorageConfinementException("file:// URI encountered but no filesystem root is confined: $path")
        val candidate = Path.of(path).toAbsolutePath().normalize()
        // Resolve symlinks when the file exists; fall back to the normalized path otherwise.
        val resolved = if (Files.exists(candidate)) candidate.toRealPath() else candidate
        if (!resolved.startsWith(root)) {
            throw StorageConfinementException("file path $resolved is outside confined root $root")
        }
        return resolved
    }

    private fun confineS3(bucket: String, key: String) {
        val conf = confinement ?: return
        val allowedBucket = conf.s3Bucket
            ?: throw StorageConfinementException("s3:// URI encountered but no bucket is confined: $bucket/$key")
        if (bucket != allowedBucket) {
            throw StorageConfinementException("bucket $bucket is not the confined bucket $allowedBucket")
        }
        val prefix = conf.normalizedS3Prefix
        val normalizedKey = key.trimStart('/')
        if (prefix.isNotEmpty() && !normalizedKey.startsWith("$prefix/") && normalizedKey != prefix) {
            throw StorageConfinementException("key $normalizedKey is outside confined prefix $prefix")
        }
    }

    private fun relocateFile(conf: BodyConfinement, sourcePath: String, destKey: String): String {
        val root = conf.normalizedRoot
            ?: throw StorageConfinementException("filesystem relocation requires a confined root")
        // Confine + canonicalize the SOURCE (rejects escapes like /app/application.conf).
        val source = confineFilePath(sourcePath)
        if (!Files.exists(source)) {
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
                .source(CopySource.builder().bucket(bucket).`object`(key).build())
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
