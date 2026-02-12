package com.kgboard.rgb.effect

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Project-level service managing RGB effects lifecycle.
 * Acts as a facade over LedCompositor — existing Phase 1 API preserved.
 *
 * Phase 1 callers (build/test/run listeners) use [applyEffect], [applyTemporary], [returnToIdle].
 * Phase 2 callers (git, analysis, pomodoro) use [addTargetedEffect], [removeTargetedEffect].
 */
@Service(Service.Level.PROJECT)
class EffectManagerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(EffectManagerService::class.java)

    private val compositor: LedCompositor
        get() = LedCompositor.getInstance(project)

    private val connection: OpenRgbConnectionService
        get() = OpenRgbConnectionService.getInstance()

    private val settings: KgBoardSettings
        get() = KgBoardSettings.getInstance()

    /** Current "main" global effect ID (for Phase 1 priority logic) */
    private var currentGlobalEffectId: String? = null
    private var currentGlobalPriority: Int = -1

    // ─── Phase 1 API (backward-compatible) ───

    fun applyEffect(effect: RgbEffect, timeoutMs: Long? = null) {
        if (!settings.enabled || !connection.isConnected) return

        if (currentGlobalPriority > effect.priority && currentGlobalEffectId != null) {
            log.debug("Ignoring effect ${effect.name} (priority ${effect.priority} < current $currentGlobalPriority)")
            return
        }

        log.info("Applying effect: ${effect.name} (priority: ${effect.priority})")

        // Remove previous global effect
        currentGlobalEffectId?.let { compositor.removeEffect(it) }

        val id = "global:${effect.name}:${System.currentTimeMillis()}"
        currentGlobalEffectId = id
        currentGlobalPriority = effect.priority

        val duration = timeoutMs ?: settings.effectDurationMs
        compositor.addEffect(id, effect, if (duration > 0) duration else 0)

        // Auto-cleanup for timed effects
        if (duration > 0) {
            // Compositor handles timeout internally via ActiveEffect.timeoutMs
            // We also need to clear our tracking
            scheduleGlobalCleanup(id, duration)
        }
    }

    fun applyTemporary(effect: RgbEffect) {
        applyEffect(effect, settings.effectDurationMs)
    }

    fun applyPersistent(effect: RgbEffect) {
        applyEffect(effect, timeoutMs = 0)
    }

    fun returnToIdle() {
        // Remove only the current global effect, not per-key indicators
        currentGlobalEffectId?.let { compositor.removeEffect(it) }
        currentGlobalEffectId = null
        currentGlobalPriority = -1
        // Compositor will render idle color for any LED not covered by per-key effects
        compositor.renderOnce()
    }

    fun clearEffect() {
        currentGlobalEffectId?.let { compositor.removeEffect(it) }
        currentGlobalEffectId = null
        currentGlobalPriority = -1
    }

    fun restoreCurrentEffect() {
        // Compositor already holds all active effects — just trigger a render
        compositor.renderOnce()
    }

    // ─── Phase 2 API (per-key targeted effects) ───

    fun addTargetedEffect(id: String, effect: RgbEffect, timeoutMs: Long = 0) {
        if (!settings.enabled || !connection.isConnected) return
        compositor.addEffect(id, effect, timeoutMs)
    }

    fun removeTargetedEffect(id: String) {
        compositor.removeEffect(id)
    }

    fun hasTargetedEffect(id: String): Boolean = compositor.hasEffect(id)

    // ─── Internal ───

    private fun scheduleGlobalCleanup(effectId: String, delayMs: Long) {
        // Use a simple thread to clear our tracking state after timeout
        Thread {
            try {
                Thread.sleep(delayMs + 100) // small buffer after compositor removes it
                if (currentGlobalEffectId == effectId) {
                    currentGlobalEffectId = null
                    currentGlobalPriority = -1
                }
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            name = "KGBoard-Cleanup"
            start()
        }
    }

    override fun dispose() {
        currentGlobalEffectId = null
        currentGlobalPriority = -1
    }

    companion object {
        fun getInstance(project: Project): EffectManagerService =
            project.getService(EffectManagerService::class.java)
    }
}
