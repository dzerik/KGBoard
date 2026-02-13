package com.kgboard.rgb.bridge

import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.FlashEffect
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Triggers a brief flash on file save (Ctrl+S / Cmd+S).
 * Registered as applicationListener via plugin.xml.
 *
 * Supports dual-path dispatch:
 * - Without LED indices: Phase 1 API (applyTemporary)
 * - With LED indices: Phase 2 API (addTargetedEffect)
 */
class FileSaveListener : FileDocumentManagerListener {

    companion object {
        const val EFFECT_ID = "file-save"
    }

    override fun beforeAllDocumentsSaving() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.state.fileSaveFlashEnabled) return

        val color = settings.parseColor(settings.state.fileSaveFlashColor)
        val ledIndices = settings.fileSaveLedIndices
        val isTargeted = ledIndices.isNotEmpty()
        val openProjects = ProjectManager.getInstance().openProjects

        for (project in openProjects) {
            if (project.isDisposed) continue
            try {
                val effectManager = EffectManagerService.getInstance(project)
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        FlashEffect(
                            color = color,
                            durationMs = 300,
                            name = "file-save",
                            priority = 3,
                            target = target
                        ),
                        timeoutMs = 400
                    )
                } else {
                    effectManager.applyTemporary(
                        FlashEffect(
                            color = color,
                            durationMs = 300,
                            name = "file-save",
                            priority = 3
                        )
                    )
                }
            } catch (_: Exception) {
                // project service may not be available
            }
        }
    }
}
