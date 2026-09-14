package dev.tracedown.common.storage

/**
 * Parsed body storage URI.
 *
 * Storage URIs are self-describing references to stored response bodies:
 * - ``file:///data/bodies/call_0.json`` — local filesystem
 * - ``s3://bucket-name/prefix/call_0.json`` — S3-compatible object store (R2, SeaweedFS, etc.)
 */
sealed class StorageUri {

    /** Local filesystem path. */
    data class File(val path: String) : StorageUri()

    /** S3-compatible object. */
    data class S3(val bucket: String, val key: String) : StorageUri()

    companion object {
        private const val FILE_SCHEME = "file://"
        private const val S3_SCHEME = "s3://"

        /** Parses a storage URI string into a typed [StorageUri]. */
        fun parse(uri: String): StorageUri = when {
            uri.startsWith(FILE_SCHEME) -> File(path = uri.removePrefix(FILE_SCHEME))
            uri.startsWith(S3_SCHEME) -> {
                val rest = uri.removePrefix(S3_SCHEME)
                val slashIdx = rest.indexOf('/')
                if (slashIdx < 1) throw IllegalArgumentException("Invalid S3 URI (missing key): $uri")
                S3(bucket = rest.substring(0, slashIdx), key = rest.substring(slashIdx + 1))
            }
            else -> throw IllegalArgumentException("Unknown storage URI scheme: $uri")
        }
    }
}
