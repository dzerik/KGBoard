package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionControllerTest {
    private val reducer = RuntimeReducer()
    private fun next(state: RuntimeState, event: RuntimeEvent, now: Long = 0) = reducer.reduce(state, event, now)

    @Test
    fun `inventory change fences pending old geometry with a fresh session`() {
        var s = next(RuntimeState(), RuntimeEvent.ConnectRequested()).state
        val old = (s.connection as ConnectionState.Connecting).session
        s = next(s, RuntimeEvent.Connected(old, listOf(testDevice())), 0).state
        s = next(s, RuntimeEvent.InventoryChanged(old), 5000).state
        val fresh = (s.connection as ConnectionState.Connecting).session
        assertNotEquals(old, fresh)
        s = next(s, RuntimeEvent.Connected(fresh, listOf(testDevice(7))), 5010).state
        assertEquals(7, (s.connection as ConnectionState.Ready).devices.single().numLeds)
        assertEquals(s.copy(lastTime = TimeSample(5020, 5020)), next(s, RuntimeEvent.InventoryChanged(old), 5020).state)
    }

    @Test
    fun `enabling auto connect takes effect without project reopen but preserves manual disconnect`() {
        val original = RuntimeState(settings = RuntimeSettings(enabled = false))
        val enabled = next(original, RuntimeEvent.SettingsApplied(original.settings.copy(enabled = true))).state
        assertTrue(enabled.connection is ConnectionState.Connecting)
        val stopped = next(enabled, RuntimeEvent.DisconnectRequested).state
        val disabled = next(stopped, RuntimeEvent.SettingsApplied(stopped.settings.copy(enabled = false))).state
        assertTrue(
            next(
                disabled,
                RuntimeEvent.SettingsApplied(disabled.settings.copy(enabled = true))
            ).state.connection is ConnectionState.Disconnected
        )
    }

    @Test
    fun `failed connection without retry still explains error in UI`() {
        var s =
            next(RuntimeState(settings = RuntimeSettings(autoReconnect = false)), RuntimeEvent.ConnectRequested()).state
        val session = (s.connection as ConnectionState.Connecting).session
        s = next(s, RuntimeEvent.ConnectFailed(session, "refused")).state
        assertTrue(connectionStatus(s).contains("refused"))
    }

    @Test
    fun `disconnect fences late connect and cancels retry`() {
        val connecting = next(RuntimeState(), RuntimeEvent.ConnectRequested()).state
        val old = (connecting.connection as ConnectionState.Connecting).session
        val disconnected = next(connecting, RuntimeEvent.DisconnectRequested).state
        assertEquals(disconnected, next(disconnected, RuntimeEvent.Connected(old, listOf(testDevice()))).state)
        assertEquals(
            disconnected.copy(lastTime = TimeSample(100000, 100000)),
            next(disconnected, RuntimeEvent.Wake(0), 100000).state
        )
    }

    @Test
    fun `retry doubles until ready and stale failures cannot break new session`() {
        var state =
            next(RuntimeState(settings = RuntimeSettings(autoReconnect = true)), RuntimeEvent.ConnectRequested()).state
        val first = (state.connection as ConnectionState.Connecting).session
        state = next(state, RuntimeEvent.ConnectFailed(first, "reset"), 10).state
        assertEquals(1010, (state.connection as ConnectionState.Backoff).retryAtMs)
        val retry = next(state, RuntimeEvent.Wake(0), 1010)
        val second = (retry.state.connection as ConnectionState.Connecting).session
        assertNotEquals(first, second)
        assertEquals(
            retry.state.copy(lastTime = TimeSample(0, 0)),
            next(retry.state, RuntimeEvent.TransportFailed(first, "old")).state
        )
        state = next(retry.state, RuntimeEvent.ConnectFailed(second, "again"), 1020).state
        assertEquals(3020, (state.connection as ConnectionState.Backoff).retryAtMs)
        state = next(state, RuntimeEvent.Wake(0), 3020).state
        val third = (state.connection as ConnectionState.Connecting).session
        state = next(state, RuntimeEvent.Connected(third, emptyList()), 3030).state
        assertTrue(state.connection is ConnectionState.Ready)
        state = next(state, RuntimeEvent.TransportFailed(third, "drop"), 3040).state
        assertEquals(4040, (state.connection as ConnectionState.Backoff).retryAtMs)
    }
}
