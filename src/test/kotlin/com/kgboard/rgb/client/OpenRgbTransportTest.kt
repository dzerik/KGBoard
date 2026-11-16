package com.kgboard.rgb.client

import com.kgboard.rgb.runtime.Endpoint
import java.awt.Color
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenRgbTransportTest {
    @Test fun `skip custom mode is honored and controller request uses negotiated protocol`() {
        for (skip in listOf(true, false)) {
            val server = ServerSocket(0)
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit<Pair<List<Int>, Int>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val ids = mutableListOf<Int>(); var requestedVersion = -1
                    while (true) {
                        val header = OpenRgbProtocol.readHeader(socket.getInputStream())
                        val data = OpenRgbProtocol.readPayload(socket.getInputStream(), header.dataSize)
                        ids.add(header.packetId)
                        fun respond(bytes: ByteArray) {
                            OpenRgbProtocol.writeHeader(socket.getOutputStream(), header.deviceIndex, header.packetId, bytes.size)
                            socket.getOutputStream().write(bytes); socket.getOutputStream().flush()
                        }
                        when(header.packetId) {
                            OpenRgbProtocol.NET_PACKET_ID_REQUEST_PROTOCOL_VERSION -> respond(int(0))
                            OpenRgbProtocol.NET_PACKET_ID_REQUEST_CONTROLLER_COUNT -> respond(int(1))
                            OpenRgbProtocol.NET_PACKET_ID_REQUEST_CONTROLLER_DATA -> {
                                requestedVersion = data.int
                                val payload = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
                                payload.putInt(0).putInt(1)
                                repeat(6) { payload.putShort(0) }
                                payload.putShort(0).putInt(0).putShort(0).putShort(1)
                                val name = "Key: A\u0000".toByteArray()
                                payload.putShort(name.size.toShort()).put(name).putInt(0).putShort(1).putInt(0)
                                respond(payload.array().copyOf(payload.position()))
                            }
                            OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_UPDATELEDS -> return@submit ids to requestedVersion
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") (ids to requestedVersion)
                }
            }
            val transport = OpenRgbTransport { skip }
            try {
                transport.connect(Endpoint("127.0.0.1", server.localPort))
                transport.write(0, listOf(Color.BLUE))
                val (ids, version) = result.get(5, TimeUnit.SECONDS)
                assertEquals(0, version)
                assertEquals(!skip, OpenRgbProtocol.NET_PACKET_ID_RGBCONTROLLER_SETCUSTOMMODE in ids)
            } finally { transport.close(); server.close(); result.cancel(true); worker.shutdownNow() }
        }
    }
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
