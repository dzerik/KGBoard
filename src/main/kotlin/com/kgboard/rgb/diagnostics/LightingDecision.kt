package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.device.InventoryToken
import com.kgboard.rgb.runtime.ProjectId
import java.awt.Color

data class RenderRevision(val value: Long)
data class FrameEnvelope(
    val token: InventoryToken, val bindingId: String, val deviceIndex: Int,
    val revision: RenderRevision, val colors: List<Color>
)

enum class SuppressionReason {
    COVERED, BACKGROUND_PROJECT, DISABLED_RULE, UNASSIGNED, MISSING_DEVICE, AMBIGUOUS_DEVICE,
    STALE_LAYOUT, ROLE, PREVIEW, FOCUS_DIM, DISABLED, SHUTDOWN, UNKNOWN_FACTS,
    PARTIAL_TARGET, AMBIGUOUS_TARGET
}

data class RuleVisibility(
    val ruleId: String, val owner: ProjectId?, val source: String,
    val requested: List<Int>, val visible: List<Int>, val reasons: Set<SuppressionReason>
)

data class DeviceDecision(
    val bindingId: String, val deviceIndex: Int?, val frame: FrameEnvelope?,
    val rules: List<RuleVisibility>, val idleReasonKey: String?
)

data class LightingDecision(
    val revision: RenderRevision, val token: InventoryToken?,
    val devices: List<DeviceDecision>, val nextWakeAtMs: Long?
)

data class CompositionLayer(
    val ruleId: String, val owner: ProjectId?, val priority: Int,
    val createdAtMs: Long, val source: String, val indices: List<Int>, val colors: List<Color>,
    val policyOverlay: Boolean = false
)

data class ComposedPixels(val colors: List<Color>, val visibility: List<RuleVisibility>)
