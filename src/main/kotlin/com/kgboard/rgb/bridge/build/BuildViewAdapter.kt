package com.kgboard.rgb.bridge.build

import com.intellij.build.BuildProgressObservable
import com.intellij.build.BuildViewManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfigurationViewManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.bridge.integration.IntegrationContext
import com.kgboard.rgb.bridge.integration.IntegrationProvider
import com.kgboard.rgb.runtime.RuntimeEvent

class BuildIntegrationProvider : IntegrationProvider {
    override val capabilityId = "build.roots"

    override fun create(context: IntegrationContext): Disposable {
        val adapter = BuildViewAdapter(context.project, context)
        try {
            adapter.start()
            return adapter
        } catch (error: Throwable) {
            Disposer.dispose(adapter)
            throw error
        }
    }
}

/** Both public managers share root identity; forwarded callbacks cannot duplicate a run. */
class BuildViewAdapter(private val project: Project, private val context: IntegrationContext) : Disposable {
    private val roots = BuildRootTracker({ operation ->
        context.emit(RuntimeEvent.OperationChanged(context.binding.id, operation))
    })

    fun start() {
        observe(project.getService(BuildViewManager::class.java))
        observe(project.getService(ExternalSystemRunConfigurationViewManager::class.java))
    }

    private fun observe(source: BuildProgressObservable) {
        source.addListener({ _, event -> buildObservation(event)?.let(roots::accept) }, this)
    }

    override fun dispose() = roots.close()
}
