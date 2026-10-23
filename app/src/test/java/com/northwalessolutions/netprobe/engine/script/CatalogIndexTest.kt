package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogIndexTest {
    private val sha = "a".repeat(64)
    private fun line(id: String = "my-tool", extra: String = "") =
        """{"id":"$id","file":"$id.js","name":"My tool","version":"1.0","sha256":"$sha","network":"lan","description":"d","broadcast":false$extra}"""
    private fun index(vararg lines: String, format: Int = 1) = """{"format":$format,"tools":[${lines.joinToString(",")}]}"""
    private fun parsed(text: String) = CatalogIndex.parse(text) as CatalogIndexResult.Parsed

    @Test fun `a good line is read in full`() {
        val e = parsed(index(line())).entries.single()
        assertEquals(CatalogEntry("my-tool", "my-tool.js", "My tool", "1.0", sha, NetworkScope.LAN, "d", false), e)
        assertEquals("user:my-tool", e.key)
    }

    @Test fun `defaults fill the optional fields and unknown fields are ignored`() {
        val e = parsed("""{"format":1,"future":true,"tools":[{"id":"abc","file":"abc.js","name":"N","sha256":"$sha","more":1}]}""").entries.single()
        assertEquals("1", e.version); assertEquals(NetworkScope.TARGET, e.network); assertEquals("", e.description); assertEquals(false, e.broadcast)
    }

    @Test fun `the minimum app version is optional and kept when given`() {
        assertEquals(null, parsed(index(line())).entries.single().minVersionCode)
        val e = parsed(index(line(extra = ""","minVersionCode":12,"minVersionName":"0.4.3""""))).entries.single()
        assertEquals(12, e.minVersionCode); assertEquals("0.4.3", e.minVersionName)
        assertEquals(12, parsed(index(line(extra = ""","minVersionCode":12"""))).entries.single().minVersionCode)
    }

    @Test fun `a bad minimum app version rejects only its line`() {
        for (extra in listOf(""","minVersionCode":0""", ""","minVersionCode":-3""", ""","minVersionName":"not a version!"""")) {
            val p = parsed(index(line(extra = extra), line("other-tool")))
            assertEquals(extra, listOf("other-tool"), p.entries.map { it.id }); assertEquals(extra, 1, p.rejected.size)
        }
    }

    @Test fun `the sha is lower-cased`() {
        assertEquals("a".repeat(64), parsed(index(line().replace(sha, "A".repeat(64)))).entries.single().sha256)
    }

    @Test fun `an unreadable index is reported as such`() {
        for (text in listOf("", "not json", "[]", "{}", """{"format":2,"tools":[]}""", """{"format":"1","tools":[]}""", """{"format":1}""", """{"format":1,"tools":{}}""", """{"format":1.5,"tools":[]}"""))
            assertEquals(text, CatalogIndexResult.Unreadable, CatalogIndex.parse(text))
    }

    @Test fun `a bad line is rejected with its reason and the others are kept`() {
        val bad = listOf(
            line("BadId").replace("BadId.js", "BadId.js") to "The id is not valid",
            line("abc").replace("\"file\":\"abc.js\"", "\"file\":\"other.js\"") to "The file name is not the id plus .js",
            line("abc").replace("\"My tool\"", "\"\"") to "The name is missing or longer than 40 characters",
            line("abc").replace("\"My tool\"", "\"${"n".repeat(41)}\"") to "The name is missing or longer than 40 characters",
            line("abc").replace("\"1.0\"", "\"has space\"") to "The version is not valid",
            line("abc").replace(sha, "zz") to "The SHA-256 is not 64 hex digits",
            line("abc").replace("\"lan\"", "\"world\"") to "The network is not target, lan or any",
            line("abc").replace("\"d\"", "\"${"d".repeat(201)}\"") to "The description is longer than 200 characters",
            line("abc").replace("\"broadcast\":false", "\"broadcast\":true").replace("\"lan\"", "\"any\"") to "Only a tool with the network set to lan can declare broadcast",
            """{"id":"abc","name":"N"}""" to "The catalog line is missing a field or has the wrong kind of value",
            """{"id":5,"file":"x.js","name":"N","sha256":"$sha"}""" to "The catalog line is missing a field or has the wrong kind of value",
        )
        for ((text, reason) in bad) {
            val p = parsed(index(text, line("good-one")))
            assertEquals(text, listOf("good-one"), p.entries.map { it.id })
            assertEquals(text, 1, p.rejected.size)
            assertEquals(text, reason, p.rejected.single().reason)
        }
    }

    @Test fun `a line that is not an object is rejected`() {
        val p = parsed("""{"format":1,"tools":[5,"x",null,${line()}]}""")
        assertEquals(1, p.entries.size); assertEquals(3, p.rejected.size)
        assertEquals(listOf("Line 1", "Line 2", "Line 3"), p.rejected.map { it.label })
    }

    @Test fun `a duplicate id keeps the first line`() {
        val p = parsed(index(line(), line().replace("My tool", "Second")))
        assertEquals("My tool", p.entries.single().name)
        assertEquals(listOf(CatalogRejected("my-tool", "Listed twice, the second line was ignored")), p.rejected)
    }

    @Test fun `no more than 200 lines are read`() {
        val lines = (1..205).map { line("tool-%03d".format(it)) }
        val p = parsed(index(*lines.toTypedArray()))
        assertEquals(200, p.entries.size)
        assertEquals(listOf(CatalogRejected("Catalog", "The catalog lists more than 200 tools, the rest were ignored")), p.rejected)
    }

    @Test fun `a long id is cut in the message`() {
        val p = parsed(index("""{"id":"${"x".repeat(300)}","file":"y"}"""))
        assertEquals(40, p.rejected.single().label.length)
    }
}
