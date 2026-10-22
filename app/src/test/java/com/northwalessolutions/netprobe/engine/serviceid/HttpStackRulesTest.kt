package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

/** HttpStackRules.load (Phase 3E spec 5): the rule shape, the old-style alias, and skip-on-invalid with a reason. */
class HttpStackRulesTest {
    private val d = "$"
    private fun one(body: String) = HttpStackRules.load("[{\"id\":\"r\",\"stack\":\"S\",\"sample\":\"s\",$body}]")
    private fun reasonOf(body: String): String { val r = one(body); assertTrue("loaded: $body", r.rules.isEmpty()); return r.skipped.single().reason }
    private val cond = "\"all\":[{\"where\":\"title\",\"pattern\":\"x\"}]"

    @Test fun `a full rule loads with every field`() {
        val r = HttpStackRules.load("""[{"id":"ha","all":[{"where":"body","pattern":"homeassistant"},{"where":"status","pattern":"200"}],"stack":"Home Assistant",
            "product":"Home Assistant","version":"${d}1","role":"software","cpeProduct":"Grafana","paths":[{"path":"/api/","status":401,"body":"401: Unauthorized"}],
            "sample":{"body":"homeassistant","status":"200"},"negativeSample":{"body":"home","status":"200"}}]""", cpeProducts = setOf("Grafana"))
        assertTrue(r.skipped.toString(), r.skipped.isEmpty())
        val rule = r.rules.single()
        assertEquals(2, rule.all.size); assertEquals(MatchRole.SOFTWARE, rule.role); assertEquals("Grafana", rule.cpeProduct)
        assertEquals("/api/", rule.paths.single().path); assertTrue("paths make a rule hard by default", rule.hard)
    }
    @Test fun `an old style source pattern name entry is a one condition rule`() {
        val r = HttpStackRules.load("""[{"id":"nginx","source":"header:server","pattern":"^nginx/?([\\d.]*)","name":"nginx ${d}1","sample":"nginx/1.25.3","negativeSample":"Apache"}]""")
        val rule = r.rules.single()
        assertEquals("header:server", rule.all.single().where); assertEquals("nginx ${d}1", rule.stack); assertEquals("Apache", rule.negativeSample!!.single)
        assertFalse("a header only rule is not hard by default", rule.hard)
    }
    @Test fun `hard defaults to true for a favicon condition and false for plain conditions, and can be set`() {
        assertTrue(HttpStackRules.load("""[{"id":"f","all":[{"where":"favicon","pattern":"-?\\d+"}],"stack":"S","sample":{"favicon":"5"}}]""").rules.single().hard)
        assertFalse(one(cond).rules.single().hard)
        assertTrue(one("$cond,\"hard\":true").rules.single().hard)
    }
    @Test fun `device role may carry vendor model and type, software may not`() {
        val dv = one("$cond,\"role\":\"device\",\"vendor\":\"V\",\"model\":\"M\",\"type\":\"CAMERA\"").rules.single()
        assertEquals(DeviceType.CAMERA, dv.type); assertEquals("V", dv.vendor)
        assertEquals(DeviceType.IOT, one("$cond,\"role\":\"device\",\"type\":\"HUB\"").rules.single().type)
        assertTrue(reasonOf("$cond,\"role\":\"software\",\"type\":\"NAS\"").contains("software rule"))
        assertTrue(reasonOf("$cond,\"vendor\":\"V\"").contains("software rule"))
        assertTrue(reasonOf("$cond,\"role\":\"device\",\"type\":\"TOASTER\"").contains("unknown type"))
        assertTrue(reasonOf("$cond,\"role\":\"robot\"").contains("role"))
    }
    @Test fun `a cpeProduct must be in the map and a free cpe string is a load error`() {
        assertTrue(HttpStackRules.load("[{\"id\":\"r\",\"stack\":\"S\",\"sample\":\"s\",$cond,\"cpeProduct\":\"nope\"}]", setOf("nginx")).skipped.single().reason.contains("not in cpe_map"))
        assertEquals(1, HttpStackRules.load("[{\"id\":\"r\",\"stack\":\"S\",\"sample\":\"s\",$cond,\"cpeProduct\":\"NGINX\"}]", setOf("nginx")).rules.size)
        assertTrue(reasonOf("$cond,\"cpe\":\"cpe:2.3:a:evil:evil:1:*:*:*:*:*:*:*\"").contains("free CPE"))
    }
    @Test fun `bad entries are skipped with a reason and the rest still load`() {
        val text = """[
            {"id":"good","all":[{"where":"title","pattern":"x"}],"stack":"G","sample":"x"},
            {"id":"nowhere","all":[{"where":"colour","pattern":"x"}],"stack":"G","sample":"x"},
            {"id":"badre","all":[{"where":"title","pattern":"("}],"stack":"G","sample":"x"},
            {"id":"redos","all":[{"where":"title","pattern":"(a+)+"}],"stack":"G","sample":"x"},
            {"id":"nosample","all":[{"where":"title","pattern":"x"}],"stack":"G"},
            {"id":"nothing","stack":"G","sample":"x"},
            {"id":"badpath","stack":"G","sample":"x","paths":[{"path":"http://evil/","status":200,"body":"b"}]},
            {"id":"bodyless","stack":"G","sample":"x","paths":[{"path":"/a","status":200}]},
            {"id":"good","all":[{"where":"title","pattern":"y"}],"stack":"G","sample":"y"},
            {"id":"nostack","all":[{"where":"title","pattern":"x"}],"sample":"x"},
            {"id":"good2","all":[{"where":"header:X-Thing","pattern":"x"}],"stack":"G2","sample":"x"}
        ]"""
        val r = HttpStackRules.load(text)
        assertEquals(listOf("good", "good2"), r.rules.map { it.id })
        val why = r.skipped.associate { it.id to it.reason }
        assertTrue(why["nowhere"]!!.contains("unknown where")); assertTrue(why["badre"]!!.contains("compile")); assertTrue(why["redos"]!!.contains("nested"))
        assertTrue(why["nosample"]!!.contains("sample")); assertTrue(why["nothing"]!!.contains("no conditions"))
        assertTrue(why["badpath"]!!.contains("plain absolute")); assertTrue(why["bodyless"]!!.contains("body pattern")); assertTrue(why["good"]!!.contains("duplicate"))
        assertTrue(why["nostack"]!!.contains("stack"))
        assertEquals("header:x-thing", r.rules[1].all.single().where)
    }
    @Test fun `a file that is not a list loads nothing and says so`() {
        assertEquals("(file)", HttpStackRules.load("{}").skipped.single().id)
        assertEquals("(file)", HttpStackRules.load("not json").skipped.single().id)
    }
    @Test fun `the favicon condition and the status condition compare whole numbers`() {
        val rule = HttpStackRules.load("""[{"id":"k","all":[{"where":"status","pattern":"40[13]"},{"where":"favicon","pattern":"-123"}],"stack":"S","sample":{"status":"401","favicon":"-123"}}]""").rules.single()
        fun src(status: Int?, fav: Int?) = StackSources(emptyList(), emptyList(), null, null, "", status = status, favicon = fav)
        assertNotNull(HttpStacks.test(rule, src(401, -123))); assertNull(HttpStacks.test(rule, src(4010, -123))); assertNull(HttpStacks.test(rule, src(401, -1234)))
        assertNull("no favicon known", HttpStacks.test(rule, src(401, null))); assertNotNull(HttpStacks.test(rule, src(401, null), skipFavicon = true))
    }
    @Test fun `the version takes the capture of the first condition that captured`() {
        val rule = HttpStackRules.load("""[{"id":"j","all":[{"where":"title","pattern":"Jenkins"},{"where":"header:x-jenkins","pattern":"^([\\d.]+)${d}"}],"stack":"Jenkins","version":"${d}1","sample":{"title":"Jenkins","header:x-jenkins":"2.4"}}]""").rules.single()
        val m = HttpStacks.test(rule, StackSources(listOf("X-Jenkins" to "2.426.1"), emptyList(), "Dashboard [Jenkins]", null, ""))!!
        assertEquals("2.426.1", HttpStacks.expand(rule.version, m.capture))
    }
}
