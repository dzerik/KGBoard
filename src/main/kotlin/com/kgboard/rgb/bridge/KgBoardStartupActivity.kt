package com.kgboard.rgb.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Connects to OpenRGB on project open and starts Phase 2 listeners.
 */
class KgBoardStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        val connection = OpenRgbConnectionService.getInstance()

        // Register connection listener to refresh layout and key groups on any successful connect.
        // The connection service is application-level and outlives the project, so the listener
        // MUST be removed when the project closes — otherwise listeners accumulate on every
        // project open/close cycle.
        val connectionListener = Runnable {
            KeyboardLayoutService.getInstance().refresh()
            KeyGroupService.getInstance().refreshFromDevice()
        }
        connection.addConnectionListener(connectionListener)
        Disposer.register(project) {
            connection.removeConnectionListener(connectionListener)
        }

        if (settings.autoConnect) {
            connection.connect()
        }

        // Start Phase 2 listeners
        CodeAnalysisListener.getInstance(project).start()
        IdeNotificationListener.getInstance(project).start()
        TodoIndicatorListener.getInstance(project).start()
        DebuggerBreakpointListener.getInstance(project).start()
    }
}
