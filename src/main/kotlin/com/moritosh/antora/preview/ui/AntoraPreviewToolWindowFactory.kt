package com.moritosh.antora.preview.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.moritosh.antora.preview.AntoraPreviewService

/** The "Antora Preview" tool window: the preview, and the server's log. It appears once a preview is opened. */
internal class AntoraPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contents = ContentFactory.getInstance()

        val previewDisposable = Disposer.newDisposable("Antora preview panel")
        val preview = contents.createContent(PreviewPanel(project, previewDisposable), AntoraPreviewService.PREVIEW_TAB, false)
        preview.setDisposer(previewDisposable)
        toolWindow.contentManager.addContent(preview)

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
        AntoraPreviewService.getInstance(project).attachLog(console) { text, type -> console.print(text + "\n", type.contentType) }
        val log = contents.createContent(console.component, AntoraPreviewService.LOG_TAB, false)
        log.setDisposer(console)
        toolWindow.contentManager.addContent(log)
    }
}
