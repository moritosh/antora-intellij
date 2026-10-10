package com.moritosh.antora.preview

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.BrowserUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.EnvironmentUtil
import com.moritosh.antora.preview.settings.AntoraPreviewConfigurable
import com.moritosh.antora.preview.settings.AntoraPreviewSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * The project's live preview: one `antora-preview.js` server for the playbook being previewed,
 * fed with unsaved edits and cursor moves by [EditorSync].
 */
@Service(Service.Level.PROJECT)
class AntoraPreviewService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    enum class LogType(val contentType: ConsoleViewContentType) {
        OUTPUT(ConsoleViewContentType.NORMAL_OUTPUT),
        INFO(ConsoleViewContentType.SYSTEM_OUTPUT),
        ERROR(ConsoleViewContentType.ERROR_OUTPUT),
    }

    private class Running(
        val generation: Int,
        val process: PreviewProcess,
        val client: PreviewClient,
        val playbook: Path,
        val disposable: Disposable,
    )

    /** Held while the server starts or stops. */
    private val lock = Mutex()

    @Volatile
    private var running: Running? = null

    /** Counts server starts, so output and progress of a replaced server are ignored. */
    @Volatile
    private var generation = 0

    private val status = MutableStateFlow<ServerLine.Status?>(null)

    @Volatile
    private var buildProgress: Job? = null

    /** The page last opened and where, to reopen it after a restart. */
    @Volatile
    private var lastOpened: Pair<Path?, Boolean>? = null

    /** The URL the tool window shows, or should show once it's created. */
    @Volatile
    var pageUrl: String? = null
        private set

    val isRunning: Boolean get() = running?.process?.isRunning == true

    val baseUrl: String? get() = running?.process?.base

    private val home: Path? = System.getProperty("user.home")?.let(Path::of)

    // --- Opening ------------------------------------------------------------------------------

    /** Start the preview if needed and show [file]'s page, in the tool window or the system browser. */
    fun open(file: VirtualFile?, inBrowser: Boolean) = openPath(file?.toNioPathOrNull(), inBrowser)

    private fun openPath(file: Path?, inBrowser: Boolean) {
        cs.launch {
            try {
                val playbook = findPlaybook(file) ?: throw PreviewException(
                    "No Antora playbook (antora-playbook.yml or another *playbook*.yml) was found above " +
                        "${file ?: "the project"}. Choose one in the settings."
                )
                val run = ensureRunning(playbook, file, inBrowser) ?: return@launch
                lastOpened = file to inBrowser
                show(run.process.openUrl(file), inBrowser)
            } catch (e: PreviewException) {
                log(e.message.orEmpty(), LogType.ERROR)
                notify(NotificationType.ERROR, e.message.orEmpty(), settingsAction(), showLogAction())
            }
        }
    }

    private fun findPlaybook(file: Path?): Path? {
        AntoraPreviewSettings.getInstance(project).playbookPath?.let { configured ->
            return Path.of(configured).takeIf { Files.isRegularFile(it) }
                ?: throw PreviewException("The playbook set in the settings doesn't exist: $configured")
        }
        if (file == null) running?.let { return it.playbook }
        file?.let { PlaybookLocator.nearest(it, home) }?.let { return it }
        return project.basePath?.let { PlaybookLocator.nearest(Path.of(it), home) }
    }

    private suspend fun ensureRunning(playbook: Path, file: Path?, inBrowser: Boolean): Running? = lock.withLock {
        running?.let {
            if (it.playbook == playbook && it.process.isRunning) return it
            stopNow(it)
        }
        val settings = AntoraPreviewSettings.getInstance(project)
        val runtime = RuntimeLocator(EnvironmentUtil.getEnvironmentMap(), home).find(settings.runtimePath)
            ?: throw PreviewException("Neither Bun nor Node was found. Install one, or set the runtime in the settings.")
        if (!ProjectDeps.isAntoraInstalled(playbook.parent)) {
            suggestInstall(playbook, file, inBrowser)
            return null
        }
        val script = withContext(Dispatchers.IO) { PreviewScript.path() }
        val gen = ++generation
        log("Starting the preview of $playbook with $runtime")
        val process = withBackgroundProgress(project, "Starting Antora preview") {
            PreviewProcess.start(runtime, script, playbook, processListener(gen))
        }
        val disposable = Disposer.newDisposable(this, "Antora preview server")
        val client = PreviewClient(process.base, process.token, cs) { log(it, LogType.ERROR) }
        val run = Running(gen, process, client, playbook, disposable)
        running = run
        EditorSync(project, this).install(disposable)
        sendUnsavedDocuments(client)
        log("Preview running at ${process.base}")
        stateChanged()
        run
    }

    private suspend fun show(url: String, inBrowser: Boolean) {
        if (inBrowser || !JBCefApp.isSupported()) return BrowserUtil.browse(url)
        withContext(Dispatchers.EDT) {
            val toolWindow = toolWindow() ?: return@withContext BrowserUtil.browse(url)
            val created = (toolWindow.contentManagerIfCreated?.contentCount ?: 0) > 0
            pageUrl = url
            toolWindow.isAvailable = true
            toolWindow.show()
            selectTab(toolWindow, PREVIEW_TAB)
            // A panel created just now loads pageUrl itself.
            if (created) project.messageBus.syncPublisher(AntoraPreviewListener.TOPIC).showPage(url)
        }
    }

    // --- Stopping -----------------------------------------------------------------------------

    fun stop() {
        cs.launch { lock.withLock { running?.let(::stopNow) } }
    }

    /** Stop the server started as [gen], if it's still the current one. */
    private fun stop(gen: Int) {
        cs.launch { lock.withLock { running?.takeIf { it.generation == gen }?.let(::stopNow) } }
    }

    /** Restart with the current settings, if the preview is running. */
    fun restart() {
        cs.launch {
            val wasRunning = lock.withLock { running?.let(::stopNow) != null }
            val (file, inBrowser) = lastOpened ?: return@launch
            if (wasRunning) openPath(file, inBrowser)
        }
    }

    private fun stopNow(run: Running) {
        run.process.stop()
        cleanUp(run)
        log("Preview stopped")
    }

    private fun cleanUp(run: Running) {
        if (running !== run) return
        running = null
        pageUrl = null
        Disposer.dispose(run.disposable)
        run.client.close()
        buildProgress.also { buildProgress = null }?.cancel()
        status.value = null
        stateChanged()
    }

    override fun dispose() {
        running?.process?.stop()
    }

    // --- Server output ------------------------------------------------------------------------

    private fun processListener(gen: Int) = object : PreviewProcess.Listener {
        override fun onLine(line: ServerLine) {
            when (line) {
                is ServerLine.Status -> if (gen == generation) onStatus(gen, line)
                is ServerLine.Reveal -> reveal(line)
                is ServerLine.Log -> log(line.text, LogType.OUTPUT)
                is ServerLine.Handshake -> {}
            }
        }

        override fun onStderr(line: String) = log(line, LogType.OUTPUT)

        override fun onExit(stopped: Boolean, exitCode: Int) {
            cs.launch {
                lock.withLock { running?.takeIf { it.generation == gen }?.let(::cleanUp) }
                if (!stopped) {
                    log("The preview server exited with code $exitCode", LogType.ERROR)
                    notify(NotificationType.WARNING, "The preview server stopped unexpectedly.", showLogAction())
                }
            }
        }
    }

    private fun onStatus(gen: Int, s: ServerLine.Status) {
        status.value = s
        when (s.state) {
            "building" -> if (buildProgress?.isActive != true) showBuildProgress(gen)
            "failed" -> notify(NotificationType.ERROR, "The site build failed: ${s.error}", showLogAction())
        }
        stateChanged()
    }

    /** Builds show as cancellable progress; cancelling stops the preview, as in antora-ls. */
    private fun showBuildProgress(gen: Int) {
        buildProgress = cs.launch {
            try {
                withBackgroundProgress(project, "Building Antora preview", cancellable = true) {
                    reportRawProgress { reporter ->
                        status.takeWhile { it?.state == "building" }.collect { reporter.text(it?.phase?.let { "$it…" }) }
                    }
                }
            } catch (e: CancellationException) {
                // cleanUp() forgets the job before cancelling it; otherwise the user cancelled.
                if (buildProgress === coroutineContext.job) stop(gen)
                throw e
            }
        }
    }

    /** Show the source of a block that was double-clicked in the preview. */
    private fun reveal(r: ServerLine.Reveal) {
        cs.launch {
            val file = withContext(Dispatchers.IO) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(r.path))
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                writeIntentReadAction { OpenFileDescriptor(project, file, maxOf(r.line - 1, 0), 0).navigate(true) }
                ProjectUtil.focusProjectWindow(project, true)
            }
        }
    }

    // --- Editor sync ----------------------------------------------------------------------------

    fun sendUpdate(path: Path, text: CharSequence?) {
        if (isRelevant(path)) running?.client?.send(PreviewClient.Msg.Update(path, text))
    }

    fun sendCursor(path: Path, line: Int) {
        if (isRelevant(path) && AntoraPreviewSettings.getInstance(project).followCursor) {
            running?.client?.send(PreviewClient.Msg.Cursor(path, line))
        }
    }

    /** Files of this project or below the playbook; the server ignores ones that aren't in the site. */
    private fun isRelevant(path: Path): Boolean {
        val run = running ?: return false
        return path.startsWith(run.playbook.parent) || project.basePath?.let { path.startsWith(Path.of(it)) } == true
    }

    /** Edits made before the preview started. */
    private suspend fun sendUnsavedDocuments(client: PreviewClient) {
        val unsaved = withContext(Dispatchers.EDT) {
            val fdm = FileDocumentManager.getInstance()
            fdm.unsavedDocuments.mapNotNull { doc -> fdm.getFile(doc)?.toNioPathOrNull()?.let { it to doc.immutableCharSequence } }
        }
        for ((path, text) in unsaved) if (isRelevant(path)) client.send(PreviewClient.Msg.Update(path, text))
    }

    // --- Dependencies ---------------------------------------------------------------------------

    private fun suggestInstall(playbook: Path, file: Path?, inBrowser: Boolean) {
        val dir = ProjectDeps.packageDir(playbook.parent)
        if (dir == null) {
            notify(
                NotificationType.ERROR,
                "Antora isn't installed for ${playbook.fileName}, and there's no package.json to install it from. " +
                    "Add Antora to the project (for example npm i -D antora), then open the preview again.",
            )
            return
        }
        val pm = AntoraPreviewSettings.getInstance(project).packageManager ?: PackageManager.detect(dir)
        notify(
            NotificationType.WARNING,
            "Antora isn't installed in $dir. Install the project's dependencies with $pm?",
            NotificationAction.createSimpleExpiring("Run $pm install") { install(dir, pm, file, inBrowser) },
            settingsAction(),
        )
    }

    private fun install(dir: Path, pm: PackageManager, file: Path?, inBrowser: Boolean) {
        cs.launch {
            val locator = RuntimeLocator(EnvironmentUtil.getEnvironmentMap(), home)
            val exe = locator.tool(pm.command) ?: run {
                notify(NotificationType.ERROR, "${pm.command} wasn't found on the PATH.", settingsAction())
                return@launch
            }
            log("Running $exe install in $dir")
            val exitCode = withBackgroundProgress(project, "Installing dependencies with $pm", cancellable = true) {
                val handler = KillableProcessHandler(
                    GeneralCommandLine(exe.toString(), "install")
                        .withWorkingDirectory(dir)
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                        .withCharset(UTF_8)
                )
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) =
                        log(event.text.trimEnd(), LogType.OUTPUT)
                })
                handler.startNotify()
                try {
                    withContext(Dispatchers.IO) {
                        while (!handler.waitFor(200)) ensureActive()
                    }
                    handler.exitCode ?: -1
                } finally {
                    if (!handler.isProcessTerminated) handler.destroyProcess()
                }
            }
            if (exitCode == 0) {
                log("Dependencies installed")
                openPath(file, inBrowser)
            } else {
                notify(NotificationType.ERROR, "$pm install failed with exit code $exitCode.", showLogAction())
            }
        }
    }

    // --- Log and notifications ------------------------------------------------------------------

    private val logLines = ArrayDeque<Pair<String, LogType>>()
    private val logConsumers = mutableListOf<(String, LogType) -> Unit>()

    fun log(text: String, type: LogType = LogType.INFO) = synchronized(logLines) {
        logLines.addLast(text to type)
        if (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logConsumers.forEach { it(text, type) }
    }

    /** Replay the log to [consumer] and keep it posted until [parent] is disposed. */
    fun attachLog(parent: Disposable, consumer: (String, LogType) -> Unit) {
        synchronized(logLines) {
            logLines.forEach { (text, type) -> consumer(text, type) }
            logConsumers += consumer
        }
        Disposer.register(parent) { synchronized(logLines) { logConsumers -= consumer } }
    }

    fun showLog() {
        cs.launch(Dispatchers.EDT) {
            val toolWindow = toolWindow() ?: return@launch
            toolWindow.isAvailable = true
            toolWindow.show()
            selectTab(toolWindow, LOG_TAB)
        }
    }

    private fun notify(type: NotificationType, text: String, vararg actions: AnAction) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Antora preview", StringUtil.escapeXmlEntities(text).replace("\n", "<br>"), type)
            .apply { actions.forEach(::addAction) }
            .notify(project)
    }

    private fun showLogAction() = NotificationAction.createSimple("Show log") { showLog() }

    private fun settingsAction() = NotificationAction.createSimple("Settings") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AntoraPreviewConfigurable::class.java)
    }

    private fun stateChanged() = project.messageBus.syncPublisher(AntoraPreviewListener.TOPIC).stateChanged()

    private fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    private fun selectTab(toolWindow: ToolWindow, name: String) {
        toolWindow.contentManager.findContent(name)?.let { toolWindow.contentManager.setSelectedContent(it) }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Antora Preview"
        const val PREVIEW_TAB = "Preview"
        const val LOG_TAB = "Log"
        private const val NOTIFICATION_GROUP = "Antora Preview"
        private const val MAX_LOG_LINES = 5000

        fun getInstance(project: Project): AntoraPreviewService = project.service()
    }
}
