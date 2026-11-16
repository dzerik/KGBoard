package com.kgboard.rgb.bridge

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.task.*
import com.intellij.xdebugger.*
import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap

/** All subscriptions exist independently of feature flags. No lighting or network calls. */
class IdeOperationAdapter(private val project: Project, private val binding: ProjectRuntimeBinding) : Disposable {
    private val registry = OperationIdentityRegistry()
    private val pending = mutableMapOf<Long, Any>()
    private val processKeys = WeakHashMap<ProcessHandler, Any>()
    private val sessions = WeakHashMap<XDebugSession, Boolean>()
    private val roots = WeakHashMap<SMTestProxy.SMRootTestProxy, Boolean>()
    private var closed = false

    @Synchronized private fun processKey(handler: ProcessHandler, executionId: Long = 0): Any =
        processKeys.getOrPut(handler) { pending.remove(executionId) ?: Any() }

    @Synchronized private fun change(key: Any, kind: OperationKind, phase: OperationPhase,
        outcome: OperationOutcome? = null, parent: OperationId? = null, expected: Long? = null) {
        if (closed) return
        val id = registry.id(key) ?: return
        val revision = registry.nextRevision(id, expected) ?: return
        binding.emit(RuntimeEvent.OperationChanged(binding.id, OperationState(id, kind, phase, outcome, parent, revision)))
        if (phase == OperationPhase.TERMINATED) registry.release(id)
    }
    private fun kind(executorId: String) = if (executorId == DefaultDebugExecutor.EXECUTOR_ID) OperationKind.DEBUG else OperationKind.RUN
    fun start() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                val key = synchronized(this@IdeOperationAdapter) { pending.getOrPut(env.executionId) { Any() } }
                change(key, kind(executorId), OperationPhase.STARTING)
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                val key = synchronized(this@IdeOperationAdapter) { pending.remove(env.executionId) } ?: return
                change(key, kind(executorId), OperationPhase.TERMINATED, OperationOutcome.CANCELLED)
            }
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val key = processKey(handler, env.executionId)
                change(key, kind(executorId), OperationPhase.RUNNING)
                reconcileTests()
            }
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                val key = processKey(handler, env.executionId)
                change(key, kind(executorId), OperationPhase.TERMINATED,
                    if (exitCode == 0) OperationOutcome.SUCCESS else OperationOutcome.FAILURE)
            }
        })
        bus.subscribe(RunContentManager.TOPIC, object : RunContentWithExecutorListener {
            override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) = reconcileTests()
            override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) = reconcileTests()
        })
        bus.subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
            override fun started(context: ProjectTaskContext) = change(context.sessionId ?: context, OperationKind.BUILD, OperationPhase.RUNNING)
            override fun finished(result: ProjectTaskManager.Result) = change(result.context.sessionId ?: result.context,
                OperationKind.BUILD, OperationPhase.TERMINATED, when {
                    result.isAborted -> OperationOutcome.CANCELLED
                    result.hasErrors() -> OperationOutcome.FAILURE
                    else -> OperationOutcome.SUCCESS
                })
        })
        bus.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(root: SMTestProxy.SMRootTestProxy) {
                synchronized(this@IdeOperationAdapter) { roots[root] = true }
                change(root, OperationKind.TEST, OperationPhase.RUNNING, parent = parent(root))
            }
            override fun onTestingFinished(root: SMTestProxy.SMRootTestProxy) {
                change(root, OperationKind.TEST, OperationPhase.TERMINATED, outcome(root), parent(root))
                synchronized(this@IdeOperationAdapter) { roots.remove(root) }
            }
        })
        bus.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) = attach(debugProcess.session)
        })
        // Subscribe first, then reconcile existing facts. Terminal operations are never guessed.
        XDebuggerManager.getInstance(project).debugSessions.forEach(::attach)
        val debugHandlers = XDebuggerManager.getInstance(project).debugSessions.map { it.debugProcess.processHandler }.toSet()
        RunContentManager.getInstance(project).allDescriptors.forEach { descriptor ->
            val handler = descriptor.processHandler ?: return@forEach
            val key = processKey(handler, descriptor.executionId)
            if (!handler.isProcessTerminated && handler !in debugHandlers) change(key, OperationKind.RUN, OperationPhase.RUNNING, expected = 0)
            val root = (descriptor.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode
            if (root != null && root.isInProgress) {
                synchronized(this) { roots[root] = true }
                change(root, OperationKind.TEST, OperationPhase.RUNNING, parent = parent(root), expected = 0)
            }
        }
    }
    private fun parent(root: SMTestProxy.SMRootTestProxy): OperationId? {
        val descriptor = RunContentManager.getInstance(project).allDescriptors.firstOrNull {
            (it.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode === root
        } ?: return null
        val handler = descriptor.processHandler ?: return null
        return registry.id(processKey(handler, descriptor.executionId))
    }
    private fun reconcileTests() {
        synchronized(this) { roots.keys.toList() }.forEach { root ->
            val parent = parent(root) ?: return@forEach
            change(root, OperationKind.TEST, OperationPhase.RUNNING, parent = parent)
        }
    }
    private fun attach(session: XDebugSession) {
        synchronized(this) { if (closed || sessions.put(session, true) != null) return }
        val handler = session.debugProcess.processHandler
        val key = processKey(handler, session.executionEnvironment?.executionId ?: 0)
        val observed = registry.id(key)?.let { registry.revision(it) } ?: return
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = change(key, OperationKind.DEBUG, OperationPhase.PAUSED)
            override fun sessionResumed() = change(key, OperationKind.DEBUG, OperationPhase.RUNNING)
            override fun sessionStopped() = change(key, OperationKind.DEBUG, OperationPhase.TERMINATED, OperationOutcome.SUCCESS)
        }, this)
        change(key, OperationKind.DEBUG, if (session.isSuspended) OperationPhase.PAUSED else OperationPhase.RUNNING, expected = observed)
    }
    @Synchronized override fun dispose() { closed = true; pending.clear(); processKeys.clear(); sessions.clear(); roots.clear(); registry.clear() }
}
