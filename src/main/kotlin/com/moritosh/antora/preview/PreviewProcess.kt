package com.moritosh.antora.preview

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class PreviewException(message: String) : Exception(message)

/**
 * The preview server, `antora-preview.js`, running under Bun or Node. It answers right away and
 * builds the site in the background, reporting progress as [ServerLine.Status] lines.
 *
 * The server exits when its stdin closes, so it ends with the IDE even if the IDE crashes.
 */
class PreviewProcess private constructor(
    val base: String,
    val token: String,
    private val handler: KillableProcessHandler,
    private val stopped: AtomicBoolean,
) {
    val isRunning: Boolean get() = !handler.isProcessTerminated && !handler.isProcessTerminating

    /** Kill the server, e.g. to cancel a build. */
    fun stop() {
        stopped.set(true)
        handler.destroyProcess()
    }

    /** URL that redirects to the page for [file] (or the site start page). */
    fun openUrl(file: Path?): String =
        "$base/__preview/open" + (file?.let { "?path=" + URLEncoder.encode(it.toString(), UTF_8) } ?: "")

    interface Listener {
        /** A line on stdout other than the handshake. */
        fun onLine(line: ServerLine)

        /** A line on stderr: the server's and Antora's log. */
        fun onStderr(line: String)

        /** The process ended, by [stop] if [stopped]. Not called if it ends during startup. */
        fun onExit(stopped: Boolean, exitCode: Int)
    }

    companion object {
        private val STARTUP_TIMEOUT = 120.seconds

        /** Start the server for [playbook] and wait for its handshake. */
        suspend fun start(runtime: Path, script: Path, playbook: Path, listener: Listener): PreviewProcess {
            val commandLine = GeneralCommandLine(runtime.toString(), script.toString(), playbook.toString())
                .withWorkingDirectory(playbook.parent)
                // On macOS this is the login shell's environment, not the Dock's.
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(UTF_8)
            val handler = try {
                KillableProcessHandler(commandLine)
            } catch (e: ExecutionException) {
                throw PreviewException("can't run $runtime: ${e.message}")
            }

            val handshake = CompletableDeferred<ServerLine.Handshake>()
            val recent = ArrayDeque<String>()
            fun tail() = synchronized(recent) { recent.joinToString("\n") }
            val stopped = AtomicBoolean(false)
            handler.addProcessListener(object : ProcessListener {
                val stdout = LineBuffer()
                val stderr = LineBuffer()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> stdout.feed(event.text) { line ->
                            val parsed = ServerLine.parse(line)
                            if (parsed is ServerLine.Handshake && !handshake.isCompleted) handshake.complete(parsed)
                            else listener.onLine(parsed)
                        }
                        ProcessOutputTypes.STDERR -> stderr.feed(event.text) { line ->
                            synchronized(recent) {
                                recent.addLast(line)
                                if (recent.size > 20) recent.removeFirst()
                            }
                            listener.onStderr(line)
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    if (handshake.isCompleted) listener.onExit(stopped.get(), event.exitCode)
                    else handshake.completeExceptionally(PreviewException("the preview server exited during startup\n${tail()}"))
                }
            })
            handler.startNotify()

            val (base, token) = try {
                withTimeout(STARTUP_TIMEOUT) { handshake.await() }
            } catch (e: TimeoutCancellationException) {
                handler.destroyProcess()
                throw PreviewException("the preview server did not start within $STARTUP_TIMEOUT\n${tail()}")
            } catch (e: Throwable) {
                handler.destroyProcess()
                throw e
            }
            return PreviewProcess(base, token, handler, stopped)
        }
    }
}

/** Splits process output into lines, whichever way the reader chunks it. */
private class LineBuffer {
    private val pending = StringBuilder()

    fun feed(text: String, onLine: (String) -> Unit) {
        pending.append(text)
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) break
            onLine(pending.substring(0, end).removeSuffix("\r"))
            pending.delete(0, end + 1)
        }
    }
}
