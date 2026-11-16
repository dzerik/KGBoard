package com.kgboard.rgb.runtime

import com.kgboard.rgb.settings.KgBoardSettings
import com.kgboard.rgb.settings.KgBoardProjectSettings
import com.kgboard.rgb.settings.*
import com.kgboard.rgb.device.*
import com.kgboard.rgb.scenario.*

data class DeviceSettings(
    val deviceIndex: Int = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val role: String = "primary"
)

data class BranchRule(val pattern: String = ".*", val color: String = "#FFFFFF") {
    private val regex = runCatching { Regex(pattern) }.getOrNull()
    fun matches(branch: String): Boolean = regex?.containsMatchIn(branch) == true
}

/** Immutable runtime copy of the persisted XML settings. */
data class RuntimeSettings(
    val host: String = "127.0.0.1",
    val port: Int = 6742,
    val deviceIndex: Int = 0,
    val enabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val autoConnect: Boolean = true,
    val buildSuccessColor: String = "#00C853",
    val buildFailureColor: String = "#FF1744",
    val buildInProgressColor: String = "#FFD600",
    val runColor: String = "#00E676",
    val debugColor: String = "#651FFF",
    val stopColor: String = "#FF9100",
    val testPassColor: String = "#00C853",
    val testFailColor: String = "#FF1744",
    val testRunningColor: String = "#2979FF",
    val effectDurationMs: Long = 3000,
    val pulseSpeedMs: Long = 1000,
    val idleColor: String = "#263238",
    val dimOnFocusLoss: Boolean = true,
    val dimBrightness: Int = 15,
    val resetOnExit: Boolean = true,
    val skipCustomMode: Boolean = false,
    val multiDeviceEnabled: Boolean = false,
    val deviceConfigs: List<DeviceSettings> = emptyList(),
    val pomodoroEnabled: Boolean = false,
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroBreakMinutes: Int = 5,
    val pomodoroLongBreakMinutes: Int = 15,
    val pomodoroSessionsBeforeLongBreak: Int = 4,
    val pomodoroWorkColor: String = "#00C853",
    val pomodoroBreakColor: String = "#2979FF",
    val pomodoroTransitionColor: String = "#FFFFFF",
    val notifyIndexingEnabled: Boolean = true,
    val notifyIndexingColor: String = "#FF9100",
    val notifyIndexingLedIndices: String = "",
    val notifyLowMemoryEnabled: Boolean = true,
    val notifyLowMemoryColor: String = "#FF1744",
    val notifyLowMemoryLedIndices: String = "",
    val notifyTodoEnabled: Boolean = false,
    val notifyTodoColor: String = "#FFD600",
    val notifyTodoLedIndices: String = "",
    val buildLedIndices: String = "",
    val executionLedIndices: String = "",
    val testLedIndices: String = "",
    val fileSaveLedIndices: String = "",
    val debugBreakpointLedIndices: String = "",
    val pomodoroLedIndices: String = "",
    val fileSaveFlashEnabled: Boolean = false,
    val fileSaveFlashColor: String = "#FFFFFF",
    val debugBreakpointFlashEnabled: Boolean = false,
    val debugBreakpointFlashColor: String = "#FF1744",
    val schemaVersion: Int = 0,
    val bindingRecords: BindingRecords = BindingRecords.EMPTY,
    val ruleRecords: ScenarioRules = ScenarioRules.empty(SettingsScope.APP),
    val untrustedSerials: Set<SerialKey> = emptySet()
) {
    val bindings: List<ConfiguredBinding> get() = if (schemaVersion > SettingsSchema.VERSION) emptyList() else bindingRecords.configurations
    val rules: Map<String, RuleAppearance>
        get() = if (schemaVersion > SettingsSchema.VERSION) emptyMap()
        else if (schemaVersion == 0 && ruleRecords.entries.isEmpty()) LegacyScenarioRules.app(this).executable else ruleRecords.executable

    fun effectiveRuleRecords(): ScenarioRules =
        if (schemaVersion == 0 && ruleRecords.entries.isEmpty()) LegacyScenarioRules.app(this) else ruleRecords

    fun toState(): KgBoardSettings.State = KgBoardSettings.State(
        host = host,
        port = port,
        deviceIndex = deviceIndex,
        enabled = enabled,
        autoReconnect = autoReconnect,
        autoConnect = autoConnect,
        buildSuccessColor = buildSuccessColor,
        buildFailureColor = buildFailureColor,
        buildInProgressColor = buildInProgressColor,
        runColor = runColor,
        debugColor = debugColor,
        stopColor = stopColor,
        testPassColor = testPassColor,
        testFailColor = testFailColor,
        testRunningColor = testRunningColor,
        effectDurationMs = effectDurationMs,
        pulseSpeedMs = pulseSpeedMs,
        idleColor = idleColor,
        dimOnFocusLoss = dimOnFocusLoss,
        dimBrightness = dimBrightness,
        resetOnExit = resetOnExit,
        skipCustomMode = skipCustomMode,
        multiDeviceEnabled = multiDeviceEnabled,
        deviceConfigs = deviceConfigs.map { KgBoardSettings.DeviceConfig(it.deviceIndex, it.name, it.enabled, it.role) }
            .toMutableList(),
        pomodoroEnabled = pomodoroEnabled,
        pomodoroWorkMinutes = pomodoroWorkMinutes,
        pomodoroBreakMinutes = pomodoroBreakMinutes,
        pomodoroLongBreakMinutes = pomodoroLongBreakMinutes,
        pomodoroSessionsBeforeLongBreak = pomodoroSessionsBeforeLongBreak,
        pomodoroWorkColor = pomodoroWorkColor,
        pomodoroBreakColor = pomodoroBreakColor,
        pomodoroTransitionColor = pomodoroTransitionColor,
        notifyIndexingEnabled = notifyIndexingEnabled,
        notifyIndexingColor = notifyIndexingColor,
        notifyIndexingLedIndices = notifyIndexingLedIndices,
        notifyLowMemoryEnabled = notifyLowMemoryEnabled,
        notifyLowMemoryColor = notifyLowMemoryColor,
        notifyLowMemoryLedIndices = notifyLowMemoryLedIndices,
        notifyTodoEnabled = notifyTodoEnabled,
        notifyTodoColor = notifyTodoColor,
        notifyTodoLedIndices = notifyTodoLedIndices,
        buildLedIndices = buildLedIndices,
        executionLedIndices = executionLedIndices,
        testLedIndices = testLedIndices,
        fileSaveLedIndices = fileSaveLedIndices,
        debugBreakpointLedIndices = debugBreakpointLedIndices,
        pomodoroLedIndices = pomodoroLedIndices,
        fileSaveFlashEnabled = fileSaveFlashEnabled,
        fileSaveFlashColor = fileSaveFlashColor,
        debugBreakpointFlashEnabled = debugBreakpointFlashEnabled,
        debugBreakpointFlashColor = debugBreakpointFlashColor,
        schemaVersion = schemaVersion,
        bindingRecords = bindingRecords.toRecords(),
        ruleRecords = ruleRecords.toRecords(),
        untrustedSerials = untrustedSerials.map(SerialRecord::from).toMutableList()
    )

    companion object {
        fun capture(source: KgBoardSettings.State): RuntimeSettings = RuntimeSettings(
            host = source.host,
            port = source.port,
            deviceIndex = source.deviceIndex,
            enabled = source.enabled,
            autoReconnect = source.autoReconnect,
            autoConnect = source.autoConnect,
            buildSuccessColor = source.buildSuccessColor,
            buildFailureColor = source.buildFailureColor,
            buildInProgressColor = source.buildInProgressColor,
            runColor = source.runColor,
            debugColor = source.debugColor,
            stopColor = source.stopColor,
            testPassColor = source.testPassColor,
            testFailColor = source.testFailColor,
            testRunningColor = source.testRunningColor,
            effectDurationMs = source.effectDurationMs,
            pulseSpeedMs = source.pulseSpeedMs,
            idleColor = source.idleColor,
            dimOnFocusLoss = source.dimOnFocusLoss,
            dimBrightness = source.dimBrightness,
            resetOnExit = source.resetOnExit,
            skipCustomMode = source.skipCustomMode,
            multiDeviceEnabled = source.multiDeviceEnabled,
            deviceConfigs = frozenList(source.deviceConfigs.map {
                DeviceSettings(
                    it.deviceIndex,
                    it.name,
                    it.enabled,
                    it.role
                )
            }),
            pomodoroEnabled = source.pomodoroEnabled,
            pomodoroWorkMinutes = source.pomodoroWorkMinutes,
            pomodoroBreakMinutes = source.pomodoroBreakMinutes,
            pomodoroLongBreakMinutes = source.pomodoroLongBreakMinutes,
            pomodoroSessionsBeforeLongBreak = source.pomodoroSessionsBeforeLongBreak,
            pomodoroWorkColor = source.pomodoroWorkColor,
            pomodoroBreakColor = source.pomodoroBreakColor,
            pomodoroTransitionColor = source.pomodoroTransitionColor,
            notifyIndexingEnabled = source.notifyIndexingEnabled,
            notifyIndexingColor = source.notifyIndexingColor,
            notifyIndexingLedIndices = source.notifyIndexingLedIndices,
            notifyLowMemoryEnabled = source.notifyLowMemoryEnabled,
            notifyLowMemoryColor = source.notifyLowMemoryColor,
            notifyLowMemoryLedIndices = source.notifyLowMemoryLedIndices,
            notifyTodoEnabled = source.notifyTodoEnabled,
            notifyTodoColor = source.notifyTodoColor,
            notifyTodoLedIndices = source.notifyTodoLedIndices,
            buildLedIndices = source.buildLedIndices,
            executionLedIndices = source.executionLedIndices,
            testLedIndices = source.testLedIndices,
            fileSaveLedIndices = source.fileSaveLedIndices,
            debugBreakpointLedIndices = source.debugBreakpointLedIndices,
            pomodoroLedIndices = source.pomodoroLedIndices,
            fileSaveFlashEnabled = source.fileSaveFlashEnabled,
            fileSaveFlashColor = source.fileSaveFlashColor,
            debugBreakpointFlashEnabled = source.debugBreakpointFlashEnabled,
            debugBreakpointFlashColor = source.debugBreakpointFlashColor,
            schemaVersion = source.schemaVersion,
            bindingRecords = BindingRecords.capture(source.bindingRecords),
            ruleRecords = ScenarioRules.capture(SettingsScope.APP, source.ruleRecords),
            untrustedSerials = frozenSet(source.untrustedSerials.map { it.snapshot() })
        )
    }
}

/** Immutable runtime copy of the persisted XML settings. */
data class ProjectSettingsSnapshot(
    val gitEnabled: Boolean = true,
    val gitLedIndices: String = "",
    val gitUncommittedColor: String = "#FFD600",
    val gitConflictColor: String = "#FF1744",
    val gitUnpushedColor: String = "#2979FF",
    val gitCleanColor: String = "#00C853",
    val gitBranchColorRules: List<BranchRule> = emptyList(),
    val gitPollingIntervalSec: Int = 5,
    val analysisEnabled: Boolean = true,
    val analysisLedIndices: String = "",
    val analysisErrorColor: String = "#FF1744",
    val analysisWarningColor: String = "#FFD600",
    val analysisCleanColor: String = "#00C853",
    val shortcutHighlightEnabled: Boolean = false,
    val shortcutContexts: List<String> = listOf("debug", "search"),
    val shortcutDebugColor: String = "#651FFF",
    val shortcutSearchColor: String = "#2979FF",
    val shortcutEditingColor: String = "#00C853",
    val shortcutVcsColor: String = "#FF9100",
    val schemaVersion: Int = 0,
    val ruleRecords: ScenarioRules = ScenarioRules.empty(SettingsScope.PROJECT)
) {
    val rules: Map<String, RuleAppearance>
        get() = if (schemaVersion > SettingsSchema.VERSION) emptyMap()
        else if (schemaVersion == 0 && ruleRecords.entries.isEmpty()) LegacyScenarioRules.project(this).executable else ruleRecords.executable

    fun effectiveRuleRecords(): ScenarioRules =
        if (schemaVersion == 0 && ruleRecords.entries.isEmpty()) LegacyScenarioRules.project(this) else ruleRecords

    fun toState(): KgBoardProjectSettings.State = KgBoardProjectSettings.State(
        gitEnabled = gitEnabled,
        gitLedIndices = gitLedIndices,
        gitUncommittedColor = gitUncommittedColor,
        gitConflictColor = gitConflictColor,
        gitUnpushedColor = gitUnpushedColor,
        gitCleanColor = gitCleanColor,
        gitBranchColorRules = gitBranchColorRules.map { KgBoardProjectSettings.GitColorRule(it.pattern, it.color) }
            .toMutableList(),
        gitPollingIntervalSec = gitPollingIntervalSec,
        analysisEnabled = analysisEnabled,
        analysisLedIndices = analysisLedIndices,
        analysisErrorColor = analysisErrorColor,
        analysisWarningColor = analysisWarningColor,
        analysisCleanColor = analysisCleanColor,
        shortcutHighlightEnabled = shortcutHighlightEnabled,
        shortcutContexts = shortcutContexts.toMutableList(),
        shortcutDebugColor = shortcutDebugColor,
        shortcutSearchColor = shortcutSearchColor,
        shortcutEditingColor = shortcutEditingColor,
        shortcutVcsColor = shortcutVcsColor,
        schemaVersion = schemaVersion,
        ruleRecords = ruleRecords.toRecords()
    )

    companion object {
        fun capture(source: KgBoardProjectSettings.State): ProjectSettingsSnapshot = ProjectSettingsSnapshot(
            gitEnabled = source.gitEnabled,
            gitLedIndices = source.gitLedIndices,
            gitUncommittedColor = source.gitUncommittedColor,
            gitConflictColor = source.gitConflictColor,
            gitUnpushedColor = source.gitUnpushedColor,
            gitCleanColor = source.gitCleanColor,
            gitBranchColorRules = frozenList(source.gitBranchColorRules.map { BranchRule(it.pattern, it.color) }),
            gitPollingIntervalSec = source.gitPollingIntervalSec,
            analysisEnabled = source.analysisEnabled,
            analysisLedIndices = source.analysisLedIndices,
            analysisErrorColor = source.analysisErrorColor,
            analysisWarningColor = source.analysisWarningColor,
            analysisCleanColor = source.analysisCleanColor,
            shortcutHighlightEnabled = source.shortcutHighlightEnabled,
            shortcutContexts = frozenList(source.shortcutContexts),
            shortcutDebugColor = source.shortcutDebugColor,
            shortcutSearchColor = source.shortcutSearchColor,
            shortcutEditingColor = source.shortcutEditingColor,
            shortcutVcsColor = source.shortcutVcsColor,
            schemaVersion = source.schemaVersion,
            ruleRecords = ScenarioRules.capture(SettingsScope.PROJECT, source.ruleRecords)
        )
    }
}
