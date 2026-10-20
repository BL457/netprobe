package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.DeviceEdits
import com.northwalessolutions.netprobe.data.Detected
import com.northwalessolutions.netprobe.data.EditDraft
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val FIELD_RADIUS = 8.dp

/**
 * Edit device (spec 4.3). The body scrolls at large font scales; Save and Cancel are the dialog's own buttons, outside the scroll,
 * so they stay pinned. Each field shows "Detected: ..." and, while it holds an override, "Reset to detected". Nothing is saved
 * until Save, which stores DeviceEdits.of(draft, detected).
 */
@Composable
fun EditDeviceDialog(state: EditDraft, detected: Detected, onSave: (DeviceEdits) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf(state) }
    val edits = DeviceEdits.of(draft, detected)
    AlertDialog(
        onDismissRequest = onCancel, containerColor = NetProbeColors.surface,
        title = { Text(EditCopy.TITLE, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("editBody")) {
                EditTextField(EditCopy.NAME, draft.name, "editName") { draft = draft.copy(name = it) }
                FieldFooter(detected.name, overridden = edits.name != null, tag = "resetName") { draft = draft.copy(name = detected.name) }
                TypeField(draft.type) { draft = draft.copy(type = it) }
                FieldFooter(detected.type.takeIf { it != DeviceType.UNKNOWN }?.let(EditCopy::typeLabel), overridden = edits.type != null, tag = "resetType") {
                    draft = draft.copy(type = detected.type.takeIf { it != DeviceType.UNKNOWN })
                }
                EditTextField(EditCopy.VENDOR, draft.vendor, "editVendor") { draft = draft.copy(vendor = it) }
                FieldFooter(detected.vendor, overridden = edits.vendor != null, tag = "resetVendor") { draft = draft.copy(vendor = detected.vendor.orEmpty()) }
                EditTextField(EditCopy.MODEL, draft.model, "editModel") { draft = draft.copy(model = it) }
                FieldFooter(detected.model, overridden = edits.model != null, tag = "resetModel") { draft = draft.copy(model = detected.model.orEmpty()) }
            }
        },
        confirmButton = { TextButton({ onSave(edits) }, Modifier.testTag("editSave")) { Text(EditCopy.SAVE, color = NetProbeColors.signal) } },
        dismissButton = { TextButton(onCancel, Modifier.testTag("editCancel")) { Text(EditCopy.CANCEL, color = NetProbeColors.textSecondary) } },
    )
}

/** Single line; input beyond 80 characters is not accepted (spec 4.3). */
@Composable
private fun EditTextField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= DeviceEdits.MAX_LEN) onChange(it) }, Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) }, singleLine = true, colors = npFieldColors(), shape = RoundedCornerShape(FIELD_RADIUS))
}

/** Read-only field opening a menu of the nine real types; "Not set" is shown, never chosen. */
@Composable
private fun TypeField(type: DeviceType?, onPick: (DeviceType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(type?.let(EditCopy::typeLabel) ?: EditCopy.NOT_SET, {}, Modifier.fillMaxWidth(), readOnly = true,
            label = { Text(EditCopy.TYPE) }, singleLine = true, colors = npFieldColors(), shape = RoundedCornerShape(FIELD_RADIUS))
        // A read-only text field still takes focus on tap; this layer opens the picker instead.
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true }.testTag("editType"))
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            EditCopy.TYPES.forEach { t ->
                DropdownMenuItem(text = { Text(EditCopy.typeLabel(t)) }, onClick = { onPick(t); open = false }, modifier = Modifier.testTag("type:${t.name}"))
            }
        }
    }
}

@Composable
private fun FieldFooter(detected: String?, overridden: Boolean, tag: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(EditCopy.detected(detected), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
        if (overridden) Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onReset).testTag(tag), contentAlignment = Alignment.Center) {
            Text(EditCopy.RESET, style = NetProbeText.action, color = NetProbeColors.signal)
        }
    }
}
