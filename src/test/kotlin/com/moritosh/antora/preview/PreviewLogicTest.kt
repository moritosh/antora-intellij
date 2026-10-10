package com.moritosh.antora.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PreviewLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, text: String = ""): Path =
        tmp.root.toPath().resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private fun executable(path: String): Path = file(path).also { it.toFile().setExecutable(true) }

    // --- ServerLine ---

    @Test
    fun `parses the handshake`() {
        assertEquals(
            ServerLine.Handshake("http://127.0.0.1:4321", "abc123"),
            ServerLine.parse("ANTORA_PREVIEW http://127.0.0.1:4321 abc123"),
        )
    }

    @Test
    fun `parses status lines`() {
        assertEquals(
            ServerLine.Status("building", "Reading content sources", null),
            ServerLine.parse("""ANTORA_PREVIEW_STATUS {"state":"building","phase":"Reading content sources","started":1}"""),
        )
        assertEquals(
            ServerLine.Status("failed", null, "boom"),
            ServerLine.parse("""ANTORA_PREVIEW_STATUS {"state":"failed","error":"boom"}"""),
        )
    }

    @Test
    fun `parses reveal lines`() {
        assertEquals(
            ServerLine.Reveal("/site/docs/modules/ROOT/pages/index.adoc", 12),
            ServerLine.parse("""ANTORA_PREVIEW_REVEAL {"path":"/site/docs/modules/ROOT/pages/index.adoc","line":12}"""),
        )
    }

    @Test
    fun `anything else, or a malformed line, is log output`() {
        assertEquals(ServerLine.Log("[antora-preview] built 3 pages"), ServerLine.parse("[antora-preview] built 3 pages"))
        assertEquals(ServerLine.Log("ANTORA_PREVIEW_STATUS {oops"), ServerLine.parse("ANTORA_PREVIEW_STATUS {oops"))
        assertEquals(ServerLine.Log("ANTORA_PREVIEW onlyone"), ServerLine.parse("ANTORA_PREVIEW onlyone"))
    }

    // --- RuntimeLocator ---

    @Test
    fun `prefers a configured runtime, then bun, then node`() {
        val bin = executable("bin/node").parent
        executable("bin/bun")
        val locator = RuntimeLocator(mapOf("PATH" to bin.toString()), home = null, windows = false)
        assertEquals(Path.of("/custom/node"), locator.find("/custom/node"))
        assertEquals(bin.resolve("bun"), locator.find(null))
    }

    @Test
    fun `finds bun in its install directory when the PATH lacks it`() {
        val home = tmp.newFolder("home").toPath()
        executable("home/.bun/bin/bun")
        val locator = RuntimeLocator(mapOf("PATH" to ""), home, windows = false)
        assertEquals(home.resolve(".bun/bin/bun"), locator.find(null))
    }

    @Test
    fun `takes the newest node of nvm`() {
        val home = tmp.newFolder("home").toPath()
        executable("home/.nvm/versions/node/v20.9.1/bin/node")
        executable("home/.nvm/versions/node/v20.10.0/bin/node")
        executable("home/.nvm/versions/node/v20.10.0/bin/pnpm")
        val locator = RuntimeLocator(emptyMap(), home, windows = false, systemDirs = emptyList())
        assertEquals(home.resolve(".nvm/versions/node/v20.10.0/bin/node"), locator.find(null))
        assertEquals(home.resolve(".nvm/versions/node/v20.10.0/bin/pnpm"), locator.tool("pnpm"))
    }

    @Test
    fun `ignores files that are not executable, and uses exe and cmd on Windows`() {
        val bin = file("bin/bun").parent
        executable("bin/node.exe")
        executable("bin/npm.cmd")
        assertNull(RuntimeLocator(mapOf("PATH" to bin.toString()), null, windows = false).onPath("bun"))
        val windows = RuntimeLocator(mapOf("Path" to bin.toString() + File.pathSeparator), null, windows = true)
        assertEquals(bin.resolve("node.exe"), windows.onPath("node"))
        assertEquals(bin.resolve("npm.cmd"), windows.onPath("npm"))
    }

    @Test
    fun `takes the newest node of fnm on Windows, which only PowerShell has on the PATH`() {
        val appData = tmp.newFolder("AppData").toPath()
        executable("AppData/fnm/node-versions/v22.2.0/installation/node.exe")
        executable("AppData/fnm/node-versions/v22.11.0/installation/node.exe")
        executable("AppData/fnm/node-versions/v22.11.0/installation/pnpm.cmd")
        val locator = RuntimeLocator(mapOf("AppData" to appData.toString()), null, windows = true)
        assertEquals(appData.resolve("fnm/node-versions/v22.11.0/installation/node.exe"), locator.find(null))
        assertEquals(appData.resolve("fnm/node-versions/v22.11.0/installation/pnpm.cmd"), locator.tool("pnpm"))
    }

    @Test
    fun `finds the node of nvm-windows, the active one first`() {
        val nvm = tmp.newFolder("nvm").toPath()
        executable("nvm/v18.20.0/node.exe")
        executable("nvm/v20.10.0/node.exe")
        executable("nvm/nvm.exe")
        assertEquals(
            nvm.resolve("v20.10.0/node.exe"),
            RuntimeLocator(mapOf("NVM_HOME" to nvm.toString()), null, windows = true).find(null),
        )
        val active = executable("nodejs/node.exe").parent
        executable("nodejs/npm.cmd")
        val locator = RuntimeLocator(mapOf("NVM_HOME" to nvm.toString(), "NVM_SYMLINK" to active.toString()), null, windows = true)
        assertEquals(active.resolve("node.exe"), locator.find(null))
        assertEquals(active.resolve("npm.cmd"), locator.tool("npm"))
    }

    // --- PlaybookLocator ---

    @Test
    fun `finds the nearest playbook upwards, preferring antora-playbook yml`() {
        val page = file("site/docs/modules/ROOT/pages/index.adoc")
        file("site/local-playbook.yml")
        file("site/antora-playbook.yml")
        file("antora-playbook.yml")
        assertEquals(tmp.root.toPath().resolve("site/antora-playbook.yml"), PlaybookLocator.nearest(page, tmp.root.toPath()))
    }

    @Test
    fun `stops at the given directory and skips hidden files`() {
        val page = file("site/docs/index.adoc")
        file(".playbook.yml")
        file("site/.hidden-playbook.yml")
        assertNull(PlaybookLocator.nearest(page, tmp.root.toPath()))
        file("site/docs/playbook.yaml")
        assertEquals(tmp.root.toPath().resolve("site/docs/playbook.yaml"), PlaybookLocator.nearest(page, tmp.root.toPath()))
    }

    // --- Dependencies ---

    @Test
    fun `detects the package manager from packageManager, then the lockfile`() {
        val dir = tmp.root.toPath()
        assertEquals(PackageManager.NPM, PackageManager.detect(dir))
        file("bun.lock")
        assertEquals(PackageManager.BUN, PackageManager.detect(dir))
        file("package.json", """{ "packageManager": "pnpm@12.6.0" }""")
        assertEquals(PackageManager.PNPM, PackageManager.detect(dir))
    }

    @Test
    fun `finds Antora in node_modules upwards, as Node resolves it`() {
        val playbookDir = tmp.newFolder("repo", "docs").toPath()
        assertFalse(ProjectDeps.isAntoraInstalled(playbookDir))
        file("repo/node_modules/antora/package.json", "{}")
        assertTrue(ProjectDeps.isAntoraInstalled(playbookDir))
        file("repo/package.json", "{}")
        assertEquals(tmp.root.toPath().resolve("repo"), ProjectDeps.packageDir(playbookDir))
    }
}
