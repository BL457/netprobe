package com.northwalessolutions.netprobe.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.update.UpdateController
import com.northwalessolutions.netprobe.update.UpdateCopy
import com.northwalessolutions.netprobe.update.UpdateInstallIntent
import com.northwalessolutions.netprobe.update.UpdateState

data class UpdateBannerActions(val onUpdate: () -> Unit = {}, val onLater: () -> Unit = {}, val onStop: () -> Unit = {},
                               val onInstall: () -> Unit = {}, val onRetry: () -> Unit = {})

private val NOTES_MAX_HEIGHT = 160.dp

/** Wires the banner to the controller and asks for the install permission when Update is tapped without it (spec 3). */
@Composable
fun UpdateBannerHost(controller: UpdateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var askPermission by rememberSaveable { mutableStateOf(false) }
    UpdateBanner(state, UpdateBannerActions(
        onUpdate = { if (UpdateInstallIntent.canInstall(ctx)) controller.download() else askPermission = true },
        onLater = controller::later, onStop = controller::stop, onInstall = controller::reinstall, onRetry = controller::download))
    if (askPermission) {
        AlertDialog(onDismissRequest = { askPermission = false }, containerColor = NetProbeColors.surface,
            title = { Text(UpdateCopy.PERMISSION_TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
            text = { Text(UpdateCopy.PERMISSION_BODY, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { askPermission = false; ctx.startActivity(UpdateInstallIntent.permissionIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.heightIn(min = Dimens.touch).testTag("updatePermissionOpen")) {
                    Text(UpdateCopy.PERMISSION_OPEN, style = NetProbeText.action, color = NetProbeColors.signal)
                }
            },
            dismissButton = {
                TextButton(onClick = { askPermission = false }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Text(UpdateCopy.PERMISSION_CANCEL, style = NetProbeText.action, color = NetProbeColors.textMuted)
                }
            })
    }
}

/**
 * The update banner (spec 3). PINNED: it sits above the host list, never inside it; its buttons (Update, Later, Stop, Install, Retry)
 * are never in a scroll container. Only the expanded release notes scroll, inside their own capped box.
 */
@Composable
fun UpdateBanner(state: UpdateState, actions: UpdateBannerActions) {
    if (state is UpdateState.Idle) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 6.dp).background(NetProbeColors.surface).padding(12.dp).testTag("updateBanner")) {
        when (state) {
            UpdateState.Idle -> Unit
            is UpdateState.Available -> Available(state, actions)
            is UpdateState.Downloading -> {
                Title(UpdateCopy.DOWNLOADING)
                ProgressRow(state.percent) { StopButton(actions.onStop) }
            }
            is UpdateState.Verifying -> {
                Title(UpdateCopy.VERIFYING)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = NetProbeColors.signal, trackColor = NetProbeColors.background)
            }
            is UpdateState.Ready -> {
                Title(UpdateCopy.READY)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Action(UpdateCopy.INSTALL, "updateInstall", actions.onInstall)
                }
            }
            is UpdateState.Failed -> {
                Text(state.text, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.testTag("updateFailed"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Action(UpdateCopy.LATER, "updateLater", actions.onLater, muted = true)
                    Action(UpdateCopy.RETRY, "updateRetry", actions.onRetry)
                }
            }
        }
    }
}

@Composable
private fun Available(state: UpdateState.Available, actions: UpdateBannerActions) {
    var expanded by rememberSaveable(state.manifest.versionCode) { mutableStateOf(false) }
    val notes = state.manifest.notes
    Title(UpdateCopy.available(state.manifest.versionName))
    if (notes.isNotBlank() && !expanded) {
        Text(UpdateCopy.firstLine(notes), style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, modifier = Modifier.testTag("updateNotesLine"))
    }
    if (expanded) {
        Box(Modifier.heightIn(max = NOTES_MAX_HEIGHT).verticalScroll(rememberScrollState()).testTag("updateNotes")) {
            Text(notes, style = NetProbeText.data, color = NetProbeColors.textSecondary)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (notes.isNotBlank()) Action(if (expanded) UpdateCopy.HIDE_NOTES else UpdateCopy.WHATS_NEW, "updateWhatsNew", { expanded = !expanded }, muted = true)
        Spacer(Modifier.weight(1f))
        Action(UpdateCopy.LATER, "updateLater", actions.onLater, muted = true)
        Action(UpdateCopy.UPDATE, "updateUpdate", actions.onUpdate)
    }
}

@Composable
private fun Title(text: String) = Text(text, style = NetProbeText.secondary, color = NetProbeColors.text)

@Composable
private fun ProgressRow(percent: Int?, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (percent != null) LinearProgressIndicator(progress = { percent / PERCENT_SCALE }, modifier = Modifier.weight(1f), color = NetProbeColors.signal, trackColor = NetProbeColors.background)
        else LinearProgressIndicator(Modifier.weight(1f), color = NetProbeColors.signal, trackColor = NetProbeColors.background)
        percent?.let { Text(UpdateCopy.percent(it), style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.padding(start = 8.dp).testTag("updatePercent")) }
        trailing()
    }
}

private const val PERCENT_SCALE = 100f

@Composable
private fun StopButton(onStop: () -> Unit) = Action(UpdateCopy.STOP, "updateStop", onStop)

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit, muted: Boolean = false) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch).testTag(tag)) {
        Text(label, style = NetProbeText.action, color = if (muted) NetProbeColors.textSecondary else NetProbeColors.signal)
    }
}
