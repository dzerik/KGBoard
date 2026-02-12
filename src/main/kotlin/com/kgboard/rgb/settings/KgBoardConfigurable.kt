package com.kgboard.rgb.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.client.OpenRgbConnectionService
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

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

    private val statusLabel = JBLabel("Not connected")

    private fun colorPanel(): ColorPanel = ColorPanel().apply {
        preferredSize = Dimension(45, 25)
    }

    override fun createPanel(): DialogPanel {
        val settings = KgBoardSettings.getInstance()
        val connection = OpenRgbConnectionService.getInstance()

        // Init color panels from settings
        loadColorPanels(settings)
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
                            SwingUtilities.invokeLater {
                                Thread.sleep(600)
                                updateStatusLabel(connection)
                            }
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
    }

    override fun reset() {
        super.reset()
        loadColorPanels(KgBoardSettings.getInstance())
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
                colorChanged(idleColorPanel, s.state.idleColor)
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
