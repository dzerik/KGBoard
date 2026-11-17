package com.kgboard.rgb.runtime

import com.kgboard.rgb.diagnostics.FrameEnvelope

/** Monotonic milliseconds; never the adjustable wall clock. */
fun interface RuntimeClock {
    fun nowMs(): Long
}

fun interface Cancellation {
    fun cancel()
}

interface RuntimeScheduler {
    fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation
}

fun interface RuntimeEvents {
    fun dispatch(event: RuntimeEvent)
}

/** Enqueues work; implementations perform network I/O on a separate worker. */
interface FrameWriter : AutoCloseable {
    /** Returns true only when this inventory's frame was accepted for asynchronous delivery. */
    fun offer(envelope: FrameEnvelope): Boolean
    fun probe(session: SessionId) {}
    fun connect(endpoint: Endpoint, session: SessionId, finalFrames: Map<Int, FrameEnvelope> = emptyMap())
    fun disconnect(session: SessionId, finalFrames: Map<Int, FrameEnvelope> = emptyMap())
    override fun close()
}
