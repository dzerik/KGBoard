package com.kgboard.rgb.runtime

import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.settings.SettingsPreviewSession
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeIntegrationTest {
    @Test fun `shutdown flushes black on configured and preview only device then closes writer`() {
        val actor = ManualExecutor(); val network = ManualExecutor(); val clock = ManualClock()
        val scheduler = ManualScheduler(clock, actor)
        val writes = mutableListOf<Pair<Int, List<Color>>>()
        var closed = false
        val transport = object : com.kgboard.rgb.client.RgbTransport {
            override fun connect(endpoint: Endpoint) = listOf(testDevice(), testDevice())
            override fun write(device: Int, colors: List<Color>) { check(!closed); writes.add(device to colors.toList()) }
            override fun close() { closed = true }
        }
        lateinit var runtime: LightingRuntime
        val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) })
        runtime = LightingRuntime(actor, clock, scheduler, RuntimeReducer(), LightingPolicy(), writer, RuntimeState())
        fun drain() { repeat(100) { actor.drain(); network.drain(); if (!actor.hasPending && !network.hasPending) return }; error("Queues failed to settle") }
        val events = RuntimeEvents { runtime.dispatch(it); drain() }
        events.dispatch(RuntimeEvent.FocusChanged(true)); events.dispatch(RuntimeEvent.ConnectRequested())
        val preview = SettingsPreviewSession(events)
        preview.preview(1, Color.RED, listOf(1))
        assertEquals(Color.RED, writes.last { it.first == 1 }.second[1])
        runtime.close(); drain()
        assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 0 }.second)
        assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 1 }.second)
        assertTrue(closed)
    }

    @Test fun `project switch preview disable focus and static deadline restore physical output`() {
        val h = RuntimeHarness(RuntimeSettings(idleColor = "#FFFFFF", dimBrightness = 10))
        val events = RuntimeEvents { h.send(it) }
        val a = ProjectRuntimeBinding(events, ProjectSettingsSnapshot())
        val b = ProjectRuntimeBinding(events, ProjectSettingsSnapshot())
        a.start(); b.start()
        h.send(RuntimeEvent.ProjectActivated(a.id)); h.send(RuntimeEvent.FocusChanged(true))
        h.send(RuntimeEvent.OperationChanged(a.id, OperationState(OperationId("run-a"), OperationKind.RUN, OperationPhase.RUNNING)))
        val run = h.lastFrame(0)
        val preview = SettingsPreviewSession(events)
        preview.preview(0, Color.RED, listOf(0)); assertEquals(Color.RED, h.lastFrame(0)[0])
        preview.close(); assertEquals(run, h.lastFrame(0))
        h.send(RuntimeEvent.ProjectActivated(b.id)); a.dispose()
        assertEquals(List(3) { Color.WHITE }, h.lastFrame(0))
        h.send(RuntimeEvent.FocusChanged(false)); assertNotEquals(Color.WHITE, h.lastFrame(0)[0])
        h.send(RuntimeEvent.FocusChanged(true)); assertEquals(Color.WHITE, h.lastFrame(0)[0])
        h.send(RuntimeEvent.SettingsApplied(h.snapshotForTest().settings.copy(enabled = false)))
        assertEquals(List(3) { Color.WHITE }, h.lastFrame(0))
        b.dispose(); h.close(); assertTrue(h.writer.closed)
    }
    @Test fun `real writer retries failed session with new geometry and preserves foreground owner`() {
        val actor = ManualExecutor(); val network = ManualExecutor(); val clock = ManualClock()
        val scheduler = ManualScheduler(clock, actor)
        val writes = mutableListOf<Pair<Int, List<Color>>>()
        var devices = listOf(testDevice(), testDevice())
        var fail = false
        val transport = object : com.kgboard.rgb.client.RgbTransport {
            override fun connect(endpoint: Endpoint) = devices
            override fun write(device: Int, colors: List<Color>) {
                if (fail) throw java.io.IOException("reset")
                writes.add(device to colors.toList())
            }
            override fun close() {}
        }
        lateinit var runtime: LightingRuntime
        val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) })
        runtime = LightingRuntime(actor, clock, scheduler, RuntimeReducer(), LightingPolicy(), writer,
            RuntimeState(settings = RuntimeSettings(dimOnFocusLoss = false, autoConnect = false)))
        fun drain() { repeat(100) { actor.drain(); network.drain(); if (!actor.hasPending && !network.hasPending) return }; error("Queues failed to settle") }
        val events = RuntimeEvents { runtime.dispatch(it); drain() }
        val a = ProjectRuntimeBinding(events, ProjectSettingsSnapshot()); val b = ProjectRuntimeBinding(events, ProjectSettingsSnapshot())
        try {
            a.start(); b.start(); events.dispatch(RuntimeEvent.ProjectActivated(b.id)); events.dispatch(RuntimeEvent.FocusChanged(true))
            events.dispatch(RuntimeEvent.ConnectRequested())
            assertTrue(runtime.snapshot().connection is ConnectionState.Ready)
            val op = OperationState(OperationId("b-run"), OperationKind.RUN, OperationPhase.RUNNING)
            events.dispatch(RuntimeEvent.OperationChanged(b.id, op))
            val running = writes.last().second
            a.dispose(); assertEquals(b.id, runtime.snapshot().activeProject)
            fail = true
            events.dispatch(RuntimeEvent.OperationChanged(b.id, op.copy(phase = OperationPhase.TERMINATED, outcome = OperationOutcome.FAILURE, revision = 2)))
            assertTrue(runtime.snapshot().connection is ConnectionState.Backoff)
            fail = false; devices = listOf(testDevice(7))
            scheduler.advanceBy(1000); drain()
            assertTrue(runtime.snapshot().connection is ConnectionState.Ready)
            assertEquals(7, writes.last().second.size)
            assertNotEquals(running, writes.last().second)
            val preview = SettingsPreviewSession(events)
            preview.preview(0, Color.BLUE, listOf(0))
            assertEquals(Color.BLUE, writes.last().second[0])
            events.dispatch(RuntimeEvent.DisconnectRequested)
            assertNotEquals(Color.BLUE, writes.last().second[0])
            preview.close()
            assertFalse(runtime.snapshot().connectionWanted)
        } finally { runtime.close(); drain() }
    }
}
