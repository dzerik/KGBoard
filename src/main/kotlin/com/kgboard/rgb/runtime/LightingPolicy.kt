package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.effect.*
import java.awt.Color

/** One composition policy for all projects and every physical device. No I/O. */
class LightingPolicy {
    fun frames(state: RuntimeState, nowMs: Long): Map<Int, List<Color>> {
        val ready = state.connection as? ConnectionState.Ready ?: return emptyMap()
        val idle = color(state.settings.idleColor)
        val layers = visibleLayers(state, nowMs)
        val configs = configuredDevices(state)
        val preview = state.preview?.takeIf { it.deadlineMs > nowMs }
        val devices = if (preview != null && configs.none { it.deviceIndex == preview.device }) configs + DeviceSettings(preview.device) else configs
        return devices.mapNotNull { config ->
            val device = ready.devices.getOrNull(config.deviceIndex)?.takeIf { it.numLeds > 0 } ?: return@mapNotNull null
            val frame = when {
                state.shuttingDown -> List(device.numLeds) { if (state.settings.resetOnExit) Color.BLACK else idle }
                !state.settings.enabled -> List(device.numLeds) { idle }
                !state.focused && state.settings.dimOnFocusLoss -> List(device.numLeds) {
                    EffectColorComputer.scaleBrightness(idle, state.settings.dimBrightness.coerceIn(0, 100) / 100f)
                }
                config.role == "ambient" -> List(device.numLeds) { idle }
                else -> EffectColorComputer.composeFrame(idle,
                    forRole(layers, config.role).map { layer ->
                        ActiveEffect(layer.effect, layer.createdAtMs, layer.expiresAtMs?.minus(layer.createdAtMs) ?: 0)
                    }, device.numLeds, nowMs) { zone -> zoneIndices(device, zone.zoneIndex) }.toList()
            }
            val result = if (!state.shuttingDown && preview?.device == config.deviceIndex && state.settings.enabled && (state.focused || !state.settings.dimOnFocusLoss))
                frame.mapIndexed { index, base -> if (preview.indices.isEmpty() || index in preview.indices) preview.color else base } else frame
            config.deviceIndex to result
        }.toMap()
    }

    fun nextWakeAt(state: RuntimeState, nowMs: Long): Long? {
        if (state.shuttingDown) return null
        val deadline = state.layers.values.mapNotNull { it.expiresAtMs }.filter { it > nowMs }.minOrNull()
        val ready = state.connection as? ConnectionState.Ready
        val animate = state.settings.enabled && (state.focused || !state.settings.dimOnFocusLoss) && ready != null &&
            configuredDevices(state).any { config ->
                val device = ready.devices.getOrNull(config.deviceIndex)
                device != null && config.role != "ambient" && animatedVisible(forRole(visibleLayers(state, nowMs), config.role), device)
            }
        return listOfNotNull(deadline, state.preview?.deadlineMs?.takeIf { it > nowMs }, OperationPolicy().nextDeadline(state, nowMs), IndicatorPolicy().nextDeadline(state, nowMs), PomodoroPolicy().nextWakeAt(state, nowMs), if (animate) nowMs + 33 else null).minOrNull()
    }

    private fun configuredDevices(state: RuntimeState): List<DeviceSettings> =
        if (state.settings.multiDeviceEnabled && state.settings.deviceConfigs.isNotEmpty()) state.settings.deviceConfigs.filter { it.enabled }
        else listOf(DeviceSettings(state.settings.deviceIndex))

    private fun visibleLayers(state: RuntimeState, nowMs: Long): List<EffectLayer> = (state.layers.values + OperationPolicy().layers(state, nowMs) + IndicatorPolicy().layers(state, nowMs) + PomodoroPolicy().layers(state, nowMs))
        .filter { (it.expiresAtMs == null || it.expiresAtMs > nowMs) &&
            (it.key.project == null || it.key.project == state.activeProject && it.key.project in state.projects) }
        .sortedWith(compareBy({ it.effect.priority }, { it.createdAtMs }, { it.key.project?.value.orEmpty() }, { it.key.source }))

    private fun forRole(layers: List<EffectLayer>, role: String): List<EffectLayer> =
        if (role == "indicator") layers.filter { it.effect.target !is EffectTarget.AllLeds } else layers

    private fun animatedVisible(layers: List<EffectLayer>, device: RgbDeviceInfo): Boolean {
        val covered = mutableSetOf<Int>()
        for (layer in layers.asReversed()) {
            val indices = EffectColorComputer.resolveIndices(layer.effect.target, device.numLeds) { zone -> zoneIndices(device, zone.zoneIndex) }
            if (isAnimated(layer.effect) && indices.any { it !in covered }) return true
            covered.addAll(indices)
        }
        return false
    }

    private fun isAnimated(effect: RgbEffect): Boolean = effect is PulseEffect || effect is FlashEffect ||
        effect is GradientEffect || effect is RainbowEffect || effect is WaveEffect

    private fun zoneIndices(device: RgbDeviceInfo, index: Int): List<Int> {
        val zone = device.zones.getOrNull(index) ?: return emptyList()
        val start = device.zones.take(index).sumOf { it.ledsCount }
        return (start until start + zone.ledsCount).filter { it in 0 until device.numLeds }
    }

    companion object {
        fun color(raw: String): Color = runCatching { Color.decode(raw) }.getOrDefault(Color.BLACK)
    }
}
