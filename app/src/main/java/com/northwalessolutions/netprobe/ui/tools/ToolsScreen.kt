package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.ui.manual.ToolsCopy
import com.northwalessolutions.netprobe.ui.rememberClipboard
import com.northwalessolutions.netprobe.ui.rootInsets
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.launch

/** Every action the Tools grid fires; the Compose tests record them. */
data class ToolsGridActions(
    val onOpenTool: (String) -> Unit = {}, val onWordlists: () -> Unit = {}, val onAbout: (String) -> Unit = {}, val onCopySource: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {}, val onImport: () -> Unit = {}, val onCatalog: () -> Unit = {},
)

private val TILE_SHAPE = RoundedCornerShape(8.dp)
private val TILE_MIN_HEIGHT = 112.dp
private val TILE_PAD = 12.dp
private val GRID_GAP = 12.dp
private val ICON_SIZE = 28.dp
private val BADGE_GAP = 4.dp
private val TITLE_ROW_MIN = 56.dp
private val HEADER_TOP = 8.dp
private val ABOUT_LABEL_WIDTH = 72.dp

@Composable
fun ToolsScreen(container: AppContainer, onOpenTool: (String) -> Unit, onOpenWordlists: () -> Unit) {
    val modules by container.toolRegistry.all.collectAsStateWithLifecycle()
    val groups = remember(modules) { ToolGrid.groups(modules) }
    val clipboard = rememberClipboard()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var about by remember { mutableStateOf<ToolAboutInfo?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    fun copySource(key: String) {
        val module = modules.firstOrNull { it.descriptor.key == key } as? ScriptToolModule ?: return
        clipboard.copy(module.source)
        scope.launch { snackbar.showSnackbar(ToolsUiCopy.SOURCE_COPIED) }
    }
    ToolsGridContent(groups, ToolsGridActions(
        onOpenTool = onOpenTool, onWordlists = onOpenWordlists,
        onAbout = { key -> about = modules.firstOrNull { it.descriptor.key == key }?.let { ToolGrid.about(it.descriptor) } },
        onCopySource = ::copySource, onRemove = { key -> removing = key }, onImport = container.toolImport::open, onCatalog = container.catalog::open), snackbar = snackbar)
    ToolImportHost(container.toolImport, onNotice = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
    CatalogHost(container.catalog, onNotice = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
    removing?.let { key ->
        val name = modules.firstOrNull { it.descriptor.key == key }?.descriptor?.name
        if (name == null) removing = null
        else RemoveToolDialog(name, onConfirm = { removing = null; container.removeTool(key) { msg -> scope.launch { snackbar.showSnackbar(msg) } } }, onDismiss = { removing = null })
    }
    about?.let { info ->
        ToolAboutDialog(info, canCopy = modules.any { it.descriptor.key == info.key && it is ScriptToolModule },
            onCopy = { copySource(info.key) }, onDismiss = { about = null })
    }
}

/** The Tools root (spec section 5): title row with an overflow, then the grid of tiles under category headers. Stateless. */
@Composable
fun ToolsGridContent(groups: List<TileGroup>, actions: ToolsGridActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val columns = ToolGrid.columns(LocalConfiguration.current.screenWidthDp)
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(), snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) { ToolsTitleRow(actions.onImport, actions.onCatalog, actions.onWordlists) } }) { pad ->
        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.padding(pad).fillMaxSize().testTag("toolsGrid"),
            contentPadding = PaddingValues(horizontal = Dimens.gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP), verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            groups.forEach { group ->
                item(key = "header:${group.category.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(ToolsUiCopy.category(group.category), style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                        modifier = Modifier.padding(top = HEADER_TOP).semantics { heading() }.testTag("toolsHeader:${group.category.name}"))
                }
                items(group.tiles, key = { it.key }) { tile -> ToolTileCard(tile, actions) }
            }
        }
    }
}

@Composable
private fun ToolsTitleRow(onImport: () -> Unit, onCatalog: () -> Unit, onWordlists: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
        Text(ToolsCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.size(Dimens.touch).testTag("toolsMore").semantics { contentDescription = ToolsUiCopy.MORE }) {
                Icon(Icons.Default.MoreVert, null, tint = NetProbeColors.textSecondary)
            }
            DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
                DropdownMenuItem(text = { Text(ToolsUiCopy.IMPORT_TOOL) }, onClick = { open = false; onImport() }, modifier = Modifier.testTag("toolsImport"))
                DropdownMenuItem(text = { Text(CatalogCopy.ACTION) }, onClick = { open = false; onCatalog() }, modifier = Modifier.testTag("toolsCatalog"))
                DropdownMenuItem(text = { Text(ToolsCopy.WORDLISTS) }, onClick = { open = false; onWordlists() }, modifier = Modifier.testTag("toolsWordlists"))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolTileCard(tile: ToolTile, actions: ToolsGridActions) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = TILE_MIN_HEIGHT).clip(TILE_SHAPE).background(NetProbeColors.surface)
            .border(1.dp, NetProbeColors.borderSubtle, TILE_SHAPE)
            .combinedClickable(role = Role.Button, onLongClickLabel = ToolsUiCopy.menuDescription(tile.name), onLongClick = { menu = true }, onClick = { actions.onOpenTool(tile.key) })
            .semantics {
                contentDescription = ToolsUiCopy.tileDescription(tile.name, tile.line, tile.any, tile.mine)
                customActions = buildList {
                    add(CustomAccessibilityAction(ToolsUiCopy.ABOUT) { actions.onAbout(tile.key); true })
                    if (tile.script) add(CustomAccessibilityAction(ToolsUiCopy.COPY_SOURCE) { actions.onCopySource(tile.key); true })
                    if (tile.mine) add(CustomAccessibilityAction(ToolsUiCopy.REMOVE) { actions.onRemove(tile.key); true })
                }
            }
            .testTag("tile:${tile.key}").padding(TILE_PAD)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(ToolIcons.vector(tile.icon), null, tint = NetProbeColors.signal, modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.weight(1f))
                if (tile.any) Badge(ToolsUiCopy.BADGE_ANY, NetProbeColors.mark, "badgeAny:${tile.key}")
                if (tile.any && tile.mine) Spacer(Modifier.width(BADGE_GAP))
                if (tile.mine) Badge(ToolsUiCopy.BADGE_MINE, NetProbeColors.signal, "badgeMine:${tile.key}")
            }
            Spacer(Modifier.height(8.dp))
            Text(tile.name, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tile.line.isNotEmpty()) Text(tile.line, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menu, { menu = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(ToolsUiCopy.ABOUT) }, onClick = { menu = false; actions.onAbout(tile.key) }, modifier = Modifier.testTag("tileAbout:${tile.key}"))
            if (tile.script) DropdownMenuItem(text = { Text(ToolsUiCopy.COPY_SOURCE) }, onClick = { menu = false; actions.onCopySource(tile.key) }, modifier = Modifier.testTag("tileCopySource:${tile.key}"))
            // Remove: an imported tool only; it asks first.
            if (tile.mine) DropdownMenuItem(text = { Text(ToolsUiCopy.REMOVE) }, onClick = { menu = false; actions.onRemove(tile.key) }, modifier = Modifier.testTag("tileRemove:${tile.key}"))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, tag: String) {
    Box(Modifier.clip(CircleShape).border(1.dp, color, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp).testTag(tag)) {
        Text(text, style = NetProbeText.caption, color = color, maxLines = 1)
    }
}

@Composable
fun ToolAboutDialog(info: ToolAboutInfo, canCopy: Boolean, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = NetProbeColors.surface,
        title = { Text(info.title, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Column(Modifier.testTag("toolAbout")) {
                if (info.description.isNotEmpty()) Text(info.description, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                Spacer(Modifier.height(8.dp))
                info.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.width(ABOUT_LABEL_WIDTH))
                        Text(value, style = NetProbeText.data, color = NetProbeColors.text, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch)) { Text(ToolsUiCopy.CLOSE, color = NetProbeColors.signal) } },
        dismissButton = if (canCopy) ({
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = Dimens.touch).testTag("aboutCopySource")) { Text(ToolsUiCopy.COPY_SOURCE, color = NetProbeColors.signal) }
        }) else null)
}
