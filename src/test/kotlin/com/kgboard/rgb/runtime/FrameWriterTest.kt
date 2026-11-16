package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbTransport
import java.awt.Color
import java.io.IOException
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameWriterTest {
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

    @Test fun `successful cache is per device and new session invalidates it`() {
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

    @Test fun `unavailable device indices cannot grow pending work`() {
        val executor = ManualExecutor(); val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        repeat(1000) { writer.offer(SessionId(1), it + 2, listOf(Color.RED)) }
        executor.drain()
        assertTrue(transport.writes.isEmpty())
        writer.close(); executor.drain()
    }
    @Test fun `superseding connection command retains retirement frame`() {
        val executor = ManualExecutor(); val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        writer.offer(SessionId(1), 0, listOf(Color.RED)); executor.drain()
        writer.disconnect(SessionId(2), mapOf(0 to listOf(Color.BLUE)))
        writer.connect(Endpoint("different", 2), SessionId(3)); executor.drain()
        assertEquals(Color.BLUE, transport.writes.last().second.first())
        writer.close(); executor.drain()
    }
    @Test fun `close preserves retirement frame after explicit disconnect`() {
        val executor = ManualExecutor(); val transport = Transport()
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); executor.drain()
        writer.offer(SessionId(1), 0, listOf(Color.RED)); executor.drain()
        writer.disconnect(SessionId(2), mapOf(0 to listOf(Color.BLUE))); writer.close(); executor.drain()
        assertEquals(Color.BLUE, transport.writes.last().second.first())
    }
    @Test fun `closing before queued connect never starts network work`() {
        val executor = ManualExecutor()
        var attempts = 0
        val transport = object : RgbTransport {
            override fun connect(endpoint: Endpoint) = emptyList<com.kgboard.rgb.client.RgbDeviceInfo>().also { attempts++ }
            override fun write(device: Int, colors: List<Color>) {}
            override fun close() {}
        }
        val writer = CoalescingFrameWriter(transport, executor, RuntimeEvents {})
        writer.connect(Endpoint("localhost", 1), SessionId(1)); writer.close(); executor.drain()
        assertEquals(0, attempts)
    }

    @Test fun `in flight frame cannot suppress return to last successful color`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val colors = CopyOnWriteArrayList<Color>()
        val transport = object : RgbTransport {
            override fun connect(endpoint: Endpoint) = listOf(testDevice())
            override fun write(device: Int, frame: List<Color>) {
                if (frame.first() == Color.RED) { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                colors.add(frame.first())
                if (colors.size == 3) finished.countDown()
            }
            override fun close() {}
        }
        val worker = Executors.newSingleThreadExecutor()
        val writer = CoalescingFrameWriter(transport, worker, RuntimeEvents { if (it is RuntimeEvent.Connected) connected.countDown() })
        try {
            writer.connect(Endpoint("localhost", 1), SessionId(1)); assertTrue(connected.await(5, TimeUnit.SECONDS))
            writer.offer(SessionId(1), 0, listOf(Color.BLUE))
            worker.submit {}.get(5, TimeUnit.SECONDS)
            writer.offer(SessionId(1), 0, listOf(Color.RED)); assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(1000) { writer.offer(SessionId(1), 0, listOf(Color.BLUE)) }
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Color.BLUE, Color.RED, Color.BLUE), colors)
        } finally { release.countDown(); writer.close(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
