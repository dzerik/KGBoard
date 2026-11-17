package com.kgboard.rgb.runtime

import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.settings.SettingsPreviewSession
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeIntegrationTest {
    @Test
    fun `delivery stays pending until actual blocked transport write returns`() {
        val actor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val network = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sent = java.util.concurrent.CountDownLatch(1)
        val transport = object : com.kgboard.rgb.client.RgbTransport {
            override fun connect(endpoint: Endpoint) = testDevices()
            override fun write(device: Int, colors: List<Color>) {
                started.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }

            override fun close() {}
        }
        lateinit var runtime: LightingRuntime
        val writer =
            CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) }, RuntimeClock { 42 })
        runtime = LightingRuntime(actor, RuntimeClock { 0 }, object : RuntimeScheduler {
            override fun scheduleAt(timeMs: Long, action: () -> Unit) = Cancellation {}
        }, RuntimeReducer(), LightingPolicy(), writer, RuntimeState(settings = RuntimeSettings().withTestBindings()))
        runtime.subscribe { if (it.diagnostics.delivery.values.any { d -> d.lastSent != null }) sent.countDown() }
        try {
            runtime.dispatch(RuntimeEvent.ConnectRequested())
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val pending = runtime.snapshot().diagnostics.delivery.values.single()
            assertNotNull(pending.pending)
            assertNull(pending.lastSent)
            assertNull(pending.sentAtMs)
            release.countDown()
            assertTrue(sent.await(3, java.util.concurrent.TimeUnit.SECONDS))
            actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val success = runtime.snapshot().diagnostics.delivery.values.single()
            assertNull(success.pending)
            assertEquals(pending.pending, success.lastSent)
            assertEquals(42L, success.sentAtMs)
        } finally {
            release.countDown(); runtime.close(); actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            actor.shutdown(); network.shutdown()
            assertTrue(actor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(network.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun `preview cleanup follows an in flight write on cancel apply disable device and endpoint changes`() {
        for (action in listOf("cancel", "apply", "disable", "device", "endpoint")) {
            val actor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val network = java.util.concurrent.Executors.newSingleThreadExecutor()
            val ready = java.util.concurrent.CountDownLatch(1)
            val red = java.util.concurrent.CountDownLatch(1)
            val blocked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val retired = java.util.concurrent.CountDownLatch(1)
            val writes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, List<Color>>>()
            val shouldBlock = java.util.concurrent.atomic.AtomicBoolean()
            val settings = RuntimeSettings(idleColor = "#123456", dimOnFocusLoss = false).withTestBindings()
            val idle = Color(0x12, 0x34, 0x56)
            val transport = object : com.kgboard.rgb.client.RgbTransport {
                override fun connect(endpoint: Endpoint) = testDevices()
                override fun write(device: Int, colors: List<Color>) {
                    if (device == 1 && shouldBlock.compareAndSet(true, false)) {
                        blocked.countDown()
                        check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    writes.add(device to colors.toList())
                    if (device == 1 && colors[1] == Color.RED) red.countDown()
                    if (device == 1 && red.count == 0L && colors == List(3) { idle }) retired.countDown()
                }

                override fun close() {}
            }
            lateinit var runtime: LightingRuntime
            val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) })
            val scheduler = object : RuntimeScheduler {
                override fun scheduleAt(timeMs: Long, action: () -> Unit) = Cancellation {}
            }
            runtime = LightingRuntime(
                actor, RuntimeClock { 0 }, scheduler,
                RuntimeReducer(), LightingPolicy(), writer, RuntimeState(settings = settings)
            )
            runtime.subscribe { if (it.connection is ConnectionState.Ready) ready.countDown() }
            val preview = SettingsPreviewSession(runtime)
            try {
                runtime.dispatch(RuntimeEvent.ConnectRequested())
                assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS), action)
                val rendered = runtime.snapshot().capturedChoice(1)
                preview.previewColor(runtime.snapshot(), 1, Color.RED, listOf(1))
                assertTrue(red.await(3, java.util.concurrent.TimeUnit.SECONDS), action)
                shouldBlock.set(true)
                preview.previewColor(runtime.snapshot(), 1, Color.BLUE, listOf(1))
                assertTrue(blocked.await(3, java.util.concurrent.TimeUnit.SECONDS), action)
                when (action) {
                    "cancel" -> preview.close()
                    "apply" -> preview.applied()
                    "disable" -> runtime.dispatch(RuntimeEvent.SettingsApplied(settings.copy(enabled = false)))
                    "device" -> preview.previewColor(runtime.snapshot(), 0, Color.GREEN, listOf(0))
                    "endpoint" -> preview.connect(Endpoint("other-server", 6742))
                }
                actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                release.countDown()
                assertTrue(retired.await(3, java.util.concurrent.TimeUnit.SECONDS), action)
                network.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(List(3) { idle }, writes.last { it.first == 1 }.second, action)
                if (action == "endpoint") {
                    actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    preview.preview(rendered, com.kgboard.rgb.settings.PreviewContent.DeviceTest)
                    actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    assertNull(runtime.snapshot().preview)
                }
            } finally {
                release.countDown(); preview.close(); runtime.close()
                actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                actor.shutdown(); network.shutdown()
                assertTrue(actor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(network.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `shutdown retires preview only device while an earlier write is blocked`() {
        val actor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val network = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ready = java.util.concurrent.CountDownLatch(1)
        val previewSent = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, List<Color>>>()
        val shouldBlock = java.util.concurrent.atomic.AtomicBoolean()
        val transport = object : com.kgboard.rgb.client.RgbTransport {
            override fun connect(endpoint: Endpoint) = testDevices()
            override fun write(device: Int, colors: List<Color>) {
                if (device == 1 && shouldBlock.compareAndSet(true, false)) {
                    blocked.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                }
                writes.add(device to colors.toList())
                if (device == 1 && colors[1] == Color.RED) previewSent.countDown()
            }

            override fun close() {}
        }
        lateinit var runtime: LightingRuntime
        val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) })
        val scheduler = object : RuntimeScheduler {
            override fun scheduleAt(timeMs: Long, action: () -> Unit) = Cancellation { }
        }
        runtime = LightingRuntime(
            actor, RuntimeClock { System.nanoTime() / 1_000_000 }, scheduler,
            RuntimeReducer(), LightingPolicy(), writer, RuntimeState(settings = RuntimeSettings().withTestBindings())
        )
        runtime.subscribe { if (it.connection is ConnectionState.Ready) ready.countDown() }
        val preview = SettingsPreviewSession(runtime)
        try {
            runtime.dispatch(RuntimeEvent.FocusChanged(true))
            runtime.dispatch(RuntimeEvent.ConnectRequested())
            assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
            preview.previewColor(runtime.snapshot(), 1, Color.RED, listOf(1))
            assertTrue(previewSent.await(3, java.util.concurrent.TimeUnit.SECONDS))
            shouldBlock.set(true)
            preview.previewColor(runtime.snapshot(), 1, Color.BLUE, listOf(1))
            assertTrue(blocked.await(3, java.util.concurrent.TimeUnit.SECONDS))
            runtime.close()
            actor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            network.shutdown()
            assertTrue(network.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 0 }.second)
            assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 1 }.second)
        } finally {
            release.countDown(); preview.close(); runtime.close()
            actor.shutdownNow(); network.shutdownNow()
            assertTrue(actor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(network.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun `shutdown flushes black on configured and preview only device then closes writer`() {
        val actor = ManualExecutor();
        val network = ManualExecutor();
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock, actor)
        val writes = mutableListOf<Pair<Int, List<Color>>>()
        var closed = false
        val transport = object : com.kgboard.rgb.client.RgbTransport {
            override fun connect(endpoint: Endpoint) = testDevices()
            override fun write(device: Int, colors: List<Color>) {
                check(!closed); writes.add(device to colors.toList())
            }

            override fun close() {
                closed = true
            }
        }
        lateinit var runtime: LightingRuntime
        val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { runtime.dispatch(it) })
        runtime = LightingRuntime(
            actor,
            clock,
            scheduler,
            RuntimeReducer(),
            LightingPolicy(),
            writer,
            RuntimeState(settings = RuntimeSettings().withTestBindings())
        )
        fun drain() {
            repeat(100) { actor.drain(); network.drain(); if (!actor.hasPending && !network.hasPending) return }; error(
                "Queues failed to settle"
            )
        }

        val events = RuntimeEvents { runtime.dispatch(it); drain() }
        events.dispatch(RuntimeEvent.FocusChanged(true)); events.dispatch(RuntimeEvent.ConnectRequested())
        val preview = SettingsPreviewSession(events)
        preview.previewColor(runtime.snapshot(), 1, Color.RED, listOf(1))
        assertEquals(Color.RED, writes.last { it.first == 1 }.second[1])
        runtime.close(); drain()
        assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 0 }.second)
        assertEquals(List(3) { Color.BLACK }, writes.last { it.first == 1 }.second)
        assertTrue(closed)
    }

    @Test
    fun `project switch preview disable focus and static deadline restore physical output`() {
        val h = RuntimeHarness(RuntimeSettings(idleColor = "#FFFFFF", dimBrightness = 10).withTestBindings())
        val events = RuntimeEvents { h.send(it) }
        val a = ProjectRuntimeBinding(events, ProjectSettingsSnapshot().withTestTargets())
        val b = ProjectRuntimeBinding(events, ProjectSettingsSnapshot().withTestTargets())
        a.start(); b.start()
        h.send(RuntimeEvent.ProjectActivated(a.id)); h.send(RuntimeEvent.FocusChanged(true))
        h.send(
            RuntimeEvent.OperationChanged(
                a.id,
                OperationState(OperationId("run-a"), OperationKind.RUN, OperationPhase.RUNNING)
            )
        )
        val run = h.lastFrame(0)
        val preview = SettingsPreviewSession(events)
        preview.previewColor(h.snapshotForTest(), 0, Color.RED, listOf(0)); assertEquals(Color.RED, h.lastFrame(0)[0])
        preview.close(); assertEquals(run, h.lastFrame(0))
        h.send(RuntimeEvent.ProjectActivated(b.id)); a.dispose()
        assertEquals(List(3) { Color.WHITE }, h.lastFrame(0))
        h.send(RuntimeEvent.FocusChanged(false)); assertNotEquals(Color.WHITE, h.lastFrame(0)[0])
        h.send(RuntimeEvent.FocusChanged(true)); assertEquals(Color.WHITE, h.lastFrame(0)[0])
        h.send(RuntimeEvent.SettingsApplied(h.snapshotForTest().settings.copy(enabled = false)))
        assertEquals(List(3) { Color.WHITE }, h.lastFrame(0))
        b.dispose(); h.close(); assertTrue(h.writer.closed)
    }

    @Test
    fun `real writer retries failed session with new geometry and preserves foreground owner`() {
        val actor = ManualExecutor();
        val network = ManualExecutor();
        val clock = ManualClock()
        val scheduler = ManualScheduler(clock, actor)
        val writes = mutableListOf<Pair<Int, List<Color>>>()
        var devices = testDevices()
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
        runtime = LightingRuntime(
            actor, clock, scheduler, RuntimeReducer(), LightingPolicy(), writer,
            RuntimeState(settings = RuntimeSettings(dimOnFocusLoss = false, autoConnect = false).withTestBindings())
        )
        fun drain() {
            repeat(100) { actor.drain(); network.drain(); if (!actor.hasPending && !network.hasPending) return }; error(
                "Queues failed to settle"
            )
        }

        val events = RuntimeEvents { runtime.dispatch(it); drain() }
        val a = ProjectRuntimeBinding(events, ProjectSettingsSnapshot().withTestTargets());
        val b = ProjectRuntimeBinding(events, ProjectSettingsSnapshot().withTestTargets())
        try {
            a.start(); b.start(); events.dispatch(RuntimeEvent.ProjectActivated(b.id)); events.dispatch(
                RuntimeEvent.FocusChanged(
                    true
                )
            )
            events.dispatch(RuntimeEvent.ConnectRequested())
            assertTrue(runtime.snapshot().connection is ConnectionState.Ready)
            val op = OperationState(OperationId("b-run"), OperationKind.RUN, OperationPhase.RUNNING)
            events.dispatch(RuntimeEvent.OperationChanged(b.id, op))
            val running = writes.last().second
            a.dispose(); assertEquals(b.id, runtime.snapshot().activeProject)
            fail = true
            events.dispatch(
                RuntimeEvent.OperationChanged(
                    b.id,
                    op.copy(phase = OperationPhase.TERMINATED, outcome = OperationOutcome.FAILURE, revision = 2)
                )
            )
            assertTrue(runtime.snapshot().connection is ConnectionState.Backoff)
            fail = false; devices = listOf(testDevice(7))
            scheduler.advanceBy(1000); drain()
            assertTrue(runtime.snapshot().connection is ConnectionState.Ready)
            assertEquals(7, writes.last().second.size)
            assertNotEquals(running, writes.last().second)
            val preview = SettingsPreviewSession(events)
            preview.previewColor(runtime.snapshot(), 0, Color.BLUE, listOf(0))
            assertEquals(Color.BLUE, writes.last().second[0])
            events.dispatch(RuntimeEvent.DisconnectRequested)
            assertNotEquals(Color.BLUE, writes.last().second[0])
            preview.close()
            assertFalse(runtime.snapshot().connectionWanted)
        } finally {
            runtime.close(); drain()
        }
    }
}
