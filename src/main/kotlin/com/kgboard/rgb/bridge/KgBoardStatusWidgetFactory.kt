package com.kgboard.rgb.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.kgboard.rgb.client.OpenRgbConnectionService
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class KgBoardStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "KGBoardStatus"
    override fun getDisplayName(): String = "KGBoard RGB"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = KgBoardStatusWidget()

    class KgBoardStatusWidget : StatusBarWidget, StatusBarWidget.TextPresentation {

        private var statusBar: StatusBar? = null

        override fun ID(): String = "KGBoardStatus"

        override fun install(statusBar: StatusBar) {
            this.statusBar = statusBar
        }

        override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

        override fun getText(): String {
            val service = OpenRgbConnectionService.getInstance()
            return if (service.isConnected) {
                "RGB: ON (${service.deviceCount})"
            } else {
                "RGB: OFF"
            }
        }

        override fun getTooltipText(): String {
            val service = OpenRgbConnectionService.getInstance()
            return if (service.isConnected) {
                "KGBoard: Connected to OpenRGB (${service.deviceCount} devices)"
            } else {
                "KGBoard: Not connected to OpenRGB. Click to connect."
            }
        }

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? {
            return com.intellij.util.Consumer {
                val service = OpenRgbConnectionService.getInstance()
                if (service.isConnected) {
                    service.disconnect()
                } else {
                    service.connect()
                }
                statusBar?.updateWidget(ID())
            }
        }

        override fun dispose() {
            statusBar = null
        }
    }
}
