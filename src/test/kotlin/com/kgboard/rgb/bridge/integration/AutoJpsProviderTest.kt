package com.kgboard.rgb.bridge.integration

import com.intellij.compiler.server.BuildManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.bridge.jps.AutoJpsIntegrationProvider
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AutoJpsProviderTest {
    @Test
    fun `public automatic session UUID is tracked without guessing outcome or manual duplicates`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(
                        h.runtime,
                        ProjectSettingsSnapshot()
                    ); binding.start(); h.project(binding.id)
                    val lease = ProviderLease(binding.id, "build.automake", 1); h.send(
                    RuntimeEvent.ProviderStarted(
                        lease
                    )
                )
                    val adapter =
                        AutoJpsIntegrationProvider().create(IntegrationContext(fixture.project, lease, binding) {
                            h.runtime.dispatch(RuntimeEvent.ProviderFact(lease, it))
                        })
                    try {
                        val source =
                            ApplicationManager.getApplication().messageBus.syncPublisher(BuildManagerListener.TOPIC)
                        source.buildStarted(fixture.project, UUID.randomUUID(), false); h.drain()
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                        val session = UUID.randomUUID(); source.buildStarted(fixture.project, session, true); h.drain()
                        val running = h.snapshotForTest().projects.getValue(binding.id).operations.values.single()
                        assertEquals(OperationPhase.RUNNING, running.phase)
                        source.buildFinished(fixture.project, session, true); source.buildFinished(
                            fixture.project,
                            session,
                            true
                        ); h.drain()
                        val terminal = h.snapshotForTest().projects.getValue(binding.id).operations.values.single()
                        assertEquals(running.id, terminal.id); assertEquals(
                            OperationOutcome.NOT_EXECUTED,
                            terminal.outcome
                        )
                        assertEquals(2, terminal.revision)
                        assertTrue(
                            OperationPolicy().layers(h.snapshotForTest(), 0).none { it.ruleId == "build.success" })
                        Disposer.dispose(adapter); h.send(RuntimeEvent.ProviderStopped(lease, "capability.unloaded"))
                        source.buildStarted(fixture.project, UUID.randomUUID(), true); h.drain()
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                    } finally {
                        Disposer.dispose(adapter); binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }
}
