package com.kgboard.rgb.effect

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Project-level service managing RGB effects lifecycle.
 * Orchestrates which effect is currently active, handles priorities and timeouts.
 */
@Service(Service.Level.PROJECT)
class EffectManagerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(EffectManagerService::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KGBoard-Effects-${project.name}").apply { isDaemon = true }
    }

    private var currentEffect: RgbEffect? = null
    private var animationTask: ScheduledFuture<*>? = null
    private var timeoutTask: ScheduledFuture<*>? = null

    private val connection: OpenRgbConnectionService
        get() = OpenRgbConnectionService.getInstance()

    private val settings: KgBoardSettings
        get() = KgBoardSettings.getInstance()

    fun applyEffect(effect: RgbEffect, timeoutMs: Long? = null) {
        if (!settings.enabled || !connection.isConnected) return

        val current = currentEffect
        if (current != null && current.priority > effect.priority) {
            log.debug("Ignoring effect ${effect.name} (priority ${effect.priority} < current ${current.priority})")
            return
        }

        log.info("Applying effect: ${effect.name} (priority: ${effect.priority})")
        stopCurrentAnimation()
        currentEffect = effect

        when (effect) {
            is StaticEffect -> applyStatic(effect)
            is PulseEffect -> applyPulse(effect)
            is FlashEffect -> applyFlash(effect)
            is ProgressEffect -> applyProgress(effect)
        }

        val duration = timeoutMs ?: settings.effectDurationMs
        if (duration > 0) {
            timeoutTask = scheduler.schedule({
                returnToIdle()
            }, duration, TimeUnit.MILLISECONDS)
        }
    }

    fun applyTemporary(effect: RgbEffect) {
        applyEffect(effect, settings.effectDurationMs)
    }

    fun applyPersistent(effect: RgbEffect) {
        applyEffect(effect, timeoutMs = 0)
    }

    fun returnToIdle() {
        stopCurrentAnimation()
        currentEffect = null
        val idleColor = settings.idleColor
        setAllLeds(idleColor)
    }

    fun clearEffect() {
        stopCurrentAnimation()
        currentEffect = null
    }

    /**
     * Re-applies the current active effect (e.g. after focus restore).
     * If no effect is active, returns to idle.
     */
    fun restoreCurrentEffect() {
        val effect = currentEffect
        if (effect != null) {
            stopCurrentAnimation()
            when (effect) {
                is StaticEffect -> applyStatic(effect)
                is PulseEffect -> applyPulse(effect)
                is FlashEffect -> applyStatic(StaticEffect(effect.color, effect.name, effect.priority))
                is ProgressEffect -> applyProgress(effect)
            }
        } else {
            returnToIdle()
        }
    }

    private fun applyStatic(effect: StaticEffect) {
        setAllLeds(effect.color)
    }

    private fun applyPulse(effect: PulseEffect) {
        val periodMs = effect.periodMs
        val stepsPerHalf = 30
        val stepMs = periodMs / (stepsPerHalf * 2)

        var step = 0
        animationTask = scheduler.scheduleAtFixedRate({
            try {
                val progress = step.toFloat() / stepsPerHalf
                val brightness = if (progress <= 1f) {
                    effect.minBrightness + (1f - effect.minBrightness) * progress
                } else {
                    1f - (1f - effect.minBrightness) * (progress - 1f)
                }
                val color = scaleBrightness(effect.color, brightness.coerceIn(effect.minBrightness, 1f))
                setAllLeds(color)
                step = (step + 1) % (stepsPerHalf * 2)
            } catch (e: Exception) {
                log.warn("Pulse effect error: ${e.message}")
            }
        }, 0, stepMs, TimeUnit.MILLISECONDS)
    }

    private fun applyFlash(effect: FlashEffect) {
        setAllLeds(effect.color)
        animationTask = scheduler.schedule({
            returnToIdle()
        }, effect.durationMs, TimeUnit.MILLISECONDS)
    }

    private fun applyProgress(effect: ProgressEffect) {
        val deviceIndex = settings.deviceIndex
        val devices = connection.devices
        if (devices.isEmpty() || deviceIndex >= devices.size) return

        val numLeds = devices[deviceIndex].numLeds
        val litLeds = (numLeds * effect.progress).toInt()
        val colors = (0 until numLeds).map { i ->
            if (i < litLeds) effect.color else effect.backgroundColor
        }
        connection.updateLeds(deviceIndex, colors)
    }

    private fun setAllLeds(color: Color) {
        connection.setAllLeds(settings.deviceIndex, color)
    }

    private fun scaleBrightness(color: Color, factor: Float): Color {
        return Color(
            (color.red * factor).toInt().coerceIn(0, 255),
            (color.green * factor).toInt().coerceIn(0, 255),
            (color.blue * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun stopCurrentAnimation() {
        animationTask?.cancel(false)
        animationTask = null
        timeoutTask?.cancel(false)
        timeoutTask = null
    }

    override fun dispose() {
        stopCurrentAnimation()
        // Reset LEDs to idle on project dispose
        try {
            if (connection.isConnected) {
                setAllLeds(settings.idleColor)
            }
        } catch (_: Exception) {
        }
        scheduler.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): EffectManagerService =
            project.getService(EffectManagerService::class.java)
    }
}
