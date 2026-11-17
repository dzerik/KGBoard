package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbTransport
import com.kgboard.rgb.device.InventoryToken
import com.kgboard.rgb.diagnostics.*
import java.util.concurrent.Executor

/** One in-flight write and at most one pending envelope per device. Producers never perform I/O. */
class CoalescingFrameWriter(
    private val transport: RgbTransport,
    private val executor: Executor,
    private val events: RuntimeEvents,
    private val clock: RuntimeClock = RuntimeClock { System.nanoTime() / 1_000_000 }
) : FrameWriter {
    private sealed interface Control {
        val finalFrames: Map<Int, FrameEnvelope>

        data class Connect(
            val endpoint: Endpoint,
            val session: SessionId,
            override val finalFrames: Map<Int, FrameEnvelope>
        ) : Control

        data class Disconnect(val session: SessionId, override val finalFrames: Map<Int, FrameEnvelope>) : Control
    }

    private data class Successful(val frame: FrameEnvelope, val sentAtMs: Long)

    private val lock = Any()
    private var session: SessionId? = null
    private var physicalSession: SessionId? = null
    private var physicalToken: InventoryToken? = null
    private var ready = false
    private var availableDevices: Set<Int> = emptySet()
    private var running = false
    private var closing = false
    private var control: Control? = null
    private var probe = false
    private val pending = linkedMapOf<Int, FrameEnvelope>()
    private val sent = mutableMapOf<Int, Successful>()
    private val acceptedRevisions = mutableMapOf<Int, RenderRevision>()

    override fun connect(endpoint: Endpoint, session: SessionId, finalFrames: Map<Int, FrameEnvelope>): Unit =
        synchronized(lock) {
            if (closing) return
            this.session = session; ready = false; clearQueue(); probe = false
            control = Control.Connect(endpoint, session, retirement(finalFrames))
            start()
        }

    override fun disconnect(session: SessionId, finalFrames: Map<Int, FrameEnvelope>): Unit = synchronized(lock) {
        if (closing) return
        this.session = session; ready = false; clearQueue(); probe = false
        control = Control.Disconnect(session, retirement(finalFrames))
        start()
    }

    override fun offer(envelope: FrameEnvelope): Boolean = synchronized(lock) {
        val token = envelope.token
        if (closing || !ready || session != token.session || envelope.deviceIndex !in availableDevices) return false
        val previous = physicalToken
        if (previous != null) {
            if (previous.epoch != token.epoch || token.revision < previous.revision) return false
            if (previous != token) clearQueue()
        }
        physicalToken = token
        val oldRevision = acceptedRevisions[envelope.deviceIndex]
        if (oldRevision != null && envelope.revision.value < oldRevision.value) return false
        acceptedRevisions[envelope.deviceIndex] = envelope.revision
        pending[envelope.deviceIndex] = envelope.copy(colors = frozenList(envelope.colors))
        start()
        true
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

    private fun clearQueue() {
        pending.clear(); sent.clear(); acceptedRevisions.clear()
    }

    private fun retirement(frames: Map<Int, FrameEnvelope>): Map<Int, FrameEnvelope> =
        (control?.finalFrames.orEmpty() + frames).mapValues { (_, frame) -> frame.copy(colors = frozenList(frame.colors)) }

    private fun start() {
        if (!running) {
            running = true; executor.execute(::drain)
        }
    }

    private fun report(receipt: DeliveryReceipt) = events.dispatch(RuntimeEvent.DeliveryReported(receipt))

    private fun retire(frames: Map<Int, FrameEnvelope>) {
        for (frame in frames.values) {
            val valid = synchronized(lock) { physicalSession == frame.token.session && physicalToken == frame.token }
            if (!valid) continue
            try {
                transport.write(frame.deviceIndex, frame.colors)
                report(DeliveryReceipt.Sent(frame, clock.nowMs()))
            } catch (e: Exception) {
                report(DeliveryReceipt.Failed(frame, "transport.failed"))
                // Cleanup must not prevent closing or connecting to a new endpoint.
            }
        }
    }

    private fun closeTransport() {
        transport.close()
        synchronized(lock) { physicalSession = null; physicalToken = null }
    }

    private fun drain() {
        while (true) {
            var command: Control? = null
            var frame: FrameEnvelope? = null
            var checkConnection = false
            var finish = false
            val generation = synchronized(lock) {
                command = control.also { control = null }
                if (command == null) {
                    frame =
                        pending.entries.firstOrNull()?.let { entry -> entry.value.also { pending.remove(entry.key) } }
                    if (frame == null) {
                        checkConnection = probe.also { probe = false }
                        if (!checkConnection) {
                            finish = closing
                            if (!finish) {
                                running = false; return
                            }
                        }
                    }
                }
                session
            }
            if (finish) {
                closeTransport(); synchronized(lock) { running = false }; return
            }
            try {
                when (val action = command) {
                    is Control.Connect -> {
                        retire(action.finalFrames)
                        val devices = transport.connect(action.endpoint)
                        val current = synchronized(lock) {
                            physicalSession = action.session; physicalToken = null
                            if (session == action.session && !closing) {
                                ready = true; availableDevices = devices.indices.toSet(); true
                            } else false
                        }
                        if (current) events.dispatch(
                            RuntimeEvent.Connected(
                                action.session,
                                devices
                            )
                        ) else closeTransport()
                    }

                    is Control.Disconnect -> {
                        try {
                            retire(action.finalFrames)
                        } finally {
                            closeTransport()
                        }
                    }

                    null -> if (frame != null) {
                        val currentFrame = frame
                        val valid =
                            synchronized(lock) { ready && session == generation && physicalToken == currentFrame.token }
                        if (valid) {
                            val cached = synchronized(lock) {
                                sent[currentFrame.deviceIndex]?.takeIf {
                                    it.frame.token == currentFrame.token && it.frame.colors == currentFrame.colors
                                }
                            }
                            if (cached != null) report(
                                DeliveryReceipt.Cached(
                                    currentFrame,
                                    cached.frame.revision,
                                    cached.sentAtMs
                                )
                            )
                            else {
                                transport.write(currentFrame.deviceIndex, currentFrame.colors)
                                val successful = Successful(currentFrame, clock.nowMs())
                                synchronized(lock) {
                                    if (session == generation && physicalToken == currentFrame.token)
                                        sent[currentFrame.deviceIndex] = successful
                                }
                                report(DeliveryReceipt.Sent(currentFrame, successful.sentAtMs))
                            }
                        }
                    } else if (checkConnection && transport.probe()) {
                        val current = synchronized(lock) {
                            if (session == generation && !closing) {
                                ready = false; clearQueue(); physicalToken = null; true
                            } else false
                        }
                        if (current && generation != null) events.dispatch(RuntimeEvent.InventoryChanged(generation))
                    }
                }
            } catch (e: Exception) {
                frame?.let { report(DeliveryReceipt.Failed(it, "transport.failed")) }
                closeTransport()
                val current = synchronized(lock) {
                    if (session == generation) {
                        ready = false; clearQueue(); probe = false; true
                    } else false
                }
                if (current && generation != null) events.dispatch(
                    if (command is Control.Connect)
                        RuntimeEvent.ConnectFailed(generation, e.message ?: "Connection failed")
                    else RuntimeEvent.TransportFailed(generation, e.message ?: "Transport failed")
                )
            }
        }
    }
}
