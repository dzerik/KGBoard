package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameFocusTrackerTest {
    @Test fun `application deactivation wins over stale AWT active window`() {
        val previous = FocusSnapshot(true, ProjectId("p"))
        assertFalse(resolveFocus(false, true, previous.project, previous).focused)
        assertEquals(previous, resolveFocus(true, false, null, previous))
        assertEquals(FocusSnapshot(true, null), resolveFocus(true, true, null, previous))
    }
    @Test fun `window owner follows current source and background close cannot reset it`() {
        val harness = RuntimeHarness()
        val a = ProjectId("a"); val b = ProjectId("b")
        harness.send(RuntimeEvent.ProjectOpened(a)); harness.send(RuntimeEvent.ProjectOpened(b))
        var focus = FocusSnapshot(true, a)
        var listener: (() -> Unit)? = null
        val tracker = FrameFocusTracker(object : FocusedProjectSource {
            override fun current() = focus
            override fun subscribe(callback: () -> Unit): Cancellation { listener = callback; return Cancellation { listener = null } }
        }, RuntimeEvents { harness.send(it) })
        tracker.start()
        assertEquals(a, harness.snapshotForTest().activeProject)
        focus = FocusSnapshot(true, b); listener!!()
        harness.send(RuntimeEvent.ProjectClosed(a))
        assertEquals(b, harness.snapshotForTest().activeProject)
        focus = FocusSnapshot(false, null); listener!!()
        assertEquals(b, harness.snapshotForTest().activeProject)
        assertFalse(harness.snapshotForTest().focused)
        tracker.close(); assertNull(listener)
    }
}
