package dev.tracedown.common.models

/**
 * `probe_steps.body_not_stored_reason` values written outside the result-ingestor.
 *
 * The ingestor's `BodyNotStoredReason` owns the vocabulary and aliases these, so
 * the whole set still reads in one place; they live here because the module that
 * writes them — the aggregate-worker — shares only this library with it. Same
 * arrangement as `BodyStoreService.REASON_STORE_REMOVED`.
 */
object BodyNotStoredReasons {

    /**
     * The body aged out of the body-retention window while the result it belongs
     * to was kept. The step row, its timings and its assertions are all still
     * there; only the stored body is gone.
     */
    const val BODY_EXPIRED = "bodyExpired"
}
