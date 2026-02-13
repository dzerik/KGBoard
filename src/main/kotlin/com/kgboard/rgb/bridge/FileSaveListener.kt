package com.kgboard.rgb.bridge

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.FlashEffect
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Triggers a brief flash on file save (Ctrl+S / Cmd+S).
 * Registered as applicationListener via plugin.xml.
 */
class FileSaveListener : FileDocumentManagerListener {

    override fun beforeAllDocumentsSaving() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.state.fileSaveFlashEnabled) return

        val color = settings.parseColor(settings.state.fileSaveFlashColor)
        val openProjects = ProjectManager.getInstance().openProjects
        for (project in openProjects) {
            if (project.isDisposed) continue
            try {
                val effectManager = EffectManagerService.getInstance(project)
                effectManager.applyTemporary(
                    FlashEffect(
                        color = color,
                        durationMs = 300,
                        name = "file-save",
                        priority = 3
                    )
                )
            } catch (_: Exception) {
                // project service may not be available
            }
        }
    }
}
