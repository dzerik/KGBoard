package com.kgboard.rgb.bridge

import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.*
import com.intellij.ui.awt.RelativePoint
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.diagnostics.*
import com.kgboard.rgb.runtime.*
import java.awt.Component
import java.awt.event.MouseEvent

class KgBoardStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "KGBoardStatus"
    override fun getDisplayName() = "KGBoard RGB"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = KgBoardStatusWidget(project)
    class KgBoardStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
        private var statusBar: StatusBar? = null
        private var presenter: DiagnosticsPresenter? = null
        private var connection = message("diagnostics.status.disconnected")
        private var tooltip = "KGBoard"
        override fun ID() = "KGBoardStatus"
        override fun install(statusBar: StatusBar) {
            this.statusBar = statusBar
            presenter = DiagnosticsPresenter(LightingRuntimeService.getInstance()::subscribe, ::uiDelay) { view ->
                connection = view.connectionLabel
                tooltip =
                    (listOf(view.connectionLabel) + view.deviceRows.take(1) + view.analysisRows.take(1)).joinToString("; ")
                this.statusBar?.updateWidget(ID())
            }.also { it.start() }
        }

        override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
        override fun getText(): String = "RGB $connection"
        override fun getTooltipText(): String = tooltip
        override fun getAlignment() = Component.CENTER_ALIGNMENT
        override fun getClickConsumer() = com.intellij.util.Consumer<MouseEvent> { event ->
            DiagnosticsActions.popup(project, DataManager.getInstance().getDataContext(event.component))
                .show(RelativePoint(event))
        }

        override fun dispose() {
            presenter?.close(); presenter = null; statusBar = null
        }
    }
}
