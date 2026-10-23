package com.northwalessolutions.netprobe.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import com.northwalessolutions.netprobe.ui.OpenUrl
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** What the About dialog shows; SettingsScreen fills it from BuildConfig so tests can pass fakes. Moved from Home unchanged (3D spec 2). */
data class AboutInfo(val versionName: String, val versionCode: Int, val gitSha: String, val buildTime: String,
                     val androidRelease: String = Build.VERSION.RELEASE) {
    val lines: List<String> get() = listOf(HomeCopy.version(versionName, versionCode), HomeCopy.build(gitSha, buildTime), HomeCopy.android(androidRelease))
}

/** The About dialog (title, three lines, "Copy", "Close"), moved from HomeContent unchanged. */
@Composable
fun AboutDialog(about: AboutInfo, onNotice: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NetProbeColors.surface, modifier = Modifier.testTag("aboutDialog"),
        title = { Text(HomeCopy.APP_NAME, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = { Column { about.lines.forEach { Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) }
            Text(SettingsCopy.SUPPORT_ABOUT_LINE, style = NetProbeText.secondary, color = NetProbeColors.signal,
                modifier = Modifier.clickable { if (!OpenUrl.open(ctx, SettingsCopy.KOFI_URL)) onNotice(SettingsCopy.NO_BROWSER) }.testTag("aboutSupport"))
            Text(SettingsCopy.SIGNATURE, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("aboutSignature")) } },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("aboutClose")) { Text(HomeCopy.CLOSE, color = NetProbeColors.signal) } },
        dismissButton = {
            TextButton({ clipboard.setText(AnnotatedString(about.lines.joinToString("\n"))) }, Modifier.testTag("aboutCopy")) {
                Text(HomeCopy.COPY_VERSION, color = NetProbeColors.textSecondary)
            }
        },
    )
}
