package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PomodoroStateTest {
    @Test fun `skip and stop fence old wake without decrementing new phase`() {
        val h = RuntimeHarness(RuntimeSettings(pomodoroEnabled = true))
        val p = ProjectId("p"); h.project(p)
        h.send(RuntimeEvent.PomodoroStart(p)); h.advanceBy(500)
        h.send(RuntimeEvent.PomodoroSkip(p))
        val deadline = h.snapshotForTest().projects.getValue(p).pomodoro.deadlineMs
        h.send(RuntimeEvent.Wake(Long.MIN_VALUE))
        assertEquals(deadline, h.snapshotForTest().projects.getValue(p).pomodoro.deadlineMs)
        h.send(RuntimeEvent.PomodoroStop(p)); h.advanceBy(500000)
        assertEquals(PomodoroPhase.IDLE, h.snapshotForTest().projects.getValue(p).pomodoro.phase)
    }
    @Test fun `delayed scheduler advances one phase from current time and long break follows configured cycles`() {
        val reducer = RuntimeReducer(); val p = ProjectId("p")
        var s = reducer.reduce(RuntimeState(settings = RuntimeSettings(pomodoroEnabled = true, pomodoroSessionsBeforeLongBreak = 2)), RuntimeEvent.ProjectOpened(p), 0).state
        s = reducer.reduce(s, RuntimeEvent.PomodoroStart(p), 0).state
        s = reducer.reduce(s, RuntimeEvent.Wake(1), 9000000).state
        assertEquals(PomodoroPhase.BREAK, s.projects.getValue(p).pomodoro.phase)
        assertEquals(9300000, s.projects.getValue(p).pomodoro.deadlineMs)
        s = reducer.reduce(s, RuntimeEvent.PomodoroSkip(p), 9000010).state
        s = reducer.reduce(s, RuntimeEvent.PomodoroSkip(p), 9000020).state
        assertEquals(PomodoroPhase.LONG_BREAK, s.projects.getValue(p).pomodoro.phase)
        s = reducer.reduce(s, RuntimeEvent.SettingsApplied(s.settings.copy(pomodoroEnabled = false)), 9000030).state
        assertEquals(PomodoroPhase.IDLE, s.projects.getValue(p).pomodoro.phase)
    }
}
