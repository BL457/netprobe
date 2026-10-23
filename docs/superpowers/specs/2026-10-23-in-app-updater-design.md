# NetProbe in-app updater design (2026-10-23)

Director ruling 2026-10-23: Google Play is out (targetSdk 30 stays, MACs matter more). Distribution is sideload. The app must tell the user when a newer release exists and install it with one tap (option 1). Lean workflow: this spec is the plan.

## 1. Manifest
- `UpdateConfig.MANIFEST_URL` (one constant, easy to change). Default assumption: `https://github.com/BL457/netprobe/releases/latest/download/latest.json`. GitHub answers that path with a redirect to another host, so the fetcher follows https-to-https redirects (max 5) and refuses any http hop.
- `latest.json` fields: `versionCode` Int (required), `versionName` String (required), `apkUrl` String https (required), `sha256` 64 hex chars (required), `sizeBytes` Long (optional), `notes` String (optional, truncated to 2000 chars), `minSdk` Int (optional).
- Parse with kotlinx.serialization, `ignoreUnknownKeys = true`. Any missing required field, non-https URL or malformed sha256 makes the manifest invalid: log, no banner, "Could not read the update information" only on a manual check.
- Manifest body cap 64 KB, timeout 10 s.

## 2. Check policy
- `UpdateChecker(fetcher, clock, prefs, installedVersionCode)`: automatic check when MainActivity comes to the foreground, at most once per 24 h (`lastCheckEpochMs` in the `netprobe` SharedPreferences), only when a network is available. Never on a metered-check failure loop: a failed check still stamps `lastCheckEpochMs`.
- Settings toggle "Check for updates automatically" (default on) in a new "Updates" group above "Support NetProbe".
- Settings row "Check for updates" forces a check regardless of the 24 h stamp and the skipped version. Results: banner shown, "NetProbe is up to date", or an error notice via AppMessages.
- "Later" on the banner stores `skippedVersionCode`; automatic checks do not show a banner for that version again. A newer version or a manual check clears it.
- A manifest whose `versionCode` <= installed `BuildConfig.VERSION_CODE`, or whose `minSdk` > `Build.VERSION.SDK_INT`, means up to date.

## 3. UI
- Home: an `UpdateBanner` pinned above the host list, outside any scroll container (pinned-controls rule). States: available (title "NetProbe {versionName} is available", first line of notes, buttons Update / Later, "What's new" expands the full notes), downloading (progress bar, "{percent}%", pinned Stop), verifying, ready-to-install (button Install, shown if the user dismissed the installer), failed (one line + Retry).
- Permission: if `packageManager.canRequestPackageInstalls()` is false when the user taps Update, show a dialog "Allow NetProbe to install updates" explaining Android asks once, with "Open settings" launching `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:` URI. On return the banner stays in the available state.
- All strings in `update/UpdateCopy`, template-tested like the other Copy objects.

## 4. Download and verify (`UpdateInstaller`)
- Download to `cacheDir/updates/netprobe-{versionCode}.apk.part`, then rename to `.apk`. Cap 64 MB. If `sizeBytes` is present the stream must end at exactly that size. Cancellable (job cancel deletes the part file).
- SHA-256 of the file must equal the manifest `sha256` (constant-time compare not required; it is a public hash).
- APK check before install: `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` must return our `packageName`, `longVersionCode` > installed, and a signing certificate whose SHA-256 digest equals the installed app's signing certificate digest (`getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)`; API 28+ `signingInfo.apkContentsSigners`). Any mismatch deletes the file and shows "Update rejected: it was not signed with NetProbe's key".
- Install: `Intent(ACTION_VIEW)` with a FileProvider `content://` URI, MIME `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_ACTIVITY_NEW_TASK`. Add `<provider>` (androidx.core FileProvider, already in the dependency tree via androidx.core; no new dependency) with `res/xml/update_paths.xml` exposing `cache-path name="updates" path="updates/"`.
- Manifest permission `REQUEST_INSTALL_PACKAGES`.
- Housekeeping: on startup delete every file in `cacheDir/updates/` whose version is <= installed, and any `.part` file.

## 5. Release signing and release recipe
- `app/build.gradle.kts`: `signingConfigs.release` reads `keystore.properties` at the repo root (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). If the file is absent, print a Gradle warning and sign release with the debug config so builds never break. Add `keystore.properties`, `*.jks`, `*.keystore` to `.gitignore`.
- `tools/make-latest-json.ps1 -Apk <path> -ApkUrl <url> [-Notes <file>]`: reads versionCode/versionName from the APK via `aapt2 dump badging` if found under the SDK, otherwise takes `-VersionCode`/`-VersionName` parameters; writes `latest.json` next to the APK with sha256 and sizeBytes.
- `docs/release.md`: how to cut a release (bump versionCode and versionName, `gradlew assembleRelease`, run the script, create the GitHub release with the APK and `latest.json` as assets), and the Director's one-off keytool command for the keystore (run in his own terminal, never via the session).

## 6. Tests (JVM, no network, no netprobe.db)
- Manifest parsing: valid, each missing required field, http apkUrl, bad sha256, unknown keys ignored, notes truncation.
- Checker: 24 h stamp, failed check stamps, forced check ignores stamp and skip, skipped version hidden on automatic only, versionCode <= installed is up to date, minSdk gate, redirect limit and http hop refusal via a fake fetcher.
- Installer: sha256 mismatch deletes the file, size mismatch deletes, cancel deletes the part file (temp dir), housekeeping deletes stale files. PackageManager signature check is Android-only: isolate it behind an interface with a fake in JVM tests.
- UpdateCopy template test.

## 7. Threats considered
HTTPS only on every hop; hash plus signing-certificate match before the installer sees the file; private cache dir; manifest and APK size caps; no automatic install, the user taps Update and Android asks again; a skipped version never nags.
