package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A dialog owns its preview and diagnostic connection until Apply/close. */
class SettingsPreviewSession(private val events: RuntimeEvents, private val id: Long = ids.incrementAndGet()) : AutoCloseable {
    private val closed = AtomicBoolean()
    init { events.dispatch(RuntimeEvent.BeginSettings(id)) }
    fun connect(endpoint: Endpoint) { if (!closed.get()) events.dispatch(RuntimeEvent.DiagnosticConnect(id, endpoint)) }
    fun preview(device: Int, color: Color, indices: List<Int>) {
        if (!closed.get()) events.dispatch(RuntimeEvent.PreviewStarted(id, device, color, indices, 2000))
    }
    fun clear() { if (!closed.get()) events.dispatch(RuntimeEvent.PreviewCleared(id)) }
    fun applied() { if (!closed.get()) events.dispatch(RuntimeEvent.SettingsCommitted(id)) }
    override fun close() { if (closed.compareAndSet(false, true)) events.dispatch(RuntimeEvent.EndSettings(id)) }
    companion object { private val ids = AtomicLong() }
}
