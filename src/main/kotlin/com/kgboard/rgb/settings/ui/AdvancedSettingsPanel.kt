package com.kgboard.rgb.settings.ui

import com.intellij.ui.ColorPanel
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.settings.*
import java.awt.Color
import javax.swing.JComponent
import kotlin.reflect.KMutableProperty0

class AdvancedSettingsPanel(private val draft: AppDraft) {
    private val switches = linkedMapOf<KMutableProperty0<Boolean>, JBCheckBox>()
    private var brightness: JBTextField? = null
    private var idle: ColorPanel? = null
    fun createPanel(): JComponent = panel {
        for ((key, property) in listOf(
            "advanced.autoConnect" to draft.state::autoConnect,
            "advanced.autoReconnect" to draft.state::autoReconnect,
            "advanced.reset" to draft.state::resetOnExit,
            "advanced.dim" to draft.state::dimOnFocusLoss
        )) {
            val input = JBCheckBox(message(key), property.get()).identified(key, message(key))
            switches[property] = input
            row { cell(input) }
        }
        val value = JBTextField(draft.state.dimBrightness.toString())
            .identified("advanced.brightness", message("advanced.brightness")).also { brightness = it }
        row(message("advanced.brightness")) { cell(value).columns(7) }
        val color = SettingsUiUtil.colorPanel().identified("advanced.idle", message("advanced.idle"))
        color.selectedColor = Color.decode(draft.state.idleColor); idle = color
        row(message("advanced.idle")) { cell(color) }
        collapsibleGroup(message("advanced.compatibility")) {
            val skip = JBCheckBox(message("advanced.skipMode"), draft.state.skipCustomMode)
                .identified("advanced.skipMode", message("advanced.skipMode"))
            switches[draft.state::skipCustomMode] = skip
            row { cell(skip) }
            row { comment(message("advanced.skipModeHelp")) }
            row { comment(message("effect.compatibility")) }
        }
    }

    fun flush() {
        val value = requireNotNull(brightness!!.text.trim().toIntOrNull()?.takeIf { it in 0..100 }) {
            message("validation.number", message("advanced.brightness"), 0, 100)
        }
        switches.forEach { (property, input) -> property.set(input.isSelected) }
        draft.state.dimBrightness = value
        draft.state.idleColor = "#%06X".format((idle!!.selectedColor ?: Color.BLACK).rgb and 0xFFFFFF)
    }

    fun reset() {
        switches.forEach { (property, input) -> input.isSelected = property.get() }
        brightness?.text = draft.state.dimBrightness.toString()
        idle?.selectedColor = Color.decode(draft.state.idleColor)
    }
}
