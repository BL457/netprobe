package com.northwalessolutions.netprobe.update

/** Every constant of the in-app updater (spec 1, 2, 4). The manifest URL is the one place to change where releases are published. */
object UpdateConfig {
    const val MANIFEST_URL = "https://github.com/BL457/netprobe/releases/latest/download/latest.json"
    const val MANIFEST_MAX_BYTES = 64L * 1024
    const val APK_MAX_BYTES = 64L * 1024 * 1024
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000
    const val MAX_REDIRECTS = 5
    const val NOTES_MAX_CHARS = 2000
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val SHA256_HEX_CHARS = 64
    const val UPDATES_DIR = "updates"
    const val APK_SUFFIX = ".apk"
    const val PART_SUFFIX = ".apk.part"
    const val FILE_PREFIX = "netprobe-"
    const val APK_MIME = "application/vnd.android.package-archive"
    const val FILE_PROVIDER_SUFFIX = ".updates"
    /** Download progress reaches the banner at most once per this many bytes. */
    const val PROGRESS_STEP_BYTES = 65_536L
    const val PREF_LAST_CHECK = "lastCheckEpochMs"
    const val PREF_LAST_CHECK_VERSION = "lastCheckVersionCode"
    const val PREF_SKIPPED = "skippedVersionCode"
    const val PREF_AUTO = "updateAuto"
    const val PREF_LAST_SEEN_VERSION = "lastSeenVersionCode"
    const val PREF_PENDING_VERSION = "pendingVersionCode"
    const val PREF_PENDING_NOTES = "pendingNotes"
    /** The tool catalog (spec section 11): the index and the scripts it lists are fetched from this folder, and only when the user asks. */
    const val CATALOG_BASE_URL = "https://raw.githubusercontent.com/BL457/netprobe/master/catalog/"
    const val CATALOG_INDEX_URL = CATALOG_BASE_URL + "index.json"
    const val CATALOG_INDEX_MAX_BYTES = 1024L * 1024
    /** All published versions, opened from Settings > About. */
    const val RELEASES_URL = "https://github.com/BL457/netprobe/releases"
}
