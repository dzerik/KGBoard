package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.KgBoardProjectSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Identity and cancellation boundary for exactly one open-project lifetime. */
class ProjectRuntimeBinding(private val events: RuntimeEvents, private val initial: ProjectSettingsSnapshot) :
    Disposable {
    constructor(project: Project, events: RuntimeEvents) : this(
        events,
        ProjectSettingsSnapshot.capture(
            com.kgboard.rgb.settings.SettingsMigration.project(
                KgBoardProjectSettings.getInstance(project).state
            )
        )
    )

    val id = ProjectId(UUID.randomUUID().toString())
    private val started = AtomicBoolean()
    private val disposed = AtomicBoolean()
    fun start() {
        if (!disposed.get() && started.compareAndSet(false, true)) events.dispatch(
            RuntimeEvent.ProjectOpened(
                id,
                initial
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
        if (disposed.compareAndSet(false, true) && started.get()) events.dispatch(RuntimeEvent.ProjectClosed(id))
    }
}
