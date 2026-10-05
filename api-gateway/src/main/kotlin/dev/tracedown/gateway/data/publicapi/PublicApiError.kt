package dev.tracedown.gateway.data.publicapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Every error the key-authenticated API answers: a code a client maps to its
 * own message and, for the codes that cannot say enough alone, `details`.
 *
 * The keys `details` can carry (the description declares them; others may be
 * added, never repurposed): `field` — the parameter, path id or body field at
 * fault; `max` — the largest count allowed (a page size, an hour range);
 * `maxBytes` — the largest size allowed, in bytes; `unknown` — the values that
 * name nothing (agent slugs); `errors` — a script's validation errors; `reason`
 * — a short machine-readable or human-readable cause.
 */
@Serializable
data class PublicApiError(val error: String, val details: JsonObject? = null)
