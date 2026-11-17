package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import com.kgboard.rgb.bridge.integration.*
import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap

class DebugIntegrationProvider : IntegrationProvider {
    override val capabilityId = "debug"
    override fun create(context: IntegrationContext): Disposable {
        val adapter = DebugAdapter(context.project, context.binding, context.emit)
        try {
            adapter.start(); return adapter
        } catch (error: Throwable) {
            com.intellij.openapi.util.Disposer.dispose(adapter); throw error
        }
    }
}

/** Debug subscriptions have their own lifetime while sharing the process identity registry. */
class DebugAdapter(
    private val project: Project, private val binding: ProjectRuntimeBinding,
    emit: (RuntimeEvent) -> Unit = binding::emit
) : Disposable {
    private val publisher = OperationPublisher(binding, emit)
    private val sessions = WeakHashMap<XDebugSession, Boolean>()
    private var closed = false
    fun start() {
        project.messageBus.connect(this).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) = attach(debugProcess.session)
        })
        XDebuggerManager.getInstance(project).debugSessions.forEach(::attach)
    }

    private fun attach(session: XDebugSession) {
        synchronized(this) { if (closed || sessions.put(session, true) != null) return }
        val key = binding.operations.processKey(
            session.debugProcess.processHandler,
            session.executionEnvironment?.executionId ?: 0
        )
        val observed = binding.operations.id(key)?.let(binding.operations::revision) ?: return
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = publisher.change(key, OperationKind.DEBUG, OperationPhase.PAUSED)
            override fun sessionResumed() = publisher.change(key, OperationKind.DEBUG, OperationPhase.RUNNING)
            override fun sessionStopped() {
                // Execution owns process results; stopped also means detach and is not proof of success.
                if (binding.operations.isExecutionOwned(key) || session.executionEnvironment != null) return
                publisher.change(key, OperationKind.DEBUG, OperationPhase.TERMINATED, OperationOutcome.NOT_EXECUTED)
            }
        }, this)
        publisher.change(
            key,
            OperationKind.DEBUG,
            if (session.isSuspended) OperationPhase.PAUSED else OperationPhase.RUNNING,
            expected = observed
        )
    }

    @Synchronized
    override fun dispose() {
        closed = true; sessions.clear(); publisher.close()
    }
}
