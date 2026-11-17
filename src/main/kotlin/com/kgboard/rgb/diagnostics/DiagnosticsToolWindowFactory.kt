package com.kgboard.rgb.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.ui.identified
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.*

internal fun uiDelay(delay: Long, action: () -> Unit): Cancellation {
    val timer = Timer(delay.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()) { action() }
    timer.isRepeats = false
    timer.start()
    return Cancellation { timer.stop() }
}

class DiagnosticsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiagnosticsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class DiagnosticsPanel(project: Project?, showSettings: Boolean = true) : Disposable {
    val component: JPanel
    private val presenter: DiagnosticsPresenter
    private val visibility: HierarchyListener

    init {
        val service = LightingRuntimeService.getInstance()
        val root = JPanel(BorderLayout()).identified("diagnostics.panel", message("diagnostics.open"))
        val connection = JBLabel().identified("diagnostics.connection", message("diagnostics.connection"))
        val tabs = JBTabbedPane().identified("diagnostics.sections", message("diagnostics.open"))
        fun area(id: String, key: String): JBTextArea {
            val text = JBTextArea().identified(id, message(key))
            text.isEditable = false; text.lineWrap = true; text.wrapStyleWord = true
            text.border = JBUI.Borders.empty(8)
            tabs.addTab(message(key), JBScrollPane(text))
            return text
        }

        val devices = area("diagnostics.devices", "diagnostics.devices")
        val rules = area("diagnostics.rules", "diagnostics.signals")
        val analysis = area("diagnostics.analysis", "diagnostics.analysis")
        val history = area("diagnostics.history", "diagnostics.history")
        val capabilities = area("diagnostics.capabilities", "diagnostics.capabilities")
        val actions = JPanel(GridLayout(0, 2, JBUI.scale(4), JBUI.scale(4)))
        fun button(id: String, key: String, action: () -> Unit) =
            JButton(message(key)).identified(id, message(key)).also {
                it.addActionListener { action() }; actions.add(it)
            }
        button("diagnostics.connect", "connection.connect") { DiagnosticsActions.connect(service.events) }
        button("diagnostics.disconnect", "connection.disconnect") { DiagnosticsActions.disconnect(service.events) }
        if (showSettings) button("diagnostics.settings", "action.open") { DiagnosticsActions.settings(project) }
        button("diagnostics.copy", "diagnostics.copy") {
            val snapshot = service.snapshot()
            val versions = mapOf(
                "plugin" to com.kgboard.rgb.BuildInfo.version,
                "ide" to ApplicationInfo.getInstance().build.asString()
            )
            CopyPasteManager.getInstance().setContents(
                StringSelection(
                    DiagnosticsExport.copyText(
                        snapshot.diagnostics,
                        versions,
                        snapshot.projects.values.flatMap { it.capabilities.values }.map { it.exportCode() }
                    )
                )
            )
        }
        button("diagnostics.clear", "diagnostics.clear") { service.events.dispatch(RuntimeEvent.ClearHistory) }
        val header =
            JPanel(BorderLayout()).apply { add(connection, BorderLayout.NORTH); add(actions, BorderLayout.CENTER) }
        root.add(header, BorderLayout.NORTH); root.add(tabs, BorderLayout.CENTER)
        presenter = DiagnosticsPresenter(service::subscribe, ::uiDelay) { view ->
            connection.text = view.connectionLabel
            devices.text = view.deviceRows.joinToString("\n\n")
            rules.text = view.ruleRows.joinToString("\n\n")
            analysis.text = view.analysisRows.joinToString("\n")
            history.text = view.errorRows.joinToString("\n")
            capabilities.text = (view.capabilityRows + message(
                if (service.nativeResumeAvailable)
                    "diagnostics.resume.native" else "diagnostics.resume.fallback"
            )).joinToString("\n")
        }
        // Hiding a Tool Window keeps content alive: subscribe only while it is actually showing.
        visibility = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L)
                if (root.isShowing) presenter.start() else presenter.close()
        }
        root.addHierarchyListener(visibility)
        component = root
        if (root.isShowing) presenter.start()
    }

    override fun dispose() {
        component.removeHierarchyListener(visibility)
        presenter.close()
    }
}
