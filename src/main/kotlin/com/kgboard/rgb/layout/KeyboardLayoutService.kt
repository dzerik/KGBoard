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

    data class MappingSnapshot(val ledNames: Map<String, Int> = emptyMap(), val keyNames: Map<String, Int> = emptyMap(),
        val keyCodes: Map<Int, Int> = emptyMap(), val revision: DeviceMappingRevision? = null,
        val device: RgbDeviceInfo? = null)
    @Volatile private var mappings = MappingSnapshot()
    fun snapshot(): MappingSnapshot = mappings
    val isAvailable: Boolean get() = mappings.keyNames.isNotEmpty()

    fun refresh() {
        val state = LightingRuntimeService.getInstance().snapshot()
        val ready = state.connection as? ConnectionState.Ready
        val device = ready?.devices?.getOrNull(state.settings.deviceIndex)
        if (device == null) clear() else buildMappings(device, DeviceMappingRevision(ready.session, state.settings.deviceIndex))
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
        val raw = device.ledNames.withIndex().groupBy({ it.value }, { it.index })
        val keys = mutableMapOf<String, MutableSet<Int>>()
        val codes = mutableMapOf<Int, MutableSet<Int>>()
        device.ledNames.forEachIndexed { index, ledName ->
            val keyName = extractKeyName(ledName) ?: return@forEachIndexed
            val normalized = normalizeKeyName(keyName)
            keys.getOrPut(normalized) { linkedSetOf() }.add(index)
            keyNameToKeyCode(normalized)?.let { code -> codes.getOrPut(code) { linkedSetOf() }.add(index) }
        }
        val nameToIdx = raw.mapNotNull { (name, indices) -> indices.singleOrNull()?.let { name to it } }.toMap()
        val keyToIdx = keys.mapNotNull { (name, indices) -> indices.singleOrNull()?.let { name to it } }.toMap()
        val codeToIdx = codes.mapNotNull { (code, indices) -> indices.singleOrNull()?.let { code to it } }.toMap()
        val geometry = device.copy(ledNames = frozenList(device.ledNames),
            displayNames = frozenList(device.displayNames), colors = frozenList(device.colors),
            zones = frozenList(device.zones.map { it.copy(matrixMap = it.matrixMap?.let(::frozenList)) }))
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

    fun clear() { mappings = MappingSnapshot() }

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
            return KEY_NAME_TO_VK[name]
        }

        private val KEY_NAME_TO_VK = buildMap {
            // Letters
            ('A'..'Z').forEach { put(it.toString(), KeyEvent.VK_A + (it - 'A')) }
            // Digits
            ('0'..'9').forEach { put(it.toString(), KeyEvent.VK_0 + (it - '0')) }
            // F-keys
            (1..12).forEach { put("F$it", KeyEvent.VK_F1 + (it - 1)) }
            // Modifiers
            put("LSHIFT", KeyEvent.VK_SHIFT)
            put("RSHIFT", KeyEvent.VK_SHIFT)
            put("LCONTROL", KeyEvent.VK_CONTROL)
            put("RCONTROL", KeyEvent.VK_CONTROL)
            put("LALT", KeyEvent.VK_ALT)
            put("RALT", KeyEvent.VK_ALT)
            put("LWIN", KeyEvent.VK_WINDOWS)
            put("RWIN", KeyEvent.VK_WINDOWS)
            // Navigation
            put("ENTER", KeyEvent.VK_ENTER)
            put("SPACE", KeyEvent.VK_SPACE)
            put("TAB", KeyEvent.VK_TAB)
            put("ESCAPE", KeyEvent.VK_ESCAPE)
            put("BACKSPACE", KeyEvent.VK_BACK_SPACE)
            put("DELETE", KeyEvent.VK_DELETE)
            put("INSERT", KeyEvent.VK_INSERT)
            put("HOME", KeyEvent.VK_HOME)
            put("END", KeyEvent.VK_END)
            put("PAGE UP", KeyEvent.VK_PAGE_UP)
            put("PAGE DOWN", KeyEvent.VK_PAGE_DOWN)
            put("UP", KeyEvent.VK_UP)
            put("DOWN", KeyEvent.VK_DOWN)
            put("LEFT", KeyEvent.VK_LEFT)
            put("RIGHT", KeyEvent.VK_RIGHT)
            // Punctuation
            put("CAPS LOCK", KeyEvent.VK_CAPS_LOCK)
            put("NUM LOCK", KeyEvent.VK_NUM_LOCK)
            put("SCROLL LOCK", KeyEvent.VK_SCROLL_LOCK)
            put("PRINT SCREEN", KeyEvent.VK_PRINTSCREEN)
            put("PAUSE", KeyEvent.VK_PAUSE)
            put("MINUS", KeyEvent.VK_MINUS)
            put("EQUALS", KeyEvent.VK_EQUALS)
            put("OPEN BRACKET", KeyEvent.VK_OPEN_BRACKET)
            put("CLOSE BRACKET", KeyEvent.VK_CLOSE_BRACKET)
            put("SEMICOLON", KeyEvent.VK_SEMICOLON)
            put("QUOTE", KeyEvent.VK_QUOTE)
            put("BACKSLASH", KeyEvent.VK_BACK_SLASH)
            put("COMMA", KeyEvent.VK_COMMA)
            put("PERIOD", KeyEvent.VK_PERIOD)
            put("SLASH", KeyEvent.VK_SLASH)
            put("BACK QUOTE", KeyEvent.VK_BACK_QUOTE)
        }
    }
}
