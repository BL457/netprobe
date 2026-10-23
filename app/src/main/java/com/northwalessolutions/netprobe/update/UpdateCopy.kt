package com.northwalessolutions.netprobe.update

/** Every word the in-app updater shows (spec 3). */
object UpdateCopy {
    const val UPDATE = "Update"
    const val LATER = "Later"
    const val STOP = "Stop"
    const val INSTALL = "Install"
    const val RETRY = "Retry"
    const val WHATS_NEW = "What's new"
    const val HIDE_NOTES = "Hide"
    const val DOWNLOADING = "Downloading update"
    const val VERIFYING = "Checking the download"
    const val READY = "Update ready to install"

    const val PERMISSION_TITLE = "Allow NetProbe to install updates"
    const val PERMISSION_BODY = "Android asks once before an app may install updates. Open settings, allow NetProbe, then come back and tap Update."
    const val PERMISSION_OPEN = "Open settings"
    const val PERMISSION_CANCEL = "Cancel"

    const val SETTINGS_SECTION = "Updates"
    const val AUTO_LABEL = "Check for updates automatically"
    const val AUTO_SUPPORT = "At most once a day, when NetProbe opens"
    const val CHECK_ROW = "Check for updates"
    const val CHECK_SUBTITLE = "Look for a newer version now"
    const val CHECKING = "Checking for updates"
    const val UP_TO_DATE = "NetProbe is up to date"
    const val BAD_MANIFEST = "Could not read the update information"
    const val CHECK_FAILED = "Could not check for updates"

    const val FAIL_DOWNLOAD = "Could not download the update"
    const val FAIL_TOO_LARGE = "The update is larger than 64 MB"
    const val FAIL_SIZE = "The download was not the expected size"
    const val FAIL_HASH = "The download did not match its checksum"
    const val FAIL_SAVE = "Could not save the update"
    const val REJECTED = "Update rejected: it was not signed with NetProbe's key"
    const val NO_INSTALLER = "No installer is available on this phone"

    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 1_440L

    fun available(versionName: String) = "NetProbe $versionName is available"
    fun lastChecked(ageMs: Long): String {
        val minutes = ageMs / MS_PER_MINUTE
        return when {
            minutes < 1 -> "Last checked just now"
            minutes < MINUTES_PER_HOUR -> "Last checked ${plural(minutes, "minute")} ago"
            minutes < MINUTES_PER_DAY -> "Last checked ${plural(minutes / MINUTES_PER_HOUR, "hour")} ago"
            else -> "Last checked ${plural(minutes / MINUTES_PER_DAY, "day")} ago"
        }
    }
    private fun plural(n: Long, unit: String): String { return if (n == 1L) "1 $unit" else "$n ${unit}s" }
    fun percent(value: Int) = "$value%"
    fun firstLine(notes: String) = notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    fun failure(reason: InstallFailure) = when (reason) {
        InstallFailure.DOWNLOAD -> FAIL_DOWNLOAD
        InstallFailure.TOO_LARGE -> FAIL_TOO_LARGE
        InstallFailure.SIZE -> FAIL_SIZE
        InstallFailure.HASH -> FAIL_HASH
        InstallFailure.REJECTED -> REJECTED
        InstallFailure.SAVE -> FAIL_SAVE
    }
}
