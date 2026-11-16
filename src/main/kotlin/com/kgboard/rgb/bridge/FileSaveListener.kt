package com.kgboard.rgb.bridge

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Save only modified documents, once per batch and only for their owning projects. */
class FileSaveListener : FileDocumentManagerListener {
    private val announced = WeakHashMap<Document, Long>()
    private val revisions = AtomicLong()
    override fun beforeAllDocumentsSaving() = announce(FileDocumentManager.getInstance().unsavedDocuments.toList())
    override fun beforeDocumentSaving(document: Document) {
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) announce(listOf(document))
    }
    @Synchronized private fun announce(documents: List<Document>) {
        val app = LightingRuntimeService.getInstance()
        val owners = documents.filter { announced[it] != it.modificationStamp }.flatMap { document ->
            announced[document] = document.modificationStamp
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@flatMap emptyList()
            ProjectLocator.getInstance().getProjectsForFile(file).toList()
        }.distinct()
        val revision = revisions.incrementAndGet()
        owners.forEach { project -> app.binding(project)?.let { it.emit(RuntimeEvent.DocumentsSaved(it.id, revision)) } }
    }
}
