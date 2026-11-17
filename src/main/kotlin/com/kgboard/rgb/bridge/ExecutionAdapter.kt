package com.kgboard.rgb.bridge

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.kgboard.rgb.runtime.*

/** Platform process lifecycle, independent of Java and test-runner plugins. */
class ExecutionAdapter(private val project: Project, private val binding: ProjectRuntimeBinding) : Disposable {
    private val publisher = OperationPublisher(binding)
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
                publisher.change(
                    registry.processKey(handler, env.executionId),
                    kind(executorId),
                    OperationPhase.RUNNING
                )
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                publisher.change(
                    registry.processKey(handler, env.executionId), kind(executorId), OperationPhase.TERMINATED,
                    if (exitCode == 0) OperationOutcome.SUCCESS else OperationOutcome.FAILURE
                )
            }
        })
        RunContentManager.getInstance(project).allDescriptors.forEach { descriptor ->
            val handler = descriptor.processHandler ?: return@forEach
            if (!handler.isProcessTerminated) publisher.change(
                registry.processKey(handler, descriptor.executionId),
                OperationKind.RUN, OperationPhase.RUNNING, expected = 0
            )
        }
    }

    override fun dispose() = publisher.close()
}
