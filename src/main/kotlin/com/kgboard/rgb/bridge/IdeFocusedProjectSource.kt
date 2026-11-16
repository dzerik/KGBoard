package com.kgboard.rgb.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.kgboard.rgb.runtime.*
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** AWT observes project-window switches even while the IDE application stays active. */
class IdeFocusedProjectSource(private val projectId: (Project) -> ProjectId?) : FocusedProjectSource {
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    private val queued = AtomicBoolean()
    private val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private var last = FocusSnapshot(false, null)
    override fun current(): FocusSnapshot {
        val app = ApplicationManager.getApplication()
        var window = manager.activeWindow
        if (!app.isActive || window == null) return resolveFocus(app.isActive, window != null, last.project, last)
        val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
        while (window != null) {
            val owner = window
            val project = projects.firstOrNull { !it.isDisposed && WindowManager.getInstance().getFrame(it) === owner }
            if (project != null) return resolveFocus(true, true, projectId(project), last).also { last = it }
            window = window.owner
        }
        return resolveFocus(true, true, null, last).also { last = it }
    }
    override fun subscribe(callback: () -> Unit): Cancellation {
        callbacks.add(callback)
        val listener = PropertyChangeListener { refresh() }
        manager.addPropertyChangeListener("activeWindow", listener)
        manager.addPropertyChangeListener("focusedWindow", listener)
        return Cancellation {
            callbacks.remove(callback)
            manager.removePropertyChangeListener("activeWindow", listener)
            manager.removePropertyChangeListener("focusedWindow", listener)
        }
    }
    fun refresh() {
        if (!queued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            queued.set(false)
            callbacks.forEach { it() }
        }
    }
}

/** IDE deactivation takes precedence over an AWT window that has not cleared yet. */
internal fun resolveFocus(applicationActive: Boolean, hasWindow: Boolean, project: ProjectId?, previous: FocusSnapshot): FocusSnapshot = when {
    !applicationActive -> FocusSnapshot(false, previous.project)
    !hasWindow -> previous
    else -> FocusSnapshot(true, project)
}
