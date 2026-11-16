package com.kgboard.rgb.device

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.runtime.Endpoint
import com.kgboard.rgb.runtime.SessionId

data class InventoryToken(val session: SessionId, val revision: Long, val epoch: String = "")
data class InventorySnapshot(val token: InventoryToken, val endpoint: Endpoint, val devices: List<RgbDeviceInfo>)

sealed interface DeviceSelector {
    data class Serial(val type: Int, val vendor: String, val serial: String) : DeviceSelector
    data class Port(val type: Int, val vendor: String, val name: String, val location: String) : DeviceSelector
    data class SessionOnly(val token: InventoryToken, val index: Int) : DeviceSelector
}

data class DeviceBinding(val id: String, val endpoint: Endpoint, val selector: DeviceSelector)
data class ConfiguredBinding(val binding: DeviceBinding, val enabled: Boolean = true, val role: String = "primary")
sealed interface DeviceResolution {
    data class Resolved(val index: Int, val fingerprint: String) : DeviceResolution
    data object Missing : DeviceResolution
    data object Ambiguous : DeviceResolution
    data object ConfirmationRequired : DeviceResolution
}

data class DeviceChoice(val token: InventoryToken, val index: Int, val fingerprint: String)
data class SerialKey(val type: Int, val vendor: String, val serial: String)
