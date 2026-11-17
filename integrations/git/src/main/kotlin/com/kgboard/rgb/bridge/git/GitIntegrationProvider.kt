package com.kgboard.rgb.bridge.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.bridge.GitFactsAdapter
import com.kgboard.rgb.bridge.ProjectRuntimeService
import com.kgboard.rgb.bridge.integration.*
import com.kgboard.rgb.runtime.*

/** Loaded exclusively through the optional Git content module. */
class GitIntegrationProvider : IntegrationProvider {
    override val capabilityId = "git"
    override fun create(context: IntegrationContext): Disposable {
        val adapter = GitFactsAdapter(
            context.project, context.binding,
            ProjectRuntimeService.getInstance(context.project).scope, context.emit
        ) {
            LightingRuntimeService.getInstance().snapshot().projects[context.binding.id]?.settings
                ?: ProjectSettingsSnapshot()
        }
        try {
            adapter.start(); return adapter
        } catch (error: Throwable) {
            Disposer.dispose(adapter); throw error
        }
    }
}
