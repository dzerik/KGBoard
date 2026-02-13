package com.kgboard.rgb.bridge

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.EffectTarget
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors TODO items in the current file and displays per-key RGB indicator.
 *
 * Triggers on:
 * - File editor change (switching tabs)
 * - Daemon code analyzer finish (file content changes)
 */
@Service(Service.Level.PROJECT)
class TodoIndicatorListener(private val project: Project) : Disposable {

    private val log = Logger.getInstance(TodoIndicatorListener::class.java)
    private val disposed = AtomicBoolean(false)

    companion object {
        const val EFFECT_ID = "todo-indicator"

        fun getInstance(project: Project): TodoIndicatorListener =
            project.getService(TodoIndicatorListener::class.java)
    }

    fun start() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.notifyTodoEnabled) return

        val connection = project.messageBus.connect(this)

        // Update on file switch
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                updateTodoStatus()
            }
        })

        // Update when daemon finishes (file content changes)
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() {
                updateTodoStatus()
            }

            override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                updateTodoStatus()
            }
        })

        log.info("TODO indicator listener started")
    }

    private fun updateTodoStatus() {
        if (disposed.get()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed.get()) return@executeOnPooledThread
            try {
                val settings = KgBoardSettings.getInstance()
                if (!settings.enabled || !settings.notifyTodoEnabled) return@executeOnPooledThread

                val ledIndices = settings.notifyTodoLedIndices
                if (ledIndices.isEmpty()) return@executeOnPooledThread

                val hasTodos = ReadAction.compute<Boolean, Throwable> {
                    if (project.isDisposed) return@compute false
                    val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                        ?: return@compute false
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                        ?: return@compute false
                    PsiTodoSearchHelper.getInstance(project).findTodoItems(psiFile).isNotEmpty()
                }

                val effectManager = EffectManagerService.getInstance(project)
                if (hasTodos) {
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        StaticEffect(
                            color = settings.notifyTodoColor,
                            name = "todo-indicator",
                            priority = 1,
                            target = EffectTarget.LedSet(ledIndices)
                        )
                    )
                } else {
                    effectManager.removeTargetedEffect(EFFECT_ID)
                }
            } catch (e: Exception) {
                log.warn("TODO indicator update error: ${e.message}")
            }
        }
    }

    override fun dispose() {
        disposed.set(true)
        try {
            EffectManagerService.getInstance(project).removeTargetedEffect(EFFECT_ID)
        } catch (_: Exception) {
            // project may already be disposed
        }
    }
}
