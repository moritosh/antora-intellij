package com.moritosh.antora.preview

import com.intellij.util.messages.Topic

/** Changes of the project's preview, for its UI. Delivered on any thread. */
interface AntoraPreviewListener {
    /** The server started or stopped, or its build state changed. */
    fun stateChanged() {}

    /** The tool window should show [url]. */
    fun showPage(url: String) {}

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(AntoraPreviewListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
