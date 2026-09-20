package dev.tracedown.gateway.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-time repair for endpoints that were named twice: once by the
 * scheduler and once, before it could, from the resolved URL alone.
 *
 * The interesting cases are all about *not* folding — a fold that guessed wrong
 * would move one endpoint's calls onto another and there would be nothing in
 * the response to say it had happened.
 */
class EndpointFoldingTest {

    @Test
    fun `a legacy key folds into the one method that shares its template`() {
        val folds = EndpointFolding.foldMap(
            listOf("GET https://api.example.com/x", "* https://api.example.com/x"),
        )
        assertEquals(mapOf("* https://api.example.com/x" to "GET https://api.example.com/x"), folds)
    }

    @Test
    fun `a legacy key with no method-qualified twin stands on its own`() {
        val folds = EndpointFolding.foldMap(
            listOf("GET https://api.example.com/other", "* https://api.example.com/x"),
        )
        assertTrue(folds.isEmpty())
    }

    @Test
    fun `a template two methods share is ambiguous and folds nowhere`() {
        // The legacy rows record no method, so there is no telling which of the
        // two they were. Either choice would move somebody else's calls.
        val folds = EndpointFolding.foldMap(
            listOf(
                "GET https://api.example.com/x",
                "DELETE https://api.example.com/x",
                "* https://api.example.com/x",
            ),
        )
        assertTrue(folds.isEmpty())
    }

    @Test
    fun `each legacy key is decided on its own template`() {
        val folds = EndpointFolding.foldMap(
            listOf(
                "GET https://api.example.com/a",
                "POST https://api.example.com/b",
                "PUT https://api.example.com/b",
                "* https://api.example.com/a",
                "* https://api.example.com/b",
                "* https://api.example.com/c",
            ),
        )
        assertEquals(
            mapOf("* https://api.example.com/a" to "GET https://api.example.com/a"),
            folds,
        )
    }

    @Test
    fun `a placeholder template never matches a fallback template`() {
        // The script's key keeps the variable's name; the fallback guesses the
        // shape back out of a resolved URL. `{orderId}` is not known to be the
        // same thing as `{id}`, and treating it as one would merge endpoints.
        val folds = EndpointFolding.foldMap(
            listOf("GET {p.baseUrl}/orders/{orderId}", "* https://api.example.com/orders/{id}"),
        )
        assertTrue(folds.isEmpty())
    }

    @Test
    fun `one legacy key never folds into another`() {
        val folds = EndpointFolding.foldMap(listOf("* https://api.example.com/x", "* https://api.example.com/x"))
        assertTrue(folds.isEmpty())
    }

    @Test
    fun `folding merges totals and keeps the target's place in the order`() {
        val totals = linkedMapOf(
            "GET https://api.example.com/a" to 10,
            "* https://api.example.com/a" to 5,
            "GET https://api.example.com/b" to 1,
        )
        val folded = EndpointFolding.fold(totals) { target, legacy -> target + legacy }

        assertEquals(
            listOf("GET https://api.example.com/a", "GET https://api.example.com/b"),
            folded.keys.toList(),
        )
        assertEquals(15, folded["GET https://api.example.com/a"])
        assertEquals(1, folded["GET https://api.example.com/b"])
    }

    @Test
    fun `a map with nothing to fold is handed back untouched`() {
        val totals = mapOf("GET https://api.example.com/a" to 10)
        assertSame(totals, EndpointFolding.fold(totals) { a, b -> a + b })
    }

    @Test
    fun `a mapping decided elsewhere applies to a window that holds only the legacy half`() {
        // The previous window is folded with the current window's mapping, or
        // `phases` and `previousPhases` would be two different populations. A
        // previous window that saw only the legacy rows reports them under the
        // name the current one gave the endpoint.
        val folds = mapOf("* https://api.example.com/a" to "GET https://api.example.com/a")
        val previous = mapOf("* https://api.example.com/a" to 7)

        val folded = EndpointFolding.fold(previous, folds) { target, legacy -> target + legacy }
        assertEquals(mapOf("GET https://api.example.com/a" to 7), folded)
    }
}
