package com.kgboard.rgb.scenario

import com.kgboard.rgb.effect.*
import java.awt.Color

/** One style-to-effect factory shared by ordinary rendering and draft preview. */
object ScenarioEffects {
    fun create(ruleId: String, style: RuleStyle, target: EffectTarget, progress: Float = 0f): RgbEffect {
        val descriptor = requireNotNull(ScenarioCatalog.byId(ruleId)) { "Unknown scenario rule" }
        require(style.durationMs >= 0 && style.periodMs > 0) { "Invalid scenario duration or period" }
        val priority = descriptor.priority
        return when (style.effect) {
            EffectStyle.STATIC -> StaticEffect(style.color, ruleId, priority, target)
            EffectStyle.PULSE -> PulseEffect(style.color, periodMs = style.periodMs, name = ruleId, priority = priority, target = target)
            EffectStyle.FLASH -> FlashEffect(style.color, style.durationMs, ruleId, priority, target)
            EffectStyle.PROGRESS -> ProgressEffect(style.color, progress = progress.coerceIn(0f,1f), name = ruleId, priority = priority, target = target)
            EffectStyle.GRADIENT -> GradientEffect(style.color, Color(0xFFD600), style.durationMs, ruleId, priority, target)
            EffectStyle.RAINBOW -> RainbowEffect(style.periodMs, ruleId, priority, target)
            EffectStyle.WAVE -> WaveEffect(style.color, style.periodMs, name = ruleId, priority = priority, target = target)
        }
    }
}
