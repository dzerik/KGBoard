package com.kgboard.rgb.effect

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.client.OpenRgbConnectionService
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * LED Compositor — buffers and composites multiple effects onto a single LED frame.
 *
 * Replaces direct setAllLeds() calls with a buffered render cycle:
 * 1. Fill buffer with idle color
 * 2. Sort active effects by priority (ascending — higher priority overwrites)
 * 3. For each effect, compute colors and write to buffer at target indices
 * 4. Dirty-check against previous frame
 * 5. Send only changed LEDs via updateLeds()
 *
 * Adaptive render: cycle runs only when animated effects are present.
 */
@Service(Service.Level.PROJECT)
class LedCompositor(private val project: Project) : Disposable {

    private val log = Logger.getInstance(LedCompositor::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KGBoard-Compositor-${project.name}").apply { isDaemon = true }
    }

    /** All active effects keyed by unique ID */
    private val activeEffects = ConcurrentHashMap<String, ActiveEffect>()

    /** Previous frame for dirty-check */
    @Volatile
    private var previousFrame: Array<Color>? = null

    /** Render loop handle — guarded by [renderLock] */
    private var renderTask: ScheduledFuture<*>? = null

    /** Lock for render loop start/stop to prevent double-start race */
    private val renderLock = ReentrantLock()

    private val connection: OpenRgbConnectionService
        get() = OpenRgbConnectionService.getInstance()

    private val settings: KgBoardSettings
        get() = KgBoardSettings.getInstance()

    companion object {
        const val RENDER_INTERVAL_MS = 33L // ~30 FPS

        fun getInstance(project: Project): LedCompositor =
            project.getService(LedCompositor::class.java)
    }

    /**
     * Add or replace an effect in the compositor.
     * Starts render loop if needed.
     */
    fun addEffect(id: String, effect: RgbEffect, timeoutMs: Long = 0) {
        val startTime = System.currentTimeMillis()
        activeEffects[id] = ActiveEffect(effect, startTime, timeoutMs)
        log.debug("Added effect '$id': ${effect.name} (priority=${effect.priority}, target=${effect.target})")
        ensureRenderLoop()
    }

    /**
     * Remove an effect by ID.
     * Stops render loop if no animated effects remain.
     */
    fun removeEffect(id: String) {
        activeEffects.remove(id)
        log.debug("Removed effect '$id'")
        renderOnce()
        maybeStopRenderLoop()
    }

    /** Remove all effects matching the given target type */
    fun removeEffectsByTarget(targetClass: Class<out EffectTarget>) {
        val toRemove = activeEffects.entries.filter { targetClass.isInstance(it.value.effect.target) }.map { it.key }
        toRemove.forEach { activeEffects.remove(it) }
        if (toRemove.isNotEmpty()) {
            renderOnce()
            maybeStopRenderLoop()
        }
    }

    /** Remove all AllLeds effects (used by returnToIdle) */
    fun removeGlobalEffects() {
        removeEffectsByTarget(EffectTarget.AllLeds::class.java)
    }

    /** Remove ALL effects */
    fun clearAll() {
        activeEffects.clear()
        renderOnce()
        maybeStopRenderLoop()
    }

    /** Check if an effect with given ID exists */
    fun hasEffect(id: String): Boolean = activeEffects.containsKey(id)

    /** Get snapshot of active effect IDs */
    fun activeEffectIds(): Set<String> = activeEffects.keys.toSet()

    /**
     * Force a single render frame (used for immediate updates like idle color).
     */
    fun renderOnce() {
        scheduler.submit { doRender() }
    }

    private fun ensureRenderLoop() {
        renderLock.withLock {
            if (renderTask != null) return
            renderTask = scheduler.scheduleAtFixedRate(
                { doRender() },
                0, RENDER_INTERVAL_MS, TimeUnit.MILLISECONDS
            )
        }
    }

    private fun maybeStopRenderLoop() {
        renderLock.withLock {
            if (!hasAnimatedEffects()) {
                renderTask?.cancel(false)
                renderTask = null
            }
        }
    }

    private fun hasAnimatedEffects(): Boolean {
        return activeEffects.values.any { ae ->
            val e = ae.effect
            e is PulseEffect || e is GradientEffect || e is FlashEffect
        }
    }

    private fun doRender() {
        try {
            if (!settings.enabled || !connection.isConnected) return

            val deviceIndex = settings.deviceIndex
            val devices = connection.devices
            if (devices.isEmpty() || deviceIndex >= devices.size) return

            val numLeds = devices[deviceIndex].numLeds
            if (numLeds <= 0) return

            val now = System.currentTimeMillis()
            val idleColor = settings.idleColor

            // 1. Fill buffer with idle
            val frame = Array(numLeds) { idleColor }

            // 2. Remove expired effects
            val expired = mutableListOf<String>()
            activeEffects.forEach { (id, ae) ->
                if (ae.timeoutMs > 0 && now - ae.startTime >= ae.timeoutMs) {
                    expired.add(id)
                }
            }
            expired.forEach { activeEffects.remove(it) }

            // 3. Get snapshot sorted by priority ascending (low priority first, high overwrites)
            val snapshot = activeEffects.values.toList().sortedBy { it.effect.priority }

            // 4. Composite each effect
            for (ae in snapshot) {
                val effect = ae.effect
                val elapsed = now - ae.startTime
                val indices = resolveIndices(effect.target, numLeds)
                val colors = computeColors(effect, elapsed, indices.size, numLeds)

                for ((i, ledIdx) in indices.withIndex()) {
                    if (ledIdx in frame.indices && i < colors.size) {
                        frame[ledIdx] = colors[i]
                    }
                }
            }

            // 5. Dirty check
            val prev = previousFrame
            if (prev != null && prev.size == frame.size && prev.contentEquals(frame)) {
                return // no change
            }
            previousFrame = frame.copyOf()

            // 6. Send to device
            connection.updateLeds(deviceIndex, frame.toList())
        } catch (e: Exception) {
            log.warn("Compositor render error: ${e.message}")
        }
    }

    private fun resolveIndices(target: EffectTarget, numLeds: Int): List<Int> {
        return when (target) {
            is EffectTarget.AllLeds -> (0 until numLeds).toList()
            is EffectTarget.SingleLed -> listOf(target.ledIndex)
            is EffectTarget.LedSet -> target.ledIndices.filter { it in 0 until numLeds }
            is EffectTarget.Zone -> {
                val devices = connection.devices
                val deviceIndex = settings.deviceIndex
                if (devices.isEmpty() || deviceIndex >= devices.size) return emptyList()
                val zones = devices[deviceIndex].zones
                if (target.zoneIndex >= zones.size) return emptyList()
                var offset = 0
                for (i in 0 until target.zoneIndex) {
                    offset += zones[i].ledsCount
                }
                val count = zones[target.zoneIndex].ledsCount
                (offset until (offset + count)).filter { it in 0 until numLeds }
            }
        }
    }

    private fun computeColors(effect: RgbEffect, elapsedMs: Long, count: Int, totalLeds: Int): List<Color> {
        return when (effect) {
            is StaticEffect -> List(count) { effect.color }

            is PulseEffect -> {
                val period = effect.periodMs
                val phase = (elapsedMs % period).toFloat() / period
                val brightness = if (phase < 0.5f) {
                    effect.minBrightness + (1f - effect.minBrightness) * (phase * 2f)
                } else {
                    1f - (1f - effect.minBrightness) * ((phase - 0.5f) * 2f)
                }
                val color = scaleBrightness(effect.color, brightness.coerceIn(effect.minBrightness, 1f))
                List(count) { color }
            }

            is FlashEffect -> {
                if (elapsedMs < effect.durationMs) {
                    List(count) { effect.color }
                } else {
                    List(count) { settings.idleColor }
                }
            }

            is ProgressEffect -> {
                if (effect.target is EffectTarget.AllLeds) {
                    val litLeds = (totalLeds * effect.progress).toInt()
                    (0 until count).map { i ->
                        if (i < litLeds) effect.color else effect.backgroundColor
                    }
                } else {
                    val litLeds = (count * effect.progress).toInt()
                    (0 until count).map { i ->
                        if (i < litLeds) effect.color else effect.backgroundColor
                    }
                }
            }

            is GradientEffect -> {
                val ratio = if (effect.durationMs > 0) {
                    (elapsedMs.toFloat() / effect.durationMs).coerceIn(0f, 1f)
                } else 0f
                val color = interpolateColor(effect.startColor, effect.endColor, ratio)
                List(count) { color }
            }
        }
    }

    private fun scaleBrightness(color: Color, factor: Float): Color {
        return Color(
            (color.red * factor).toInt().coerceIn(0, 255),
            (color.green * factor).toInt().coerceIn(0, 255),
            (color.blue * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        return Color(
            (from.red + (to.red - from.red) * ratio).toInt().coerceIn(0, 255),
            (from.green + (to.green - from.green) * ratio).toInt().coerceIn(0, 255),
            (from.blue + (to.blue - from.blue) * ratio).toInt().coerceIn(0, 255)
        )
    }

    override fun dispose() {
        renderLock.withLock {
            renderTask?.cancel(false)
            renderTask = null
        }
        activeEffects.clear()
        scheduler.shutdown()
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }
}

/**
 * Wraps an effect with timing metadata.
 */
data class ActiveEffect(
    val effect: RgbEffect,
    val startTime: Long,
    val timeoutMs: Long = 0 // 0 = no timeout
)
