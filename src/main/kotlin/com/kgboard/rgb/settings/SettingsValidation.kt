package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*

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
    fun app(s: RuntimeSettings, numLeds: Int? = null) {
        require(s.host.isNotBlank()) { "Host is required" }
        require(s.port in 1..65535) { "Port must be 1..65535" }
        require(s.deviceIndex >= 0 && s.dimBrightness in 0..100) { "Invalid device or brightness" }
        require(s.effectDurationMs >= 0 && s.pulseSpeedMs > 0) { "Invalid effect duration" }
        require(s.pomodoroWorkMinutes > 0 && s.pomodoroBreakMinutes > 0 && s.pomodoroLongBreakMinutes > 0 && s.pomodoroSessionsBeforeLongBreak > 0) { "Pomodoro durations must be positive" }
        listOf(s.buildLedIndices, s.executionLedIndices, s.testLedIndices, s.fileSaveLedIndices, s.debugBreakpointLedIndices,
            s.pomodoroLedIndices, s.notifyIndexingLedIndices, s.notifyLowMemoryLedIndices, s.notifyTodoLedIndices).forEach { indices(it, numLeds) }
        require(s.deviceConfigs.all { it.deviceIndex >= 0 && it.role in setOf("primary", "mirror", "indicator", "ambient") }) { "Invalid device configuration" }
        require(s.deviceConfigs.map { it.deviceIndex }.distinct().size == s.deviceConfigs.size) { "Duplicate device indices" }
    }
    fun project(s: ProjectSettingsSnapshot, numLeds: Int? = null) {
        indices(s.gitLedIndices, numLeds); indices(s.analysisLedIndices, numLeds)
        require(s.gitPollingIntervalSec >= 2) { "Git polling interval must be at least 2 seconds" }
        s.gitBranchColorRules.forEach { Regex(it.pattern) }
    }
}

/** Validate the whole draft before publishing either persisted or runtime state. */
object SettingsTransaction {
    fun applyApp(draft: KgBoardSettings.State, persist: (KgBoardSettings.State) -> Unit,
        events: RuntimeEvents, numLeds: Int? = null) {
        val snapshot = RuntimeSettings.capture(draft)
        SettingsValidation.app(snapshot, numLeds)
        persist(snapshot.toState())
        events.dispatch(RuntimeEvent.SettingsApplied(snapshot))
    }
    fun applyProject(id: ProjectId, draft: KgBoardProjectSettings.State, persist: (KgBoardProjectSettings.State) -> Unit,
        events: RuntimeEvents, numLeds: Int? = null) {
        val snapshot = ProjectSettingsSnapshot.capture(draft)
        SettingsValidation.project(snapshot, numLeds)
        persist(snapshot.toState())
        events.dispatch(RuntimeEvent.ProjectSettingsApplied(id, snapshot))
    }
}
