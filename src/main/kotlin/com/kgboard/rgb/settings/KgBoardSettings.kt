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
        var resetOnExit: Boolean = true
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
