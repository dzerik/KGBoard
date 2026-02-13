package com.kgboard.rgb.settings

import com.kgboard.rgb.effect.EffectTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for per-key LED selection features:
 * - parseLedIndices: parsing comma-separated LED index strings
 * - resolveTarget: converting LED indices to EffectTarget
 * - Default values for new State fields
 * - Integration: State field → property accessor → resolveTarget
 */
class KgBoardSettingsPerKeyTest {

    private val settings = KgBoardSettings()

    // ── parseLedIndices ──

    @Test
    fun `parseLedIndices with valid comma-separated values`() {
        val result = settings.parseLedIndices("0,1,2")
        assertEquals(listOf(0, 1, 2), result)
    }

    @Test
    fun `parseLedIndices with spaces`() {
        val result = settings.parseLedIndices(" 3 , 5 , 7 ")
        assertEquals(listOf(3, 5, 7), result)
    }

    @Test
    fun `parseLedIndices empty string returns empty list`() {
        val result = settings.parseLedIndices("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseLedIndices filters negative values`() {
        val result = settings.parseLedIndices("0,-1,2,-5,3")
        assertEquals(listOf(0, 2, 3), result)
    }

    @Test
    fun `parseLedIndices ignores non-numeric values`() {
        val result = settings.parseLedIndices("0,abc,2,def,4")
        assertEquals(listOf(0, 2, 4), result)
    }

    @Test
    fun `parseLedIndices single value`() {
        val result = settings.parseLedIndices("42")
        assertEquals(listOf(42), result)
    }

    @Test
    fun `parseLedIndices all invalid returns empty`() {
        val result = settings.parseLedIndices("abc,def,ghi")
        assertTrue(result.isEmpty())
    }

    // ── resolveTarget ──

    @Test
    fun `resolveTarget empty list returns AllLeds`() {
        val target = settings.resolveTarget(emptyList())
        assertEquals(EffectTarget.AllLeds, target)
    }

    @Test
    fun `resolveTarget non-empty list returns LedSet`() {
        val target = settings.resolveTarget(listOf(0, 1, 2))
        assertEquals(EffectTarget.LedSet(listOf(0, 1, 2)), target)
    }

    @Test
    fun `resolveTarget single element returns LedSet`() {
        val target = settings.resolveTarget(listOf(5))
        assertEquals(EffectTarget.LedSet(listOf(5)), target)
    }

    // ── Default values for new fields ──

    @Test
    fun `default per-key LED indices are empty`() {
        val state = KgBoardSettings.State()
        assertEquals("", state.buildLedIndices)
        assertEquals("", state.executionLedIndices)
        assertEquals("", state.testLedIndices)
        assertEquals("", state.fileSaveLedIndices)
        assertEquals("", state.debugBreakpointLedIndices)
        assertEquals("", state.pomodoroLedIndices)
    }

    // ── Integration: State → property → resolveTarget ──

    @Test
    fun `buildLedIndices property accessor parses state field`() {
        settings.state.buildLedIndices = "10,20,30"
        assertEquals(listOf(10, 20, 30), settings.buildLedIndices)
    }

    @Test
    fun `empty buildLedIndices resolves to AllLeds`() {
        settings.state.buildLedIndices = ""
        val target = settings.resolveTarget(settings.buildLedIndices)
        assertEquals(EffectTarget.AllLeds, target)
    }

    @Test
    fun `configured buildLedIndices resolves to LedSet`() {
        settings.state.buildLedIndices = "0,1,2"
        val target = settings.resolveTarget(settings.buildLedIndices)
        assertEquals(EffectTarget.LedSet(listOf(0, 1, 2)), target)
    }

    @Test
    fun `executionLedIndices property accessor`() {
        settings.state.executionLedIndices = "5,10"
        assertEquals(listOf(5, 10), settings.executionLedIndices)
    }

    @Test
    fun `testLedIndices property accessor`() {
        settings.state.testLedIndices = "100"
        assertEquals(listOf(100), settings.testLedIndices)
    }

    @Test
    fun `fileSaveLedIndices property accessor`() {
        settings.state.fileSaveLedIndices = "1,2,3"
        assertEquals(listOf(1, 2, 3), settings.fileSaveLedIndices)
    }

    @Test
    fun `pomodoroLedIndices property accessor`() {
        settings.state.pomodoroLedIndices = "0,5,10,15"
        assertEquals(listOf(0, 5, 10, 15), settings.pomodoroLedIndices)
    }
}
