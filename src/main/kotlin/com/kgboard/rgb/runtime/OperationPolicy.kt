package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*

internal fun target(csv: String): EffectTarget = if (csv.isBlank()) EffectTarget.AllLeds else
    EffectTarget.LedSet(runCatching { com.kgboard.rgb.settings.SettingsValidation.indices(csv) }.getOrDefault(emptyList()))

/** Derive operation layers; never destroy a lower-priority operation on completion. */
class OperationPolicy {
    fun layers(state: RuntimeState, nowMs: Long): List<EffectLayer> {
        val projectId = state.activeProject ?: return emptyList()
        val project = state.projects[projectId] ?: return emptyList()
        val settings = state.settings
        val testParents = project.operations.values.filter { it.kind == OperationKind.TEST }.mapNotNull { it.parent }.toSet()
        return project.operations.values.mapNotNull { op ->
            if (op.id in testParents && op.kind == OperationKind.RUN) return@mapNotNull null
            val terminal = op.phase == OperationPhase.TERMINATED
            val expiry = if (terminal) op.changedAtMs + settings.effectDurationMs.coerceAtLeast(0) else null
            if (expiry != null && expiry <= nowMs) return@mapNotNull null
            val name = "operation:${op.id.value}"
            val target = target(when (op.kind) {
                OperationKind.BUILD -> settings.buildLedIndices
                OperationKind.TEST -> settings.testLedIndices
                else -> settings.executionLedIndices
            })
            fun color(raw: String) = LightingPolicy.color(raw)
            val effect: RgbEffect? = if (terminal) when {
                op.outcome == OperationOutcome.FAILURE -> StaticEffect(color(when (op.kind) {
                    OperationKind.BUILD -> settings.buildFailureColor
                    OperationKind.TEST -> settings.testFailColor
                    else -> settings.stopColor
                }), name, if (op.kind == OperationKind.BUILD) 9 else 8, target)
                op.outcome == OperationOutcome.SUCCESS && (op.kind == OperationKind.TEST || op.kind == OperationKind.BUILD) ->
                    FlashEffect(color(if (op.kind == OperationKind.BUILD) settings.buildSuccessColor else settings.testPassColor),
                        settings.effectDurationMs, name, 7, target)
                else -> null
            } else when (op.kind) {
                OperationKind.RUN -> StaticEffect(color(settings.runColor), name, 4, target)
                OperationKind.DEBUG -> if (op.phase == OperationPhase.PAUSED && settings.debugBreakpointFlashEnabled)
                    StaticEffect(color(settings.debugBreakpointFlashColor), name, 8, target(settings.debugBreakpointLedIndices))
                    else PulseEffect(color(settings.debugColor), periodMs = settings.pulseSpeedMs, name = name, priority = 6, target = target)
                OperationKind.TEST -> PulseEffect(color(settings.testRunningColor), periodMs = settings.pulseSpeedMs, name = name, priority = 6, target = target)
                OperationKind.BUILD -> PulseEffect(color(settings.buildInProgressColor), periodMs = settings.pulseSpeedMs, name = name, priority = 5, target = target)
            }
            effect?.let { EffectLayer(EffectKey(projectId, name), it, op.changedAtMs, expiry) }
        }
    }
    fun nextDeadline(state: RuntimeState, nowMs: Long): Long? = state.projects.values.flatMap { it.operations.values }
        .filter { it.phase == OperationPhase.TERMINATED }.map { it.changedAtMs + state.settings.effectDurationMs.coerceAtLeast(0) }
        .filter { it > nowMs }.minOrNull()
}
