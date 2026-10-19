package com.northwalessolutions.netprobe.actions

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidActionRunner(private val context: Context) : HandlerResolver {
    @Suppress("DEPRECATION")
    override fun hasHandler(uri: String): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), 0).isNotEmpty()

    /** Returns false when nothing could handle the action; the caller shows the fallback. */
    fun run(action: DeviceAction): Boolean = try {
        when (action) {
            is DeviceAction.OpenUri -> { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            is DeviceAction.OpenWith -> { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            is DeviceAction.Copy -> { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NetProbe", action.text)); true }
        }
    } catch (_: ActivityNotFoundException) { false }
}
