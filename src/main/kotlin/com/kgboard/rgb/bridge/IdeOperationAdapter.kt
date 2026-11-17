package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.*
import com.kgboard.rgb.runtime.*

/** Core process wiring. Legacy project builds remain until C3 proves complete BuildView coverage. */
class IdeOperationAdapter(private val project: Project, binding: ProjectRuntimeBinding) : Disposable {
    private val execution = ExecutionAdapter(project, binding)
    private val builds = OperationPublisher(binding)

    init {
        Disposer.register(this, execution)
    }

    fun start() {
        execution.start()
        project.messageBus.connect(this).subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
            override fun started(context: ProjectTaskContext) =
                builds.change(context.sessionId ?: context, OperationKind.BUILD, OperationPhase.RUNNING)

            override fun finished(result: ProjectTaskManager.Result) = builds.change(
                result.context.sessionId ?: result.context,
                OperationKind.BUILD, OperationPhase.TERMINATED, when {
                    result.isAborted -> OperationOutcome.CANCELLED
                    result.hasErrors() -> OperationOutcome.FAILURE
                    else -> OperationOutcome.SUCCESS
                }
            )
        })
    }

    override fun dispose() = builds.close()
}
