package com.northwalessolutions.netprobe.update

import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.net.URL

/** The two persisted facts of the check policy (spec 2), in the netprobe preferences. */
interface UpdatePrefs {
    var lastCheckEpochMs: Long
    /** The versionCode the user chose "Later" for; 0 for none. */
    var skippedVersionCode: Int
    /** The versionCode that performed the last check; 0 for none. */
    var lastCheckVersionCode: Int
}

class SharedUpdatePrefs(private val prefs: SharedPreferences) : UpdatePrefs {
    override var lastCheckEpochMs: Long
        get() = prefs.getLong(UpdateConfig.PREF_LAST_CHECK, 0L)
        set(v) { prefs.edit().putLong(UpdateConfig.PREF_LAST_CHECK, v).apply() }
    override var skippedVersionCode: Int
        get() = prefs.getInt(UpdateConfig.PREF_SKIPPED, 0)
        set(v) { prefs.edit().putInt(UpdateConfig.PREF_SKIPPED, v).apply() }
    override var lastCheckVersionCode: Int
        get() = prefs.getInt(UpdateConfig.PREF_LAST_CHECK_VERSION, 0)
        set(v) { prefs.edit().putInt(UpdateConfig.PREF_LAST_CHECK_VERSION, v).apply() }
}

sealed interface CheckResult {
    data class Available(val manifest: UpdateManifest) : CheckResult
    data object UpToDate : CheckResult
    /** An automatic check that did not run: checked within 24 h. */
    data object NotDue : CheckResult
    /** An automatic check found a version the user chose "Later" for. */
    data object Skipped : CheckResult
    /** The manifest was fetched but is not usable (spec 1). */
    data object Invalid : CheckResult
    /** The manifest could not be fetched. */
    data object Failed : CheckResult
}

/** Decides whether to look, looks, and judges the manifest (spec 2). No Android types: [sdkInt] and [installedVersionCode] are passed in. */
class UpdateChecker(private val fetcher: UpdateFetcher, private val clock: () -> Long, private val prefs: UpdatePrefs,
                    private val installedVersionCode: Int, private val sdkInt: Int, private val manifestUrl: String = UpdateConfig.MANIFEST_URL,
                    private val log: (String) -> Unit = {}) {

    /** When the last check ran (epoch ms), 0 for never. */
    fun lastCheckEpochMs(): Long = prefs.lastCheckEpochMs

    /** [manual] ignores the 24 h stamp and the skipped version, and clears the skip. An update of the app also makes an automatic check due. */
    suspend fun check(manual: Boolean): CheckResult {
        val now = clock()
        val since = now - prefs.lastCheckEpochMs
        val updatedSinceCheck = prefs.lastCheckVersionCode != installedVersionCode
        if (!manual && !updatedSinceCheck && since in 0 until UpdateConfig.CHECK_INTERVAL_MS) return CheckResult.NotDue
        prefs.lastCheckVersionCode = installedVersionCode
        prefs.lastCheckEpochMs = now   // stamped before the fetch, so a failed check is not retried in a loop
        val body = ByteArrayOutputStream()
        when (val r = fetcher.fetch(URL(manifestUrl), UpdateConfig.MANIFEST_MAX_BYTES, body)) {
            is UpdateFetch.Failed -> { log("manifest fetch failed: ${r.error}"); return CheckResult.Failed }
            is UpdateFetch.Ok -> Unit
        }
        val manifest = when (val p = UpdateManifestParser.parse(body.toByteArray())) {
            is ManifestParse.Valid -> p.manifest
            ManifestParse.Invalid -> { log("manifest invalid"); return CheckResult.Invalid }
        }
        if (manifest.versionCode <= installedVersionCode) return CheckResult.UpToDate
        if (manifest.minSdk != null && manifest.minSdk > sdkInt) return CheckResult.UpToDate
        if (manual || manifest.versionCode > prefs.skippedVersionCode) prefs.skippedVersionCode = 0
        if (!manual && manifest.versionCode == prefs.skippedVersionCode) return CheckResult.Skipped
        return CheckResult.Available(manifest)
    }

    /** "Later": automatic checks no longer show this version. */
    fun skip(versionCode: Int) { prefs.skippedVersionCode = versionCode }
}
