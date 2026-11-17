package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.KgBoardProjectSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Identity and cancellation boundary for exactly one open-project lifetime. */
class ProjectRuntimeBinding(
    private val events: RuntimeEvents,
    private val initial: ProjectSettingsSnapshot,
    private val displayName: String? = null
) :
    Disposable {
    constructor(project: Project, events: RuntimeEvents) : this(
        events,
        ProjectSettingsSnapshot.capture(
            com.kgboard.rgb.settings.SettingsMigration.project(
                KgBoardProjectSettings.getInstance(project).state
            )
        ), project.name
    )

    val operations = OperationIdentityRegistry()
    val id = ProjectId(UUID.randomUUID().toString())
    private val started = AtomicBoolean()
    private val disposed = AtomicBoolean()
    fun start() {
        if (!disposed.get() && started.compareAndSet(false, true)) events.dispatch(
            RuntimeEvent.ProjectOpened(
                id,
                initial, displayName
            )
        )
    }

    fun emit(event: RuntimeEvent) {
        if (started.get() && !disposed.get()) events.dispatch(event)
    }

    fun own(child: Disposable) {
        Disposer.register(this, child)
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            operations.clear()
            if (started.get()) events.dispatch(RuntimeEvent.ProjectClosed(id))
        }
    }
}
