package com.kgboard.rgb.client

import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import java.io.IOException
import java.net.Socket

/**
 * Low-level OpenRGB SDK TCP client.
 * Manages socket connection and protocol communication with OpenRGB server.
 */
class OpenRgbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = OpenRgbProtocol.DEFAULT_PORT,
    private val clientName: String = "KGBoard"
) : AutoCloseable {

    private val log = Logger.getInstance(OpenRgbClient::class.java)

    private var socket: Socket? = null
    private val lock = Any()

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect() {
        synchronized(lock) {
            if (isConnected) return
            try {
                val s = Socket(host, port)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                socket = s
                negotiateProtocolVersion()
                setClientName(clientName)
                log.info("Connected to OpenRGB at $host:$port")
            } catch (e: IOException) {
                socket = null
                throw OpenRgbException("Failed to connect to OpenRGB at $host:$port", e)
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            socket = null
            log.info("Disconnected from OpenRGB")
        }
    }

    override fun close() = disconnect()

    fun getControllerCount(): Int {
        synchronized(lock) {
            val s = requireConnection()
            OpenRgbProtocol.writeHeader(s.getOutputStream(), 0, OpenRgbProtocol.NET_PACKET_ID_REQUEST_CONTROLLER_COUNT, 0)
            val header = OpenRgbProtocol.readHeader(s.getInputStream())
            val payload = OpenRgbProtocol.readPayload(s.getInputStream(), header.dataSize)
            return OpenRgbProtocol.parseControllerCount(payload)
        }
    }

    fun getControllerData(deviceIndex: Int): RgbDeviceInfo {
        synchronized(lock) {
            val s = requireConnection()
            // Send protocol version as data
            val versionData = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(OpenRgbProtocol.CLIENT_PROTOCOL_VERSION)
                .array()
            OpenRgbProtocol.writeHeader(s.getOutputStream(), deviceIndex, OpenRgbProtocol.NET_PACKET_ID_REQUEST_CONTROLLER_DATA, versionData.size)
            s.getOutputStream().write(versionData)
            s.getOutputStream().flush()
            val header = OpenRgbProtocol.readHeader(s.getInputStream())
            val payload = OpenRgbProtocol.readPayload(s.getInputStream(), header.dataSize)
            return OpenRgbProtocol.parseControllerData(payload)
        }
    }

    fun getAllDevices(): List<RgbDeviceInfo> {
        val count = getControllerCount()
        return (0 until count).map { getControllerData(it) }
    }

    fun setCustomMode(deviceIndex: Int) {
        synchronized(lock) {
            val s = requireConnection()
            OpenRgbProtocol.writeHeader(s.getOutputStream(), deviceIndex, OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_SETCUSTOMMODE, 0)
        }
    }

    fun updateLeds(deviceIndex: Int, colors: List<Color>) {
        synchronized(lock) {
            val s = requireConnection()
            val data = OpenRgbProtocol.encodeUpdateLeds(colors)
            OpenRgbProtocol.writeHeader(s.getOutputStream(), deviceIndex, OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_UPDATELEDS, data.size)
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
        }
    }

    fun updateZoneLeds(deviceIndex: Int, zoneIndex: Int, colors: List<Color>) {
        synchronized(lock) {
            val s = requireConnection()
            val data = OpenRgbProtocol.encodeUpdateZoneLeds(zoneIndex, colors)
            OpenRgbProtocol.writeHeader(s.getOutputStream(), deviceIndex, OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_UPDATEZONELEDS, data.size)
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
        }
    }

    fun updateSingleLed(deviceIndex: Int, ledIndex: Int, color: Color) {
        synchronized(lock) {
            val s = requireConnection()
            val data = OpenRgbProtocol.encodeUpdateSingleLed(ledIndex, color)
            OpenRgbProtocol.writeHeader(s.getOutputStream(), deviceIndex, OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_UPDATESINGLELED, data.size)
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
        }
    }

    fun setAllLeds(deviceIndex: Int, color: Color) {
        val device = getControllerData(deviceIndex)
        val colors = List(device.numLeds) { color }
        setCustomMode(deviceIndex)
        updateLeds(deviceIndex, colors)
    }

    private fun negotiateProtocolVersion() {
        val s = requireConnection()
        val versionData = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(OpenRgbProtocol.CLIENT_PROTOCOL_VERSION)
            .array()
        OpenRgbProtocol.writeHeader(s.getOutputStream(), 0, OpenRgbProtocol.NET_PACKET_ID_REQUEST_PROTOCOL_VERSION, versionData.size)
        s.getOutputStream().write(versionData)
        s.getOutputStream().flush()
        val header = OpenRgbProtocol.readHeader(s.getInputStream())
        val payload = OpenRgbProtocol.readPayload(s.getInputStream(), header.dataSize)
        val serverVersion = if (header.dataSize >= 4) payload.getInt() else 0
        log.info("OpenRGB server protocol version: $serverVersion")
    }

    private fun setClientName(name: String) {
        val s = requireConnection()
        val data = OpenRgbProtocol.encodeSetClientName(name)
        OpenRgbProtocol.writeHeader(s.getOutputStream(), 0, OpenRgbProtocol.NET_PACKET_ID_SET_CLIENT_NAME, data.size)
        s.getOutputStream().write(data)
        s.getOutputStream().flush()
    }

    private fun requireConnection(): Socket {
        return socket ?: throw OpenRgbException("Not connected to OpenRGB server")
    }
}
