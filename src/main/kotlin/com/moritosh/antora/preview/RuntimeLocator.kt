package com.moritosh.antora.preview

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Finds a JavaScript runtime for the preview server: Bun if available, else Node, as antora-ls does.
 *
 * An IDE started from the Dock doesn't inherit the shell's PATH, so callers pass the shell
 * environment, and the usual install locations of version managers are probed as well.
 */
class RuntimeLocator(
    private val env: Map<String, String>,
    private val home: Path?,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    fun find(configured: String?): Path? {
        if (!configured.isNullOrBlank()) return Path.of(configured)
        return onPath("bun") ?: home?.resolve(".bun/bin")?.let { inDir(it, "bun") }
            ?: onPath("node") ?: nodeFromVersionManagers()
    }

    /**
     * A command-line tool such as a package manager: on the PATH, in Bun's directory, or next to
     * the Node that version managers installed (where npm, and often pnpm and yarn, live).
     */
    fun tool(name: String): Path? =
        onPath(name) ?: home?.resolve(".bun/bin")?.let { inDir(it, name) }
            ?: nodeFromVersionManagers()?.parent?.let { inDir(it, name) }

    /** An executable named [name] in one of the PATH directories. */
    fun onPath(name: String): Path? =
        envVar("PATH")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }
            ?.firstNotNullOfOrNull { inDir(Path.of(it), name) }

    // Windows spells some names differently (Path, AppData) and ignores case.
    private fun envVar(name: String): String? =
        env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun envDir(name: String): Path? = envVar(name)?.let { runCatching { Path.of(it) }.getOrNull() }

    private fun inDir(dir: Path, name: String): Path? =
        executableNames(name).map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    // npm, pnpm and yarn come as .cmd shims on Windows.
    private fun executableNames(name: String) = if (windows) listOf("$name.exe", "$name.cmd") else listOf(name)

    private fun nodeFromVersionManagers(): Path? {
        val fixed = if (windows) windowsDirs() else unixDirs()
        fixed.firstNotNullOfOrNull { inDir(it, "node") }?.let { return it }
        // Version managers keep one directory per Node version; take the newest.
        val versioned = if (windows) windowsVersionDirs() else unixVersionDirs()
        return versioned.flatMap { (root, bin) ->
            if (!root.isDirectory()) emptyList()
            else runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList()).mapNotNull { version ->
                inDir(version.resolve(bin), "node")?.let { version.fileName.toString() to it }
            }
        }.maxWithOrNull(compareBy(VERSION_ORDER) { it.first })?.second
    }

    private fun unixDirs(): List<Path> = buildList {
        home?.let {
            add(it.resolve(".volta/bin"))
            add(it.resolve(".asdf/shims"))
            add(it.resolve(".local/share/mise/shims"))
        }
        add(Path.of("/opt/homebrew/bin"))
        add(Path.of("/usr/local/bin"))
    }

    private fun unixVersionDirs(): List<Pair<Path, String>> = home?.let {
        listOf(
            it.resolve(".nvm/versions/node") to "bin",
            it.resolve(".local/share/fnm/node-versions") to "installation/bin",
            it.resolve("Library/Application Support/fnm/node-versions") to "installation/bin",
        )
    }.orEmpty()

    /**
     * nvm-windows' active version, Volta, mise and Scoop shims, and the Node installer's directory.
     * These are usually on the PATH, but a Windows app keeps the PATH it started with, so Node
     * installed while the IDE runs is only found here.
     */
    private fun windowsDirs(): List<Path> = listOfNotNull(
        envDir("NVM_SYMLINK"),
        Path.of("C:/nvm4w/nodejs"), // nvm-windows' default NVM_SYMLINK
        envDir("LOCALAPPDATA")?.resolve("Volta/bin"),
        envDir("LOCALAPPDATA")?.resolve("mise/shims"),
        home?.resolve("scoop/shims"),
        envDir("ProgramFiles")?.resolve("nodejs"),
    )

    /** nvm-windows keeps node.exe right in each version's directory; fnm puts it on the PATH only in PowerShell. */
    private fun windowsVersionDirs(): List<Pair<Path, String>> = listOfNotNull(
        envDir("NVM_HOME")?.let { it to "" },
        envDir("APPDATA")?.let { it.resolve("nvm") to "" },
        (envDir("FNM_DIR") ?: envDir("APPDATA")?.resolve("fnm"))?.let { it.resolve("node-versions") to "installation" },
    )

    companion object {
        /** Orders `v20.10.0` after `v20.9.1`. */
        val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
            val pa = a.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
            (0 until maxOf(pa.size, pb.size)).firstNotNullOfOrNull { i ->
                (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 }).takeIf { it != 0 }
            } ?: 0
        }
    }
}
