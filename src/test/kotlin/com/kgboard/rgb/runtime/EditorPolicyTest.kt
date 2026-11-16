package com.kgboard.rgb.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EditorPolicyTest {
    @Test fun `first reconnect frame cannot use old shortcut indices with unchanged LED count`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false)); val p = ProjectId("p"); h.project(p)
        h.send(RuntimeEvent.ProjectSettingsApplied(p, ProjectSettingsSnapshot(shortcutHighlightEnabled = true)))
        h.send(RuntimeEvent.ShortcutsChanged(p, setOf("debug"), mapOf("debug" to listOf(0)), DeviceMappingRevision(SessionId(1), 0)))
        val purple = LightingPolicy.color("#651FFF"); val idle = LightingPolicy.color(h.snapshotForTest().settings.idleColor)
        assertEquals(purple, h.lastFrame(0)[0])
        h.send(RuntimeEvent.ConnectRequested(Endpoint("new", 1)))
        val session = (h.snapshotForTest().connection as ConnectionState.Connecting).session
        h.send(RuntimeEvent.Connected(session, listOf(testDevice().copy(ledNames = listOf("Key: A", "Key: F5", "Key: B")))))
        assertEquals(List(3) { idle }, h.lastFrame(0))
        h.send(RuntimeEvent.ShortcutsChanged(p, setOf("debug"), mapOf("debug" to listOf(0)), DeviceMappingRevision(SessionId(1), 0)))
        assertEquals(List(3) { idle }, h.lastFrame(0))
        h.send(RuntimeEvent.ShortcutsChanged(p, setOf("debug"), mapOf("debug" to listOf(1)), DeviceMappingRevision(session, 0)))
        assertEquals(listOf(idle, purple, idle), h.lastFrame(0))
    }
    @Test fun `selection and document revision reject stale analysis and todos`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false, notifyTodoEnabled = true, notifyTodoLedIndices = "1"))
        val p = ProjectId("p"); h.project(p)
        h.send(RuntimeEvent.ProjectSettingsApplied(p, ProjectSettingsSnapshot(analysisLedIndices = "0")))
        val a = EditorRevision("a.kt", 1, 1); val b = EditorRevision("b.kt", 1, 2)
        h.send(RuntimeEvent.EditorSelected(p, a, true))
        h.send(RuntimeEvent.AnalysisCompleted(p, a, AnalysisResult.Ready(true, false)))
        assertEquals(LightingPolicy.color("#FF1744"), h.lastFrame(0)[0])
        h.send(RuntimeEvent.EditorSelected(p, b, true))
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0)[0])
        h.send(RuntimeEvent.AnalysisCompleted(p, a, AnalysisResult.Ready(false, false)))
        h.send(RuntimeEvent.TodosCompleted(p, a, true))
        assertEquals(AnalysisResult.Unknown, h.snapshotForTest().projects.getValue(p).editor.analysis)
        h.send(RuntimeEvent.AnalysisCompleted(p, b, AnalysisResult.Ready(false, false)))
        assertEquals(LightingPolicy.color("#00C853"), h.lastFrame(0)[0])
        h.send(RuntimeEvent.EditorSelected(p, EditorRevision("image.png", 1, 3), false))
        assertEquals(AnalysisResult.NotApplicable, h.snapshotForTest().projects.getValue(p).editor.analysis)
        h.send(RuntimeEvent.EditorSelected(p, null, false))
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0)[0])
    }
    @Test fun `indexing blocks stale clean and toggle removes indicator immediately`() {
        val h = RuntimeHarness(RuntimeSettings(dimOnFocusLoss = false, notifyIndexingLedIndices = "2"))
        val p = ProjectId("p"); h.project(p)
        val rev = EditorRevision("a", 1, 1)
        h.send(RuntimeEvent.EditorSelected(p, rev, true))
        h.send(RuntimeEvent.IndexingChanged(p, true))
        h.send(RuntimeEvent.AnalysisCompleted(p, rev, AnalysisResult.Ready(false, false)))
        assertEquals(AnalysisResult.Unknown, h.snapshotForTest().projects.getValue(p).editor.analysis)
        h.send(RuntimeEvent.SettingsApplied(h.snapshotForTest().settings.copy(notifyIndexingEnabled = false)))
        assertEquals(LightingPolicy.color(h.snapshotForTest().settings.idleColor), h.lastFrame(0)[2])
    }
}
