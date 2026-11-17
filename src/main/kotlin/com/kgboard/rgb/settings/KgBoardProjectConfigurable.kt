package com.kgboard.rgb.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.bridge.ProjectRuntimeService
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.SettingsScope
import com.kgboard.rgb.settings.ui.*

/** The project captured by this Configurable never follows the active IDE window. */
class KgBoardProjectConfigurable(private val project: Project) : BoundConfigurable("KGBoard RGB (Project)") {
    private data class Ui(
        val id: ProjectId, val draft: ProjectDraft, val preview: SettingsPreviewSession,
        val header: ConnectionHeader, val device: PreviewDeviceSelector, val scenarios: ScenarioEditor,
        val branches: BranchRulesEditor
    )

    private var ui: Ui? = null
    private var subscription: Cancellation? = null
    private var lifetime: Any? = null
    private var baseline: KgBoardProjectSettings.State? = null

    override fun createPanel(): DialogPanel {
        ApplicationManager.getApplication().assertIsDispatchThread()
        disposeUi()
        val app = LightingRuntimeService.getInstance()
        val service = ProjectRuntimeService.getInstance(project)
        service.start()
        val id = service.binding.id
        val draft = ProjectDraft.capture(KgBoardProjectSettings.getInstance(project).state)
        baseline = ProjectSettingsSnapshot.capture(draft.toState()).toState()
        val preview = SettingsPreviewSession(app.events, scope = SettingsScope.PROJECT, project = id)
        val header = ConnectionHeader(message("scope.project", project.name), null, preview, app.events)
        var scenarioRef: ScenarioEditor? = null
        lateinit var device: PreviewDeviceSelector
        device = PreviewDeviceSelector {
            preview.clear(); header.update(app.snapshot(), device.choice()); scenarioRef?.updateInventory()
        }
        val groups = { app.snapshot().groups }
        val scenarios = ScenarioEditor(
            SettingsScope.PROJECT, draft.rules, device::choice, app::snapshot, groups,
            { ruleId, rule ->
                preview.availability(
                    app.snapshot(),
                    device.choice(),
                    PreviewContent.Scenario(ruleId, rule)
                )
            },
            { ruleId, rule -> preview.preview(requireNotNull(device.choice()), PreviewContent.Scenario(ruleId, rule)) })
        scenarioRef = scenarios
        val branches = BranchRulesEditor(draft) { app.snapshot().projects[id]?.git?.branch }
        val tabs = JBTabbedPane().identified("kgboard.project.navigation", message("nav.project")).apply {
            minimumSize = java.awt.Dimension(0, 0); preferredSize = java.awt.Dimension(0, 0)
        }
        tabs.addTab(message("nav.project"), scenarios.createPanel())
        tabs.addTab(message("branch.rules"), settingsScrollPane(branches.createPanel()))
        ui = Ui(id, draft, preview, header, device, scenarios, branches)
        val root = panel {
            row { cell(header.createPanel()).align(AlignX.FILL) }
            row { cell(device.createPanel()).align(AlignX.FILL) }
            row { cell(tabs).align(Align.FILL) }.resizableRow()
        }
        val token = Any().also { lifetime = it }
        fun update(state: RuntimeState) {
            device.update(state); header.update(
                state,
                device.choice()
            ); scenarios.updateInventory(); scenarios.refreshGroups()
        }
        update(app.snapshot())
        var lastPresentation: List<Any?>? = null
        subscription = app.subscribe { state ->
            val signature = state.settingsPresentation(preview.id, id)
            if (signature == lastPresentation) return@subscribe
            lastPresentation = signature
            ApplicationManager.getApplication().invokeLater({
                if (lifetime === token && !project.isDisposed) update(state)
            }, ModalityState.any())
        }
        return root
    }

    override fun apply() {
        val view = ui ?: return
        try {
            view.scenarios.flush(); view.branches.flush()
            val prepared = view.draft.toState()
            SettingsTransaction.applyProject(
                view.id, prepared, KgBoardProjectSettings.getInstance(project)::loadState,
                LightingRuntimeService.getInstance().events
            )
            view.preview.applied()
            baseline = ProjectSettingsSnapshot.capture(KgBoardProjectSettings.getInstance(project).state).toState()
            view.draft.reset(requireNotNull(baseline)); view.scenarios.reset(); view.branches.reset()
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message ?: message("validation.invalid"))
        }
    }

    override fun reset() {
        val view = ui ?: return
        view.preview.clear()
        view.draft.reset(KgBoardProjectSettings.getInstance(project).state)
        baseline = ProjectSettingsSnapshot.capture(view.draft.toState()).toState()
        view.scenarios.reset(); view.branches.reset()
    }

    override fun isModified(): Boolean {
        val view = ui ?: return false
        if (view.branches.hasPendingEdit()) return true
        return try {
            view.scenarios.flush(); view.branches.flush()
            view.draft.toState() != baseline
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
