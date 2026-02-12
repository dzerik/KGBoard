package com.kgboard.rgb.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.awt.Color

@Service(Service.Level.APP)
@State(name = "KgBoardSettings", storages = [Storage("kgboard.xml")])
class KgBoardSettings : PersistentStateComponent<KgBoardSettings.State> {

    data class DeviceConfig(
        var deviceIndex: Int = 0,
        var name: String = "",
        var enabled: Boolean = true,
        var role: String = "primary" // primary, ambient, indicator, mirror
    )

    data class State(
        var host: String = "127.0.0.1",
        var port: Int = 6742,
        var deviceIndex: Int = 0,
        var enabled: Boolean = true,
        var autoReconnect: Boolean = true,
        var autoConnect: Boolean = true,

        // Build events
        var buildSuccessColor: String = "#00C853",
        var buildFailureColor: String = "#FF1744",
        var buildInProgressColor: String = "#FFD600",

        // Execution events
        var runColor: String = "#00E676",
        var debugColor: String = "#651FFF",
        var stopColor: String = "#FF9100",

        // Test events
        var testPassColor: String = "#00C853",
        var testFailColor: String = "#FF1744",
        var testRunningColor: String = "#2979FF",

        // Effects
        var effectDurationMs: Long = 3000,
        var pulseSpeedMs: Long = 1000,
        var idleColor: String = "#263238",

        // Focus behavior
        var dimOnFocusLoss: Boolean = true,
        var dimBrightness: Int = 15, // percent (0-100)
        var resetOnExit: Boolean = true,

        // Multi-device
        var multiDeviceEnabled: Boolean = false,
        var deviceConfigs: MutableList<DeviceConfig> = mutableListOf(),

        // Pomodoro
        var pomodoroEnabled: Boolean = false,
        var pomodoroWorkMinutes: Int = 25,
        var pomodoroBreakMinutes: Int = 5,
        var pomodoroLongBreakMinutes: Int = 15,
        var pomodoroSessionsBeforeLongBreak: Int = 4,
        var pomodoroWorkColor: String = "#00C853",
        var pomodoroBreakColor: String = "#2979FF",
        var pomodoroTransitionColor: String = "#FFFFFF",

        // IDE Notifications
        var notifyIndexingEnabled: Boolean = true,
        var notifyIndexingColor: String = "#FF9100",
        var notifyLowMemoryEnabled: Boolean = true,
        var notifyLowMemoryColor: String = "#FF1744",
        var notifyTodoEnabled: Boolean = false,
        var notifyTodoColor: String = "#FFD600",
        var notifyTodoLedIndices: String = "" // comma-separated
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var host: String
        get() = state.host
        set(value) { state.host = value }

    var port: Int
        get() = state.port
        set(value) { state.port = value }

    var deviceIndex: Int
        get() = state.deviceIndex
        set(value) { state.deviceIndex = value }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var autoReconnect: Boolean
        get() = state.autoReconnect
        set(value) { state.autoReconnect = value }

    var autoConnect: Boolean
        get() = state.autoConnect
        set(value) { state.autoConnect = value }

    var effectDurationMs: Long
        get() = state.effectDurationMs
        set(value) { state.effectDurationMs = value }

    var pulseSpeedMs: Long
        get() = state.pulseSpeedMs
        set(value) { state.pulseSpeedMs = value }

    var dimOnFocusLoss: Boolean
        get() = state.dimOnFocusLoss
        set(value) { state.dimOnFocusLoss = value }

    var dimBrightness: Int
        get() = state.dimBrightness
        set(value) { state.dimBrightness = value }

    var resetOnExit: Boolean
        get() = state.resetOnExit
        set(value) { state.resetOnExit = value }

    // Multi-device
    var multiDeviceEnabled: Boolean
        get() = state.multiDeviceEnabled
        set(value) { state.multiDeviceEnabled = value }

    val deviceConfigs: MutableList<DeviceConfig>
        get() = state.deviceConfigs

    // Pomodoro
    var pomodoroEnabled: Boolean
        get() = state.pomodoroEnabled
        set(value) { state.pomodoroEnabled = value }

    var pomodoroWorkMinutes: Int
        get() = state.pomodoroWorkMinutes
        set(value) { state.pomodoroWorkMinutes = value }

    var pomodoroBreakMinutes: Int
        get() = state.pomodoroBreakMinutes
        set(value) { state.pomodoroBreakMinutes = value }

    var pomodoroLongBreakMinutes: Int
        get() = state.pomodoroLongBreakMinutes
        set(value) { state.pomodoroLongBreakMinutes = value }

    var pomodoroSessionsBeforeLongBreak: Int
        get() = state.pomodoroSessionsBeforeLongBreak
        set(value) { state.pomodoroSessionsBeforeLongBreak = value }

    val pomodoroWorkColor: Color get() = parseColor(state.pomodoroWorkColor)
    val pomodoroBreakColor: Color get() = parseColor(state.pomodoroBreakColor)
    val pomodoroTransitionColor: Color get() = parseColor(state.pomodoroTransitionColor)

    // IDE Notifications
    var notifyIndexingEnabled: Boolean
        get() = state.notifyIndexingEnabled
        set(value) { state.notifyIndexingEnabled = value }

    val notifyIndexingColor: Color get() = parseColor(state.notifyIndexingColor)

    var notifyLowMemoryEnabled: Boolean
        get() = state.notifyLowMemoryEnabled
        set(value) { state.notifyLowMemoryEnabled = value }

    val notifyLowMemoryColor: Color get() = parseColor(state.notifyLowMemoryColor)

    var notifyTodoEnabled: Boolean
        get() = state.notifyTodoEnabled
        set(value) { state.notifyTodoEnabled = value }

    val notifyTodoColor: Color get() = parseColor(state.notifyTodoColor)

    val notifyTodoLedIndices: List<Int>
        get() = state.notifyTodoLedIndices.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }

    fun parseColor(hex: String): Color {
        return try {
            Color.decode(hex)
        } catch (_: NumberFormatException) {
            Color.BLACK
        }
    }

    val buildSuccessColor: Color get() = parseColor(state.buildSuccessColor)
    val buildFailureColor: Color get() = parseColor(state.buildFailureColor)
    val buildInProgressColor: Color get() = parseColor(state.buildInProgressColor)
    val runColor: Color get() = parseColor(state.runColor)
    val debugColor: Color get() = parseColor(state.debugColor)
    val stopColor: Color get() = parseColor(state.stopColor)
    val testPassColor: Color get() = parseColor(state.testPassColor)
    val testFailColor: Color get() = parseColor(state.testFailColor)
    val testRunningColor: Color get() = parseColor(state.testRunningColor)
    val idleColor: Color get() = parseColor(state.idleColor)

    companion object {
        fun getInstance(): KgBoardSettings =
            ApplicationManager.getApplication().getService(KgBoardSettings::class.java)
    }
}
