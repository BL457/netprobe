package com.northwalessolutions.netprobe.ui.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.engine.script.ImportPlan
import com.northwalessolutions.netprobe.engine.script.InputKinds
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*

private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val SOURCE_HEIGHT = 240.dp
private val ROW_LABEL_WIDTH = 80.dp
private const val SHEET_MAX_FRACTION = 0.9f
private const val PICKER_MIME = "*/*"

/** Pure helper for the "View source" expander: the whole script in pieces a LazyColumn can draw, so nothing is hidden from the reviewer. */
object SourceChunks {
    const val MAX_CHARS = 1_000

    /** Every line of [source], cut into pieces of at most [max] characters; joining the pieces with newlines per line gives the source back. */
    fun of(source: String, max: Int = MAX_CHARS): List<String> {
        val out = ArrayList<String>()
        for (line in source.split('\n')) {
            val clean = line.removeSuffix("\r")
            if (clean.length <= max) { out += clean; continue }
            var from = 0
            while (from < clean.length) { out += clean.substring(from, minOf(clean.length, from + max)); from += max }
        }
        return out
    }
}

/**
 * The import flow for whichever screen shows it: the first dialog (From file, or an address), the working dialog, the error list and the
 * confirmation sheet. Both entry points (Tools overflow, Settings) draw the same state from the container's controller.
 */
@Composable
fun ToolImportHost(controller: ToolImportController, onNotice: (String) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) controller.close()
        else controller.importFile(displayName(ctx, uri)) { ctx.contentResolver.openInputStream(uri) }
    }
    LaunchedEffect(notice) { notice?.let { onNotice(it); controller.consumeNotice() } }
    when (val s = state) {
        ImportState.Closed -> Unit
        ImportState.Choosing -> ImportChooseDialog(onFile = { launcher.launch(arrayOf(PICKER_MIME)) }, onUrl = controller::importUrl, onCancel = controller::close)
        is ImportState.Working -> ImportWorkingDialog(s.fromUrl, controller::close)
        is ImportState.Failed -> ImportFailedDialog(s.errors, onAgain = controller::again, onClose = controller::close)
        is ImportState.Review -> ImportReviewSheet(s, onAcknowledged = controller::setAcknowledged, onImport = controller::confirm, onCancel = controller::close)
    }
}

private fun displayName(ctx: Context, uri: Uri): String? {
    return try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
}

@Composable
fun ImportChooseDialog(onFile: () -> Unit, onUrl: (String) -> Unit, onCancel: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onCancel, containerColor = NetProbeColors.surface,
        title = { Text(ImportCopy.IMPORT_TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Column(Modifier.testTag("importChoose")) {
                TextButton(onClick = onFile, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touch).testTag("importFromFile")) {
                    Text(ImportCopy.FROM_FILE, style = NetProbeText.action, color = NetProbeColors.signal)
                }
                Spacer(Modifier.height(8.dp))
                Text(ImportCopy.FROM_URL, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("importUrl"), singleLine = true, label = { Text(ImportCopy.URL_LABEL) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onUrl(url) }), colors = npFieldColors(), shape = FIELD_SHAPE)
            }
        },
        confirmButton = {
            TextButton(onClick = { onUrl(url) }, enabled = url.isNotBlank(), modifier = Modifier.heightIn(min = Dimens.touch).testTag("importFetch")) {
                Text(ImportCopy.URL_FETCH, color = if (url.isNotBlank()) NetProbeColors.signal else NetProbeColors.textMuted)
            }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(ImportCopy.CANCEL, color = NetProbeColors.textSecondary) } })
}

@Composable
fun ImportWorkingDialog(fromUrl: Boolean, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, containerColor = NetProbeColors.surface,
        title = { Text(ImportCopy.IMPORT_TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Row(Modifier.testTag("importWorking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = NetProbeColors.signal, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (fromUrl) ImportCopy.WORKING_URL else ImportCopy.WORKING_FILE, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch).testTag("importWorkingCancel")) { Text(ImportCopy.CANCEL, color = NetProbeColors.signal) } })
}

/** Every problem at once, each its own line. */
@Composable
fun ImportFailedDialog(errors: List<String>, onAgain: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, containerColor = NetProbeColors.surface,
        title = { Text(ImportCopy.FAILED_TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("importErrors")) {
                Text(ImportCopy.errorsCount(errors.size), style = NetProbeText.data, color = NetProbeColors.textMuted)
                errors.forEachIndexed { i, e -> Text(e, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.padding(top = 6.dp).testTag("importError:$i")) }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Dimens.touch).testTag("importErrorsClose")) { Text(ImportCopy.CLOSE, color = NetProbeColors.signal) } },
        dismissButton = { TextButton(onClick = onAgain, modifier = Modifier.heightIn(min = Dimens.touch).testTag("importAgain")) { Text(ImportCopy.IMPORT_TITLE, color = NetProbeColors.textSecondary) } })
}

/**
 * The confirmation sheet (G9): name, version, the author labelled unverified, description, network scope with any called out, the inputs,
 * the sha256, a View source expander, the replace diff, and Import / Cancel pinned below the scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReviewSheet(state: ImportState.Review, onAcknowledged: (Boolean) -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    val plan = state.plan
    val d = plan.descriptor
    val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp
    val canImport = !state.saving && (!plan.needsWideningAck || state.acknowledged)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NetProbeColors.surface,
        shape = SHEET_SHAPE, contentWindowInsets = { WindowInsets.navigationBars }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("importReview")) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
                Text(ImportCopy.reviewTitle(d.name, d.version), style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.testTag("importTitle"))
                if (d.description.isNotEmpty()) Text(d.description, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                ReviewRow(ImportCopy.ROW_AUTHOR, ImportCopy.authorUnverified(d.author), "importAuthor")
                ReviewRow(ImportCopy.ROW_NEEDS, ToolsUiCopy.needs(d.target), "importNeeds")
                ReviewRow(ImportCopy.ROW_NETWORK, ToolsUiCopy.scope(d.network), "importNetwork")
                if (d.network != NetworkScope.TARGET) ScopeCallout(d.network)
                if (d.broadcast) BroadcastCallout()
                ReviewRow(ImportCopy.ROW_INPUTS, if (d.inputs.isEmpty()) ImportCopy.NO_INPUTS else d.inputs.joinToString("\n") { ImportCopy.inputLine(it.label, InputKinds.of(it), it.required) }, "importInputs")
                ReviewRow(ImportCopy.ROW_SHA, plan.sha256, "importSha", mono = true)
                plan.replace?.let { ReplaceDiff(plan, state.acknowledged, onAcknowledged) }
                SourceExpander(plan.source)
                Text(ImportCopy.NOTHING_RUNS, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = 8.dp))
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch).testTag("importCancel")) { Text(ImportCopy.CANCEL, style = NetProbeText.action, color = NetProbeColors.textSecondary) }
                TextButton(onClick = onImport, enabled = canImport, modifier = Modifier.heightIn(min = Dimens.touch).testTag("importConfirm")) {
                    Text(if (plan.replace != null) ImportCopy.REPLACE_BUTTON else ImportCopy.IMPORT_BUTTON, style = NetProbeText.action, color = if (canImport) NetProbeColors.signal else NetProbeColors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, tag: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.width(ROW_LABEL_WIDTH))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = NetProbeText.data, color = NetProbeColors.text, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.testTag(tag))
        }
    }
}

@Composable
private fun ScopeCallout(scope: NetworkScope) {
    val any = scope == NetworkScope.ANY
    Text(if (any) ImportCopy.CALLOUT_ANY else ImportCopy.CALLOUT_LAN, style = NetProbeText.secondary, color = if (any) NetProbeColors.mark else NetProbeColors.signal,
        modifier = Modifier.padding(vertical = 4.dp).testTag("importScopeCallout"))
}

/** Shown when the tool declared broadcast (spec section 11); also used by the catalog review. */
@Composable
internal fun BroadcastCallout() {
    Text(ToolsUiCopy.BROADCAST_CAN, style = NetProbeText.secondary, color = NetProbeColors.signal, modifier = Modifier.padding(vertical = 4.dp).testTag("importBroadcastCallout"))
}

@Composable
private fun ReplaceDiff(plan: ImportPlan, acknowledged: Boolean, onAcknowledged: (Boolean) -> Unit) {
    val r = plan.replace ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("importDiff")) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        Text(ImportCopy.replaces(r.oldName, r.oldVersion, r.newVersion), style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.padding(top = 8.dp))
        if (r.identical) Text(ImportCopy.SAME_SCRIPT, style = NetProbeText.data, color = NetProbeColors.textMuted)
        else Text(ImportCopy.shaChange(r.shaFrom, r.shaTo), style = NetProbeText.data, color = NetProbeColors.textSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("diffSha"))
        val from = r.scopeFrom
        if (from != null && from != r.scopeTo) Text(ImportCopy.scopeChange(ImportCopy.scopeWord(from), ImportCopy.scopeWord(r.scopeTo)), style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("diffScope"))
        else Text(ImportCopy.SCOPE_SAME, style = NetProbeText.data, color = NetProbeColors.textMuted)
        if (!r.inputsKnown) Text(ImportCopy.INPUTS_UNKNOWN, style = NetProbeText.data, color = NetProbeColors.textMuted)
        if (r.inputsAdded.isNotEmpty()) Text(ImportCopy.inputsAdded(r.inputsAdded), style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("diffAdded"))
        if (r.inputsRemoved.isNotEmpty()) Text(ImportCopy.inputsRemoved(r.inputsRemoved), style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("diffRemoved"))
        if (r.inputsChanged.isNotEmpty()) Text(ImportCopy.inputsChanged(r.inputsChanged), style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("diffChanged"))
        if (r.widened) {
            val to = ImportCopy.scopeWord(r.scopeTo)
            Text(ImportCopy.widened(ImportCopy.scopeWord(r.scopeFrom ?: NetworkScope.TARGET), to), style = NetProbeText.secondary, color = NetProbeColors.mark, modifier = Modifier.padding(top = 6.dp).testTag("diffWidened"))
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = onAcknowledged).testTag("importAck"),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = NetProbeColors.signal, checkmarkColor = NetProbeColors.background, uncheckedColor = NetProbeColors.border))
                Spacer(Modifier.width(8.dp))
                Text(ImportCopy.widenedYes(to), style = NetProbeText.secondary, color = NetProbeColors.text)
            }
        }
    }
}

/** View source: collapsed by default; open, the whole script in a fixed-height list (nothing is cut, long lines are wrapped in pieces). */
@Composable
private fun SourceExpander(source: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chunks = remember(source) { SourceChunks.of(source) }
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { open = !open }.testTag("importViewSource"), verticalAlignment = Alignment.CenterVertically) {
        Text(if (open) ImportCopy.HIDE_SOURCE else ImportCopy.VIEW_SOURCE, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.weight(1f))
        Text(ImportCopy.sourceSize(source.length, chunks.size), style = NetProbeText.caption, color = NetProbeColors.textMuted)
    }
    if (open) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().height(SOURCE_HEIGHT).background(NetProbeColors.background).padding(8.dp).testTag("importSource")) {
                items(chunks.size) { i -> Text(chunks[i].ifEmpty { " " }, style = NetProbeText.data, color = NetProbeColors.text, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
