package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.device.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.*
import com.kgboard.rgb.layout.DeviceKeyMapping
import java.awt.Color

/** One composition policy for all projects and every physical device. No I/O. */
class LightingPolicy {
    private fun activePreview(state: RuntimeState, nowMs: Long): PreviewState? {
        val inventory = state.inventory() ?: return null
        return state.preview?.takeIf { preview ->
            preview.deadlineMs > nowMs && preview.token == inventory.token && preview.binding?.let {
                val resolved = DeviceResolver.resolve(it, inventory, state.settings.untrustedSerials)
                resolved is DeviceResolution.Resolved && resolved.index == preview.device
            } == true
        }
    }

    fun outputs(state: RuntimeState, nowMs: Long): List<ResolvedDevice> {
        val devices = state.resolvedDevices().toMutableList()
        val inventory = state.inventory() ?: return emptyList()
        val preview = activePreview(state, nowMs)?.takeUnless { state.shuttingDown }
        preview?.binding?.let { binding ->
            val resolution = DeviceResolver.resolve(binding, inventory, state.settings.untrustedSerials)
            if (resolution is DeviceResolution.Resolved && devices.none { it.index == resolution.index })
                devices.add(
                    ResolvedDevice(
                        ConfiguredBinding(binding), inventory.token, resolution.index,
                        resolution.fingerprint, inventory.devices[resolution.index]
                    )
                )
        }
        return devices
    }

    fun retiredFrame(state: RuntimeState, device: RgbDeviceInfo): List<Color> {
        val idle = color(state.settings.idleColor)
        val base = when {
            state.shuttingDown -> if (state.settings.resetOnExit) Color.BLACK else idle
            !state.settings.enabled -> idle
            !state.focused && state.settings.dimOnFocusLoss -> EffectColorComputer.scaleBrightness(
                idle,
                state.settings.dimBrightness / 100f
            )

            else -> idle
        }
        return List(device.numLeds) { base }
    }

    fun frames(state: RuntimeState, nowMs: Long): Map<Int, List<Color>> {
        val layers = visibleLayers(state, nowMs)
        val preview = activePreview(state, nowMs)
        return outputs(state, nowMs).filter { it.device.numLeds > 0 }.associate { output ->
            val device = output.device
            val prepared = layers.mapNotNull { prepare(state, it, output) }
            val frame = when {
                state.shuttingDown || !state.settings.enabled || !state.focused && state.settings.dimOnFocusLoss || output.configured.role == "ambient" -> retiredFrame(
                    state,
                    device
                )

                else -> EffectColorComputer.composeFrame(
                    color(state.settings.idleColor),
                    forRole(prepared, output.configured.role).map {
                        ActiveEffect(
                            it.effect,
                            it.createdAtMs,
                            it.expiresAtMs?.minus(it.createdAtMs) ?: 0
                        )
                    },
                    device.numLeds, nowMs
                ) { zone -> zoneIndices(device, zone.zoneIndex) }.toList()
            }
            val colors =
                if (!state.shuttingDown && state.settings.enabled && (state.focused || !state.settings.dimOnFocusLoss) && preview?.device == output.index)
                    frame.mapIndexed { index, base -> if (preview.indices.isEmpty() || index in preview.indices) preview.color else base } else frame
            output.index to colors
        }
    }

    fun nextWakeAt(state: RuntimeState, nowMs: Long): Long? {
        if (state.shuttingDown) return null
        val layers = visibleLayers(state, nowMs)
        val animate = state.settings.enabled && (state.focused || !state.settings.dimOnFocusLoss) &&
            outputs(state, nowMs).any { output ->
                output.configured.role != "ambient" &&
                    animatedVisible(
                        forRole(layers.mapNotNull { prepare(state, it, output) }, output.configured.role),
                        output.device
                    )
            }
        return listOfNotNull(
            state.layers.values.mapNotNull { it.expiresAtMs }.filter { it > nowMs }.minOrNull(),
            state.preview?.deadlineMs?.takeIf { it > nowMs }, OperationPolicy().nextDeadline(state, nowMs),
            IndicatorPolicy().nextDeadline(state, nowMs), PomodoroPolicy().nextWakeAt(state, nowMs),
            if (animate) nowMs + 33 else null
        ).minOrNull()
    }

    private fun prepare(state: RuntimeState, layer: EffectLayer, output: ResolvedDevice): EffectLayer? {
        val id = layer.ruleId ?: return layer
        val descriptor = ScenarioCatalog.byId(id) ?: return null
        val appearance = (if (descriptor.scope == SettingsScope.APP) state.settings.rules[id]
        else layer.key.project?.let { state.projects[it]?.settings?.rules?.get(id) })?.takeIf { it.enabled }
            ?: return null
        var indices = TargetResolver.resolve(appearance.target, output.device, state.groups).indices
        if (layer.hintCodes != null) {
            val mapping = DeviceKeyMapping.build(output.device)
            val eligible = layer.hintCodes.mapNotNull { mapping.keyCodes[it] }.toSet()
            indices = indices.filter { it in eligible }
        } else if (layer.mapping != null) {
            if (layer.mapping != DeviceMappingRevision(
                    output.token,
                    output.configured.binding.id,
                    output.index,
                    output.fingerprint
                )
            ) return null
            val eligible = EffectColorComputer.resolveIndices(layer.effect.target, output.device.numLeds).toSet()
            indices = indices.filter { it in eligible }
        }
        if (indices.isEmpty()) return null
        val goal = if (appearance.target == TargetSpec.All && layer.hintCodes == null && layer.mapping == null)
            EffectTarget.AllLeds else EffectTarget.LedSet(indices)
        val style = appearance.style.copy(
            color = layer.colorOverride ?: appearance.style.color,
            durationMs = layer.factDurationMs ?: appearance.style.durationMs
        )
        return layer.copy(
            effect = ScenarioEffects.create(
                id,
                style,
                goal,
                (layer.effect as? ProgressEffect)?.progress ?: 0f
            )
        )
    }

    private fun visibleLayers(state: RuntimeState, nowMs: Long): List<EffectLayer> =
        (state.layers.values + OperationPolicy().layers(state, nowMs) + IndicatorPolicy().layers(
            state,
            nowMs
        ) + PomodoroPolicy().layers(state, nowMs))
            .filter {
                (it.expiresAtMs == null || it.expiresAtMs > nowMs) &&
                    (it.key.project == null || it.key.project == state.activeProject && it.key.project in state.projects)
            }
            .sortedWith(
                compareBy(
                    { it.effect.priority },
                    { it.createdAtMs },
                    { it.key.project?.value.orEmpty() },
                    { it.key.source })
            )

    private fun forRole(layers: List<EffectLayer>, role: String): List<EffectLayer> =
        if (role == "indicator") layers.filter { it.effect.target !is EffectTarget.AllLeds } else layers

    private fun animatedVisible(layers: List<EffectLayer>, device: RgbDeviceInfo): Boolean {
        val covered = mutableSetOf<Int>()
        for (layer in layers.asReversed()) {
            val indices = EffectColorComputer.resolveIndices(layer.effect.target, device.numLeds) { zone ->
                zoneIndices(
                    device,
                    zone.zoneIndex
                )
            }
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
