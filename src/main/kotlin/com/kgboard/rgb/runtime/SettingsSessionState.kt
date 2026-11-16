package com.kgboard.rgb.runtime

import java.awt.Color

data class SettingsSessionState(val id: Long, val endpoint: Endpoint, val wanted: Boolean,
    val manuallyDisconnected: Boolean, val applied: Boolean = false)
data class PreviewState(val owner: Long, val device: Int, val color: Color, val indices: List<Int>, val deadlineMs: Long)

internal fun settingsTransition(state: RuntimeState, event: RuntimeEvent, now: Long): Transition {
    val controller = ConnectionController()
    return when(event) {
        is RuntimeEvent.BeginSettings -> Transition(state.copy(settingsSession = SettingsSessionState(event.id,
            state.endpoint, state.connectionWanted, state.manuallyDisconnected), preview = null))
        is RuntimeEvent.DiagnosticConnect -> if (state.settingsSession?.id != event.id) Transition(state) else
            controller.reduce(state.copy(preview = null), RuntimeEvent.ConnectRequested(event.endpoint), now)
        is RuntimeEvent.PreviewCleared -> if (state.settingsSession?.id != event.id) Transition(state) else Transition(state.copy(preview = null))
        is RuntimeEvent.ConnectFailed, is RuntimeEvent.TransportFailed, is RuntimeEvent.InventoryChanged ->
            controller.reduce(state, event, now).let { transition ->
                if (transition.state.connection == state.connection) transition else transition.copy(state = transition.state.copy(preview = null))
            }
        is RuntimeEvent.SettingsCommitted -> if (state.settingsSession?.id != event.id) Transition(state) else
            Transition(state.copy(preview = null, settingsSession = SettingsSessionState(event.id, state.endpoint, state.connectionWanted, state.manuallyDisconnected, applied = true)))
        is RuntimeEvent.EndSettings -> {
            val session = state.settingsSession
            if (session?.id != event.id) Transition(state) else {
                val cleared = state.copy(settingsSession = null, preview = null)
                if (state.endpoint == session.endpoint && state.connectionWanted == session.wanted) Transition(cleared)
                else controller.reduce(cleared, if (session.wanted) RuntimeEvent.ConnectRequested(session.endpoint) else RuntimeEvent.DisconnectRequested, now)
                    .let { it.copy(state = it.state.copy(endpoint = session.endpoint, manuallyDisconnected = session.manuallyDisconnected)) }
            }
        }
        is RuntimeEvent.PreviewStarted -> {
            val device = (state.connection as? ConnectionState.Ready)?.devices?.getOrNull(event.device)
            if (state.settingsSession?.id != event.id || !state.settings.enabled || device == null || device.numLeds <= 0 ||
                event.indices.any { it !in 0 until device.numLeds }) Transition(state)
            else Transition(state.copy(preview = PreviewState(event.id, event.device, event.color, frozenList(event.indices), now + event.durationMs.coerceIn(1, 10000))))
        }
        RuntimeEvent.DisconnectRequested -> controller.reduce(state.copy(preview = null,
            settingsSession = state.settingsSession?.copy(wanted = false, manuallyDisconnected = true)), event, now)
        is RuntimeEvent.SettingsApplied -> controller.reduce(state.copy(preview = null), event, now)
        else -> controller.reduce(state, event, now)
    }
}
