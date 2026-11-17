package com.kgboard.rgb.pomodoro

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.kgboard.rgb.bridge.ProjectRuntimeService
import com.kgboard.rgb.runtime.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Compatibility facade; timing and transitions belong entirely to LightingRuntime. */
@Service(Service.Level.PROJECT)
class PomodoroTimerService(private val project: Project) : Disposable {
    enum class Phase { IDLE, WORK, BREAK, LONG_BREAK }

    private val app = LightingRuntimeService.getInstance()
    private val binding = ProjectRuntimeService.getInstance(project).binding
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val closed = AtomicBoolean()
    private var last: Pair<PomodoroState, Int>? = null
    private val subscription = app.subscribe { snapshot ->
        val state = snapshot.projects[binding.id]?.pomodoro ?: PomodoroState()
        val value = state to state.remainingSeconds(snapshot.timeAt(snapshot.nowMs))
        if (last != value) {
            last = value
            ApplicationManager.getApplication()
                .invokeLater({ if (!closed.get()) listeners.forEach { it() } }, ModalityState.any())
        }
    }

    private fun state() = app.snapshot().projects[binding.id]?.pomodoro ?: PomodoroState()
    val currentPhase: Phase get() = Phase.valueOf(state().phase.name)
    val completedSessions: Int get() = state().completedSessions
    val remainingSeconds: Int
        get() = app.snapshot()
            .let { (it.projects[binding.id]?.pomodoro ?: PomodoroState()).remainingSeconds(it.timeAt(it.nowMs)) }
    val isRunning: Boolean get() = currentPhase != Phase.IDLE
    fun start() {
        ProjectRuntimeService.getInstance(project).start(); binding.emit(RuntimeEvent.PomodoroStart(binding.id))
    }

    fun stop() {
        binding.emit(RuntimeEvent.PomodoroStop(binding.id))
    }

    fun skip() {
        binding.emit(RuntimeEvent.PomodoroSkip(binding.id))
    }

    fun addChangeListener(listener: () -> Unit) {
        if (!closed.get()) listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun formatTimeRemaining(): String = "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)
    fun phaseDisplayName(): String = when (currentPhase) {
        Phase.IDLE -> "Idle"; Phase.WORK -> "Work"; Phase.BREAK -> "Break"; Phase.LONG_BREAK -> "Long Break"
    }

    override fun dispose() {
        closed.set(true); subscription.cancel(); listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): PomodoroTimerService = project.getService(PomodoroTimerService::class.java)
    }
}
