package com.kgboard.rgb.bridge

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiTodoSearchHelper
import com.kgboard.rgb.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Selection-scoped, cancellable reads. The runtime validates every result's revision. */
class EditorStateAdapter(private val project: Project, private val binding: ProjectRuntimeBinding,
    parentScope: CoroutineScope) : Disposable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val queued = AtomicBoolean()
    @Volatile private var closed = false
    private var sequence = 0L
    private var readJob: Job? = null
    fun start() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refresh()
        })
        bus.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() { binding.emit(RuntimeEvent.IndexingChanged(binding.id, true)); refresh() }
            override fun exitDumbMode() { binding.emit(RuntimeEvent.IndexingChanged(binding.id, false)); refresh() }
        })
        bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonStarting(fileEditors: Collection<FileEditor>) = refresh()
            override fun daemonFinished() = refresh()
            override fun daemonCanceled(reason: String, fileEditors: Collection<FileEditor>) = refresh()
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) { if (events.any { it is com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent }) refresh() }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileEditorManager.getInstance(project).selectedTextEditor?.document === event.document) refresh()
            }
        }, this)
        binding.emit(RuntimeEvent.IndexingChanged(binding.id, DumbService.isDumb(project)))
        refresh()
    }
    fun refresh() {
        if (closed || !queued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            queued.set(false)
            if (closed || project.isDisposed) return@invokeLater
            capture()
        }, { closed || project.isDisposed })
    }
    private fun capture() {
        readJob?.cancel()
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor
        val document = editor?.document
        val file = document?.let { FileDocumentManager.getInstance().getFile(it) }?.takeIf { it.isValid }
        val revision = file?.let { EditorRevision(it.url, document.modificationStamp, ++sequence) }
        val applicable = file != null && !file.fileType.isBinary
        binding.emit(RuntimeEvent.EditorSelected(binding.id, revision, applicable))
        if (!applicable || revision == null || document == null || file == null) return
        binding.emit(RuntimeEvent.AnalysisRequested(binding.id, revision))
        val selected = manager.selectedEditor
        readJob = scope.launch {
            val facts = readAction {
                if (closed || project.isDisposed || !file.isValid || document.modificationStamp != revision.documentStamp || DumbService.isDumb(project)) return@readAction null
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@readAction null
                val todos = PsiTodoSearchHelper.getInstance(project).findTodoItems(psi).isNotEmpty()
                val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)
                val markup = DocumentMarkupModel.forDocument(document, project, false)
                val analysis = when {
                    !daemon.isHighlightingAvailable(psi) -> AnalysisResult.NotApplicable
                    selected == null || !DaemonCodeAnalyzerEx.isHighlightingCompleted(selected, project) || !daemon.isErrorAnalyzingFinished(psi) || markup == null -> AnalysisResult.Unknown
                    else -> {
                        val highlights = markup.allHighlighters.mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                        AnalysisResult.Ready(highlights.any { it.severity >= HighlightSeverity.ERROR }, highlights.any { it.severity == HighlightSeverity.WARNING })
                    }
                }
                analysis to todos
            }
            ensureActive()
            if (!closed && !project.isDisposed && document.modificationStamp == revision.documentStamp && facts != null) {
                binding.emit(RuntimeEvent.AnalysisCompleted(binding.id, revision, facts.first))
                binding.emit(RuntimeEvent.TodosCompleted(binding.id, revision, facts.second))
            }
        }
    }
    override fun dispose() { closed = true; job.cancel() }
}
