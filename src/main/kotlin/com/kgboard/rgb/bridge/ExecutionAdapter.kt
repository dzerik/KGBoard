package com.kgboard.rgb.bridge

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.kgboard.rgb.runtime.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Platform process lifecycle, independent of Java and test-runner plugins. */
class ExecutionAdapter(private val project: Project, private val binding: ProjectRuntimeBinding) : Disposable {
    private val publisher = OperationPublisher(binding)
    private val closed = AtomicBoolean()
    private val watched = ConcurrentHashMap<ProcessHandler, ProcessListener>()
    private val registry get() = binding.operations
    private fun kind(id: String) =
        if (id == DefaultDebugExecutor.EXECUTOR_ID) OperationKind.DEBUG else OperationKind.RUN

    fun start() {
        project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                publisher.change(registry.reserveExecution(env.executionId), kind(executorId), OperationPhase.STARTING)
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                val key = registry.cancelExecution(env.executionId) ?: return
                publisher.change(key, kind(executorId), OperationPhase.TERMINATED, OperationOutcome.CANCELLED)
            }

            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val key = registry.executionKey(handler, env.executionId)
                watch(handler, key)
                publisher.change(key, kind(executorId), OperationPhase.RUNNING)
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                val key = registry.executionKey(handler, env.executionId)
                publisher.change(
                    key,
                    kind(executorId),
                    OperationPhase.TERMINATED,
                    registry.executionOutcome(key, exitCode)
                )
            }
        })
        RunContentManager.getInstance(project).allDescriptors.forEach { descriptor ->
            val handler = descriptor.processHandler ?: return@forEach
            if (!handler.isProcessTerminated) {
                val key = registry.executionKey(handler, descriptor.executionId)
                watch(handler, key)
                publisher.change(key, OperationKind.RUN, OperationPhase.RUNNING, expected = 0)
            }
        }
    }

    private fun watch(handler: ProcessHandler, key: Any) {
        if (closed.get()) return
        val listener = object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (!closed.get() && !willBeDestroyed) registry.markDetached(key)
            }

            override fun processTerminated(event: ProcessEvent) {
                watched.remove(handler, this)
                handler.removeProcessListener(this)
            }
        }
        if (watched.putIfAbsent(handler, listener) != null) return
        handler.addProcessListener(listener)
        // Disposal or termination can overtake listener registration.
        if (closed.get() || handler.isProcessTerminated || watched[handler] !== listener) {
            watched.remove(handler, listener)
            handler.removeProcessListener(listener)
        }
    }

    override fun dispose() {
        closed.set(true)
        publisher.close()
        watched.forEach { (handler, listener) ->
            if (watched.remove(handler, listener)) handler.removeProcessListener(listener)
        }
    }
}
