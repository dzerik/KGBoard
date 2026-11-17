package com.kgboard.rgb.runtime

import com.kgboard.rgb.diagnostics.*
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
            preview.deadlineMs > nowMs && preview.owner in state.settingsSession?.owners.orEmpty() &&
                (preview.project == null || preview.project in state.projects) &&
                preview.token == inventory.token && preview.binding.let {
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

    private data class Prepared(
        val original: EffectLayer, val effect: RgbEffect?, val requested: List<Int>,
        val reasons: Set<SuppressionReason>
    ) {
        val paintable: Boolean
            get() = effect != null && requested.isNotEmpty() && reasons.all {
                it == SuppressionReason.PARTIAL_TARGET || it == SuppressionReason.AMBIGUOUS_TARGET
            }
    }

    /** Compatibility view: pixels and explanations always come from the same computation. */
    fun frames(state: RuntimeState, nowMs: Long): Map<Int, List<Color>> =
        decide(state, nowMs, RenderRevision(0)).devices.mapNotNull { it.frame }
            .associate { it.deviceIndex to it.colors }

    fun nextWakeAt(state: RuntimeState, nowMs: Long): Long? = decide(state, nowMs, RenderRevision(0)).nextWakeAtMs

    fun decide(state: RuntimeState, nowMs: Long, revision: RenderRevision): LightingDecision {
        val inventory = state.inventory()
        val candidates = candidates(state, nowMs)
        val assignments = state.bindingAssignments().associateBy { it.configured.binding.id }
        val preview = activePreview(state, nowMs)?.takeUnless { state.shuttingDown }
        var animated = false
        val decisions = state.settings.bindings.map { configured ->
            val resolution = assignments[configured.binding.id]?.resolution
                ?: inventory?.let { DeviceResolver.resolve(configured.binding, it, state.settings.untrustedSerials) }
            val resolved = resolution as? DeviceResolution.Resolved
            if (!configured.enabled || inventory == null || resolved == null) {
                val reason = when {
                    !configured.enabled -> SuppressionReason.DISABLED
                    resolution == DeviceResolution.Ambiguous -> SuppressionReason.AMBIGUOUS_DEVICE
                    resolution == DeviceResolution.ConfirmationRequired -> SuppressionReason.UNASSIGNED
                    else -> SuppressionReason.MISSING_DEVICE
                }
                val key = when {
                    !configured.enabled -> "device.disabled"
                    inventory == null -> "connection.disconnected"
                    resolution == DeviceResolution.Ambiguous -> "device.ambiguous"
                    resolution == DeviceResolution.ConfirmationRequired -> "device.unconfirmed"
                    else -> "device.missing"
                }
                DeviceDecision(configured.binding.id, resolved?.index, null, frozenList(candidates.map {
                    visibility(it, emptyList(), emptyList(), setOf(reason) + background(state, it))
                }), key)
            } else {
                val output = ResolvedDevice(
                    configured, inventory.token, resolved.index,
                    resolved.fingerprint, inventory.devices[resolved.index]
                )
                deviceDecision(state, nowMs, revision, output, candidates, preview).also {
                    animated = animated || it.second
                }.first
            }
        }.toMutableList()
        if (inventory != null && preview != null && decisions.none { it.frame?.deviceIndex == preview.device }) {
            val device = inventory.devices[preview.device]
            val output = ResolvedDevice(
                ConfiguredBinding(preview.binding), inventory.token, preview.device,
                LayoutFingerprint.of(device), device
            )
            val result = deviceDecision(state, nowMs, revision, output, candidates, preview)
            decisions.add(result.first); animated = animated || result.second
        }
        val wake = if (state.shuttingDown) null else listOfNotNull(
            state.layers.values.mapNotNull { it.expiresAtMs }.filter { it > nowMs }.minOrNull(),
            state.preview?.deadlineMs?.takeIf { it > nowMs }, OperationPolicy().nextDeadline(state, nowMs),
            IndicatorPolicy().nextDeadline(state, nowMs), PomodoroPolicy().nextWakeAt(state, nowMs),
            if (animated) nowMs + 33 else null
        ).minOrNull()
        return LightingDecision(revision, inventory?.token, frozenList(decisions), wake)
    }

    private fun deviceDecision(
        state: RuntimeState, now: Long, revision: RenderRevision, output: ResolvedDevice,
        candidates: List<EffectLayer>, preview: PreviewState?
    ): Pair<DeviceDecision, Boolean> {
        val device = output.device
        if (device.numLeds <= 0) return DeviceDecision(
            output.configured.binding.id, output.index, null,
            frozenList(candidates.map {
                visibility(
                    it,
                    emptyList(),
                    emptyList(),
                    setOf(SuppressionReason.UNASSIGNED)
                )
            }),
            "target.emptyDevice"
        ) to false
        val override = when {
            state.shuttingDown -> SuppressionReason.SHUTDOWN
            !state.settings.enabled -> SuppressionReason.DISABLED
            !state.focused && state.settings.dimOnFocusLoss -> SuppressionReason.FOCUS_DIM
            else -> null
        }
        val prepared = candidates.map { layer ->
            prepare(state, layer, output).let { value ->
                val role =
                    output.configured.role == "ambient" || output.configured.role == "indicator" && value.effect?.target is EffectTarget.AllLeds
                value.copy(
                    reasons = value.reasons + background(
                        state,
                        layer
                    ) + if (role) setOf(SuppressionReason.ROLE) else emptySet()
                )
            }
        }.sortedWith(
            compareBy(
                { it.effect?.priority ?: it.original.effect.priority }, { it.original.createdAtMs },
                { it.original.key.project?.value.orEmpty() }, { it.original.key.source })
        )
        if (override != null) {
            val rules = prepared.map { visibility(it.original, it.requested, emptyList(), it.reasons + override) }
            val key = when (override) {
                SuppressionReason.SHUTDOWN -> "integration.shuttingDown"
                SuppressionReason.DISABLED -> "integration.disabled"
                else -> "integration.unfocused"
            }
            return DeviceDecision(
                output.configured.binding.id, output.index,
                FrameEnvelope(
                    output.token,
                    output.configured.binding.id,
                    output.index,
                    revision,
                    frozenList(retiredFrame(state, device))
                ),
                frozenList(rules), key
            ) to false
        }
        val eligible = prepared.filter { it.paintable }
        val layers = eligible.map { value ->
            val effect = requireNotNull(value.effect)
            CompositionLayer(
                value.original.ruleId ?: value.original.key.source, value.original.key.project,
                effect.priority, value.original.createdAtMs, value.original.key.source, value.requested,
                EffectColorComputer.computeColors(
                    effect, now - value.original.createdAtMs, value.requested.size,
                    device.numLeds, color(state.settings.idleColor)
                )
            )
        }.toMutableList()
        val overlay = preview?.takeIf { it.device == output.index }
        if (overlay != null) layers.add(
            CompositionLayer(
                "preview", overlay.project, Int.MAX_VALUE,
                overlay.createdAtMs, "preview:${overlay.owner}", overlay.indices,
                EffectColorComputer.computeColors(
                    overlay.effect, now - overlay.createdAtMs, overlay.indices.size,
                    device.numLeds, color(state.settings.idleColor)
                ), policyOverlay = true
            )
        )
        val composed = DecisionComposer.compose(color(state.settings.idleColor), device.numLeds, layers)
        var painted = 0
        var animate = false
        val rules = prepared.map { value ->
            if (!value.paintable) visibility(value.original, value.requested, emptyList(), value.reasons)
            else {
                val visible = composed.visibility[painted++]
                if (visible.visible.isNotEmpty() && isAnimated(requireNotNull(value.effect))) animate = true
                val previewReason =
                    if (overlay != null && value.requested.any { it in overlay.indices && it !in visible.visible })
                        setOf(SuppressionReason.PREVIEW) else emptySet()
                visible.copy(reasons = frozenSet(visible.reasons + value.reasons + previewReason))
            }
        }.toMutableList()
        if (overlay != null) {
            val visible = composed.visibility.last()
            rules.add(visible)
            animate = animate || visible.visible.isNotEmpty() && isAnimated(overlay.effect)
        }
        val localReasons =
            rules.filter { SuppressionReason.BACKGROUND_PROJECT !in it.reasons }.flatMap { it.reasons }.toSet()
        val idleReason = when {
            rules.any { it.visible.isNotEmpty() } -> null
            output.configured.role == "ambient" -> "device.ambient"
            SuppressionReason.STALE_LAYOUT in localReasons -> "target.confirmLayout"
            SuppressionReason.DISABLED_RULE in localReasons -> "scenario.disabled"
            SuppressionReason.UNASSIGNED in localReasons -> "target.unassigned"
            SuppressionReason.UNKNOWN_FACTS in localReasons -> "scenario.unavailable"
            state.activeProject == null -> "diagnostics.noProject"
            state.projects[state.activeProject]?.indexing == true -> "analysis.indexing"
            state.projects[state.activeProject]?.editor?.analysis == AnalysisResult.Unknown -> "analysis.unknown"
            state.projects[state.activeProject]?.editor?.analysis == AnalysisResult.NotApplicable -> "analysis.notApplicable"
            else -> "diagnostics.noSignals"
        }
        return DeviceDecision(
            output.configured.binding.id, output.index,
            FrameEnvelope(output.token, output.configured.binding.id, output.index, revision, composed.colors),
            frozenList(rules), idleReason
        ) to animate
    }

    private fun visibility(
        layer: EffectLayer,
        requested: List<Int>,
        visible: List<Int>,
        reasons: Set<SuppressionReason>
    ) =
        RuleVisibility(
            layer.ruleId ?: layer.key.source, layer.key.project, layer.key.source,
            frozenList(requested), frozenList(visible), frozenSet(reasons)
        )

    private fun background(state: RuntimeState, layer: EffectLayer): Set<SuppressionReason> =
        if (layer.key.project != null && (layer.key.project != state.activeProject || layer.key.project !in state.projects))
            setOf(SuppressionReason.BACKGROUND_PROJECT) else emptySet()

    private fun prepare(state: RuntimeState, layer: EffectLayer, output: ResolvedDevice): Prepared {
        val id = layer.ruleId
        if (id == null) {
            val indices = EffectColorComputer.resolveIndices(layer.effect.target, output.device.numLeds) { zone ->
                zoneIndices(
                    output.device,
                    zone.zoneIndex
                )
            }
            return Prepared(
                layer,
                layer.effect,
                indices,
                if (indices.isEmpty()) setOf(SuppressionReason.UNASSIGNED) else emptySet()
            )
        }
        val descriptor = ScenarioCatalog.byId(id)
        val appearance = descriptor?.let {
            if (it.scope == SettingsScope.APP) state.settings.rules[id]
            else layer.key.project?.let { project -> state.projects[project]?.settings?.rules?.get(id) }
        } ?: return Prepared(layer, null, emptyList(), setOf(SuppressionReason.UNKNOWN_FACTS))
        val resolved = TargetResolver.resolve(appearance.target, output.device, state.groups)
        val reasons = mutableSetOf<SuppressionReason>()
        if (!appearance.enabled) reasons.add(SuppressionReason.DISABLED_RULE)
        if (resolved.status == ResolutionStatus.PARTIAL) reasons.add(SuppressionReason.PARTIAL_TARGET)
        if (resolved.status == ResolutionStatus.AMBIGUOUS) reasons.add(SuppressionReason.AMBIGUOUS_TARGET)
        if (resolved.reason == "target.confirmLayout") reasons.add(SuppressionReason.STALE_LAYOUT)
        var indices = resolved.indices
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
            )
                reasons.add(SuppressionReason.STALE_LAYOUT)
            val eligible = EffectColorComputer.resolveIndices(layer.effect.target, output.device.numLeds).toSet()
            indices = indices.filter { it in eligible }
        }
        if (indices.isEmpty() && SuppressionReason.STALE_LAYOUT !in reasons) reasons.add(SuppressionReason.UNASSIGNED)
        val goal = if (appearance.target == TargetSpec.All && layer.hintCodes == null && layer.mapping == null)
            EffectTarget.AllLeds else EffectTarget.LedSet(indices)
        val style = appearance.style.copy(
            color = layer.colorOverride ?: appearance.style.color,
            durationMs = layer.factDurationMs ?: appearance.style.durationMs
        )
        return Prepared(
            layer, ScenarioEffects.create(id, style, goal, (layer.effect as? ProgressEffect)?.progress ?: 0f),
            frozenList(indices), frozenSet(reasons)
        )
    }

    private fun candidates(state: RuntimeState, now: Long): List<EffectLayer> = buildList {
        addAll(state.layers.values)
        fun derived(snapshot: RuntimeState) {
            addAll(OperationPolicy().layers(snapshot, now))
            addAll(IndicatorPolicy().layers(snapshot, now))
            addAll(PomodoroPolicy().layers(snapshot, now))
        }
        derived(state)
        state.projects.keys.filter { it != state.activeProject }.forEach {
            // Low memory is application-wide and already belongs to the active presentation.
            derived(state.copy(activeProject = it, lowMemoryAtMs = null))
        }
    }.filter { it.expiresAtMs == null || it.expiresAtMs > now }

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
