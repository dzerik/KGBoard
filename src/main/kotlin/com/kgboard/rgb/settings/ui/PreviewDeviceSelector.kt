package com.kgboard.rgb.settings.ui

import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.device.*
import com.kgboard.rgb.runtime.*
import javax.swing.*

/** Project preview selects a device without editing application assignments. */
class PreviewDeviceSelector(private val changed: () -> Unit) {
    private data class Choice(val value: DeviceChoice, val label: String) {
        override fun toString() = label
    }

    private var input: JComboBox<Choice>? = null
    private var token: InventoryToken? = null
    private var loading = false
    fun createPanel(): JComponent {
        val combo = JComboBox<Choice>().identified("device.selection", message("device.select")).also { input = it }
        combo.addActionListener { if (!loading) changed() }
        return panel { row(message("device.select")) { cell(combo).align(AlignX.FILL) } }
    }

    fun choice(): DeviceChoice? = (input?.selectedItem as? Choice)?.value
    fun update(state: RuntimeState) {
        val inventory = state.inventory()
        if (token == inventory?.token && (input?.itemCount ?: 0) > 0) return
        val old = choice()
        val values = inventory?.devices?.mapIndexed { index, device ->
            Choice(
                DeviceChoice(inventory.token, index, LayoutFingerprint.of(device)),
                "${device.name} (${device.numLeds} LED)"
            )
        }.orEmpty()
        loading = true
        try {
            input?.model = DefaultComboBoxModel(values.toTypedArray())
            input?.selectedItem = values.firstOrNull { it.value == old }
                ?: state.primaryDevice()?.let { primary -> values.firstOrNull { it.value.index == primary.index } }
            token = inventory?.token
        } finally {
            loading = false
        }
        changed()
    }
}
