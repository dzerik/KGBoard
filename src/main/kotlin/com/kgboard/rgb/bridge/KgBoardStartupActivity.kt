package com.kgboard.rgb.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Connects to OpenRGB on project open and starts Phase 2 listeners.
 */
class KgBoardStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        if (settings.autoConnect) {
            OpenRgbConnectionService.getInstance().connect()
            // Refresh keyboard layout after connection
            KeyboardLayoutService.getInstance().refresh()
        }

        // Start Phase 2 listeners
        CodeAnalysisListener.getInstance(project).start()
        IdeNotificationListener.getInstance(project).start()
        TodoIndicatorListener.getInstance(project).start()
    }
}
