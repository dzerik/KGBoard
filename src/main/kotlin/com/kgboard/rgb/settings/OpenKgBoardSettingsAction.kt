package com.kgboard.rgb.settings

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/** Public user action shared by Tools, Find Action and diagnostics. */
class OpenKgBoardSettingsAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(event.project, KgBoardConfigurable::class.java)
    }
}
