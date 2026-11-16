package com.kgboard.rgb.client

import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenRgbReconnectTest {
    @Test fun `reset invalidates socket and reconnect accepts a fresh peer`() {
        val server = ServerSocket(0)
        val reset = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val peer = worker.submit {
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val header = OpenRgbProtocol.readHeader(input)
                    OpenRgbProtocol.readPayload(input, header.dataSize)
                    OpenRgbProtocol.writeHeader(socket.getOutputStream(), 0, header.packetId, 4)
                    socket.getOutputStream().write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(5).array())
                    socket.getOutputStream().flush()
                    val name = OpenRgbProtocol.readHeader(input)
                    OpenRgbProtocol.readPayload(input, name.dataSize)
                    if (attempt == 0) { socket.setSoLinger(true, 0) } else { assertTrue(release.await(5, TimeUnit.SECONDS)) }
                }
                if (attempt == 0) reset.countDown()
            }
        }
        val client = OpenRgbClient("127.0.0.1", server.localPort)
        try {
            client.connect(); assertTrue(reset.await(5, TimeUnit.SECONDS))
            assertThrows(Exception::class.java) { client.getControllerCount() }
            assertFalse(client.isConnected, "failed requests must invalidate the session")
            client.connect(); assertTrue(client.isConnected)
            release.countDown(); peer.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown(); client.close(); server.close()
            peer.cancel(true); worker.shutdownNow()
        }
    }
}
