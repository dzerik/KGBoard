package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsPreviewSessionTest {
    @Test
    fun `closing preview immediately restores live frame and old dialog cannot clear new one`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false));
        val p = ProjectId("p"); h.project(p)
        val events = RuntimeEvents { h.send(it) }
        val first = SettingsPreviewSession(events, 1)
        first.previewColor(h.snapshotForTest(), 0, Color.RED, listOf(1))
        assertEquals(Color.RED, h.lastFrame(0)[1])
        val second = SettingsPreviewSession(events, 2)
        second.previewColor(h.snapshotForTest(), 1, Color.BLUE, listOf(0))
        first.close()
        assertEquals(Color.BLUE, h.lastFrame(1)[0])
        second.close()
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(1)[0])
        h.advanceBy(5000)
        assertNull(h.snapshotForTest().preview)
    }

    @Test
    fun `disconnect restores base frame before retiring preview session`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false))
        val p = ProjectId("p"); h.project(p)
        val preview = SettingsPreviewSession(RuntimeEvents { h.send(it) }, 1)
        preview.previewColor(h.snapshotForTest(), 0, Color.RED, emptyList())
        h.send(RuntimeEvent.DisconnectRequested)
        assertNull(h.snapshotForTest().preview)
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0)[0])
    }

    @Test
    fun `apply sets a new diagnostic baseline for a later cancel`() {
        val reducer = RuntimeReducer()
        var state = reducer.reduce(RuntimeState(), RuntimeEvent.BeginSettings(1), 0).state
        state = reducer.reduce(state, RuntimeEvent.SettingsApplied(state.settings.copy(host = "applied")), 0).state
        state = reducer.reduce(state, RuntimeEvent.SettingsCommitted(1), 0).state
        state = reducer.reduce(state, RuntimeEvent.DiagnosticConnect(1, Endpoint("temporary", 10)), 0).state
        state = reducer.reduce(state, RuntimeEvent.EndSettings(1), 0).state
        assertFalse(state.connectionWanted)
        assertEquals("applied", state.endpoint.host)
    }

    @Test
    fun `stale failed transport cannot clear preview of a newer ready session`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false)); h.project(ProjectId("p"))
        val preview = SettingsPreviewSession(RuntimeEvents { h.send(it) }, 1)
        preview.previewColor(h.snapshotForTest(), 0, Color.RED, listOf(0))
        h.send(RuntimeEvent.TransportFailed(SessionId(-1), "stale"))
        assertNotNull(h.snapshotForTest().preview)
        assertEquals(Color.RED, h.lastFrame(0)[0])
    }

    @Test
    fun `cancel restores diagnostic connection intent and ignores late successful connect`() {
        val reducer = RuntimeReducer()
        var s = RuntimeState()
        s = reducer.reduce(s, RuntimeEvent.BeginSettings(1), 0).state
        s = reducer.reduce(s, RuntimeEvent.DiagnosticConnect(1, Endpoint("draft", 10)), 0).state
        val session = (s.connection as ConnectionState.Connecting).session
        s = reducer.reduce(s, RuntimeEvent.EndSettings(1), 0).state
        assertFalse(s.connectionWanted)
        assertTrue(s.connection is ConnectionState.Disconnected)
        assertEquals(s, reducer.reduce(s, RuntimeEvent.Connected(session, emptyList()), 0).state)
    }
}
