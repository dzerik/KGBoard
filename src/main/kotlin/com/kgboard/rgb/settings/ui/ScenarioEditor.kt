package com.kgboard.rgb.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.device.DeviceChoice
import com.kgboard.rgb.runtime.RuntimeState
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.settings.*
import com.kgboard.rgb.target.*
import java.awt.BorderLayout
import java.awt.Color
import java.math.BigDecimal
import javax.swing.*

/** One catalogue-driven editor for both scopes; incomplete text stays in its state's draft. */
class ScenarioEditor(
    private val scope: SettingsScope,
    private val rules: MutableMap<String, RuleAppearance>,
    private val selectedDevice: () -> DeviceChoice?,
    private val snapshot: () -> RuntimeState,
    private val groups: () -> Map<String, GroupDefinition>,
    private val availability: (String, RuleAppearance) -> PreviewAvailability,
    private val onPreview: (String, RuleAppearance) -> Unit
) {
    private data class Edit(
        val enabled: Boolean, val target: TargetSpec, val color: Color,
        val effect: EffectStyle, val duration: String, val period: String
    )

    private val pending = mutableMapOf<String, Edit>()
    private val dirty = mutableSetOf<String>()
    private var selectedId: String? = null
    private var changing = false
    private var list: JBList<ScenarioDescriptor>? = null
    private var enable: JBCheckBox? = null
    private var color: ColorPanel? = null
    private var effect: JComboBox<EffectStyle>? = null
    private var duration: JBTextField? = null
    private var period: JBTextField? = null
    private var target: LedTargetEditor? = null
    private var previewButton: JButton? = null
    private var reason: JBLabel? = null
    private var unsupported: Row? = null
    private var durationRow: Row? = null
    private var periodRow: Row? = null

    fun createPanel(): JComponent {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val descriptors = ScenarioCatalog.rules.filter { it.scope == scope }
        val scenarios = JBList(descriptors).identified("kgboard.scenarios", message("scenarios.list"))
        list = scenarios
        scenarios.selectionMode = ListSelectionModel.SINGLE_SELECTION
        scenarios.cellRenderer = DefaultListCellRenderer().let { renderer ->
            ListCellRenderer { component, value, index, selected, focus ->
                renderer.getListCellRendererComponent(
                    component,
                    value?.let { message(it.labelKey) },
                    index,
                    selected,
                    focus
                )
            }
        }
        val enabled =
            JBCheckBox(message("scenario.enabled")).identified("scenario.enabled", message("scenario.enabled"))
        enable = enabled
        val picker = SettingsUiUtil.colorPanel().identified("scenario.color", message("scenario.color")); color = picker
        val styles = JComboBox(EffectStyle.entries.toTypedArray()).identified(
            "scenario.effect",
            message("scenario.effect")
        ); effect = styles
        styles.renderer = DefaultListCellRenderer().let { renderer ->
            ListCellRenderer { component, value, index, selected, focus ->
                renderer.getListCellRendererComponent(
                    component,
                    value?.let { message("effect.${it.name}") },
                    index,
                    selected,
                    focus
                )
            }
        }
        val durationInput = JBTextField().identified("scenario.duration", message("scenario.duration")); duration =
            durationInput
        val periodInput = JBTextField().identified("scenario.period", message("scenario.period")); period = periodInput
        val goals = LedTargetEditor(selectedDevice, snapshot, groups) { markEdited() }; target = goals
        val feedback = JBLabel().identified("scenario.preview.reason", message("preview.start")); reason = feedback
        val preview =
            JButton(message("preview.start")).identified("scenario.preview", message("preview.start")); previewButton =
            preview
        preview.addActionListener {
            try {
                savePending()
                val id = selectedId ?: return@addActionListener
                val appearance = appearance(id, pending.getValue(id))
                val result = availability(id, appearance)
                if (result.allowed) onPreview(id, appearance) else showFeedback(unavailable(result))
            } catch (error: IllegalArgumentException) {
                showFeedback(error.message.orEmpty())
            }
        }
        val detail = panel {
            unsupported = row { comment(message("scenario.unavailable")) }.visible(false)
            row { cell(enabled) }
            row(message("scenario.color")) { cell(picker) }
            row(message("scenario.effect")) { cell(styles).align(AlignX.FILL) }
            durationRow = row(message("scenario.duration")) { cell(durationInput).columns(8) }
            periodRow = row(message("scenario.period")) { cell(periodInput).columns(8) }
            row { cell(goals.createPanel()).align(AlignX.FILL) }
        }
        val actions = panel {
            row { cell(preview); cell(feedback).align(AlignX.FILL) }.layout(RowLayout.INDEPENDENT)
        }
        scenarios.addListSelectionListener {
            if (!it.valueIsAdjusting && !changing) {
                savePending()
                selectedId = scenarios.selectedValue?.id
                loadSelected()
            }
        }
        listOf(durationInput, periodInput).forEach { it.onEdit { markEdited() } }
        styles.addActionListener { markEdited() }
        enabled.addActionListener { markEdited() }
        picker.addActionListener { markEdited() }
        val right = JPanel(BorderLayout()).apply {
            add(settingsScrollPane(detail), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(scenarios), right).apply {
            border = null; minimumSize = java.awt.Dimension(0, 0); resizeWeight = .32; isContinuousLayout = true
            scenarios.selectedIndex = 0
        }
    }

    private fun savePending() {
        if (changing) return
        selectedId?.let { id ->
            pending[id] = Edit(
                enable!!.isSelected, target!!.value(), color!!.selectedColor ?: Color.BLACK,
                effect!!.selectedItem as EffectStyle, duration!!.text, period!!.text
            )
        }
    }

    private fun loadSelected() {
        val id = selectedId ?: return
        val descriptor = ScenarioCatalog.byId(id)!!
        unsupported?.visible(id !in rules)
        val rule = rules[id] ?: descriptor.default.copy(enabled = false, target = TargetSpec.Unassigned)
        val edit = pending[id] ?: Edit(
            rule.enabled, rule.target, rule.style.color, rule.style.effect,
            seconds(rule.style.durationMs), seconds(rule.style.periodMs)
        )
        changing = true
        try {
            enable!!.isSelected = edit.enabled
            color!!.selectedColor = edit.color
            effect!!.selectedItem = edit.effect
            duration!!.text = edit.duration; period!!.text = edit.period
            target!!.reset(edit.target)
        } finally {
            changing = false
        }
        updateTimingRows()
        updateAvailability()
    }

    private fun appearance(id: String, edit: Edit): RuleAppearance {
        fun millis(text: String, positive: Boolean): Long {
            return requireNotNull(milliseconds(text, positive)) { message("preview.invalidTiming") }
        }
        return RuleAppearance(
            edit.enabled, edit.target, RuleStyle(
                edit.color, edit.effect,
                millis(edit.duration, false), millis(edit.period, true)
            )
        )
    }

    private fun milliseconds(text: String, positive: Boolean): Long? =
        runCatching { BigDecimal(text.trim()).multiply(BigDecimal(1000)).longValueExact() }.getOrNull()
            ?.takeIf { it >= if (positive) 1 else 0 }

    fun flush() {
        savePending()
        val prepared = pending.filterKeys { it in dirty }.mapValues { (id, edit) -> appearance(id, edit) }
        rules.putAll(prepared)
    }

    fun currentTarget(): TargetSpec = target?.value() ?: TargetSpec.Unassigned
    fun groupDeleted(id: String) {
        pending.replaceAll { _, edit -> if (edit.target == TargetSpec.Group(id)) edit.copy(target = TargetSpec.Unassigned) else edit }
        target?.let { if (it.value() == TargetSpec.Group(id)) it.reset(TargetSpec.Unassigned) }
        updateAvailability()
    }

    fun reset() {
        pending.clear(); dirty.clear(); loadSelected()
    }

    fun updateInventory() {
        target?.updateInventory(); updateAvailability()
    }

    fun refreshGroups() {
        target?.let { it.reset(it.value()) }; updateAvailability()
    }

    private fun markEdited() {
        if (!changing) {
            selectedId?.let(dirty::add); updateTimingRows(); updateAvailability()
        }
    }

    private fun updateAvailability() {
        if (changing || previewButton == null) return
        val id = selectedId ?: return
        val result = runCatching {
            savePending(); availability(id, appearance(id, pending.getValue(id)))
        }.getOrElse { PreviewAvailability(false, "preview.invalidTiming") }
        previewButton!!.isEnabled = result.allowed
        showFeedback(if (result.allowed) "" else unavailable(result))
    }

    private fun updateTimingRows() {
        val style = effect?.selectedItem as? EffectStyle ?: return
        val id = selectedId.orEmpty()
        val transient = id.substringAfterLast('.') in setOf("success", "failure", "cancelled", "notExecuted") ||
            id in setOf("fileSave", "lowMemory", "pomodoro.transition")
        durationRow?.visible(
            transient || style in setOf(EffectStyle.FLASH, EffectStyle.GRADIENT) ||
            duration?.text?.let { milliseconds(it, false) == null } != false)
        periodRow?.visible(
            style in setOf(EffectStyle.PULSE, EffectStyle.RAINBOW, EffectStyle.WAVE) ||
            period?.text?.let { milliseconds(it, true) == null } != false)
    }

    private fun showFeedback(text: String) {
        reason?.text =
            if (text.isEmpty()) "" else "<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</html>"
        previewButton?.toolTipText = text
    }

    private fun unavailable(result: PreviewAvailability) =
        result.reasonKey?.let { runCatching { message(it) }.getOrDefault(message("preview.selectTarget")) }.orEmpty()

    private fun seconds(milliseconds: Long) = BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString()
}
