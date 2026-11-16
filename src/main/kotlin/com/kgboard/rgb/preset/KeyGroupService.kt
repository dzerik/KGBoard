package com.kgboard.rgb.preset

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.layout.KeyboardLayoutService

/**
 * Manages key groups: built-in definitions + user-defined custom groups.
 * Resolves key names to LED indices via KeyboardLayoutService.
 *
 * Built-in groups are defined by key names and resolved at runtime.
 * User-defined groups store raw LED indices directly.
 */
@Service(Service.Level.APP)
@State(name = "KgBoardKeyGroups", storages = [Storage("kgboard-keygroups.xml")])
class KeyGroupService : PersistentStateComponent<KeyGroupService.GroupState> {

    private val log = Logger.getInstance(KeyGroupService::class.java)

    data class UserGroup(
        var name: String = "",
        var ledIndices: String = "" // comma-separated LED indices
    )

    data class GroupState(
        var userGroups: MutableList<UserGroup> = mutableListOf()
    )

    @Volatile
    private var state = GroupState()

    /** Cached resolved indices for built-in groups. Refreshed on device connect. */
    @Volatile
    private var resolvedBuiltInGroups: Map<String, List<Int>> = emptyMap()

    override fun getState(): GroupState = state
    override fun loadState(state: GroupState) {
        this.state = state
    }

    /** Resolve built-in group key names to LED indices via KeyboardLayoutService. */
    fun resolveBuiltInGroup(groupId: String): List<Int> {
        return resolvedBuiltInGroups[groupId] ?: emptyList()
    }

    /** Get all available groups: built-in (with resolved indices) + user-defined. */
    fun getAvailableGroups(): Map<String, List<Int>> {
        val result = mutableMapOf<String, List<Int>>()

        // Built-in groups (only those with at least one resolved LED)
        for ((groupId, indices) in resolvedBuiltInGroups) {
            if (indices.isNotEmpty()) {
                result[groupId] = indices
            }
        }

        // User-defined groups
        for (group in state.userGroups) {
            if (group.name.isNotBlank()) {
                val indices = group.ledIndices.split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it >= 0 }
                if (indices.isNotEmpty()) {
                    result[group.name] = indices
                }
            }
        }

        return result
    }

    /** Refresh built-in group cache from current device layout. Call after device connection. */
    fun refreshFromDevice() {
        val layout = KeyboardLayoutService.getInstance()
        if (!layout.isAvailable) {
            resolvedBuiltInGroups = emptyMap()
            log.info("Key groups: no device layout available, cleared cache")
            return
        }

        val resolved = mutableMapOf<String, List<Int>>()
        for ((groupId, keyNames) in KeyGroupDefinitions.BUILT_IN_GROUPS) {
            val indices = layout.getLedIndices(keyNames)
            resolved[groupId] = indices
        }

        resolvedBuiltInGroups = resolved
        val total = resolved.values.sumOf { it.size }
        log.info("Key groups: resolved ${resolved.size} built-in groups ($total total LEDs)")
    }

    /** Resolve a draft device without changing the live layout. */
    fun groupsForDevice(device: com.kgboard.rgb.client.RgbDeviceInfo): Map<String, List<Int>> {
        val layout = KeyboardLayoutService()
        layout.refreshForDevice(device)
        return buildMap {
            for ((id, keys) in KeyGroupDefinitions.BUILT_IN_GROUPS) {
                val indices = layout.getLedIndices(keys)
                if (indices.isNotEmpty()) put(id, indices)
            }
            for (group in state.userGroups) put(group.name, runCatching { com.kgboard.rgb.settings.SettingsValidation.indices(group.ledIndices, device.numLeds) }.getOrDefault(emptyList()))
        }
    }

    /** Get display name for a group ID. */
    fun getDisplayName(groupId: String): String {
        // Check built-in first
        KeyGroupDefinitions.DISPLAY_NAMES[groupId]?.let { return it }
        // Check user groups
        state.userGroups.find { it.name == groupId }?.let { return it.name }
        return groupId
    }

    /** Get user-defined groups (for settings UI). */
    val userGroups: MutableList<UserGroup>
        get() = state.userGroups

    companion object {
        fun getInstance(): KeyGroupService =
            ApplicationManager.getApplication().getService(KeyGroupService::class.java)
    }
}
