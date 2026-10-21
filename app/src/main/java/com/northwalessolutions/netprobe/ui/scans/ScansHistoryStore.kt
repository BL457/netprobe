package com.northwalessolutions.netprobe.ui.scans

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Clear history cut-off (3D spec 3.8): recent items finished at or before it are hidden. Nothing is deleted. The key is data, not a
 * setting, so "Reset to defaults" (which rewrites only the covered settings keys) never touches it.
 */
class ScansHistoryStore(private val prefs: SharedPreferences) {
    private val _clearedAt = MutableStateFlow(prefs.getLong(KEY, 0L))
    val clearedAt: StateFlow<Long> = _clearedAt

    fun set(at: Long) {
        prefs.edit().putLong(KEY, at).apply()
        _clearedAt.value = at
    }

    companion object { const val KEY = "scansClearedAt" }
}
