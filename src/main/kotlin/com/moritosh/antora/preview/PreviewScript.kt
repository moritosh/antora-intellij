package com.moritosh.antora.preview

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The bundled `antora-preview.js`, written to disk so Bun or Node can run it. */
object PreviewScript {
    private const val RESOURCE = "/antora-preview/antora-preview.js"

    /**
     * The script's path. Each version gets its own directory: Antora loads the script again as an
     * extension on every build, so a running server must keep seeing the copy it started with.
     */
    fun path(): Path {
        val bytes = PreviewScript::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw PreviewException("antora-preview.js is missing from the plugin")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
        val target = Path.of(PathManager.getSystemPath(), "antora-preview", hash, "antora-preview.js")
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, "antora-preview", ".tmp")
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return target
    }
}
