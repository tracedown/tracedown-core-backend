package dev.tracedown.gateway.util

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.common.auth.canWrite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who may decrypt a stored variable.
 *
 * The defect these cover: reveal was gated on READ. All five reveal endpoints —
 * org (`requireOrgRead { it.settings }`), webhook (`requireOrgRead { it.webhooks }`),
 * workspace / project / service (`requireXAccess`, the read-level resource
 * check) — admitted the caller and then decrypted unconditionally. A member
 * granted read and nothing else, on any of those five scopes, could walk the
 * variable list and pull every encrypted value back in the clear. Encrypting a
 * value at rest and then handing the plaintext to anyone who can list it is a
 * measure with no access-control half.
 *
 * Reveal is a write-level operation now: seeing a configured credential is the
 * same privilege as being able to replace it.
 */
class VariableRevealPolicyTest {

    // ---- the two things the decision is made from ----

    private val secret = true
    private val plain = false
    private val writer = true
    private val reader = false

    // ---- the defect itself ----

    @Test
    fun `a read-only caller cannot reveal an encrypted variable`() {
        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_READ_ONLY,
            VariableRevealPolicy.decide(plain, reader),
        )
    }

    @Test
    fun `a caller with write access can reveal it`() {
        assertEquals(
            VariableRevealPolicy.Decision.REVEAL,
            VariableRevealPolicy.decide(plain, writer),
        )
    }

    // ---- secrets are refused for everyone, and refused the SAME way ----

    @Test
    fun `a secret is never revealed, at any access level`() {
        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_SECRET,
            VariableRevealPolicy.decide(secret, reader),
        )
        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_SECRET,
            VariableRevealPolicy.decide(secret, writer),
        )
    }

    @Test
    fun `the refusal for a secret does not disclose the caller's grant`() {
        // Same answer for a reader and a writer: the difference between the two
        // refusals must not become an oracle for what the caller holds.
        assertEquals(
            VariableRevealPolicy.decide(secret, reader),
            VariableRevealPolicy.decide(secret, writer),
        )
    }

    // ---- the level the five endpoints feed in ----

    @Test
    fun `org-level grants resolve to write only at the write level`() {
        // The org and webhook endpoints pass a section level straight in. Read
        // access on the section is exactly what used to be sufficient.
        assertFalse(perms(settings = AccessLevel.READ).settings.canWrite())
        assertTrue(perms(settings = AccessLevel.WRITE).settings.canWrite())
        assertFalse(perms(webhooks = AccessLevel.READ).webhooks.canWrite())
        assertTrue(perms(webhooks = AccessLevel.WRITE).webhooks.canWrite())

        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_READ_ONLY,
            VariableRevealPolicy.decide(plain, perms(settings = AccessLevel.READ).settings.canWrite()),
        )
        assertEquals(
            VariableRevealPolicy.Decision.REVEAL,
            VariableRevealPolicy.decide(plain, perms(settings = AccessLevel.WRITE).settings.canWrite()),
        )
    }

    @Test
    fun `the owner reveals everything except secrets`() {
        assertEquals(
            VariableRevealPolicy.Decision.REVEAL,
            VariableRevealPolicy.decide(plain, OrgPermissions.FULL.settings.canWrite()),
        )
        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_SECRET,
            VariableRevealPolicy.decide(secret, OrgPermissions.FULL.settings.canWrite()),
        )
    }

    @Test
    fun `no access at all is still a refusal, not a reveal`() {
        // The endpoints reject a non-member before they get here; this only
        // pins that NONE never reads as write by arithmetic accident.
        assertEquals(
            VariableRevealPolicy.Decision.REFUSED_READ_ONLY,
            VariableRevealPolicy.decide(plain, perms().settings.canWrite()),
        )
    }

    private fun perms(
        settings: Short = AccessLevel.NONE,
        webhooks: Short = AccessLevel.NONE,
    ) = OrgPermissions(
        users = AccessLevel.NONE,
        settings = settings,
        domains = AccessLevel.NONE,
        webhooks = webhooks,
        notifications = AccessLevel.NONE,
        admin = AccessLevel.NONE,
        workspaces = AccessLevel.NONE,
        isOwner = false,
    )
}
