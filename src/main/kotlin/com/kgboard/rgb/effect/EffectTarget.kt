package com.kgboard.rgb.effect

/**
 * Defines which LEDs an effect targets.
 */
sealed interface EffectTarget {
    /** All LEDs on the device (default Phase 1 behavior) */
    data object AllLeds : EffectTarget

    /** A single LED by index */
    data class SingleLed(val ledIndex: Int) : EffectTarget {
        init {
            require(ledIndex >= 0) { "ledIndex must be non-negative, got $ledIndex" }
        }
    }

    /** A set of LEDs by indices */
    data class LedSet(val ledIndices: List<Int>) : EffectTarget {
        init {
            require(ledIndices.all { it >= 0 }) { "All LED indices must be non-negative" }
        }
    }

    /** An entire zone by index */
    data class Zone(val zoneIndex: Int) : EffectTarget {
        init {
            require(zoneIndex >= 0) { "zoneIndex must be non-negative, got $zoneIndex" }
        }
    }
}
