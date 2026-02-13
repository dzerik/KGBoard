package com.kgboard.rgb.effect

import java.awt.Color

/**
 * Pure computation of effect colors — extracted from LedCompositor for testability.
 * All methods are stateless and thread-safe.
 */
object EffectColorComputer {

    fun computeColors(effect: RgbEffect, elapsedMs: Long, count: Int, totalLeds: Int, idleColor: Color): List<Color> {
        return when (effect) {
            is StaticEffect -> List(count) { effect.color }

            is PulseEffect -> {
                val period = effect.periodMs
                val phase = (elapsedMs % period).toFloat() / period
                val brightness = if (phase < 0.5f) {
                    effect.minBrightness + (1f - effect.minBrightness) * (phase * 2f)
                } else {
                    1f - (1f - effect.minBrightness) * ((phase - 0.5f) * 2f)
                }
                val color = scaleBrightness(effect.color, brightness.coerceIn(effect.minBrightness, 1f))
                List(count) { color }
            }

            is FlashEffect -> {
                if (elapsedMs < effect.durationMs) {
                    List(count) { effect.color }
                } else {
                    List(count) { idleColor }
                }
            }

            is ProgressEffect -> {
                if (effect.target is EffectTarget.AllLeds) {
                    val litLeds = (totalLeds * effect.progress).toInt()
                    (0 until count).map { i ->
                        if (i < litLeds) effect.color else effect.backgroundColor
                    }
                } else {
                    val litLeds = (count * effect.progress).toInt()
                    (0 until count).map { i ->
                        if (i < litLeds) effect.color else effect.backgroundColor
                    }
                }
            }

            is GradientEffect -> {
                val ratio = if (effect.durationMs > 0) {
                    (elapsedMs.toFloat() / effect.durationMs).coerceIn(0f, 1f)
                } else 0f
                val color = interpolateColor(effect.startColor, effect.endColor, ratio)
                List(count) { color }
            }
        }
    }

    fun scaleBrightness(color: Color, factor: Float): Color {
        return Color(
            (color.red * factor).toInt().coerceIn(0, 255),
            (color.green * factor).toInt().coerceIn(0, 255),
            (color.blue * factor).toInt().coerceIn(0, 255)
        )
    }

    fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        return Color(
            (from.red + (to.red - from.red) * ratio).toInt().coerceIn(0, 255),
            (from.green + (to.green - from.green) * ratio).toInt().coerceIn(0, 255),
            (from.blue + (to.blue - from.blue) * ratio).toInt().coerceIn(0, 255)
        )
    }
}
