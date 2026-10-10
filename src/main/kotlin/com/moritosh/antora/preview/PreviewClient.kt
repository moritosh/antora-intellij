package com.moritosh.antora.preview

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Sends unsaved edits and cursor moves to the preview server. */
class PreviewClient(
    private val base: String,
    private val token: String,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    sealed interface Msg {
        /** New text for a file; `null` means "use what's on disk". */
        data class Update(val path: Path, val text: CharSequence?) : Msg

        /** The caret is on (1-based) [line] of [path]. */
        data class Cursor(val path: Path, val line: Int) : Msg
    }

    private val channel = Channel<Msg>(Channel.UNLIMITED)

    // IntelliJ installs its own proxy selector; the server is on 127.0.0.1.
    private val http = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(5.seconds.toJavaDuration())
        .build()

    private val job = scope.launch(Dispatchers.IO) { forward() }

    fun send(msg: Msg) {
        channel.trySend(msg)
    }

    fun close() {
        channel.close()
        job.cancel()
    }

    /** Keystrokes arriving within [COALESCE] are sent as one update, keeping only the latest text per file. */
    private suspend fun forward() {
        for (first in channel) {
            val updates = LinkedHashMap<Path, CharSequence?>()
            var cursor: Msg.Cursor? = null
            fun take(msg: Msg) {
                when (msg) {
                    is Msg.Update -> updates[msg.path] = msg.text
                    is Msg.Cursor -> cursor = msg
                }
            }
            take(first)
            delay(COALESCE)
            while (true) take(channel.tryReceive().getOrNull() ?: break)
            for ((path, text) in updates) {
                post("update", JsonObject().apply {
                    addProperty("path", path.toString())
                    addProperty("text", text?.toString())
                })
            }
            cursor?.let { (path, line) ->
                post("cursor", JsonObject().apply {
                    addProperty("path", path.toString())
                    addProperty("line", line)
                })
            }
        }
    }

    private fun post(route: String, body: JsonObject) {
        val request = HttpRequest.newBuilder(URI.create("$base/__preview/$route"))
            .header("content-type", "application/json")
            .header("x-preview-token", token)
            .timeout(30.seconds.toJavaDuration())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        try {
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status !in 200..299) log("preview $route failed: HTTP $status")
        } catch (e: java.io.IOException) {
            log("preview $route failed: ${e.message}")
        }
    }

    private companion object {
        val COALESCE = 40.milliseconds
    }
}
