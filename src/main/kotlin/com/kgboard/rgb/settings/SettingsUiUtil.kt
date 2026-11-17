package com.kgboard.rgb.settings

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColorPanel
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.preset.KeyGroupService
import java.awt.Color
import java.awt.Dimension
import javax.swing.JTextField

/**
 * Shared helpers for the app-level and project-level settings UIs.
 * Previously duplicated verbatim across both Configurables.
 */
object SettingsUiUtil {

    fun colorPanel(): ColorPanel = com.kgboard.rgb.settings.ui.AccessibleColorPanel()

    /** Parse a #RRGGBB hex string, falling back to black on malformed input. */
    fun parseColor(hex: String): Color = try {
        Color.decode(hex)
    } catch (_: NumberFormatException) {
        Color.BLACK
    }

    fun toHex(color: Color?): String {
        color ?: return "#000000"
        return String.format("#%02X%02X%02X", color.red, color.green, color.blue)
    }

    /** True if the panel's color differs from the stored [hex]. Null selection = unchanged. */
    fun colorChanged(panel: ColorPanel, hex: String): Boolean {
        val panelColor = panel.selectedColor ?: return false
        return toHex(panelColor) != hex
    }

    /** Validate a comma-separated LED-index string. Returns an error message, or null if valid. */
    fun validateLedIndices(raw: String): String? {
        return try {
            SettingsValidation.indices(raw); null
        } catch (error: IllegalArgumentException) {
            error.message
        }

    }

    /**
     * Shows a chooser of available key groups; appends the chosen group's LED indices to [textField].
     * Performs a lazy layout/group refresh if groups are empty but a connection is active.
     */
    fun showKeyGroupPopup(textField: JTextField, deviceIndex: Int? = null) {
        val groupService = KeyGroupService.getInstance()
        val state = LightingRuntimeService.getInstance().snapshot()
        val device =
            (state.connection as? ConnectionState.Ready)?.devices?.getOrNull(deviceIndex ?: state.settings.deviceIndex)
        val groups = if (device == null) emptyMap() else groupService.groupsForDevice(device)

        if (groups.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No key groups available. Connect to OpenRGB first.")
                .showInFocusCenter()
            return
        }

        val items = groups.entries.map { (id, indices) ->
            val displayName = groupService.getDisplayName(id)
            "$displayName (${indices.size} LEDs)" to indices.joinToString(",")
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items.map { it.first })
            .setTitle("Select Key Group")
            .setItemChosenCallback { selected ->
                val pair = items.find { it.first == selected }
                if (pair != null) {
                    val current = textField.text.trim()
                    textField.text = if (current.isEmpty()) pair.second
                    else "$current,${pair.second}"
                }
            }
            .createPopup()
            .showInFocusCenter()
    }
}
