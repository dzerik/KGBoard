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

    const val CLIENT_PROTOCOL_VERSION = 5

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
        if (size !in 0..(16 * 1024 * 1024)) throw OpenRgbException("Invalid payload length")
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
        if (payload.remaining() != 4) throw OpenRgbException("Invalid controller count payload")
        val count = payload.getInt()
        if (count !in 0..65535) throw OpenRgbException("Invalid controller count")
        return count
    }

    fun parseControllerData(payload: ByteBuffer, protocolVersion: Int = 0): RgbDeviceInfo =
        ControllerDataReader(payload, protocolVersion).read()

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
    val ledNames: List<String> = emptyList(),
    val zones: List<RgbZoneInfo>,
    val colors: List<Color>,
    val serial: String = "",
    val location: String = "",
    val displayNames: List<String> = emptyList(),
    val version: String = ""
)

data class RgbZoneInfo(
    val name: String,
    val type: Int,
    val ledsCount: Int,
    val matrixHeight: Int = 0,
    val matrixWidth: Int = 0,
    val matrixMap: List<Int>? = null
)

class OpenRgbException(message: String, cause: Throwable? = null) : Exception(message, cause)
