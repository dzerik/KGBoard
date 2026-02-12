package com.kgboard.rgb.pomodoro

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.settings.KgBoardSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pomodoro timer with RGB visualization.
 *
 * State machine: IDLE → WORK → BREAK → WORK → ... → LONG_BREAK → IDLE
 *
 * Effects:
 * - WORK: GradientEffect green→yellow over work period
 * - BREAK: PulseEffect blue
 * - LONG_BREAK: PulseEffect blue (slower)
 * - Phase transition: FlashEffect white
 */
@Service(Service.Level.PROJECT)
class PomodoroTimerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PomodoroTimerService::class.java)

    enum class Phase { IDLE, WORK, BREAK, LONG_BREAK }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KGBoard-Pomodoro-${project.name}").apply { isDaemon = true }
    }

    var currentPhase: Phase = Phase.IDLE
        private set
    var completedSessions: Int = 0
        private set
    var remainingSeconds: Int = 0
        private set

    private var tickTask: ScheduledFuture<*>? = null
    private var listeners = mutableListOf<() -> Unit>()

    companion object {
        const val EFFECT_ID = "pomodoro"
        const val TRANSITION_EFFECT_ID = "pomodoro-transition"

        fun getInstance(project: Project): PomodoroTimerService =
            project.getService(PomodoroTimerService::class.java)
    }

    val isRunning: Boolean get() = currentPhase != Phase.IDLE

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun start() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.pomodoroEnabled) return

        completedSessions = 0
        startWorkPhase()
        log.info("Pomodoro started")
    }

    fun stop() {
        tickTask?.cancel(false)
        tickTask = null
        currentPhase = Phase.IDLE
        remainingSeconds = 0

        val effectManager = EffectManagerService.getInstance(project)
        effectManager.removeTargetedEffect(EFFECT_ID)
        effectManager.removeTargetedEffect(TRANSITION_EFFECT_ID)

        notifyListeners()
        log.info("Pomodoro stopped")
    }

    fun skip() {
        when (currentPhase) {
            Phase.WORK -> {
                completedSessions++
                startBreakPhase()
            }
            Phase.BREAK, Phase.LONG_BREAK -> startWorkPhase()
            Phase.IDLE -> {}
        }
    }

    private fun startWorkPhase() {
        val settings = KgBoardSettings.getInstance()
        currentPhase = Phase.WORK
        remainingSeconds = settings.pomodoroWorkMinutes * 60

        // Flash transition
        flashTransition()

        // Gradient effect green→yellow
        val effectManager = EffectManagerService.getInstance(project)
        effectManager.addTargetedEffect(
            EFFECT_ID,
            GradientEffect(
                startColor = settings.pomodoroWorkColor,
                endColor = settings.parseColor("#FFD600"),
                durationMs = remainingSeconds * 1000L,
                name = "pomodoro-work",
                priority = 1,
                target = EffectTarget.AllLeds
            )
        )

        startTicking()
        notifyListeners()
    }

    private fun startBreakPhase() {
        val settings = KgBoardSettings.getInstance()
        val isLongBreak = completedSessions > 0 &&
            completedSessions % settings.pomodoroSessionsBeforeLongBreak == 0

        if (isLongBreak) {
            currentPhase = Phase.LONG_BREAK
            remainingSeconds = settings.pomodoroLongBreakMinutes * 60
        } else {
            currentPhase = Phase.BREAK
            remainingSeconds = settings.pomodoroBreakMinutes * 60
        }

        flashTransition()

        val effectManager = EffectManagerService.getInstance(project)
        effectManager.addTargetedEffect(
            EFFECT_ID,
            PulseEffect(
                color = settings.pomodoroBreakColor,
                periodMs = if (isLongBreak) 2000 else 1200,
                name = "pomodoro-break",
                priority = 1,
                target = EffectTarget.AllLeds
            )
        )

        startTicking()
        notifyListeners()
    }

    private fun flashTransition() {
        val settings = KgBoardSettings.getInstance()
        val effectManager = EffectManagerService.getInstance(project)
        effectManager.addTargetedEffect(
            TRANSITION_EFFECT_ID,
            FlashEffect(
                color = settings.pomodoroTransitionColor,
                durationMs = 600,
                name = "pomodoro-transition",
                priority = 5,
                target = EffectTarget.AllLeds
            ),
            timeoutMs = 700
        )
    }

    private fun startTicking() {
        tickTask?.cancel(false)
        tickTask = scheduler.scheduleAtFixedRate({
            if (remainingSeconds > 0) {
                remainingSeconds--
                notifyListeners()
            } else {
                onPhaseComplete()
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun onPhaseComplete() {
        tickTask?.cancel(false)
        tickTask = null

        when (currentPhase) {
            Phase.WORK -> {
                completedSessions++
                startBreakPhase()
            }
            Phase.BREAK, Phase.LONG_BREAK -> {
                startWorkPhase()
            }
            Phase.IDLE -> {}
        }
    }

    private fun notifyListeners() {
        listeners.forEach {
            try { it() } catch (_: Exception) {}
        }
    }

    override fun dispose() {
        tickTask?.cancel(false)
        tickTask = null
        scheduler.shutdownNow()
    }

    fun formatTimeRemaining(): String {
        val min = remainingSeconds / 60
        val sec = remainingSeconds % 60
        return "%02d:%02d".format(min, sec)
    }

    fun phaseDisplayName(): String = when (currentPhase) {
        Phase.IDLE -> "Idle"
        Phase.WORK -> "Work"
        Phase.BREAK -> "Break"
        Phase.LONG_BREAK -> "Long Break"
    }
}
