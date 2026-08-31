package dev.tracedown.gateway.util

import io.ktor.server.config.ApplicationConfig

/**
 * Which browser origins may call this API.
 *
 * The dashboard sends credentials with every request, and a credentialed
 * cross-origin response may not answer with `Access-Control-Allow-Origin: *` —
 * it has to name the exact origin. So there is no usable "allow everything"
 * setting, and the configuration is a list of exact origins.
 *
 * Unset is the default, and it means **no CORS headers at all**. That is the
 * shape almost every deployment has: the bundled Compose stack (and the
 * single-process edition, and the Vite dev server's `/api` proxy) serve the app
 * and the API from one origin, so no request from the dashboard is
 * cross-origin and no CORS header would be an answer to anything. Emitting
 * nothing is both correct there and the safest thing to do when an operator has
 * said nothing — an operator who has never heard of this variable gets a
 * working gateway, and no origin gains credentialed access by omission.
 *
 * A deployment that serves the dashboard from a different origin is the one
 * that has to say so, by listing those origins in [ORIGINS_VAR].
 */
data class CorsSettings(
    /** Exact origins, already parsed into the (hostWithPort, scheme) pairs Ktor wants. */
    val hosts: List<Pair<String, String>>,
) {
    /** True when origins were configured, so CORS headers are emitted for them. */
    val enabled: Boolean get() = hosts.isNotEmpty()

    companion object {
        /** The variable an operator sets. Named in every error and hint this file raises. */
        const val ORIGINS_VAR = "API_CORS_ORIGINS"

        fun load(config: ApplicationConfig): CorsSettings =
            CorsSettings(hosts = parseOrigins(config.propertyOrNull("cors.origins")?.getString().orEmpty()))

        /**
         * Splits and validates the configured list. Each entry must be exactly
         * `scheme://host[:port]`: a trailing slash, a path or a query would
         * register a host nobody meant and then fail to match at runtime, which
         * is the kind of CORS problem that costs an afternoon.
         *
         * Only a configured value can fail here — an operator who sets nothing
         * gets an empty list, never an error.
         *
         * @throws IllegalStateException naming the offending entry.
         */
        fun parseOrigins(raw: String): List<Pair<String, String>> =
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { parseOrigin(it) }

        /** One origin as `hostWithPort to scheme`. */
        fun parseOrigin(origin: String): Pair<String, String> {
            val uri = runCatching { java.net.URI(origin) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host
            val valid = uri != null &&
                (scheme == "http" || scheme == "https") &&
                !host.isNullOrBlank() &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.userInfo == null
            check(valid) {
                "$ORIGINS_VAR entry '$origin' is not a plain origin — " +
                    "expected scheme://host[:port] with no trailing slash, path or query"
            }
            val hostPort = if (uri.port != -1) "$host:${uri.port}" else host
            return hostPort to scheme
        }

        /**
         * Does this request look like it came from a browser on another origin?
         *
         * Used only to decide whether to log a hint when nothing is configured:
         * a same-origin deployment never trips it, while a dashboard on another
         * host produces a line naming [ORIGINS_VAR] instead of a CORS failure
         * the operator can only see in a browser console.
         *
         * Compares authorities, not schemes: a TLS-terminating proxy forwards
         * plain HTTP, so the request's own scheme says nothing about the
         * browser's. Default ports are normalised away so `https://host` and
         * `host:443` are the same place.
         */
        fun looksCrossOrigin(originHeader: String?, requestHost: String?): Boolean {
            val origin = originHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val host = requestHost?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            // "null" is what a sandboxed iframe or a file:// page sends; it names
            // no host to compare and no host an operator could ever configure.
            if (origin.equals("null", ignoreCase = true)) return false
            val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
            val originHost = uri.host?.lowercase() ?: return false
            val defaultPort = if (uri.scheme?.lowercase() == "https") 443 else 80
            val originAuthority = if (uri.port == -1 || uri.port == defaultPort) {
                originHost
            } else {
                "$originHost:${uri.port}"
            }
            val requestAuthority = host.lowercase().removeSuffix(":80").removeSuffix(":443")
            return originAuthority != requestAuthority
        }
    }
}
