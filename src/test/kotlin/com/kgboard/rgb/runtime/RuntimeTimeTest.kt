package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeTimeTest {
    @Test
    fun `clock rollback cannot extend deadline and sleep expires it`() {
        val deadline = RuntimeDeadline.after(10_000, TimeSample(1000, 100_000), 1)
        assertEquals(5000L, deadline.remaining(TimeSample(6000, 1)))
        assertEquals(0L, deadline.remaining(TimeSample(1001, 28_900_000)))
    }

    @Test
    fun `resume gap threshold uses absolute wall monotonic divergence`() {
        val start = TimeSample(0, 0)
        assertFalse(ResumeDetector.observe(start, TimeSample(1000, 11_000)))
        assertTrue(ResumeDetector.observe(start, TimeSample(1000, 11_001)))
        assertTrue(ResumeDetector.observe(start, TimeSample(11_001, 0)))
    }

    @Test
    fun `deadline arithmetic saturates instead of wrapping expired`() {
        val deadline = RuntimeDeadline.after(100, TimeSample(Long.MAX_VALUE - 5, Long.MAX_VALUE - 2), 1)
        assertEquals(2L, deadline.remaining(TimeSample(Long.MAX_VALUE - 5, Long.MAX_VALUE - 2)))
        assertEquals(0L, deadline.remaining(TimeSample(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, safeDifference(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, safeDifference(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun `wall expiry removes terminal layer preview and indicator visuals without a resume reset`() {
        RuntimeHarness(
            RuntimeSettings(
                fileSaveFlashEnabled = true,
                dimOnFocusLoss = false
            ).withTestBindings()
        ).use { h ->
            val p = ProjectId("p"); h.project(p)
            h.send(
                RuntimeEvent.OperationChanged(
                    p,
                    OperationState(OperationId("run"), OperationKind.RUN, OperationPhase.RUNNING)
                )
            )
            h.send(
                RuntimeEvent.OperationChanged(
                    p, OperationState(
                        OperationId("run"), OperationKind.RUN, OperationPhase.TERMINATED,
                        OperationOutcome.SUCCESS, revision = 2
                    )
                )
            )
            h.send(RuntimeEvent.DocumentsSaved(p, 1)); h.send(RuntimeEvent.LowMemory)
            h.send(
                RuntimeEvent.LayerChanged(
                    EffectLayer(
                        EffectKey(p, "temporary"),
                        com.kgboard.rgb.effect.StaticEffect(java.awt.Color.RED),
                        0,
                        1000
                    )
                )
            )
            val session = com.kgboard.rgb.settings.SettingsPreviewSession(h.runtime)
            session.previewColor(h.snapshotForTest(), 0, java.awt.Color.BLUE, listOf(0)); h.drain()
            val after = RuntimeReducer().reduce(
                h.snapshotForTest(),
                RuntimeEvent.FocusChanged(true),
                TimeSample(1, 30_000)
            ).state
            assertTrue(after.layers.isEmpty())
            assertTrue(after.projects.getValue(p).operations.isEmpty())
            assertNull(after.preview)
            assertTrue(IndicatorPolicy().layers(after, 1).none { it.ruleId in setOf("fileSave", "lowMemory") })
            assertNull(LightingPolicy().nextWakeAt(after, 1))
            session.close(); h.drain()
        }
    }

    @Test
    fun `pomodoro remaining time uses both clocks without integer overflow`() {
        val settings = RuntimeSettings(pomodoroWorkMinutes = 1)
        val state = PomodoroState().next(settings, TimeSample(10, 100_000))
        assertEquals(30, state.remainingSeconds(TimeSample(30_010, 1)))
        assertEquals(0, state.remainingSeconds(TimeSample(11, 200_000)))
        assertEquals(
            1, PomodoroState().next(settings, TimeSample(Long.MAX_VALUE - 2, Long.MAX_VALUE - 2))
                .remainingSeconds(TimeSample(Long.MAX_VALUE - 2, Long.MAX_VALUE - 2))
        )
    }
}
