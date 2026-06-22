package com.kgboard.rgb.effect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class ComposeFrameTest {

    private val idle = Color(38, 50, 56) // #263238
    private val baseTime = 1000000L

    // ── Basic composition ──

    @Test
    fun `empty effects returns idle frame`() {
        val frame = EffectColorComputer.composeFrame(idle, emptyList(), 5, baseTime)
        assertEquals(5, frame.size)
        assertTrue(frame.all { it == idle })
    }

    @Test
    fun `single static AllLeds effect fills entire frame`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, 0)
        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime)
        assertTrue(frame.all { it == Color.RED })
    }

    @Test
    fun `single static LedSet effect fills only target LEDs`() {
        val effect = StaticEffect(Color.GREEN, target = EffectTarget.LedSet(listOf(1, 3)))
        val ae = ActiveEffect(effect, baseTime, 0)
        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime)

        assertEquals(idle, frame[0])
        assertEquals(Color.GREEN, frame[1])
        assertEquals(idle, frame[2])
        assertEquals(Color.GREEN, frame[3])
        assertEquals(idle, frame[4])
    }

    @Test
    fun `single static SingleLed effect fills only one LED`() {
        val effect = StaticEffect(Color.BLUE, target = EffectTarget.SingleLed(2))
        val ae = ActiveEffect(effect, baseTime, 0)
        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime)

        assertEquals(idle, frame[0])
        assertEquals(idle, frame[1])
        assertEquals(Color.BLUE, frame[2])
        assertEquals(idle, frame[3])
        assertEquals(idle, frame[4])
    }

    // ── Priority composition ──

    @Test
    fun `higher priority overwrites lower priority on same LEDs`() {
        val lowEffect = StaticEffect(Color.RED, priority = 0)
        val highEffect = StaticEffect(Color.BLUE, priority = 10)
        val low = ActiveEffect(lowEffect, baseTime, 0)
        val high = ActiveEffect(highEffect, baseTime, 0)

        val frame = EffectColorComputer.composeFrame(idle, listOf(low, high), 5, baseTime)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun `priority order is independent of list order`() {
        val lowEffect = StaticEffect(Color.RED, priority = 0)
        val highEffect = StaticEffect(Color.BLUE, priority = 10)
        val low = ActiveEffect(lowEffect, baseTime, 0)
        val high = ActiveEffect(highEffect, baseTime, 0)

        // High priority first in list, but still overwrites
        val frame = EffectColorComputer.composeFrame(idle, listOf(high, low), 5, baseTime)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun `test effect priority 1000 overwrites all other effects`() {
        val codeAnalysis = StaticEffect(Color.YELLOW, name = "code-analysis", priority = 5,
            target = EffectTarget.LedSet(listOf(0, 1)))
        val gitStatus = StaticEffect(Color.CYAN, name = "git-status", priority = 3,
            target = EffectTarget.LedSet(listOf(2, 3)))
        val testEffect = StaticEffect(Color.GREEN, name = "test-color", priority = 1000)

        val effects = listOf(
            ActiveEffect(codeAnalysis, baseTime, 0),
            ActiveEffect(gitStatus, baseTime, 0),
            ActiveEffect(testEffect, baseTime, 2000)
        )

        val frame = EffectColorComputer.composeFrame(idle, effects, 5, baseTime)
        // Test effect (AllLeds, priority 1000) overwrites everything
        assertTrue(frame.all { it == Color.GREEN })
    }

    @Test
    fun `per-key effect on subset does not affect other LEDs`() {
        val global = StaticEffect(Color.RED, priority = 0)
        val perKey = StaticEffect(Color.BLUE, priority = 5, target = EffectTarget.LedSet(listOf(2)))

        val effects = listOf(
            ActiveEffect(global, baseTime, 0),
            ActiveEffect(perKey, baseTime, 0)
        )

        val frame = EffectColorComputer.composeFrame(idle, effects, 5, baseTime)
        assertEquals(Color.RED, frame[0])
        assertEquals(Color.RED, frame[1])
        assertEquals(Color.BLUE, frame[2]) // per-key overwrites
        assertEquals(Color.RED, frame[3])
        assertEquals(Color.RED, frame[4])
    }

    // ── Timeout expiration ──

    @Test
    fun `effect within timeout is rendered`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, timeoutMs = 2000)

        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime + 1000)
        assertTrue(frame.all { it == Color.RED })
    }

    @Test
    fun `expired effect is not rendered`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, timeoutMs = 2000)

        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime + 2000)
        assertTrue(frame.all { it == idle })
    }

    @Test
    fun `effect at exact timeout boundary is expired`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, timeoutMs = 2000)

        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime + 2000)
        assertTrue(frame.all { it == idle })
    }

    @Test
    fun `zero timeout means no expiration`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, timeoutMs = 0)

        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime + 999999)
        assertTrue(frame.all { it == Color.RED })
    }

    @Test
    fun `test effect expires after 2s leaving per-key effects visible`() {
        val perKey = StaticEffect(Color.CYAN, name = "code-analysis", priority = 5,
            target = EffectTarget.LedSet(listOf(0, 1)))
        val testEffect = StaticEffect(Color.GREEN, name = "test-color", priority = 1000)

        val effects = listOf(
            ActiveEffect(perKey, baseTime, 0),
            ActiveEffect(testEffect, baseTime, 2000)
        )

        // During test: all green
        val during = EffectColorComputer.composeFrame(idle, effects, 5, baseTime + 500)
        assertTrue(during.all { it == Color.GREEN })

        // After test expires: per-key visible, rest idle
        val after = EffectColorComputer.composeFrame(idle, effects, 5, baseTime + 2500)
        assertEquals(Color.CYAN, after[0])
        assertEquals(Color.CYAN, after[1])
        assertEquals(idle, after[2])
        assertEquals(idle, after[3])
        assertEquals(idle, after[4])
    }

    // ── Flash effect in composition ──

    @Test
    fun `flash effect shows color during duration then idle`() {
        val flash = FlashEffect(Color.RED, durationMs = 500)
        val ae = ActiveEffect(flash, baseTime, timeoutMs = 1000)

        val during = EffectColorComputer.composeFrame(idle, listOf(ae), 3, baseTime + 200)
        assertTrue(during.all { it == Color.RED })

        val after = EffectColorComputer.composeFrame(idle, listOf(ae), 3, baseTime + 600)
        assertTrue(after.all { it == idle })
    }

    // ── Edge cases ──

    @Test
    fun `LedSet with out-of-range indices are filtered`() {
        val effect = StaticEffect(Color.RED, target = EffectTarget.LedSet(listOf(0, 10, 20)))
        val ae = ActiveEffect(effect, baseTime, 0)

        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 5, baseTime)
        assertEquals(Color.RED, frame[0])
        assertEquals(idle, frame[1]) // index 10, 20 filtered out
    }

    @Test
    fun `zero numLeds returns empty frame`() {
        val effect = StaticEffect(Color.RED)
        val ae = ActiveEffect(effect, baseTime, 0)
        val frame = EffectColorComputer.composeFrame(idle, listOf(ae), 0, baseTime)
        assertEquals(0, frame.size)
    }

    @Test
    fun `multiple overlapping per-key effects respect priority`() {
        val low = StaticEffect(Color.RED, priority = 1, target = EffectTarget.LedSet(listOf(0, 1, 2)))
        val high = StaticEffect(Color.BLUE, priority = 10, target = EffectTarget.LedSet(listOf(1, 2, 3)))

        val effects = listOf(
            ActiveEffect(low, baseTime, 0),
            ActiveEffect(high, baseTime, 0)
        )

        val frame = EffectColorComputer.composeFrame(idle, effects, 5, baseTime)
        assertEquals(Color.RED, frame[0])  // only low
        assertEquals(Color.BLUE, frame[1]) // high overwrites low
        assertEquals(Color.BLUE, frame[2]) // high overwrites low
        assertEquals(Color.BLUE, frame[3]) // only high
        assertEquals(idle, frame[4])       // neither
    }
}

class ResolveIndicesTest {

    @Test
    fun `AllLeds returns full range`() {
        val indices = EffectColorComputer.resolveIndices(EffectTarget.AllLeds, 5)
        assertEquals(listOf(0, 1, 2, 3, 4), indices)
    }

    @Test
    fun `SingleLed returns single index`() {
        val indices = EffectColorComputer.resolveIndices(EffectTarget.SingleLed(3), 10)
        assertEquals(listOf(3), indices)
    }

    @Test
    fun `SingleLed out of range returns empty`() {
        val indices = EffectColorComputer.resolveIndices(EffectTarget.SingleLed(10), 5)
        assertTrue(indices.isEmpty())
    }

    @Test
    fun `LedSet filters out-of-range`() {
        val indices = EffectColorComputer.resolveIndices(EffectTarget.LedSet(listOf(0, 3, 7, 15)), 10)
        assertEquals(listOf(0, 3, 7), indices)
    }

    @Test
    fun `Zone returns empty list (no device info)`() {
        val indices = EffectColorComputer.resolveIndices(EffectTarget.Zone(0), 10)
        assertTrue(indices.isEmpty())
    }
}
