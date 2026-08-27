package dev.tracedown.gateway.controllers.presets

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.OrgPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Which script templates a caller may see.
 *
 * A preset with no workspace is org-wide and every member may read it. A preset
 * carrying a workspace id is workspace content — its script is written for, and
 * about, that workspace. The listing took a membership check and nothing else,
 * so any member could name any workspace in the query string and read the
 * scripts stored there without ever holding a grant on it.
 *
 * The rule these cover: the workspace scope survives into the query only when
 * the caller could read that workspace anyway. Everything else — a workspace in
 * another org, a workspace they hold nothing on, no workspace named at all —
 * collapses to the org-wide list, which is also what makes the listing silent
 * about whether the named workspace exists.
 */
class RulePresetScopeTest {

    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()

    /** Every workspace id is a live one in the caller's org. */
    private val resolves: (UUID) -> Boolean = { true }

    /** No workspace id resolves — stands for an id belonging to another org. */
    private val resolvesNothing: (UUID) -> Boolean = { false }

    private fun perms(
        workspaces: Short = AccessLevel.NONE,
        resources: Map<String, Short> = emptyMap(),
        owner: Boolean = false,
    ) = CachedPermissions(
        org = if (owner) OrgPermissions.FULL
        else OrgPermissions(
            AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE, AccessLevel.NONE,
            AccessLevel.NONE, AccessLevel.NONE, workspaces,
        ),
        resources = resources,
        totpRequired = false,
    )

    /** A member with no grant at all — the escalation's protagonist. */
    private val bareMember = perms()

    // ---- the defect ----

    @Test
    fun `a member without a grant gets no workspace scope`() {
        assertNull(
            RulePresetController.visibleWorkspaceScope(bareMember, workspace, resolves),
        )
    }

    @Test
    fun `a direct workspace grant opens the scope`() {
        val granted = perms(resources = mapOf("workspace::$workspace" to AccessLevel.READ))
        assertEquals(
            workspace,
            RulePresetController.visibleWorkspaceScope(granted, workspace, resolves),
        )
    }

    @Test
    fun `a grant on one workspace does not open another`() {
        val granted = perms(resources = mapOf("workspace::$otherWorkspace" to AccessLevel.WRITE))
        assertNull(
            RulePresetController.visibleWorkspaceScope(granted, workspace, resolves),
        )
    }

    // ---- who legitimately sees everything ----

    @Test
    fun `org-wide workspaces read opens any workspace in the org`() {
        val orgReader = perms(workspaces = AccessLevel.READ)
        assertEquals(
            workspace,
            RulePresetController.visibleWorkspaceScope(orgReader, workspace, resolves),
        )
    }

    @Test
    fun `the owner sees every scope`() {
        assertEquals(
            workspace,
            RulePresetController.visibleWorkspaceScope(perms(owner = true), workspace, resolves),
        )
    }

    // ---- org containment comes first ----

    @Test
    fun `a workspace outside the org yields no scope even for the owner`() {
        assertNull(
            RulePresetController.visibleWorkspaceScope(perms(owner = true), workspace, resolvesNothing),
        )
    }

    @Test
    fun `containment is checked before the grant`() {
        // The grant map names the workspace, but it does not resolve inside the
        // org — a stale or forged grant key must not reach across the boundary.
        val granted = perms(resources = mapOf("workspace::$workspace" to AccessLevel.WRITE))
        assertNull(
            RulePresetController.visibleWorkspaceScope(granted, workspace, resolvesNothing),
        )
    }

    // ---- no workspace named ----

    @Test
    fun `no workspace named means the org-wide list`() {
        assertNull(RulePresetController.visibleWorkspaceScope(perms(owner = true), null, resolves))
        assertNull(RulePresetController.visibleWorkspaceScope(bareMember, null, resolves))
    }

    @Test
    fun `a null workspace never reaches the database`() {
        var probed = false
        RulePresetController.visibleWorkspaceScope(bareMember, null) { probed = true; true }
        assertEquals(false, probed)
    }
}
