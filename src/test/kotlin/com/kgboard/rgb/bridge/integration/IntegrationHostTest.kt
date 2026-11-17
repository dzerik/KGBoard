package com.kgboard.rgb.bridge.integration

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntegrationHostTest {
    @Test
    fun `late provider fact cannot restore stopped capability`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            val lease = ProviderLease(p, "tests.sm", 1)
            h.send(RuntimeEvent.ProviderStarted(lease))
            h.send(RuntimeEvent.ProviderStopped(lease, "capability.unloaded"))
            h.send(
                RuntimeEvent.ProviderFact(
                    lease, RuntimeEvent.OperationChanged(
                        p,
                        OperationState(OperationId("late"), OperationKind.TEST, OperationPhase.RUNNING)
                    )
                )
            )
            val project = h.snapshotForTest().projects.getValue(p)
            assertTrue(project.operations.isEmpty())
            assertEquals(CapabilityStatus.UNAVAILABLE, project.capabilities.getValue("tests.sm").status)
        }
    }

    @Test
    fun `unload clears only owned operations and cannot stop a newer generation`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            val first = ProviderLease(p, "tests.sm", 1)
            h.send(RuntimeEvent.ProviderStarted(first))
            h.send(
                RuntimeEvent.OperationChanged(
                    p,
                    OperationState(OperationId("run"), OperationKind.RUN, OperationPhase.RUNNING)
                )
            )
            h.send(
                RuntimeEvent.ProviderFact(
                    first, RuntimeEvent.OperationChanged(
                        p,
                        OperationState(OperationId("test"), OperationKind.TEST, OperationPhase.RUNNING)
                    )
                )
            )
            h.send(RuntimeEvent.ProviderStopped(first, "capability.unloaded"))
            assertEquals(setOf(OperationId("run")), h.snapshotForTest().projects.getValue(p).operations.keys)
            val second = first.copy(generation = 2)
            h.send(RuntimeEvent.ProviderStarted(second))
            h.send(
                RuntimeEvent.ProviderFact(
                    second, RuntimeEvent.OperationChanged(
                        p,
                        OperationState(OperationId("new"), OperationKind.TEST, OperationPhase.RUNNING)
                    )
                )
            )
            h.send(RuntimeEvent.ProviderStopped(first, "capability.unloaded"))
            h.send(RuntimeEvent.ProviderStarted(first))
            assertEquals(
                setOf(OperationId("run"), OperationId("new")),
                h.snapshotForTest().projects.getValue(p).operations.keys
            )
            assertEquals(second, h.snapshotForTest().projects.getValue(p).providerLeases["tests.sm"])
        }
    }

    @Test
    fun `new provider generation resets only its old facts and accepts revision one`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            val old = ProviderLease(p, "git", 1)
            h.send(RuntimeEvent.ProviderStarted(old))
            h.send(
                RuntimeEvent.ProviderFact(
                    old,
                    RuntimeEvent.GitChanged(p, 100, gitTestFacts("old"))
                )
            )
            h.send(RuntimeEvent.ProviderStopped(old, "capability.unloaded"))
            val fresh = old.copy(generation = 2)
            h.send(RuntimeEvent.ProviderStarted(fresh))
            h.send(
                RuntimeEvent.ProviderFact(
                    fresh,
                    RuntimeEvent.GitChanged(p, 1, gitTestFacts("new"))
                )
            )
            assertEquals("new", h.snapshotForTest().projects.getValue(p).git.branch)
        }
    }

    @Test
    fun `starting newer generation retires displaced ownership even if stop has not arrived`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p"); h.project(p)
            val old = ProviderLease(p, "tests.sm", 1)
            h.send(RuntimeEvent.ProviderStarted(old))
            h.send(
                RuntimeEvent.ProviderFact(
                    old, RuntimeEvent.OperationChanged(
                        p,
                        OperationState(OperationId("old"), OperationKind.TEST, OperationPhase.RUNNING)
                    )
                )
            )
            h.send(RuntimeEvent.ProviderStarted(old.copy(generation = 2)))
            assertTrue(h.snapshotForTest().projects.getValue(p).operations.isEmpty())
        }
    }

    @Test
    fun `providers cannot inject commands or facts for another project`() {
        RuntimeHarness().use { h ->
            val p = ProjectId("p");
            val q = ProjectId("q"); h.project(p); h.project(q)
            val lease = ProviderLease(p, "tests.sm", 1)
            h.send(RuntimeEvent.ProviderStarted(lease))
            for (command in listOf(
                RuntimeEvent.Shutdown, RuntimeEvent.DisconnectRequested,
                RuntimeEvent.SettingsApplied(RuntimeSettings(enabled = false)),
                RuntimeEvent.ProviderFact(lease, RuntimeEvent.Shutdown), RuntimeEvent.ProjectClosed(p)
            )) {
                h.send(RuntimeEvent.ProviderFact(lease, command))
            }
            h.send(
                RuntimeEvent.ProviderFact(
                    lease, RuntimeEvent.OperationChanged(
                        q,
                        OperationState(OperationId("foreign"), OperationKind.TEST, OperationPhase.RUNNING)
                    )
                )
            )
            assertFalse(h.snapshotForTest().shuttingDown)
            assertTrue(h.snapshotForTest().settings.enabled)
            assertTrue(h.snapshotForTest().connection is ConnectionState.Ready)
            assertTrue(h.snapshotForTest().projects.getValue(q).operations.isEmpty())
            h.send(RuntimeEvent.ProjectClosed(p))
            h.send(RuntimeEvent.ProviderStarted(lease.copy(generation = 99)))
            assertFalse(h.snapshotForTest().projects.containsKey(p))
        }
    }

    @Test
    fun `optional linkage failure becomes Error and late emitter remains fenced`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(h.runtime, ProjectSettingsSnapshot())
                    binding.start(); h.project(binding.id)
                    val lifetime = Disposer.newDisposable()
                    lateinit var late: (RuntimeEvent) -> Unit
                    val seen = mutableSetOf<OperationId>()
                    val observation =
                        h.runtime.subscribe { state -> seen.addAll(state.projects[binding.id]?.operations?.keys.orEmpty()) }
                    h.drain()
                    val provider = object : IntegrationProvider {
                        override val capabilityId = "tests.failed"
                        override fun create(context: IntegrationContext): Disposable {
                            if (context.binding !== binding) return Disposable {}
                            late = context.emit
                            context.emit(
                                RuntimeEvent.OperationChanged(
                                    binding.id,
                                    OperationState(OperationId("partial"), OperationKind.TEST, OperationPhase.RUNNING)
                                )
                            )
                            throw NoClassDefFoundError("optional.sdk.Missing")
                        }
                    }
                    IntegrationProvider.EP_NAME.point.registerExtension(provider, lifetime)
                    val host = IntegrationHost(fixture.project, binding)
                    try {
                        assertDoesNotThrow { host.start() }; h.drain()
                        val capability =
                            h.snapshotForTest().projects.getValue(binding.id).capabilities.getValue("tests.failed")
                        assertFalse(
                            OperationId("partial") in seen,
                            "A failed constructor must never publish partial operation facts"
                        )
                        assertEquals(CapabilityStatus.ERROR, capability.status)
                        assertEquals("capability.failed", capability.reasonKey)
                        late(
                            RuntimeEvent.OperationChanged(
                                binding.id,
                                OperationState(OperationId("late"), OperationKind.TEST, OperationPhase.RUNNING)
                            )
                        )
                        h.drain()
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                    } finally {
                        observation.cancel(); Disposer.dispose(host); Disposer.dispose(lifetime); binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }

    @Test
    fun `provider disposal does not hold the callback gate while waiting for a source`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(h.runtime, ProjectSettingsSnapshot())
                    binding.start(); h.project(binding.id)
                    val lifetime = Disposer.newDisposable()
                    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
                    var callbackFinishedDuringDispose = false
                    val provider = object : IntegrationProvider {
                        override val capabilityId = "tests.close"
                        override fun create(context: IntegrationContext): Disposable {
                            if (context.binding !== binding) return Disposable {}
                            return Disposable {
                                val callback = worker.submit {
                                    context.emit(
                                        RuntimeEvent.OperationChanged(
                                            binding.id,
                                            OperationState(
                                                OperationId("late"),
                                                OperationKind.TEST,
                                                OperationPhase.RUNNING
                                            )
                                        )
                                    )
                                }
                                callbackFinishedDuringDispose = runCatching {
                                    callback.get(
                                        250,
                                        java.util.concurrent.TimeUnit.MILLISECONDS
                                    )
                                }.isSuccess
                            }
                        }
                    }
                    IntegrationProvider.EP_NAME.point.registerExtension(provider, lifetime)
                    val host = IntegrationHost(fixture.project, binding)
                    try {
                        host.start(); h.drain()
                        Disposer.dispose(lifetime); h.drain()
                        assertTrue(
                            callbackFinishedDuringDispose,
                            "Disposal must not deadlock a source draining its callbacks"
                        )
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                    } finally {
                        Disposer.dispose(host)
                        if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime)
                        worker.shutdownNow(); assertTrue(
                            worker.awaitTermination(
                                5,
                                java.util.concurrent.TimeUnit.SECONDS
                            )
                        )
                        binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }

    @Test
    fun `real extension removal disposes before Unavailable and invalidates queued emitter`() {
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                RuntimeHarness().use { h ->
                    val binding = ProjectRuntimeBinding(h.runtime, ProjectSettingsSnapshot())
                    binding.start(); h.project(binding.id)
                    val lifetime = Disposer.newDisposable()
                    val order = mutableListOf<String>()
                    var active = 0
                    lateinit var emit: (RuntimeEvent) -> Unit
                    val provider = object : IntegrationProvider {
                        override val capabilityId = "tests.fixture"
                        override fun create(context: IntegrationContext): Disposable {
                            // The fixture may also have its real IDE service host; observe only this synthetic binding.
                            if (context.binding !== binding) return Disposable {}
                            active++; emit = context.emit
                            return Disposable { active--; order.add("disposed") }
                        }
                    }
                    IntegrationProvider.EP_NAME.point.registerExtension(provider, lifetime)
                    val host = IntegrationHost(fixture.project, binding)
                    var lastStatus: CapabilityStatus? = null
                    val observation = h.runtime.subscribe { state ->
                        val status = state.projects[binding.id]?.capabilities?.get("tests.fixture")?.status
                        if (status == CapabilityStatus.UNAVAILABLE && status != lastStatus) order.add("unavailable")
                        lastStatus = status
                    }
                    h.drain()
                    try {
                        host.start(); host.start(); h.drain()
                        assertEquals(1, active)
                        Disposer.dispose(lifetime); h.drain()
                        assertEquals(0, active)
                        assertEquals(listOf("disposed", "unavailable"), order)
                        emit(
                            RuntimeEvent.OperationChanged(
                                binding.id,
                                OperationState(OperationId("late"), OperationKind.TEST, OperationPhase.RUNNING)
                            )
                        )
                        h.drain()
                        assertTrue(h.snapshotForTest().projects.getValue(binding.id).operations.isEmpty())
                    } finally {
                        observation.cancel(); Disposer.dispose(host)
                        if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime)
                        binding.dispose(); h.drain()
                    }
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }
}
