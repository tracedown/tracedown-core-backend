package dev.tracedown.common.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PathCanonicalizerTest {

    @Test
    fun `collapses leading and doubled slashes`() {
        assertEquals("/api/v1/auth/login", PathCanonicalizer.canonicalize("//api/v1/auth/login"))
        assertEquals("/api/v1/auth/login", PathCanonicalizer.canonicalize("/api//v1///auth/login"))
    }

    @Test
    fun `strips a trailing slash and the query string`() {
        assertEquals("/api/v1/auth/login", PathCanonicalizer.canonicalize("/api/v1/auth/login/"))
        assertEquals("/api/v1/auth/login", PathCanonicalizer.canonicalize("/api/v1/auth/login?next=/x"))
    }

    @Test
    fun `percent-decodes each segment`() {
        assertEquals("/api/v1/auth/login", PathCanonicalizer.canonicalize("/api/v1/auth/%6cogin"))
    }

    @Test
    fun `rejects dot-segments, plain or encoded`() {
        assertNull(PathCanonicalizer.canonicalize("/api/v1/x/../auth/login"))
        assertNull(PathCanonicalizer.canonicalize("/api/v1/./auth/login"))
        assertNull(PathCanonicalizer.canonicalize("/api/v1/%2e%2e/auth/login"))
    }

    @Test
    fun `rejects a malformed percent-escape`() {
        assertNull(PathCanonicalizer.canonicalize("/api/%zz/login"))
        assertNull(PathCanonicalizer.canonicalize("/api/%2"))
    }

    @Test
    fun `an empty or root path canonicalizes to root`() {
        assertEquals("/", PathCanonicalizer.canonicalize(""))
        assertEquals("/", PathCanonicalizer.canonicalize("/"))
        assertEquals("/", PathCanonicalizer.canonicalize("///"))
    }
}
