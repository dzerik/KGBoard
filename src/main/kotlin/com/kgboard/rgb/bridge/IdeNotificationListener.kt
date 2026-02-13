package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Monitors IDE-level notifications and triggers RGB effects:
 * - Indexing (dumb mode) → orange pulse
 * - Low memory → red flash
 */
@Service(Service.Level.PROJECT)
class IdeNotificationListener(private val project: Project) : Disposable {

    private val log = Logger.getInstance(IdeNotificationListener::class.java)

    companion object {
        const val INDEXING_EFFECT_ID = "ide-indexing"
        const val MEMORY_EFFECT_ID = "ide-low-memory"

        fun getInstance(project: Project): IdeNotificationListener =
            project.getService(IdeNotificationListener::class.java)
    }

    fun start() {
        val settings = KgBoardSettings.getInstance()

        // Indexing / dumb mode
        if (settings.notifyIndexingEnabled) {
            project.messageBus.connect(this)
                .subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                    override fun enteredDumbMode() {
                        onIndexingStarted()
                    }

                    override fun exitDumbMode() {
                        onIndexingFinished()
                    }
                })
            log.info("IDE notification listener started (indexing)")
        }

        // Low memory
        if (settings.notifyLowMemoryEnabled) {
            com.intellij.openapi.util.LowMemoryWatcher.register({
                onLowMemory()
            }, this)
            log.info("IDE notification listener started (memory)")
        }
    }

    private fun onIndexingStarted() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.notifyIndexingEnabled) return

        val ledIndices = settings.notifyIndexingLedIndices
        val target = if (ledIndices.isNotEmpty()) EffectTarget.LedSet(ledIndices) else EffectTarget.AllLeds

        val effectManager = EffectManagerService.getInstance(project)
        effectManager.addTargetedEffect(
            INDEXING_EFFECT_ID,
            PulseEffect(
                color = settings.notifyIndexingColor,
                periodMs = 1500,
                name = "indexing",
                priority = 1,
                target = target
            )
        )
    }

    private fun onIndexingFinished() {
        val effectManager = EffectManagerService.getInstance(project)
        effectManager.removeTargetedEffect(INDEXING_EFFECT_ID)
    }

    private fun onLowMemory() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.notifyLowMemoryEnabled) return

        val ledIndices = settings.notifyLowMemoryLedIndices
        val target = if (ledIndices.isNotEmpty()) EffectTarget.LedSet(ledIndices) else EffectTarget.AllLeds

        val effectManager = EffectManagerService.getInstance(project)
        effectManager.addTargetedEffect(
            MEMORY_EFFECT_ID,
            FlashEffect(
                color = settings.notifyLowMemoryColor,
                durationMs = 2000,
                name = "low-memory",
                priority = 10,
                target = target
            ),
            timeoutMs = 3000
        )
    }

    override fun dispose() {
        try {
            val effectManager = EffectManagerService.getInstance(project)
            effectManager.removeTargetedEffect(INDEXING_EFFECT_ID)
            effectManager.removeTargetedEffect(MEMORY_EFFECT_ID)
        } catch (_: Exception) {
            // project may already be disposed
        }
    }
}
