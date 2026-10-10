package com.moritosh.antora.preview

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

enum class PackageManager(val command: String, private val lockfiles: List<String>) {
    BUN("bun", listOf("bun.lock", "bun.lockb")),
    PNPM("pnpm", listOf("pnpm-lock.yaml")),
    YARN("yarn", listOf("yarn.lock")),
    NPM("npm", listOf("package-lock.json", "npm-shrinkwrap.json"));

    override fun toString() = command

    companion object {
        /** From `packageManager` in package.json (`pnpm@9.1.0`), else the lockfile, else npm. */
        fun detect(packageDir: Path): PackageManager {
            val declared = runCatching {
                JsonParser.parseString(Files.readString(packageDir.resolve("package.json")))
                    .asJsonObject.get("packageManager")?.asString?.substringBefore('@')
            }.getOrNull()
            entries.firstOrNull { it.command == declared }?.let { return it }
            return entries.firstOrNull { pm -> pm.lockfiles.any { Files.exists(packageDir.resolve(it)) } } ?: NPM
        }
    }
}

/** Where the preview server will find Antora, which it loads from the project like Node's `require`. */
object ProjectDeps {
    /** Whether Antora resolves from [playbookDir]: some `node_modules` upwards has the site generator. */
    fun isAntoraInstalled(playbookDir: Path): Boolean = generateSequence(playbookDir) { it.parent }.any { dir ->
        val modules = dir.resolve("node_modules")
        Files.isRegularFile(modules.resolve("@antora/site-generator/package.json")) ||
            Files.isRegularFile(modules.resolve("antora/package.json"))
    }

    /** The nearest directory with a package.json, where dependencies are installed. */
    fun packageDir(playbookDir: Path): Path? =
        generateSequence(playbookDir) { it.parent }.firstOrNull { Files.isRegularFile(it.resolve("package.json")) }
}
