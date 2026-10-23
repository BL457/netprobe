package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The card shown after an update: the installed [versionName] and the notes the update carried. */
data class WhatsNewCard(val versionName: String, val notes: String)

/** The "What's new" decision, kept free of Android types. */
object WhatsNewRules {
    /** The notes to show for the installed version, or null for no card. Pending notes only count when they were saved for this very version. */
    fun notesToShow(installedVersionCode: Int, lastSeenVersionCode: Int, pendingVersionCode: Int, pendingNotes: String): String? {
        if (installedVersionCode == lastSeenVersionCode) return null
        if (pendingVersionCode != installedVersionCode) return null
        return pendingNotes.trim().ifEmpty { null }
    }
}

/**
 * Decides once, at construction, whether the card shows. A version change with no matching notes (first install, sideload) is recorded
 * silently. "Got it" records the version and clears the saved notes.
 */
class WhatsNewState(private val prefs: UpdatePrefs, private val installedVersionCode: Int, private val installedVersionName: String) {
    private val _card = MutableStateFlow<WhatsNewCard?>(null)
    val card: StateFlow<WhatsNewCard?> = _card

    init {
        val notes = WhatsNewRules.notesToShow(installedVersionCode, prefs.lastSeenVersionCode, prefs.pendingVersionCode, prefs.pendingNotes)
        if (notes != null) _card.value = WhatsNewCard(installedVersionName, notes)
        else if (prefs.lastSeenVersionCode != installedVersionCode) prefs.lastSeenVersionCode = installedVersionCode
    }

    fun dismiss() {
        prefs.lastSeenVersionCode = installedVersionCode
        prefs.pendingVersionCode = 0
        prefs.pendingNotes = ""
        _card.value = null
    }
}
