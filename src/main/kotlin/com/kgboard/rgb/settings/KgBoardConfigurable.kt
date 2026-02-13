package com.kgboard.rgb.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.kgboard.rgb.client.OpenRgbConnectionService
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class KgBoardConfigurable : BoundConfigurable("KGBoard RGB") {

    // Color panels for native color pickers
    private val buildSuccessColorPanel = colorPanel()
    private val buildFailureColorPanel = colorPanel()
    private val buildProgressColorPanel = colorPanel()
    private val runColorPanel = colorPanel()
    private val debugColorPanel = colorPanel()
    private val stopColorPanel = colorPanel()
    private val testPassColorPanel = colorPanel()
    private val testFailColorPanel = colorPanel()
    private val testRunningColorPanel = colorPanel()
    private val idleColorPanel = colorPanel()

    // Phase 2 color panels
    private val pomodoroWorkColorPanel = colorPanel()
    private val pomodoroBreakColorPanel = colorPanel()
    private val pomodoroTransitionColorPanel = colorPanel()
    private val notifyIndexingColorPanel = colorPanel()
    private val notifyLowMemoryColorPanel = colorPanel()
    private val notifyTodoColorPanel = colorPanel()
    private val fileSaveFlashColorPanel = colorPanel()

    // Device config table model
    private val deviceTableModel = ListTableModel<KgBoardSettings.DeviceConfig>(
        object : ColumnInfo<KgBoardSettings.DeviceConfig, Boolean>("Enabled") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): Boolean = item.enabled
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: Boolean) { item.enabled = value }
            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Name") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.name
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) { item.name = value }
            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor = DefaultCellEditor(JTextField())
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Device Index") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.deviceIndex.toString()
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) {
                item.deviceIndex = value.toIntOrNull() ?: 0
            }
            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor = DefaultCellEditor(JTextField())
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Role") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.role
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) { item.role = value }
            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor {
                val combo = JComboBox(arrayOf("primary", "mirror", "ambient", "indicator"))
                return DefaultCellEditor(combo)
            }
            override fun getRenderer(item: KgBoardSettings.DeviceConfig): TableCellRenderer? = null
        }
    )

    private val statusLabel = JBLabel("Not connected")
    private val statusUpdateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    private fun colorPanel(): ColorPanel = ColorPanel().apply {
        preferredSize = Dimension(90, 25)
    }

    override fun createPanel(): DialogPanel {
        val settings = KgBoardSettings.getInstance()
        val connection = OpenRgbConnectionService.getInstance()

        // Init color panels and device table from settings
        loadColorPanels(settings)
        loadDeviceTable(settings)
        updateStatusLabel(connection)

        return panel {
            // ── General ──
            group("General") {
                row {
                    checkBox("Enable KGBoard RGB integration")
                        .bindSelected(settings::enabled)
                }
            }

            // ── Connection ──
            group("OpenRGB Connection") {
                row("Status:") {
                    cell(statusLabel)
                }
                row {
                    val connectBtn = JButton("Connect").apply {
                        addActionListener {
                            connection.reconnect()
                            statusUpdateAlarm.cancelAllRequests()
                            statusUpdateAlarm.addRequest({ updateStatusLabel(connection) }, 600)
                        }
                    }
                    val disconnectBtn = JButton("Disconnect").apply {
                        addActionListener {
                            connection.disconnect()
                            updateStatusLabel(connection)
                        }
                    }
                    val testBtn = JButton("Test LEDs").apply {
                        addActionListener {
                            connection.setAllLeds(settings.deviceIndex, buildSuccessColorPanel.selectedColor ?: settings.buildSuccessColor)
                        }
                    }
                    cell(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                        add(connectBtn)
                        add(disconnectBtn)
                        add(testBtn)
                    })
                }
                row("Host:") {
                    textField()
                        .columns(15)
                        .bindText(settings::host)
                        .comment("IP address of OpenRGB SDK server")
                }
                row("Port:") {
                    intTextField(1..65535)
                        .columns(6)
                        .bindIntText(settings::port)
                }
                row("Device index:") {
                    intTextField(0..99)
                        .columns(4)
                        .bindIntText(settings::deviceIndex)
                        .comment("0 = first device. Open OpenRGB to see device order.")
                }
                row {
                    checkBox("Auto-reconnect on connection loss")
                        .bindSelected(settings::autoReconnect)
                }
                row {
                    checkBox("Connect automatically on IDE startup")
                        .bindSelected(settings::autoConnect)
                }
            }

            // ── Effects ──
            group("Effect Timing") {
                row("Effect duration:") {
                    intTextField(100..30000)
                        .columns(6)
                        .bindIntText(
                            { settings.effectDurationMs.toInt() },
                            { settings.effectDurationMs = it.toLong() }
                        )
                        .comment("How long temporary effects last (ms)")
                }
                row("Pulse speed:") {
                    intTextField(200..10000)
                        .columns(6)
                        .bindIntText(
                            { settings.pulseSpeedMs.toInt() },
                            { settings.pulseSpeedMs = it.toLong() }
                        )
                        .comment("Breathing animation period (ms)")
                }
            }

            // ── Build Colors ──
            group("Build Events") {
                colorRow("Build success:", buildSuccessColorPanel, "Green flash on successful compilation")
                colorRow("Build failure:", buildFailureColorPanel, "Red static when build has errors")
                colorRow("Build in progress:", buildProgressColorPanel, "Yellow pulse during compilation")
            }

            // ── Execution Colors ──
            group("Execution Events") {
                colorRow("Run:", runColorPanel, "Green static while application is running")
                colorRow("Debug:", debugColorPanel, "Purple pulse in debug mode")
                colorRow("Stop / error:", stopColorPanel, "Orange flash on abnormal termination")
            }

            // ── Test Colors ──
            group("Test Events") {
                colorRow("Tests passed:", testPassColorPanel, "Green flash when all tests pass")
                colorRow("Tests failed:", testFailColorPanel, "Red static when tests fail")
                colorRow("Tests running:", testRunningColorPanel, "Blue pulse while tests execute")
            }

            // ── Idle ──
            group("Idle State") {
                colorRow("Idle color:", idleColorPanel, "Dim background color when nothing is happening")
            }

            // ── Focus & Shutdown ──
            group("Focus & Shutdown") {
                row {
                    checkBox("Dim keyboard when IDE loses focus")
                        .bindSelected(settings::dimOnFocusLoss)
                }
                row("Dim brightness:") {
                    intTextField(0..100)
                        .columns(4)
                        .bindIntText(settings::dimBrightness)
                        .comment("Brightness level when dimmed (0–100%)")
                }
                row {
                    checkBox("Reset LEDs to black on IDE exit")
                        .bindSelected(settings::resetOnExit)
                }
            }

            // ── Multi-Device ──
            group("Multi-Device") {
                row {
                    checkBox("Enable multi-device support")
                        .bindSelected(settings::multiDeviceEnabled)
                }
                row {
                    comment("Configure multiple OpenRGB devices. Each device can have a role:<br/>" +
                            "<b>primary</b> — all effects, <b>ambient</b> — idle color only,<br/>" +
                            "<b>indicator</b> — per-key effects only, <b>mirror</b> — copies primary.")
                }
                row {
                    val table = JBTable(deviceTableModel)
                    val decorator = ToolbarDecorator.createDecorator(table)
                        .setAddAction {
                            deviceTableModel.addRow(KgBoardSettings.DeviceConfig(
                                deviceIndex = deviceTableModel.items.size,
                                name = "Device ${deviceTableModel.items.size}",
                                enabled = true,
                                role = "primary"
                            ))
                        }
                        .setRemoveAction {
                            val selected = table.selectedRow
                            if (selected >= 0) deviceTableModel.removeRow(selected)
                        }
                        .disableUpDownActions()
                    cell(decorator.createPanel())
                        .align(AlignX.FILL)
                }
            }

            // ── Pomodoro ──
            group("Pomodoro Timer") {
                row {
                    checkBox("Enable Pomodoro timer")
                        .bindSelected(settings::pomodoroEnabled)
                }
                row("Work duration:") {
                    intTextField(1..120)
                        .columns(4)
                        .bindIntText(settings::pomodoroWorkMinutes)
                        .comment("Minutes per work session")
                }
                row("Break duration:") {
                    intTextField(1..60)
                        .columns(4)
                        .bindIntText(settings::pomodoroBreakMinutes)
                        .comment("Minutes per short break")
                }
                row("Long break:") {
                    intTextField(1..120)
                        .columns(4)
                        .bindIntText(settings::pomodoroLongBreakMinutes)
                        .comment("Minutes per long break")
                }
                row("Sessions before long break:") {
                    intTextField(1..12)
                        .columns(4)
                        .bindIntText(settings::pomodoroSessionsBeforeLongBreak)
                }
                colorRow("Work color:", pomodoroWorkColorPanel, "Keyboard color during work phase")
                colorRow("Break color:", pomodoroBreakColorPanel, "Keyboard color during break")
                colorRow("Transition:", pomodoroTransitionColorPanel, "Flash color on phase change")
            }

            // ── IDE Notifications ──
            group("IDE Notifications") {
                row {
                    checkBox("Indexing indicator (orange pulse during indexing)")
                        .bindSelected(settings::notifyIndexingEnabled)
                }
                colorRow("Indexing color:", notifyIndexingColorPanel, "Pulse color during IDE indexing")
                row("Indexing LED indices:") {
                    textField()
                        .columns(20)
                        .bindText(settings.state::notifyIndexingLedIndices)
                        .comment("Comma-separated LED indices (e.g., 0,1,2). Empty = all LEDs.")
                }
                row {
                    checkBox("Low memory warning (red flash)")
                        .bindSelected(settings::notifyLowMemoryEnabled)
                }
                colorRow("Low memory color:", notifyLowMemoryColorPanel, "Flash color on low memory")
                row("Low memory LED indices:") {
                    textField()
                        .columns(20)
                        .bindText(settings.state::notifyLowMemoryLedIndices)
                        .comment("Comma-separated LED indices. Empty = all LEDs.")
                }
                row {
                    checkBox("TODO indicator in current file")
                        .bindSelected(settings::notifyTodoEnabled)
                }
                colorRow("TODO color:", notifyTodoColorPanel, "Indicator color for TODO items")
                row("TODO LED indices:") {
                    textField()
                        .columns(20)
                        .bindText(settings.state::notifyTodoLedIndices)
                        .comment("Comma-separated LED indices. Empty = all LEDs.")
                }
            }

            // ── File Save Flash ──
            group("File Save Flash") {
                row {
                    checkBox("Flash on file save (Ctrl+S)")
                        .bindSelected(settings.state::fileSaveFlashEnabled)
                }
                colorRow("Flash color:", fileSaveFlashColorPanel, "Short flash color on file save")
            }
        }
    }

    private fun Panel.colorRow(label: String, colorPanel: ColorPanel, comment: String) {
        row(label) {
            cell(colorPanel)
                .gap(RightGap.SMALL)
            comment(comment)
        }
    }

    override fun apply() {
        super.apply()
        // Save color panels to settings
        val s = KgBoardSettings.getInstance()
        s.state.buildSuccessColor = toHex(buildSuccessColorPanel.selectedColor)
        s.state.buildFailureColor = toHex(buildFailureColorPanel.selectedColor)
        s.state.buildInProgressColor = toHex(buildProgressColorPanel.selectedColor)
        s.state.runColor = toHex(runColorPanel.selectedColor)
        s.state.debugColor = toHex(debugColorPanel.selectedColor)
        s.state.stopColor = toHex(stopColorPanel.selectedColor)
        s.state.testPassColor = toHex(testPassColorPanel.selectedColor)
        s.state.testFailColor = toHex(testFailColorPanel.selectedColor)
        s.state.testRunningColor = toHex(testRunningColorPanel.selectedColor)
        s.state.idleColor = toHex(idleColorPanel.selectedColor)
        // Phase 2
        s.state.pomodoroWorkColor = toHex(pomodoroWorkColorPanel.selectedColor)
        s.state.pomodoroBreakColor = toHex(pomodoroBreakColorPanel.selectedColor)
        s.state.pomodoroTransitionColor = toHex(pomodoroTransitionColorPanel.selectedColor)
        s.state.notifyIndexingColor = toHex(notifyIndexingColorPanel.selectedColor)
        s.state.notifyLowMemoryColor = toHex(notifyLowMemoryColorPanel.selectedColor)
        s.state.notifyTodoColor = toHex(notifyTodoColorPanel.selectedColor)
        s.state.fileSaveFlashColor = toHex(fileSaveFlashColorPanel.selectedColor)
        // Save device configs
        s.state.deviceConfigs = deviceTableModel.items.map {
            KgBoardSettings.DeviceConfig(it.deviceIndex, it.name, it.enabled, it.role)
        }.toMutableList()
    }

    override fun reset() {
        super.reset()
        val s = KgBoardSettings.getInstance()
        loadColorPanels(s)
        loadDeviceTable(s)
        updateStatusLabel(OpenRgbConnectionService.getInstance())
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        val s = KgBoardSettings.getInstance()
        return colorChanged(buildSuccessColorPanel, s.state.buildSuccessColor) ||
                colorChanged(buildFailureColorPanel, s.state.buildFailureColor) ||
                colorChanged(buildProgressColorPanel, s.state.buildInProgressColor) ||
                colorChanged(runColorPanel, s.state.runColor) ||
                colorChanged(debugColorPanel, s.state.debugColor) ||
                colorChanged(stopColorPanel, s.state.stopColor) ||
                colorChanged(testPassColorPanel, s.state.testPassColor) ||
                colorChanged(testFailColorPanel, s.state.testFailColor) ||
                colorChanged(testRunningColorPanel, s.state.testRunningColor) ||
                colorChanged(idleColorPanel, s.state.idleColor) ||
                colorChanged(pomodoroWorkColorPanel, s.state.pomodoroWorkColor) ||
                colorChanged(pomodoroBreakColorPanel, s.state.pomodoroBreakColor) ||
                colorChanged(pomodoroTransitionColorPanel, s.state.pomodoroTransitionColor) ||
                colorChanged(notifyIndexingColorPanel, s.state.notifyIndexingColor) ||
                colorChanged(notifyLowMemoryColorPanel, s.state.notifyLowMemoryColor) ||
                colorChanged(notifyTodoColorPanel, s.state.notifyTodoColor) ||
                colorChanged(fileSaveFlashColorPanel, s.state.fileSaveFlashColor) ||
                deviceConfigsModified(s)
    }

    private fun deviceConfigsModified(s: KgBoardSettings): Boolean {
        val current = deviceTableModel.items
        val saved = s.state.deviceConfigs
        if (current.size != saved.size) return true
        return current.zip(saved).any { (a, b) ->
            a.deviceIndex != b.deviceIndex || a.name != b.name || a.enabled != b.enabled || a.role != b.role
        }
    }

    private fun loadColorPanels(s: KgBoardSettings) {
        buildSuccessColorPanel.selectedColor = s.buildSuccessColor
        buildFailureColorPanel.selectedColor = s.buildFailureColor
        buildProgressColorPanel.selectedColor = s.buildInProgressColor
        runColorPanel.selectedColor = s.runColor
        debugColorPanel.selectedColor = s.debugColor
        stopColorPanel.selectedColor = s.stopColor
        testPassColorPanel.selectedColor = s.testPassColor
        testFailColorPanel.selectedColor = s.testFailColor
        testRunningColorPanel.selectedColor = s.testRunningColor
        idleColorPanel.selectedColor = s.idleColor
        // Phase 2
        pomodoroWorkColorPanel.selectedColor = s.pomodoroWorkColor
        pomodoroBreakColorPanel.selectedColor = s.pomodoroBreakColor
        pomodoroTransitionColorPanel.selectedColor = s.pomodoroTransitionColor
        notifyIndexingColorPanel.selectedColor = s.notifyIndexingColor
        notifyLowMemoryColorPanel.selectedColor = s.notifyLowMemoryColor
        notifyTodoColorPanel.selectedColor = s.notifyTodoColor
        fileSaveFlashColorPanel.selectedColor = s.parseColor(s.state.fileSaveFlashColor)
    }

    private fun loadDeviceTable(s: KgBoardSettings) {
        deviceTableModel.items = s.state.deviceConfigs.map {
            KgBoardSettings.DeviceConfig(it.deviceIndex, it.name, it.enabled, it.role)
        }
    }

    private fun updateStatusLabel(connection: OpenRgbConnectionService) {
        if (connection.isConnected) {
            statusLabel.text = "Connected (${connection.deviceCount} devices)"
            statusLabel.foreground = JBColor(Color(0, 150, 0), Color(80, 200, 80))
        } else {
            statusLabel.text = "Not connected"
            statusLabel.foreground = JBColor(Color(200, 0, 0), Color(255, 80, 80))
        }
    }

    private fun colorChanged(panel: ColorPanel, hex: String): Boolean {
        val panelColor = panel.selectedColor ?: return false
        return toHex(panelColor) != hex
    }

    private fun toHex(color: Color?): String {
        color ?: return "#000000"
        return String.format("#%02X%02X%02X", color.red, color.green, color.blue)
    }
}
