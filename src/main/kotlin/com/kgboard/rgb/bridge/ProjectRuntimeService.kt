package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.runtime.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/** IDE lifecycle wiring, kept outside the testable project identity boundary. */
@Service(Service.Level.PROJECT)
class ProjectRuntimeService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val app = LightingRuntimeService.getInstance()
    val binding = ProjectRuntimeBinding(project, app.events)
    private val started = AtomicBoolean()
    private val disposed = AtomicBoolean()
    fun start() {
        if (disposed.get() || !started.compareAndSet(false, true)) return
        binding.start()
        Disposer.register(this, binding)
        val operations = IdeOperationAdapter(project, binding)
        val editor = EditorStateAdapter(project, binding, scope)
        binding.own(operations); binding.own(editor)
        operations.start(); editor.start()
        app.register(project, binding)
    }
    override fun dispose() {
        if (disposed.compareAndSet(false, true)) { binding.dispose(); app.unregister(project) }
    }
    companion object {
        fun getInstance(project: Project): ProjectRuntimeService = project.getService(ProjectRuntimeService::class.java)
    }
}
