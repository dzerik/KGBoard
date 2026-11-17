package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.diagnostics.DiagnosticsView
import com.kgboard.rgb.diagnostics.DiagnosticsExport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GitRuntimeTest {
    private fun sample(
        id: String, head: Long, request: Long, branch: String = "main", ahead: Int? = 0, behind: Int? = 0,
        upstream: UpstreamState = UpstreamState.PRESENT
    ) = GitSample(
        RepoRevision(id, request, head),
        RepositoryFacts(
            id, "a".repeat(40), branch, HeadState.BRANCH, upstream, false, 0, ahead, behind,
            RepositoryOperation.NONE, Freshness.FRESH, 0
        )
    )

    @Test
    fun `actor rejects old branch completion even in newer batch and retires removed repositories`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            h.send(RuntimeEvent.GitChanged(p, 1, GitFacts(listOf(sample("r", 2, 3, "new")))))
            h.send(RuntimeEvent.GitChanged(p, 2, GitFacts(listOf(sample("r", 1, 2, "old")))))
            assertEquals("new", h.snapshotForTest().projects.getValue(p).git.repositories.single().facts.branch)
            h.send(RuntimeEvent.GitChanged(p, 3, GitFacts()))
            assertTrue(h.snapshotForTest().projects.getValue(p).git.repositories.isEmpty())
        }
    }

    @Test
    fun `mixed repositories keep separate directions and no upstream never renders synced`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            h.send(
                RuntimeEvent.GitChanged(
                    p,
                    1,
                    GitFacts(listOf(sample("a", 0, 1, ahead = 2), sample("b", 0, 1, behind = 3)))
                )
            )
            val rows = DiagnosticsView.from(h.snapshotForTest()).gitRows
            assertEquals(2, rows.size); assertTrue(rows.any { it.contains("Ahead") && it.contains("2") })
            assertTrue(rows.any { it.contains("Behind") && it.contains("3") })
            h.send(
                RuntimeEvent.GitChanged(
                    p,
                    2,
                    GitFacts(
                        listOf(
                            sample(
                                "r",
                                0,
                                1,
                                "PRIVATE_BRANCH",
                                ahead = null,
                                behind = null,
                                upstream = UpstreamState.NONE
                            )
                        )
                    )
                )
            )
            assertTrue(IndicatorPolicy().layers(h.snapshotForTest(), 0).none { it.ruleId == "git.synced" })
            assertFalse(
                DiagnosticsExport.copyText(h.snapshotForTest().diagnostics, emptyMap(), emptyList())
                    .contains("PRIVATE_BRANCH")
            )
        }
    }

    @Test
    fun `Git export includes safe per-repository status without path branch or raw error`() {
        val repositorySample = sample("/private/repo", 0, 1, "secret-branch").copy(
            facts = RepositoryFacts.unknown("/private/repo", 0).copy(branch = "secret-branch"), errorKey = "git.timeout"
        )
        val text = DiagnosticsExport.copyText(
            com.kgboard.rgb.diagnostics.DiagnosticsSnapshot(), emptyMap(), emptyList(),
            listOf(repositorySample, repositorySample.copy(errorKey = "raw /private/error"))
        )
        assertTrue(text.contains("git[1].state=unknown")); assertTrue(text.contains("git.timeout"))
        assertFalse(text.contains("/private/")); assertFalse(text.contains("secret-branch"))
    }

    @Test
    fun `branch preview follows repository selected for worst indicator`() {
        val facts = GitFacts(listOf(sample("a", 0, 1, "quiet"), sample("b", 0, 1, "incoming", behind = 3)))
        assertEquals("incoming", facts.branch)
    }

    @Test
    fun `cancel at process handoff destroys process even before SDK field assignment`() {
        val cancellation = GitCancellation();
        val slot = GitProcessSlot(cancellation)
        val p = object : Process() {
            var killed = false
            override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
            override fun getInputStream() = java.io.InputStream.nullInputStream()
            override fun getErrorStream() = java.io.InputStream.nullInputStream()
            override fun waitFor() = 0;
            override fun exitValue() = 0;
            override fun destroy() {
                killed = true
            }

            override fun destroyForcibly(): Process {
                killed = true; return this
            }
        }
        cancellation.cancel(); slot.attach(p); assertTrue(p.killed)
        val second = GitCancellation();
        val live = GitProcessSlot(second); p.killed = false
        live.attach(p); second.cancel(); assertTrue(p.killed)
    }
}
