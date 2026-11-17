package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IndicatorAdaptersTest {
    @Test
    fun `older git result cannot overwrite current facts`() {
        val h = RuntimeHarness();
        val p = ProjectId("p"); h.project(p)
        val fresh = gitTestFacts("main")
        h.send(RuntimeEvent.GitChanged(p, 2, fresh))
        h.send(RuntimeEvent.GitChanged(p, 1, GitFacts()))
        assertEquals(fresh, h.snapshotForTest().projects.getValue(p).git)
    }
}
