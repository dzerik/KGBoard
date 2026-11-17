package com.kgboard.rgb.bridge.tests

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.*
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.kgboard.rgb.bridge.*
import com.kgboard.rgb.bridge.integration.*
import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap

class SmTestIntegrationProvider : IntegrationProvider {
    override val capabilityId = "tests.sm"
    override fun create(context: IntegrationContext): Disposable {
        val adapter = SmAdapter(context)
        try {
            adapter.start(); return adapter
        } catch (error: Throwable) {
            com.intellij.openapi.util.Disposer.dispose(adapter); throw error
        }
    }
}

private class SmAdapter(private val context: IntegrationContext) : Disposable {
    private val project: Project get() = context.project
    private val registry get() = context.binding.operations
    private val publisher = OperationPublisher(context.binding, context.emit)
    private val roots = WeakHashMap<SMTestProxy.SMRootTestProxy, Any>()
    private val retired = java.util.Collections.newSetFromMap(WeakHashMap<SMTestProxy.SMRootTestProxy, Boolean>())
    private fun key(root: SMTestProxy.SMRootTestProxy): Any = synchronized(this) { roots.getOrPut(root) { Any() } }
    private var closed = false
    fun start() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(root: SMTestProxy.SMRootTestProxy) {
                val key = synchronized(this@SmAdapter) { if (closed || root in retired) return; key(root) }
                publisher.change(key, OperationKind.TEST, OperationPhase.RUNNING, parent = parent(root))
            }

            override fun onTestingFinished(root: SMTestProxy.SMRootTestProxy) {
                val key = synchronized(this@SmAdapter) { roots.remove(root)?.also { retired.add(root) } } ?: return
                publisher.change(key, OperationKind.TEST, OperationPhase.TERMINATED, outcome(root), parent(root))
            }
        })
        bus.subscribe(RunContentManager.TOPIC, object : RunContentWithExecutorListener {
            override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) = reconcile()
            override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) = reconcile()
        })
        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
                reconcile()
        })
        RunContentManager.getInstance(project).allDescriptors.forEach { descriptor ->
            val root = (descriptor.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode
            if (root != null && root.isInProgress) {
                publisher.change(
                    key(root),
                    OperationKind.TEST,
                    OperationPhase.RUNNING,
                    parent = parent(root),
                    expected = 0
                )
            }
        }
    }

    private fun parent(root: SMTestProxy.SMRootTestProxy): OperationId? {
        val descriptor = RunContentManager.getInstance(project).allDescriptors.firstOrNull {
            (it.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode === root
        } ?: return null
        val handler = descriptor.processHandler ?: return null
        return registry.id(registry.processKey(handler, descriptor.executionId))
    }

    private fun reconcile() {
        synchronized(this) { roots.keys.toList() }.forEach { root ->
            val parent = parent(root) ?: return@forEach
            val key = synchronized(this) { roots[root] } ?: return@forEach
            publisher.change(key, OperationKind.TEST, OperationPhase.RUNNING, parent = parent)
        }
    }

    @Synchronized
    override fun dispose() {
        closed = true; publisher.close()
        roots.values.forEach { key -> registry.id(key)?.let(registry::release) }
        roots.clear(); retired.clear()
    }
}
