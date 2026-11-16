package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.runtime.LightingRuntimeService
import com.kgboard.rgb.runtime.ProjectSettingsSnapshot
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/** Optional Git4Idea wiring. Core runtime has no Git plugin dependency. */
@Service(Service.Level.PROJECT)
class GitStatusListener(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val started = AtomicBoolean()
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val service = ProjectRuntimeService.getInstance(project)
        service.start()
        val adapter = GitFactsAdapter(project, service.binding, scope) {
            LightingRuntimeService.getInstance().snapshot().projects[service.binding.id]?.settings ?: ProjectSettingsSnapshot()
        }
        Disposer.register(this, adapter)
        adapter.start()
    }
    override fun dispose() {}
    companion object { fun getInstance(project: Project): GitStatusListener = project.getService(GitStatusListener::class.java) }
}
