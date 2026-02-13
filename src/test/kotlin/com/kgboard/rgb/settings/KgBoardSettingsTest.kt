package com.kgboard.rgb.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Tests for KgBoardSettings static utility methods (parseColor)
 * and KgBoardProjectSettings property accessors (parseLedIndices via getter).
 */
class KgBoardSettingsTest {

    // We test parseColor by instantiating KgBoardSettings directly
    // (it has no IntelliJ service dependencies in the constructor).

    private val settings = KgBoardSettings()

    // ── parseColor ──

    @Test
    fun `parseColor valid hex`() {
        assertEquals(Color(255, 0, 0), settings.parseColor("#FF0000"))
    }

    @Test
    fun `parseColor with lowercase hex`() {
        assertEquals(Color(0, 255, 0), settings.parseColor("#00ff00"))
    }

    @Test
    fun `parseColor with mixed case`() {
        assertEquals(Color(0, 0, 255), settings.parseColor("#0000Ff"))
    }

    @Test
    fun `parseColor returns BLACK for invalid hex`() {
        assertEquals(Color.BLACK, settings.parseColor("not-a-color"))
    }

    @Test
    fun `parseColor returns BLACK for empty string`() {
        assertEquals(Color.BLACK, settings.parseColor(""))
    }

    @Test
    fun `parseColor handles white`() {
        assertEquals(Color.WHITE, settings.parseColor("#FFFFFF"))
    }

    @Test
    fun `parseColor handles black`() {
        assertEquals(Color.BLACK, settings.parseColor("#000000"))
    }

    @Test
    fun `parseColor handles custom color`() {
        val color = settings.parseColor("#263238")
        assertEquals(0x26, color.red)
        assertEquals(0x32, color.green)
        assertEquals(0x38, color.blue)
    }

    // ── State defaults ──

    @Test
    fun `default state has expected values`() {
        val state = KgBoardSettings.State()
        assertEquals("127.0.0.1", state.host)
        assertEquals(6742, state.port)
        assertEquals(0, state.deviceIndex)
        assertTrue(state.enabled)
        assertTrue(state.autoReconnect)
        assertTrue(state.autoConnect)
        assertEquals(3000L, state.effectDurationMs)
        assertEquals("#263238", state.idleColor)
    }

    @Test
    fun `default multi-device is disabled`() {
        val state = KgBoardSettings.State()
        assertFalse(state.multiDeviceEnabled)
        assertTrue(state.deviceConfigs.isEmpty())
    }

    @Test
    fun `default pomodoro is disabled`() {
        val state = KgBoardSettings.State()
        assertFalse(state.pomodoroEnabled)
        assertEquals(25, state.pomodoroWorkMinutes)
        assertEquals(5, state.pomodoroBreakMinutes)
    }

    // ── DeviceConfig ──

    @Test
    fun `DeviceConfig defaults`() {
        val config = KgBoardSettings.DeviceConfig()
        assertEquals(0, config.deviceIndex)
        assertEquals("", config.name)
        assertTrue(config.enabled)
        assertEquals("primary", config.role)
    }

    // ── ProjectSettings parseLedIndices via State ──

    @Test
    fun `ProjectSettings State default values`() {
        val state = KgBoardProjectSettings.State()
        assertTrue(state.gitEnabled)
        assertTrue(state.analysisEnabled)
        assertEquals("", state.gitLedIndices)
        assertEquals("#FFD600", state.analysisWarningColor)
    }

    @Test
    fun `GitColorRule defaults`() {
        val rule = KgBoardProjectSettings.GitColorRule()
        assertEquals(".*", rule.pattern)
        assertEquals("#FFFFFF", rule.color)
    }
}
