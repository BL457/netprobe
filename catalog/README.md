# NetProbe tool catalog

This folder holds community tools for NetProbe. In the app, Tools > More > "Update tools from GitHub" (and Settings > Tools) reads
[`index.json`](index.json), shows what is new or changed, and installs it when you tap Install. Nothing is fetched until you ask.

Every tool here is one JavaScript file, written the way the [tools guide](../docs/tools-scripting.md) describes. The app shows the
tool's description, network scope and whether it can send broadcast packets before it installs anything, and you can read the source on
the same screen.

## Contributing a tool

1. Write the script by following [docs/tools-scripting.md](../docs/tools-scripting.md). Import it into the app from a file and run it on
   a device you own.
2. Save it here as `<id>.js`, where `<id>` is the tool's `id` (3 to 40 characters from `a-z`, `0-9` and `-`). The file name must match.
3. Keep the tool's own fields (`id`, `name`, `version`, `description`, `network`, `broadcast`) as plain string, `true` or `false` literals,
   written before `inputs:` and `run:`. A string must not contain a backslash or a double quote. The index script reads them with a small
   text parser and does not run your code.
4. Run `.\tools\make-catalog-index.ps1`. It rewrites `index.json` with the name, version, network, description, broadcast flag and SHA-256
   of every script in this folder.
5. Run the unit tests (`.\gradlew.bat testDebugUnitTest`). `CatalogTest` loads every script in the sandbox, reads its description, and
   checks it against `index.json`.
6. Open a pull request with the script and the updated `index.json`.

## Updating a tool

Change the script, raise its `version`, and run `.\tools\make-catalog-index.ps1` again. The app refuses to install a version older than
the one on the phone. If a new version needs a wider network scope (`target`, then `lan`, then `any`), or starts to declare `broadcast`,
the review sheet in the app calls it out.

## What the app does with the index

| Index field | Used for |
| --- | --- |
| `id`, `file` | The tool is installed as `user:<id>`; the script is fetched from `<file>` in this folder. |
| `name`, `version`, `description`, `network`, `broadcast` | The review sheet, before the script is fetched. The script's own description is what counts once it is read. |
| `sha256` | A tool whose installed script has this SHA-256 is unchanged and is not fetched again. |

The SHA-256 is taken over the file with Unix line endings, which is what GitHub serves. `.gitattributes` keeps this folder on Unix line
endings so the hash is the same on every machine.

A tool removed from this folder stays on the phone until its owner removes it.
