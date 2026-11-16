package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*
import com.kgboard.rgb.scenario.*

internal fun expiryAt(start: Long, duration: Long): Long = try {
    Math.addExact(start, duration.coerceAtLeast(0))
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

/** Operation facts survive visual flag changes; rules gate each state independently. */
class OperationPolicy {
    fun ruleId(op: OperationState, settings: RuntimeSettings): String {
        val group = op.kind.name.lowercase(java.util.Locale.ROOT)
        if (op.phase == OperationPhase.TERMINATED) return "$group.${
            when (op.outcome) {
                OperationOutcome.SUCCESS -> "success"; OperationOutcome.FAILURE -> "failure"
                OperationOutcome.CANCELLED -> "cancelled"; else -> "notExecuted"
            }
        }"
        if (op.kind == OperationKind.DEBUG && op.phase == OperationPhase.PAUSED && settings.rules["debug.breakpoint"]?.enabled == true)
            return "debug.breakpoint"
        return "$group.${if (op.kind in listOf(OperationKind.BUILD, OperationKind.TEST)) "progress" else "active"}"
    }

    fun deadline(op: OperationState, settings: RuntimeSettings): Long? = if (op.phase != OperationPhase.TERMINATED) null
    else expiryAt(op.changedAtMs, settings.rules[ruleId(op, settings)]?.style?.durationMs ?: settings.effectDurationMs)

    fun layers(state: RuntimeState, nowMs: Long): List<EffectLayer> {
        val id = state.activeProject ?: return emptyList()
        val project = state.projects[id] ?: return emptyList()
        val parents =
            project.operations.values.filter { it.kind == OperationKind.TEST }.mapNotNull { it.parent }.toSet()
        return project.operations.values.mapNotNull { op ->
            if (op.id in parents && op.kind == OperationKind.RUN) return@mapNotNull null
            val deadline = deadline(op, state.settings)
            if (deadline != null && deadline <= nowMs) return@mapNotNull null
            val ruleId = ruleId(op, state.settings)
            val appearance = state.settings.rules[ruleId] ?: return@mapNotNull null
            EffectLayer(
                EffectKey(id, "operation:${op.id.value}"),
                ScenarioEffects.create(ruleId, appearance.style, EffectTarget.AllLeds),
                op.changedAtMs,
                deadline,
                ruleId
            )
        }
    }

    fun nextDeadline(state: RuntimeState, nowMs: Long): Long? = state.projects.values.flatMap { it.operations.values }
        .mapNotNull { deadline(it, state.settings) }.filter { it > nowMs }.minOrNull()
}
