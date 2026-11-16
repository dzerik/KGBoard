package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.device.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.*

/** Validation is shared by Apply and Test. Empty means all; malformed never does. */
object SettingsValidation {
    fun indices(raw: String, numLeds: Int? = null): List<Int> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').map { token ->
            val index = token.trim().toIntOrNull()
            require(index != null && index >= 0) { "Invalid LED index: '$token'" }
            require(numLeds == null || index < numLeds) { "LED index $index is outside 0..${numLeds!! - 1}" }
            index
        }.distinct()
    }

    fun app(s: RuntimeSettings, numLeds: Int? = null, inventory: InventorySnapshot? = null) {
        require(s.host.isNotBlank()) { "Host is required" }
        require(s.port in 1..65535) { "Port must be 1..65535" }
        require(s.dimBrightness in 0..100) { "Invalid brightness" }
        if (s.schemaVersion == 0) require(s.effectDurationMs >= 0 && s.pulseSpeedMs > 0) { "Invalid effect duration" }
        require(s.pomodoroWorkMinutes > 0 && s.pomodoroBreakMinutes > 0 && s.pomodoroLongBreakMinutes > 0 && s.pomodoroSessionsBeforeLongBreak > 0) { "Pomodoro durations must be positive" }
        if (s.schemaVersion == 0) legacyApp(s, numLeds)
        require(s.schemaVersion <= SettingsSchema.VERSION) { "Configuration schema is newer than this plugin" }
        rules(s.effectiveRuleRecords(), numLeds)
        require(s.bindingRecords.entries.map { it.id }
            .distinct().size == s.bindingRecords.entries.size) { "Duplicate binding IDs" }
        s.bindings.forEach { binding ->
            require(binding.role in setOf("primary", "mirror", "indicator", "ambient")) { "Invalid device role" }
            require(binding.binding.endpoint.host.isNotBlank() && binding.binding.endpoint.port in 1..65535) { "Invalid binding server" }
        }
        if (inventory != null) {
            val resolved = s.bindings.filter { it.enabled }.mapNotNull {
                (DeviceResolver.resolve(it.binding, inventory, s.untrustedSerials) as? DeviceResolution.Resolved)?.index
            }
            require(resolved.distinct().size == resolved.size) { "Two enabled roles select the same device" }
        }
    }

    fun project(s: ProjectSettingsSnapshot, numLeds: Int? = null) {
        if (s.schemaVersion == 0) {
            indices(s.gitLedIndices, numLeds); indices(s.analysisLedIndices, numLeds)
        }
        require(s.schemaVersion <= SettingsSchema.VERSION) { "Configuration schema is newer than this plugin" }
        rules(s.effectiveRuleRecords(), numLeds)
        require(s.gitPollingIntervalSec >= 2) { "Git polling interval must be at least 2 seconds" }
        s.gitBranchColorRules.forEach { Regex(it.pattern) }
    }

    fun legacyApp(s: RuntimeSettings, numLeds: Int? = null) {
        require(s.deviceIndex >= 0) { "Invalid device index" }
        listOf(
            s.buildLedIndices,
            s.executionLedIndices,
            s.testLedIndices,
            s.fileSaveLedIndices,
            s.debugBreakpointLedIndices,
            s.pomodoroLedIndices,
            s.notifyIndexingLedIndices,
            s.notifyLowMemoryLedIndices,
            s.notifyTodoLedIndices
        ).forEach { indices(it, numLeds) }
        require(s.deviceConfigs.all {
            it.deviceIndex >= 0 && it.role in setOf(
                "primary",
                "mirror",
                "indicator",
                "ambient"
            )
        }) { "Invalid device configuration" }
        require(s.deviceConfigs.map { it.deviceIndex }
            .distinct().size == s.deviceConfigs.size) { "Duplicate device indices" }
    }

    private fun rules(records: ScenarioRules, numLeds: Int?) {
        val known = records.entries.filter { ScenarioCatalog.byId(it.id) != null }
        require(known.map { it.id }.distinct().size == known.size) { "Duplicate scenario rule IDs" }
        known.forEach { rule ->
            require(ScenarioCatalog.byId(rule.id)!!.scope == records.scope) { "Scenario belongs to another settings scope" }
            if (runCatching { EffectStyle.valueOf(rule.effect) }.isFailure) return@forEach
            require(rule.durationMs >= 0 && rule.periodMs > 0) { "Invalid scenario timing: ${rule.id}" }
            require(runCatching { java.awt.Color.decode(rule.color) }.isSuccess) { "Invalid scenario color: ${rule.id}" }
            when (val target = rule.target.toRecord().toSpec()) {
                is TargetSpec.RawIndices -> {
                    require(target.text.isNotBlank()) { "Select LED indices" }; indices(target.text, numLeds)
                }

                is TargetSpec.NamedKeys -> require(target.names.isNotEmpty() && target.names.all { it.isNotBlank() }) { "Select named keys" }
                is TargetSpec.Group -> require(target.id.isNotBlank()) { "Select a group" }
                else -> Unit
            }
        }
    }
}

/** Validate the whole draft before publishing either persisted or runtime state. */
object SettingsTransaction {
    fun applyApp(
        draft: KgBoardSettings.State, persist: (KgBoardSettings.State) -> Unit,
        events: RuntimeEvents, numLeds: Int? = null, inventory: InventorySnapshot? = null,
        groups: Map<String, GroupDefinition>? = null
    ) {
        SettingsValidation.app(RuntimeSettings.capture(draft), numLeds, inventory)
        val snapshot = RuntimeSettings.capture(SettingsMigration.app(draft))
        SettingsValidation.app(snapshot, numLeds, inventory)
        persist(snapshot.toState())
        events.dispatch(RuntimeEvent.SettingsApplied(snapshot, groups))
    }

    fun applyProject(
        id: ProjectId, draft: KgBoardProjectSettings.State, persist: (KgBoardProjectSettings.State) -> Unit,
        events: RuntimeEvents, numLeds: Int? = null
    ) {
        SettingsValidation.project(ProjectSettingsSnapshot.capture(draft), numLeds)
        val snapshot = ProjectSettingsSnapshot.capture(SettingsMigration.project(draft))
        SettingsValidation.project(snapshot, numLeds)
        persist(snapshot.toState())
        events.dispatch(RuntimeEvent.ProjectSettingsApplied(id, snapshot))
    }
}
