package dev.tracedown.gateway.util

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.OrgPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who may hand out which access level.
 *
 * The defect these cover: reaching the user-management surface and deciding what
 * to write into it were the same check. `users.write` admitted a caller, and
 * nothing then compared what they asked for against what they held — so a member
 * with `users=2, admin=0` could PATCH their own row to `admin=2` and take the org
 * danger zone and org-wide TOTP enforcement with it. The same escalation was
 * available one indirection away, by pointing the group they belonged to at
 * `admin=2`, or by having themselves pre-assigned to the Admins group on an
 * invite.
 */
class GrantPolicyTest {

    // ---- the callers ----

    /** The escalation's protagonist: full rights over users, none over admin. */
    private val userManager = perms(users = AccessLevel.WRITE)

    private val orgAdmin = perms(users = AccessLevel.WRITE, admin = AccessLevel.WRITE)

    private val owner = OrgPermissions.FULL

    private fun perms(
        users: Short = AccessLevel.NONE,
        settings: Short = AccessLevel.NONE,
        domains: Short = AccessLevel.NONE,
        webhooks: Short = AccessLevel.NONE,
        notifications: Short = AccessLevel.NONE,
        admin: Short = AccessLevel.NONE,
        workspaces: Short = AccessLevel.NONE,
        extra: Map<String, Short> = emptyMap(),
    ) = OrgPermissions(users, settings, domains, webhooks, notifications, admin, workspaces, false, extra)

    private val nothing = emptyMap<String, Short>()

    // ---- rule 1: nobody hands out what they do not hold ----

    @Test
    fun `a section cannot be raised past the caller's own level`() {
        // No workspaces access at all, so neither read nor write may be handed out.
        assertEquals(
            "workspaces",
            GrantPolicy.deniedSection(userManager, nothing, mapOf("workspaces" to AccessLevel.READ)),
        )
        assertEquals(
            "workspaces",
            GrantPolicy.deniedSection(userManager, nothing, mapOf("workspaces" to AccessLevel.WRITE)),
        )
    }

    @Test
    fun `a section can be raised to exactly the level the caller holds`() {
        val readOnly = perms(users = AccessLevel.WRITE, settings = AccessLevel.READ)
        assertNull(GrantPolicy.deniedSection(readOnly, nothing, mapOf("settings" to AccessLevel.READ)))
        assertEquals(
            "settings",
            GrantPolicy.deniedSection(readOnly, nothing, mapOf("settings" to AccessLevel.WRITE)),
        )
    }

    @Test
    fun `lowering is always allowed — taking access away grants nothing`() {
        val current = mapOf("settings" to AccessLevel.WRITE, "workspaces" to AccessLevel.WRITE)
        assertNull(
            GrantPolicy.deniedSection(
                userManager, current,
                mapOf("settings" to AccessLevel.NONE, "workspaces" to AccessLevel.READ),
            ),
        )
    }

    @Test
    fun `an unchanged level is not a grant`() {
        // Editing one section on a row that holds others must not require holding
        // those others — only the sections actually moving are judged.
        val current = mapOf("workspaces" to AccessLevel.WRITE, "users" to AccessLevel.NONE)
        assertNull(
            GrantPolicy.deniedSection(
                userManager, current,
                mapOf("workspaces" to AccessLevel.WRITE, "users" to AccessLevel.WRITE),
            ),
        )
    }

    @Test
    fun `sections absent from the request are untouched`() {
        assertNull(GrantPolicy.deniedSection(userManager, mapOf("admin" to AccessLevel.WRITE), nothing))
    }

    @Test
    fun `extension sections obey the same rule as built-ins`() {
        val caller = perms(users = AccessLevel.WRITE, extra = mapOf("reports" to AccessLevel.READ))
        assertNull(GrantPolicy.deniedSection(caller, nothing, mapOf("reports" to AccessLevel.READ)))
        assertEquals(
            "reports",
            GrantPolicy.deniedSection(caller, nothing, mapOf("reports" to AccessLevel.WRITE)),
        )
        // A section the caller has never heard of resolves to NONE, not to a pass.
        assertEquals(
            "audits",
            GrantPolicy.deniedSection(caller, nothing, mapOf("audits" to AccessLevel.READ)),
        )
    }

    // ---- rule 2: the admin section is admin-gated ----

    @Test
    fun `a users-write holder cannot grant admin`() {
        // The reported escalation, at its source.
        assertEquals(
            "admin",
            GrantPolicy.deniedSection(userManager, nothing, mapOf("admin" to AccessLevel.WRITE)),
        )
        // Read on admin is no consolation prize — it is still the admin section.
        assertEquals(
            "admin",
            GrantPolicy.deniedSection(userManager, nothing, mapOf("admin" to AccessLevel.READ)),
        )
    }

    @Test
    fun `a users-write holder cannot strip admin either`() {
        // Both directions: deciding who the org's admins are is an admin act, and
        // stripping every admin is its own kind of takeover.
        assertEquals(
            "admin",
            GrantPolicy.deniedSection(
                userManager, mapOf("admin" to AccessLevel.WRITE), mapOf("admin" to AccessLevel.NONE),
            ),
        )
    }

    @Test
    fun `an admin-write holder may move the admin section`() {
        assertNull(GrantPolicy.deniedSection(orgAdmin, nothing, mapOf("admin" to AccessLevel.WRITE)))
        assertNull(
            GrantPolicy.deniedSection(
                orgAdmin, mapOf("admin" to AccessLevel.WRITE), mapOf("admin" to AccessLevel.NONE),
            ),
        )
    }

    @Test
    fun `admin read is not enough to move the admin section`() {
        val adminReader = perms(users = AccessLevel.WRITE, admin = AccessLevel.READ)
        assertEquals(
            "admin",
            GrantPolicy.deniedSection(adminReader, nothing, mapOf("admin" to AccessLevel.WRITE)),
        )
    }

    @Test
    fun `the owner is constrained by none of it`() {
        assertNull(
            GrantPolicy.deniedSection(
                owner, nothing,
                mapOf(
                    "admin" to AccessLevel.WRITE,
                    "workspaces" to AccessLevel.WRITE,
                    "anything_a_module_registers" to AccessLevel.WRITE,
                ),
            ),
        )
    }

    @Test
    fun `the first offending section is the one reported`() {
        // Ordered map: the admin denial is reached before the workspaces one.
        val requested = linkedMapOf(
            "admin" to AccessLevel.WRITE,
            "workspaces" to AccessLevel.WRITE,
        )
        assertEquals("admin", GrantPolicy.deniedSection(userManager, nothing, requested))
    }

    // ---- TOTP enforcement rides the same gate ----

    @Test
    fun `only admin write may switch TOTP enforcement`() {
        assertFalse(GrantPolicy.mayGovernOrgPolicy(userManager))
        assertFalse(GrantPolicy.mayGovernOrgPolicy(perms(users = AccessLevel.WRITE, admin = AccessLevel.READ)))
        assertTrue(GrantPolicy.mayGovernOrgPolicy(orgAdmin))
        assertTrue(GrantPolicy.mayGovernOrgPolicy(owner))
    }

    @Test
    fun `requireOrgPolicyWrite refuses a users-write holder`() {
        assertThrows(ForbiddenException::class.java) { requireOrgPolicyWrite(userManager) }
        requireOrgPolicyWrite(orgAdmin)
    }

    // ---- joining a group is a grant of everything the group holds ----

    @Test
    fun `a users-write holder cannot be put into a group carrying admin`() {
        assertThrows(ForbiddenException::class.java) {
            requireGroupGrantable(userManager, mapOf("users" to AccessLevel.WRITE, "admin" to AccessLevel.WRITE))
        }
    }

    @Test
    fun `a users-write holder cannot be put into a group reaching past them`() {
        assertThrows(ForbiddenException::class.java) {
            requireGroupGrantable(userManager, mapOf("workspaces" to AccessLevel.WRITE))
        }
    }

    @Test
    fun `a group entirely within the caller's reach is assignable`() {
        requireGroupGrantable(userManager, mapOf("users" to AccessLevel.WRITE, "admin" to AccessLevel.NONE))
    }

    @Test
    fun `an admin may assign the admin-carrying group`() {
        requireGroupGrantable(orgAdmin, mapOf("users" to AccessLevel.WRITE, "admin" to AccessLevel.WRITE))
    }

    // ---- the throwing wrapper ----

    @Test
    fun `requireGrantable throws exactly when deniedSection reports a section`() {
        assertThrows(ForbiddenException::class.java) {
            requireGrantable(userManager, nothing, mapOf("admin" to AccessLevel.WRITE))
        }
        requireGrantable(userManager, nothing, mapOf("users" to AccessLevel.WRITE))
    }
}
