package com.kgboard.rgb.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.device.*
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.*
import java.util.UUID
import javax.swing.*

/** Choices belong to the inventory that rendered them; no index is a persisted identity. */
class DeviceSettingsPanel(
    private val draft: AppDraft,
    private val preview: SettingsPreviewSession,
    private val onSelectionChanged: () -> Unit
) {
    private data class Choice(val value: DeviceChoice, val label: String) {
        override fun toString() = label
    }

    private data class Assignment(val id: String, val value: ConfiguredBinding?, val label: String) {
        override fun toString() = label
    }

    private var state = RuntimeState()
    private var choices: JComboBox<Choice>? = null
    private var assignments: JBList<Assignment>? = null
    private var roles: JComboBox<String>? = null
    private var enabled: JBCheckBox? = null
    private var hint: JBLabel? = null
    private var test: JButton? = null
    private var loading = false
    private var renderedToken: InventoryToken? = null

    fun choice(): DeviceChoice? = (choices?.selectedItem as? Choice)?.value

    fun createPanel(): JComponent {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val devices = JComboBox<Choice>().identified("device.selection", message("device.select")).also { choices = it }
        devices.addActionListener {
            if (!loading) {
                preview.clear()
                if (draft.bindings.isEmpty() && choice() != null) assign(null)
                updateAvailability(); onSelectionChanged()
            }
        }
        val list = JBList<Assignment>().identified("device.assignments", message("device.assignments"))
            .also { assignments = it }
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val role = JComboBox(arrayOf("primary", "mirror", "indicator", "ambient"))
            .identified("device.role", message("device.role")).also { roles = it }
        role.renderer = DefaultListCellRenderer().let { renderer ->
            ListCellRenderer { component, value, index, selected, focus ->
                renderer.getListCellRendererComponent(
                    component,
                    value?.let { message("role.$it") },
                    index,
                    selected,
                    focus
                )
            }
        }
        val roleHelp = JBLabel(message("role.help.primary"))
        role.addActionListener { roleHelp.text = message("role.help.${role.selectedItem}") }
        val toggle = JBCheckBox(message("device.enabled")).identified("device.enabled", message("device.enabled"))
            .also { enabled = it }
        toggle.addActionListener {
            if (!loading) list.selectedValue?.value?.let { selected ->
                val index = draft.bindings.indexOfFirst { it.binding.id == selected.binding.id }
                if (index >= 0) {
                    draft.bindings[index] =
                        selected.copy(enabled = toggle.isSelected); refreshAssignments(selected.binding.id)
                }
            }
        }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !loading) {
                loading = true
                try {
                    val selected = list.selectedValue?.value
                    toggle.isSelected = selected?.enabled ?: false
                    if (selected != null) role.selectedItem = selected.role
                } finally {
                    loading = false
                }
            }
        }
        val feedback = JBLabel().identified("device.status", message("device.select")).also { hint = it }
        val identify =
            JButton(message("device.test")).identified("device.test", message("device.test")).also { test = it }
        identify.addActionListener {
            val selected = choice() ?: return@addActionListener
            val availability = preview.availability(state, selected, PreviewContent.DeviceTest)
            if (availability.allowed) preview.preview(selected, PreviewContent.DeviceTest)
            else feedback.text = message(availability.reasonKey ?: "preview.selectDevice")
        }
        return panel {
            row(message("device.select")) { cell(devices).align(AlignX.FILL); cell(identify) }
            row { comment(message("device.testHelp")) }
            row { cell(feedback).align(AlignX.FILL) }
            row(message("device.role")) { cell(role).align(AlignX.FILL) }
            row { cell(roleHelp).align(AlignX.FILL) }
            row {
                button(message("device.add")) { assign(null) }
                button(message("device.rebind")) { list.selectedValue?.id?.let(::assign) }
            }
            row { comment(message("device.assignmentHelp")) }
            group(message("device.assignments")) {
                row { scrollCell(list).align(Align.FILL) }.resizableRow()
                row {
                    cell(toggle)
                    button(message("device.remove")) {
                        list.selectedValue?.let { selected ->
                            draft.bindings.removeAll { it.binding.id == selected.id }
                            draft.state.bindingRecords.removeAll { it.id == selected.id }
                            preview.clear(); refreshAssignments(); onSelectionChanged()
                        }
                    }.applyToComponent { identified("device.remove", message("device.remove")) }
                }
            }
        }.also { update(state) }
    }

    private fun assign(replace: String?) {
        try {
            val selected = choice() ?: throw IllegalArgumentException(message("preview.selectDevice"))
            val inventory = state.inventory() ?: throw IllegalArgumentException(message("preview.notConnected"))
            val id = replace?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: UUID.randomUUID().toString()
            val binding = DeviceResolver.bind(id, selected, inventory, state.settings.untrustedSerials)
            require(
                DeviceResolver.resolve(
                    binding,
                    inventory,
                    state.settings.untrustedSerials
                ) is DeviceResolution.Resolved
            ) {
                message("preview.ambiguousDevice")
            }
            require(draft.bindings.none { configured ->
                configured.enabled && configured.binding.id != id &&
                    (DeviceResolver.resolve(
                        configured.binding,
                        inventory,
                        state.settings.untrustedSerials
                    ) as? DeviceResolution.Resolved)?.index == selected.index
            }) { message("device.duplicate") }
            val configured = ConfiguredBinding(binding, true, roles!!.selectedItem as String)
            if (replace != null) draft.state.bindingRecords.removeAll { it.id == replace }
            val index = draft.bindings.indexOfFirst { it.binding.id == id }
            if (index < 0) draft.bindings.add(configured) else draft.bindings[index] = configured
            refreshAssignments(id)
            hint!!.text = identity(binding)
            onSelectionChanged()
        } catch (error: IllegalArgumentException) {
            hint?.text = error.message
        }
    }

    fun update(snapshot: RuntimeState) {
        state = snapshot
        val combo = choices ?: return
        val inventory = state.inventory()
        if (inventory?.token != renderedToken || combo.itemCount == 0 && inventory != null) {
            val old = choice()
            val retained = if (old?.token == inventory?.token) old else null
            loading = true
            try {
                val values = inventory?.devices?.mapIndexed { index, device ->
                    Choice(
                        DeviceChoice(inventory.token, index, LayoutFingerprint.of(device)),
                        "${device.name} (${device.numLeds} LED)"
                    )
                }.orEmpty()
                combo.model = DefaultComboBoxModel(values.toTypedArray())
                val saved = inventory?.let { current ->
                    draft.bindings.firstNotNullOfOrNull { configured ->
                        if (!configured.enabled || configured.role != "primary") null
                        else (DeviceResolver.resolve(
                            configured.binding,
                            current,
                            state.settings.untrustedSerials
                        ) as? DeviceResolution.Resolved)?.index
                    }
                }
                combo.selectedItem = values.firstOrNull { it.value == retained }
                    ?: values.firstOrNull { saved != null && it.value.index == saved }
                renderedToken = inventory?.token
            } finally {
                loading = false
            }
            preview.clear(); onSelectionChanged()
        }
        refreshAssignments()
        updateAvailability()
    }

    fun reset() {
        refreshAssignments(); update(state)
    }

    private fun refreshAssignments(selectedId: String? = assignments?.selectedValue?.id) {
        val list = assignments ?: return
        loading = true
        try {
            val inventory = state.inventory()
            val values = draft.bindings.map { configured ->
                val status =
                    inventory?.let { DeviceResolver.resolve(configured.binding, it, state.settings.untrustedSerials) }
                val device = (status as? DeviceResolution.Resolved)?.index?.let { inventory?.devices?.getOrNull(it) }
                val text = when (status) {
                    is DeviceResolution.Resolved -> identity(configured.binding)
                    DeviceResolution.Ambiguous -> message("device.ambiguous")
                    DeviceResolution.ConfirmationRequired -> message("device.unconfirmed")
                    else -> message("device.missing")
                }
                val role = configured.role.takeIf { it in setOf("primary", "mirror", "indicator", "ambient") }
                    ?.let { message("role.$it") } ?: configured.role
                Assignment(configured.binding.id, configured, "$role: ${device?.name ?: text} — $text")
            } + draft.state.bindingRecords.filter { stored -> draft.bindings.none { it.binding.id == stored.id } }
                .distinctBy { it.id }.map { stored ->
                    Assignment(stored.id, null, message("device.inactive", stored.name.ifBlank { stored.id }))
                }
            list.setListData(values.toTypedArray())
            list.setSelectedValue(values.firstOrNull { it.id == selectedId }, true)
            enabled?.isSelected = list.selectedValue?.value?.enabled ?: false
        } finally {
            loading = false
        }
    }

    private fun identity(binding: DeviceBinding) = message(
        when (binding.selector) {
            is DeviceSelector.Serial -> "device.serial"
            is DeviceSelector.Port -> "device.port"
            is DeviceSelector.SessionOnly -> "device.session"
        }
    )

    private fun updateAvailability() {
        val result = preview.availability(state, choice(), PreviewContent.DeviceTest)
        test?.isEnabled = result.allowed
        hint?.text = if (result.allowed) "" else message(result.reasonKey ?: "preview.selectDevice")
    }
}
