package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address

/** ToolInputs.validate: every input type, required, defaults, min and max, bad ip and port, choice outside its options, secrets, reserved names. */
class ToolInputsTest {
    private fun desc(target: TargetKind = TargetKind.NONE, vararg inputs: InputSpec) =
        ToolDescriptor("t", "T", "", ToolIcon.TERMINAL, ToolCategory.WEB, target, inputs.toList())
    private fun valid(d: ToolDescriptor, raw: Map<String, String>): ToolInputs = (ToolInputs.validate(d, raw) as InputsResult.Valid).inputs
    private fun errors(d: ToolDescriptor, raw: Map<String, String>): Map<String, String> = (ToolInputs.validate(d, raw) as InputsResult.Invalid).errors

    @Test fun `a none target has no implicit inputs`() {
        val v = valid(desc(), emptyMap())
        assertTrue(v.values.isEmpty())
    }
    @Test fun `a host target requires an ip and gives an Inet4Address`() {
        val d = desc(TargetKind.HOST)
        assertEquals(mapOf("ip" to ToolModelCopy.REQUIRED), errors(d, emptyMap()))
        val v = valid(d, mapOf("ip" to " 10.0.3.9 "))
        assertEquals("10.0.3.9", v.ip().hostAddress); assertTrue(v.values["ip"] is Inet4Address)
        assertFalse(v.has("port"))
    }
    @Test fun `a port target requires ip and port, and tls defaults to false`() {
        val d = desc(TargetKind.PORT)
        assertEquals(mapOf("ip" to ToolModelCopy.REQUIRED, "port" to ToolModelCopy.REQUIRED), errors(d, emptyMap()))
        val v = valid(d, mapOf("ip" to "10.0.3.9", "port" to "5000", "tls" to "true"))
        assertEquals(5000, v.port()); assertTrue(v.bool("tls"))
        assertFalse(valid(d, mapOf("ip" to "10.0.3.9", "port" to "5000")).bool("tls"))
    }
    @Test fun `bad ips are refused without any name lookup`() {
        val d = desc(TargetKind.HOST)
        for (bad in listOf("10.0.3", "10.0.3.256", "a.b.c.d", "example.com", "10.0.3.9.1", "10.0.3.-1", "::1", "10.0.3.9/24")) {
            assertEquals(bad, mapOf("ip" to ToolModelCopy.BAD_IP), errors(d, mapOf("ip" to bad)))
        }
    }
    @Test fun `bad ports are refused`() {
        val d = desc(TargetKind.PORT)
        for (bad in listOf("0", "65536", "-1", "http", "80.5", "99999999999")) {
            assertEquals(bad, ToolModelCopy.BAD_PORT, errors(d, mapOf("ip" to "10.0.3.9", "port" to bad))["port"])
        }
        assertEquals(1, valid(d, mapOf("ip" to "10.0.3.9", "port" to "1")).port())
        assertEquals(65535, valid(d, mapOf("ip" to "10.0.3.9", "port" to "65535")).port())
    }
    @Test fun `text is trimmed, required unless said otherwise, and an optional blank takes the default or is left out`() {
        val d = desc(inputs = arrayOf(InputSpec.Text("path", "Path", default = "/", required = false), InputSpec.Text("name", "Name"), InputSpec.Text("note", "Note", required = false)))
        assertEquals(mapOf("name" to ToolModelCopy.REQUIRED), errors(d, mapOf("name" to "  ")))
        val v = valid(d, mapOf("name" to " x ", "path" to ""))
        assertEquals("x", v.text("name")); assertEquals("/", v.text("path")); assertFalse(v.has("note")); assertEquals("", v.text("note"))
        assertEquals("/a", valid(d, mapOf("name" to "x", "path" to "/a")).text("path"))
    }
    @Test fun `a required input that has a default takes it only when the key is absent`() {
        val d = desc(inputs = arrayOf(InputSpec.Text("path", "Path", default = "/")))
        assertEquals("/", valid(d, emptyMap()).text("path"))
        assertEquals(mapOf("path" to ToolModelCopy.REQUIRED), errors(d, mapOf("path" to "")))
    }
    @Test fun `numbers are whole, and honour min and max`() {
        val d = desc(inputs = arrayOf(InputSpec.Number("n", "N", min = 1, max = 16, default = 8)))
        assertEquals(8, valid(d, emptyMap()).int("n"))
        assertEquals(1, valid(d, mapOf("n" to "1")).int("n")); assertEquals(16, valid(d, mapOf("n" to "16")).int("n"))
        assertEquals(mapOf("n" to ToolModelCopy.tooSmall(1)), errors(d, mapOf("n" to "0")))
        assertEquals(mapOf("n" to ToolModelCopy.tooBig(16)), errors(d, mapOf("n" to "17")))
        assertEquals(mapOf("n" to ToolModelCopy.NOT_A_NUMBER), errors(d, mapOf("n" to "1.5")))
        assertEquals(mapOf("n" to ToolModelCopy.NOT_A_NUMBER), errors(d, mapOf("n" to "abc")))
        val open = desc(inputs = arrayOf(InputSpec.Number("n", "N")))
        assertEquals(-5, valid(open, mapOf("n" to "-5")).int("n"))
    }
    @Test fun `a choice must be one of its options`() {
        val d = desc(inputs = arrayOf(InputSpec.Choice("m", "M", listOf(ChoiceOption("A", "Option A"), ChoiceOption("B")), default = "A")))
        assertEquals("A", valid(d, emptyMap()).choice("m")); assertEquals("B", valid(d, mapOf("m" to "B")).choice("m"))
        assertEquals(mapOf("m" to ToolModelCopy.NOT_AN_OPTION), errors(d, mapOf("m" to "C")))
        assertEquals("the label is not the value", mapOf("m" to ToolModelCopy.NOT_AN_OPTION), errors(d, mapOf("m" to "Option A")))
    }
    @Test fun `bool and tls inputs read true and false words and fall back to their default`() {
        val d = desc(inputs = arrayOf(InputSpec.Bool("b", "B", default = true), InputSpec.Tls("https", "HTTPS")))
        val v = valid(d, emptyMap()); assertTrue(v.bool("b")); assertFalse(v.bool("https"))
        val w = valid(d, mapOf("b" to "false", "https" to "yes")); assertFalse(w.bool("b")); assertTrue(w.bool("https"))
        assertTrue(valid(d, mapOf("b" to "maybe")).bool("b"))
    }
    @Test fun `a wordlist keeps its id and takes the name and entry count the resolver knows`() {
        val d = desc(inputs = arrayOf(InputSpec.Wordlist("wordlist", "Wordlist", default = "builtin:common-short")))
        assertEquals(WordlistRef("builtin:common-short"), valid(d, emptyMap()).wordlist("wordlist"))
        val known = ToolInputs.validate(d, mapOf("wordlist" to "user:abc")) { id -> WordlistRef(id, "mine", 12) } as InputsResult.Valid
        assertEquals(WordlistRef("user:abc", "mine", 12), known.inputs.wordlist("wordlist"))
        assertEquals(mapOf("wordlist" to ToolModelCopy.REQUIRED), errors(d, mapOf("wordlist" to "")))
    }
    @Test fun `a list splits on commas, drops blanks and honours maxItems`() {
        val d = desc(inputs = arrayOf(InputSpec.TextList("ext", "Ext", required = false, maxItems = 2)))
        assertEquals(listOf("php", "html"), valid(d, mapOf("ext" to " php, ,html,")).list("ext"))
        assertEquals(emptyList<String>(), valid(d, mapOf("ext" to "")).list("ext"))
        assertEquals(mapOf("ext" to ToolModelCopy.tooManyItems(2)), errors(d, mapOf("ext" to "a,b,c")))
    }
    @Test fun `a multichoice keeps option order, refuses strangers, and falls back to its default set`() {
        val opts = listOf(200, 301, 403).map { ChoiceOption(it.toString()) }
        val d = desc(inputs = arrayOf(InputSpec.MultiChoice("codes", "Codes", opts, default = listOf("200", "403"))))
        assertEquals(listOf("200", "403"), valid(d, emptyMap()).list("codes"))
        assertEquals(listOf("200", "301"), valid(d, mapOf("codes" to "301,200")).list("codes"))
        assertEquals(mapOf("codes" to ToolModelCopy.NOT_AN_OPTION), errors(d, mapOf("codes" to "200,999")))
        assertEquals(mapOf("codes" to ToolModelCopy.REQUIRED), errors(d, mapOf("codes" to "")))
    }
    @Test fun `a secret is a string value and is dropped by withoutSecrets`() {
        val d = desc(inputs = arrayOf(InputSpec.Secret("community", "Community", default = "public"), InputSpec.Text("x", "X", required = false)))
        val v = valid(d, mapOf("x" to "y"))
        assertEquals("public", v.text("community"))
        val safe = v.withoutSecrets(d)
        assertFalse(safe.has("community")); assertEquals("y", safe.text("x"))
    }
    @Test fun `every error comes back at once, keyed by field`() {
        val d = desc(TargetKind.PORT, InputSpec.Number("n", "N", min = 1), InputSpec.Text("t", "T"))
        val e = errors(d, mapOf("ip" to "x", "port" to "0", "n" to "0"))
        assertEquals(setOf("ip", "port", "n", "t"), e.keys)
    }
    @Test fun `descriptor rules reject the reserved names and duplicates`() {
        assertTrue(DescriptorRules.errors(desc(inputs = arrayOf(InputSpec.Text("a", "A")))).isEmpty())
        for (name in listOf("ip", "port", "tls")) {
            assertEquals(name, listOf(ToolModelCopy.reservedName(name)), DescriptorRules.errors(desc(inputs = arrayOf(InputSpec.Text(name, "X")))))
        }
        assertEquals(listOf(ToolModelCopy.duplicateName("a")), DescriptorRules.errors(desc(inputs = arrayOf(InputSpec.Text("a", "A"), InputSpec.Bool("a", "A2")))))
        assertEquals(listOf(ToolModelCopy.noOptions("c")), DescriptorRules.errors(desc(inputs = arrayOf(InputSpec.Choice("c", "C", emptyList())))))
    }
    @Test fun `icon names fall back to terminal and the set has the twelve names`() {
        assertEquals(12, ToolIcon.entries.size)
        assertEquals(setOf("web", "search", "lock", "folder", "share", "dns", "terminal", "fingerprint", "network", "wifi", "info", "bolt"), ToolIcon.entries.map { it.id }.toSet())
        assertEquals(ToolIcon.LOCK, ToolIcon.parse("lock")); assertEquals(ToolIcon.TERMINAL, ToolIcon.parse("nope")); assertEquals(ToolIcon.TERMINAL, ToolIcon.parse(null))
    }
}
