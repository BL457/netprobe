package com.northwalessolutions.netprobe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The one place NetProbe puts text on the Android clipboard (Director ruling R4); callers show "Copied" themselves. */
class Clipboard(private val context: Context) {
    fun copy(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }
    private companion object { const val LABEL = "NetProbe" }
}

@Composable
fun rememberClipboard(): Clipboard {
    val context = LocalContext.current
    return remember(context) { Clipboard(context) }
}
