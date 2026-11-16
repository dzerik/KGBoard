package com.kgboard.rgb.pomodoro
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class PomodoroFacadeTest {
    @Test fun `remaining time rounds up and never becomes negative`() {
        val state = PomodoroState(PomodoroPhase.WORK, deadlineMs = 1001)
        assertEquals(2, state.remainingSeconds(0))
        assertEquals(0, state.remainingSeconds(5000))
    }
}
