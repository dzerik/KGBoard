package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbTransport
import java.awt.Color
import java.util.concurrent.Executor

/** One in-flight write and at most one pending frame per device. No producer performs I/O. */
class CoalescingFrameWriter(
    private val transport: RgbTransport,
    private val executor: Executor,
    private val events: RuntimeEvents
) : FrameWriter {
    private sealed interface Control {
        val finalFrames: Map<Int, List<Color>>
        data class Connect(val endpoint: Endpoint, val session: SessionId, override val finalFrames: Map<Int, List<Color>>) : Control
        data class Disconnect(val session: SessionId, override val finalFrames: Map<Int, List<Color>>) : Control
    }
    private val lock = Any()
    private var session: SessionId? = null
    private var ready = false
    private var availableDevices: Set<Int> = emptySet()
    private var running = false
    private var closing = false
    private var control: Control? = null
    private var probe = false
    private val pending = linkedMapOf<Int, List<Color>>()
    private val sent = mutableMapOf<Int, List<Color>>()

    override fun connect(endpoint: Endpoint, session: SessionId, finalFrames: Map<Int, List<Color>>): Unit = synchronized(lock) {
        if (closing) return
        this.session = session; ready = false; pending.clear(); sent.clear(); probe = false
        control = Control.Connect(endpoint, session, retirement(finalFrames))
        start()
    }
    override fun disconnect(session: SessionId, finalFrames: Map<Int, List<Color>>): Unit = synchronized(lock) {
        if (closing) return
        this.session = session; ready = false; pending.clear(); sent.clear(); probe = false
        control = Control.Disconnect(session, retirement(finalFrames))
        start()
    }
    override fun offer(session: SessionId, device: Int, colors: List<Color>): Unit = synchronized(lock) {
        if (closing || !ready || this.session != session || device !in availableDevices) return
        pending[device] = frozenList(colors)
        start()
    }
    override fun probe(session: SessionId): Unit = synchronized(lock) {
        if (closing || !ready || this.session != session) return
        probe = true
        start()
    }
    override fun close(): Unit = synchronized(lock) {
        if (closing) return
        closing = true
        control = when (val action = control) {
            is Control.Connect -> Control.Disconnect(action.session, action.finalFrames)
            else -> action
        }
        if (!ready) pending.clear()
        probe = false
        start()
    }
    private fun retirement(frames: Map<Int, List<Color>>): Map<Int, List<Color>> =
        (control?.finalFrames.orEmpty() + frames).mapValues { frozenList(it.value) }

    private fun start() {
        if (!running) { running = true; executor.execute(::drain) }
    }
    private fun drain() {
        while (true) {
            var command: Control? = null
            var frame: Pair<Int, List<Color>>? = null
            var checkConnection = false
            var finish = false
            val generation = synchronized(lock) {
                command = control.also { control = null }
                if (command == null) {
                    frame = pending.entries.firstOrNull()?.let { entry -> (entry.key to entry.value).also { pending.remove(entry.key) } }
                    if (frame == null) {
                        checkConnection = probe.also { probe = false }
                        if (!checkConnection) {
                            finish = closing
                            if (!finish) { running = false; return }
                        }
                    }
                }
                session
            }
            if (finish) { transport.close(); synchronized(lock) { running = false }; return }
            try {
                when (val action = command) {
                    is Control.Connect -> {
                        action.finalFrames.forEach { (device, colors) -> runCatching { transport.write(device, colors) } }
                        val devices = transport.connect(action.endpoint)
                        val current = synchronized(lock) {
                            if (session == action.session && !closing) { ready = true; availableDevices = devices.indices.toSet(); true } else false
                        }
                        if (current) events.dispatch(RuntimeEvent.Connected(action.session, devices)) else transport.close()
                    }
                    is Control.Disconnect -> {
                        try { action.finalFrames.forEach { (device, colors) -> transport.write(device, colors) } }
                        finally { transport.close() }
                    }
                    null -> if (frame != null) {
                        val (device, colors) = frame
                        val write = synchronized(lock) { ready && session == generation && sent[device] != colors }
                        if (write) {
                            transport.write(device, colors)
                            synchronized(lock) { if (session == generation) sent[device] = colors }
                        }
                    } else if (checkConnection && transport.probe()) {
                        val current = synchronized(lock) {
                            if (session == generation && !closing) { ready = false; pending.clear(); sent.clear(); true } else false
                        }
                        if (current && generation != null) events.dispatch(RuntimeEvent.InventoryChanged(generation))
                    }
                }
            } catch (e: Exception) {
                transport.close()
                val current = synchronized(lock) {
                    if (session == generation) { ready = false; pending.clear(); sent.clear(); probe = false; true } else false
                }
                if (current && generation != null) events.dispatch(if (command is Control.Connect)
                    RuntimeEvent.ConnectFailed(generation, e.message ?: "Connection failed")
                    else RuntimeEvent.TransportFailed(generation, e.message ?: "Transport failed"))
            }
        }
    }
}
