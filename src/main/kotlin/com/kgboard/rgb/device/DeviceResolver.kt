package com.kgboard.rgb.device

import com.kgboard.rgb.client.RgbDeviceInfo
import java.util.Locale

/** Resolves a saved identity within one endpoint/inventory; never falls back to an index. */
object DeviceResolver {
    private val fakeSerials = setOf("unknown", "none", "n/a", "null", "not available", "default")
    private fun trusted(serial: String): Boolean {
        val value = serial.trim().lowercase(Locale.ROOT)
        return value.isNotEmpty() && value !in fakeSerials && value.any { it != '0' }
    }
    private fun key(device: RgbDeviceInfo) = SerialKey(device.type, device.vendor, device.serial.trim())

    fun duplicates(devices: List<RgbDeviceInfo>): Set<SerialKey> = devices
        .filter { trusted(it.serial) }.groupingBy(::key).eachCount().filterValues { it > 1 }.keys.toSet()

    fun resolve(binding: DeviceBinding, inventory: InventorySnapshot,
                untrusted: Set<SerialKey>): DeviceResolution {
        if (binding.endpoint != inventory.endpoint) return DeviceResolution.ConfirmationRequired
        val matches = when (val selector = binding.selector) {
            is DeviceSelector.Serial -> {
                val identity = SerialKey(selector.type, selector.vendor, selector.serial.trim())
                val candidates = inventory.devices.indices.filter { key(inventory.devices[it]) == identity }
                if (candidates.size > 1) return DeviceResolution.Ambiguous
                if (!trusted(selector.serial) || identity in untrusted) return DeviceResolution.ConfirmationRequired
                candidates
            }
            is DeviceSelector.Port -> {
                if (selector.location.isBlank()) return DeviceResolution.ConfirmationRequired
                inventory.devices.indices.filter { i -> inventory.devices[i].let {
                    it.type == selector.type && it.vendor == selector.vendor && it.name == selector.name &&
                        it.location == selector.location
                } }
            }
            is DeviceSelector.SessionOnly -> {
                if (selector.token != inventory.token) return DeviceResolution.ConfirmationRequired
                listOf(selector.index).filter { it in inventory.devices.indices }
            }
        }
        return when (matches.size) {
            0 -> DeviceResolution.Missing
            1 -> DeviceResolution.Resolved(matches.single(), LayoutFingerprint.of(inventory.devices[matches.single()]))
            else -> DeviceResolution.Ambiguous
        }
    }

    fun bind(id: String, choice: DeviceChoice, inventory: InventorySnapshot,
             untrusted: Set<SerialKey>): DeviceBinding {
        require(id.isNotBlank()) { "Binding ID is required" }
        require(choice.token == inventory.token) { "Device list changed; select the device again" }
        val device = inventory.devices.getOrNull(choice.index)
        require(device != null && LayoutFingerprint.of(device) == choice.fingerprint) {
            "Device layout changed; select the device again"
        }
        val key = key(device)
        val selector = when {
            trusted(device.serial) && key !in untrusted && key !in duplicates(inventory.devices) ->
                DeviceSelector.Serial(device.type, device.vendor, device.serial.trim())
            device.location.isNotBlank() -> DeviceSelector.Port(device.type, device.vendor, device.name, device.location)
            else -> DeviceSelector.SessionOnly(inventory.token, choice.index)
        }
        return DeviceBinding(id, inventory.endpoint, selector)
    }
}
