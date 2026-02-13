package com.kgboard.rgb.effect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EffectTargetTest {

    // ── AllLeds ──

    @Test
    fun `AllLeds is singleton`() {
        assertSame(EffectTarget.AllLeds, EffectTarget.AllLeds)
    }

    // ── SingleLed ──

    @Test
    fun `SingleLed accepts zero`() {
        val target = EffectTarget.SingleLed(0)
        assertEquals(0, target.ledIndex)
    }

    @Test
    fun `SingleLed accepts positive index`() {
        val target = EffectTarget.SingleLed(42)
        assertEquals(42, target.ledIndex)
    }

    @Test
    fun `SingleLed rejects negative index`() {
        assertThrows<IllegalArgumentException> {
            EffectTarget.SingleLed(-1)
        }
    }

    // ── LedSet ──

    @Test
    fun `LedSet accepts valid indices`() {
        val target = EffectTarget.LedSet(listOf(0, 1, 2))
        assertEquals(listOf(0, 1, 2), target.ledIndices)
    }

    @Test
    fun `LedSet rejects negative indices`() {
        assertThrows<IllegalArgumentException> {
            EffectTarget.LedSet(listOf(0, -1, 2))
        }
    }

    @Test
    fun `LedSet accepts empty list`() {
        val target = EffectTarget.LedSet(emptyList())
        assertTrue(target.ledIndices.isEmpty())
    }

    // ── Zone ──

    @Test
    fun `Zone accepts zero`() {
        val target = EffectTarget.Zone(0)
        assertEquals(0, target.zoneIndex)
    }

    @Test
    fun `Zone rejects negative index`() {
        assertThrows<IllegalArgumentException> {
            EffectTarget.Zone(-1)
        }
    }
}
