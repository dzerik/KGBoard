package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/** Core process wiring. Build lifecycles belong to their project-owned integration providers. */
class IdeOperationAdapter(private val project: Project, binding: ProjectRuntimeBinding) : Disposable {
    private val execution = ExecutionAdapter(project, binding)

    init {
        Disposer.register(this, execution)
    }

    fun start() {
        execution.start()
    }

    override fun dispose() = Unit
}
