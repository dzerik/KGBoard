package com.kgboard.rgb.diagnostics

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.KgBoardConfigurable
import com.kgboard.rgb.settings.SettingsPreviewSession
import com.intellij.openapi.options.ShowSettingsUtil
import java.awt.Component

/** Same explicit connection commands in Settings, Tool Window and the status popup. */
object DiagnosticsActions {
    fun connect(events: RuntimeEvents, preview: SettingsPreviewSession? = null, endpoint: Endpoint? = null) {
        if (preview != null && endpoint != null) preview.connect(endpoint)
        else events.dispatch(RuntimeEvent.ConnectRequested(endpoint))
    }

    fun disconnect(events: RuntimeEvents) = events.dispatch(RuntimeEvent.DisconnectRequested)
    fun open(project: Project?) {
        val target = project ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        if (target == null) showDialog(null)
        else ToolWindowManager.getInstance(target).getToolWindow("KGBoard")?.activate(null)
    }

    fun openFrom(component: Component) {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(component))
            ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        showDialog(project)
    }

    private fun showDialog(project: Project?) {
        // Settings is modal: show the same diagnostics inside its native child dialog.
        object : com.intellij.openapi.ui.DialogWrapper(project, true) {
            private val panel = DiagnosticsPanel(project, showSettings = false)

            init {
                title = message("diagnostics.open")
                panel.component.preferredSize = com.intellij.util.ui.JBUI.size(760, 460)
                com.intellij.openapi.util.Disposer.register(disposable, panel)
                init()
            }

            override fun createCenterPanel(): javax.swing.JComponent = panel.component
            override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)
        }.show()
    }

    fun settings(project: Project?) =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, KgBoardConfigurable::class.java)

    fun popup(project: Project?, context: DataContext): com.intellij.openapi.ui.popup.ListPopup {
        val service = LightingRuntimeService.getInstance()
        val state = service.snapshot()
        val view = DiagnosticsView.from(state)
        val group = DefaultActionGroup()
        fun info(text: String) = group.add(object : DumbAwareAction(text) {
            override fun actionPerformed(e: AnActionEvent) {}
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = false
            }
        })
        info(view.connectionLabel)
        view.deviceRows.firstOrNull()?.let(::info)
        view.ruleRows.firstOrNull()?.let(::info)
        group.addSeparator()
        group.add(object : DumbAwareAction(message("connection.connect")) {
            override fun actionPerformed(e: AnActionEvent) = connect(service.events)
        })
        group.add(object : DumbAwareAction(message("connection.disconnect")) {
            override fun actionPerformed(e: AnActionEvent) {
                disconnect(service.events)
            }
        })
        group.add(object : DumbAwareAction(message("action.open")) {
            override fun actionPerformed(e: AnActionEvent) {
                settings(project)
            }
        })
        group.add(object : DumbAwareAction(message("diagnostics.open")) {
            override fun actionPerformed(e: AnActionEvent) {
                open(project)
            }
        })
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "KGBoard", group, context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
        ).also {
            it.content.name = "diagnostics.popup"
            it.content.accessibleContext.accessibleName = message("diagnostics.open")
        }
    }
}

class OpenDiagnosticsAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) = DiagnosticsActions.open(event.project)
}

class OpenConnectionPopupAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        DiagnosticsActions.popup(event.project, event.dataContext).showInBestPositionFor(event.dataContext)
    }
}
