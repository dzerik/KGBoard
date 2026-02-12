package com.kgboard.rgb.effect

/**
 * Defines which LEDs an effect targets.
 */
sealed interface EffectTarget {
    /** All LEDs on the device (default Phase 1 behavior) */
    data object AllLeds : EffectTarget

    /** A single LED by index */
    data class SingleLed(val ledIndex: Int) : EffectTarget

    /** A set of LEDs by indices */
    data class LedSet(val ledIndices: List<Int>) : EffectTarget

    /** An entire zone by index */
    data class Zone(val zoneIndex: Int) : EffectTarget
}
