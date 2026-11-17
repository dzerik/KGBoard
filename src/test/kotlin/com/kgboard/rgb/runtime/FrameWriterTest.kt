package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbTransport
import com.kgboard.rgb.diagnostics.*
import com.kgboard.rgb.device.InventoryToken
import java.awt.Color
import java.io.IOException
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameWriterTest {
    private fun frame(revision: Long, color: Color, token: InventoryToken = InventoryToken(SessionId(1), 1, "epoch")) =
        FrameEnvelope(token, "binding", 0, RenderRevision(revision), listOf(color))

    @Test
    fun `coalesced frames have no invented sent receipt and cache names successful write`() {
        val executor = ManualExecutor();
        val transport = Transport();
        val events = mutableListOf<RuntimeEvent>()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents { events.add(it) }, RuntimeClock { 17 })
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        val dropped = frame(1, Color.RED);
        val kept = frame(2, Color.BLUE)
        assertTrue(writer.offer(dropped)); assertTrue(writer.offer(kept)); executor.drain()
        assertEquals(listOf(0 to listOf(Color.BLUE)), transport.writes)
        assertEquals(
            listOf(DeliveryReceipt.Sent(kept, 17)),
            events.filterIsInstance<RuntimeEvent.DeliveryReported>().map { it.receipt })
        val cached = frame(3, Color.BLUE)
        writer.offer(cached); executor.drain()
        assertEquals(
            DeliveryReceipt.Cached(cached, RenderRevision(2), 17),
            (events.last() as RuntimeEvent.DeliveryReported).receipt
        )
        assertEquals(1, transport.writes.size)
        writer.close(); executor.drain()
    }

    @Test
    fun `new inventory token invalidates cache and rejects stale epoch revision and session`() {
        val executor = ManualExecutor();
        val transport = Transport();
        val events = mutableListOf<RuntimeEvent>()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents { events.add(it) })
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        val first = frame(1, Color.RED)
        writer.offer(first); executor.drain()
        val current = first.copy(token = first.token.copy(revision = 2), revision = RenderRevision(2))
        assertTrue(writer.offer(current)); executor.drain()
        assertFalse(writer.offer(first))
        assertFalse(writer.offer(current.copy(token = current.token.copy(epoch = "foreign"))))
        assertFalse(writer.offer(current.copy(token = current.token.copy(session = SessionId(2)))))
        executor.drain()
        assertEquals(2, transport.writes.size)
        writer.close(); executor.drain()
    }

    @Test
    fun `failed write reports failure without sent and still emits lifecycle event`() {
        val executor = ManualExecutor();
        val transport = Transport();
        val events = mutableListOf<RuntimeEvent>()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents { events.add(it) })
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        transport.fail = true
        val failed = frame(1, Color.RED)
        writer.offer(failed); executor.drain()
        assertEquals(
            listOf(DeliveryReceipt.Failed(failed, "transport.failed")),
            events.filterIsInstance<RuntimeEvent.DeliveryReported>().map { it.receipt })
        assertTrue(events.last() is RuntimeEvent.TransportFailed)
        assertFalse(writer.offer(frame(2, Color.BLUE)))
        writer.close(); executor.drain()
    }

    @Test
    fun `retirement retains original token and cannot target a different inventory`() {
        val executor = ManualExecutor();
        val transport = Transport();
        val events = mutableListOf<RuntimeEvent>()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents { events.add(it) })
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        val old = frame(1, Color.RED)
        writer.offer(old); executor.drain()
        writer.offer(old.copy(token = old.token.copy(revision = 2), revision = RenderRevision(2))); executor.drain()
        writer.disconnect(SessionId(2), mapOf(0 to old.copy(colors = listOf(Color.BLUE))))
        executor.drain()
        assertEquals(2, transport.writes.size)
        assertTrue(
            events.filterIsInstance<RuntimeEvent.DeliveryReported>()
                .none { it.receipt.frame.colors == listOf(Color.BLUE) })
        writer.close(); executor.drain()
    }

    private class Transport : RgbTransport {
        val writes = mutableListOf<Pair<Int, List<Color>>>()
        var fail = false
        override fun connect(endpoint: Endpoint) = listOf(testDevice(), testDevice())
        override fun write(device: Int, colors: List<Color>) {
            if (fail) throw IOException("reset")
            writes.add(device to colors.toList())
        }

        override fun close() {}
    }

    @Test
    fun `successful cache is per device and new session invalidates it`() {
        val executor = ManualExecutor()
        val transport = Transport()
        val events = mutableListOf<RuntimeEvent>()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents { events.add(it) })
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        repeat(2) {
            writer.offer(SessionId(1), 0, listOf(Color.RED))
            writer.offer(SessionId(1), 1, listOf(Color.BLUE)); executor.drain()
        }
        assertEquals(2, transport.writes.size)
        writer.connect(Endpoint("localhost", 1), SessionId(2)); executor.drain()
        writer.offer(SessionId(1), 0, listOf(Color.GREEN))
        writer.offer(SessionId(2), 0, listOf(Color.RED)); executor.drain()
        assertEquals(3, transport.writes.size)
        transport.fail = true
        writer.offer(SessionId(2), 0, listOf(Color.BLACK)); executor.drain()
        assertTrue(events.last() is RuntimeEvent.TransportFailed)
        writer.close(); executor.drain()
    }

    @Test
    fun `unavailable device indices cannot grow pending work`() {
        val executor = ManualExecutor();
        val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        repeat(1000) { writer.offer(SessionId(1), it + 2, listOf(Color.RED)) }
        executor.drain()
        assertTrue(transport.writes.isEmpty())
        writer.close(); executor.drain()
    }

    @Test
    fun `superseding connection command retains retirement frame`() {
        val executor = ManualExecutor();
        val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        writer.offer(SessionId(1), 0, listOf(Color.RED)); executor.drain()
        writer.disconnect(SessionId(2), mapOf(0 to frame(10000, Color.BLUE, InventoryToken(SessionId(1), 1, "test"))))
        writer.connect(Endpoint("different", 2), SessionId(3)); executor.drain()
        assertEquals(Color.BLUE, transport.writes.last().second.first())
        writer.close(); executor.drain()
    }

    @Test
    fun `close preserves retirement frame after explicit disconnect`() {
        val executor = ManualExecutor();
        val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        writer.offer(SessionId(1), 0, listOf(Color.RED)); executor.drain()
        writer.disconnect(
            SessionId(2),
            mapOf(0 to frame(10000, Color.BLUE, InventoryToken(SessionId(1), 1, "test")))
        ); writer.close(); executor.drain()
        assertEquals(Color.BLUE, transport.writes.last().second.first())
    }

    @Test
    fun `closing before queued connect never starts network work`() {
        val executor = ManualExecutor()
        var attempts = 0
        val transport = object : RgbTransport {
            override fun connect(endpoint: Endpoint) =
                emptyList<com.kgboard.rgb.client.RgbDeviceInfo>().also { attempts++ }

            override fun write(device: Int, colors: List<Color>) {}
            override fun close() {}
        }
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); writer.close(); executor.drain()
        assertEquals(0, attempts)
    }

    @Test
    fun `in flight frame cannot suppress return to last successful color`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val colors = CopyOnWriteArrayList<Color>()
        val receipts = CopyOnWriteArrayList<DeliveryReceipt>()
        val transport = object : RgbTransport {
            override fun connect(endpoint: Endpoint) = listOf(testDevice())
            override fun write(device: Int, frame: List<Color>) {
                if (frame.first() == Color.RED) {
                    started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                colors.add(frame.first())
                if (colors.size == 3) finished.countDown()
            }

            override fun close() {}
        }
        val worker = Executors.newSingleThreadExecutor()
        val writer = CoalescingFrameWriter(transport, worker, RuntimeEvents {
            if (it is RuntimeEvent.Connected) connected.countDown()
            if (it is RuntimeEvent.DeliveryReported) receipts.add(it.receipt)
        })
        try {
            writer.connect(Endpoint("localhost", 1), SessionId(1)); assertTrue(connected.await(5, TimeUnit.SECONDS))
            writer.offer(SessionId(1), 0, listOf(Color.BLUE))
            worker.submit {}.get(5, TimeUnit.SECONDS)
            writer.offer(SessionId(1), 0, listOf(Color.RED)); assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(1000) { writer.offer(SessionId(1), 0, listOf(Color.BLUE)) }
            assertEquals(1, receipts.size)
            assertEquals(listOf(Color.BLUE), receipts.single().frame.colors)
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(Color.BLUE, Color.RED, Color.BLUE), colors)
            assertEquals(
                listOf(Color.BLUE, Color.RED, Color.BLUE),
                receipts.filterIsInstance<DeliveryReceipt.Sent>().map { it.frame.colors.single() })
            assertEquals(3, receipts.size)
        } finally {
            release.countDown(); writer.close(); worker.shutdown(); assertTrue(
                worker.awaitTermination(
                    5,
                    TimeUnit.SECONDS
                )
            )
        }
    }
}

private val writerTestRevision = java.util.concurrent.atomic.AtomicLong()
internal fun FrameWriter.offer(session: SessionId, device: Int, colors: List<Color>): Boolean = offer(
    FrameEnvelope(
        InventoryToken(session, 1, "test"), "test-$device", device,
        RenderRevision(writerTestRevision.incrementAndGet()), colors
    )
)
