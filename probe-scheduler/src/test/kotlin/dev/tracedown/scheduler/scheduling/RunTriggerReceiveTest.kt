package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.scheduler.dispatch.DispatchItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a run asked for is read off pub/sub, from a gateway of either age —
 * the scheduler's half of the rolling-deploy story (the gateway's is in its
 * `RunTriggerPublishTest`).
 *
 * A gateway from before run handles sends the bare service id on
 * `probe:trigger`; one that knows them sends the run with its id on
 * `probe:run`, and the bare id only when no scheduler heard that. Both must
 * run, the first as a manual run with no id.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunTriggerReceiveTest {

    private val quartz = QuartzManager(1)

    @AfterAll
    fun shutdown() = quartz.shutdown()

    private fun receiver(): Pair<ScheduleSyncService, MutableList<DispatchItem>> {
        val enqueued = CopyOnWriteArrayList<DispatchItem>()
        val service = ScheduleSyncService(quartz, 60, pubSubConnection = noPubSub(), claims = null) { enqueued.add(it) }
        return service to enqueued
    }

    @Test
    fun `an old gateway's bare service id is run as a manual run with no id`() {
        val (service, enqueued) = receiver()
        val serviceId = UUID.randomUUID()
        service.onMessage(RunTrigger.TRIGGER_CHANNEL, serviceId.toString())
        assertEquals(listOf(DispatchItem(serviceId, manual = true, runId = null)), enqueued)
        assertEquals(RunTrigger.MANUAL, enqueued.single().trigger)
    }

    @Test
    fun `a run with its id is run under that id`() {
        val (service, enqueued) = receiver()
        val serviceId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        service.onMessage(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(serviceId, runId))
        assertEquals(listOf(DispatchItem(serviceId, manual = true, runId = runId)), enqueued)
    }

    @Test
    fun `a run that cannot be claimed is not run`() {
        // Redis does not answer the claim: another replica may have it, and
        // two results under one id would lose one.
        @Suppress("UNCHECKED_CAST")
        val failing = java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.sync.RedisCommands::class.java.classLoader,
            arrayOf(io.lettuce.core.api.sync.RedisCommands::class.java),
        ) { _, _, _ -> throw io.lettuce.core.RedisConnectionException("down") } as io.lettuce.core.api.sync.RedisCommands<String, String>
        val enqueued = CopyOnWriteArrayList<DispatchItem>()
        val service = ScheduleSyncService(quartz, 60, noPubSub(), claims = failing) { enqueued.add(it) }
        service.onMessage(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(UUID.randomUUID(), UUID.randomUUID()))
        assertTrue(enqueued.isEmpty(), "$enqueued")
    }

    @Test
    fun `a message that is not a run is dropped, never thrown`() {
        val (service, enqueued) = receiver()
        service.onMessage(RunTrigger.TRIGGER_CHANNEL, "not-a-uuid")
        service.onMessage(RunTrigger.RUN_CHANNEL, UUID.randomUUID().toString())
        service.onMessage(RunTrigger.RUN_CHANNEL, """{"serviceId":"${UUID.randomUUID()}"}""")
        service.onMessage(RunTrigger.RUN_CHANNEL, "{")
        service.onMessage("somewhere:else", UUID.randomUUID().toString())
        assertTrue(enqueued.isEmpty(), "$enqueued")
    }

    @Test
    fun `the wire format reads back what it writes, and the bare id is what every scheduler parses`() {
        val serviceId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        assertEquals(RunTrigger.Request(serviceId, runId), RunTrigger.decode(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(serviceId, runId)))
        assertEquals(RunTrigger.Request(serviceId, null), RunTrigger.decode(RunTrigger.TRIGGER_CHANNEL, serviceId.toString()))
        // The run message is not a UUID: a scheduler that only knows the bare
        // channel could never have been sent it there.
        assertNull(runCatching { UUID.fromString(RunTrigger.encodeRun(serviceId, runId)) }.getOrNull())
    }
}
