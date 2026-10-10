package com.moritosh.antora.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

/**
 * Forwards edits and caret moves to the running preview: unsaved text as you type, "read it from
 * disk" on save or reload, and the caret line so the preview follows along. Saves by other tools
 * are picked up by the server's own file watcher.
 */
internal class EditorSync(private val project: Project, private val service: AntoraPreviewService) :
    DocumentListener, CaretListener, FileDocumentManagerListener, FileEditorManagerListener {

    fun install(parent: Disposable) {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(this, parent)
        multicaster.addCaretListener(this, parent)
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(FileDocumentManagerListener.TOPIC, this)
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    // An immutable snapshot is cheap to take here and converted to a string off the EDT.
    override fun documentChanged(event: DocumentEvent) {
        pathOf(event.document)?.let { service.sendUpdate(it, event.document.immutableCharSequence) }
    }

    override fun afterDocumentSaved(document: Document) {
        pathOf(document)?.let { service.sendUpdate(it, null) } // disk is current again
    }

    override fun fileContentReloaded(file: VirtualFile, document: Document) {
        file.toNioPathOrNull()?.let { service.sendUpdate(it, null) }
    }

    override fun unsavedDocumentDropped(document: Document) {
        pathOf(document)?.let { service.sendUpdate(it, null) }
    }

    override fun caretPositionChanged(event: CaretEvent) {
        val editor = event.editor
        if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (event.caret != editor.caretModel.primaryCaret) return
        pathOf(editor.document)?.let { service.sendCursor(it, event.newPosition.line + 1) }
    }

    /** Switching to another page's tab shows that page. */
    override fun selectionChanged(event: FileEditorManagerEvent) {
        val editor = (event.newEditor as? TextEditor)?.editor ?: return
        val path = event.newFile?.toNioPathOrNull() ?: return
        service.sendCursor(path, editor.caretModel.logicalPosition.line + 1)
    }

    private fun pathOf(document: Document): Path? =
        FileDocumentManager.getInstance().getFile(document)?.takeIf { it.isInLocalFileSystem }?.toNioPathOrNull()
}
