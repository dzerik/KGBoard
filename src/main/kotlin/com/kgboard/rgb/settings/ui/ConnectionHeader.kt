package com.kgboard.rgb.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.device.DeviceChoice
import com.kgboard.rgb.device.LayoutFingerprint
import com.kgboard.rgb.settings.*
import javax.swing.*

class ConnectionHeader(
    private val scopeLabel: String,
    private val draft: AppDraft?,
    private val preview: SettingsPreviewSession,
    private val events: RuntimeEvents
) {
    private var state = RuntimeState()
    private var status: JBLabel? = null
    private var deviceName: JBLabel? = null
    private var previewRow: Row? = null
    private var previewStatus: JBLabel? = null
    private var enabled: JBCheckBox? = null
    private var host: JBTextField? = null
    private var port: JBTextField? = null

    fun createPanel(): JComponent {
        ApplicationManager.getApplication().assertIsDispatchThread()
        status = JBLabel().identified("connection.status", message("connection.disconnected"))
        deviceName = JBLabel().identified("connection.device", message("device.select"))
        previewStatus = JBLabel().identified("connection.preview", message("preview.active"))
        return panel {
            row { label(scopeLabel).bold(); cell(deviceName!!).align(AlignX.FILL) }.layout(RowLayout.INDEPENDENT)
            row {
                cell(status!!).align(AlignX.FILL)
                button(message("connection.connect")) {
                    try {
                        flush()
                        com.kgboard.rgb.diagnostics.DiagnosticsActions.connect(
                            events,
                            preview,
                            draft?.state?.let { Endpoint(it.host, it.port) } ?: state.endpoint)
                    } catch (error: IllegalArgumentException) {
                        status!!.text = error.message
                    }
                }.applyToComponent { identified("connection.connect", message("connection.connect")) }
                button(message("connection.disconnect")) {
                    com.kgboard.rgb.diagnostics.DiagnosticsActions.disconnect(
                        events
                    )
                }
                    .applyToComponent { identified("connection.disconnect", message("connection.disconnect")) }
            }
            row {
                if (draft != null) {
                    enabled = JBCheckBox(message("integration.enabled"), draft.state.enabled)
                        .identified("app.enabled", message("integration.enabled"))
                    cell(enabled!!)
                }
                button(message("diagnostics.open")) {
                    status?.let { com.kgboard.rgb.diagnostics.DiagnosticsActions.openFrom(it) }
                }.applyToComponent { identified("connection.diagnostics", message("diagnostics.open")) }
            }
            previewRow = row { cell(previewStatus!!); button(message("preview.stop")) { preview.clear() } }
                .layout(RowLayout.INDEPENDENT)
            if (draft != null) collapsibleGroup(message("connection.server")) {
                host = JBTextField(draft.state.host).identified("app.host", message("connection.host"))
                port = JBTextField(draft.state.port.toString()).identified("app.port", message("connection.port"))
                row(message("connection.host")) { cell(host!!).align(AlignX.FILL) }
                row(message("connection.port")) { cell(port!!).columns(7) }
                row { comment(message("connection.help")) }
            }
        }.also { update(state) }
    }

    fun flush() {
        val state = draft?.state ?: return
        val address = host?.text?.trim() ?: state.host
        val number =
            port?.text?.trim()?.toIntOrNull() ?: throw IllegalArgumentException(message("connection.invalidPort"))
        require(address.isNotBlank()) { message("connection.invalidHost") }
        require(number in 1..65535) { message("connection.invalidPort") }
        state.host = address; state.port = number; state.enabled = enabled?.isSelected ?: state.enabled
    }

    fun reset() {
        draft?.state?.let { state ->
            host?.text = state.host; port?.text = state.port.toString(); enabled?.isSelected = state.enabled
        }
    }

    fun update(snapshot: RuntimeState, choice: DeviceChoice? = null) {
        state = snapshot
        val inventory = snapshot.inventory()
        val device = choice?.let { captured ->
            inventory?.devices?.getOrNull(captured.index)?.takeIf {
                captured.token == inventory.token && captured.fingerprint == LayoutFingerprint.of(it)
            }
        }
        deviceName?.text = device?.let { message("connection.device", it.name, it.numLeds) } ?: message("device.select")
        deviceName?.toolTipText = deviceName?.text
        val connectionText = message(
            when (snapshot.connection) {
                is ConnectionState.Ready -> "connection.ready"
                is ConnectionState.Connecting -> "connection.connecting"
                is ConnectionState.Backoff -> "connection.retrying"
                ConnectionState.Disconnected -> "connection.disconnected"
            }
        )
        status?.text =
            if (snapshot.settings.enabled) connectionText else message("integration.disabled") + ": " + connectionText
        previewRow?.visible(snapshot.preview?.owner == preview.id)
        previewStatus?.text = if (snapshot.preview != null) message("preview.active") else ""
    }
}
