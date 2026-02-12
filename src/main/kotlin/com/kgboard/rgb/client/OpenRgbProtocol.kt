package com.kgboard.rgb.client

import java.awt.Color
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenRGB SDK binary protocol implementation.
 * Protocol spec: https://gitlab.com/OpenRGBDevelopers/OpenRGB-Wiki/-/blob/stable/Developer-Documentation/OpenRGB-SDK-Documentation.md
 */
object OpenRgbProtocol {
    const val MAGIC = "ORGB"
    const val DEFAULT_PORT = 6742
    const val HEADER_SIZE = 16

    // Command IDs
    const val NET_PACKET_ID_REQUEST_CONTROLLER_COUNT = 0
    const val NET_PACKET_ID_REQUEST_CONTROLLER_DATA = 1
    const val NET_PACKET_ID_REQUEST_PROTOCOL_VERSION = 40
    const val NET_PACKET_ID_SET_CLIENT_NAME = 50
    const val NET_PACKET_ID_DEVICE_LIST_UPDATED = 100
    const val NET_PACKET_ID_RGBCONTROLLER_UPDATELEDS = 1050
    const val NET_PACKET_ID_RGBCONTROLLER_UPDATEZONELEDS = 1051
    const val NET_PACKET_ID_RGBCONTROLLER_UPDATESINGLELED = 1052
    const val NET_PACKET_ID_RGBCONTROLLER_SETCUSTOMMODE = 1100

    const val CLIENT_PROTOCOL_VERSION = 4

    fun writeHeader(out: OutputStream, deviceIndex: Int, packetId: Int, dataSize: Int) {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.putInt(deviceIndex)
        buf.putInt(packetId)
        buf.putInt(dataSize)
        out.write(buf.array())
        out.flush()
    }

    fun readHeader(input: InputStream): PacketHeader {
        val headerBytes = input.readNBytes(HEADER_SIZE)
        if (headerBytes.size < HEADER_SIZE) {
            throw OpenRgbException("Connection closed: incomplete header (got ${headerBytes.size} bytes)")
        }
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        val magicStr = String(magic, Charsets.US_ASCII)
        if (magicStr != MAGIC) {
            throw OpenRgbException("Invalid magic: $magicStr")
        }
        return PacketHeader(
            deviceIndex = buf.getInt(),
            packetId = buf.getInt(),
            dataSize = buf.getInt()
        )
    }

    fun readPayload(input: InputStream, size: Int): ByteBuffer {
        if (size == 0) return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
        val data = input.readNBytes(size)
        if (data.size < size) {
            throw OpenRgbException("Incomplete payload: expected $size, got ${data.size}")
        }
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun encodeSetClientName(name: String): ByteArray {
        return name.toByteArray(Charsets.US_ASCII) + 0.toByte()
    }

    fun encodeUpdateLeds(colors: List<Color>): ByteArray {
        val numColors = colors.size
        val dataSize = 4 + 2 + (numColors * 4)
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dataSize)
        buf.putShort(numColors.toShort())
        for (color in colors) {
            buf.put(color.red.toByte())
            buf.put(color.green.toByte())
            buf.put(color.blue.toByte())
            buf.put(0) // padding
        }
        return buf.array()
    }

    fun encodeUpdateZoneLeds(zoneIndex: Int, colors: List<Color>): ByteArray {
        val numColors = colors.size
        val dataSize = 4 + 4 + 2 + (numColors * 4)
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dataSize)
        buf.putInt(zoneIndex)
        buf.putShort(numColors.toShort())
        for (color in colors) {
            buf.put(color.red.toByte())
            buf.put(color.green.toByte())
            buf.put(color.blue.toByte())
            buf.put(0)
        }
        return buf.array()
    }

    fun encodeUpdateSingleLed(ledIndex: Int, color: Color): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ledIndex)
        buf.put(color.red.toByte())
        buf.put(color.green.toByte())
        buf.put(color.blue.toByte())
        buf.put(0)
        return buf.array()
    }

    fun parseControllerCount(payload: ByteBuffer): Int {
        return payload.getInt()
    }

    fun parseControllerData(payload: ByteBuffer): RgbDeviceInfo {
        @Suppress("UNUSED_VARIABLE") val dataSize = payload.getInt()
        val deviceType = payload.getInt()

        val name = readString(payload)
        val vendor = readString(payload)
        val description = readString(payload)
        readString(payload) // version
        readString(payload) // serial
        readString(payload) // location

        val numModes = payload.getShort().toInt() and 0xFFFF
        payload.getInt() // activeModeIndex

        // Skip modes
        for (i in 0 until numModes) {
            skipMode(payload)
        }

        val numZones = payload.getShort().toInt() and 0xFFFF
        val zones = mutableListOf<RgbZoneInfo>()
        for (i in 0 until numZones) {
            zones.add(parseZone(payload))
        }

        val numLeds = payload.getShort().toInt() and 0xFFFF
        // Skip LED names
        for (i in 0 until numLeds) {
            readString(payload) // LED name
            payload.getInt()    // LED value
        }

        val numColors = payload.getShort().toInt() and 0xFFFF
        val colors = mutableListOf<Color>()
        for (i in 0 until numColors) {
            val r = payload.get().toInt() and 0xFF
            val g = payload.get().toInt() and 0xFF
            val b = payload.get().toInt() and 0xFF
            payload.get() // padding
            colors.add(Color(r, g, b))
        }

        return RgbDeviceInfo(
            type = deviceType,
            name = name,
            vendor = vendor,
            description = description,
            numLeds = numLeds,
            zones = zones,
            colors = colors
        )
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.getShort().toInt() and 0xFFFF
        if (len == 0) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        // Strip null terminator if present
        val end = bytes.indexOf(0)
        return if (end >= 0) String(bytes, 0, end, Charsets.US_ASCII) else String(bytes, Charsets.US_ASCII)
    }

    private fun skipMode(buf: ByteBuffer) {
        readString(buf) // name
        buf.getInt() // value
        buf.getInt() // flags
        buf.getInt() // speed_min
        buf.getInt() // speed_max
        buf.getInt() // brightness_min
        buf.getInt() // brightness_max
        buf.getInt() // colors_min
        buf.getInt() // colors_max
        buf.getInt() // speed
        buf.getInt() // brightness
        buf.getInt() // direction
        buf.getInt() // color_mode
        val numColors = buf.getShort().toInt() and 0xFFFF
        for (i in 0 until numColors) {
            buf.getInt() // color (RGBA)
        }
    }

    private fun parseZone(buf: ByteBuffer): RgbZoneInfo {
        val name = readString(buf)
        val type = buf.getInt()
        buf.getInt() // ledsMin
        buf.getInt() // ledsMax
        val ledsCount = buf.getInt()
        val matrixSize = buf.getShort().toInt() and 0xFFFF
        if (matrixSize > 0) {
            val matrixHeight = buf.getInt()
            val matrixWidth = buf.getInt()
            // Skip matrix values
            for (i in 0 until matrixHeight * matrixWidth) {
                buf.getInt()
            }
        }
        return RgbZoneInfo(name = name, type = type, ledsCount = ledsCount)
    }
}

data class PacketHeader(
    val deviceIndex: Int,
    val packetId: Int,
    val dataSize: Int
)

data class RgbDeviceInfo(
    val type: Int,
    val name: String,
    val vendor: String,
    val description: String,
    val numLeds: Int,
    val zones: List<RgbZoneInfo>,
    val colors: List<Color>
)

data class RgbZoneInfo(
    val name: String,
    val type: Int,
    val ledsCount: Int
)

class OpenRgbException(message: String, cause: Throwable? = null) : Exception(message, cause)
