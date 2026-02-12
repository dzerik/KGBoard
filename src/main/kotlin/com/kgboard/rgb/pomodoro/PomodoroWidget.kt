package com.kgboard.rgb.pomodoro

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Component
import java.awt.event.MouseEvent

class PomodoroWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "KGBoardPomodoro"
    override fun getDisplayName(): String = "KGBoard Pomodoro"
    override fun isAvailable(project: Project): Boolean = KgBoardSettings.getInstance().pomodoroEnabled

    override fun createWidget(project: Project): StatusBarWidget = PomodoroStatusWidget(project)

    class PomodoroStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
        private var statusBar: StatusBar? = null

        override fun ID(): String = "KGBoardPomodoro"

        override fun install(statusBar: StatusBar) {
            this.statusBar = statusBar
            val timer = PomodoroTimerService.getInstance(project)
            timer.addChangeListener {
                statusBar.updateWidget(ID())
            }
        }

        override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

        override fun getText(): String {
            val timer = PomodoroTimerService.getInstance(project)
            return if (timer.isRunning) {
                "${timer.phaseDisplayName()} ${timer.formatTimeRemaining()} (#${timer.completedSessions + 1})"
            } else {
                "Pomodoro"
            }
        }

        override fun getTooltipText(): String {
            val timer = PomodoroTimerService.getInstance(project)
            return if (timer.isRunning) {
                "KGBoard Pomodoro: ${timer.phaseDisplayName()} — ${timer.formatTimeRemaining()} remaining (session #${timer.completedSessions + 1}). Click to stop."
            } else {
                "KGBoard Pomodoro: Click to start"
            }
        }

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? {
            return com.intellij.util.Consumer {
                val timer = PomodoroTimerService.getInstance(project)
                if (timer.isRunning) {
                    timer.stop()
                } else {
                    timer.start()
                }
                statusBar?.updateWidget(ID())
            }
        }

        override fun dispose() {
            statusBar = null
        }
    }
}
