package com.kgboard.rgb.client

import com.kgboard.rgb.testing.ControllerFixture
import com.kgboard.rgb.testing.ControllerFixtureSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ControllerMetadataTest {
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun `protocol zero to five preserve controller identity and display names`() {
        val spec = ControllerFixtureSpec(displayNames = listOf("Escape", "Letter A"))
        for (version in 0..5) {
            val device = OpenRgbProtocol.parseControllerData(buffer(ControllerFixture.encode(version, spec)), version)
            assertEquals(if (version == 0) "" else "Vendor", device.vendor)
            assertEquals("serial-1", device.serial)
            assertEquals("usb1", device.location)
            assertEquals("1.0", device.version)
            assertEquals(if (version >= 5) spec.displayNames else emptyList<String>(), device.displayNames)
        }
    }

    @Test fun `every truncated controller payload is rejected as a bounded protocol error`() {
        for (version in 0..5) {
            val payload = ControllerFixture.encode(version, ControllerFixtureSpec())
            for (size in payload.indices) {
                val truncated = payload.copyOf(size)
                if (size >= 4) buffer(truncated).putInt(size)
                assertThrows(OpenRgbException::class.java,
                    { OpenRgbProtocol.parseControllerData(buffer(truncated), version) }, "v$version size$size")
            }
        }
    }

    @Test fun `declared size and invalid string lengths never accept partial metadata`() {
        val bytes = ControllerFixture.encode(5, ControllerFixtureSpec())
        buffer(bytes).putInt(bytes.size + 1)
        assertThrows(OpenRgbException::class.java) { OpenRgbProtocol.parseControllerData(buffer(bytes), 5) }
        buffer(bytes).putInt(bytes.size).putShort(8, (-1).toShort())
        assertThrows(OpenRgbException::class.java) { OpenRgbProtocol.parseControllerData(buffer(bytes), 5) }
    }

    @Test fun `unversioned server ignoring negotiation still supports protocol zero`() {
        com.kgboard.rgb.testing.OpenRgbSdkSimulator(0, listOf(ControllerFixtureSpec()),
            respondToNegotiation = false).use { server ->
            OpenRgbClient(server.endpoint.host, server.endpoint.port).use { client ->
                client.connect()
                assertEquals(0, client.protocolVersion)
                assertEquals("serial-1", client.getAllDevices().single().serial)
                assertEquals(0, server.requestedProtocol)
            }
        }
    }

    @Test fun `invalid packet lengths are rejected before reading or allocating`() {
        for (size in listOf(-1, Int.MAX_VALUE)) {
            assertThrows(OpenRgbException::class.java) {
                OpenRgbProtocol.readPayload(object : java.io.InputStream() {
                    override fun read(): Int = throw AssertionError("Invalid length must be rejected before I/O")
                }, size)
            }
        }
    }

    @Test fun `controller count is bounded before creating an inventory collection`() {
        for (count in listOf(-1, Int.MAX_VALUE)) {
            assertThrows(OpenRgbException::class.java) {
                OpenRgbProtocol.parseControllerCount(ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN).putInt(count).flip())
            }
        }
        assertThrows(OpenRgbException::class.java) {
            OpenRgbProtocol.parseControllerCount(ByteBuffer.allocate(0))
        }
    }
}
