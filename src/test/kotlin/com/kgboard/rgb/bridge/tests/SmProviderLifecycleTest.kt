package com.kgboard.rgb.bridge.tests

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.bridge.integration.IntegrationContext
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmProviderLifecycleTest {
    @Test
    fun `unload retires owned identities and reattach tracks the same running root with a fresh identity`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(h.runtime, ProjectSettingsSnapshot())
                    binding.start(); h.project(binding.id)
                    val root = SMTestProxy.SMRootTestProxy(); root.setStarted()
                    val events = fixture.project.messageBus.syncPublisher(SMTRunnerEventsListener.TEST_STATUS)
                    fun attach(generation: Long): Pair<ProviderLease, com.intellij.openapi.Disposable> {
                        val lease = ProviderLease(binding.id, "tests.sm", generation)
                        h.send(RuntimeEvent.ProviderStarted(lease))
                        val adapter =
                            SmTestIntegrationProvider().create(IntegrationContext(fixture.project, lease, binding) {
                                h.runtime.dispatch(RuntimeEvent.ProviderFact(lease, it))
                            })
                        return lease to adapter
                    }

                    val first = attach(1)
                    var second: Pair<ProviderLease, com.intellij.openapi.Disposable>? = null
                    try {
                        events.onTestingStarted(root); h.drain()
                        val firstId = h.snapshotForTest().projects.getValue(binding.id).operations.keys.single()
                        assertEquals(1, binding.operations.activeCount)
                        Disposer.dispose(first.second)
                        h.send(RuntimeEvent.ProviderStopped(first.first, "capability.unloaded"))
                        assertEquals(
                            0,
                            binding.operations.activeCount,
                            "Disposed test roots must not retain active identities"
                        )
                        second = attach(2)
                        events.onTestingStarted(root); h.drain()
                        val secondId = h.snapshotForTest().projects.getValue(binding.id).operations.keys.single()
                        assertNotEquals(firstId, secondId)
                        assertEquals(1, binding.operations.activeCount)
                        root.setFinished(); events.onTestingFinished(root); h.drain()
                        assertEquals(0, binding.operations.activeCount)
                        events.onTestingStarted(root); h.drain()
                        assertEquals(
                            0,
                            binding.operations.activeCount,
                            "Duplicate callbacks must not resurrect finished roots"
                        )
                    } finally {
                        if (!Disposer.isDisposed(first.second)) Disposer.dispose(first.second)
                        second?.second?.let { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
                        binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }
}
