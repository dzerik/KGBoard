package com.kgboard.rgb.layout

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.client.RgbZoneInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.event.KeyEvent

class KeyboardLayoutServiceTest {

    private lateinit var service: KeyboardLayoutService

    @BeforeEach
    fun setUp() {
        service = KeyboardLayoutService()
    }

    private fun deviceWithLeds(vararg ledNames: String): RgbDeviceInfo {
        return RgbDeviceInfo(
            type = 0,
            name = "TestKeyboard",
            vendor = "Test",
            description = "Test device",
            numLeds = ledNames.size,
            ledNames = ledNames.toList(),
            zones = listOf(RgbZoneInfo("Zone1", 0, ledNames.size)),
            colors = ledNames.map { Color.BLACK }
        )
    }

    // ── isAvailable ──

    @Test
    fun `isAvailable false before refresh`() {
        assertFalse(service.isAvailable)
    }

    @Test
    fun `isAvailable true after refresh with key LEDs`() {
        service.refreshForDevice(deviceWithLeds("Key: A", "Key: B"))
        assertTrue(service.isAvailable)
    }

    @Test
    fun `isAvailable false with no key LEDs`() {
        service.refreshForDevice(deviceWithLeds("LED 0", "LED 1"))
        assertFalse(service.isAvailable)
    }

    // ── getLedIndex ──

    @Test
    fun `getLedIndex finds key by name`() {
        service.refreshForDevice(deviceWithLeds("Key: A", "Key: B", "Key: C"))
        assertEquals(0, service.getLedIndex("A"))
        assertEquals(1, service.getLedIndex("B"))
        assertEquals(2, service.getLedIndex("C"))
    }

    @Test
    fun `getLedIndex is case-insensitive`() {
        service.refreshForDevice(deviceWithLeds("Key: A"))
        assertEquals(0, service.getLedIndex("a"))
        assertEquals(0, service.getLedIndex("A"))
    }

    @Test
    fun `getLedIndex returns null for missing key`() {
        service.refreshForDevice(deviceWithLeds("Key: A"))
        assertNull(service.getLedIndex("Z"))
    }

    // ── getLedIndexForKeyCode ──

    @Test
    fun `getLedIndexForKeyCode maps letters`() {
        service.refreshForDevice(deviceWithLeds("Key: A", "Key: B"))
        assertEquals(0, service.getLedIndexForKeyCode(KeyEvent.VK_A))
        assertEquals(1, service.getLedIndexForKeyCode(KeyEvent.VK_B))
    }

    @Test
    fun `getLedIndexForKeyCode maps Enter`() {
        service.refreshForDevice(deviceWithLeds("Key: Enter"))
        assertEquals(0, service.getLedIndexForKeyCode(KeyEvent.VK_ENTER))
    }

    @Test
    fun `getLedIndexForKeyCode returns null for unmapped code`() {
        service.refreshForDevice(deviceWithLeds("Key: A"))
        assertNull(service.getLedIndexForKeyCode(KeyEvent.VK_F24))
    }

    // ── getLedIndices ──

    @Test
    fun `getLedIndices returns multiple mapped indices`() {
        service.refreshForDevice(deviceWithLeds("Key: A", "Key: B", "Key: C"))
        val indices = service.getLedIndices(listOf("A", "C"))
        assertEquals(listOf(0, 2), indices)
    }

    @Test
    fun `getLedIndices skips unmapped keys`() {
        service.refreshForDevice(deviceWithLeds("Key: A"))
        val indices = service.getLedIndices(listOf("A", "Z"))
        assertEquals(listOf(0), indices)
    }

    // ── getAllMappings ──

    @Test
    fun `getAllMappings returns all key mappings`() {
        service.refreshForDevice(deviceWithLeds("Key: Space", "Key: Enter", "LED 0"))
        val mappings = service.getAllMappings()
        assertEquals(2, mappings.size) // only "Key: " prefixed
        assertTrue("SPACE" in mappings)
        assertTrue("ENTER" in mappings)
    }

    // ── keyNameToKeyCode ──

    @Test
    fun `keyNameToKeyCode maps letters A-Z`() {
        assertEquals(KeyEvent.VK_A, KeyboardLayoutService.keyNameToKeyCode("A"))
        assertEquals(KeyEvent.VK_Z, KeyboardLayoutService.keyNameToKeyCode("Z"))
    }

    @Test
    fun `keyNameToKeyCode maps digits 0-9`() {
        assertEquals(KeyEvent.VK_0, KeyboardLayoutService.keyNameToKeyCode("0"))
        assertEquals(KeyEvent.VK_9, KeyboardLayoutService.keyNameToKeyCode("9"))
    }

    @Test
    fun `keyNameToKeyCode maps F-keys`() {
        assertEquals(KeyEvent.VK_F1, KeyboardLayoutService.keyNameToKeyCode("F1"))
        assertEquals(KeyEvent.VK_F12, KeyboardLayoutService.keyNameToKeyCode("F12"))
    }

    @Test
    fun `keyNameToKeyCode maps navigation keys`() {
        assertEquals(KeyEvent.VK_ENTER, KeyboardLayoutService.keyNameToKeyCode("ENTER"))
        assertEquals(KeyEvent.VK_SPACE, KeyboardLayoutService.keyNameToKeyCode("SPACE"))
        assertEquals(KeyEvent.VK_ESCAPE, KeyboardLayoutService.keyNameToKeyCode("ESCAPE"))
        assertEquals(KeyEvent.VK_TAB, KeyboardLayoutService.keyNameToKeyCode("TAB"))
    }

    @Test
    fun `keyNameToKeyCode returns null for unknown`() {
        assertNull(KeyboardLayoutService.keyNameToKeyCode("UNKNOWN_KEY"))
    }

    // ── normalizeKeyName (tested via getLedIndex) ──

    @Test
    fun `Left Shift normalized correctly`() {
        service.refreshForDevice(deviceWithLeds("Key: Left Shift"))
        // "Left Shift" -> uppercase "LEFT SHIFT" -> replace "LEFT " -> "LSHIFT"
        assertEquals(0, service.getLedIndex("Left Shift"))
    }

    @Test
    fun `Number Pad keys normalized`() {
        service.refreshForDevice(deviceWithLeds("Key: Number Pad 5"))
        assertEquals(0, service.getLedIndex("Number Pad 5"))
    }
}
