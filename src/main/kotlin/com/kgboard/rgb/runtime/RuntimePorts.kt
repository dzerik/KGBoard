package com.kgboard.rgb.runtime

import com.kgboard.rgb.diagnostics.FrameEnvelope

/** One paired sample per actor event; scheduling and animation stay monotonic. */
fun interface RuntimeClock {
    fun sample(): TimeSample
    fun nowMs(): Long = sample().monotonicMs
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
