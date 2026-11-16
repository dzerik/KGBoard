package com.kgboard.rgb.preset

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.target.*
import java.util.UUID

/**
 * Manages key groups: built-in definitions + user-defined custom groups.
 * Resolves key names to LED indices via KeyboardLayoutService.
 *
 * Built-in groups are defined by key names and resolved at runtime.
 * User groups retain stable IDs and typed targets; legacy indices require layout confirmation.
 */
@Service(Service.Level.APP)
@State(name = "KgBoardKeyGroups", storages = [Storage("kgboard-keygroups.xml")])
class KeyGroupService : PersistentStateComponent<KeyGroupService.GroupState> {

    private val log = Logger.getInstance(KeyGroupService::class.java)

    data class UserGroup(
        var name: String = "",
        var ledIndices: String = "", // Legacy field retained for XML compatibility.
        var id: String = "",
        var target: TargetRecord? = null
    ) {
        fun copyDeep() = copy(target = target?.copyDeep())
    }

    data class GroupState(
        var userGroups: MutableList<UserGroup> = mutableListOf()
    ) {
        fun copyDeep() = copy(userGroups = userGroups.map { it.copyDeep() }.toMutableList())
    }

    @Volatile
    private var state = GroupState()

    /** Cached resolved indices for built-in groups. Refreshed on device connect. */
    @Volatile
    private var resolvedBuiltInGroups: Map<String, List<Int>> = emptyMap()

    override fun getState(): GroupState = state
    override fun loadState(state: GroupState) {
        this.state = state.copyDeep().also { copy ->
            copy.userGroups.forEach { if (it.id.isBlank()) it.id = "user:${UUID.randomUUID()}" }
        }
    }

    /** Resolve built-in group key names to LED indices via KeyboardLayoutService. */
    fun resolveBuiltInGroup(groupId: String): List<Int> {
        return resolvedBuiltInGroups[groupId] ?: emptyList()
    }

    /** Snapshot definitions independently of one device's current indices. */
    fun definitions(): Map<String, GroupDefinition> {
        val userGroups = state.userGroups.toList()
        val counts = userGroups.groupingBy { it.id }.eachCount()
        return java.util.Collections.unmodifiableMap(buildMap {
            KeyGroupDefinitions.BUILT_IN_GROUPS.forEach { (id, names) ->
                val stableId = "builtin:$id"
                put(stableId, GroupDefinition(stableId, KeyGroupDefinitions.DISPLAY_NAMES.getValue(id),
                    TargetSpec.NamedKeys(names.toList())))
            }
            userGroups.filter { it.id.startsWith("user:") && counts[it.id] == 1 &&
                runCatching { UUID.fromString(it.id.removePrefix("user:")) }.isSuccess }.forEach { group ->
                put(group.id, GroupDefinition(group.id, group.name, group.target?.toSpec()
                    ?: if (group.target == null) TargetSpec.RawIndices(group.ledIndices, null) else TargetSpec.Unassigned))
            }
        })
    }

    /** Compatibility view for legacy CSV controls; unconfirmed raw groups are excluded. */
    fun getAvailableGroups(): Map<String, List<Int>> {
        val device = KeyboardLayoutService.getInstance().snapshot().device ?: return emptyMap()
        return groupsForDevice(device)
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
        val definitions = definitions()
        return definitions.mapValues { (_, group) ->
            TargetResolver.resolve(group.target, device, definitions).indices
        }.filterValues { it.isNotEmpty() }
    }

    /** Get display name for a group ID. */
    fun getDisplayName(groupId: String): String {
        // Check built-in first
        KeyGroupDefinitions.DISPLAY_NAMES[groupId.removePrefix("builtin:")]?.let { return it }
        // Check user groups
        state.userGroups.find { it.id == groupId }?.let { return it.name }
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
