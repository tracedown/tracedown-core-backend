package dev.tracedown.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard used to test two exact literals — the all-zero platform key and one
 * dev JWT secret — while the only tracked file carrying a complete variable set
 * shipped *different* dev values. Both of those sailed through `requireSecure`
 * with `DEPLOYMENT_ENV=production`, which meant a "guarded" production deploy
 * could be running a published key-encryption key (it wraps the org DEKs, the
 * TOTP secrets and the internal CA private key) and a published JWT signing
 * secret.
 *
 * These tests pin the values in the repository today, and — more importantly —
 * the structural properties that catch the ones added tomorrow.
 */
class SecretGuardCredentialTest {

    /** Every dev value that appears in a tracked file in this repository. */
    private val publishedDevValues = listOf(
        "0".repeat(64),
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "default-dev-secret-change-in-production",
        "dev-jwt-secret-change-me-for-prod",
        "tracedown",
        "Down2trace!",
    )

    @Test
    fun `every published dev value is refused`() {
        for (value in publishedDevValues) {
            assertNotNull(
                SecretGuard.credentialWeakness(value),
                "'$value' appears in a tracked file and must never be accepted",
            )
        }
    }

    @Test
    fun `the value the docker env example ships for the platform key is refused`() {
        // The exact regression: docker/.env.example line 4. It is 64 hex
        // characters, so it passes every naive shape test — but it is one
        // 16-character unit repeated four times, and it is published here.
        val weakness = SecretGuard.credentialWeakness(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        assertNotNull(weakness)
    }

    @Test
    fun `the value the docker env example ships for the JWT secret is refused`() {
        assertNotNull(SecretGuard.credentialWeakness("dev-jwt-secret-change-me-for-prod"))
    }

    @Test
    fun `a blank or unset credential is refused`() {
        assertEquals("unset", SecretGuard.credentialWeakness(null))
        assertEquals("unset", SecretGuard.credentialWeakness(""))
        assertEquals("unset", SecretGuard.credentialWeakness("   "))
    }

    @Test
    fun `short credentials are refused whatever they say`() {
        assertNotNull(SecretGuard.credentialWeakness("a7Kd93Lq"))
        assertNotNull(SecretGuard.credentialWeakness("tracedown"))
    }

    @Test
    fun `a value with no entropy is refused even at full length`() {
        assertNotNull(SecretGuard.credentialWeakness("0".repeat(64)))
        assertNotNull(SecretGuard.credentialWeakness("abababababababababababababababab"))
    }

    @Test
    fun `a pattern repeated to length is refused`() {
        // The shape of a hand-made "long enough" key: a varied unit, tiled.
        assertNotNull(SecretGuard.credentialWeakness("deadbeefcafebabe".repeat(4)))
    }

    @Test
    fun `values that say they are placeholders are refused`() {
        for (value in listOf(
            "please-change-me-before-production-really",
            "dev.signing.key.for.the.local.stack.only",
            "insecureinsecureinsecureinsecure1",
            "my-example-signing-secret-value-42",
        )) {
            assertNotNull(SecretGuard.credentialWeakness(value), value)
        }
    }

    @Test
    fun `a generated credential is accepted`() {
        // Random hex and random base64 of the sizes these variables actually
        // take. If this test ever fails, the guard has become a false-positive
        // machine and would refuse a correctly configured production deploy.
        assertNull(SecretGuard.credentialWeakness("9f2c71a05bd34e6f8813ac47de90b25c1f6a83049eb27d5c0a4318fe7629db1a"))
        assertNull(SecretGuard.credentialWeakness("Xq3Zr7Tn1LpV8sKd2WgB9yHc4mUeJf6A"))
        assertNull(SecretGuard.credentialWeakness("kZ7t+Qw1/9Rm2XcV4bNp8LsHg3JdY6Ae0FuTi5Ow"))
    }

    @Test
    fun `requireSecure refuses a published credential in production`() {
        val e = assertFailsWith<IllegalStateException> {
            SecretGuard.requireSecure(
                "production",
                "test-service",
                checks = emptyMap(),
                credentials = mapOf(
                    "PLATFORM_AES_KEY" to
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            )
        }
        assertTrue(e.message!!.contains("PLATFORM_AES_KEY"))
    }

    @Test
    fun `requireSecure lists every offending credential at once`() {
        val e = assertFailsWith<IllegalStateException> {
            SecretGuard.requireSecure(
                "production",
                "test-service",
                checks = emptyMap(),
                credentials = mapOf(
                    "PLATFORM_AES_KEY" to "0".repeat(64),
                    "JWT_SECRET" to "dev-jwt-secret-change-me-for-prod",
                ),
            )
        }
        assertTrue(e.message!!.contains("PLATFORM_AES_KEY"))
        assertTrue(e.message!!.contains("JWT_SECRET"))
    }

    @Test
    fun `requireSecure passes with generated credentials`() {
        SecretGuard.requireSecure(
            "production",
            "test-service",
            checks = emptyMap(),
            credentials = mapOf(
                "PLATFORM_AES_KEY" to "9f2c71a05bd34e6f8813ac47de90b25c1f6a83049eb27d5c0a4318fe7629db1a",
                "JWT_SECRET" to "Xq3Zr7Tn1LpV8sKd2WgB9yHc4mUeJf6A",
            ),
        )
    }

    @Test
    fun `dev still boots on the published values`() {
        // The whole point of the environment gate: a checkout runs with zero
        // configuration, and only production refuses.
        SecretGuard.requireSecure(
            "dev",
            "test-service",
            checks = emptyMap(),
            credentials = publishedDevValues.associateBy { "VALUE_$it" },
        )
    }

    @Test
    fun `the published-value list is loaded from the classpath`() {
        val values = SecretGuard.publishedCredentialValues()
        assertTrue(values.isNotEmpty(), "${SecretGuard.PUBLISHED_VALUES_RESOURCE} did not load")
        assertTrue(values.contains("0".repeat(64)))
        assertTrue(values.none { it.startsWith("#") })
    }
}
