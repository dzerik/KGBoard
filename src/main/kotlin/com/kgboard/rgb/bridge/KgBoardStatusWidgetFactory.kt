package com.kgboard.rgb.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.*
import com.kgboard.rgb.runtime.*
import java.awt.Component
import java.awt.event.MouseEvent

class KgBoardStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "KGBoardStatus"
    override fun getDisplayName() = "KGBoard RGB"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = KgBoardStatusWidget()
    class KgBoardStatusWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
        private var statusBar: StatusBar? = null
        private var subscription: Cancellation? = null
        private var disposed = false
        override fun ID() = "KGBoardStatus"
        override fun install(statusBar: StatusBar) {
            this.statusBar = statusBar
            var last: String? = null
            subscription = LightingRuntimeService.getInstance().subscribe { state ->
                val presentation = connectionStatus(state) + analysisStatus(state.projects[state.activeProject])
                if (presentation == last) return@subscribe
                last = presentation
                ApplicationManager.getApplication().invokeLater({ if (!disposed) statusBar.updateWidget(ID()) }, ModalityState.any())
            }
        }
        override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
        override fun getText(): String = "RGB: ${connectionStatus(LightingRuntimeService.getInstance().snapshot())}"
        override fun getTooltipText(): String {
            val state = LightingRuntimeService.getInstance().snapshot()
            return "KGBoard: ${connectionStatus(state)}; ${analysisStatus(state.projects[state.activeProject])}"
        }
        override fun getAlignment() = Component.CENTER_ALIGNMENT
        override fun getClickConsumer() = com.intellij.util.Consumer<MouseEvent> {
            val service = LightingRuntimeService.getInstance()
            service.events.dispatch(if (service.snapshot().connectionWanted) RuntimeEvent.DisconnectRequested else RuntimeEvent.ConnectRequested())
        }
        override fun dispose() { disposed = true; subscription?.cancel(); subscription = null; statusBar = null }
    }
}
