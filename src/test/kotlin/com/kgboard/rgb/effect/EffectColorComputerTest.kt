package com.kgboard.rgb.effect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class EffectColorComputerTest {

    private val idle = Color(38, 50, 56) // #263238

    // ── StaticEffect ──

    @Test
    fun `static effect returns uniform color`() {
        val effect = StaticEffect(Color.RED)
        val colors = EffectColorComputer.computeColors(effect, 0, 5, 5, idle)
        assertEquals(5, colors.size)
        assertTrue(colors.all { it == Color.RED })
    }

    @Test
    fun `static effect with count 0`() {
        val effect = StaticEffect(Color.RED)
        val colors = EffectColorComputer.computeColors(effect, 0, 0, 100, idle)
        assertTrue(colors.isEmpty())
    }

    // ── PulseEffect ──

    @Test
    fun `pulse effect at phase 0 returns minBrightness`() {
        val effect = PulseEffect(Color(100, 100, 100), minBrightness = 0.5f, periodMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 0, 1, 1, idle)
        // At phase 0: brightness = minBrightness = 0.5
        val c = colors[0]
        assertEquals(50, c.red)
        assertEquals(50, c.green)
        assertEquals(50, c.blue)
    }

    @Test
    fun `pulse effect at phase 0_5 returns full brightness`() {
        val effect = PulseEffect(Color(200, 200, 200), minBrightness = 0.0f, periodMs = 1000)
        // At elapsedMs=250 (phase=0.25), brightness = 0 + 1.0 * (0.25*2) = 0.5
        val colors = EffectColorComputer.computeColors(effect, 250, 1, 1, idle)
        assertEquals(100, colors[0].red) // 200 * 0.5 = 100
    }

    @Test
    fun `pulse effect is periodic`() {
        val effect = PulseEffect(Color(100, 100, 100), minBrightness = 0.2f, periodMs = 1000)
        val c0 = EffectColorComputer.computeColors(effect, 0, 1, 1, idle)
        val c1000 = EffectColorComputer.computeColors(effect, 1000, 1, 1, idle)
        assertEquals(c0[0], c1000[0]) // same phase
    }

    // ── FlashEffect ──

    @Test
    fun `flash effect before duration returns effect color`() {
        val effect = FlashEffect(Color.YELLOW, durationMs = 500)
        val colors = EffectColorComputer.computeColors(effect, 200, 3, 3, idle)
        assertTrue(colors.all { it == Color.YELLOW })
    }

    @Test
    fun `flash effect after duration returns idle color`() {
        val effect = FlashEffect(Color.YELLOW, durationMs = 500)
        val colors = EffectColorComputer.computeColors(effect, 600, 3, 3, idle)
        assertTrue(colors.all { it == idle })
    }

    @Test
    fun `flash effect exactly at duration returns idle`() {
        val effect = FlashEffect(Color.RED, durationMs = 500)
        val colors = EffectColorComputer.computeColors(effect, 500, 1, 1, idle)
        assertEquals(idle, colors[0])
    }

    // ── ProgressEffect ──

    @Test
    fun `progress effect at 0 pct shows all background`() {
        val bg = Color(20, 20, 20)
        val effect = ProgressEffect(Color.GREEN, backgroundColor = bg, progress = 0f)
        val colors = EffectColorComputer.computeColors(effect, 0, 10, 10, idle)
        assertTrue(colors.all { it == bg })
    }

    @Test
    fun `progress effect at 50 pct lights half`() {
        val bg = Color(20, 20, 20)
        val effect = ProgressEffect(Color.GREEN, backgroundColor = bg, progress = 0.5f)
        val colors = EffectColorComputer.computeColors(effect, 0, 10, 10, idle)
        assertEquals(5, colors.count { it == Color.GREEN })
        assertEquals(5, colors.count { it == bg })
    }

    @Test
    fun `progress effect at 100 pct lights all`() {
        val bg = Color(20, 20, 20)
        val effect = ProgressEffect(Color.GREEN, backgroundColor = bg, progress = 1.0f)
        val colors = EffectColorComputer.computeColors(effect, 0, 10, 10, idle)
        assertTrue(colors.all { it == Color.GREEN })
    }

    @Test
    fun `progress effect with targeted target uses count`() {
        val bg = Color(20, 20, 20)
        val effect = ProgressEffect(Color.RED, backgroundColor = bg, progress = 0.5f, target = EffectTarget.LedSet(listOf(0, 1, 2, 3)))
        val colors = EffectColorComputer.computeColors(effect, 0, 4, 100, idle)
        assertEquals(2, colors.count { it == Color.RED })
        assertEquals(2, colors.count { it == bg })
    }

    // ── GradientEffect ──

    @Test
    fun `gradient effect at start returns start color`() {
        val effect = GradientEffect(Color.RED, Color.BLUE, durationMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 0, 1, 1, idle)
        assertEquals(Color.RED, colors[0])
    }

    @Test
    fun `gradient effect at end returns end color`() {
        val effect = GradientEffect(Color.RED, Color.BLUE, durationMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 1000, 1, 1, idle)
        assertEquals(Color.BLUE, colors[0])
    }

    @Test
    fun `gradient effect at midpoint returns interpolated color`() {
        val effect = GradientEffect(Color(0, 0, 0), Color(100, 100, 100), durationMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 500, 1, 1, idle)
        assertEquals(50, colors[0].red)
        assertEquals(50, colors[0].green)
        assertEquals(50, colors[0].blue)
    }

    @Test
    fun `gradient effect with 0 duration returns start color`() {
        val effect = GradientEffect(Color.RED, Color.BLUE, durationMs = 0)
        val colors = EffectColorComputer.computeColors(effect, 100, 1, 1, idle)
        assertEquals(Color.RED, colors[0])
    }

    @Test
    fun `gradient effect clamps at end`() {
        val effect = GradientEffect(Color.RED, Color.BLUE, durationMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 2000, 1, 1, idle)
        assertEquals(Color.BLUE, colors[0])
    }

    // ── RainbowEffect ──

    @Test
    fun `rainbow effect returns different hues per LED`() {
        val effect = RainbowEffect(speedMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 0, 10, 10, idle)
        assertEquals(10, colors.size)
        // Adjacent LEDs should have different hues
        assertNotEquals(colors[0], colors[5])
    }

    @Test
    fun `rainbow effect is periodic`() {
        val effect = RainbowEffect(speedMs = 1000)
        val c0 = EffectColorComputer.computeColors(effect, 0, 5, 5, idle)
        val c1000 = EffectColorComputer.computeColors(effect, 1000, 5, 5, idle)
        assertEquals(c0, c1000) // same phase after full period
    }

    @Test
    fun `rainbow effect count 1 returns single color`() {
        val effect = RainbowEffect(speedMs = 1000)
        val colors = EffectColorComputer.computeColors(effect, 0, 1, 1, idle)
        assertEquals(1, colors.size)
    }

    // ── WaveEffect ──

    @Test
    fun `wave effect returns varying brightness`() {
        val effect = WaveEffect(Color.WHITE, speedMs = 1000, minBrightness = 0.0f)
        val colors = EffectColorComputer.computeColors(effect, 0, 10, 10, idle)
        assertEquals(10, colors.size)
        // Not all LEDs should have the same brightness
        val unique = colors.toSet()
        assertTrue(unique.size > 1)
    }

    @Test
    fun `wave effect is periodic`() {
        val effect = WaveEffect(Color.RED, speedMs = 1000)
        val c0 = EffectColorComputer.computeColors(effect, 0, 5, 5, idle)
        val c1000 = EffectColorComputer.computeColors(effect, 1000, 5, 5, idle)
        assertEquals(c0, c1000)
    }

    @Test
    fun `wave effect brightness stays within bounds`() {
        val effect = WaveEffect(Color(200, 200, 200), speedMs = 1000, minBrightness = 0.2f)
        val colors = EffectColorComputer.computeColors(effect, 250, 20, 20, idle)
        for (c in colors) {
            assertTrue(c.red in 0..200)
            assertTrue(c.green in 0..200)
            assertTrue(c.blue in 0..200)
        }
    }

    // ── scaleBrightness ──

    @Test
    fun `scaleBrightness with factor 1 returns same color`() {
        val result = EffectColorComputer.scaleBrightness(Color(100, 150, 200), 1f)
        assertEquals(Color(100, 150, 200), result)
    }

    @Test
    fun `scaleBrightness with factor 0 returns black`() {
        val result = EffectColorComputer.scaleBrightness(Color(100, 150, 200), 0f)
        assertEquals(Color.BLACK, result)
    }

    @Test
    fun `scaleBrightness with factor 0_5 halves values`() {
        val result = EffectColorComputer.scaleBrightness(Color(200, 100, 50), 0.5f)
        assertEquals(100, result.red)
        assertEquals(50, result.green)
        assertEquals(25, result.blue)
    }

    // ── interpolateColor ──

    @Test
    fun `interpolateColor at ratio 0 returns from color`() {
        val result = EffectColorComputer.interpolateColor(Color.RED, Color.BLUE, 0f)
        assertEquals(Color.RED, result)
    }

    @Test
    fun `interpolateColor at ratio 1 returns to color`() {
        val result = EffectColorComputer.interpolateColor(Color.RED, Color.BLUE, 1f)
        assertEquals(Color.BLUE, result)
    }

    @Test
    fun `interpolateColor at ratio 0_5 returns midpoint`() {
        val result = EffectColorComputer.interpolateColor(Color(0, 0, 0), Color(200, 100, 50), 0.5f)
        assertEquals(100, result.red)
        assertEquals(50, result.green)
        assertEquals(25, result.blue)
    }
}
