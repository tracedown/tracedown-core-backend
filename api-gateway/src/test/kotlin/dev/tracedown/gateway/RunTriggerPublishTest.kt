package dev.tracedown.gateway

import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.gateway.util.ScheduleNudge
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * How a run asked for reaches a scheduler of either age, during a rolling
 * deploy — the gateway's half (the scheduler's is in the scheduler's tests).
 *
 * A scheduler from before run handles reads `probe:trigger` with
 * `UUID.fromString` and nothing else; one that knows them reads `probe:run`
 * too. The gateway publishes the run with its id on the second, and only when
 * nobody heard it falls back to the bare id on the first — never both to one
 * scheduler, which would be two runs.
 */
class RunTriggerPublishTest {

    private val connection = RedisFactory.createConnection(TestRedis.url)
    private val subscribers = mutableListOf<StatefulRedisPubSubConnection<String, String>>()

    @BeforeEach
    fun wire() = ScheduleNudge.init { connection.sync() }

    @AfterEach
    fun close() {
        subscribers.forEach { it.close() }
        subscribers.clear()
        connection.close()
    }

    /** A scheduler subscribed to [channels]: what it hears, as (channel, message). */
    private fun scheduler(vararg channels: String): LinkedBlockingQueue<Pair<String, String>> {
        val heard = LinkedBlockingQueue<Pair<String, String>>()
        val connection = RedisFactory.createPubSubConnection(TestRedis.url)
        connection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                heard.add(channel to message)
            }
        })
        connection.sync().subscribe(*channels)
        subscribers += connection
        return heard
    }

    @Test
    fun `an old scheduler is sent the bare service id, which it reads as it always has`() {
        val old = scheduler(RunTrigger.TRIGGER_CHANNEL)
        val serviceId = UUID.randomUUID()

        assertEquals(1L, ScheduleNudge.trigger(serviceId, UUID.randomUUID()))

        val (channel, message) = old.poll(5, TimeUnit.SECONDS)!!
        assertEquals(RunTrigger.TRIGGER_CHANNEL, channel)
        // Exactly what a scheduler from before run handles does with it.
        assertEquals(serviceId, UUID.fromString(message))
        assertNull(old.poll(300, TimeUnit.MILLISECONDS), "one request, one message")
    }

    @Test
    fun `a new scheduler is sent the run with its id, and nothing on the bare channel`() {
        val new = scheduler(RunTrigger.TRIGGER_CHANNEL, RunTrigger.RUN_CHANNEL)
        val serviceId = UUID.randomUUID()
        val runId = UUID.randomUUID()

        assertEquals(1L, ScheduleNudge.trigger(serviceId, runId))

        val (channel, message) = new.poll(5, TimeUnit.SECONDS)!!
        assertEquals(RunTrigger.RUN_CHANNEL, channel)
        assertEquals(RunTrigger.Request(serviceId, runId), RunTrigger.decode(channel, message))
        assertNull(new.poll(300, TimeUnit.MILLISECONDS), "a second message would be a second run")
    }

    @Test
    fun `with an old and a new scheduler both up, the new one takes the run and the old one hears nothing`() {
        val old = scheduler(RunTrigger.TRIGGER_CHANNEL)
        val new = scheduler(RunTrigger.TRIGGER_CHANNEL, RunTrigger.RUN_CHANNEL)

        ScheduleNudge.trigger(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(RunTrigger.RUN_CHANNEL, new.poll(5, TimeUnit.SECONDS)!!.first)
        assertNull(old.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `with no scheduler listening the request is lost, and says so`() {
        assertEquals(0L, ScheduleNudge.trigger(UUID.randomUUID(), UUID.randomUUID()))
    }
}
