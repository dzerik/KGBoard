package com.kgboard.rgb.scenario

import com.kgboard.rgb.target.TargetSpec
import java.awt.Color

enum class SettingsScope { APP, PROJECT }
enum class BrightnessChannel { PERSISTENT, NOTIFICATION }
enum class EffectStyle { STATIC, PULSE, FLASH, PROGRESS, GRADIENT, RAINBOW, WAVE }
data class RuleStyle(val color: Color, val effect: EffectStyle, val durationMs: Long, val periodMs: Long)
data class RuleAppearance(val enabled: Boolean, val target: TargetSpec, val style: RuleStyle)
data class ScenarioDescriptor(val id: String, val group: String, val scope: SettingsScope,
    val labelKey: String, val channel: BrightnessChannel, val priority: Int, val default: RuleAppearance)
