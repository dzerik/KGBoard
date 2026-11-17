package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.device.InventoryToken
import com.kgboard.rgb.effect.PulseEffect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeliveryStateTest {
    @Test
    fun `delivery reports and history clear never render or schedule another wake`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(
                RuntimeEvent.LayerChanged(
                    EffectLayer(
                        EffectKey(ProjectId("p"), "pulse"),
                        PulseEffect(java.awt.Color.RED),
                        0
                    )
                )
            )
            val before = h.writer.envelopes.size
            val decision = requireNotNull(h.snapshotForTest().lastDecision)
            val frame = requireNotNull(decision.devices.first().frame)
            val scheduled = h.scheduler.scheduledCount
            val cancelled = h.scheduler.cancelledCount
            repeat(1000) { h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(frame, 10))) }
            h.send(RuntimeEvent.ClearHistory)
            assertEquals(before, h.writer.envelopes.size)
            assertEquals(decision.revision, h.snapshotForTest().lastDecision?.revision)
            assertEquals(scheduled, h.scheduler.scheduledCount)
            assertEquals(cancelled, h.scheduler.cancelledCount)
            assertEquals(frame.revision, h.snapshotForTest().diagnostics.delivery[frame.deliveryKey()]?.lastSent)
            assertTrue(h.snapshotForTest().diagnostics.history.isEmpty())
            h.advanceBy(34)
            assertTrue(h.writer.envelopes.size > before)
        }
    }

    @Test
    fun `older success cannot clear newer pending or regress successful revision`() {
        RuntimeHarness().use { h ->
            h.project()
            val old = h.writer.envelopes.last()
            h.send(RuntimeEvent.FocusChanged(false))
            val newer = h.writer.envelopes.last()
            assertEquals(newer.revision, h.snapshotForTest().diagnostics.delivery[newer.deliveryKey()]?.pending)
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(old, 10)))
            assertEquals(newer.revision, h.snapshotForTest().diagnostics.delivery[newer.deliveryKey()]?.pending)
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(newer, 20)))
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(old, 10)))
            val delivery = h.snapshotForTest().diagnostics.delivery.getValue(newer.deliveryKey())
            assertNull(delivery.pending)
            assertEquals(newer.revision, delivery.lastSent)
            assertEquals(20L, delivery.sentAtMs)
        }
    }

    @Test
    fun `cached result references actual successful revision and clears current pending`() {
        RuntimeHarness().use { h ->
            h.project()
            val old = h.writer.envelopes.last()
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(old, 5)))
            h.send(RuntimeEvent.FocusChanged(true))
            val newer = h.writer.envelopes.last()
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Cached(newer, old.revision, 5)))
            val delivery = h.snapshotForTest().diagnostics.delivery.getValue(newer.deliveryKey())
            assertNull(delivery.pending)
            assertEquals(old.revision, delivery.lastSent)
            assertEquals(5L, delivery.sentAtMs)
        }
    }

    @Test
    fun `foreign session epoch inventory and future receipts cannot alter current delivery`() {
        RuntimeHarness().use { h ->
            h.project()
            val frame = h.writer.envelopes.last()
            val before = h.snapshotForTest().diagnostics
            val tokens = listOf(
                frame.token.copy(session = SessionId(99)),
                frame.token.copy(epoch = "old"),
                frame.token.copy(revision = 99)
            )
            for (token in tokens) h.send(
                RuntimeEvent.DeliveryReported(
                    DeliveryReceipt.Failed(
                        frame.copy(token = token),
                        "transport.failed"
                    )
                )
            )
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(frame.copy(revision = RenderRevision(9999)), 2)))
            assertEquals(before, h.snapshotForTest().diagnostics)
        }
    }

    @Test
    fun `reconnected delivery ignores old successful callback`() {
        RuntimeHarness().use { h ->
            h.project()
            val old = h.writer.envelopes.last()
            h.send(RuntimeEvent.DisconnectRequested)
            h.send(RuntimeEvent.ConnectRequested())
            val connecting = h.snapshotForTest().connection as ConnectionState.Connecting
            h.send(RuntimeEvent.Connected(connecting.session, testDevices()))
            val current = h.writer.envelopes.last()
            assertNotEquals(old.token, current.token)
            val before = h.snapshotForTest().diagnostics
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(old, 99)))
            assertEquals(before, h.snapshotForTest().diagnostics)
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(current, 100)))
            assertEquals(
                current.revision,
                h.snapshotForTest().diagnostics.delivery.getValue(current.deliveryKey()).lastSent
            )
        }
    }

    @Test
    fun `transport failure is visible without render and repeated errors coalesce`() {
        RuntimeHarness().use { h ->
            h.project()
            val frame = h.writer.envelopes.last()
            val revision = h.snapshotForTest().lastDecision?.revision
            repeat(3) { n ->
                h.clock.value = n.toLong(); h.send(
                RuntimeEvent.DeliveryReported(
                    DeliveryReceipt.Failed(
                        frame,
                        "transport.failed"
                    )
                )
            )
            }
            val delivery = h.snapshotForTest().diagnostics.delivery.getValue(frame.deliveryKey())
            assertNull(delivery.pending)
            assertEquals("transport.failed", delivery.error)
            val entry = h.snapshotForTest().diagnostics.history.last()
            assertEquals(3, entry.count)
            assertEquals(0L, entry.firstSeenMs)
            assertEquals(2L, entry.lastSeenMs)
            assertEquals(revision, h.snapshotForTest().lastDecision?.revision)
        }
    }

    @Test
    fun `animation produces no significant history entries`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(
                RuntimeEvent.LayerChanged(
                    EffectLayer(
                        EffectKey(ProjectId("p"), "pulse"),
                        PulseEffect(java.awt.Color.RED),
                        0
                    )
                )
            )
            val count = h.snapshotForTest().diagnostics.history.size
            h.advanceBy(1000)
            assertEquals(count, h.snapshotForTest().diagnostics.history.size)
        }
    }

    @Test
    fun `history is bounded and snapshots survive coalescing and clear`() {
        val history = DiagnosticsHistory()
        repeat(1000) { history.record(HistoryEntry("event-$it", "transport.failed", it.toLong(), it.toLong(), 1)) }
        val before = history.entries()
        assertEquals(100, before.size)
        assertEquals("event-900", before.first().key)
        history.record(HistoryEntry("event-999", "transport.failed", 1001, 1001, 1))
        assertEquals(1, before.last().count)
        assertEquals(2, history.entries().last().count)
        assertEquals(999L, history.entries().last().firstSeenMs)
        assertEquals(1001L, history.entries().last().lastSeenMs)
        history.clear()
        assertTrue(history.entries().isEmpty())
        assertEquals(100, before.size)
    }

    @Test
    fun `late receipt after shutdown cannot resurrect state or listeners`() {
        val h = RuntimeHarness()
        h.project()
        val frame = h.writer.envelopes.last()
        var notifications = 0
        h.runtime.subscribe { notifications++ }; h.drain()
        h.close()
        val before = h.snapshotForTest()
        val count = notifications
        h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(frame, 99)))
        assertSame(before, h.snapshotForTest())
        assertEquals(count, notifications)
    }
}
