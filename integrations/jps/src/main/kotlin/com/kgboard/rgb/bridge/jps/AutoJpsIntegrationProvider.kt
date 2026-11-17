package com.kgboard.rgb.bridge.jps

import com.intellij.compiler.server.BuildManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.bridge.build.*
import com.kgboard.rgb.bridge.integration.*
import com.kgboard.rgb.runtime.*
import java.util.UUID

/** Public UUID lifecycle only. The separate result callback has no public session identity. */
class AutoJpsIntegrationProvider : IntegrationProvider {
    override val capabilityId = "build.automake"
    override fun create(context: IntegrationContext): Disposable {
        val adapter = AutoJpsAdapter(context)
        try {
            adapter.start()
            return adapter
        } catch (error: Throwable) {
            Disposer.dispose(adapter)
            throw error
        }
    }
}

private class AutoJpsAdapter(private val context: IntegrationContext) : Disposable {
    private val roots =
        BuildRootTracker({ operation -> context.emit(RuntimeEvent.OperationChanged(context.binding.id, operation)) })

    fun start() {
        context.project.messageBus.connect(this).subscribe(BuildManagerListener.TOPIC, object : BuildManagerListener {
            override fun buildStarted(project: Project, session: UUID, automatic: Boolean) {
                if (project === context.project && automatic) roots.accept(BuildObservation.Started(session))
            }

            override fun buildFinished(project: Project, session: UUID, automatic: Boolean) {
                if (project === context.project && automatic)
                    roots.accept(BuildObservation.Finished(session, OperationOutcome.NOT_EXECUTED))
            }
        })
    }

    override fun dispose() {
        roots.close()
    }
}
