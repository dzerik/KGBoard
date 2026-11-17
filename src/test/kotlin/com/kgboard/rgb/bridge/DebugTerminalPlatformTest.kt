package com.kgboard.rgb.bridge

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusFactory
import com.intellij.util.messages.MessageBusOwner
import com.intellij.util.messages.impl.PluginListenerDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.xdebugger.*
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.kgboard.rgb.bridge.integration.*
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.lang.reflect.Proxy

class DebugTerminalPlatformTest {
    @Test
    fun `process outcome wins in both SDK callback orders including failure`() = fixture { project ->
        for (exit in listOf(1, 0)) for (debugFirst in listOf(true, false)) {
            scenario(project, true) { h, binding, source, signal, handler, env, lease ->
                handler.finish(exit)
                finish(source, signal, handler, env, debugFirst)
                h.drain()
                val operation = h.snapshotForTest().projects.getValue(binding.id).operations.values.single()
                assertEquals(
                    if (exit == 0) OperationOutcome.SUCCESS else OperationOutcome.FAILURE, operation.outcome,
                    "exit=$exit debugFirst=$debugFirst"
                )
                val expected = if (exit == 0) "debug.success" else "debug.failure"
                assertTrue(h.snapshotForTest().lastDecision!!.devices.flatMap { it.rules }
                    .any { it.ruleId == expected && it.visible.isNotEmpty() })
                assertEquals(0, binding.operations.activeCount)
            }
        }
    }

    @Test
    fun `detached SDK synthetic zero is not successful in either callback order`() = fixture { project ->
        for (debugFirst in listOf(true, false)) scenario(project, true) { h, binding, source, signal, handler, env, _ ->
            handler.detachProcess()
            assertEquals(0, handler.exitCode, "Pinned SDK synthesizes zero for detach")
            finish(source, signal, handler, env, debugFirst); h.drain()
            assertEquals(
                OperationOutcome.NOT_EXECUTED,
                h.snapshotForTest().projects.getValue(binding.id).operations.values.single().outcome
            )
            assertTrue(h.snapshotForTest().lastDecision!!.devices.flatMap { it.rules }
                .none { it.ruleId == "debug.success" && it.visible.isNotEmpty() })
        }
    }

    @Test
    fun `standalone stop without confirmed result never invents success`() = fixture { project ->
        for (detach in listOf(false, true)) scenario(project, false) { h, binding, _, signal, handler, _, _ ->
            if (detach) handler.detachProcess()
            signal.stop(); h.drain()
            assertEquals(
                OperationOutcome.NOT_EXECUTED,
                h.snapshotForTest().projects.getValue(binding.id).operations.values.single().outcome
            )
            assertEquals(0, binding.operations.activeCount)
        }
    }

    @Test
    fun `unloading debug metadata cannot erase authoritative process result`() = fixture { project ->
        scenario(project, true) { h, binding, source, signal, handler, env, lease ->
            handler.finish(1); finish(source, signal, handler, env, true); h.drain()
            val terminal = h.snapshotForTest().projects.getValue(binding.id).operations.values.single()
            // A paused callback can already be queued when core confirms termination.
            h.send(
                RuntimeEvent.ProviderFact(
                    lease, RuntimeEvent.OperationChanged(
                        binding.id,
                        terminal.copy(phase = OperationPhase.PAUSED, outcome = null, revision = terminal.revision - 1)
                    )
                )
            )
            h.send(RuntimeEvent.ProviderStopped(lease, "capability.unloaded"))
            assertEquals(
                OperationOutcome.FAILURE,
                h.snapshotForTest().projects.getValue(binding.id).operations.values.single().outcome
            )
        }
    }

    private fun finish(
        source: ExecutionListener,
        signal: SessionSignal,
        handler: Handler,
        env: ExecutionEnvironment,
        debugFirst: Boolean
    ) {
        if (debugFirst) signal.stop()
        source.processTerminated(DefaultDebugExecutor.EXECUTOR_ID, env, handler, handler.exitCode!!)
        if (!debugFirst) signal.stop()
    }

    private fun scenario(
        project: com.intellij.openapi.project.Project, processBacked: Boolean,
        check: (RuntimeHarness, ProjectRuntimeBinding, ExecutionListener, SessionSignal, Handler, ExecutionEnvironment, ProviderLease) -> Unit
    ) {
        val base = RuntimeSettings(dimOnFocusLoss = false)
        val rules = base.rules.mapValues { (id, rule) ->
            if (id in setOf(
                    "debug.success",
                    "debug.failure"
                )
            ) rule.copy(enabled = true) else rule
        }
        RuntimeHarness(
            base.copy(ruleRecords = base.effectiveRuleRecords().withEditedRules(rules)).withTestBindings()
        ).use { h ->
            val binding = ProjectRuntimeBinding(h.runtime, ProjectSettingsSnapshot()); binding.start(); h.project(
            binding.id
        )
            val core = ExecutionAdapter(project, binding); core.start()
            val lease = ProviderLease(binding.id, "debug", 1); h.send(RuntimeEvent.ProviderStarted(lease))
            val debug = DebugIntegrationProvider().create(IntegrationContext(project, lease, binding) {
                h.runtime.dispatch(RuntimeEvent.ProviderFact(lease, it))
            })
            val handler = Handler(); handler.startNotify()
            val env = ExecutionEnvironment().apply { executionId = 42 }
            val signal = SessionSignal(handler, if (processBacked) env else null)
            val source = project.messageBus.syncPublisher(ExecutionManager.EXECUTION_TOPIC)
            try {
                if (processBacked) {
                    source.processStartScheduled(DefaultDebugExecutor.EXECUTOR_ID, env); source.processStarted(
                        DefaultDebugExecutor.EXECUTOR_ID,
                        env,
                        handler
                    )
                }
                project.messageBus.syncPublisher(XDebuggerManager.TOPIC).processStarted(signal.process)
                h.drain()
                assertEquals(
                    OperationPhase.RUNNING,
                    h.snapshotForTest().projects.getValue(binding.id).operations.values.single().phase
                )
                check(h, binding, source, signal, handler, env, lease)
            } finally {
                Disposer.dispose(debug); Disposer.dispose(core); Disposer.dispose(env); binding.dispose(); h.drain()
            }
        }
    }

    private fun fixture(check: (com.intellij.openapi.project.Project) -> Unit) {
        val fixture: IdeaProjectTestFixture =
            IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        try {
            EdtTestUtil.runInEdtAndWait<Throwable> {
                // Isolate adapter callbacks from SDK listeners that require a backend XDebugSessionImpl.
                val bus = MessageBusFactory.newMessageBus(object : MessageBusOwner {
                    override fun isDisposed() = false
                    override fun createListener(descriptor: PluginListenerDescriptor): Any =
                        error("No lazy listeners in fixture")
                })
                val project = Proxy.newProxyInstance(
                    Project::class.java.classLoader,
                    arrayOf(Project::class.java)
                ) { _, method, args ->
                    if (method.name == "getMessageBus") bus else method.invoke(fixture.project, *(args ?: emptyArray()))
                } as Project
                try {
                    check(project)
                } finally {
                    bus.dispose()
                }
            }
        } finally {
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }

    private class Handler : ProcessHandler() {
        override fun destroyProcessImpl() {
            notifyProcessTerminated(1)
        }

        override fun detachProcessImpl() {
            notifyProcessDetached()
        }

        override fun detachIsDefault() = true
        override fun getProcessInput(): OutputStream? = null
        fun finish(exit: Int) {
            notifyProcessTerminated(exit)
        }
    }

    private class SessionSignal(handler: Handler, env: ExecutionEnvironment?) {
        private val listeners = mutableListOf<XDebugSessionListener>()
        val session: XDebugSession = Proxy.newProxyInstance(
            XDebugSession::class.java.classLoader,
            arrayOf(XDebugSession::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getDebugProcess" -> process
                "getExecutionEnvironment" -> env
                "isSuspended" -> false
                "addSessionListener" -> {
                    val listener = args[0] as XDebugSessionListener; listeners.add(listener)
                    (args.getOrNull(1) as? Disposable)?.let { parent ->
                        Disposer.register(
                            parent,
                            Disposable { listeners.remove(listener) })
                    }; null
                }

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "Fixture debug session"
                else -> error("Unexpected session call: ${method.name}")
            }
        } as XDebugSession
        val process = object : XDebugProcess(session) {
            override fun doGetProcessHandler() = handler
            override fun getEditorsProvider() = object : XDebuggerEditorsProvider() {
                override fun getFileType() = PlainTextFileType.INSTANCE
            }
        }

        fun stop() {
            listeners.toList().forEach { it.sessionStopped() }
        }
    }
}
