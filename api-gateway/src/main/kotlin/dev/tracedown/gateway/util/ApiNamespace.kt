package dev.tracedown.gateway.util

import dev.tracedown.common.net.PathCanonicalizer

/**
 * The two namespaces of this API, told apart by path.
 *
 * Everything under [PUBLIC_ROOT] is the key-authenticated API; everything else
 * is the dashboard's. Which credential a request may carry, and which rate
 * budget it spends, both follow from which side of this line its path is on —
 * so the line is drawn in one place.
 */
object ApiNamespace {

    const val PUBLIC_ROOT = "/api/public"

    /** Whether a canonical path (see [PathCanonicalizer]) lies in the key-authenticated namespace. */
    fun isPublic(canonicalPath: String): Boolean =
        canonicalPath == PUBLIC_ROOT || canonicalPath.startsWith("$PUBLIC_ROOT/")

    /** As [isPublic], for a raw request URI. A URI that will not canonicalize is in neither namespace. */
    fun isPublicUri(rawUri: String): Boolean =
        PathCanonicalizer.canonicalize(rawUri)?.let(::isPublic) ?: false
}
