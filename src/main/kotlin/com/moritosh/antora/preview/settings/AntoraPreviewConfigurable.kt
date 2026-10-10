package com.moritosh.antora.preview.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.moritosh.antora.preview.AntoraPreviewService
import com.moritosh.antora.preview.PackageManager

class AntoraPreviewConfigurable(private val project: Project) : BoundConfigurable("Antora Live Preview") {
    private val settings = AntoraPreviewSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        row("JavaScript runtime:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select Bun or Node"),
                project,
            ).bindText({ settings.runtimePath.orEmpty() }, { settings.runtimePath = it })
                .align(AlignX.FILL)
                .comment("Runs the preview server. Leave empty to use Bun, or Node if Bun isn't installed.")
        }
        row("Playbook:") {
            textFieldWithBrowseButton(
                FileChooserDescriptorFactory.singleFile().withTitle("Select the Antora Playbook"),
                project,
            ).bindText({ settings.playbookPath.orEmpty() }, { settings.playbookPath = it })
                .align(AlignX.FILL)
                .comment("Leave empty to use the playbook nearest to the page: antora-playbook.yml, else another *playbook*.yml.")
        }
        row("Package manager:") {
            comboBox(listOf<PackageManager?>(null) + PackageManager.entries, textListCellRenderer { it?.command ?: "Detect" })
                .bindItem({ settings.packageManager }, { settings.packageManager = it })
                .comment("Installs the project's dependencies when Antora is missing. Detect reads packageManager in package.json, then the lockfile.")
        }
        row {
            checkBox("Follow the cursor").bindSelected({ settings.followCursor }, { settings.followCursor = it })
                .comment("Scroll the preview to the block being edited, and switch to the page in the active editor.")
        }
    }

    override fun apply() {
        val before = settings.runtimePath to settings.playbookPath
        super.apply()
        if (before != settings.runtimePath to settings.playbookPath) AntoraPreviewService.getInstance(project).restart()
    }
}
