package com.kgboard.rgb.settings

import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.target.*
import java.util.UUID

/** Editing groups owns only the application draft; managed project configurations stay independent. */
class GroupDraft(private val draft: AppDraft) {
    fun create(name: String, target: TargetSpec): String {
        require(name.isNotBlank()) { "group.nameRequired" }
        require(target != TargetSpec.Unassigned) { "group.targetRequired" }
        val id = "user:${UUID.randomUUID()}"
        draft.groups.userGroups.add(KeyGroupService.UserGroup(name.trim(), id = id, target = TargetRecord.from(target)))
        return id
    }

    fun rename(id: String, name: String) {
        require(name.isNotBlank()) { "group.nameRequired" }
        requireNotNull(draft.groups.userGroups.singleOrNull { it.id == id }) { "group.builtin" }.name = name.trim()
    }

    fun delete(id: String): Set<String> {
        require(draft.groups.userGroups.count { it.id == id } == 1) { "group.builtin" }
        draft.groups.userGroups.removeAll { it.id == id }
        val affected = draft.rules.filterValues { it.target == TargetSpec.Group(id) }.keys.toSet()
        affected.forEach { key -> draft.rules[key] = draft.rules.getValue(key).copy(target = TargetSpec.Unassigned) }
        draft.groups.userGroups.filter { it.target?.toSpec() == TargetSpec.Group(id) }.forEach {
            it.target = TargetRecord.from(TargetSpec.Unassigned)
        }
        return affected
    }

    fun definitions(): Map<String, GroupDefinition> = KeyGroupService.definitions(draft.groups)
}
