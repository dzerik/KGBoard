package com.kgboard.rgb.pomodoro

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action for Tools menu: start/stop/skip Pomodoro timer.
 */
class PomodoroStartStopAction : AnAction("KGBoard Pomodoro: Start/Stop") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val timer = PomodoroTimerService.getInstance(project)
        if (timer.isRunning) {
            timer.stop()
        } else {
            timer.start()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val timer = PomodoroTimerService.getInstance(project)
        e.presentation.text = if (timer.isRunning) "KGBoard Pomodoro: Stop" else "KGBoard Pomodoro: Start"
    }
}

class PomodoroSkipAction : AnAction("KGBoard Pomodoro: Skip Phase") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val timer = PomodoroTimerService.getInstance(project)
        if (timer.isRunning) {
            timer.skip()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val timer = PomodoroTimerService.getInstance(project)
        e.presentation.isEnabled = timer.isRunning
    }
}
