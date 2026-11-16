package com.kgboard.rgb.settings

import com.kgboard.rgb.device.*
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.*
import java.awt.Color

sealed interface PreviewContent {
    data object DeviceTest : PreviewContent
    data class Scenario(val ruleId: String, val appearance: RuleAppearance) : PreviewContent
    data class ColorSample(
        val color: Color, val target: TargetSpec,
        val channel: BrightnessChannel = BrightnessChannel.PERSISTENT
    ) : PreviewContent
}

data class PreviewRequest(
    val owner: Long, val choice: DeviceChoice, val content: PreviewContent,
    val groups: Map<String, GroupDefinition>? = null
)

data class PreviewAvailability(val allowed: Boolean, val reasonKey: String?) {
    companion object {
        fun evaluate(state: RuntimeState, request: PreviewRequest): PreviewAvailability {
            fun no(reason: String) = PreviewAvailability(false, reason)
            val owner = state.settingsSession?.owners?.get(request.owner) ?: return no("preview.settingsClosed")
            if (state.shuttingDown) return no("preview.shuttingDown")
            if (!state.settings.enabled) return no("preview.applyEnableFirst")
            if (owner.scope == SettingsScope.PROJECT && owner.project !in state.projects) return no("preview.projectClosed")
            val inventory = state.inventory() ?: return no("preview.notConnected")
            if (inventory.token != request.choice.token) return no("preview.staleDevice")
            val device = inventory.devices.getOrNull(request.choice.index) ?: return no("preview.selectDevice")
            if (device.numLeds <= 0) return no("preview.emptyDevice")
            if (LayoutFingerprint.of(device) != request.choice.fingerprint) return no("preview.staleLayout")
            val binding = DeviceResolver.bind(
                "preview:${request.owner}",
                request.choice,
                inventory,
                state.settings.untrustedSerials
            )
            if (DeviceResolver.resolve(
                    binding,
                    inventory,
                    state.settings.untrustedSerials
                ) !is DeviceResolution.Resolved
            )
                return no("preview.ambiguousDevice")
            val target = when (val content = request.content) {
                PreviewContent.DeviceTest -> TargetSpec.All
                is PreviewContent.ColorSample -> content.target
                is PreviewContent.Scenario -> {
                    val descriptor = ScenarioCatalog.byId(content.ruleId) ?: return no("preview.unknownScenario")
                    if (descriptor.scope != owner.scope) return no("preview.wrongScope")
                    if (content.appearance.style.durationMs < 0 || content.appearance.style.periodMs <= 0)
                        return no("preview.invalidTiming")
                    if (content.appearance.style.effect == EffectStyle.FLASH && content.appearance.style.durationMs == 0L)
                        return no("preview.emptyDuration")
                    content.appearance.target
                }
            }
            val resolved = TargetResolver.resolve(target, device, request.groups ?: state.groups)
            if (resolved.indices.isEmpty()) return no(resolved.reason ?: "preview.selectTarget")
            return PreviewAvailability(true, null)
        }
    }
}

internal fun PreviewRequest.frozen(): PreviewRequest {
    fun target(value: TargetSpec) = TargetRecord.from(value).toSpec()!!
    val content = when (val value = content) {
        PreviewContent.DeviceTest -> value
        is PreviewContent.Scenario -> value.copy(appearance = value.appearance.copy(target = target(value.appearance.target)))
        is PreviewContent.ColorSample -> value.copy(target = target(value.target))
    }
    return copy(content = content, groups = groups?.let { values ->
        frozenMap(values.mapValues { (_, group) ->
            group.copy(target = target(group.target))
        })
    })
}
