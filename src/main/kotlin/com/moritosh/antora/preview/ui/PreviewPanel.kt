package com.moritosh.antora.preview.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import com.moritosh.antora.preview.AntoraPreviewListener
import com.moritosh.antora.preview.AntoraPreviewService
import com.moritosh.antora.preview.settings.AntoraPreviewSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** The preview in an embedded browser, with a toolbar. */
internal class PreviewPanel(private val project: Project, private val parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = AntoraPreviewService.getInstance(project)
    private val settings = AntoraPreviewSettings.getInstance(project)

    /** Created for the first page shown; disposed while no preview runs unless the settings keep it. Used on the EDT. */
    private var browser: JBCefBrowser? = null

    init {
        val actions = ActionManager.getInstance()
        val group = DefaultActionGroup(
            ReloadAction(),
            OpenCurrentInBrowserAction(),
            actions.getAction("AntoraPreview.FollowCursor"),
            actions.getAction("AntoraPreview.Stop"),
        )
        toolbar = actions.createActionToolbar("AntoraPreview", group, true).also { it.targetComponent = this }.component

        project.messageBus.connect(parent).subscribe(AntoraPreviewListener.TOPIC, object : AntoraPreviewListener {
            override fun showPage(url: String) = onEdt { show(url) }
            override fun stateChanged() = onEdt { if (!service.isRunning && service.pageUrl == null) showIdle() }
        })
        service.pageUrl?.let(::show) ?: showIdle()
    }

    private fun show(url: String) {
        (browser ?: createBrowser())?.loadURL(url)
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            setContent(message(NO_BROWSER))
            return null
        }
        return JBCefBrowser.createBuilder().build().also {
            Disposer.register(parent, it)
            keepLinksOutside(it)
            setContent(it.component)
            browser = it
        }
    }

    /** Links that leave the preview, and ones that would open a new window, go to the system browser. */
    private fun keepLinksOutside(browser: JBCefBrowser) {
        fun isPreview(url: String) = service.baseUrl?.let { url.startsWith("$it/") || url == it } == true ||
            url.startsWith("data:") || url.startsWith("about:")
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(b: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                if (isPreview(request.url) || frame?.isMain == false) return false
                BrowserUtil.browse(request.url)
                return true
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(b: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                targetUrl?.let(BrowserUtil::browse)
                return true
            }
        }, browser.cefBrowser)
    }

    private fun showIdle() {
        if (!JBCefApp.isSupported()) return setContent(message(NO_BROWSER))
        if (!settings.keepBrowser) {
            setContent(message("No preview is running.", "Run Open Antora Live Preview on a page to start one."))
            browser?.let { browser = null; Disposer.dispose(it) }
            return
        }
        val browser = browser ?: createBrowser() ?: return
        val fg = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val bg = ColorUtil.toHtmlColor(UIUtil.getPanelBackground())
        val html = """<!doctype html><html><body style="margin:0;height:100vh;display:grid;place-items:center;
              |background:$bg;color:$fg;font:13px system-ui,sans-serif;text-align:center">
              |<p>No preview is running.<br>Run <b>Open Antora Live Preview</b> on a page to start one.</p>
              |</body></html>""".trimMargin()
        // A data: URL rather than loadHTML(), which serves the page from a file:///jbcefbrowser/<random> URL
        // that keepLinksOutside would hand to the system browser.
        browser.loadURL("data:text/html;base64," + Base64.getEncoder().encodeToString(html.toByteArray(UTF_8)))
    }

    private fun message(vararg lines: String) = JBPanelWithEmptyText().apply {
        emptyText.text = lines.first()
        lines.drop(1).forEach { emptyText.appendLine(it) }
    }

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater({ action() }, project.disposed)

    private inner class ReloadAction : DumbAwareAction("Reload", "Reload the page", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = browser != null && service.isRunning
        }
        override fun actionPerformed(e: AnActionEvent) {
            browser?.cefBrowser?.reloadIgnoreCache()
        }
    }

    private inner class OpenCurrentInBrowserAction :
        DumbAwareAction("Open in Browser", "Open this page in the system browser", AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = browser != null && service.isRunning
        }
        override fun actionPerformed(e: AnActionEvent) {
            val url = browser?.cefBrowser?.url?.takeIf { it.startsWith("http") } ?: service.baseUrl ?: return
            BrowserUtil.browse(url)
        }
    }

    private companion object {
        const val NO_BROWSER = "The embedded browser isn't available; previews open in the system browser"
    }
}
