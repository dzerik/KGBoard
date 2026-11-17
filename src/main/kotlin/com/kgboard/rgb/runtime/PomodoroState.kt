package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*
import com.kgboard.rgb.scenario.ScenarioEffects

enum class PomodoroPhase { IDLE, WORK, BREAK, LONG_BREAK }
data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.IDLE, val completedSessions: Int = 0,
    val deadlineMs: Long? = null, val generation: Long = 0, val startedAtMs: Long = 0,
    val deadline: RuntimeDeadline? = null, val startedTime: TimeSample? = null
) {
    fun remainingSeconds(nowMs: Long): Int = remainingSeconds(TimeSample(nowMs, nowMs))
    fun remainingSeconds(now: TimeSample): Int {
        val ms =
            deadline?.remaining(now) ?: deadlineMs?.let { safeDifference(it, now.monotonicMs).coerceAtLeast(0) } ?: 0
        return (ms / 1000 + if (ms % 1000 > 0) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun stop() = if (phase == PomodoroPhase.IDLE && deadlineMs == null) this else copy(
        phase = PomodoroPhase.IDLE,
        deadlineMs = null, deadline = null,
        generation = generation + 1
    )

    fun next(settings: RuntimeSettings, now: Long): PomodoroState = next(settings, TimeSample(now, now))
    fun next(settings: RuntimeSettings, now: TimeSample): PomodoroState {
        val completed = completedSessions + if (phase == PomodoroPhase.WORK) 1 else 0
        val next = if (phase == PomodoroPhase.WORK) {
            if (completed % settings.pomodoroSessionsBeforeLongBreak.coerceAtLeast(1) == 0) PomodoroPhase.LONG_BREAK else PomodoroPhase.BREAK
        } else PomodoroPhase.WORK
        val minutes = when (next) {
            PomodoroPhase.WORK -> settings.pomodoroWorkMinutes
            PomodoroPhase.LONG_BREAK -> settings.pomodoroLongBreakMinutes
            else -> settings.pomodoroBreakMinutes
        }
        val deadline = RuntimeDeadline.after(minutes.coerceAtLeast(1) * 60000L, now, generation + 1)
        return PomodoroState(next, completed, deadline.monotonicAtMs, generation + 1, now.monotonicMs, deadline, now)
    }
}

class PomodoroPolicy {
    fun nextWakeAt(state: RuntimeState, now: Long): Long? = state.projects.values.mapNotNull {
        it.pomodoro.deadline?.wakeAt(state.timeAt(now)) ?: it.pomodoro.deadlineMs
    }.map { minOf(it, expiryAt(now, 1000)) }.minOrNull()

    fun layers(state: RuntimeState, now: Long): List<EffectLayer> {
        val id = state.activeProject ?: return emptyList()
        val pomodoro = state.projects[id]?.pomodoro ?: return emptyList()
        if (!state.settings.pomodoroEnabled || pomodoro.phase == PomodoroPhase.IDLE) return emptyList()
        val ruleId = "pomodoro.${
            when (pomodoro.phase) {
                PomodoroPhase.WORK -> "work"; PomodoroPhase.LONG_BREAK -> "longBreak"; else -> "break"
            }
        }"
        val rule = state.settings.rules[ruleId] ?: return emptyList()
        val duration = safeDifference(pomodoro.deadlineMs!!, pomodoro.startedAtMs).coerceAtLeast(1)
        return buildList {
            add(
                EffectLayer(
                    EffectKey(id, "pomodoro"), ScenarioEffects.create(ruleId, rule.style, EffectTarget.AllLeds),
                    pomodoro.startedAtMs, ruleId = ruleId, factDurationMs = duration
                )
            )
            val transition = state.settings.rules["pomodoro.transition"]
            if (transition != null) {
                val transitionDeadline = RuntimeDeadline.after(
                    expiryAt(transition.style.durationMs, 100),
                    pomodoro.startedTime ?: TimeSample(pomodoro.startedAtMs, pomodoro.startedAtMs), pomodoro.generation
                )
                val until = transitionDeadline.wakeAt(state.timeAt(now))
                if (until > now) add(
                    EffectLayer(
                        EffectKey(id, "pomodoro-transition"),
                        ScenarioEffects.create("pomodoro.transition", transition.style, EffectTarget.AllLeds),
                        pomodoro.startedAtMs, until, "pomodoro.transition"
                    )
                )
            }
        }
    }
}
