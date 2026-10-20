package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.*
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.settings.StepSlider
import com.northwalessolutions.netprobe.ui.settings.SwitchRow
import com.northwalessolutions.netprobe.ui.theme.*

private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val GROUP_GAP = 12.dp
private const val SHEET_MAX_FRACTION = 0.9f
private val METHODS = listOf(DirMethod.HEAD_THEN_GET, DirMethod.GET_ONLY)

private fun message(e: FieldError): String = when (e) {
    FieldError.EXT_CHARS -> ToolCopy.ERR_EXT_CHARS; FieldError.EXT_TOO_MANY -> ToolCopy.ERR_EXT_TOO_MANY
    FieldError.CODE_RANGE -> ToolCopy.ERR_CODE_RANGE; FieldError.CODE_EMPTY -> ToolCopy.ERR_CODE_EMPTY
}

/**
 * Directory brute-force options (spec 8.4). Three parts in a height-bounded Column: a fixed header, a scrolling body, and a fixed
 * footer holding the live path count and the Run pill, so Run is never inside the scroll. Run is disabled while a field has an error
 * or the host is held; dismissing starts nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirBruteSheet(state: DirBruteSheetState, onRun: (DirBruteOptions) -> Unit, onManageWordlists: () -> Unit, onDismiss: () -> Unit) {
    val init = state.initial
    var wordlistId by remember(init) { mutableStateOf(init.wordlistId) }
    var extText by remember(init) { mutableStateOf(init.extensions.joinToString(", ")) }
    var method by remember(init) { mutableStateOf(init.method) }
    var concurrency by remember(init) { mutableIntStateOf(init.concurrency) }
    var timeoutS by remember(init) { mutableIntStateOf(init.timeoutS) }
    var codesText by remember(init) { mutableStateOf(init.statusCodes.joinToString(", ")) }
    var baseline by remember(init) { mutableStateOf(init.baseline) }
    val ext = DirBruteParse.extensions(extText); val codes = DirBruteParse.statusCodes(codesText)
    val extError = (ext as? Parsed.Bad)?.let { message(it.error) }; val codesError = (codes as? Parsed.Bad)?.let { message(it.error) }
    val extensions = (ext as? Parsed.Ok)?.value.orEmpty()
    val entries = state.lists.firstOrNull { it.id == wordlistId }?.entries ?: 0
    val canRun = extError == null && codesError == null && state.busyReason == null
    val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = SHEET_SHAPE,
        contentWindowInsets = { WindowInsets.navigationBars.union(WindowInsets.ime) }) {   // IME joins the insets once, so Run stays above the keyboard
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            // 1. Header (fixed).
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                Text(ToolCopy.DIR_BRUTEFORCE, style = NetProbeText.rowName, color = NetProbeColors.text)
                Text(state.targetUrl, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 2. Body (scrolls).
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).testTag("dirBruteBody"),
                verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                Spacer(Modifier.height(4.dp))
                Group(ToolCopy.WORDLIST) {
                    Column(Modifier.selectableGroup()) {
                        state.lists.forEach { meta ->
                            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).selectable(meta.id == wordlistId, role = Role.RadioButton) { wordlistId = meta.id }
                                .testTag("wordlist:${meta.id}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = meta.id == wordlistId, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = NetProbeColors.signal, unselectedColor = NetProbeColors.textMuted))
                                Spacer(Modifier.width(8.dp))
                                Text(meta.name, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ToolCopy.entries(meta.entries), style = NetProbeText.data, color = NetProbeColors.textMuted)
                            }
                        }
                    }
                    Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onManageWordlists).testTag("manageWordlists"), contentAlignment = Alignment.CenterStart) {
                        Text(ToolCopy.MANAGE_WORDLISTS, style = NetProbeText.secondary.copy(fontWeight = FontWeight.W500), color = NetProbeColors.signal)
                    }
                }
                Group(ToolCopy.EXTENSIONS) {
                    OutlinedTextField(extText, { extText = it }, Modifier.fillMaxWidth().testTag("extensionsField"), singleLine = true, textStyle = NetProbeText.data,
                        placeholder = { Text(ToolCopy.EXTENSIONS_PLACEHOLDER) }, isError = extError != null,
                        supportingText = { Text(extError ?: ToolCopy.EXTENSIONS_SUPPORT, color = if (extError != null) NetProbeColors.error else NetProbeColors.textMuted) },
                        colors = npFieldColors(), shape = FIELD_SHAPE)
                }
                Group(ToolCopy.METHOD) {
                    PinnedSegmented(listOf(ToolCopy.HEAD_THEN_GET, ToolCopy.GET_ONLY), METHODS.indexOf(method), { method = METHODS[it] },
                        activeFill = NetProbeColors.background, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())
                }
                StepSlider(ToolCopy.CONCURRENCY, concurrency, DirBruteOptions.MIN_CONCURRENCY, DirBruteOptions.MAX_CONCURRENCY, 1, "") { concurrency = it }
                StepSlider(ToolCopy.TIMEOUT, timeoutS, DirBruteOptions.MIN_TIMEOUT_S, DirBruteOptions.MAX_TIMEOUT_S, 1, ToolCopy.SECONDS_UNIT) { timeoutS = it }
                Group(ToolCopy.REPORT_CODES) {
                    OutlinedTextField(codesText, { codesText = it }, Modifier.fillMaxWidth().testTag("codesField"), singleLine = true, textStyle = NetProbeText.data,
                        isError = codesError != null, supportingText = codesError?.let { e -> { Text(e, color = NetProbeColors.error) } },
                        colors = npFieldColors(), shape = FIELD_SHAPE)
                }
                Box(Modifier.testTag("filterSwitch")) {
                    SwitchRow(ToolCopy.FILTER, baseline, supporting = ToolCopy.FILTER_SUPPORT) { baseline = it }
                }
                Spacer(Modifier.height(4.dp))
            }
            // 3. Footer (fixed): never inside the scroll.
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            Column(Modifier.fillMaxWidth().padding(Dimens.gutter)) {
                state.busyReason?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.error, modifier = Modifier.padding(bottom = 6.dp)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolCopy.paths(entries * (1 + extensions.size)), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
                    ActionPill(ToolCopy.RUN, enabled = canRun, tag = "toolRunPill", onClick = {
                        val parsedCodes = (codes as? Parsed.Ok)?.value ?: return@ActionPill
                        onRun(DirBruteOptions(wordlistId, extensions, method, concurrency, timeoutS, parsedCodes, baseline).coerced())
                    })
                }
            }
        }
    }
}

@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = NetProbeText.caption, color = NetProbeColors.textMuted)
        content()
    }
}
