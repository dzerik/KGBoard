package com.kgboard.rgb.effect

import java.awt.Color
import com.kgboard.rgb.diagnostics.CompositionLayer
import com.kgboard.rgb.diagnostics.DecisionComposer

/**
 * Pure computation of effect colors — shared by the runtime and deterministic tests.
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

            is RainbowEffect -> {
                val hueOffset = (elapsedMs % effect.speedMs).toFloat() / effect.speedMs
                List(count) { i ->
                    val hue = (hueOffset + i.toFloat() / count.coerceAtLeast(1)) % 1f
                    Color.getHSBColor(hue, 1f, 1f)
                }
            }

            is WaveEffect -> {
                val phase = (elapsedMs % effect.speedMs).toFloat() / effect.speedMs
                List(count) { i ->
                    val position = i.toFloat() / count.coerceAtLeast(1)
                    val wave = (Math.sin(2.0 * Math.PI * (position - phase)).toFloat() + 1f) / 2f
                    val brightness = effect.minBrightness + (1f - effect.minBrightness) * wave
                    scaleBrightness(effect.color, brightness)
                }
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

    /**
     * Compose a complete LED frame from active effects.
     * Pure function: no service dependencies, fully testable.
     *
     * 1. Fill frame with idle color
     * 2. Remove expired effects
     * 3. Sort by priority ascending (higher overwrites lower)
     * 4. Composite each effect onto the frame
     *
     * @return Composed frame; equal priorities preserve caller order
     */
    fun composeFrame(
        idleColor: Color,
        effects: List<ActiveEffect>,
        numLeds: Int,
        now: Long,
        zoneResolver: ((EffectTarget.Zone) -> List<Int>)? = null
    ): Array<Color> {
        val layers = effects.mapIndexedNotNull { index, active ->
            if (active.timeoutMs > 0 && now - active.startTime >= active.timeoutMs) return@mapIndexedNotNull null
            val indices = resolveIndices(active.effect.target, numLeds, zoneResolver)
            // This legacy helper has caller-order ties; policy supplies actual owner/source/time instead.
            CompositionLayer(
                active.effect.name, null, active.effect.priority, 0, index.toString().padStart(10, '0'),
                indices, computeColors(active.effect, now - active.startTime, indices.size, numLeds, idleColor)
            )
        }
        return DecisionComposer.compose(idleColor, numLeds, layers).colors.toTypedArray()
    }

    /**
     * Resolve effect target to LED index list (pure, no service dependencies).
     * Zone targets require device info, supplied by the optional [zoneResolver]
     * (the runtime passes its device-aware resolver). Without it, Zone resolves to empty.
     */
    internal fun resolveIndices(
        target: EffectTarget,
        numLeds: Int,
        zoneResolver: ((EffectTarget.Zone) -> List<Int>)? = null
    ): List<Int> {
        return when (target) {
            is EffectTarget.AllLeds -> (0 until numLeds).toList()
            is EffectTarget.SingleLed -> listOf(target.ledIndex).filter { it in 0 until numLeds }
            is EffectTarget.LedSet -> target.ledIndices.filter { it in 0 until numLeds }
            is EffectTarget.Zone -> (zoneResolver?.invoke(target) ?: emptyList()).filter { it in 0 until numLeds }
        }
    }

    fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        return Color(
            (from.red + (to.red - from.red) * ratio).toInt().coerceIn(0, 255),
            (from.green + (to.green - from.green) * ratio).toInt().coerceIn(0, 255),
            (from.blue + (to.blue - from.blue) * ratio).toInt().coerceIn(0, 255)
        )
    }
}
