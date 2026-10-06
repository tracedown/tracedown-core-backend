package dev.tracedown.common.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The failure preview is built from whatever the executor wrote into
 * `calls[].assertions[]` (spec §9.2), so both record shapes have to come out
 * readable and no value shape may throw.
 */
class FailedAssertionsTest {

    private fun calls(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `a failed scope assertion keeps its scope, expected and actual`() {
        val failed = FailedAssertions.fromCalls(calls("""
            [{"assertions": [
              {"method": "expect", "scope": "status", "op": "eq", "outcome": "failed", "expected": 409, "actual": 404, "options": null}
            ]}]
        """))
        assertEquals(listOf(FailedAssertionPreview("status", "409", "404")), failed)
    }

    @Test
    fun `a failed assert condition carries its expression and left operand`() {
        val failed = FailedAssertions.fromCalls(calls("""
            [{"assertions": [
              {"method": "assert", "kind": "expect", "index": 0, "outcome": "failed",
               "expression": "this.body.error eq \"service_inactive\"", "actualLhs": "not_found", "actualRhs": "service_inactive"},
              {"method": "assert", "kind": "expect", "index": 1, "outcome": "failed",
               "expression": "this.body.id neq null", "actualLhs": null, "actualRhs": null}
            ]}]
        """))
        assertEquals(
            listOf(
                FailedAssertionPreview("assert", null, "not_found", "this.body.error eq \"service_inactive\""),
                FailedAssertionPreview("assert", null, "null", "this.body.id neq null"),
            ),
            failed,
        )
    }

    @Test
    fun `a list or object value is rendered as JSON instead of throwing`() {
        val failed = FailedAssertions.fromCalls(calls("""
            [{"assertions": [
              {"method": "expect", "scope": "status", "op": "eq", "outcome": "failed", "expected": [200, 404], "actual": 500},
              {"method": "check", "scope": "body", "op": "eq", "outcome": "failed", "expected": {"type": "object"}, "actual": "x"}
            ]}]
        """))
        assertEquals("[200,404]", failed[0].expected)
        assertEquals("""{"type":"object"}""", failed[1].expected)
    }

    @Test
    fun `passed and indeterminate are not failures, and malformed entries are skipped`() {
        val failed = FailedAssertions.fromCalls(calls("""
            [{"assertions": [
              {"method": "expect", "scope": "status", "outcome": "passed", "expected": 200, "actual": 200},
              {"method": "assert", "kind": "expect", "outcome": "indeterminate", "expression": "${'$'}${'$'}a gt 1", "actualLhs": null, "actualRhs": 1},
              "not an object", 7
            ]}, "not a call", {"assertions": null}, {"index": 2}]
        """))
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `the limit caps across calls`() {
        val one = """{"method": "expect", "scope": "status", "outcome": "failed", "expected": 200, "actual": 500}"""
        val failed = FailedAssertions.fromCalls(calls("""[{"assertions": [$one, $one, $one]}, {"assertions": [$one, $one, $one]}]"""), limit = 5)
        assertEquals(5, failed.size)
    }
}
