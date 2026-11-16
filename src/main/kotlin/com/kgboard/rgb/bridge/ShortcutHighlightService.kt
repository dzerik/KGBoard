package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.find.EditorSearchSession
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ShortcutHighlightService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val explicit = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean()
    private var poll: Job? = null
    fun start() {
        if (!started.compareAndSet(false, true)) return
        poll = scope.launch {
            var last: Triple<DeviceMappingRevision?, Set<String>, Map<String, List<Int>>>? = null
            while (isActive) {
                withContext(Dispatchers.EDT) {
                    if (project.isDisposed) return@withContext
                    val app = LightingRuntimeService.getInstance()
                    val binding = app.binding(project) ?: return@withContext
                    val snapshot = app.snapshot()
                    val state = snapshot.projects[binding.id] ?: return@withContext
                    val mapping = snapshot.primaryMapping()
                    val contexts = explicit.toMutableSet()
                    if (state.operations.values.any { it.kind == OperationKind.DEBUG && it.phase != OperationPhase.TERMINATED }) contexts.add(
                        "debug"
                    )
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    if (SearchEverywhereManager.getInstance(project).isShown || editor?.let { EditorSearchSession.get(it) } != null) contexts.add(
                        "search"
                    )
                    val keymap = KeymapManager.getInstance().activeKeymap
                    val codes = contexts.associateWith { context ->
                        CONTEXT_ACTIONS[context].orEmpty().flatMap { action ->
                            keymap.getShortcuts(action).filterIsInstance<KeyboardShortcut>()
                                .map { it.firstKeyStroke.keyCode }
                        }.distinct()
                    }
                    val facts = Triple(mapping, contexts.toSet(), codes)
                    if (facts != last) {
                        last = facts
                        binding.emit(RuntimeEvent.ShortcutsChanged(binding.id, facts.second, keyCodes = facts.third))
                    }

                }
                delay(500)
            }
        }
    }

    fun activateContext(context: String) {
        explicit.add(context)
    }

    fun deactivateContext(context: String) {
        explicit.remove(context)
    }

    fun clearAll() {
        explicit.clear()
    }

    override fun dispose() {
        poll?.cancel(); explicit.clear()
    }

    companion object {
        private val CONTEXT_ACTIONS = mapOf(
            "debug" to listOf(
                "StepOver", "StepInto", "StepOut", "Resume", "Pause",
                "ToggleLineBreakpoint", "EvaluateExpression", "RunToCursor"
            ),
            "search" to listOf(
                "Find", "Replace", "FindInPath", "ReplaceInPath",
                "FindNext", "FindPrevious", "FindUsages"
            ),
            "editing" to listOf(
                "\$Copy", "\$Paste", "\$Cut", "\$Undo", "\$Redo",
                "\$SelectAll", "EditorDuplicate", "CommentByLineComment"
            ),
            "vcs" to listOf(
                "CheckinProject", "Vcs.Push", "Vcs.UpdateProject",
                "Git.Pull", "Annotate", "Vcs.ShowTabbedFileHistory"
            )
        )

        fun getInstance(project: Project): ShortcutHighlightService =
            project.getService(ShortcutHighlightService::class.java)
    }
}
