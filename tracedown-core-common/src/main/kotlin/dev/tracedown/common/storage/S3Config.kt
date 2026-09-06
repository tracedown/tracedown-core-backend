package dev.tracedown.common.storage

/**
 * Configuration for connecting to an S3-compatible object store.
 *
 * Works with any S3-compatible service: Cloudflare R2, MinIO, Backblaze B2,
 * DigitalOcean Spaces, etc.
 */
data class S3Config(
    /** Endpoint URL, e.g. ``https://<account>.r2.cloudflarestorage.com`` */
    val endpoint: String,
    /** Access key ID. */
    val accessKey: String,
    /** Secret access key. */
    val secretKey: String,
    /**
     * Signing region. R2 wants `auto`; AWS S3 wants the bucket's real region;
     * MinIO ignores it. Without one the client would look the region up per
     * bucket, which some stores answer slowly or not at all.
     */
    val region: String = "auto",
    /**
     * Connect, read and write timeout for every call. A store that stops
     * answering must turn into a failure the caller can record (see
     * `pending_body_deletions`), not a thread parked forever: the retention
     * job runs its deletes one after another, so one hung call stalls all of
     * retention with nothing in the log.
     */
    val timeoutSeconds: Long = 30,
)
