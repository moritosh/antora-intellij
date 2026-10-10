package com.moritosh.antora.preview

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Finds the Antora playbook that builds a file. */
object PlaybookLocator {
    private val PLAYBOOK = Regex("""(?!\.).*playbook.*\.ya?ml""", RegexOption.IGNORE_CASE)

    /**
     * The playbook nearest to [file]: the first directory upwards that has one, not going above
     * [stopAt] (the user's home, say). Playbooks usually sit at the repository root, above the
     * component they build.
     */
    fun nearest(file: Path, stopAt: Path?): Path? {
        var dir: Path? = if (file.isDirectory()) file else file.parent
        while (dir != null) {
            pick(playbooksIn(dir))?.let { return it }
            if (dir == stopAt) break
            dir = dir.parent
        }
        return null
    }

    fun playbooksIn(dir: Path): List<Path> =
        runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())
            .filter { PLAYBOOK.matches(it.name) && it.isRegularFile() }
            .sortedBy { it.name }

    /** `antora-playbook.yml` if present, else the first one, as antora-ls picks. */
    fun pick(playbooks: List<Path>): Path? =
        playbooks.firstOrNull { it.name == "antora-playbook.yml" } ?: playbooks.firstOrNull()
}
