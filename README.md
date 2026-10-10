# Antora Live Preview for IntelliJ

A dev-server-style live preview of [Antora](https://antora.org) sites for IntelliJ IDEA and other JetBrains IDEs. It requires the [AsciiDoc plugin](https://plugins.jetbrains.com/plugin/7391-asciidoc).

It runs the same preview server as [antora-ls](https://github.com/moritosh/antora-ls) for Zed: `antora-preview.js` builds the site with the project's own Antora and serves it from memory. The plugin starts the server, feeds it your edits and shows the result.

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

The build bundles `antora-preview.js` from antora-ls, which must be checked out next to this repository (`antoraPreviewScript` in `gradle.properties`). Gradle needs JDK 21 or later, such as the runtime that ships with IntelliJ:

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

Set `pluginVersion` in `gradle.properties` and run `./gradlew buildPlugin`. The zip works on macOS, Windows and Linux. To distribute it:

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
