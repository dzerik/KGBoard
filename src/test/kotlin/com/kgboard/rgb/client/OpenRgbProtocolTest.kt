package com.kgboard.rgb.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenRgbProtocolTest {

    // ── Constants ──

    @Test
    fun `magic is ORGB`() {
        assertEquals("ORGB", OpenRgbProtocol.MAGIC)
    }

    @Test
    fun `default port is 6742`() {
        assertEquals(6742, OpenRgbProtocol.DEFAULT_PORT)
    }

    @Test
    fun `header size is 16 bytes`() {
        assertEquals(16, OpenRgbProtocol.HEADER_SIZE)
    }

    @Test
    fun `client protocol version is 4`() {
        assertEquals(4, OpenRgbProtocol.CLIENT_PROTOCOL_VERSION)
    }

    // ── writeHeader / readHeader ──

    @Test
    fun `writeHeader produces 16 bytes with correct magic`() {
        val out = ByteArrayOutputStream()
        OpenRgbProtocol.writeHeader(out, 0, 1, 100)
        val bytes = out.toByteArray()
        assertEquals(16, bytes.size)
        assertEquals("ORGB", String(bytes, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `writeHeader encodes deviceIndex, packetId, dataSize in little-endian`() {
        val out = ByteArrayOutputStream()
        OpenRgbProtocol.writeHeader(out, 2, 1050, 256)
        val buf = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4) // skip magic
        assertEquals(2, buf.getInt())
        assertEquals(1050, buf.getInt())
        assertEquals(256, buf.getInt())
    }

    @Test
    fun `readHeader parses valid header`() {
        val out = ByteArrayOutputStream()
        OpenRgbProtocol.writeHeader(out, 3, 40, 0)
        val input = ByteArrayInputStream(out.toByteArray())
        val header = OpenRgbProtocol.readHeader(input)
        assertEquals(3, header.deviceIndex)
        assertEquals(40, header.packetId)
        assertEquals(0, header.dataSize)
    }

    @Test
    fun `readHeader throws on incomplete header`() {
        val input = ByteArrayInputStream(ByteArray(10))
        assertThrows<OpenRgbException> {
            OpenRgbProtocol.readHeader(input)
        }
    }

    @Test
    fun `readHeader throws on invalid magic`() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("XXXX".toByteArray(Charsets.US_ASCII))
        buf.putInt(0); buf.putInt(0); buf.putInt(0)
        val input = ByteArrayInputStream(buf.array())
        assertThrows<OpenRgbException> {
            OpenRgbProtocol.readHeader(input)
        }
    }

    @Test
    fun `roundtrip writeHeader then readHeader`() {
        val out = ByteArrayOutputStream()
        OpenRgbProtocol.writeHeader(out, 5, 100, 42)
        val header = OpenRgbProtocol.readHeader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(5, header.deviceIndex)
        assertEquals(100, header.packetId)
        assertEquals(42, header.dataSize)
    }

    // ── readPayload ──

    @Test
    fun `readPayload with size 0 returns empty buffer`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val payload = OpenRgbProtocol.readPayload(input, 0)
        assertEquals(0, payload.remaining())
    }

    @Test
    fun `readPayload reads exact bytes`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val input = ByteArrayInputStream(data)
        val payload = OpenRgbProtocol.readPayload(input, 4)
        assertEquals(4, payload.remaining())
        assertEquals(ByteOrder.LITTLE_ENDIAN, payload.order())
    }

    @Test
    fun `readPayload throws on incomplete data`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2))
        assertThrows<OpenRgbException> {
            OpenRgbProtocol.readPayload(input, 10)
        }
    }

    // ── encodeSetClientName ──

    @Test
    fun `encodeSetClientName produces null-terminated ASCII`() {
        val result = OpenRgbProtocol.encodeSetClientName("KGBoard")
        assertEquals('K'.code.toByte(), result[0])
        assertEquals(0.toByte(), result.last())
        assertEquals(8, result.size) // "KGBoard" + null
    }

    // ── encodeUpdateLeds ──

    @Test
    fun `encodeUpdateLeds encodes colors as RGBX`() {
        val colors = listOf(Color(255, 0, 0), Color(0, 255, 0))
        val data = OpenRgbProtocol.encodeUpdateLeds(colors)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = buf.getInt()
        assertEquals(data.size, dataSize)
        val numColors = buf.getShort().toInt() and 0xFFFF
        assertEquals(2, numColors)
        // First color: R=255, G=0, B=0, pad=0
        assertEquals(255.toByte(), buf.get())
        assertEquals(0.toByte(), buf.get())
        assertEquals(0.toByte(), buf.get())
        assertEquals(0.toByte(), buf.get()) // padding
        // Second color
        assertEquals(0.toByte(), buf.get())
        assertEquals(255.toByte(), buf.get())
        assertEquals(0.toByte(), buf.get())
    }

    @Test
    fun `encodeUpdateLeds with empty list`() {
        val data = OpenRgbProtocol.encodeUpdateLeds(emptyList())
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.getInt() // dataSize
        assertEquals(0, buf.getShort().toInt() and 0xFFFF)
    }

    // ── encodeUpdateZoneLeds ──

    @Test
    fun `encodeUpdateZoneLeds includes zone index`() {
        val colors = listOf(Color.BLUE)
        val data = OpenRgbProtocol.encodeUpdateZoneLeds(3, colors)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.getInt() // dataSize
        assertEquals(3, buf.getInt()) // zoneIndex
        assertEquals(1, buf.getShort().toInt() and 0xFFFF)
    }

    // ── encodeUpdateSingleLed ──

    @Test
    fun `encodeUpdateSingleLed encodes ledIndex and color`() {
        val data = OpenRgbProtocol.encodeUpdateSingleLed(42, Color(10, 20, 30))
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42, buf.getInt())
        assertEquals(10.toByte(), buf.get())
        assertEquals(20.toByte(), buf.get())
        assertEquals(30.toByte(), buf.get())
        assertEquals(0.toByte(), buf.get()) // padding
    }

    @Test
    fun `encodeUpdateSingleLed is 8 bytes`() {
        val data = OpenRgbProtocol.encodeUpdateSingleLed(0, Color.WHITE)
        assertEquals(8, data.size)
    }

    // ── parseControllerCount ──

    @Test
    fun `parseControllerCount reads int`() {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(5)
        buf.flip()
        assertEquals(5, OpenRgbProtocol.parseControllerCount(buf))
    }

    // ── parseControllerData ──

    @Test
    fun `parseControllerData parses minimal device`() {
        val buf = buildMinimalDevicePayload(
            name = "Keyboard",
            vendor = "TestVendor",
            description = "Test",
            numZones = 1,
            zoneName = "MainZone",
            zoneLeds = 3,
            ledNames = listOf("Key: A", "Key: B", "Key: C"),
            colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        )
        val device = OpenRgbProtocol.parseControllerData(buf)
        assertEquals("Keyboard", device.name)
        assertEquals("TestVendor", device.vendor)
        assertEquals("Test", device.description)
        assertEquals(3, device.numLeds)
        assertEquals(listOf("Key: A", "Key: B", "Key: C"), device.ledNames)
        assertEquals(3, device.colors.size)
        assertEquals(1, device.zones.size)
        assertEquals("MainZone", device.zones[0].name)
        assertEquals(3, device.zones[0].ledsCount)
    }

    // ── PacketHeader data class ──

    @Test
    fun `PacketHeader equals and copy`() {
        val h1 = PacketHeader(0, 1, 100)
        val h2 = PacketHeader(0, 1, 100)
        assertEquals(h1, h2)
        val h3 = h1.copy(dataSize = 200)
        assertEquals(200, h3.dataSize)
    }

    // ── RgbDeviceInfo ──

    @Test
    fun `RgbDeviceInfo default ledNames is empty`() {
        val info = RgbDeviceInfo(0, "Test", "V", "D", 0, zones = emptyList(), colors = emptyList())
        assertEquals(emptyList<String>(), info.ledNames)
    }

    // ── Helper ──

    private fun buildMinimalDevicePayload(
        name: String, vendor: String, description: String,
        numZones: Int, zoneName: String, zoneLeds: Int,
        ledNames: List<String>, colors: List<Color>
    ): ByteBuffer {
        val out = ByteArrayOutputStream()

        fun writeString(s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII) + 0.toByte()
            val len = bytes.size
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(len.toShort()).array())
            out.write(bytes)
        }

        fun writeInt(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }

        fun writeShort(v: Int) {
            out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        }

        writeInt(0) // dataSize placeholder
        writeInt(0) // deviceType

        writeString(name)
        writeString(vendor)
        writeString(description)
        writeString("1.0") // version
        writeString("SN123") // serial
        writeString("USB") // location

        writeShort(0) // numModes
        writeInt(0) // activeModeIndex

        // Zones
        writeShort(numZones)
        for (i in 0 until numZones) {
            writeString(zoneName)
            writeInt(0) // type
            writeInt(0) // ledsMin
            writeInt(zoneLeds) // ledsMax
            writeInt(zoneLeds) // ledsCount
            writeShort(0) // matrixSize
        }

        // LEDs
        writeShort(ledNames.size)
        for (led in ledNames) {
            writeString(led)
            writeInt(0) // LED value
        }

        // Colors
        writeShort(colors.size)
        for (c in colors) {
            out.write(byteArrayOf(c.red.toByte(), c.green.toByte(), c.blue.toByte(), 0))
        }

        val data = out.toByteArray()
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    }
}
