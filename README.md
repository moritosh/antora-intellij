# Antora Live Preview for IntelliJ

A dev-server-style live preview of [Antora](https://antora.org) sites for IntelliJ IDEA and other JetBrains IDEs. It requires the [AsciiDoc plugin](https://plugins.jetbrains.com/plugin/7391-asciidoc).

It runs the same preview server as [antora-ls](https://github.com/moritosh/antora-ls) for Zed: `antora-preview.js` builds the site with the project's own Antora and serves it from memory. The plugin starts the server, feeds it your edits and shows the result.

The script is bundled into the plugin from an antora-ls release, the one set by `antoraLsVersion` in `gradle.properties`; version 0.1.0 bundles antora-ls 0.2.2. The two projects are versioned independently, so a fix to the preview server reaches this plugin once `antoraLsVersion` is raised and the plugin is released.

## Install

The plugin isn't on JetBrains Marketplace yet; install it from the zip of the [latest release](https://github.com/moritosh/antora-intellij/releases/latest). It needs IntelliJ IDEA 2026.2 or later, or another JetBrains IDE of that version, with the AsciiDoc plugin.

### macOS

1. In IntelliJ IDEA, open **IntelliJ IDEA | Settings** (`⌘,`), go to **Plugins | Marketplace**, search for **AsciiDoc** and install it.
2. Download `antora-intellij-<version>.zip` from the [latest release](https://github.com/moritosh/antora-intellij/releases/latest). Don't unpack it.
3. In **Settings | Plugins**, click **⚙ | Install Plugin from Disk…** and choose the zip. No restart is needed.
4. Install [Bun](https://bun.sh) (`curl -fsSL https://bun.sh/install | bash`) or Node (`brew install node`, or the installer from [nodejs.org](https://nodejs.org)). The plugin finds them even when IntelliJ is started from the Dock; see [Requirements](#requirements) for where it looks.
5. Open your docs project, right-click a page in the editor or the project view, and choose **Open Antora Live Preview**.

### Windows

1. In IntelliJ IDEA, open **File | Settings** (`Ctrl+Alt+S`), go to **Plugins | Marketplace**, search for **AsciiDoc** and install it.
2. Download `antora-intellij-<version>.zip` from the [latest release](https://github.com/moritosh/antora-intellij/releases/latest). Don't unpack it.
3. In **Settings | Plugins**, click **⚙ | Install Plugin from Disk…** and choose the zip. No restart is needed.
4. Install [Bun](https://bun.sh) (`powershell -c "irm bun.sh/install.ps1 | iex"`) or Node (`winget install OpenJS.NodeJS.LTS`, or the installer from [nodejs.org](https://nodejs.org)). The plugin also finds Node installed while IntelliJ is running, so there's no need to restart it.
5. Open your docs project, right-click a page in the editor or the project view, and choose **Open Antora Live Preview**.

### Linux

1. In IntelliJ IDEA, open **File | Settings** (`Ctrl+Alt+S`), go to **Plugins | Marketplace**, search for **AsciiDoc** and install it.
2. Download `antora-intellij-<version>.zip` from the [latest release](https://github.com/moritosh/antora-intellij/releases/latest). Don't unpack it.
3. In **Settings | Plugins**, click **⚙ | Install Plugin from Disk…** and choose the zip. No restart is needed.
4. Install [Bun](https://bun.sh) (`curl -fsSL https://bun.sh/install | bash`; it needs `unzip`) or Node (your distribution's `nodejs` package, or [nvm](https://github.com/nvm-sh/nvm) or fnm).
5. Open your docs project, right-click a page in the editor or the project view, and choose **Open Antora Live Preview**. If the IDE's embedded browser isn't available, the preview opens in the system browser.

### The docs project

The preview builds the site with the project's own Antora 3. If the project's `package.json` lists Antora but it isn't installed yet, the plugin offers to install the dependencies with the project's package manager. If Antora isn't listed, add it first:

```sh
npm i -D antora    # or: pnpm add -D antora, yarn add -D antora, bun add -d antora
```

## Use

Run **Open Antora Live Preview** on a page, from the editor or project view context menu, the AsciiDoc editor toolbar, or **Tools**. The page opens in the **Antora Preview** tool window; **Open Antora Live Preview in Browser** opens it in the system browser.

- **Fast start:** the preview opens at once and shows build progress as a background task; cancelling the task stops the preview. Pages render when first viewed, so sites with thousands of pages are ready in seconds.
- **Working tree:** local content sources are read from the working tree, whatever branches the playbook names.
- **Live:** unsaved edits re-render only the changed page, without a reload. Edits to partials, examples and nav files update the pages that use them. New files, `antora.yml` and supplemental UI files trigger a full rebuild. Changes made outside the IDE are picked up too.
- **Follows the cursor** and the active editor. *Follow Cursor* in the tool window turns this off.
- **Double-click** a block in the preview to jump to its source line.
- **Log** tab: the server's and Antora's output.

The preview uses the nearest playbook above the page: `antora-playbook.yml`, else another `*playbook*.yml`. There is one preview per project; opening a page from another playbook restarts it. The runtime, playbook and package manager can be set in **Settings | Tools | Antora Live Preview**, as can whether the tool window keeps its embedded browser while no preview runs (on by default); turning this off frees the browser's memory.

## Requirements

- IntelliJ 2026.2 or later, with the AsciiDoc plugin.
- Bun or Node; Bun is preferred. The plugin looks on the `PATH`, in `~/.bun/bin`, and where Volta, nvm, fnm, asdf, mise or Homebrew install Node. On Windows it also checks nvm-windows, fnm, Volta, mise, Scoop and `Program Files\nodejs`.
- Antora 3 in the project's `node_modules`, installed with npm, pnpm, Yarn (not Plug'n'Play) or Bun. If it's missing, the plugin offers to install it with the package manager named by `packageManager` in `package.json`, else the one whose lockfile is present.

## Limitations

- Pages render on demand, so search extensions such as Lunr only index titles, and extensions that post-process converted pages don't run.
- Antora installed only globally isn't supported.
- Remote Development and WSL aren't supported yet.
- Installing or uninstalling the plugin doesn't need a restart, but the AsciiDoc editor toolbar only changes in editors opened afterwards: the AsciiDoc plugin builds it when the editor opens. Reopen a file to add or remove the preview button. The right-click menus and **Tools** are updated at once.

## Build

The build bundles `antora-preview.js` from antora-ls, which must be checked out next to this repository (`antoraPreviewScript` in `gradle.properties`). Releases take it from the antora-ls release named by `antoraLsVersion`; to build the same locally, check out that tag in antora-ls (for example `git -C ../antora-ls checkout v0.2.2`). Gradle needs JDK 21 or later, such as the runtime that ships with IntelliJ:

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew test
./gradlew runIde        # sandbox IDE with the AsciiDoc plugin and this one
./gradlew buildPlugin   # build/distributions/antora-intellij-<version>.zip
```

On Windows, run `gradlew.bat` with `JAVA_HOME` set to the `jbr` folder of the IntelliJ installation.

The build downloads the IntelliJ version and AsciiDoc plugin version set in `gradle.properties`. To use installed copies instead, set them in `local.properties`, which isn't checked in:

```properties
platformLocalPath=/Users/you/Applications/IntelliJ IDEA.app
asciidocLocalPath=/Users/you/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/asciidoctor-intellij-plugin
```

## Release

Set `pluginVersion` in `gradle.properties`, commit, and push a tag like `v0.1.0`. The `release` workflow checks the tag against `pluginVersion`, runs the tests, builds the plugin with `antora-preview.js` from the antora-ls release named by `antoraLsVersion`, and attaches `antora-intellij-<version>.zip` to a GitHub release. To ship changes to the preview server, release antora-ls first and raise `antoraLsVersion`.

The zip works on macOS, Windows and Linux. To distribute it:

- **From disk:** **Settings | Plugins | ⚙ | Install Plugin from Disk…**, after installing the AsciiDoc plugin.
- **JetBrains Marketplace:** upload the first version by hand at [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add). After that, `PUBLISH_TOKEN=… ./gradlew publishPlugin` publishes a new version. To [sign](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) it, also set `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`. Marketplace offers to install the AsciiDoc plugin along with this one.
- **Your own repository:** host the zip and an [`updatePlugins.xml`](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html) on a web server. Users add its URL under **Settings | Plugins | ⚙ | Manage Plugin Repositories** and then get updates as from Marketplace.

## How it works

```
IntelliJ plugin (Kotlin)                          antora-preview.js (Bun or Node)
  AntoraPreviewService ── starts ───────────────▶ builds the site in memory with the
    PreviewProcess  ◀── handshake, status, ─────   project's Antora, serves it on
                         reveal (stdout)           127.0.0.1, pushes updates to pages
    PreviewClient   ── POST update / cursor ────▶  over server-sent events
    EditorSync         (document, caret, save events)
  Tool window: embedded browser (JCEF) + Log
```

- The server prints `ANTORA_PREVIEW <url> <token>` on stdout first, then `ANTORA_PREVIEW_STATUS <json>` lines for build progress and `ANTORA_PREVIEW_REVEAL <json>` lines for double-clicks. It exits when its stdin closes, so it stops with the IDE.
- Unsaved text and caret lines go to `/__preview/update` and `/__preview/cursor` with the token, batched in 40 ms windows.
- Everything Antora-specific lives in the shared script; the plugin knows nothing about Antora's content model.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). The bundled preview server comes from [antora-ls](https://github.com/moritosh/antora-ls), also Apache-2.0.
