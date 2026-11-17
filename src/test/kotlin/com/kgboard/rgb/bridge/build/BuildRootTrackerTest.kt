package com.kgboard.rgb.bridge.build

import com.kgboard.rgb.runtime.*
import com.intellij.build.events.impl.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class BuildRootTrackerTest {
    @Test
    fun `unknown finish duplicate terminal and retired start never invent success`() {
        val events = mutableListOf<OperationState>();
        val tracker = BuildRootTracker(events::add)
        val root = Any(); tracker.accept(BuildObservation.Finished(root, OperationOutcome.SUCCESS))
        assertTrue(events.isEmpty()); tracker.accept(BuildObservation.Started(root))
        tracker.accept(BuildObservation.Finished(root, OperationOutcome.NOT_EXECUTED))
        tracker.accept(
            BuildObservation.Finished(
                root,
                OperationOutcome.SUCCESS
            )
        ); tracker.accept(BuildObservation.Started(root))
        assertEquals(2, events.size); assertEquals(OperationOutcome.NOT_EXECUTED, events.last().outcome)
        assertEquals(listOf(1L, 2L), events.map { it.revision }); assertEquals(events.first().id, events.last().id)
    }

    @Test
    fun `parallel roots are independent and disposal retires live roots once`() {
        val events = mutableListOf<OperationState>();
        val tracker = BuildRootTracker(events::add)
        val a = Any();
        val b = Any(); tracker.accept(BuildObservation.Started(a)); tracker.accept(BuildObservation.Started(b))
        assertNotEquals(events[0].id, events[1].id)
        tracker.accept(BuildObservation.Finished(a, OperationOutcome.FAILURE)); tracker.close(); tracker.close()
        tracker.accept(BuildObservation.Finished(b, OperationOutcome.SUCCESS))
        assertEquals(4, events.size); assertEquals(OperationOutcome.NOT_EXECUTED, events.last().outcome)
    }

    @Test
    fun `SDK results distinguish failure skip unknown and confirmed nested cancellation`() {
        assertEquals(OperationOutcome.SUCCESS, buildOutcome(SuccessResultImpl()))
        assertEquals(OperationOutcome.NOT_EXECUTED, buildOutcome(SkippedResultImpl()))
        assertEquals(OperationOutcome.NOT_EXECUTED, buildOutcome(null))
        assertEquals(OperationOutcome.FAILURE, buildOutcome(FailureResultImpl(IllegalStateException("failure"))))
        assertEquals(
            OperationOutcome.CANCELLED,
            buildOutcome(FailureResultImpl(RuntimeException("wrapper", CancellationException())))
        )
    }

    @Test
    fun `SDK child or generic start events never create global build roots`() {
        val root = Any();
        val child = Any()
        val descriptor = com.intellij.build.DefaultBuildDescriptor(root, "Fixture", "/tmp", 0)
        assertEquals(BuildObservation.Started(root), buildObservation(StartBuildEventImpl(descriptor, "Started")))
        assertNull(buildObservation(StartEventImpl(child, root, 0, "Child")))
        assertNull(buildObservation(FinishBuildEventImpl(child, root, 0, "Child complete", SuccessResultImpl())))
        assertEquals(
            BuildObservation.Finished(root, OperationOutcome.NOT_EXECUTED),
            buildObservation(FinishBuildEventImpl(root, null, 0, "Skipped", SkippedResultImpl()))
        )
    }

}
