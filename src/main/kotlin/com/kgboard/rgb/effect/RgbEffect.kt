package com.kgboard.rgb.effect

import java.awt.Color

/**
 * Represents an RGB visual effect that can be applied to a keyboard.
 */
sealed class RgbEffect(val priority: Int) : Comparable<RgbEffect> {
    abstract val name: String
    override fun compareTo(other: RgbEffect): Int = other.priority.compareTo(this.priority) // higher priority first
}

/** Static single color on all LEDs */
class StaticEffect(
    val color: Color,
    override val name: String = "static",
    priority: Int = 0
) : RgbEffect(priority)

/** Pulsing/breathing effect between two colors */
class PulseEffect(
    val color: Color,
    val minBrightness: Float = 0.2f,
    val periodMs: Long = 1000,
    override val name: String = "pulse",
    priority: Int = 0
) : RgbEffect(priority)

/** Quick flash then return to previous state */
class FlashEffect(
    val color: Color,
    val durationMs: Long = 500,
    override val name: String = "flash",
    priority: Int = 0
) : RgbEffect(priority)

/** Progress bar effect across keyboard LEDs */
class ProgressEffect(
    val color: Color,
    val backgroundColor: Color = Color(20, 20, 20),
    var progress: Float = 0f, // 0.0 - 1.0
    override val name: String = "progress",
    priority: Int = 0
) : RgbEffect(priority)
