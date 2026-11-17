package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeReducerTest {
    private val reducer = RuntimeReducer()
    private val p = ProjectId("p")
    private fun opened() = reducer.reduce(RuntimeState(), RuntimeEvent.ProjectOpened(p), 0).state
    private fun change(state: RuntimeState, id: String, phase: OperationPhase, revision: Long = 1) =
        reducer.reduce(
            state, RuntimeEvent.OperationChanged(
                p,
                OperationState(OperationId(id), OperationKind.RUN, phase, revision = revision)
            ), 1
        ).state

    @Test
    fun `terminating A preserves running B`() {
        var state = change(change(opened(), "A", OperationPhase.RUNNING), "B", OperationPhase.RUNNING)
        state = change(state, "A", OperationPhase.TERMINATED, 2)
        assertEquals(OperationPhase.RUNNING, state.projects.getValue(p).operations.getValue(OperationId("B")).phase)
    }

    @Test
    fun `stale callback and duplicate finish cannot revive a process`() {
        var state = change(opened(), "A", OperationPhase.RUNNING, 2)
        state = change(state, "A", OperationPhase.TERMINATED, 3)
        assertEquals(state, change(state, "A", OperationPhase.RUNNING, 2))
        assertEquals(state, change(state, "A", OperationPhase.TERMINATED, 3))
        assertEquals(state, change(state, "A", OperationPhase.RUNNING, 4))
    }

    @Test
    fun `finish without a known start produces no success`() {
        val state = opened()
        assertEquals(state.copy(lastTime = TimeSample(1, 1)), change(state, "unknown", OperationPhase.TERMINATED))
    }

    @Test
    fun `closed project rejects late operation and open callbacks`() {
        val state = reducer.reduce(opened(), RuntimeEvent.ProjectClosed(p), 1).state
        assertEquals(state, change(state, "A", OperationPhase.RUNNING))
        assertEquals(
            state.copy(lastTime = TimeSample(2, 2)),
            reducer.reduce(state, RuntimeEvent.ProjectOpened(p), 2).state
        )
    }

    @Test
    fun `closing background project preserves foreground owner`() {
        val q = ProjectId("q")
        var state = reducer.reduce(opened(), RuntimeEvent.ProjectOpened(q), 1).state
        state = reducer.reduce(state, RuntimeEvent.ProjectActivated(p), 2).state
        state = reducer.reduce(state, RuntimeEvent.ProjectClosed(q), 3).state
        assertEquals(p, state.activeProject)
        assertTrue(state.projects.containsKey(p))
    }

    @Test
    fun `settings changes do not require reopening the project`() {
        val state =
            reducer.reduce(opened(), RuntimeEvent.SettingsApplied(RuntimeSettings(notifyTodoEnabled = true)), 1).state
        assertTrue(state.settings.notifyTodoEnabled)
        assertTrue(state.projects.containsKey(p))
    }
}
