package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*

enum class PomodoroPhase { IDLE, WORK, BREAK, LONG_BREAK }
data class PomodoroState(val phase: PomodoroPhase = PomodoroPhase.IDLE, val completedSessions: Int = 0,
    val deadlineMs: Long? = null, val generation: Long = 0, val startedAtMs: Long = 0) {
    fun remainingSeconds(nowMs: Long): Int = ((deadlineMs?.minus(nowMs)?.coerceAtLeast(0)?.plus(999) ?: 0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun stop() = if (phase == PomodoroPhase.IDLE && deadlineMs == null) this else copy(phase = PomodoroPhase.IDLE, deadlineMs = null, generation = generation + 1)
    fun next(settings: RuntimeSettings, now: Long): PomodoroState {
        val completed = completedSessions + if (phase == PomodoroPhase.WORK) 1 else 0
        val next = if (phase == PomodoroPhase.WORK) {
            if (completed % settings.pomodoroSessionsBeforeLongBreak.coerceAtLeast(1) == 0) PomodoroPhase.LONG_BREAK else PomodoroPhase.BREAK
        } else PomodoroPhase.WORK
        val minutes = when(next) {
            PomodoroPhase.WORK -> settings.pomodoroWorkMinutes
            PomodoroPhase.LONG_BREAK -> settings.pomodoroLongBreakMinutes
            else -> settings.pomodoroBreakMinutes
        }
        return PomodoroState(next, completed, now + minutes.coerceAtLeast(1) * 60000L, generation + 1, now)
    }
}

class PomodoroPolicy {
    fun nextWakeAt(state: RuntimeState, now: Long): Long? = state.projects.values.mapNotNull { it.pomodoro.deadlineMs }
        .map { minOf(it, now + 1000) }.minOrNull()
    fun layers(state: RuntimeState, now: Long): List<EffectLayer> {
        val id = state.activeProject ?: return emptyList()
        val pomodoro = state.projects[id]?.pomodoro ?: return emptyList()
        if (!state.settings.pomodoroEnabled || pomodoro.phase == PomodoroPhase.IDLE) return emptyList()
        val s = state.settings; val target = target(s.pomodoroLedIndices)
        val base = if (pomodoro.phase == PomodoroPhase.WORK)
            GradientEffect(LightingPolicy.color(s.pomodoroWorkColor), LightingPolicy.color("#FFD600"),
                (pomodoro.deadlineMs!! - pomodoro.startedAtMs).coerceAtLeast(1), priority = 1, target = target)
            else PulseEffect(LightingPolicy.color(s.pomodoroBreakColor), periodMs = if (pomodoro.phase == PomodoroPhase.LONG_BREAK) 2000 else 1200,
                priority = 1, target = target)
        return buildList {
            add(EffectLayer(EffectKey(id, "pomodoro"), base, pomodoro.startedAtMs))
            if (pomodoro.startedAtMs + 700 > now) add(EffectLayer(EffectKey(id, "pomodoro-transition"),
                FlashEffect(LightingPolicy.color(s.pomodoroTransitionColor), 600, priority = 5, target = target),
                pomodoro.startedAtMs, pomodoro.startedAtMs + 700))
        }
    }
}
