package com.kgboard.rgb.bridge

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color

/**
 * Handles IDE lifecycle events:
 * - App closing → reset keyboard to black (clean exit)
 */
class AppLifecycleHandler : AppLifecycleListener {

    private val log = Logger.getInstance(AppLifecycleHandler::class.java)

    override fun appWillBeClosed(isRestart: Boolean) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.resetOnExit) return

        val connection = OpenRgbConnectionService.getInstance()
        if (connection.isConnected) {
            log.info("IDE shutting down (restart=$isRestart) — resetting keyboard LEDs")
            try {
                connection.setAllLedsMultiDevice(Color.BLACK)
                // The reset is dispatched to the connection executor asynchronously. During IDE
                // shutdown there is no later flush point, so we briefly block here to give the
                // packet a chance to reach OpenRGB before the process exits. 100ms is a pragmatic
                // bound — acceptable on the shutdown path (no UI responsiveness concern).
                Thread.sleep(100)
            } catch (e: Exception) {
                log.warn("Failed to reset LEDs on shutdown: ${e.message}")
            }
        }
    }
}
