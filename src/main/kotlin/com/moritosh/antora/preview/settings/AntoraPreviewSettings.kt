package com.moritosh.antora.preview.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.moritosh.antora.preview.PackageManager

/** Per-project settings. Kept in the workspace file, since paths to runtimes differ between machines. */
@Service(Service.Level.PROJECT)
@State(name = "AntoraPreviewSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AntoraPreviewSettings : SimplePersistentStateComponent<AntoraPreviewSettings.Options>(Options()) {
    class Options : BaseState() {
        /** Bun or Node executable; empty to detect. */
        var runtimePath by string()

        /** Playbook to preview; empty to use the one nearest to the page. */
        var playbookPath by string()

        /** Command of a [PackageManager]; empty to detect. */
        var packageManager by string()

        var followCursor by property(true)

        /** Keep the tool window's embedded browser while no preview runs, rather than freeing it. */
        var keepBrowser by property(true)
    }

    var runtimePath: String?
        get() = state.runtimePath?.takeIf { it.isNotBlank() }
        set(value) { state.runtimePath = value?.trim()?.ifEmpty { null } }

    var playbookPath: String?
        get() = state.playbookPath?.takeIf { it.isNotBlank() }
        set(value) { state.playbookPath = value?.trim()?.ifEmpty { null } }

    var packageManager: PackageManager?
        get() = PackageManager.entries.firstOrNull { it.command == state.packageManager }
        set(value) { state.packageManager = value?.command }

    var followCursor: Boolean
        get() = state.followCursor
        set(value) { state.followCursor = value }

    var keepBrowser: Boolean
        get() = state.keepBrowser
        set(value) { state.keepBrowser = value }

    companion object {
        fun getInstance(project: Project): AntoraPreviewSettings = project.service()
    }
}
