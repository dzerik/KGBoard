package com.kgboard.rgb.testing

import com.kgboard.rgb.runtime.Endpoint
import java.awt.Color
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class RecordedWrite(val connection: Long, val device: Int, val colors: List<Color>)

/** Loopback-only server. All waits and shutdown are bounded; no test classes enter the plugin. */
class OpenRgbSdkSimulator(private val version: Int, controllers: List<ControllerFixtureSpec>) : AutoCloseable {
    init { require(version in 0..6) }

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val endpoint = Endpoint(server.inetAddress.hostAddress, server.localPort)
    private val closed = AtomicBoolean()
    private val inventory = AtomicReference(controllers.toList())
    private val socket = AtomicReference<Socket?>()
    private val gate = AtomicReference<CountDownLatch?>()
    private val writes = ArrayBlockingQueue<RecordedWrite>(1024)
    private val failure = AtomicReference<Throwable?>()
    @Volatile var requestedProtocol: Int = -1
        private set
    private val worker = thread(name = "kgboard-sdk-simulator", isDaemon = true) {
        var connection = 0L
        while (!closed.get()) {
            try {
                server.accept().use { client ->
                    client.soTimeout = 5000
                    socket.set(client)
                    try { serve(client, ++connection) }
                    catch (_: EOFException) { }
                    catch (_: SocketException) { }
                    finally { socket.compareAndSet(client, null) }
                }
            } catch (error: Exception) {
                if (!closed.get()) failure.compareAndSet(null, error)
            }
        }
    }

    fun inventory(controllers: List<ControllerFixtureSpec>) { inventory.set(controllers.toList()) }

    fun blockWrites(): AutoCloseable {
        val latch = CountDownLatch(1)
        check(gate.compareAndSet(null, latch)) { "Write gate already active" }
        return AutoCloseable { gate.compareAndSet(latch, null); latch.countDown() }
    }

    fun awaitWrite(timeoutMs: Long = 2000): RecordedWrite =
        writes.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("No SDK write within ${timeoutMs}ms", failure.get())

    fun resetConnection() {
        socket.getAndSet(null)?.let { it.setSoLinger(true, 0); it.close() }
    }

    private fun serve(client: Socket, connection: Long) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        while (!closed.get()) {
            val first = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (first == -1) return
            val header = byteArrayOf(first.toByte()) + input.readNBytes(15)
            check(header.size == 16) { "Truncated request header" }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            check(String(header, 0, 4, Charsets.US_ASCII) == "ORGB")
            val device = buf.getInt(4); val id = buf.getInt(8); val size = buf.getInt(12)
            check(size in 0..(1024 * 1024))
            val bytes = input.readNBytes(size)
            check(bytes.size == size)
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun respond(payload: ByteArray) {
                output.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .put("ORGB".toByteArray()).putInt(device).putInt(id).putInt(payload.size).array())
                output.write(payload); output.flush()
            }
            fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            when (id) {
                40 -> respond(int(version))
                50, 1100 -> Unit
                0 -> respond(int(inventory.get().size))
                1 -> {
                    requestedProtocol = if (size == 0) 0 else data.int
                    check(requestedProtocol in 0..minOf(version, 5))
                    respond(ControllerFixture.encode(requestedProtocol, inventory.get()[device]))
                }
                1050 -> {
                    check(gate.get()?.await(3, TimeUnit.SECONDS) != false) { "Write gate timed out" }
                    check(data.int == size)
                    val count = data.short.toInt() and 65535
                    check(data.remaining() == count * 4)
                    val colors = List(count) {
                        val r = data.get().toInt() and 255
                        val g = data.get().toInt() and 255
                        val b = data.get().toInt() and 255
                        data.get()
                        Color(r,g,b)
                    }
                    check(writes.offer(RecordedWrite(connection, device, colors))) { "Write queue full" }
                }
                else -> error("Unsupported request $id")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gate.getAndSet(null)?.countDown()
        socket.getAndSet(null)?.close()
        server.close()
        worker.join(3000)
        check(!worker.isAlive) { "SDK simulator failed to stop" }
        failure.get()?.let { throw AssertionError("SDK simulator failed", it) }
    }
}
