# Cutting a NetProbe release

NetProbe is distributed by sideload. The app checks `latest.json` on GitHub and installs a newer APK with one tap
(see `docs/superpowers/specs/2026-10-23-in-app-updater-design.md`).

## One-off: create the release keystore

Run this in your own terminal, never through an assistant session. Keep the keystore and its passwords safe: every future update must
be signed with this same key, and losing it means users must uninstall to update.

```
keytool -genkeypair -v -keystore K:\claude\netprobe\netprobe-release.jks -alias netprobe -keyalg RSA -keysize 4096 -validity 10000
```

Then create `K:\claude\netprobe\keystore.properties` (git-ignored, like `*.jks` and `*.keystore`):

```
storeFile=netprobe-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=netprobe
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` is relative to the repo root. Without `keystore.properties`, `assembleRelease` still works but prints a warning and signs
with the debug key; such an APK cannot update a release-signed install (the updater rejects it).

Note: the first release-signed APK cannot update a debug-signed install either. Uninstall the debug build once.

## Each release

1. Bump `versionCode` (integer, must increase) and `versionName` in `app/build.gradle.kts`.
2. Add a `## <versionName>` section at the top of `CHANGELOG.md` (below the intro) with bullet points. These are the release notes: the
   first line shows on the update banner, the whole section under "What's new" and on the post-update card (2000 characters at most).
   Commit.
3. Build: `$env:JAVA_HOME="C:/Program Files/Java/jdk-17.0.2"; .\gradlew.bat assembleRelease`
   The APK is `app\build\outputs\apk\release\app-release.apk`.
4. Write the manifest next to it:

   ```
   .\tools\make-latest-json.ps1 -Apk app\build\outputs\apk\release\app-release.apk `
       -ApkUrl https://github.com/BL457/netprobe/releases/download/v<versionName>/app-release.apk
   ```

   The script reads the notes from the `CHANGELOG.md` section named after the APK's versionName and fails if there is none.
   `-Notes notes.txt` overrides that with a text file.
   If `aapt2` is not found under the Android SDK, add `-VersionCode <n> -VersionName <x.y.z>`.
5. Create the GitHub release `v<versionName>` and attach both `app-release.apk` and `latest.json` as assets. The app reads
   `https://github.com/BL457/netprobe/releases/latest/download/latest.json`, so the newest published (non-draft, non-prerelease)
   release is what users see. To change that URL edit `UpdateConfig.MANIFEST_URL`.
6. Check: open the installed older build, Settings, Updates, "Check for updates".
