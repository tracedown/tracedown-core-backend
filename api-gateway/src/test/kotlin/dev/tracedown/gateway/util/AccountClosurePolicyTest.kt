package dev.tracedown.gateway.util

import dev.tracedown.gateway.data.auth.OwnedOrgSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What stops an account from closing itself.
 *
 * The endpoint has always refused an owner, and refused with the generic
 * `forbidden` code, so the caller could not be told which organization was in
 * the way or what to do about it. These pin the rule down: ownership blocks,
 * the blockers come back named, and the one organization nobody else is in can
 * be taken along on request.
 */
class AccountClosurePolicyTest {

    private fun org(name: String, soleMember: Boolean) =
        OwnedOrgSummary(id = "id-$name", name = name, soleMember = soleMember)

    @Test
    fun `an account owning nothing may close`() {
        assertTrue(AccountClosurePolicy.blocking(emptyList(), deleteOwnedOrgs = false).isEmpty())
        assertTrue(AccountClosurePolicy.blocking(emptyList(), deleteOwnedOrgs = true).isEmpty())
    }

    @Test
    fun `an owned organization blocks by default`() {
        val owned = listOf(org("Platform", soleMember = true))
        assertEquals(owned, AccountClosurePolicy.blocking(owned, deleteOwnedOrgs = false))
    }

    @Test
    fun `opting in clears a sole-member organization`() {
        val owned = listOf(org("Platform", soleMember = true))
        assertTrue(AccountClosurePolicy.blocking(owned, deleteOwnedOrgs = true).isEmpty())
    }

    @Test
    fun `opting in never clears an organization with other members`() {
        val owned = listOf(org("Shared", soleMember = false))
        assertEquals(owned, AccountClosurePolicy.blocking(owned, deleteOwnedOrgs = true))
        assertEquals(owned, AccountClosurePolicy.blocking(owned, deleteOwnedOrgs = false))
    }

    @Test
    fun `a mixed set reports only the ones still in the way`() {
        val mine = org("Mine", soleMember = true)
        val theirs = org("Theirs", soleMember = false)
        assertEquals(
            listOf(theirs),
            AccountClosurePolicy.blocking(listOf(mine, theirs), deleteOwnedOrgs = true),
        )
        assertEquals(
            listOf(mine, theirs),
            AccountClosurePolicy.blocking(listOf(mine, theirs), deleteOwnedOrgs = false),
        )
    }

    @Test
    fun `blockers keep their order so the refusal can list them`() {
        val owned = listOf(org("A", false), org("B", false), org("C", false))
        assertEquals(listOf("A", "B", "C"), AccountClosurePolicy.blocking(owned, false).map { it.name })
    }
}
