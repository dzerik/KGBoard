package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.device.*

data class BindingAssignment(val configured: ConfiguredBinding, val resolution: DeviceResolution)
data class ResolvedDevice(
    val configured: ConfiguredBinding, val token: InventoryToken,
    val index: Int, val fingerprint: String, val device: RgbDeviceInfo
)

fun RuntimeState.inventory(): InventorySnapshot? = (connection as? ConnectionState.Ready)?.let {
    InventorySnapshot(InventoryToken(it.session, it.inventoryRevision, connectionEpoch), endpoint, it.devices)
}

fun RuntimeState.bindingAssignments(): List<BindingAssignment> {
    val inventory = inventory() ?: return emptyList()
    val assignments = settings.bindings.filter { it.enabled }.map { configured ->
        BindingAssignment(
            configured, if (configured.role !in setOf("primary", "mirror", "indicator", "ambient"))
                DeviceResolution.ConfirmationRequired else DeviceResolver.resolve(
                configured.binding,
                inventory,
                settings.untrustedSerials
            )
        )
    }
    val counts =
        assignments.mapNotNull { (it.resolution as? DeviceResolution.Resolved)?.index }.groupingBy { it }.eachCount()
    return assignments.map { assignment ->
        if ((assignment.resolution as? DeviceResolution.Resolved)?.index?.let { counts[it]!! > 1 } == true)
            assignment.copy(resolution = DeviceResolution.Ambiguous) else assignment
    }
}

fun RuntimeState.resolvedDevices(): List<ResolvedDevice> {
    val inventory = inventory() ?: return emptyList()
    return bindingAssignments().mapNotNull { assignment ->
        (assignment.resolution as? DeviceResolution.Resolved)?.let {
            ResolvedDevice(
                assignment.configured,
                inventory.token,
                it.index,
                it.fingerprint,
                inventory.devices[it.index]
            )
        }
    }
}

fun RuntimeState.primaryDevice(): ResolvedDevice? = resolvedDevices().let { devices ->
    devices.firstOrNull { it.configured.role == "primary" } ?: devices.firstOrNull { it.configured.role != "ambient" }
}

fun RuntimeState.primaryMapping(): DeviceMappingRevision? = primaryDevice()?.let {
    DeviceMappingRevision(it.token, it.configured.binding.id, it.index, it.fingerprint)
}
