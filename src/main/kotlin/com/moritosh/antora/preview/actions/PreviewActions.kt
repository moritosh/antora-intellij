package com.moritosh.antora.preview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.vfs.VirtualFile
import com.moritosh.antora.preview.AntoraPreviewService
import com.moritosh.antora.preview.settings.AntoraPreviewSettings

/** Opens the live preview of the current page in the tool window. */
open class OpenPreviewAction : DumbAwareAction() {
    protected open val inBrowser = false

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && isSiteFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AntoraPreviewService.getInstance(project).open(e.getData(CommonDataKeys.VIRTUAL_FILE), inBrowser)
    }

    /** AsciiDoc pages, partials and nav files, and the site's YAML files. */
    private fun isSiteFile(file: VirtualFile) = file.isInLocalFileSystem && !file.isDirectory &&
        (file.fileType.name == "AsciiDoc" || file.name == "antora.yml" || file.name.contains("playbook"))
}

/** Opens the live preview of the current page in the system browser. */
class OpenPreviewInBrowserAction : OpenPreviewAction() {
    override val inBrowser = true
}

class StopPreviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { AntoraPreviewService.getInstance(it).isRunning } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { AntoraPreviewService.getInstance(it).stop() }
    }
}

class ToggleFollowCursorAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) =
        e.project?.let { AntoraPreviewSettings.getInstance(it).followCursor } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { AntoraPreviewSettings.getInstance(it).followCursor = state }
    }
}
