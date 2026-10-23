package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteInputs
import org.junit.Assert.*
import org.junit.Test

/** The registry, and the seven built-in descriptors: historical keys, categories, targets, icons and inputs (tools platform spec D5, D8). */
class ToolRegistryTest {
    private val reg = TestRegistry.registry()
    private fun fake(key: String, inputs: List<InputSpec> = emptyList()) = ToolRunnerTestSupport.FakeTool(key) { _, _ -> ToolResult(com.northwalessolutions.netprobe.data.ToolRunStatus.DONE, "ok") }
        .let { f -> object : ToolModule by f { override val descriptor = f.descriptor.copy(inputs = inputs) } }

    @Test fun `byKey finds every built-in and nothing else`() {
        for (k in ToolKeys.BUILT_IN) assertEquals(k, reg.byKey(k)!!.descriptor.key)
        assertNull(reg.byKey("nope")); assertNull(reg.byKey("user:nope"))
    }
    @Test fun `each built-in descriptor key equals the historical key`() {
        assertEquals(listOf("dir-bruteforce", "http-fingerprint", "tls-certificate", "smb-session", "snmp-walk", "identify-service", "identify-all-services"),
            reg.all.value.map { it.descriptor.key })
        assertEquals(ToolKeys.BUILT_IN, reg.all.value.map { it.descriptor.key })
    }
    @Test fun `categories, targets, icons and names of the built-ins`() {
        fun d(k: String) = reg.byKey(k)!!.descriptor
        assertEquals(ToolCategory.WEB, d("dir-bruteforce").category); assertEquals(ToolCategory.WEB, d("http-fingerprint").category); assertEquals(ToolCategory.WEB, d("tls-certificate").category)
        assertEquals(ToolCategory.NETWORK, d("smb-session").category); assertEquals(ToolCategory.NETWORK, d("snmp-walk").category)
        assertEquals(ToolCategory.IDENTITY, d("identify-service").category); assertEquals(ToolCategory.IDENTITY, d("identify-all-services").category)
        assertEquals(TargetKind.HOST, d("snmp-walk").target); assertEquals(TargetKind.HOST, d("identify-all-services").target)
        for (k in listOf("dir-bruteforce", "http-fingerprint", "tls-certificate", "smb-session", "identify-service")) assertEquals(k, TargetKind.PORT, d(k).target)
        assertEquals("Directory brute-force", d("dir-bruteforce").name); assertEquals("Identify all services", d("identify-all-services").name)
        for (m in reg.all.value) {
            assertEquals(NetworkScope.TARGET, m.descriptor.network); assertEquals(ToolSource.BuiltIn, m.descriptor.source)
            assertTrue(m.descriptor.description.isNotBlank()); assertTrue(DescriptorRules.errors(m.descriptor).isEmpty())
        }
        assertEquals(setOf(ToolIcon.FOLDER, ToolIcon.WEB, ToolIcon.LOCK, ToolIcon.SHARE, ToolIcon.NETWORK, ToolIcon.FINGERPRINT, ToolIcon.SEARCH), reg.all.value.map { it.descriptor.icon }.toSet())
    }
    @Test fun `dir-brute declares its seven inputs with the old defaults`() {
        val specs = reg.byKey("dir-bruteforce")!!.descriptor.inputs
        assertEquals(listOf("wordlist", "extensions", "method", "concurrency", "timeoutS", "statusCodes", "baseline", "recursive", "maxDepth"), specs.map { it.name })
        val v = (ToolInputs.validate(reg.byKey("dir-bruteforce")!!.descriptor, mapOf("ip" to "10.0.3.9", "port" to "80")) as InputsResult.Valid).inputs
        assertEquals("builtin:common-short", v.wordlist("wordlist").id); assertEquals(emptyList<String>(), v.list("extensions"))
        assertEquals("HEAD_THEN_GET", v.choice("method")); assertEquals(8, v.int("concurrency")); assertEquals(3, v.int("timeoutS"))
        assertEquals(listOf("200", "204", "301", "302", "307", "401", "403"), v.list("statusCodes")); assertTrue(v.bool("baseline")); assertFalse(v.bool("recursive")); assertEquals(2, v.int("maxDepth"))
        val n = specs.filterIsInstance<InputSpec.Number>()
        assertEquals(listOf(1 to 16, 1 to 10, 1 to 4), n.map { it.min!! to it.max!! })
        assertEquals(DirBruteInputs.options(v), com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions())
    }
    @Test fun `snmp-walk asks for a masked community and a version`() {
        val specs = reg.byKey("snmp-walk")!!.descriptor.inputs
        assertEquals(InputSpec.Secret("community", "Community", default = "public"), specs[0])
        val version = specs[1] as InputSpec.Choice
        assertEquals(listOf("v1", "v2c"), version.options.map { it.value }); assertEquals("v2c", version.default)
    }
    @Test fun `script tools join after the built-ins, and a clash or a reserved name is dropped`() {
        val r = ToolRegistry(TestRegistry.builtIns())
        r.setScriptTools(listOf(fake("user:one"), fake("dir-bruteforce"), fake("user:one"), fake("user:bad", listOf(InputSpec.Text("port", "Port"))), fake("user:two")))
        assertEquals(ToolKeys.BUILT_IN + listOf("user:one", "user:two"), r.all.value.map { it.descriptor.key })
        assertTrue(r.byKey("dir-bruteforce")!! !is ToolRunnerTestSupport.FakeTool)
        assertNull(r.byKey("user:bad"))
        r.setScriptTools(emptyList())
        assertEquals(ToolKeys.BUILT_IN, r.all.value.map { it.descriptor.key })
        assertNull(r.byKey("user:one"))
    }
    @Test fun `a built-in that breaks the descriptor rules is refused at construction`() {
        try { ToolRegistry(listOf(fake("x", listOf(InputSpec.Text("ip", "Ip"))))); fail("expected a refusal") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("ip")) }
    }
}
