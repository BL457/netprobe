package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import org.junit.Assert.*
import org.junit.Test

/** The Tools grid's pure rules: category groups and their order, sorting inside a group, badges, columns, the one-line description and About. */
class ToolGridTest {
    private fun m(key: String, name: String, c: ToolCategory, scope: NetworkScope = NetworkScope.TARGET, description: String = "d") =
        UiToolFakes.module(key, name, c, network = scope, description = description)

    @Test fun `groups follow the category order and leave empty categories out`() {
        val groups = ToolGrid.groups(listOf(m("user:x", "X", ToolCategory.MINE), m("a", "A", ToolCategory.IDENTITY), m("b", "B", ToolCategory.WEB),
            m("example:e", "E", ToolCategory.EXAMPLES)))
        assertEquals(listOf(ToolCategory.WEB, ToolCategory.IDENTITY, ToolCategory.EXAMPLES, ToolCategory.MINE), groups.map { it.category })
        assertTrue(ToolGrid.groups(emptyList()).isEmpty())
    }
    @Test fun `tiles sort by name ignoring case, then by key`() {
        val tiles = ToolGrid.groups(listOf(m("k3", "beta", ToolCategory.WEB), m("k1", "Alpha", ToolCategory.WEB), m("k2", "alpha", ToolCategory.WEB), m("k0", "Zed", ToolCategory.WEB)))
            .single().tiles
        assertEquals(listOf("k1", "k2", "k3", "k0"), tiles.map { it.key })
    }
    @Test fun `the built-in registry groups as web, network and identity`() {
        val groups = ToolGrid.groups(TestRegistry.builtIns())
        assertEquals(listOf(ToolCategory.WEB, ToolCategory.NETWORK, ToolCategory.IDENTITY), groups.map { it.category })
        assertEquals(listOf("Directory brute-force", "HTTP fingerprint", "TLS certificate"), groups[0].tiles.map { it.name })
        assertEquals(listOf("SMB session", "SNMP walk"), groups[1].tiles.map { it.name })
        assertEquals(listOf("Identify all services", "Identify service"), groups[2].tiles.map { it.name })
        assertTrue(groups.flatMap { it.tiles }.none { it.any || it.mine || it.script })
        assertEquals(ToolIcon.FOLDER, groups[0].tiles[0].icon)
    }
    @Test fun `the any badge follows the network scope and the mine badge follows the user key prefix`() {
        val tiles = ToolGrid.groups(listOf(m("user:scan", "Scan", ToolCategory.MINE, NetworkScope.ANY), m("user:lan", "Lan", ToolCategory.MINE, NetworkScope.LAN),
            m("example:dns", "Dns", ToolCategory.EXAMPLES, NetworkScope.ANY), m("http", "Http", ToolCategory.WEB))).flatMap { it.tiles }.associateBy { it.key }
        assertTrue(tiles.getValue("user:scan").any); assertTrue(tiles.getValue("user:scan").mine)
        assertFalse(tiles.getValue("user:lan").any); assertTrue(tiles.getValue("user:lan").mine)
        assertTrue(tiles.getValue("example:dns").any); assertFalse("an example is not a user tool", tiles.getValue("example:dns").mine)
        assertFalse(tiles.getValue("http").any); assertFalse(tiles.getValue("http").mine)
        assertTrue(ToolGrid.isMine(UiToolFakes.descriptor(ToolKeys.USER_PREFIX + "a"))); assertFalse(ToolGrid.isMine(UiToolFakes.descriptor("dir-bruteforce")))
    }
    @Test fun `a phone gets two columns and a wide window gets more`() {
        listOf(320, 360, 411, 480, 599).forEach { assertEquals("$it dp", 2, ToolGrid.columns(it)) }
        assertEquals(3, ToolGrid.columns(600)); assertEquals(3, ToolGrid.columns(840)); assertEquals(4, ToolGrid.columns(1200)); assertEquals(6, ToolGrid.columns(5000))
    }
    @Test fun `the tile line is the first non-blank line of the description with single spaces`() {
        assertEquals("Reads the page and headers", ToolGrid.oneLine("  Reads   the page\tand headers  \nSecond line"))
        assertEquals("Second", ToolGrid.oneLine("\n\nSecond"))
        assertEquals("", ToolGrid.oneLine("   "))
        assertEquals("Fetches the headers", ToolGrid.tile(m("a", "A", ToolCategory.WEB, description = "Fetches the headers\nmore")).line)
    }
    @Test fun `about lists version, author, needs, network and the input labels`() {
        val d = UiToolFakes.descriptor("user:t", "T", ToolCategory.MINE, TargetKind.PORT, listOf(InputSpec.Text("a", "Path"), InputSpec.Bool("b", "Deep")), NetworkScope.LAN)
            .copy(author = "BL457", version = "2.1")
        val about = ToolGrid.about(d)
        assertEquals(listOf("Version" to "2.1", "Author" to "BL457", "Needs" to "A device and a port", "Network" to "Contacts addresses on your network", "Inputs" to "Path, Deep"), about.rows)
        assertEquals("T", about.title)
        val bare = ToolGrid.about(UiToolFakes.descriptor("x", target = TargetKind.NONE))
        assertEquals(listOf("Version", "Needs", "Network"), bare.rows.map { it.first })
    }
    @Test fun `about says when a tool can send broadcast packets`() {
        val d = UiToolFakes.descriptor("user:t", "T", ToolCategory.MINE, TargetKind.HOST, network = NetworkScope.LAN)
        assertFalse(ToolGrid.about(d).rows.any { it.first == "Broadcast" })
        assertEquals("Can send broadcast packets on your network", ToolGrid.about(d.copy(broadcast = true)).rows.single { it.first == "Broadcast" }.second)
    }
    @Test fun `own inputs and device need`() {
        assertFalse(ToolGrid.hasOwnInputs(UiToolFakes.descriptor("a"))); assertTrue(ToolGrid.hasOwnInputs(UiToolFakes.descriptor("a", inputs = listOf(InputSpec.Bool("b", "B")))))
        assertTrue(ToolGrid.needsDevice(UiToolFakes.descriptor("a", target = TargetKind.HOST))); assertFalse(ToolGrid.needsDevice(UiToolFakes.descriptor("a", target = TargetKind.NONE)))
    }
}
