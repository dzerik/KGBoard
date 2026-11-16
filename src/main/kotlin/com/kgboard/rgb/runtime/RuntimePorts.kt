package com.kgboard.rgb.runtime

import java.awt.Color

/** Monotonic milliseconds; never the adjustable wall clock. */
fun interface RuntimeClock { fun nowMs(): Long }
fun interface Cancellation { fun cancel() }
interface RuntimeScheduler { fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation }
fun interface RuntimeEvents { fun dispatch(event: RuntimeEvent) }

/** Enqueues work; implementations perform network I/O on a separate worker. */
interface FrameWriter : AutoCloseable {
    fun offer(session: SessionId, device: Int, colors: List<Color>)
    fun probe(session: SessionId) {}
    fun connect(endpoint: Endpoint, session: SessionId, finalFrames: Map<Int, List<Color>> = emptyMap())
    fun disconnect(session: SessionId, finalFrames: Map<Int, List<Color>> = emptyMap())
    override fun close()
}
