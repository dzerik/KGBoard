package com.kgboard.rgb.settings

import com.kgboard.rgb.device.DeviceChoice
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.SettingsScope
import com.kgboard.rgb.target.GroupDefinition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One UI owner; multiple live Configurables share a baseline without sharing drafts. */
class SettingsPreviewSession(
    private val events: RuntimeEvents, val id: Long = ids.incrementAndGet(),
    scope: SettingsScope = SettingsScope.APP, project: ProjectId? = null
) : AutoCloseable {
    private val closed = AtomicBoolean()

    init {
        events.dispatch(RuntimeEvent.BeginSettings(id, scope, project))
    }

    fun connect(endpoint: Endpoint) {
        require(endpoint.host.isNotBlank() && endpoint.port in 1..65535) { "Invalid diagnostic server" }
        if (!closed.get()) events.dispatch(RuntimeEvent.DiagnosticConnect(id, endpoint))
    }

    fun availability(
        state: RuntimeState, choice: DeviceChoice?, content: PreviewContent,
        groups: Map<String, GroupDefinition>? = null
    ): PreviewAvailability =
        if (choice == null) PreviewAvailability(false, "preview.selectDevice")
        else PreviewAvailability.evaluate(state, PreviewRequest(id, choice, content, groups))

    fun preview(choice: DeviceChoice, content: PreviewContent, groups: Map<String, GroupDefinition>? = null) {
        if (!closed.get()) events.dispatch(
            RuntimeEvent.PreviewStarted(
                PreviewRequest(
                    id,
                    choice,
                    content,
                    groups
                ).frozen()
            )
        )
    }

    fun clear() {
        if (!closed.get()) events.dispatch(RuntimeEvent.PreviewCleared(id))
    }

    fun applied() {
        if (!closed.get()) events.dispatch(RuntimeEvent.SettingsCommitted(id))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) events.dispatch(RuntimeEvent.EndSettings(id))
    }

    companion object {
        private val ids = AtomicLong()
    }
}
