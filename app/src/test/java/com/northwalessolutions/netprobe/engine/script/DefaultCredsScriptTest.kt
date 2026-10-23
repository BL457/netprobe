package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The catalog's default-creds.js run for real through ScriptToolModule, the real guard and a fake transport (spec section 11). */
class DefaultCredsScriptTest {
    private val source = File("../catalog/default-creds.js").readText(Charsets.UTF_8).replace("\r\n", "\n")
    private val engine = RhinoScriptEngine(ScriptLimits(findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
    private val transport = FakeScriptTransport()
    private val progress = RecordingProgress()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val target = ToolTarget(1L, Ipv4.parse("192.168.1.20")!!, 80, false, null)

    private fun module(): ScriptToolModule {
        val loaded = runBlocking { engine.evaluate(source) } as ScriptEvaluation.Loaded
        val d = (ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.BuiltIn) as ParsedTool.Valid).descriptor
        return ScriptToolModule(d, source, engine, { env }, { null }, transportFor = { _, _ -> transport })
    }

    private fun run(inputs: Map<String, Any?> = emptyMap(), control: RunControl = FakeControl()) =
        runBlocking { module().run(target, ToolInputs(inputs), control, progress) }

    private fun authHeader(call: HttpCall): String? = call.headers.firstOrNull { it.first.equals("Authorization", true) }?.second
    private fun basic(user: String, pass: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
    private val challenge = listOf("WWW-Authenticate" to "Basic realm=\"Router\"")

    @Test fun `a basic-auth device is tried from the generic table and stops at the first success`() {
        transport.httpHandler = { _, call ->
            if (authHeader(call) == basic("admin", "password")) HttpReply(200, "OK", listOf("Server" to "fake"), "Welcome".toByteArray(), false, 2)
            else HttpReply(401, "Unauthorized", challenge, ByteArray(0), false, 2)
        }
        val r = run()
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        val f = progress.findings.single()
        assertEquals("admin / password", f.value); assertEquals(FindingKind.OTHER, f.kind); assertEquals("CREDENTIAL", f.extra["kind"])
        // the detection GET, then "admin/admin" (fails) and "admin/password" (succeeds); nothing after the success
        assertEquals(3, transport.calls.size)
    }

    @Test fun `a form-login device is posted to with the discovered fields and stops at the first success`() {
        val form = "<html><body><form action=\"/login.cgi\" method=\"post\">" +
            "<input type=\"hidden\" name=\"csrftoken\" value=\"tok123\">" +
            "<input type=\"text\" name=\"username\"><input type=\"password\" name=\"password\">" +
            "</form></body></html>"
        transport.httpHandler = { _, call ->
            if (call.method == "GET") HttpReply(200, "OK", listOf("Server" to "fake"), form.toByteArray(), false, 2)
            else {
                val body = String(call.body, Charsets.UTF_8)
                if (body.contains("username=admin") && body.contains("password=admin") && body.contains("csrftoken=tok123"))
                    HttpReply(302, "Found", listOf("Location" to "/index.html", "Set-Cookie" to "sess=1"), ByteArray(0), false, 2)
                else HttpReply(200, "OK", listOf("Server" to "fake"), form.toByteArray(), false, 2)
            }
        }
        val r = run()
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        val f = progress.findings.single()
        assertEquals("admin / admin", f.value); assertEquals("CREDENTIAL", f.extra["kind"])
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls[1], transport.calls[1].contains("POST") && transport.calls[1].contains("/login.cgi"))
    }

    @Test fun `a device that locks out is reported and stops at once`() {
        transport.httpHandler = { _, call -> if (authHeader(call) == null) HttpReply(401, "Unauthorized", challenge, ByteArray(0), false, 2) else HttpReply(429, "Too Many Requests", emptyList(), ByteArray(0), false, 2) }
        val r = run()
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertTrue(r.summary, r.summary.contains("locked out after 1 attempt"))
        assertTrue(progress.findings.none { it.extra["kind"] == "CREDENTIAL" })
        assertTrue(progress.findings.any { it.value.contains("lockout") })
        assertEquals(2, transport.calls.size)
    }

    @Test fun `an open admin page with no login challenge is reported without any attempts`() {
        transport.httpHandler = { _, _ -> HttpReply(200, "OK", listOf("Server" to "fake"), "<html><body>Welcome</body></html>".toByteArray(), false, 2) }
        val r = run()
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertTrue(r.summary, r.summary.contains("No login challenge"))
        assertEquals(1, transport.calls.size)
        val f = progress.findings.single()
        assertEquals(FindingKind.PATH, f.kind)
    }

    @Test fun `maxAttempts bounds the attempts even when nothing succeeds or locks out`() {
        transport.httpHandler = { _, _ -> HttpReply(401, "Unauthorized", challenge, ByteArray(0), false, 2) }
        val r = run(mapOf("maxAttempts" to 3))
        assertEquals(r.summary, ToolRunStatus.DONE, r.status)
        assertTrue(r.summary, r.summary.contains("No default credentials worked in 3 attempt"))
        assertEquals(4, transport.calls.size)
        assertTrue(progress.findings.none { it.extra["kind"] == "CREDENTIAL" })
    }

    @Test fun `a run cancelled mid-way makes no further requests`() {
        transport.httpHandler = { _, _ -> HttpReply(401, "Unauthorized", challenge, ByteArray(0), false, 2) }
        val control = FakeControl()
        val gateCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        control.onGate = { if (gateCount.incrementAndGet() == 3) { latch.countDown(); awaitCancellation() } }
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { module().run(target, ToolInputs(emptyMap()), control, progress) }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(3_000) { job.cancelAndJoin() } }
        assertTrue(job.isCancelled)
        // the detection GET and the first attempt happened; the gate for the second attempt was never let through
        assertEquals(2, transport.calls.size)
        assertTrue(progress.findings.none { it.extra["kind"] == "CREDENTIAL" })
    }

    @Test fun `the descriptor is as the catalog promises`() {
        val d = module().descriptor
        assertEquals("user:default-creds", d.key)
        assertEquals(listOf("scheme", "path", "pairs", "maxAttempts", "stopAtFirst", "formLogin"), d.inputs.map { it.name })
    }
}
