package com.kgboard.rgb.bridge

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.IdeFrame
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color

/**
 * Handles IDE window focus changes:
 * - Focus lost → dim keyboard to idle or turn off
 * - Focus gained → restore current effect
 */
class FocusEventListener : ApplicationActivationListener {

    private val log = Logger.getInstance(FocusEventListener::class.java)

    override fun applicationActivated(ideFrame: IdeFrame) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        log.info("IDE gained focus — restoring RGB")
        val project = ideFrame.project ?: return
        val effectManager = com.kgboard.rgb.effect.EffectManagerService.getInstance(project)
        // Re-apply current effect or idle
        effectManager.restoreCurrentEffect()
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.dimOnFocusLoss) return

        log.info("IDE lost focus — dimming RGB")
        val connection = OpenRgbConnectionService.getInstance()
        if (connection.isConnected) {
            val factor = settings.dimBrightness / 100f
            val dimColor = dimColor(settings.idleColor, factor)
            connection.setAllLedsMultiDevice(dimColor)
        }
    }

    private fun dimColor(color: Color, factor: Float): Color {
        return Color(
            (color.red * factor).toInt().coerceIn(0, 255),
            (color.green * factor).toInt().coerceIn(0, 255),
            (color.blue * factor).toInt().coerceIn(0, 255)
        )
    }
}
