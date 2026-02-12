package com.kgboard.rgb.bridge

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Handles project lifecycle events:
 * - Project closing → reset LEDs to idle or black
 * - Project opened → handled by KgBoardStartupActivity
 */
class ProjectFocusListener : ProjectManagerListener {

    private val log = Logger.getInstance(ProjectFocusListener::class.java)

    override fun projectClosing(project: Project) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        log.info("Project '${project.name}' closing — clearing effects")
        val effectManager = EffectManagerService.getInstance(project)
        effectManager.clearEffect()

        val connection = OpenRgbConnectionService.getInstance()
        if (connection.isConnected) {
            connection.setAllLeds(settings.deviceIndex, settings.idleColor)
        }
    }
}
