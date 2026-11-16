package com.kgboard.rgb.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.device.LayoutFingerprint
import com.kgboard.rgb.target.TargetSpec
import com.intellij.openapi.options.ConfigurationException
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class KgBoardConfigurable : BoundConfigurable("KGBoard RGB") {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(KgBoardConfigurable::class.java)

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
    private val debugBreakpointColorPanel = colorPanel()

    private var deviceTable: JBTable? = null

    private class DeviceIndexEditor : DefaultCellEditor(JTextField()) {
        override fun stopCellEditing(): Boolean {
            val field = component as JTextField
            val valid = field.text.toIntOrNull()?.let { it >= 0 } == true
            if (!valid) {
                field.toolTipText = "Device index must be a non-negative integer"; return false
            }
            return super.stopCellEditing()
        }
    }

    // Device config table model
    private val deviceTableModel = ListTableModel<KgBoardSettings.DeviceConfig>(
        object : ColumnInfo<KgBoardSettings.DeviceConfig, Boolean>("Enabled") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): Boolean = item.enabled
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: Boolean) {
                item.enabled = value
            }

            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Name") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.name
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) {
                item.name = value
            }

            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor =
                DefaultCellEditor(JTextField())
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Device Index") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.deviceIndex.toString()
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) {
                item.deviceIndex = value.toInt()
            }

            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor = DeviceIndexEditor()
        },
        object : ColumnInfo<KgBoardSettings.DeviceConfig, String>("Role") {
            override fun valueOf(item: KgBoardSettings.DeviceConfig): String = item.role
            override fun setValue(item: KgBoardSettings.DeviceConfig, value: String) {
                item.role = value
            }

            override fun isCellEditable(item: KgBoardSettings.DeviceConfig): Boolean = true
            override fun getEditor(item: KgBoardSettings.DeviceConfig): TableCellEditor {
                val combo = JComboBox(arrayOf("primary", "mirror", "ambient", "indicator"))
                return DefaultCellEditor(combo)
            }

            override fun getRenderer(item: KgBoardSettings.DeviceConfig): TableCellRenderer? = null
        }
    )

    private val statusLabel = JBLabel("Not connected")

    // UI-scoped disposables/alarms — (re)created in createPanel, torn down in disposeUIResources.
    private var uiDisposable: Disposable? = null
    private var subscription: Cancellation? = null
    private var preview: SettingsPreviewSession? = null
    private val draft = KgBoardSettings()
    private var legacyBaseline: KgBoardSettings.State? = null
    private val ledFields = mutableMapOf<String, JTextField>()
    private var hostField: JTextField? = null
    private var portField: JTextField? = null

    private data class DeviceChoice(
        val index: Int, val label: String,
        val rendered: com.kgboard.rgb.device.DeviceChoice? = null
    ) {
        override fun toString() = label
    }

    private val deviceChoice = JComboBox<DeviceChoice>()
    private fun renderedChoices(state: RuntimeState): List<DeviceChoice> {
        val inventory = state.inventory() ?: return emptyList()
        return inventory.devices.mapIndexed { index, device ->
            DeviceChoice(
                index, "$index: ${device.name} (${device.numLeds} LEDs)",
                com.kgboard.rgb.device.DeviceChoice(inventory.token, index, LayoutFingerprint.of(device))
            )
        }
    }

    private fun refreshDevices(state: RuntimeState, requested: Int? = null) {
        val previous = deviceChoice.selectedItem as? DeviceChoice
        val choices = renderedChoices(state).toMutableList()
        val retained = previous?.rendered?.let { old ->
            choices.firstOrNull {
                it.rendered == old && (requested == null || requested == it.index)
            }
        }
        val selected = retained ?: DeviceChoice(
            requested ?: previous?.index ?: draft.deviceIndex,
            "Select a device to test — selection expires after reconnect"
        )
        if (retained == null) choices.add(selected)
        deviceChoice.model = DefaultComboBoxModel(choices.toTypedArray())
        deviceChoice.selectedItem = selected
    }

    /** Tab panels that need apply/reset/isModified delegation */
    private val tabPanels = mutableListOf<DialogPanel>()

    private fun colorPanel(): ColorPanel = SettingsUiUtil.colorPanel()

    override fun createPanel(): DialogPanel {
        val settings = draft
        XmlSerializerUtil.copyBean(RuntimeSettings.capture(KgBoardSettings.getInstance().state).toState(), draft.state)
        legacyBaseline = RuntimeSettings.capture(draft.state).toState()
        val connection = LightingRuntimeService.getInstance()

        // (Re)create UI-scoped resources and subscribe the status label to connection events,
        // so it reflects the real state instead of guessing with a fixed delay.
        disposeUi()
        val disp = Disposer.newDisposable("KgBoardConfigurable UI")
        uiDisposable = disp
        preview = SettingsPreviewSession(connection.events)
        var lastPresentation: Pair<String, List<DeviceChoice>>? = null
        subscription = connection.subscribe { state ->
            val choices = renderedChoices(state)
            val view = connectionStatus(state) to choices
            if (view == lastPresentation) return@subscribe
            lastPresentation = view
            ApplicationManager.getApplication().invokeLater({
                if (uiDisposable === disp) {
                    updateStatusLabel(state); refreshDevices(state)
                }
            }, ModalityState.any())
        }
        ledFields.clear()
        deviceChoice.actionListeners.forEach { deviceChoice.removeActionListener(it) }
        deviceChoice.addActionListener {
            (deviceChoice.selectedItem as? DeviceChoice)?.let {
                if (draft.deviceIndex != it.index) {
                    draft.deviceIndex = it.index; preview?.clear()
                }
            }
        }
        refreshDevices(connection.snapshot(), draft.deviceIndex)

        // Init color panels and device table from settings
        loadColorPanels(settings)
        loadDeviceTable(settings)
        updateStatusLabel(connection.snapshot())

        tabPanels.clear()

        val tabbedPane = JBTabbedPane()

        // Tab 1: Connection
        val connectionPanel = panel {
            group("General") {
                row {
                    checkBox("Enable KGBoard RGB integration")
                        .bindSelected(settings::enabled)
                }
            }
            group("OpenRGB Connection") {
                row("Status:") {
                    cell(statusLabel)
                }
                row {
                    val connectBtn = JButton("Connect").apply {
                        addActionListener {
                            val host = hostField?.text.orEmpty().trim()
                            val port = portField?.text?.toIntOrNull()
                            if (host.isBlank() || port == null || port !in 1..65535) {
                                JBPopupFactory.getInstance().createMessage("Enter a valid host and port")
                                    .showInFocusCenter()
                            } else preview?.connect(Endpoint(host, port))
                        }
                    }
                    val disconnectBtn = JButton("Disconnect").apply {
                        addActionListener {
                            connection.events.dispatch(RuntimeEvent.DisconnectRequested)
                            updateStatusLabel(connection.snapshot())
                        }
                    }
                    val testBtn = JButton("Test LEDs").apply {
                        addActionListener {
                            testColor(idleColorPanel)
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
                        .validationOnInput { if (it.text.isBlank()) error("Host must not be empty") else null }
                        .comment("IP address of OpenRGB SDK server")
                        .applyToComponent { hostField = this; name = "app.host" }
                }
                row("Port:") {
                    intTextField(1..65535)
                        .columns(6)
                        .bindIntText(settings::port)
                        .applyToComponent { portField = this }
                }
                row("Device:") {
                    cell(deviceChoice).comment("Unavailable devices cannot be tested; saved indices are preserved.")
                }
                row {
                    checkBox("Auto-reconnect on connection loss")
                        .bindSelected(settings::autoReconnect)
                }
                row {
                    checkBox("Connect automatically on IDE startup")
                        .bindSelected(settings::autoConnect)
                }
                row {
                    checkBox("Skip setCustomMode before LED updates")
                        .bindSelected(settings::skipCustomMode)
                        .comment("Enable for EVision and other keyboards that break on setCustomMode")
                }
            }
        }
        tabPanels.add(connectionPanel)
        tabbedPane.addTab("Connection", connectionPanel)

        // Tab 2: Effects
        val effectsPanel = panel {
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
            group("Idle State") {
                colorRow("Idle color:", idleColorPanel, "Dim background color when nothing is happening")
            }
            group("Focus & Shutdown") {
                row {
                    checkBox("Dim keyboard when IDE loses focus")
                        .bindSelected(settings::dimOnFocusLoss)
                }
                row("Dim brightness:") {
                    intTextField(0..100)
                        .columns(4)
                        .bindIntText(settings::dimBrightness)
                        .comment("Brightness level when dimmed (0-100%)")
                }
                row {
                    checkBox("Reset LEDs to black on IDE exit")
                        .bindSelected(settings::resetOnExit)
                }
            }
        }
        tabPanels.add(effectsPanel)
        tabbedPane.addTab("Effects", effectsPanel)

        // Tab 3: Events
        val eventsPanel = panel {
            group("Build Events") {
                colorRow(
                    "Build success:",
                    buildSuccessColorPanel,
                    "Green flash on successful compilation"
                ) { ledFields["buildLedIndices"]?.text ?: settings.state.buildLedIndices }
                colorRow(
                    "Build failure:",
                    buildFailureColorPanel,
                    "Red static when build has errors"
                ) { ledFields["buildLedIndices"]?.text ?: settings.state.buildLedIndices }
                colorRow(
                    "Build in progress:",
                    buildProgressColorPanel,
                    "Yellow pulse during compilation"
                ) { ledFields["buildLedIndices"]?.text ?: settings.state.buildLedIndices }
                ledIndicesRow("Build LEDs:", settings.state::buildLedIndices)
            }
            group("Execution Events") {
                colorRow(
                    "Run:",
                    runColorPanel,
                    "Green static while application is running"
                ) { ledFields["executionLedIndices"]?.text ?: settings.state.executionLedIndices }
                colorRow(
                    "Debug:",
                    debugColorPanel,
                    "Purple pulse in debug mode"
                ) { ledFields["executionLedIndices"]?.text ?: settings.state.executionLedIndices }
                colorRow(
                    "Stop / error:",
                    stopColorPanel,
                    "Orange flash on abnormal termination"
                ) { ledFields["executionLedIndices"]?.text ?: settings.state.executionLedIndices }
                ledIndicesRow("Execution LEDs:", settings.state::executionLedIndices)
            }
            group("Test Events") {
                colorRow(
                    "Tests passed:",
                    testPassColorPanel,
                    "Green flash when all tests pass"
                ) { ledFields["testLedIndices"]?.text ?: settings.state.testLedIndices }
                colorRow(
                    "Tests failed:",
                    testFailColorPanel,
                    "Red static when tests fail"
                ) { ledFields["testLedIndices"]?.text ?: settings.state.testLedIndices }
                colorRow(
                    "Tests running:",
                    testRunningColorPanel,
                    "Blue pulse while tests execute"
                ) { ledFields["testLedIndices"]?.text ?: settings.state.testLedIndices }
                ledIndicesRow("Test LEDs:", settings.state::testLedIndices)
            }
        }
        tabPanels.add(eventsPanel)
        tabbedPane.addTab("Events", eventsPanel)

        // Tab 4: Features
        val featuresPanel = panel {
            group("Pomodoro Timer") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Enable Pomodoro timer")
                        .bindSelected(settings::pomodoroEnabled)
                }
                row("Work duration:") {
                    intTextField(1..120)
                        .columns(4)
                        .bindIntText(settings::pomodoroWorkMinutes)
                        .comment("Minutes per work session")
                }.enabledIf(enable.selected)
                row("Break duration:") {
                    intTextField(1..60)
                        .columns(4)
                        .bindIntText(settings::pomodoroBreakMinutes)
                        .comment("Minutes per short break")
                }.enabledIf(enable.selected)
                row("Long break:") {
                    intTextField(1..120)
                        .columns(4)
                        .bindIntText(settings::pomodoroLongBreakMinutes)
                        .comment("Minutes per long break")
                }.enabledIf(enable.selected)
                row("Sessions before long break:") {
                    intTextField(1..12)
                        .columns(4)
                        .bindIntText(settings::pomodoroSessionsBeforeLongBreak)
                }.enabledIf(enable.selected)
                colorRow(
                    "Work color:",
                    pomodoroWorkColorPanel,
                    "Keyboard color during work phase"
                ) {
                    ledFields["pomodoroLedIndices"]?.text ?: settings.state.pomodoroLedIndices
                }.enabledIf(enable.selected)
                colorRow(
                    "Break color:",
                    pomodoroBreakColorPanel,
                    "Keyboard color during break"
                ) {
                    ledFields["pomodoroLedIndices"]?.text ?: settings.state.pomodoroLedIndices
                }.enabledIf(enable.selected)
                colorRow(
                    "Transition:",
                    pomodoroTransitionColorPanel,
                    "Flash color on phase change"
                ) {
                    ledFields["pomodoroLedIndices"]?.text ?: settings.state.pomodoroLedIndices
                }.enabledIf(enable.selected)
                ledIndicesRow("Pomodoro LEDs:", settings.state::pomodoroLedIndices).enabledIf(enable.selected)
            }
            group("File Save Flash") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Flash on file save (Ctrl+S)")
                        .bindSelected(settings.state::fileSaveFlashEnabled)
                }
                colorRow(
                    "Flash color:",
                    fileSaveFlashColorPanel,
                    "Short flash color on file save"
                ) {
                    ledFields["fileSaveLedIndices"]?.text ?: settings.state.fileSaveLedIndices
                }.enabledIf(enable.selected)
                ledIndicesRow("File Save LEDs:", settings.state::fileSaveLedIndices).enabledIf(enable.selected)
            }
            group("Debugger Breakpoint") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Flash on breakpoint hit")
                        .bindSelected(settings.state::debugBreakpointFlashEnabled)
                }
                colorRow(
                    "Flash color:",
                    debugBreakpointColorPanel,
                    "Flash color when debugger pauses at breakpoint"
                ) {
                    ledFields["debugBreakpointLedIndices"]?.text ?: settings.state.debugBreakpointLedIndices
                }.enabledIf(enable.selected)
                ledIndicesRow("Debugger LEDs:", settings.state::debugBreakpointLedIndices).enabledIf(enable.selected)
            }
        }
        tabPanels.add(featuresPanel)
        tabbedPane.addTab("Features", featuresPanel)

        // Tab 5: Notifications
        val notificationsPanel = panel {
            group("Indexing") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Indexing indicator (orange pulse during indexing)")
                        .bindSelected(settings::notifyIndexingEnabled)
                }
                colorRow(
                    "Indexing color:",
                    notifyIndexingColorPanel,
                    "Pulse color during IDE indexing"
                ) { ledFields["notifyIndexingLedIndices"]?.text ?: settings.state.notifyIndexingLedIndices }.enabledIf(
                    enable.selected
                )
                ledIndicesRow("Indexing LEDs:", settings.state::notifyIndexingLedIndices).enabledIf(enable.selected)
            }
            group("Low Memory") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Low memory warning (red flash)")
                        .bindSelected(settings::notifyLowMemoryEnabled)
                }
                colorRow(
                    "Low memory color:",
                    notifyLowMemoryColorPanel,
                    "Flash color on low memory"
                ) {
                    ledFields["notifyLowMemoryLedIndices"]?.text ?: settings.state.notifyLowMemoryLedIndices
                }.enabledIf(enable.selected)
                ledIndicesRow("Low memory LEDs:", settings.state::notifyLowMemoryLedIndices).enabledIf(enable.selected)
            }
            group("TODO Indicator") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("TODO indicator in current file")
                        .bindSelected(settings::notifyTodoEnabled)
                }
                colorRow(
                    "TODO color:",
                    notifyTodoColorPanel,
                    "Indicator color for TODO items"
                ) {
                    ledFields["notifyTodoLedIndices"]?.text ?: settings.state.notifyTodoLedIndices
                }.enabledIf(enable.selected)
                ledIndicesRow("TODO LEDs:", settings.state::notifyTodoLedIndices).enabledIf(enable.selected)
            }
        }
        tabPanels.add(notificationsPanel)
        tabbedPane.addTab("Notifications", notificationsPanel)

        // Tab 6: Devices
        val devicesPanel = panel {
            group("Multi-Device") {
                lateinit var enable: Cell<JBCheckBox>
                row {
                    enable = checkBox("Enable multi-device support")
                        .bindSelected(settings::multiDeviceEnabled)
                }
                row {
                    comment(
                        "Configure multiple OpenRGB devices. Each device can have a role:<br/>" +
                            "<b>primary</b> — all effects, <b>ambient</b> — idle color only,<br/>" +
                            "<b>indicator</b> — per-key effects only, <b>mirror</b> — copies primary."
                    )
                }
                row {
                    val table = JBTable(deviceTableModel).also { deviceTable = it }
                    val decorator = ToolbarDecorator.createDecorator(table)
                        .setAddAction {
                            deviceTableModel.addRow(
                                KgBoardSettings.DeviceConfig(
                                    deviceIndex = deviceTableModel.items.size,
                                    name = "Device ${deviceTableModel.items.size}",
                                    enabled = true,
                                    role = "primary"
                                )
                            )
                        }
                        .setRemoveAction {
                            val selected = table.selectedRow
                            if (selected >= 0) deviceTableModel.removeRow(selected)
                        }
                        .disableUpDownActions()
                    cell(decorator.createPanel())
                        .align(AlignX.FILL)
                }.enabledIf(enable.selected)
            }
        }
        tabPanels.add(devicesPanel)
        tabbedPane.addTab("Devices", devicesPanel)

        // Wrap in a DialogPanel so BoundConfigurable works
        return panel {
            row {
                cell(tabbedPane)
                    .align(AlignX.FILL)
                    .align(AlignY.FILL)
            }.resizableRow()
        }
    }

    private fun Panel.ledIndicesRow(label: String, bindProperty: kotlin.reflect.KMutableProperty0<String>): Row {
        return row(label) {
            val tf = textField()
                .columns(20)
                .bindText(bindProperty)
                .applyToComponent { name = "app.${bindProperty.name}" }
                .validationOnInput { SettingsUiUtil.validateLedIndices(it.text)?.let { msg -> error(msg) } }
                .comment("Comma-separated LED indices. Empty = all LEDs.")
                .component
            ledFields[bindProperty.name] = tf
            button("Key Groups...") { SettingsUiUtil.showKeyGroupPopup(tf, draft.deviceIndex) }
        }
    }

    private fun testColor(colorPanel: ColorPanel, ledIndicesRaw: String = "") {
        val session = preview ?: return
        val choice = (deviceChoice.selectedItem as? DeviceChoice)?.rendered
        val color = colorPanel.selectedColor ?: return
        if (ledIndicesRaw.isNotBlank()) {
            JBPopupFactory.getInstance().createMessage(
                "Confirm the LED layout in the scenario editor before testing raw indices."
            ).showInFocusCenter()
            return
        }
        val content = PreviewContent.ColorSample(color, TargetSpec.All)
        val availability = session.availability(LightingRuntimeService.getInstance().snapshot(), choice, content)
        if (!availability.allowed) {
            JBPopupFactory.getInstance().createMessage(availability.reasonKey ?: "Preview unavailable")
                .showInFocusCenter()
            return
        }
        session.preview(requireNotNull(choice), content)
    }

    private fun Panel.colorRow(
        label: String,
        colorPanel: ColorPanel,
        comment: String,
        ledIndicesGetter: () -> String = { "" }
    ): Row {
        return row(label) {
            cell(colorPanel).gap(RightGap.SMALL)
            button("Test") { testColor(colorPanel, ledIndicesGetter()) }.gap(RightGap.SMALL)
            comment(comment)
        }
    }

    override fun apply() {
        if (deviceTable?.isEditing == true && deviceTable?.cellEditor?.stopCellEditing() == false)
            throw ConfigurationException("Invalid device index")
        super.apply()
        tabPanels.forEach { it.apply() }
        // Save color panels to settings
        val s = draft
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
        s.state.pomodoroWorkColor = toHex(pomodoroWorkColorPanel.selectedColor)
        s.state.pomodoroBreakColor = toHex(pomodoroBreakColorPanel.selectedColor)
        s.state.pomodoroTransitionColor = toHex(pomodoroTransitionColorPanel.selectedColor)
        s.state.notifyIndexingColor = toHex(notifyIndexingColorPanel.selectedColor)
        s.state.notifyLowMemoryColor = toHex(notifyLowMemoryColorPanel.selectedColor)
        s.state.notifyTodoColor = toHex(notifyTodoColorPanel.selectedColor)
        s.state.fileSaveFlashColor = toHex(fileSaveFlashColorPanel.selectedColor)
        s.state.debugBreakpointFlashColor = toHex(debugBreakpointColorPanel.selectedColor)
        // Save device configs
        s.state.deviceConfigs = deviceTableModel.items.map {
            KgBoardSettings.DeviceConfig(it.deviceIndex, it.name, it.enabled, it.role)
        }.toMutableList()
        try {
            val app = LightingRuntimeService.getInstance()
            val numLeds =
                (app.snapshot().connection as? ConnectionState.Ready)?.devices?.getOrNull(draft.deviceIndex)?.numLeds
            SettingsValidation.legacyApp(RuntimeSettings.capture(draft.state), numLeds)
            val prepared = LegacyEditorBridge.app(draft.state, legacyBaseline ?: draft.state)
            SettingsTransaction.applyApp(
                prepared,
                KgBoardSettings.getInstance()::loadState,
                app.events,
                numLeds,
                app.snapshot().inventory()
            )
            XmlSerializerUtil.copyBean(prepared, draft.state)
            legacyBaseline = RuntimeSettings.capture(prepared).toState()
            preview?.applied()
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: "Invalid settings")
        }
    }

    override fun reset() {
        XmlSerializerUtil.copyBean(RuntimeSettings.capture(KgBoardSettings.getInstance().state).toState(), draft.state)
        legacyBaseline = RuntimeSettings.capture(draft.state).toState()
        super.reset()
        tabPanels.forEach { it.reset() }
        val s = KgBoardSettings.getInstance()
        loadColorPanels(s)
        loadDeviceTable(s)
        updateStatusLabel(LightingRuntimeService.getInstance().snapshot())
        refreshDevices(LightingRuntimeService.getInstance().snapshot(), draft.deviceIndex)
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        if (tabPanels.any { it.isModified() }) return true
        val s = KgBoardSettings.getInstance()
        return draft.deviceIndex != s.deviceIndex || colorChanged(buildSuccessColorPanel, s.state.buildSuccessColor) ||
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
            colorChanged(debugBreakpointColorPanel, s.state.debugBreakpointFlashColor) ||
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
        pomodoroWorkColorPanel.selectedColor = s.pomodoroWorkColor
        pomodoroBreakColorPanel.selectedColor = s.pomodoroBreakColor
        pomodoroTransitionColorPanel.selectedColor = s.pomodoroTransitionColor
        notifyIndexingColorPanel.selectedColor = s.notifyIndexingColor
        notifyLowMemoryColorPanel.selectedColor = s.notifyLowMemoryColor
        notifyTodoColorPanel.selectedColor = s.notifyTodoColor
        fileSaveFlashColorPanel.selectedColor = s.parseColor(s.state.fileSaveFlashColor)
        debugBreakpointColorPanel.selectedColor = s.parseColor(s.state.debugBreakpointFlashColor)
    }

    private fun loadDeviceTable(s: KgBoardSettings) {
        deviceTableModel.items = s.state.deviceConfigs.map {
            KgBoardSettings.DeviceConfig(it.deviceIndex, it.name, it.enabled, it.role)
        }
    }

    private fun updateStatusLabel(state: RuntimeState) {
        statusLabel.text = connectionStatus(state)
        statusLabel.foreground = JBColor.foreground()
    }

    private fun colorChanged(panel: ColorPanel, hex: String): Boolean = SettingsUiUtil.colorChanged(panel, hex)

    private fun toHex(color: Color?): String = SettingsUiUtil.toHex(color)

    override fun disposeUIResources() {
        disposeUi()
        super.disposeUIResources()
    }

    private fun disposeUi() {
        subscription?.cancel()
        subscription = null
        preview?.close()
        preview = null
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
    }
}
