package com.kgboard.rgb.device

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.runtime.Endpoint
import com.kgboard.rgb.runtime.SessionId
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceResolverTest {
    private val endpoint = Endpoint("localhost", 6742)
    private val token = InventoryToken(SessionId(1), 1)
    private fun device(serial: String = "stable", location: String = "usb1", name: String = "Board") =
        RgbDeviceInfo(1, name, "Vendor", "", 2, listOf("Esc", "A"), emptyList(), emptyList(),
            serial = serial, location = location)
    private fun inventory(vararg devices: RgbDeviceInfo) = InventorySnapshot(token, endpoint, devices.toList())
    private fun choice(snapshot: InventorySnapshot, index: Int = 0) =
        DeviceChoice(snapshot.token, index, LayoutFingerprint.of(snapshot.devices[index]))

    @Test fun `duplicate serial is ambiguous and remembered taint prevents later promotion`() {
        val inventory = inventory(device(), device(location = "usb2"))
        val binding = DeviceBinding("b", endpoint, DeviceSelector.Serial(1, "Vendor", "stable"))
        assertEquals(DeviceResolution.Ambiguous, DeviceResolver.resolve(binding, inventory, emptySet()))
        val taint = DeviceResolver.duplicates(inventory.devices)
        assertEquals(setOf(SerialKey(1, "Vendor", "stable")), taint)
        assertEquals(DeviceResolution.ConfirmationRequired,
            DeviceResolver.resolve(binding, inventory.copy(devices = listOf(device())), taint))
        assertTrue(DeviceResolver.bind("b", choice(inventory), inventory, taint).selector is DeviceSelector.Port)
    }

    @Test fun `serial identity follows reorder and rename but not endpoint change`() {
        val inventory = inventory(device(serial = "other"), device(name = "Renamed"))
        val binding = DeviceBinding("b", endpoint, DeviceSelector.Serial(1, "Vendor", "stable"))
        assertEquals(1, (DeviceResolver.resolve(binding, inventory, emptySet()) as DeviceResolution.Resolved).index)
        assertEquals(DeviceResolution.ConfirmationRequired, DeviceResolver.resolve(binding,
            inventory.copy(endpoint = endpoint.copy(host = "other")), emptySet()))
        assertEquals(DeviceResolution.Missing, DeviceResolver.resolve(binding,
            inventory.copy(devices = listOf(device(serial = "other"))), emptySet()))
    }

    @Test fun `fake serial chooses port and no metadata requires session confirmation`() {
        for (serial in listOf("", "unknown", "none", "n/a", "00000000", "  UNKNOWN  ")) {
            val inventory = inventory(device(serial = serial))
            val binding = DeviceResolver.bind("b", choice(inventory), inventory, emptySet())
            assertTrue(binding.selector is DeviceSelector.Port, serial)
            assertEquals(DeviceResolution.Missing, DeviceResolver.resolve(binding,
                inventory.copy(devices = listOf(device(serial = serial, location = "usb2"))), emptySet()))
        }
        val inventory = inventory(device(serial = "", location = ""))
        val binding = DeviceResolver.bind("b", choice(inventory), inventory, emptySet())
        assertTrue(binding.selector is DeviceSelector.SessionOnly)
        assertEquals(DeviceResolution.ConfirmationRequired, DeviceResolver.resolve(binding,
            inventory.copy(token = InventoryToken(SessionId(2), 1)), emptySet()))
        assertTrue(DeviceResolver.resolve(binding, inventory, emptySet()) is DeviceResolution.Resolved)
    }

    @Test fun `indistinguishable metadata never gains ordinal identity`() {
        val inventory = inventory(device(serial = ""), device(serial = ""))
        val binding = DeviceResolver.bind("b", choice(inventory), inventory, emptySet())
        assertEquals(DeviceResolution.Ambiguous, DeviceResolver.resolve(binding, inventory, emptySet()))
    }

    @Test fun `choice must match rendered token and ordered layout`() {
        val inventory = inventory(device())
        val selected = choice(inventory)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceResolver.bind("b", selected, inventory.copy(token = token.copy(revision = 2)), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceResolver.bind("b", selected, inventory.copy(devices = listOf(
                device().copy(ledNames = listOf("A", "Esc")))) , emptySet())
        }
    }

    @Test fun `fingerprint excludes colors but includes ordered names and zone geometry`() {
        val device = device()
        assertEquals(LayoutFingerprint.of(device), LayoutFingerprint.of(device.copy(colors = listOf(Color.RED, Color.BLUE))))
        assertNotEquals(LayoutFingerprint.of(device), LayoutFingerprint.of(device.copy(ledNames = listOf("A", "Esc"))))
        assertNotEquals(LayoutFingerprint.of(device), LayoutFingerprint.of(device.copy(zones = listOf(
            com.kgboard.rgb.client.RgbZoneInfo("keys", 2, 2, 1, 2, listOf(0, 1))))))
    }
}
