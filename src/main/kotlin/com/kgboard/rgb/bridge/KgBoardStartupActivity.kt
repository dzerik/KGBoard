package com.kgboard.rgb.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Connects to OpenRGB on project open if auto-connect is enabled.
 */
class KgBoardStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = KgBoardSettings.getInstance()
        if (settings.enabled && settings.autoConnect) {
            OpenRgbConnectionService.getInstance().connect()
        }
    }
}
