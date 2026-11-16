package com.kgboard.rgb.runtime

import com.kgboard.rgb.device.DeviceChoice
import com.kgboard.rgb.device.LayoutFingerprint
import com.kgboard.rgb.settings.PreviewContent
import com.kgboard.rgb.settings.SettingsPreviewSession
import com.kgboard.rgb.target.TargetSpec
import java.awt.Color

/** A test's explicit rendered selection; stale-choice tests keep this value across transitions. */
internal fun RuntimeState.capturedChoice(index: Int = 0): DeviceChoice {
    val inventory = requireNotNull(inventory())
    return DeviceChoice(inventory.token, index, LayoutFingerprint.of(inventory.devices[index]))
}

internal fun SettingsPreviewSession.previewColor(
    state: RuntimeState, index: Int, color: Color,
    indices: List<Int> = emptyList()
) {
    val choice = state.capturedChoice(index)
    val target = if (indices.isEmpty()) TargetSpec.All else
        TargetSpec.RawIndices(indices.joinToString(","), choice.fingerprint)
    preview(choice, PreviewContent.ColorSample(color, target))
}
