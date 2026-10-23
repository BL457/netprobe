package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.runtime.Immutable
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import java.util.Locale

/** One tile of the Tools grid (spec section 5): [any] and [mine] are the two badges. */
@Immutable data class ToolTile(val key: String, val name: String, val line: String, val icon: ToolIcon, val any: Boolean, val mine: Boolean, val script: Boolean)
@Immutable data class TileGroup(val category: ToolCategory, val tiles: List<ToolTile>)
/** What the About dialog shows: a title, the description and label/value rows. */
@Immutable data class ToolAboutInfo(val key: String, val title: String, val description: String, val rows: List<Pair<String, String>>)

/** The Tools grid's pure rules: which tiles, in which groups and order, how many columns, and what About says. */
object ToolGrid {
    /** Category order on the grid; an empty category has no header. */
    val CATEGORY_ORDER: List<ToolCategory> = listOf(ToolCategory.WEB, ToolCategory.NETWORK, ToolCategory.IDENTITY, ToolCategory.EXAMPLES, ToolCategory.MINE)
    /** The width from which a tablet gets a third column; a phone has two (spec section 5). */
    const val PHONE_MAX_WIDTH_DP = 600
    private const val PHONE_COLUMNS = 2
    private const val TABLET_COLUMN_DP = 280
    private const val TABLET_MIN_COLUMNS = 3
    private const val MAX_COLUMNS = 6
    private val WHITESPACE = Regex("\\s+")

    /** An imported tool is MINE (ToolSource user); the bundled examples and the built-ins are not. */
    fun isMine(d: ToolDescriptor): Boolean = d.key.startsWith(ToolKeys.USER_PREFIX)

    /** The description cut to its first line with runs of white space made single, for the tile's one line. */
    fun oneLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().replace(WHITESPACE, " ")

    fun tile(m: ToolModule): ToolTile {
        val d = m.descriptor
        return ToolTile(d.key, d.name, oneLine(d.description), d.icon, d.network == NetworkScope.ANY, isMine(d), m is ScriptToolModule)
    }

    /** Tiles grouped by category in [CATEGORY_ORDER], each group sorted by name (case-insensitive) then key. Empty groups are left out. */
    fun groups(modules: List<ToolModule>): List<TileGroup> {
        val byCategory = modules.groupBy { it.descriptor.category }
        return CATEGORY_ORDER.mapNotNull { category ->
            val tiles = byCategory[category].orEmpty().map(::tile)
                .sortedWith(compareBy<ToolTile> { it.name.lowercase(Locale.ROOT) }.thenBy { it.key })
            if (tiles.isEmpty()) null else TileGroup(category, tiles)
        }
    }

    /** Two columns on a phone; a wider window gets one more column for each [TABLET_COLUMN_DP]. */
    fun columns(widthDp: Int): Int =
        if (widthDp < PHONE_MAX_WIDTH_DP) PHONE_COLUMNS else (widthDp / TABLET_COLUMN_DP).coerceIn(TABLET_MIN_COLUMNS, MAX_COLUMNS)

    fun about(d: ToolDescriptor): ToolAboutInfo {
        val rows = ArrayList<Pair<String, String>>()
        rows += ToolsUiCopy.ABOUT_VERSION to d.version
        d.author?.takeIf { it.isNotBlank() }?.let { rows += ToolsUiCopy.ABOUT_AUTHOR to it }
        rows += ToolsUiCopy.ABOUT_NEEDS to ToolsUiCopy.needs(d.target)
        rows += ToolsUiCopy.ABOUT_NETWORK to ToolsUiCopy.scope(d.network)
        if (d.inputs.isNotEmpty()) rows += ToolsUiCopy.ABOUT_INPUTS to d.inputs.joinToString(", ") { it.label }
        return ToolAboutInfo(d.key, d.name, d.description, rows)
    }

    /** Whether the tool's form has anything beyond the device and port: a tool with none can run straight from the device page. */
    fun hasOwnInputs(d: ToolDescriptor): Boolean = d.inputs.isNotEmpty()

    fun needsDevice(d: ToolDescriptor): Boolean = d.target != TargetKind.NONE
}
