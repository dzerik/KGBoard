package com.kgboard.rgb.bridge.integration

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.impl.*
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfigurationViewManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.bridge.build.BuildIntegrationProvider
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildViewProviderTest {
    @Test
    fun `parallel SDK roots retain independent outcomes through provider envelopes`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(
                        h.runtime,
                        ProjectSettingsSnapshot()
                    ); binding.start(); h.project(binding.id)
                    val lease = ProviderLease(binding.id, "build.roots", 1); h.send(RuntimeEvent.ProviderStarted(lease))
                    val adapter = BuildIntegrationProvider().create(
                        IntegrationContext(
                            fixture.project,
                            lease,
                            binding
                        ) { h.runtime.dispatch(RuntimeEvent.ProviderFact(lease, it)) })
                    try {
                        // Run-view delivery is synchronous; BuildViewManager queues delivery beyond this EDT fixture.
                        val source = fixture.project.getService(ExternalSystemRunConfigurationViewManager::class.java)
                        val roots = List(3) { Any() }
                        roots.forEach { id ->
                            source.onEvent(
                                id,
                                StartBuildEventImpl(DefaultBuildDescriptor(id, "Fixture", "/tmp", 0), "Started")
                            )
                        }
                        h.drain()
                        assertEquals(3, h.snapshotForTest().projects.getValue(binding.id).operations.size)
                        val results = listOf(
                            SuccessResultImpl(),
                            FailureResultImpl(IllegalStateException()),
                            FailureResultImpl(java.util.concurrent.CancellationException())
                        )
                        roots.zip(results).forEach { (id, result) ->
                            source.onEvent(
                                id,
                                FinishBuildEventImpl(id, null, 0, "Done", result)
                            )
                        }
                        h.drain()
                        val operations = h.snapshotForTest().projects.getValue(binding.id).operations.values
                        assertEquals(
                            setOf(
                                OperationOutcome.SUCCESS,
                                OperationOutcome.FAILURE,
                                OperationOutcome.CANCELLED
                            ), operations.map { it.outcome }.toSet()
                        )
                        assertTrue(operations.all { it.phase == OperationPhase.TERMINATED && it.revision == 2L })
                        assertEquals(
                            setOf("build.success", "build.failure", "build.cancelled"),
                            OperationPolicy().layers(h.snapshotForTest(), 0).map { it.ruleId }.toSet()
                        )
                    } finally {
                        Disposer.dispose(adapter); binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }

    @Test
    fun `public managers share one root and unload rejects late SDK events`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(
                        h.runtime,
                        ProjectSettingsSnapshot()
                    ); binding.start(); h.project(binding.id)
                    val lease = ProviderLease(binding.id, "build.roots", 1); h.send(RuntimeEvent.ProviderStarted(lease))
                    val adapter = BuildIntegrationProvider().create(
                        IntegrationContext(
                            fixture.project,
                            lease,
                            binding
                        ) { h.runtime.dispatch(RuntimeEvent.ProviderFact(lease, it)) })
                    try {
                        val run = fixture.project.getService(ExternalSystemRunConfigurationViewManager::class.java)
                        val build = fixture.project.getService(BuildViewManager::class.java)
                        val id = Any();
                        val start = StartBuildEventImpl(DefaultBuildDescriptor(id, "Fixture", "/tmp", 0), "Started")
                        run.onEvent(id, FinishBuildEventImpl(id, null, 0, "Unseen", SuccessResultImpl())); h.drain()
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                        run.onEvent(id, start); build.onEvent(id, start); h.drain()
                        assertEquals(1, h.snapshotForTest().projects.getValue(binding.id).operations.size)
                        run.onEvent(id, FinishBuildEventImpl(id, null, 0, "Skipped", SkippedResultImpl()))
                        build.onEvent(
                            id,
                            FinishBuildEventImpl(id, null, 0, "Duplicate", SuccessResultImpl())
                        ); h.drain()
                        val op = h.snapshotForTest().projects.getValue(binding.id).operations.values.single()
                        assertEquals(OperationOutcome.NOT_EXECUTED, op.outcome); assertEquals(2, op.revision)
                        Disposer.dispose(adapter); h.send(RuntimeEvent.ProviderStopped(lease, "capability.unloaded"))
                        val late = Any(); run.onEvent(
                            late,
                            StartBuildEventImpl(DefaultBuildDescriptor(late, "Late", "/tmp", 0), "Late")
                        ); h.drain()
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
