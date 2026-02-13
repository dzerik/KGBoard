package com.kgboard.rgb.effect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class RgbEffectTest {

    // ── StaticEffect ──

    @Test
    fun `StaticEffect has correct defaults`() {
        val e = StaticEffect(color = Color.RED)
        assertEquals(Color.RED, e.color)
        assertEquals("static", e.name)
        assertEquals(0, e.priority)
        assertEquals(EffectTarget.AllLeds, e.target)
    }

    @Test
    fun `StaticEffect with custom params`() {
        val target = EffectTarget.LedSet(listOf(1, 2))
        val e = StaticEffect(Color.BLUE, name = "test", priority = 5, target = target)
        assertEquals("test", e.name)
        assertEquals(5, e.priority)
        assertEquals(target, e.target)
    }

    // ── PulseEffect ──

    @Test
    fun `PulseEffect has correct defaults`() {
        val e = PulseEffect(color = Color.GREEN)
        assertEquals(0.2f, e.minBrightness)
        assertEquals(1000L, e.periodMs)
        assertEquals("pulse", e.name)
    }

    @Test
    fun `PulseEffect with custom period`() {
        val e = PulseEffect(Color.WHITE, periodMs = 500)
        assertEquals(500L, e.periodMs)
    }

    // ── FlashEffect ──

    @Test
    fun `FlashEffect has correct defaults`() {
        val e = FlashEffect(color = Color.YELLOW)
        assertEquals(500L, e.durationMs)
        assertEquals("flash", e.name)
    }

    // ── ProgressEffect ──

    @Test
    fun `ProgressEffect has correct defaults`() {
        val e = ProgressEffect(color = Color.CYAN)
        assertEquals(0f, e.progress)
        assertEquals("progress", e.name)
    }

    @Test
    fun `ProgressEffect with custom progress`() {
        val e = ProgressEffect(Color.RED, progress = 0.75f)
        assertEquals(0.75f, e.progress)
    }

    // ── GradientEffect ──

    @Test
    fun `GradientEffect has default 25min duration`() {
        val e = GradientEffect(Color.RED, Color.GREEN)
        assertEquals(25 * 60 * 1000L, e.durationMs)
    }

    // ── Comparable (priority ordering) ──

    @Test
    fun `compareTo orders by priority descending`() {
        val low = StaticEffect(Color.RED, priority = 1)
        val high = StaticEffect(Color.RED, priority = 10)
        assertTrue(high < low) // higher priority sorts first (compareTo returns negative)
    }

    @Test
    fun `sorted list puts highest priority first`() {
        val effects = listOf(
            StaticEffect(Color.RED, priority = 1),
            StaticEffect(Color.GREEN, priority = 10),
            StaticEffect(Color.BLUE, priority = 5)
        ).sorted()
        assertEquals(10, effects[0].priority)
        assertEquals(5, effects[1].priority)
        assertEquals(1, effects[2].priority)
    }
}
