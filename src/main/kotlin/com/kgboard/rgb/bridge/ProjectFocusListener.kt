package com.kgboard.rgb.bridge
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.kgboard.rgb.runtime.LightingRuntimeService
class ProjectFocusListener : ProjectManagerListener {
    override fun projectClosing(project: Project) {
        val app = LightingRuntimeService.getInstance()
        app.binding(project)?.dispose()
        app.unregister(project)
    }
}
