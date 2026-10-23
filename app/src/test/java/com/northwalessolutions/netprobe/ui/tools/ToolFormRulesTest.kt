package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ChoiceOption
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.manual.ManualCopy
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The generated form's state (spec section 5, G14), Compose-free: defaults, prefill, validation on every edit, Run enabled, the confirmation flag, TLS. */
class ToolFormRulesTest {
    private val registry = TestRegistry.registry()
    private fun builtIn(key: String): ToolModule = registry.byKey(key)!!
    private val home = SubnetMath.of(Ipv4.parse("192.168.1.50")!!, 24)
    private val ctx = FormContext(home = home)
    private val lists = listOf(WordlistMeta("builtin:common-short", "common-short", 120, null, true), WordlistMeta("user:big", "big", 9000, 1L, false))
    private fun svc(name: String) = ServiceInfo(name, confidence = Confidence.CONFIRMED)

    private fun script(key: String = "user:probe", target: TargetKind = TargetKind.PORT, network: NetworkScope = NetworkScope.TARGET, inputs: List<InputSpec> = emptyList(),
                       check: (com.northwalessolutions.netprobe.engine.tools.ToolInputs) -> Map<String, String> = { emptyMap() }): ToolModule =
        DescribedModule(UiToolFakes.descriptor(key, "Probe", ToolCategory.MINE, target, inputs, network, source = ToolSource.Script(File("x.js"), "sha")), check)

    private fun ready(m: ToolModule, ip: String = "192.168.1.20", port: String = "8080", mutate: (ToolForm) -> ToolForm = { it }): ToolForm {
        var f = ToolFormRules.initial(m.descriptor)
        if (m.descriptor.target != TargetKind.NONE) f = ToolFormRules.withDevice(f, 7, ip)
        if (m.descriptor.target == TargetKind.PORT) f = ToolFormRules.withPort(f, port)
        return mutate(f)
    }

    // defaults and prefill

    @Test fun `the form starts from the descriptor defaults`() {
        val f = ToolFormRules.initial(builtIn(ToolKeys.DIR_BRUTEFORCE).descriptor)
        assertEquals(mapOf("wordlist" to "builtin:common-short", "extensions" to "", "method" to "HEAD_THEN_GET", "concurrency" to "8", "timeoutS" to "3",
            "statusCodes" to "200,204,301,302,307,401,403", "baseline" to "true"), f.values)
        assertTrue(f.touched.isEmpty()); assertEquals("", f.deviceIp); assertNull(f.deviceHostId)
        assertEquals("public", ToolFormRules.initial(builtIn(ToolKeys.SNMP_WALK).descriptor).values["community"])
        assertEquals("v2c", ToolFormRules.initial(builtIn(ToolKeys.SNMP_WALK).descriptor).values["version"])
    }
    @Test fun `every input type has a default text`() {
        val specs = listOf(InputSpec.Ip("a", "A", default = "10.0.0.1"), InputSpec.Port("b", "B", default = 80), InputSpec.Text("c", "C", default = "x"), InputSpec.Number("d", "D", default = 5),
            InputSpec.Bool("e", "E", default = true), InputSpec.Choice("f", "F", listOf(ChoiceOption("one")), default = "one"), InputSpec.Wordlist("g", "G", default = "w"),
            InputSpec.Secret("h", "H", default = "s"), InputSpec.TextList("i", "I", default = "p,q"), InputSpec.MultiChoice("j", "J", listOf(ChoiceOption("1"), ChoiceOption("2")), default = listOf("1", "2")),
            InputSpec.Tls("k", "K", default = true), InputSpec.Text("l", "L"), InputSpec.Number("m", "M"), InputSpec.Bool("n", "N"))
        assertEquals(listOf("10.0.0.1", "80", "x", "5", "true", "one", "w", "s", "p,q", "1,2", "true", "", "", "false"), specs.map { ToolFormRules.defaultRaw(it) })
    }
    @Test fun `a Device screen prefills the device and the port`() {
        val f = ToolFormRules.initial(builtIn(ToolKeys.HTTP_FINGERPRINT).descriptor, hostId = 7, ip = "192.168.1.20", port = 8080)
        assertEquals(7L, f.deviceHostId); assertEquals("192.168.1.20", f.deviceIp); assertEquals("8080", f.portText)
        val ev = ToolFormRules.evaluate(builtIn(ToolKeys.HTTP_FINGERPRINT), f, ctx)
        assertTrue(ev.canRun); assertEquals(8080, ev.port); assertEquals("192.168.1.20", ev.ip!!.hostAddress)
    }
    @Test fun `a prefill never reaches a tool with a fixed port, a host tool's port, port 0 or a tool with no device`() {
        val smb = ToolFormRules.initial(builtIn(ToolKeys.SMB_SESSION).descriptor, 7, "192.168.1.20", 445)
        assertEquals("", smb.portText); assertEquals("192.168.1.20", smb.deviceIp)
        assertEquals("", ToolFormRules.initial(builtIn(ToolKeys.IDENTIFY_ALL).descriptor, 7, "192.168.1.20", 0).portText)
        assertEquals("", ToolFormRules.initial(builtIn(ToolKeys.HTTP_FINGERPRINT).descriptor, 7, "192.168.1.20", 0).portText)
        val none = ToolFormRules.initial(script(target = TargetKind.NONE).descriptor, 7, "192.168.1.20", 80)
        assertEquals("", none.deviceIp); assertNull(none.deviceHostId)
        assertEquals("", ToolFormRules.initial(builtIn(ToolKeys.HTTP_FINGERPRINT).descriptor, hostId = 7, ip = null, port = 80).deviceIp)
    }

    // validation and Run

    @Test fun `an empty port tool form cannot run and scolds nothing until a field is touched`() {
        val ev = ToolFormRules.evaluate(builtIn(ToolKeys.HTTP_FINGERPRINT), ToolFormRules.initial(builtIn(ToolKeys.HTTP_FINGERPRINT).descriptor), ctx)
        assertFalse(ev.canRun); assertEquals(setOf("ip", "port"), ev.errors.keys); assertTrue(ev.visibleErrors.isEmpty())
        assertEquals("Choose a device", ev.errors["ip"]); assertEquals(ManualCopy.ERR_PORT_NEEDED, ev.errors["port"])
        assertNull(ev.inputs)
        assertEquals("Complete the form to run", ToolFormRules.hint(ev, true))
    }
    @Test fun `choosing a device and a port enables Run, and clearing the port disables it again with an inline error`() {
        val m = builtIn(ToolKeys.HTTP_FINGERPRINT)
        var f = ToolFormRules.withDevice(ToolFormRules.initial(m.descriptor), 7, "192.168.1.20")
        assertFalse(ToolFormRules.evaluate(m, f, ctx).canRun)
        f = ToolFormRules.withPort(f, "80")
        assertTrue(ToolFormRules.evaluate(m, f, ctx).canRun); assertNull(ToolFormRules.hint(ToolFormRules.evaluate(m, f, ctx), true))
        f = ToolFormRules.withPort(f, "")
        val ev = ToolFormRules.evaluate(m, f, ctx)
        assertFalse(ev.canRun); assertEquals(ManualCopy.ERR_PORT_NEEDED, ev.visibleErrors["port"])
        assertEquals(ManualCopy.ERR_PORT_RANGE, ToolFormRules.evaluate(m, ToolFormRules.withPort(f, "99999"), ctx).visibleErrors["port"])
        assertEquals("65535", ToolFormRules.withPort(f, "65535x").portText)
    }
    @Test fun `every edit is validated and the error shows inline`() {
        val m = builtIn(ToolKeys.DIR_BRUTEFORCE)
        var f = ready(m, port = "80")
        assertTrue(ToolFormRules.evaluate(m, f, ctx).canRun)
        f = ToolFormRules.withInput(f, "concurrency", "99")
        var ev = ToolFormRules.evaluate(m, f, ctx)
        assertFalse(ev.canRun); assertEquals("Use 16 or less", ev.visibleErrors["concurrency"])
        f = ToolFormRules.withInput(f, "concurrency", "abc")
        assertEquals("Enter a whole number", ToolFormRules.evaluate(m, f, ctx).visibleErrors["concurrency"])
        f = ToolFormRules.withInput(f, "concurrency", "0")
        assertEquals("Use 1 or more", ToolFormRules.evaluate(m, f, ctx).visibleErrors["concurrency"])
        f = ToolFormRules.withInput(f, "concurrency", "4")
        ev = ToolFormRules.evaluate(m, f, ctx)
        assertTrue(ev.canRun); assertTrue(ev.visibleErrors.isEmpty()); assertEquals(4, ev.inputs!!.int("concurrency"))
    }
    @Test fun `list and multi-choice inputs are validated`() {
        val m = builtIn(ToolKeys.DIR_BRUTEFORCE)
        val ok = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "extensions", "php,html") }, ctx)
        assertTrue(ok.canRun); assertEquals(listOf("php", "html"), ok.inputs!!.list("extensions"))
        val many = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "extensions", (1..9).joinToString(",") { n -> "e$n" }) }, ctx)
        assertFalse(many.canRun); assertTrue(many.visibleErrors.containsKey("extensions"))
        val bad = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "extensions", "ph-p") }, ctx)
        assertFalse(bad.canRun); assertEquals("Use letters and digits only, for example php, html", bad.visibleErrors["extensions"])
        val noCodes = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "statusCodes", "") }, ctx)
        assertFalse(noCodes.canRun); assertTrue(noCodes.visibleErrors.containsKey("statusCodes"))
        val some = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "statusCodes", "403,200") }, ctx)
        assertEquals(listOf("200", "403"), some.inputs!!.list("statusCodes"))
    }
    @Test fun `the module's own check can refuse a form that passes the generic rules`() {
        val m = script(inputs = listOf(InputSpec.Text("path", "Path", required = false)), check = { if (it.text("path") == "bad") mapOf("path" to "Not that path") else emptyMap() })
        assertTrue(ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "path", "ok") }, ctx).canRun)
        val ev = ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "path", "bad") }, ctx)
        assertFalse(ev.canRun); assertEquals("Not that path", ev.visibleErrors["path"])
    }
    @Test fun `a required own input blank stays quiet until touched, then shows`() {
        val m = script(inputs = listOf(InputSpec.Text("name", "Name")))
        val untouched = ToolFormRules.evaluate(m, ready(m), ctx)
        assertFalse(untouched.canRun); assertEquals("Required", untouched.errors["name"]); assertTrue(untouched.visibleErrors.isEmpty())
        val touched = ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "name", "") }, ctx)
        assertEquals("Required", touched.visibleErrors["name"])
        assertTrue(ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "name", "x") }, ctx).canRun)
    }
    @Test fun `an optional blank input is fine and a choice outside its options is not`() {
        val m = script(inputs = listOf(InputSpec.Text("note", "Note", required = false), InputSpec.Choice("mode", "Mode", listOf(ChoiceOption("a"), ChoiceOption("b")), default = "a")))
        val ev = ToolFormRules.evaluate(m, ready(m), ctx)
        assertTrue(ev.canRun); assertFalse(ev.inputs!!.has("note")); assertEquals("a", ev.inputs!!.choice("mode"))
        assertFalse(ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "mode", "z") }, ctx).canRun)
    }
    @Test fun `no network disables Run and says why`() {
        val m = builtIn(ToolKeys.HTTP_FINGERPRINT)
        val ev = ToolFormRules.evaluate(m, ready(m), ctx.copy(lan = false))
        assertFalse(ev.canRun); assertTrue(ev.errors.isEmpty()); assertEquals(ManualCopy.NO_LAN, ToolFormRules.hint(ev, lan = false))
    }
    @Test fun `reserved and malformed addresses are refused`() {
        val m = builtIn(ToolKeys.HTTP_FINGERPRINT)
        assertEquals(ManualCopy.ERR_ADDRESS, ToolFormRules.evaluate(m, ready(m, ip = "127.0.0.1"), ctx).visibleErrors["ip"])
        assertEquals(ManualCopy.ERR_ADDRESS, ToolFormRules.evaluate(m, ready(m, ip = "192.168.1.255"), ctx).visibleErrors["ip"])
        assertEquals(ManualCopy.ERR_IP, ToolFormRules.evaluate(m, ready(m, ip = "banana"), ctx).visibleErrors["ip"])
        assertTrue(ToolFormRules.evaluate(m, ready(m, ip = "8.8.8.8"), ctx).canRun)
    }
    @Test fun `a wordlist that is gone is refused, and a known one is resolved with its name and size`() {
        val m = builtIn(ToolKeys.DIR_BRUTEFORCE)
        val loaded = ctx.copy(wordlists = lists)
        val gone = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "wordlist", "user:deleted") }, loaded)
        assertFalse(gone.canRun); assertEquals("That wordlist is gone, choose another", gone.visibleErrors["wordlist"])
        val big = ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withInput(it, "wordlist", "user:big") }, loaded)
        assertTrue(big.canRun); assertEquals(com.northwalessolutions.netprobe.engine.tools.WordlistRef("user:big", "big", 9000), big.inputs!!.wordlist("wordlist"))
        assertTrue("lists not loaded yet: the default is let through", ToolFormRules.evaluate(m, ready(m, port = "80"), ctx).canRun)
    }

    // targets

    @Test fun `host and fixed-port tools show no port, and say which port they use`() {
        val snmp = ToolFormRules.evaluate(builtIn(ToolKeys.SNMP_WALK), ready(builtIn(ToolKeys.SNMP_WALK)), ctx)
        assertTrue(snmp.canRun); assertFalse(snmp.showPort); assertTrue(snmp.showDevice); assertEquals(ManualCopy.FIXED_SNMP, snmp.fixedLine); assertEquals(161, snmp.port)
        val smb = ToolFormRules.evaluate(builtIn(ToolKeys.SMB_SESSION), ready(builtIn(ToolKeys.SMB_SESSION)), ctx)
        assertTrue(smb.canRun); assertFalse(smb.showPort); assertEquals(445, smb.port); assertEquals(ManualCopy.FIXED_SMB, smb.fixedLine)
        val all = ToolFormRules.evaluate(builtIn(ToolKeys.IDENTIFY_ALL), ready(builtIn(ToolKeys.IDENTIFY_ALL)), ctx)
        assertTrue(all.canRun); assertEquals(0, all.port); assertEquals(ManualCopy.FIXED_ALL, all.fixedLine)
        val host = script(target = TargetKind.HOST)
        val h = ToolFormRules.evaluate(host, ready(host), ctx)
        assertTrue(h.canRun); assertFalse(h.showPort); assertNull(h.fixedLine); assertEquals(0, h.port)
    }
    @Test fun `a tool with no target has no device and runs on its own inputs`() {
        val m = script(target = TargetKind.NONE, inputs = listOf(InputSpec.Text("name", "Name")))
        val ev0 = ToolFormRules.evaluate(m, ToolFormRules.initial(m.descriptor), ctx)
        assertFalse(ev0.showDevice); assertFalse(ev0.showPort); assertEquals(setOf("name"), ev0.errors.keys); assertFalse(ev0.canRun)
        val ev = ToolFormRules.evaluate(m, ToolFormRules.withInput(ToolFormRules.initial(m.descriptor), "name", "example.com"), ctx)
        assertTrue(ev.canRun); assertEquals("example.com", ev.inputs!!.text("name")); assertFalse(ev.inputs!!.has("ip"))
        assertEquals(TlsMode.NEVER, ev.tlsMode)
    }
    @Test fun `choosing another device drops the port and the HTTPS choice, choosing the same one keeps them`() {
        var f = ready(builtIn(ToolKeys.HTTP_FINGERPRINT)) { ToolFormRules.withHttps(it, true) }
        assertEquals("8080", f.portText); assertEquals(true, f.httpsChoice)
        assertSame(f, ToolFormRules.withDevice(f, 7, "192.168.1.20"))
        f = ToolFormRules.withDevice(f, 9, "192.168.1.30")
        assertEquals("", f.portText); assertNull(f.httpsChoice); assertEquals(9L, f.deviceHostId); assertEquals("192.168.1.30", f.deviceIp)
        assertNull(ToolFormRules.withDevice(f, null, "8.8.8.8").deviceHostId)
    }

    // TLS

    @Test fun `the HTTPS switch starts from the stored service, else the port number, and the Director's choice wins`() {
        val m = builtIn(ToolKeys.HTTP_FINGERPRINT)
        assertTrue(ToolFormRules.evaluate(m, ready(m, port = "443"), ctx).tls)
        assertFalse(ToolFormRules.evaluate(m, ready(m, port = "80"), ctx).tls)
        assertTrue(ToolFormRules.evaluate(m, ready(m, port = "5001"), ctx.copy(services = mapOf(5001 to svc("HTTPS")))).tls)
        assertFalse(ToolFormRules.evaluate(m, ready(m, port = "5000"), ctx.copy(services = mapOf(5000 to svc("HTTP")))).tls)
        assertFalse(ToolFormRules.evaluate(m, ready(m, port = "443") { ToolFormRules.withHttps(it, false) }, ctx).tls)
        assertTrue(ToolFormRules.evaluate(m, ready(m, port = "80") { ToolFormRules.withHttps(it, true) }, ctx).tls)
    }
    @Test fun `built-ins keep their TLS rule and a script port tool gets the switch`() {
        assertEquals(TlsMode.SWITCH, ToolFormRules.tlsMode(builtIn(ToolKeys.DIR_BRUTEFORCE).descriptor)); assertEquals(TlsMode.SWITCH, ToolFormRules.tlsMode(builtIn(ToolKeys.HTTP_FINGERPRINT).descriptor))
        assertEquals(TlsMode.ALWAYS, ToolFormRules.tlsMode(builtIn(ToolKeys.TLS_CERTIFICATE).descriptor))
        listOf(ToolKeys.SMB_SESSION, ToolKeys.SNMP_WALK, ToolKeys.IDENTIFY, ToolKeys.IDENTIFY_ALL).forEach { assertEquals(it, TlsMode.NEVER, ToolFormRules.tlsMode(builtIn(it).descriptor)) }
        assertEquals(TlsMode.SWITCH, ToolFormRules.tlsMode(script().descriptor)); assertEquals(TlsMode.NEVER, ToolFormRules.tlsMode(script(target = TargetKind.HOST).descriptor))
        assertTrue(ToolFormRules.evaluate(builtIn(ToolKeys.TLS_CERTIFICATE), ready(builtIn(ToolKeys.TLS_CERTIFICATE), port = "80"), ctx).tls)
        assertFalse(ToolFormRules.evaluate(builtIn(ToolKeys.SMB_SESSION), ready(builtIn(ToolKeys.SMB_SESSION)), ctx).tls)
        val s = script()
        assertTrue(ToolFormRules.evaluate(s, ready(s, port = "443"), ctx).inputs!!.bool("tls"))
        assertFalse(ToolFormRules.evaluate(s, ready(s, port = "80"), ctx).inputs!!.bool("tls"))
    }

    // the per-run confirmation (ruling a)

    @Test fun `a network any tool needs the confirmation, and the confirmed run carries the flag`() {
        val any = script(network = NetworkScope.ANY)
        val ev = ToolFormRules.evaluate(any, ready(any), ctx)
        assertTrue("Run stays enabled before the confirmation", ev.canRun)
        assertTrue(ToolFormRules.needsConfirm(any.descriptor))
        assertNull(ToolFormRules.startInputs(any.descriptor, ev, confirmed = false))
        val confirmed = ToolFormRules.startInputs(any.descriptor, ev, confirmed = true)!!
        assertEquals(true, confirmed.values[ScriptToolModule.CONFIRM_ANY]); assertEquals(8080, confirmed.port())
        assertNull("the form's own inputs never hold the flag", ev.inputs!!.values[ScriptToolModule.CONFIRM_ANY])
    }
    @Test fun `other scopes start without a flag, and an invalid form never starts`() {
        listOf(NetworkScope.TARGET, NetworkScope.LAN).forEach { scope ->
            val m = script(network = scope)
            assertFalse(ToolFormRules.needsConfirm(m.descriptor))
            val inputs = ToolFormRules.startInputs(m.descriptor, ToolFormRules.evaluate(m, ready(m), ctx), confirmed = false)!!
            assertFalse(inputs.values.containsKey(ScriptToolModule.CONFIRM_ANY))
        }
        val any = script(network = NetworkScope.ANY)
        assertNull(ToolFormRules.startInputs(any.descriptor, ToolFormRules.evaluate(any, ToolFormRules.initial(any.descriptor), ctx), confirmed = true))
        assertFalse(ToolFormRules.needsConfirm(builtIn(ToolKeys.DIR_BRUTEFORCE).descriptor))
    }
    @Test fun `a secret input is masked data that stays in the run's inputs but never in the recorded ones`() {
        val m = builtIn(ToolKeys.SNMP_WALK)
        val ev = ToolFormRules.evaluate(m, ready(m) { ToolFormRules.withInput(it, "community", "hunter2") }, ctx)
        assertEquals("hunter2", ev.inputs!!.text("community"))
        assertFalse(ev.inputs!!.withoutSecrets(m.descriptor).values.containsKey("community"))
    }
}
