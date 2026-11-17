package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PomodoroStateTest {
    @Test
    fun `ordinary event at phase deadline cannot consume the only wake or block probes`() {
        for (eventFirst in listOf(true, false)) {
            RuntimeHarness(
                RuntimeSettings(
                    pomodoroEnabled = true,
                    pomodoroWorkMinutes = 1
                ).withTestBindings()
            ).use { h ->
                h.send(RuntimeEvent.DisconnectRequested)
                h.send(RuntimeEvent.ConnectRequested())
                val session = (h.snapshotForTest().connection as ConnectionState.Connecting).session
                h.send(RuntimeEvent.Connected(session, testDevices()))
                val p = ProjectId("p"); h.project(p); h.send(RuntimeEvent.PomodoroStart(p))
                h.advanceBy(59_000)
                h.clock.value += 1000; h.clock.wallValue += 1000
                if (eventFirst) h.send(RuntimeEvent.FocusChanged(true))
                h.scheduler.advanceBy(0)
                if (!eventFirst) h.send(RuntimeEvent.FocusChanged(true))
                val phase = h.snapshotForTest().projects.getValue(p).pomodoro
                assertEquals(PomodoroPhase.BREAK, phase.phase, "eventFirst=$eventFirst")
                val seconds = phase.remainingSeconds(h.clock.sample())
                h.advanceBy(1000)
                assertEquals(
                    seconds - 1,
                    h.snapshotForTest().projects.getValue(p).pomodoro.remainingSeconds(h.clock.sample())
                )
                assertTrue(
                    requireNotNull(h.snapshotForTest().probeAtMs) > h.clock.value,
                    "A due phase must not suppress inventory probing"
                )
            }
        }
    }

    @Test
    fun `stop and skip at due deadline still take effect exactly once`() {
        for (stop in listOf(true, false)) {
            RuntimeHarness(
                RuntimeSettings(
                    pomodoroEnabled = true,
                    pomodoroWorkMinutes = 1
                ).withTestBindings()
            ).use { h ->
                val p = ProjectId("p"); h.project(p); h.send(RuntimeEvent.PomodoroStart(p)); h.advanceBy(59_000)
                h.clock.value += 1000; h.clock.wallValue += 1000
                h.send(if (stop) RuntimeEvent.PomodoroStop(p) else RuntimeEvent.PomodoroSkip(p))
                h.scheduler.advanceBy(0)
                val phase = h.snapshotForTest().projects.getValue(p).pomodoro
                assertEquals(if (stop) PomodoroPhase.IDLE else PomodoroPhase.BREAK, phase.phase)
                assertEquals(if (stop) 0 else 1, phase.completedSessions)
            }
        }
    }

    @Test
    fun `skip and stop fence old wake without decrementing new phase`() {
        val h = RuntimeHarness(RuntimeSettings(pomodoroEnabled = true).withTestBindings())
        val p = ProjectId("p"); h.project(p)
        h.send(RuntimeEvent.PomodoroStart(p)); h.advanceBy(500)
        h.send(RuntimeEvent.PomodoroSkip(p))
        val deadline = h.snapshotForTest().projects.getValue(p).pomodoro.deadlineMs
        h.send(RuntimeEvent.Wake(Long.MIN_VALUE))
        assertEquals(deadline, h.snapshotForTest().projects.getValue(p).pomodoro.deadlineMs)
        h.send(RuntimeEvent.PomodoroStop(p)); h.advanceBy(500000)
        assertEquals(PomodoroPhase.IDLE, h.snapshotForTest().projects.getValue(p).pomodoro.phase)
    }

    @Test
    fun `delayed scheduler advances one phase from current time and long break follows configured cycles`() {
        val reducer = RuntimeReducer();
        val p = ProjectId("p")
        var s = reducer.reduce(
            RuntimeState(
                settings = RuntimeSettings(
                    pomodoroEnabled = true,
                    pomodoroSessionsBeforeLongBreak = 2
                ).withTestBindings()
            ), RuntimeEvent.ProjectOpened(p), 0
        ).state
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
