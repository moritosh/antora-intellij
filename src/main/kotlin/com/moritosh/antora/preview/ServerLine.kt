package com.moritosh.antora.preview

import com.google.gson.JsonParser

/** A line the preview server prints on stdout (see `antora-preview.js`). */
sealed interface ServerLine {
    /** The server listens at [base]; updates must carry [token]. */
    data class Handshake(val base: String, val token: String) : ServerLine

    /** Build progress: [state] is `building`, `ready` or `failed`. */
    data class Status(val state: String, val phase: String?, val error: String?) : ServerLine

    /** A block of the page from source [path] at (1-based) [line] was double-clicked in the preview. */
    data class Reveal(val path: String, val line: Int) : ServerLine

    data class Log(val text: String) : ServerLine

    companion object {
        private const val HANDSHAKE = "ANTORA_PREVIEW "
        private const val STATUS = "ANTORA_PREVIEW_STATUS "
        private const val REVEAL = "ANTORA_PREVIEW_REVEAL "

        fun parse(line: String): ServerLine = runCatching {
            when {
                line.startsWith(HANDSHAKE) -> line.removePrefix(HANDSHAKE).trim().split(' ', limit = 2)
                    .takeIf { it.size == 2 }?.let { (base, token) -> Handshake(base, token) }
                line.startsWith(STATUS) -> JsonParser.parseString(line.removePrefix(STATUS)).asJsonObject.let {
                    Status(it.string("state") ?: "building", it.string("phase"), it.string("error"))
                }
                line.startsWith(REVEAL) -> JsonParser.parseString(line.removePrefix(REVEAL)).asJsonObject.let {
                    Reveal(it.string("path")!!, it.get("line").asInt)
                }
                else -> null
            }
        }.getOrNull() ?: Log(line)

        private fun com.google.gson.JsonObject.string(name: String): String? =
            get(name)?.takeUnless { it.isJsonNull }?.asString
    }
}
