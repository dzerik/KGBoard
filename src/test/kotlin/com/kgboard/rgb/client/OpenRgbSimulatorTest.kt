package com.kgboard.rgb.client

import com.kgboard.rgb.testing.ControllerFixture
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import java.awt.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenRgbSimulatorTest {
    @Test fun `real transport writes are recorded and reset invalidates connection`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { server ->
            val transport = OpenRgbTransport { false }
            try {
                assertEquals(listOf("Esc", "A"), transport.connect(server.endpoint).single().ledNames)
                transport.write(0, listOf(Color.RED, Color.BLUE))
                assertEquals(listOf(Color.RED, Color.BLUE), server.awaitWrite().colors)
                server.resetConnection()
                assertThrows(Exception::class.java) { transport.probe() }
            } finally { transport.close() }
        }
    }

    @Test fun `inventory reorder and same size layout changes are visible over TCP`() {
        val first = ControllerFixtureSpec(name = "First")
        val second = ControllerFixtureSpec(name = "Second", serial = "serial-2")
        OpenRgbSdkSimulator(5, listOf(first, second)).use { server ->
            val transport = OpenRgbTransport { true }
            try {
                transport.connect(server.endpoint)
                server.inventory(listOf(second, first.copy(ledNames = listOf("A", "Esc"))))
                assertTrue(transport.probe())
                val updated = transport.connect(server.endpoint)
                assertEquals(listOf("Second", "First"), updated.map { it.name })
                assertEquals(listOf("A", "Esc"), updated[1].ledNames)
            } finally { transport.close() }
        }
    }

    @Test fun `server six accepts client five wire layout`() {
        OpenRgbSdkSimulator(6, listOf(ControllerFixtureSpec())).use { server ->
            OpenRgbClient(server.endpoint.host, server.endpoint.port).use { client ->
                client.connect()
                assertEquals(2, client.getAllDevices().single().numLeds)
                assertEquals(5, server.requestedProtocol)
            }
        }
    }

    @Test fun `wire fixture gates vendor independently from production parser`() {
        val spec = ControllerFixtureSpec(name = "N", vendor = "UNIQUE_VENDOR")
        val zero = ControllerFixture.encode(0, spec)
        val one = ControllerFixture.encode(1, spec)
        assertEquals(zero.size, ByteBuffer.wrap(zero).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(one.size, ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN).int)
        assertFalse(String(zero, Charsets.ISO_8859_1).contains("UNIQUE_VENDOR"))
        assertTrue(String(one, Charsets.ISO_8859_1).contains("UNIQUE_VENDOR"))
        assertEquals("N", readName(zero))
        assertEquals("N", readName(one))
    }

    @Test fun `write gate releases and empty queue times out without sleeps`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { server ->
            val transport = OpenRgbTransport { true }
            try {
                transport.connect(server.endpoint)
                val gate = server.blockWrites()
                try {
                    transport.write(0, listOf(Color.GREEN, Color.GREEN))
                    assertThrows(AssertionError::class.java) { server.awaitWrite(30) }
                } finally { gate.close() }
                assertEquals(Color.GREEN, server.awaitWrite().colors.first())
            } finally { transport.close() }
        }
    }

    private fun readName(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8)
        val length = buf.short.toInt() and 65535
        return String(ByteArray(length).also(buf::get), Charsets.UTF_8).trimEnd('\u0000')
    }
}
