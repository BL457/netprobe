package com.northwalessolutions.netprobe.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Shared open-in-browser helper (Settings row and About dialog). Free of Compose. */
object OpenUrl {
    fun intent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True if a browser took it; false when nothing can handle the URL. */
    fun open(context: Context, url: String): Boolean {
        return try {
            context.startActivity(intent(url))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
