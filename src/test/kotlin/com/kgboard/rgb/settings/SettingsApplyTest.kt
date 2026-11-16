package com.kgboard.rgb.settings
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class SettingsApplyTest {
    @Test fun `invalid draft does not partially persist host or colors`() {
        val old = KgBoardSettings.State()
        var stored = old
        val events = mutableListOf<RuntimeEvent>()
        val draft = RuntimeSettings.capture(old).toState().apply { host = "new"; buildLedIndices = ",,"; idleColor = "#112233" }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransaction.applyApp(draft, { stored = it }, RuntimeEvents { events.add(it) })
        }
        assertSame(old, stored); assertTrue(events.isEmpty())
        draft.buildLedIndices = "1,2"
        SettingsTransaction.applyApp(draft, { stored = it }, RuntimeEvents { events.add(it) })
        draft.host = "mutated"
        assertEquals("new", stored.host)
        assertEquals("new", (events.single() as RuntimeEvent.SettingsApplied).settings.host)
    }
}
