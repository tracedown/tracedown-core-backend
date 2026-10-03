package dev.tracedown.gateway.util

import dev.tracedown.common.net.PathCanonicalizer
import java.net.URLDecoder

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

    /**
     * Whether [rawUri] will not canonicalize and yet names the key-authenticated
     * namespace — any segment of it reads `public` once decoded, wherever a dot
     * segment or an encoded slash would have moved it. Such a path is in
     * neither namespace by [isPublicUri], so nothing would enforce the key's
     * steps on it; it is refused outright instead, so that the safety of a
     * public route never rests on a router that resolves the path differently.
     */
    fun claimsPublicUri(rawUri: String): Boolean {
        if (PathCanonicalizer.canonicalize(rawUri) != null) return false
        val segments = PUBLIC_ROOT.split('/').filter { it.isNotEmpty() }
        return rawUri.substringBefore('?').substringBefore('#').split('/').any { raw ->
            val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(raw)
            decoded.split('/').any { part -> part.equals(segments.last(), ignoreCase = true) }
        }
    }
}
