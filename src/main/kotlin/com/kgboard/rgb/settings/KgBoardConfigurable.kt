package com.kgboard.rgb.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.SettingsScope
import com.kgboard.rgb.settings.ui.*
import java.awt.BorderLayout
import javax.swing.JPanel

/** Application-only drafts. All Swing resources live between createPanel and disposeUIResources. */
class KgBoardConfigurable : BoundConfigurable("KGBoard RGB") {
    private data class Ui(
        val draft: AppDraft, val preview: SettingsPreviewSession,
        val header: ConnectionHeader, val devices: DeviceSettingsPanel, val scenarios: ScenarioEditor,
        val groups: GroupSettingsPanel, val pomodoro: PomodoroSettingsPanel, val advanced: AdvancedSettingsPanel
    )

    private var ui: Ui? = null
    private var subscription: Cancellation? = null
    private var lifetime: Any? = null
    private var baseline: KgBoardSettings.State? = null
    private var baselineGroups: KeyGroupService.GroupState? = null

    override fun createPanel(): DialogPanel {
        ApplicationManager.getApplication().assertIsDispatchThread()
        disposeUi()
        val app = LightingRuntimeService.getInstance()
        val draft = AppDraft.capture(KgBoardSettings.getInstance().state)
        draft.groups = KeyGroupService.getInstance().state.copyDeep()
        baseline = RuntimeSettings.capture(draft.toState()).toState()
        baselineGroups = draft.groups.copyDeep()
        val preview = SettingsPreviewSession(app.events)
        val groupDraft = GroupDraft(draft)
        val header = ConnectionHeader(message("scope.application"), draft, preview, app.events)
        var scenarioRef: ScenarioEditor? = null
        lateinit var devices: DeviceSettingsPanel
        devices = DeviceSettingsPanel(draft, preview) {
            header.update(app.snapshot(), devices.choice()); scenarioRef?.updateInventory()
        }
        val scenarios = ScenarioEditor(
            SettingsScope.APP, draft.rules, devices::choice, app::snapshot,
            groupDraft::definitions,
            { id, rule ->
                preview.availability(
                    app.snapshot(),
                    devices.choice(),
                    PreviewContent.Scenario(id, rule),
                    groupDraft.definitions()
                )
            },
            { id, rule ->
                preview.preview(
                    requireNotNull(devices.choice()),
                    PreviewContent.Scenario(id, rule),
                    groupDraft.definitions()
                )
            })
        scenarioRef = scenarios
        val groups = GroupSettingsPanel(groupDraft, scenarios::currentTarget) { deleted ->
            if (deleted != null) scenarios.groupDeleted(deleted)
            scenarios.refreshGroups()
        }
        val pomodoro = PomodoroSettingsPanel(draft)
        val advanced = AdvancedSettingsPanel(draft)
        val navigation = JBTabbedPane().identified("kgboard.navigation", message("nav.scenarios")).apply {
            minimumSize = java.awt.Dimension(0, 0); preferredSize = java.awt.Dimension(0, 0)
        }
        navigation.addTab(message("nav.devices"), settingsScrollPane(devices.createPanel()))
        val scenarioPanel = JPanel(BorderLayout()).apply {
            add(scenarios.createPanel(), BorderLayout.CENTER)
            add(groups.createPanel(), BorderLayout.SOUTH)
        }
        navigation.addTab(message("nav.scenarios"), scenarioPanel)
        navigation.addTab(message("nav.pomodoro"), settingsScrollPane(pomodoro.createPanel()))
        navigation.addTab(message("nav.advanced"), settingsScrollPane(advanced.createPanel()))
        ui = Ui(draft, preview, header, devices, scenarios, groups, pomodoro, advanced)
        val root = panel {
            row { cell(header.createPanel()).align(AlignX.FILL) }
            row { cell(navigation).align(Align.FILL) }.resizableRow()
        }
        val token = Any().also { lifetime = it }
        fun update(state: RuntimeState) {
            devices.update(state); header.update(state, devices.choice()); scenarios.updateInventory()
        }
        update(app.snapshot())
        var lastPresentation: List<Any?>? = null
        subscription = app.subscribe { state ->
            val signature = state.settingsPresentation(preview.id)
            if (signature == lastPresentation) return@subscribe
            lastPresentation = signature
            ApplicationManager.getApplication().invokeLater({
                if (lifetime === token) update(state)
            }, ModalityState.any())
        }
        return root
    }

    private fun flush(view: Ui) {
        view.header.flush(); view.scenarios.flush(); view.pomodoro.flush(); view.advanced.flush()
    }

    override fun apply() {
        val view = ui ?: return
        try {
            flush(view)
            val prepared = view.draft.toState()
            val app = LightingRuntimeService.getInstance()
            val groups = KeyGroupService.definitions(view.draft.groups)
            SettingsTransaction.applyApp(prepared, { saved ->
                KgBoardSettings.getInstance().loadState(saved)
                KeyGroupService.getInstance().loadState(view.draft.groups)
            }, app.events, inventory = app.snapshot().inventory(), groups = groups)
            view.preview.applied()
            baseline = RuntimeSettings.capture(KgBoardSettings.getInstance().state).toState()
            baselineGroups = KeyGroupService.getInstance().state.copyDeep()
            view.draft.reset(requireNotNull(baseline))
            view.draft.groups = requireNotNull(baselineGroups).copyDeep()
            resetControls(view)
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: message("validation.invalid"))
        }
    }

    override fun reset() {
        val view = ui ?: return
        view.preview.clear()
        view.draft.reset(KgBoardSettings.getInstance().state)
        view.draft.groups = KeyGroupService.getInstance().state.copyDeep()
        baseline = RuntimeSettings.capture(view.draft.toState()).toState()
        baselineGroups = view.draft.groups.copyDeep()
        resetControls(view)
    }

    private fun resetControls(view: Ui) {
        view.header.reset(); view.devices.reset(); view.scenarios.reset(); view.groups.reset()
        view.pomodoro.reset(); view.advanced.reset()
    }

    override fun isModified(): Boolean {
        val view = ui ?: return false
        return try {
            flush(view)
            view.draft.toState() != baseline || view.draft.groups != baselineGroups
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    private fun disposeUi() {
        lifetime = null
        subscription?.cancel(); subscription = null
        ui?.preview?.close(); ui = null
    }

    override fun disposeUIResources() {
        disposeUi(); super.disposeUIResources()
    }
}
