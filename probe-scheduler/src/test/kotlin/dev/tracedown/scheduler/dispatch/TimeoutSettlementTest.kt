package dev.tracedown.scheduler.dispatch

import dev.tracedown.scheduler.crypto.TestPki
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * Whose a timed-out run is, settled before it is recorded.
 *
 * The rule under test: a timeout stops being announced only on positive
 * evidence that the endpoint was fine — the agent's own reading says it was
 * stalled in its own work, or a second agent reached the endpoint cleanly at
 * the same moment. Every doubt keeps the timeout and its alert.
 */
class TimeoutSettlementTest {

    private fun timedOut(attribution: String?, extraEvents: String = ""): JsonObject = Json.decodeFromString(
        """
        {"outcome":"timeout","calls":[],"error":"probe did not finish",
         "actions":{"notifications":[
           {"callIndex":-1,"conditionIndex":-1,"trigger":"timeout","scope":null,
            "notification":{"tag":"text","value":"timed out"}}$extraEvents]}
         ${if (attribution != null) ""","attribution":$attribution""" else ""}}
        """.trimIndent()
    )

    private val network = """
        {"verdict":"network","calls":[{"callIndex":0,"verdict":"network","reason":"the path was losing packets"}],
         "target":{"host":"api.example.com","port":443,"tls":true}}
    """.trimIndent()

    private fun witness(path: String?): suspend (TimeoutSettlement.Target) -> TimeoutSettlement.Witness? = {
        TimeoutSettlement.Witness(
            "eu-west-p01",
            path?.let { Json.decodeFromString<JsonObject>("""{"path":"$it"}""") },
        )
    }

    private val never: suspend (TimeoutSettlement.Target) -> TimeoutSettlement.Witness? = {
        error("the second agent must not be asked")
    }

    private fun settle(result: JsonObject, w: suspend (TimeoutSettlement.Target) -> TimeoutSettlement.Witness?) =
        runBlocking { TimeoutSettlement.settle(result, "us-east-p01", w) }

    private fun JsonObject.outcome() = this["outcome"]!!.jsonPrimitive.content
    private fun JsonObject.settlement() = this["attribution"]!!.jsonObject["settlement"]!!.jsonObject
    private fun JsonObject.triggers() =
        this["actions"]!!.jsonObject["notifications"]!!.jsonArray.map { it.jsonObject["trigger"]!!.jsonPrimitive.content }

    @Test
    fun `an endpoint that took the request and never answered stays a timeout, with no second opinion`() {
        val settled = settle(timedOut("""{"verdict":"endpoint","calls":[]}"""), never)

        assertEquals("timeout", settled.outcome())
        assertEquals(listOf("timeout"), settled.triggers())
        assertEquals("timeout", settled.settlement()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a result from an agent too old to read the connection is left exactly as it came`() {
        val result = timedOut(null)
        assertSame(result, settle(result, never))
    }

    @Test
    fun `only a timeout is settled`() {
        val result = Json.decodeFromString<JsonObject>("""{"outcome":"failure","attribution":{"verdict":"agent"}}""")
        assertSame(result, settle(result, never))
    }

    @Test
    fun `a run stalled in the agent's own work is an error, and its timeout event is gone`() {
        val failureEvent = """,{"callIndex":0,"conditionIndex":0,"trigger":"failure","scope":null,
            "notification":{"tag":"text","value":"x"}}"""
        val settled = settle(timedOut("""{"verdict":"agent","reason":"the run was in the agent's own work"}""", failureEvent), never)

        assertEquals("error", settled.outcome())
        assertEquals(listOf("failure"), settled.triggers())
        assertTrue(settled["error"]!!.jsonPrimitive.content.contains("agent us-east-p01's own work"))
        assertEquals("timeout", settled["attribution"]!!.jsonObject["observedOutcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a lossy path the endpoint is reachable around is the agent's — an error, not announced`() {
        val settled = settle(timedOut(network), witness("clean"))

        assertEquals("error", settled.outcome())
        assertEquals(emptyList<String>(), settled.triggers())
        val message = settled["error"]!!.jsonPrimitive.content
        assertTrue(message.contains("agent us-east-p01 lost its connection to api.example.com:443"), message)
        assertTrue(message.contains("agent eu-west-p01 reached it over a clean path"), message)
        assertEquals("eu-west-p01", settled.settlement()["witness"]!!.jsonObject["agent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an endpoint the second agent cannot reach cleanly either keeps its timeout`() {
        for (path in listOf("lossy", "unreachable", "refused", "something-new")) {
            val settled = settle(timedOut(network), witness(path))
            assertEquals("timeout", settled.outcome(), path)
            assertEquals(listOf("timeout"), settled.triggers(), path)
        }
    }

    @Test
    fun `with no second agent, or none that could answer, the timeout stands`() {
        assertEquals("timeout", settle(timedOut(network)) { null }.outcome())
        assertEquals("timeout", settle(timedOut(network), witness(null)).outcome())
    }

    @Test
    fun `a path verdict with nothing to re-check stands as a timeout`() {
        val settled = settle(timedOut("""{"verdict":"network","calls":[]}"""), never)
        assertEquals("timeout", settled.outcome())
    }

    @Test
    fun `an undecided reading is a timeout`() {
        assertEquals("timeout", settle(timedOut("""{"verdict":"unknown"}"""), never).outcome())
    }

    @Test
    fun `the second agent is from another region when there is one`() {
        val timedOut = AgentSelector.Agent(1, "us-east-p01", "https://a")
        val sameRegion = AgentSelector.Agent(2, "us-east-p02", "https://b")
        val elsewhere = AgentSelector.Agent(3, "eu-west-p01", "https://c")

        assertEquals(elsewhere, TimeoutSettlement.pickWitness(listOf(timedOut, sameRegion, elsewhere), timedOut))
        assertEquals(sameRegion, TimeoutSettlement.pickWitness(listOf(timedOut, sameRegion), timedOut))
        assertNull(TimeoutSettlement.pickWitness(listOf(timedOut), timedOut))
    }

    /** A fake agent over real mutual TLS, answering one request with [status] and [body]. */
    private fun agentAnswering(status: String, body: String, seen: MutableList<String>): Pair<java.net.ServerSocket, Int> {
        val agentKey = TestPki.key()
        val cert = TestPki.agentCert(agentKey, listOf(GeneralName(GeneralName.dNSName, "localhost")))
        val server = TestPki.agentServerSocket(agentKey, cert)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    seen += reader.readLine()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) read += reader.read(chars, read, length - read)
                    seen += String(chars)
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        return server to server.localPort
    }

    @Test
    fun `the second agent is asked to reach the endpoint, and its answer comes back`() {
        val seen = mutableListOf<String>()
        val (server, port) = agentAnswering("200 OK", """{"path":"clean","attempts":[]}""", seen)
        val factory = TestPki.clientFactory()
        try {
            val reading = runBlocking {
                AgentDispatchService(factory).reach(
                    "https://localhost:$port", TestPki.SLUG, TimeoutSettlement.Target("api.example.com", 443, true),
                )
            }
            assertEquals("clean", reading!!["path"]!!.jsonPrimitive.content)
            assertEquals("POST /reach HTTP/1.1", seen[0])
            assertEquals(
                Json.decodeFromString<JsonObject>("""{"host":"api.example.com","port":443,"tls":true}"""),
                Json.decodeFromString<JsonObject>(seen[1]),
            )
        } finally {
            factory.close()
            server.close()
        }
    }

    @Test
    fun `an agent too old to know the witness endpoint gives no answer, which settles nothing`() {
        val (server, port) = agentAnswering("404 Not Found", """{"detail":"Not Found"}""", mutableListOf())
        val factory = TestPki.clientFactory()
        try {
            val reading = runBlocking {
                AgentDispatchService(factory).reach(
                    "https://localhost:$port", TestPki.SLUG, TimeoutSettlement.Target("api.example.com", 443, true),
                )
            }
            assertNull(reading)
        } finally {
            factory.close()
            server.close()
        }
    }

}
