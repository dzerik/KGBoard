package com.kgboard.rgb.runtime

import com.kgboard.rgb.device.*
import com.kgboard.rgb.effect.RgbEffect
import com.kgboard.rgb.scenario.SettingsScope
import com.kgboard.rgb.settings.PreviewContent

/** UI pages own independent previews/drafts and share the original connection intent. */
data class SettingsOwner(val scope: SettingsScope, val project: ProjectId? = null)
data class SettingsSessionState(
    val owners: Map<Long, SettingsOwner>, val endpoint: Endpoint,
    val wanted: Boolean, val manuallyDisconnected: Boolean, val connectionOwner: Long? = null
)

data class PreviewState(
    val owner: Long, val device: Int, val indices: List<Int>,
    val createdAtMs: Long, val deadlineMs: Long, val binding: DeviceBinding, val token: InventoryToken,
    val content: PreviewContent, val effect: RgbEffect, val project: ProjectId? = null,
    val deadline: RuntimeDeadline? = null
)

internal fun settingsTransition(state: RuntimeState, event: RuntimeEvent, now: Long): Transition =
    SettingsSessionReducer.reduce(state, event, now)
