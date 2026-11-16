package com.kgboard.rgb.runtime

import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationPolicyTest {
    @Test fun `finishing debug reveals surviving run in every termination order`() {
        repeat(2) { order ->
            val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false, runColor = "#00FF00", debugColor = "#FF0000", debugBreakpointFlashEnabled = true, debugBreakpointFlashColor = "#FF0000"))
            val p = ProjectId("p"); h.project(p)
            val a = OperationState(OperationId("a"), OperationKind.RUN, OperationPhase.RUNNING)
            val b = OperationState(OperationId("b"), OperationKind.DEBUG, OperationPhase.PAUSED)
            h.send(RuntimeEvent.OperationChanged(p, a)); h.send(RuntimeEvent.OperationChanged(p, b))
            assertEquals(Color.RED, h.lastFrame(0).first())
            val first = if (order == 0) b else a
            h.send(RuntimeEvent.OperationChanged(p, first.copy(phase = OperationPhase.TERMINATED, outcome = OperationOutcome.SUCCESS, revision = 2)))
            if (order == 0) assertEquals(Color.GREEN, h.lastFrame(0).first())
        }
    }
    @Test fun `operation completed while disabled does not replay on enable`() {
        val h = RuntimeHarness(RuntimeSettings(enabled = false, dimOnFocusLoss = false)); val p = ProjectId("p"); h.project(p)
        val op = OperationState(OperationId("disabled"), OperationKind.BUILD, OperationPhase.RUNNING)
        h.send(RuntimeEvent.OperationChanged(p, op))
        h.send(RuntimeEvent.OperationChanged(p, op.copy(phase = OperationPhase.TERMINATED, outcome = OperationOutcome.FAILURE, revision = 2)))
        h.send(RuntimeEvent.SettingsApplied(h.snapshotForTest().settings.copy(enabled = true)))
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0)[0])
    }
    @Test fun `cancelled and empty tests never produce pass and terminal facts expire`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false))
        val p = ProjectId("p"); h.project(p)
        for (outcome in listOf(OperationOutcome.CANCELLED, OperationOutcome.NOT_EXECUTED)) {
            val op = OperationState(OperationId(outcome.name), OperationKind.TEST, OperationPhase.RUNNING)
            h.send(RuntimeEvent.OperationChanged(p, op))
            h.send(RuntimeEvent.OperationChanged(p, op.copy(phase = OperationPhase.TERMINATED, outcome = outcome, revision = 2)))
            assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0).first())
        }
        h.advanceBy(3000)
        assertTrue(h.snapshotForTest().projects.getValue(p).operations.isEmpty())
    }
}
