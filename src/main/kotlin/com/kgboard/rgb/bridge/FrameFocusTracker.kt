package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*

data class FocusSnapshot(val focused: Boolean, val project: ProjectId?)
interface FocusedProjectSource {
    fun current(): FocusSnapshot
    fun subscribe(callback: () -> Unit): Cancellation
}

/** Re-read actual focus rather than trusting ordering of lost/gained callbacks. */
class FrameFocusTracker(private val source: FocusedProjectSource, private val events: RuntimeEvents) : AutoCloseable {
    private var subscription: Cancellation? = null
    private var closed = false
    fun start() {
        if (closed || subscription != null) return
        subscription = source.subscribe(::refresh)
        refresh()
    }
    fun refresh() {
        if (closed) return
        val current = source.current()
        if (current.focused) events.dispatch(RuntimeEvent.ProjectActivated(current.project))
        events.dispatch(RuntimeEvent.FocusChanged(current.focused))
    }
    override fun close() { closed = true; subscription?.cancel(); subscription = null }
}
