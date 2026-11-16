package com.kgboard.rgb.bridge
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class EditorStateAdapterTest {
    @Test fun `cancelled reads stay unknown instead of reporting clean`() {
        val h = RuntimeHarness(); val p = ProjectId("p"); h.project(p)
        val revision = EditorRevision("file", 1, 1)
        h.send(RuntimeEvent.EditorSelected(p, revision, true))
        h.send(RuntimeEvent.AnalysisRequested(p, revision))
        h.send(RuntimeEvent.AnalysisCompleted(p, revision, AnalysisResult.Unknown))
        assertEquals(AnalysisResult.Unknown, h.snapshotForTest().projects.getValue(p).editor.analysis)
    }
}
