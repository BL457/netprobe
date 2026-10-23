package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import com.northwalessolutions.netprobe.ui.manual.ManualTarget
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.settings.SwitchRow
import com.northwalessolutions.netprobe.ui.theme.*

/** What the generated form can do; the Compose tests record them. */
data class ToolFormActions(
    val onDevice: (Long?, String) -> Unit = { _, _ -> }, val onPort: (String) -> Unit = {}, val onHttps: (Boolean) -> Unit = {},
    val onInput: (String, String) -> Unit = { _, _ -> }, val onManageWordlists: () -> Unit = {},
)

private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val GROUP_GAP = 12.dp
private val PILL_VISUAL = 32.dp
private val PILL_PAD = 12.dp
private val PILL_GAP = 8.dp
private const val SHEET_MAX_FRACTION = 0.9f
private const val SELECTED_FILL_ALPHA = 0.18f
private const val MAX_LIST_ITEM_CHARS = 60

/**
 * The form generated from a descriptor (spec section 5, G14): the device picker and the port picker for the tool's target, then the
 * tool's own inputs in declared order, each by type. Stateless: the text lives in [form] (Compose state in the view model) and every
 * error shown comes from [evaluation].
 */
@Composable
fun ToolFormFields(module: ToolModule, form: ToolForm, evaluation: FormEvaluation, state: ToolPageState,
                   enabled: Boolean, actions: ToolFormActions) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("toolForm"), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        if (evaluation.showDevice) {
            DeviceField(form, state.devices, evaluation.visibleErrors[ToolInputs.IP], enabled) { picking = true }
            if (evaluation.fixedLine != null) Text(evaluation.fixedLine, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("fixedLine"))
            if (evaluation.showPort) PortField(form, state.ports, evaluation.visibleErrors[ToolInputs.PORT], enabled, actions.onPort)
            if (evaluation.tlsMode == TlsMode.SWITCH) Box(Modifier.testTag("tlsSwitch")) { SwitchRow(ToolModelCopy.TLS_LABEL, evaluation.https, enabled = enabled) { actions.onHttps(it) } }
        }
        for (spec in module.descriptor.inputs) {
            InputField(spec, form.values[spec.name].orEmpty(), evaluation.visibleErrors[spec.name], enabled, state.context.wordlists, actions)
        }
    }
    if (picking) DevicePickerSheet(state.devices, onPick = { id, ip -> picking = false; actions.onDevice(id, ip) }, onDismiss = { picking = false })
}

@Composable
private fun FieldLabel(text: String) { Text(text, style = NetProbeText.caption, color = NetProbeColors.textMuted) }

@Composable
private fun Supporting(error: String?, help: String?) {
    val text = error ?: help ?: return
    Text(text, style = NetProbeText.data, color = if (error != null) NetProbeColors.error else NetProbeColors.textMuted)
}

/** The device row: tapping opens the picker sheet. Shows the chosen device's name and address, or the placeholder. */
@Composable
private fun DeviceField(form: ToolForm, devices: List<DeviceChoice>, error: String?, enabled: Boolean, onOpen: () -> Unit) {
    val chosen = devices.firstOrNull { it.hostId == form.deviceHostId && form.deviceHostId != null } ?: devices.firstOrNull { it.ip == form.deviceIp }
    val text = when {
        form.deviceIp.isBlank() -> ToolsUiCopy.DEVICE_PLACEHOLDER
        chosen != null && chosen.name != chosen.ip -> "${chosen.name}  ${chosen.ip}"
        else -> form.deviceIp
    }
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(ToolModelCopy.IP_LABEL)
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(FIELD_SHAPE).border(1.dp, if (error != null) NetProbeColors.error else NetProbeColors.border, FIELD_SHAPE)
            .alpha(if (enabled) 1f else DISABLED_ALPHA).clickable(enabled = enabled, role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp).testTag("deviceField"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = NetProbeText.secondary, color = if (form.deviceIp.isBlank()) NetProbeColors.textMuted else NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Supporting(error, null)
    }
}

/** The chosen device's open ports as pills with their service names, and a typed port. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortField(form: ToolForm, ports: List<PortChoice>, error: String?, enabled: Boolean, onPort: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(ToolModelCopy.PORT_LABEL)
        if (ports.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
            ports.forEach { p -> SelectPill(p.label, form.portText == p.port.toString(), enabled, "portChoice:${p.port}") { onPort(p.port.toString()) } }
        } else if (form.deviceIp.isNotBlank()) Text(ToolsUiCopy.NO_OPEN_PORTS, style = NetProbeText.data, color = NetProbeColors.textMuted)
        OutlinedTextField(form.portText, { onPort(it) }, Modifier.fillMaxWidth().testTag("portField"), enabled = enabled, singleLine = true,
            label = { Text(ToolsUiCopy.PORT_TYPED) }, isError = error != null, supportingText = error?.let { e -> { Text(e) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = npFieldColors(), shape = FIELD_SHAPE)
    }
}

/** One input by its declared type (spec D7, G10). */
@Composable
private fun InputField(spec: InputSpec, raw: String, error: String?, enabled: Boolean, wordlists: List<WordlistMeta>, actions: ToolFormActions) {
    val tag = "input:${spec.name}"
    val onChange = { v: String -> actions.onInput(spec.name, v) }
    when (spec) {
        is InputSpec.Bool -> Box(Modifier.testTag(tag)) { SwitchRow(spec.label, raw == "true", supporting = error ?: spec.help, enabled = enabled) { onChange(it.toString()) } }
        is InputSpec.Tls -> Box(Modifier.testTag(tag)) { SwitchRow(spec.label, raw == "true", supporting = error ?: spec.help, enabled = enabled) { onChange(it.toString()) } }
        is InputSpec.Choice -> OptionGroup(spec.label, spec.help, error, tag) {
            spec.options.forEach { o -> SelectPill(o.label, raw == o.value, enabled, "$tag:${o.value}") { onChange(o.value) } }
        }
        is InputSpec.MultiChoice -> {
            val chosen = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            OptionGroup(spec.label, spec.help, error, tag) {
                spec.options.forEach { o ->
                    SelectPill(o.label, o.value in chosen, enabled, "$tag:${o.value}", toggle = true) {
                        val next = if (o.value in chosen) chosen - o.value else chosen + o.value
                        onChange(spec.options.map { it.value }.filter { it in next }.joinToString(","))
                    }
                }
            }
        }
        is InputSpec.Wordlist -> WordlistField(spec, raw, error, enabled, wordlists, tag, onChange, actions.onManageWordlists)
        is InputSpec.TextList -> ListField(spec, raw, error, enabled, tag, onChange)
        is InputSpec.Secret -> OutlinedTextField(raw, onChange, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true, label = { Text(spec.label) },
            visualTransformation = PasswordVisualTransformation(), isError = error != null, supportingText = (error ?: spec.help)?.let { t -> { Text(t) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = npFieldColors(), shape = FIELD_SHAPE)
        is InputSpec.Number -> OutlinedTextField(raw, { onChange(it.filter { ch -> ch.isDigit() || ch == '-' }) }, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true,
            label = { Text(spec.label) }, isError = error != null,
            supportingText = (error ?: spec.help ?: ToolsUiCopy.range(spec.min, spec.max))?.let { t -> { Text(t) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = npFieldColors(), shape = FIELD_SHAPE)
        is InputSpec.Port -> OutlinedTextField(raw, { onChange(it.filter(Char::isDigit).take(ManualTarget.MAX_PORT_DIGITS)) }, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true,
            label = { Text(spec.label) }, isError = error != null, supportingText = (error ?: spec.help)?.let { t -> { Text(t) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = npFieldColors(), shape = FIELD_SHAPE)
        is InputSpec.Ip -> OutlinedTextField(raw, onChange, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true, label = { Text(spec.label) },
            isError = error != null, supportingText = (error ?: spec.help)?.let { t -> { Text(t) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = npFieldColors(), shape = FIELD_SHAPE)
        is InputSpec.Text -> OutlinedTextField(raw, onChange, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = true, label = { Text(spec.label) },
            placeholder = spec.placeholder?.let { p -> { Text(p) } }, isError = error != null, supportingText = (error ?: spec.help)?.let { t -> { Text(t) } },
            colors = npFieldColors(), shape = FIELD_SHAPE)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroup(label: String, help: String?, error: String?, tag: String, pills: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        FieldLabel(label)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) { pills() }
        Supporting(error, help)
    }
}

/** A selectable pill, 32 dp visual in a 48 dp target. [toggle] reads as a checkbox for a multiple choice, else as a radio button. */
@Composable
private fun SelectPill(label: String, selected: Boolean, enabled: Boolean, tag: String, toggle: Boolean = false, onClick: () -> Unit) {
    val shape = CircleShape
    Box(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)
        .then(if (toggle) Modifier.toggleable(selected, enabled, Role.Checkbox) { onClick() } else Modifier.selectable(selected, enabled, Role.RadioButton, onClick))
        .testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = PILL_VISUAL).clip(shape).background(if (selected) NetProbeColors.signal.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent)
            .border(1.dp, if (selected) NetProbeColors.signal else NetProbeColors.border, shape).padding(horizontal = PILL_PAD), contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.data, color = if (selected) NetProbeColors.text else NetProbeColors.textSecondary)
        }
    }
}

/** The wordlists as a radio list with their entry counts, and a link to manage them (the old options sheet's chooser). */
@Composable
private fun WordlistField(spec: InputSpec.Wordlist, raw: String, error: String?, enabled: Boolean, lists: List<WordlistMeta>,
                          tag: String, onChange: (String) -> Unit, onManage: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        FieldLabel(spec.label)
        Column(Modifier.selectableGroup().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            lists.forEach { meta ->
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).selectable(meta.id == raw, enabled, Role.RadioButton) { onChange(meta.id) }.testTag("wordlist:${meta.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = meta.id == raw, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = NetProbeColors.signal, unselectedColor = NetProbeColors.textMuted))
                    Spacer(Modifier.width(8.dp))
                    Text(meta.name, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ToolCopy.entries(meta.entries), style = NetProbeText.data, color = NetProbeColors.textMuted)
                }
            }
        }
        Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onManage).testTag("manageWordlists"), contentAlignment = Alignment.CenterStart) {
            Text(ToolCopy.MANAGE_WORDLISTS, style = NetProbeText.secondary.copy(fontWeight = FontWeight.W500), color = NetProbeColors.signal)
        }
        Supporting(error, spec.help)
    }
}

/**
 * A list input (extensions): the words so far as removable pills, and a field that adds a word on Done, on the Add button, or when a comma or
 * space is typed. A word still in the field when Run is tapped counts too, so nothing the Director typed is dropped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListField(spec: InputSpec.TextList, raw: String, error: String?, enabled: Boolean, tag: String, onChange: (String) -> Unit) {
    var items by remember(spec.name) { mutableStateOf(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
    var pending by remember(spec.name) { mutableStateOf("") }
    fun publish(list: List<String>, word: String) { onChange((list + word.trim()).filter { it.isNotEmpty() }.joinToString(",")) }
    fun commit() {
        val word = pending.trim()
        if (word.isEmpty()) return
        items = items + word; pending = ""; publish(items, "")
    }
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        FieldLabel(spec.label)
        if (items.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
            items.forEach { item ->
                Box(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA).clickable(enabled = enabled, role = Role.Button) {
                    items = items - item; publish(items, pending)
                }.semantics { contentDescription = ToolsUiCopy.listRemove(item) }.testTag("$tag:item:$item"), contentAlignment = Alignment.Center) {
                    Box(Modifier.heightIn(min = PILL_VISUAL).clip(CircleShape).border(1.dp, NetProbeColors.border, CircleShape).padding(horizontal = PILL_PAD), contentAlignment = Alignment.Center) {
                        Text("$item  x", style = NetProbeText.data, color = NetProbeColors.textSecondary)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(pending, { v ->
                val clean = v.take(MAX_LIST_ITEM_CHARS)
                if (clean.endsWith(',') || clean.endsWith(' ')) { pending = clean.dropLast(1); commit() } else { pending = clean; publish(items, pending) }
            }, Modifier.weight(1f).testTag("$tag:field"), enabled = enabled, singleLine = true, placeholder = spec.placeholder?.let { p -> { Text(p) } },
                isError = error != null, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
                colors = npFieldColors(), shape = FIELD_SHAPE)
            TextButton(onClick = { commit() }, enabled = enabled && pending.isNotBlank(), modifier = Modifier.heightIn(min = Dimens.touch).testTag("$tag:add")) {
                Text(ToolsUiCopy.LIST_ADD, color = NetProbeColors.signal)
            }
        }
        Supporting(error, spec.help)
    }
}

/** The device picker: a search field over the discovered devices, and a "Use <address>" row when the search is an address no device has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(devices: List<DeviceChoice>, onPick: (Long?, String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(devices, query) { DevicePick.search(devices, query) }
    val typed = remember(devices, query) { DevicePick.typedAddress(devices, query) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NetProbeColors.surface,
        shape = SHEET_SHAPE, contentWindowInsets = { WindowInsets.navigationBars.union(WindowInsets.ime) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).testTag("deviceSearch"), singleLine = true,
                placeholder = { Text(ToolsUiCopy.DEVICE_SEARCH) }, colors = npFieldColors(), shape = FIELD_SHAPE)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("deviceList")) {
                if (typed != null) item(key = "typed") {
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onPick(null, typed) }.padding(horizontal = Dimens.gutter).testTag("deviceTyped"),
                        verticalAlignment = Alignment.CenterVertically) { Text(ToolsUiCopy.useAddress(typed), style = NetProbeText.secondary, color = NetProbeColors.signal) }
                }
                if (shown.isEmpty() && typed == null) item(key = "none") {
                    Text(ToolsUiCopy.NO_MATCHES, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(Dimens.gutter))
                }
                items(shown, key = { it.hostId }) { d ->
                    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onPick(d.hostId, d.ip) }
                        .padding(horizontal = Dimens.gutter, vertical = 6.dp).testTag("device:${d.hostId}")) {
                        Text(d.name, style = NetProbeText.secondary, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.detail.isNotEmpty()) Text(d.detail, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
