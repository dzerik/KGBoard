package com.kgboard.rgb.settings.ui

import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.settings.AppDraft
import javax.swing.JComponent
import kotlin.reflect.KMutableProperty0

class PomodoroSettingsPanel(private val draft: AppDraft) {
    private var enabled: JBCheckBox? = null
    private val numbers = linkedMapOf<KMutableProperty0<Int>, JBTextField>()
    fun createPanel(): JComponent = panel {
        enabled = JBCheckBox(message("pomodoro.enabled"), draft.state.pomodoroEnabled)
            .identified("pomodoro.enabled", message("pomodoro.enabled"))
        row { cell(enabled!!) }
        for ((key, property) in listOf(
            "pomodoro.work" to draft.state::pomodoroWorkMinutes,
            "pomodoro.break" to draft.state::pomodoroBreakMinutes,
            "pomodoro.longBreak" to draft.state::pomodoroLongBreakMinutes,
            "pomodoro.sessions" to draft.state::pomodoroSessionsBeforeLongBreak
        )) {
            val input = JBTextField(property.get().toString()).identified(key, message(key))
            numbers[property] = input
            row(message(key)) { cell(input).columns(7) }
        }
    }

    fun flush() {
        val values = numbers.map { (property, input) ->
            property to requireNotNull(input.text.trim().toIntOrNull()?.takeIf { it > 0 }) {
                message("validation.number", input.accessibleContext.accessibleName, 1, Int.MAX_VALUE)
            }
        }
        values.forEach { (property, value) -> property.set(value) }
        draft.state.pomodoroEnabled = enabled?.isSelected ?: draft.state.pomodoroEnabled
    }

    fun reset() {
        enabled?.isSelected = draft.state.pomodoroEnabled
        numbers.forEach { (property, input) -> input.text = property.get().toString() }
    }
}
