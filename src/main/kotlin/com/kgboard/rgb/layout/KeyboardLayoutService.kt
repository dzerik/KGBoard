package com.kgboard.rgb.layout

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.event.KeyEvent

/**
 * Maps key names to LED indices using OpenRGB device LED names.
 *
 * OpenRGB exposes LED names like "Key: A", "Key: Enter", "Key: Space", etc.
 * This service parses those names and provides lookups by:
 * - Key name string (e.g., "A", "Enter")
 * - Java KeyEvent keyCode (e.g., KeyEvent.VK_A)
 * - Action ID → resolved keystroke → LED indices
 */
@Service(Service.Level.APP)
class KeyboardLayoutService : Disposable {

    private val log = Logger.getInstance(KeyboardLayoutService::class.java)

    data class MappingSnapshot(
        val ledNames: Map<String, Int> = emptyMap(), val keyNames: Map<String, Int> = emptyMap(),
        val keyCodes: Map<Int, Int> = emptyMap(), val revision: DeviceMappingRevision? = null,
        val device: RgbDeviceInfo? = null
    )

    @Volatile
    private var mappings = MappingSnapshot()
    fun snapshot(): MappingSnapshot = mappings
    val isAvailable: Boolean get() = mappings.keyNames.isNotEmpty()

    fun refresh() {
        val state = LightingRuntimeService.getInstance().snapshot()
        val resolved = state.primaryDevice()
        if (resolved == null) clear() else buildMappings(resolved.device, state.primaryMapping())
    }

    fun refreshForDevice(device: RgbDeviceInfo, revision: DeviceMappingRevision? = null) {
        buildMappings(device, revision)
    }

    /** Get LED index for a key name like "A", "Enter", "F5", "Space" */
    fun getLedIndex(keyName: String): Int? {
        return mappings.keyNames[normalizeKeyName(keyName)]
    }

    /** Get LED index for a Java VK_ key code */
    fun getLedIndexForKeyCode(keyCode: Int): Int? {
        return mappings.keyCodes[keyCode]
    }

    /** Get LED indices for multiple key names */
    fun getLedIndices(keyNames: List<String>): List<Int> {
        val snapshot = mappings
        return keyNames.mapNotNull { snapshot.keyNames[normalizeKeyName(it)] }
    }

    /** Get LED indices for multiple key codes */
    fun getLedIndicesForKeyCodes(keyCodes: List<Int>): List<Int> {
        val snapshot = mappings
        return keyCodes.mapNotNull { snapshot.keyCodes[it] }
    }

    /** Get all known LED name → index pairs (for debugging/UI) */
    fun getAllMappings(): Map<String, Int> = mappings.keyNames

    private fun buildMappings(device: RgbDeviceInfo, revision: DeviceMappingRevision?) {
        val keys = DeviceKeyMapping.build(device)
        val nameToIdx = keys.ledNames
        val keyToIdx = keys.keyNames
        val codeToIdx = keys.keyCodes
        val geometry = device.copy(
            ledNames = frozenList(device.ledNames),
            displayNames = frozenList(device.displayNames), colors = frozenList(device.colors),
            zones = frozenList(device.zones.map { it.copy(matrixMap = it.matrixMap?.let(::frozenList)) })
        )
        mappings = MappingSnapshot(frozenMap(nameToIdx), frozenMap(keyToIdx), frozenMap(codeToIdx), revision, geometry)

        log.info("Keyboard layout: ${keyToIdx.size} keys mapped from ${device.ledNames.size} LEDs (device: ${device.name})")
    }

    private fun extractKeyName(ledName: String): String? {
        // OpenRGB formats: "Key: A", "Key: Enter", "Key: Space", etc.
        val prefix = "Key: "
        return if (ledName.startsWith(prefix)) {
            ledName.substring(prefix.length).trim()
        } else null
    }

    private fun normalizeKeyName(name: String): String = KeyNames.normalize(name)

    fun clear() {
        mappings = MappingSnapshot()
    }

    override fun dispose() {
        clear()
    }

    companion object {
        fun getInstance(): KeyboardLayoutService =
            ApplicationManager.getApplication().getService(KeyboardLayoutService::class.java)

        /**
         * Map common key names to Java VK_ key codes.
         */
        fun keyNameToKeyCode(name: String): Int? {
            return KeyCodes.forName(name)
        }


    }
}
