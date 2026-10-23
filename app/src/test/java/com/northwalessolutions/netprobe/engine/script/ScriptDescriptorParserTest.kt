package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.ChoiceOption
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptDescriptorParserTest {
    private val base: Map<String, Any?> = mapOf("id" to "my-tool", "name" to "My tool", "target" to "port")

    private fun parse(tool: Map<String, Any?>, hasRun: Boolean = true, category: ToolCategory? = null) =
        ScriptDescriptorParser.parse(tool, hasRun, "user:", category, ToolSource.BuiltIn)

    private fun valid(tool: Map<String, Any?>) = (parse(tool) as? ParsedTool.Valid ?: error("invalid: ${(parse(tool) as ParsedTool.Invalid).errors}")).descriptor
    private fun errors(tool: Map<String, Any?>, hasRun: Boolean = true) = (parse(tool, hasRun) as ParsedTool.Invalid).errors
    private fun withInput(vararg kv: Pair<String, Any?>) = base + ("inputs" to listOf(mapOf("name" to "x", "label" to "X") + kv))

    @Test fun `the minimum app version is kept, optional, and checked`() {
        assertEquals(null, valid(base).minVersionCode); assertEquals(null, valid(base).minVersionName)
        val d = valid(base + mapOf("minVersionCode" to 12.0, "minVersionName" to "0.4.3"))
        assertEquals(12, d.minVersionCode); assertEquals("0.4.3", d.minVersionName)
        for (bad in listOf(0.0, -1.0, 1.5, "12", true)) assertEquals(bad.toString(), listOf(ScriptCopy.BAD_MIN_VERSION_CODE), errors(base + ("minVersionCode" to bad)))
        assertEquals(listOf(ScriptCopy.BAD_MIN_VERSION_NAME), errors(base + ("minVersionName" to "no way!")))
    }

    @Test fun `a minimal tool gets the defaults`() {
        val d = valid(base)
        assertEquals("user:my-tool", d.key); assertEquals("My tool", d.name); assertEquals("", d.description); assertEquals(ToolIcon.TERMINAL, d.icon)
        assertEquals(ToolCategory.MINE, d.category); assertEquals(TargetKind.PORT, d.target); assertEquals(NetworkScope.TARGET, d.network); assertEquals("1", d.version)
        assertEquals(null, d.author); assertTrue(d.inputs.isEmpty())
    }

    @Test fun `every descriptor field is read`() {
        val d = valid(base + mapOf("description" to "Does a thing", "icon" to "lock", "category" to "web", "version" to "2.1-beta", "author" to "Me", "target" to "none", "network" to "any"))
        assertEquals(ToolIcon.LOCK, d.icon); assertEquals(ToolCategory.WEB, d.category); assertEquals("2.1-beta", d.version); assertEquals("Me", d.author)
        assertEquals(TargetKind.NONE, d.target); assertEquals(NetworkScope.ANY, d.network); assertEquals("Does a thing", d.description)
        assertEquals(NetworkScope.LAN, valid(base + ("network" to "lan")).network)
        assertEquals(TargetKind.HOST, valid(base + ("target" to "host")).target)
        for ((word, cat) in listOf("web" to ToolCategory.WEB, "network" to ToolCategory.NETWORK, "identity" to ToolCategory.IDENTITY, "other" to ToolCategory.MINE))
            assertEquals(cat, valid(base + ("category" to word)).category)
        assertEquals(ToolIcon.TERMINAL, valid(base + ("icon" to "no-such-icon")).icon)
    }

    @Test fun `the examples category replaces the script's own`() {
        assertEquals(ToolCategory.EXAMPLES, (parse(base + ("category" to "web"), category = ToolCategory.EXAMPLES) as ParsedTool.Valid).descriptor.category)
    }

    @Test fun `top level problems are all reported in one go`() {
        val e = errors(mapOf("id" to "X", "name" to 5, "version" to "has space", "target" to "everywhere", "network" to "world", "category" to "games", "description" to "d".repeat(201)), hasRun = false)
        assertEquals(setOf(
            "The id must be 3 to 40 characters from a to z, 0 to 9 and -", "The name must be text", "The version must be 1 to 20 letters, digits, dots, dashes or underscores",
            "The target must be port, host or none", "The network must be target, lan or any", "The category must be web, network, identity or other",
            "The description can be at most 200 characters", "The tool needs a run function",
        ), e.toSet())
        assertEquals(setOf("The tool object has no id", "The tool object has no name", "The tool object has no target").sorted(), errors(emptyMap()).sorted())
    }

    @Test fun `missing required fields are named`() {
        val e = errors(mapOf("name" to "Only a name"))
        assertTrue(e.toString(), "The tool object has no id" in e && "The tool object has no target" in e)
        assertTrue("The tool object has no name" in errors(mapOf("id" to "abc", "target" to "none")))
    }

    @Test fun `ids follow the slug rule`() {
        for (good in listOf("abc", "a-b", "x".repeat(40), "123", "a1-b2-c3")) assertEquals(good, "user:$good", valid(base + ("id" to good)).key)
        for (bad in listOf("ab", "x".repeat(41), "Upper", "has space", "under_score", "dot.dot", "-", "ééé", "a/b", "")) assertTrue(bad, errors(base + ("id" to bad)).isNotEmpty())
    }

    @Test fun `text lengths are limited`() {
        assertTrue(errors(base + ("name" to "n".repeat(41))).contains("The name can be at most 40 characters"))
        assertTrue(errors(base + ("author" to "n".repeat(41))).contains("The author can be at most 40 characters"))
        assertEquals(40, valid(base + ("name" to "n".repeat(40))).name.length)
    }

    @Test fun `each input type maps to its spec`() {
        val d = valid(base + ("inputs" to listOf(
            mapOf("name" to "t", "type" to "text", "label" to "T", "default" to "d", "required" to false, "placeholder" to "ph", "help" to "h"),
            mapOf("name" to "n", "type" to "number", "label" to "N", "default" to 5.0, "min" to 1.0, "max" to 10.0),
            mapOf("name" to "b", "type" to "bool", "label" to "B", "default" to true),
            mapOf("name" to "c", "type" to "choice", "label" to "C", "options" to listOf("a", mapOf("value" to "b", "label" to "Bee")), "default" to "b"),
            mapOf("name" to "m", "type" to "multichoice", "label" to "M", "options" to listOf("a", "b", "c"), "default" to listOf("a", "c")),
            mapOf("name" to "l", "type" to "list", "label" to "L", "default" to "x,y", "maxItems" to 3.0),
            mapOf("name" to "w", "type" to "wordlist", "label" to "W"),
            mapOf("name" to "s", "type" to "secret", "label" to "S"),
            mapOf("name" to "i", "type" to "ip", "label" to "I", "default" to "10.0.0.1"),
            mapOf("name" to "p", "type" to "port", "label" to "P", "default" to 8080.0),
            mapOf("name" to "u", "type" to "tls", "label" to "U", "default" to true),
        )))
        assertEquals(listOf(
            InputSpec.Text("t", "T", "h", false, "d", "ph"), InputSpec.Number("n", "N", null, true, 5, 1, 10), InputSpec.Bool("b", "B", null, true, true),
            InputSpec.Choice("c", "C", listOf(ChoiceOption("a", "a"), ChoiceOption("b", "Bee")), null, true, "b"),
            InputSpec.MultiChoice("m", "M", listOf(ChoiceOption("a", "a"), ChoiceOption("b", "b"), ChoiceOption("c", "c")), null, true, listOf("a", "c")),
            InputSpec.TextList("l", "L", null, true, "x,y", null, 3), InputSpec.Wordlist("w", "W"), InputSpec.Secret("s", "S"), InputSpec.Ip("i", "I", null, true, "10.0.0.1"),
            InputSpec.Port("p", "P", null, true, 8080), InputSpec.Tls("u", "U", null, true, true),
        ), d.inputs)
    }

    @Test fun `input problems are reported with the input named`() {
        assertEquals(listOf("Input x has an unknown type"), errors(withInput("type" to "color")))
        assertEquals(listOf("Input x has an unknown type"), errors(withInput()))
        assertEquals(listOf("Input 1 needs a name that starts with a letter and uses letters, digits and _"), errors(base + ("inputs" to listOf(mapOf("name" to "1x", "type" to "text", "label" to "L")))))
        assertEquals(listOf("Input x needs a label"), errors(base + ("inputs" to listOf(mapOf("name" to "x", "type" to "text")))))
        assertEquals(listOf("Input x: the label can be at most 40 characters"), errors(withInput("type" to "text", "label" to "l".repeat(41))))
        assertEquals(listOf("Input x: the help can be at most 200 characters"), errors(withInput("type" to "text", "help" to "h".repeat(201))))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "text", "default" to 5.0)))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "number", "min" to 5.0, "default" to 1.0)))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "port", "default" to 70000.0)))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "ip", "default" to "not-an-ip")))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "bool", "default" to "yes")))
        assertEquals(listOf("Input x has a minimum above its maximum"), errors(withInput("type" to "number", "min" to 9.0, "max" to 2.0)))
        assertEquals(listOf("Input x: the min has the wrong kind of value"), errors(withInput("type" to "number", "min" to 1.5)))
        assertEquals(listOf("Input x: the required has the wrong kind of value"), errors(withInput("type" to "text", "required" to "no")))
        assertEquals(listOf("The inputs must be a list"), errors(base + ("inputs" to "text")))
        assertEquals(listOf("Input 1 must be an object"), errors(base + ("inputs" to listOf("x"))))
        assertEquals(listOf("A tool can have at most 24 inputs"), errors(base + ("inputs" to List(25) { mapOf("name" to "a$it", "type" to "bool", "label" to "A") })))
    }

    @Test fun `choice options are checked`() {
        val shown = "Input x needs 1 to 12 different options, as text or as value and label"
        assertEquals(listOf(shown), errors(withInput("type" to "choice")))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to emptyList<String>())))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to List(13) { "o$it" })))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to listOf("a", "a"))))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to listOf("a,b"))))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to listOf(5.0))))
        assertEquals(listOf(shown), errors(withInput("type" to "choice", "options" to listOf(mapOf("label" to "no value")))))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "choice", "options" to listOf("a"), "default" to "z")))
        assertEquals(listOf("The default of input x does not fit its type"), errors(withInput("type" to "multichoice", "options" to listOf("a"), "default" to listOf("z"))))
        assertEquals(12, (valid(withInput("type" to "choice", "options" to List(12) { "o$it" })).inputs.single() as InputSpec.Choice).options.size)
    }

    @Test fun `reserved and repeated input names are refused`() {
        val reserved = listOf("ip", "port", "tls", "confirmAny")
        for (name in reserved) {
            val e = errors(base + ("inputs" to listOf(mapOf("name" to name, "type" to "text", "label" to "L"))))
            assertEquals(listOf("The input name $name is reserved"), e)
        }
        val twice = base + ("inputs" to listOf(mapOf("name" to "a", "type" to "text", "label" to "L"), mapOf("name" to "a", "type" to "bool", "label" to "L")))
        assertEquals(listOf("The input name a is used twice"), errors(twice))
    }

    @Test fun `a descriptor with only errors in inputs still reports the tool level ones too`() {
        val e = errors(mapOf("id" to "ab", "name" to "N", "target" to "port", "inputs" to listOf(mapOf("name" to "x", "type" to "zzz", "label" to "L"))))
        assertEquals(2, e.size)
    }
}
