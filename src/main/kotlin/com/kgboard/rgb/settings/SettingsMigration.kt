package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.preset.KeyGroupService
import java.util.UUID

/** Detached, idempotent migration. Only successful Apply persists its schema version. */
object SettingsMigration {
    fun app(source: KgBoardSettings.State): KgBoardSettings.State {
        val copy = RuntimeSettings.capture(source).toState()
        if (copy.schemaVersion >= SettingsSchema.VERSION) return copy
        val existing = copy.ruleRecords.map { it.id }.toSet()
        copy.ruleRecords.addAll(
            LegacyScenarioRules.app(RuntimeSettings.capture(copy)).toRecords().filter { it.id !in existing })
        if (copy.bindingRecords.isEmpty()) {
            val legacy = if (copy.multiDeviceEnabled && copy.deviceConfigs.isNotEmpty()) copy.deviceConfigs
            else listOf(KgBoardSettings.DeviceConfig(copy.deviceIndex))
            copy.bindingRecords = legacy.map { device ->
                BindingRecord(
                    id = UUID.randomUUID().toString(),
                    enabled = device.enabled, role = device.role, host = copy.host, port = copy.port,
                    index = device.deviceIndex, name = device.name
                )
            }.toMutableList()
        }
        copy.schemaVersion = SettingsSchema.VERSION
        return copy
    }

    fun project(source: KgBoardProjectSettings.State): KgBoardProjectSettings.State {
        val copy = ProjectSettingsSnapshot.capture(source).toState()
        if (copy.schemaVersion >= SettingsSchema.VERSION) return copy
        val existing = copy.ruleRecords.map { it.id }.toSet()
        copy.ruleRecords.addAll(
            LegacyScenarioRules.project(ProjectSettingsSnapshot.capture(copy)).toRecords()
                .filter { it.id !in existing })
        copy.schemaVersion = SettingsSchema.VERSION
        return copy
    }

    fun groups(source: KeyGroupService.GroupState): KeyGroupService.GroupState = source.copyDeep().also {
        it.userGroups.forEach { group -> if (group.id.isBlank()) group.id = "user:${UUID.randomUUID()}" }
    }
}
