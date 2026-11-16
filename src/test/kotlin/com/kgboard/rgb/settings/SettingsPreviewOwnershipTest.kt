package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsPreviewOwnershipTest {
    @Test
    fun `overlapping settings pages cannot orphan a temporary connection`() {
        val reducer = RuntimeReducer()
        var state = RuntimeState()
        fun send(event: RuntimeEvent) {
            state = reducer.reduce(state, event, 0).state
        }
        send(RuntimeEvent.BeginSettings(1))
        send(RuntimeEvent.DiagnosticConnect(1, Endpoint("temporary", 6742)))
        send(RuntimeEvent.BeginSettings(2))
        send(RuntimeEvent.EndSettings(1))
        send(RuntimeEvent.EndSettings(2))
        assertFalse(state.connectionWanted)
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertEquals("127.0.0.1", state.endpoint.host)
    }

    @Test
    fun `returning to an already open settings page retains its preview authority`() {
        RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false).withTestBindings()).use { h ->
            h.project()
            val events = RuntimeEvents { h.send(it) }
            val first = SettingsPreviewSession(events, 1)
            val second = SettingsPreviewSession(events, 2)
            try {
                first.previewColor(h.snapshotForTest(), 0, Color.RED, listOf(0))
                assertEquals(Color.RED, h.lastFrame()[0])
            } finally {
                first.close(); second.close()
            }
        }
    }

    @Test
    fun `project apply cannot commit an application pages diagnostic connection`() {
        val reducer = RuntimeReducer()
        var state = RuntimeState()
        fun send(event: RuntimeEvent) {
            state = reducer.reduce(state, event, 0).state
        }

        val project = ProjectId("p")
        send(RuntimeEvent.ProjectOpened(project))
        send(RuntimeEvent.BeginSettings(1))
        send(RuntimeEvent.BeginSettings(2, com.kgboard.rgb.scenario.SettingsScope.PROJECT, project))
        send(RuntimeEvent.DiagnosticConnect(1, Endpoint("temporary", 10)))
        send(RuntimeEvent.SettingsCommitted(2))
        send(RuntimeEvent.EndSettings(1))
        assertFalse(state.connectionWanted)
        assertEquals("127.0.0.1", state.endpoint.host)
        send(RuntimeEvent.EndSettings(2))
        assertFalse(state.connectionWanted)
    }

    @Test
    fun `another page can adopt a temporary connection for its preview until it closes`() {
        val reducer = RuntimeReducer()
        var state = RuntimeState(focused = true)
        fun send(event: RuntimeEvent) {
            state = reducer.reduce(state, event, 0).state
        }
        send(RuntimeEvent.BeginSettings(1))
        send(RuntimeEvent.DiagnosticConnect(1, Endpoint("temporary", 10)))
        val session = (state.connection as ConnectionState.Connecting).session
        send(RuntimeEvent.Connected(session, testDevices()))
        send(RuntimeEvent.BeginSettings(2))
        send(RuntimeEvent.PreviewStarted(PreviewRequest(2, state.capturedChoice(), PreviewContent.DeviceTest)))
        send(RuntimeEvent.EndSettings(1))
        assertTrue(state.connectionWanted)
        assertEquals(2L, state.preview?.owner)
        send(RuntimeEvent.EndSettings(2))
        assertFalse(state.connectionWanted)
        assertNull(state.preview)
    }

    @Test
    fun `closing project revokes its settings owner and late messages are inert`() {
        val reducer = RuntimeReducer()
        var state = RuntimeState(focused = true, connection = ConnectionState.Ready(SessionId(1), testDevices()))
        fun send(event: RuntimeEvent) {
            state = reducer.reduce(state, event, 0).state
        }

        val project = ProjectId("p")
        send(RuntimeEvent.ProjectOpened(project))
        send(RuntimeEvent.BeginSettings(1, com.kgboard.rgb.scenario.SettingsScope.PROJECT, project))
        val request = PreviewRequest(1, state.capturedChoice(), PreviewContent.DeviceTest)
        send(RuntimeEvent.PreviewStarted(request))
        assertNotNull(state.preview)
        send(RuntimeEvent.ProjectClosed(project))
        assertNull(state.settingsSession); assertNull(state.preview)
        val closed = state
        send(RuntimeEvent.PreviewStarted(request))
        send(RuntimeEvent.DiagnosticConnect(1, Endpoint("late", 10)))
        send(RuntimeEvent.SettingsCommitted(1))
        send(RuntimeEvent.EndSettings(1))
        assertEquals(closed, state)
    }

    @Test
    fun `manual disconnect while settings is open survives every page cancellation`() {
        val reducer = RuntimeReducer()
        var state = RuntimeState(connectionWanted = true)
        fun send(event: RuntimeEvent) {
            state = reducer.reduce(state, event, 0).state
        }
        send(RuntimeEvent.BeginSettings(1))
        send(RuntimeEvent.BeginSettings(2))
        send(RuntimeEvent.DisconnectRequested)
        send(RuntimeEvent.EndSettings(1))
        send(RuntimeEvent.EndSettings(2))
        assertFalse(state.connectionWanted); assertTrue(state.manuallyDisconnected)
    }
}
