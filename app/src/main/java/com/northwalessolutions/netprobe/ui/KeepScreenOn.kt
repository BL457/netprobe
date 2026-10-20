package com.northwalessolutions.netprobe.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A default extended run (1.5 to 2 minutes) outlasts a typical screen timeout; the window stays on only while the run is active. */
@Composable
fun KeepScreenOn(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active, activity) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
