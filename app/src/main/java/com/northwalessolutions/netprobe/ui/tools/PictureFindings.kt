package com.northwalessolutions.netprobe.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.northwalessolutions.netprobe.engine.script.SnapshotStore
import com.northwalessolutions.netprobe.ui.OpenUrl
import com.northwalessolutions.netprobe.ui.settings.SettingsCopy
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The folder of page pictures, provided once by MainActivity (tools platform spec section 12). Null in previews and tests that draw no pictures. */
val LocalSnapshotStore = staticCompositionLocalOf<SnapshotStore?> { null }

private val THUMB_WIDTH = 96.dp
private val THUMB_HEIGHT = 72.dp
private val THUMB_RADIUS = 4.dp
private const val THUMB_DECODE_WIDTH = 192
private const val FULL_DECODE_WIDTH = 1_280
private const val CAPTION_LINES = 2

/** Decodes a stored JPEG no wider than needed; null when the file is missing or unreadable. Runs off the main thread. */
object PictureDecoder {
    fun decode(file: File?, maxWidth: Int): ImageBitmap? {
        if (file == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

@Composable
private fun rememberPicture(path: String, maxWidth: Int): State<ImageBitmap?> {
    val store = LocalSnapshotStore.current
    return produceState<ImageBitmap?>(null, path, store) { value = withContext(Dispatchers.IO) { PictureDecoder.decode(store?.fileFor(path), maxWidth) } }
}

/**
 * A finding with a page picture (spec section 12): the thumbnail, the value as its caption with the detail under it, the Open button for the
 * page's url and Copy. Tapping the thumbnail or the caption opens the full-size viewer.
 */
@Composable
fun PictureFindingRow(row: FindingRow, path: String, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit) {
    var viewing by rememberSaveable(row.id) { mutableStateOf(false) }
    val thumb by rememberPicture(path, THUMB_DECODE_WIDTH)
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 10.dp).testTag("finding:${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(THUMB_WIDTH, THUMB_HEIGHT).clip(RoundedCornerShape(THUMB_RADIUS)).background(NetProbeColors.background)
            .clickable(role = Role.Button, onClickLabel = ToolCopy.viewPictureDescription(row.value)) { viewing = true }.testTag("thumb:${row.id}")) {
            thumb?.let { Image(it, ToolCopy.pictureDescription(row.value), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable(role = Role.Button, onClickLabel = ToolCopy.viewPictureDescription(row.value)) { viewing = true }) {
            row.label?.let { Text(it, style = NetProbeText.caption, color = NetProbeColors.signal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("findingLabel:${row.id}")) }
            Text(row.value, style = NetProbeText.data, color = NetProbeColors.text, maxLines = CAPTION_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("caption:${row.id}"))
            row.detail?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        row.url?.let { url ->
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onOpen(row) }
                .semantics { contentDescription = ToolCopy.openDescription(url) }.testTag("open:${row.id}"), contentAlignment = Alignment.Center) {
                Text(ToolCopy.OPEN_WITH, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onCopy(row) }
            .semantics { contentDescription = ToolCopy.copyDescription(row.value) }.testTag("copy:${row.id}"), contentAlignment = Alignment.Center) {
            Text(ToolCopy.COPY, style = NetProbeText.action.copy(fontWeight = FontWeight.W400), color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
    if (viewing) PictureViewer(row, path, onCopy) { viewing = false }
}

/**
 * The full-size viewer: the picture fitted to the screen, with its caption, and a pinned bar of Copy URL, Open in browser and Close that
 * never sits inside anything that scrolls. Copy URL and Open in browser appear only when the finding has a page address.
 */
@Composable
private fun PictureViewer(row: FindingRow, path: String, onCopy: (FindingRow) -> Unit, onClose: () -> Unit) {
    val picture by rememberPicture(path, FULL_DECODE_WIDTH)
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("pictureViewer"), color = NetProbeColors.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Text(row.value, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = CAPTION_LINES, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 12.dp).testTag("viewerCaption"))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shown = picture
                    if (shown != null) Image(shown, ToolCopy.pictureDescription(row.value), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(ToolCopy.PICTURE_MISSING, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.testTag("pictureMissing"))
                }
                notice?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter).testTag("viewerNotice")) }
                HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                    row.url?.let { url ->
                        TextButton(onClick = { onCopy(row); notice = ToolCopy.COPIED }, modifier = Modifier.heightIn(min = Dimens.touch).testTag("viewerCopyUrl")) {
                            Text(ToolCopy.COPY_URL, style = NetProbeText.action, color = NetProbeColors.signal)
                        }
                        TextButton(onClick = { notice = if (OpenUrl.open(context, url)) null else SettingsCopy.NO_BROWSER }, modifier = Modifier.heightIn(min = Dimens.touch).testTag("viewerOpen")) {
                            Text(ToolCopy.OPEN_IN_BROWSER, style = NetProbeText.action, color = NetProbeColors.signal)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Dimens.touch).testTag("viewerClose")) {
                        Text(ToolCopy.CLOSE_PICTURE, style = NetProbeText.action, color = NetProbeColors.textMuted)
                    }
                }
            }
        }
    }
}
