package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** One test per host API function against the fake transport (spec section 4), plus the result and finding rules. */
class HostApiTest {
    private val h = Harness()
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Runs [js] (a function body that ends with `return json(...)`) and returns the JSON the script produced. */
    private fun json(js: String, harness: Harness = h, inputs: Map<String, Any?> = emptyMap()): String {
        val r = harness.body("function json(v) { return { status: 'done', summary: JSON.stringify(v) }; }\n$js", inputs)
        check(r.status == ToolRunStatus.DONE) { "run failed: ${r.summary}" }
        return r.summary
    }

    // ctx.http.request

    @Test fun `http request returns the documented shape`() {
        assertEquals(
            """{"status":200,"statusText":"OK","headers":{"Server":"fake","Set-Cookie":"a=1, b=2"},"list":3,"body":"hello","bytes":[104,101,108,108,111],"trunc":false,"url":"http://10.0.0.5/x?q=1","timeMs":3}""",
            json("""
                var r = ctx.http.request({ url: 'http://10.0.0.5/x?q=1#frag' });
                return json({ status: r.status, statusText: r.statusText, headers: r.headers, list: r.headerList.length, body: r.body, bytes: r.bodyBytes, trunc: r.bodyBytesTruncated, url: r.url, timeMs: r.timeMs });
            """))
        assertEquals("http GET http://10.0.0.5/x?q=1 -> 10.0.0.5:80", h.transport.calls.single())
    }

    @Test fun `http request sends method headers body and port and keeps the host header`() {
        var seen: HttpCall? = null
        h.transport.httpHandler = { _, c -> seen = c; HttpReply(204, "No Content", emptyList(), ByteArray(0), false, 1) }
        json("ctx.http.request({ method: 'post', url: 'https://10.0.0.5:8443/a b'.replace(' ', '%20'), headers: { 'X-A': 'b', 'Accept': 5 }, body: 'abc', timeoutMs: 1234 }); return json(1);")
        val c = seen!!
        assertEquals("POST", c.method); assertTrue(c.tls); assertEquals(8443, c.port); assertEquals("10.0.0.5:8443", c.hostHeader)
        assertEquals("/a%20b", c.pathAndQuery); assertEquals(listOf("X-A" to "b", "Accept" to "5"), c.headers); assertEquals("abc", String(c.body)); assertEquals(1234, c.timeoutMs)
    }

    @Test fun `http request timeout is clamped and a name url gets an sni`() {
        var seen: HttpCall? = null
        h.transport.httpHandler = { _, c -> seen = c; HttpReply(200, "OK", emptyList(), ByteArray(0), false, 1) }
        json("ctx.http.request({ url: 'https://Example.test/', timeoutMs: 1 }); return json(1);")
        assertEquals(ScriptCaps.MIN_TIMEOUT_MS, seen!!.timeoutMs)
        assertEquals("example.test", seen!!.sni)
    }

    @Test fun `http request refuses malformed urls and headers`() {
        for (js in listOf(
            "ctx.http.request({ url: 'ftp://10.0.0.5/' })", "ctx.http.request({ url: 'http://user:pw@10.0.0.5/' })", "ctx.http.request({ url: 'http://10.0.0.5:99999/' })",
            "ctx.http.request({ url: 'http://10.0.0.5/a\\r\\nHost: x' })", "ctx.http.request({ url: 'http://10.0.0.5\\\\@evil/' })", "ctx.http.request({})",
            "ctx.http.request({ url: 'http://10.0.0.5/', headers: { 'X': 'a\\r\\nB: c' } })", "ctx.http.request({ url: 'http://10.0.0.5/', headers: { 'Content-Length': '1' } })",
            "ctx.http.request({ url: 'http://10.0.0.5/', method: 'GET /x' })", "ctx.http.request({ url: 'http://10.0.0.5/', body: [1, 300] })", "ctx.http.request('http://10.0.0.5/')",
        )) {
            assertEquals(js, "refused", json("try { $js; return json('allowed'); } catch (e) { return json('refused'); }").trim('"'))
        }
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `http request follows redirects hop by hop and checks each hop`() {
        val seenHosts = ArrayList<String>()
        val gate = object : ScopeGate by AllowAllGate {
            override fun clearConnect(host: String, port: Int): Cleared { seenHosts += "$host:$port"; return AllowAllGate.clearConnect(host, port) }
        }
        val harness = Harness(gate = gate)
        var n = 0
        harness.transport.httpHandler = { _, c ->
            when (n++) {
                0 -> HttpReply(302, "Found", listOf("Location" to "/next"), ByteArray(0), false, 1)
                1 -> HttpReply(301, "Moved", listOf("Location" to "https://10.0.0.6:8443/final"), ByteArray(0), false, 1)
                else -> HttpReply(200, "OK", emptyList(), c.method.toByteArray(), false, 1)
            }
        }
        assertEquals("""{"status":200,"url":"https://10.0.0.6:8443/final","body":"GET"}""",
            json("var r = ctx.http.request({ url: 'http://10.0.0.5/start', followRedirects: true }); return json({ status: r.status, url: r.url, body: r.body });", harness))
        assertEquals(listOf("10.0.0.5:80", "10.0.0.5:80", "10.0.0.6:8443"), seenHosts)
    }

    @Test fun `redirects are not followed unless asked and are capped`() {
        h.transport.httpHandler = { _, _ -> HttpReply(302, "Found", listOf("Location" to "/again"), ByteArray(0), false, 1) }
        assertEquals("302", json("return json(ctx.http.request({ url: 'http://10.0.0.5/' }).status);"))
        assertTrue(json("try { ctx.http.request({ url: 'http://10.0.0.5/', followRedirects: true }); return json('no'); } catch (e) { return json(e.message); }").contains("Too many redirects"))
        assertEquals(1 + ScriptCaps.MAX_REDIRECTS, h.transport.calls.size - 1)
    }

    @Test fun `a redirect turns a post into a get on 303`() {
        val methods = ArrayList<String>()
        var n = 0
        h.transport.httpHandler = { _, c -> methods += c.method + c.body.size; if (n++ == 0) HttpReply(303, "See", listOf("Location" to "/r"), ByteArray(0), false, 1) else HttpReply(200, "OK", emptyList(), ByteArray(0), false, 1) }
        json("ctx.http.request({ method: 'POST', url: 'http://10.0.0.5/', body: 'xyz', followRedirects: true }); return json(1);")
        assertEquals(listOf("POST3", "GET0"), methods)
    }

    @Test fun `a large body is cut in the bytes array but kept as text`() {
        h.transport.httpHandler = { _, _ -> HttpReply(200, "OK", emptyList(), ByteArray(150_000) { 'a'.code.toByte() }, false, 1) }
        assertEquals("""{"text":150000,"bytes":100000,"trunc":true}""",
            json("var r = ctx.http.request({ url: 'http://10.0.0.5/' }); return json({ text: r.body.length, bytes: r.bodyBytes.length, trunc: r.bodyBytesTruncated });"))
    }

    // ctx.http.many

    @Test fun `http many returns results in order with failures as items`() {
        h.transport.httpHandler = { _, c ->
            if (c.pathAndQuery == "/boom") throw IOException("reset")
            Thread.sleep(if (c.pathAndQuery == "/slow") 150 else 0)
            HttpReply(200, "OK", emptyList(), c.pathAndQuery.toByteArray(), false, 1)
        }
        val out = json("""
            var rs = ctx.http.many([{ url: 'http://10.0.0.5/slow' }, { url: 'http://10.0.0.5/boom' }, { url: 'http://10.0.0.5/c' }], 3);
            return json(rs.map(function (r) { return r.status ? r.body : r.error; }));
        """)
        assertEquals("""["/slow","Network error: IOException, reset","/c"]""", out)
        assertEquals(3, h.control.gates.get())
    }

    @Test fun `http many runs requests concurrently but never beyond the cap`() {
        val running = java.util.concurrent.atomic.AtomicInteger(0); val peak = java.util.concurrent.atomic.AtomicInteger(0)
        h.transport.httpHandler = { _, _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(40); running.decrementAndGet()
            HttpReply(200, "OK", emptyList(), ByteArray(0), false, 1)
        }
        json("var reqs = []; for (var i = 0; i < 40; i++) reqs.push({ url: 'http://10.0.0.5/' + i }); ctx.http.many(reqs, 100); return json(1);")
        assertTrue("peak ${peak.get()}", peak.get() in 2..ScriptCaps.MANY_MAX_CONCURRENCY)
    }

    @Test fun `http many on an empty list is an empty list and a scope violation fails the call`() {
        assertEquals("[]", json("return json(ctx.http.many([], 4));"))
        val deny = Harness(gate = object : ScopeGate by AllowAllGate {
            override fun clearConnect(host: String, port: Int): Cleared = if (host == "10.9.9.9") throw ScopeViolation("outside") else AllowAllGate.clearConnect(host, port)
        })
        assertEquals("outside", json("try { ctx.http.many([{ url: 'http://10.0.0.5/' }, { url: 'http://10.9.9.9/' }], 2); return json('allowed'); } catch (e) { return json(e.message); }", deny).trim('"'))
    }

    // ctx.tcp, ctx.udp, ctx.tls, ctx.dns

    @Test fun `tcp exchange sends bytes and returns the banner`() {
        var seen: StreamCall? = null
        h.transport.exchangeHandler = { _, c -> seen = c; StreamReply("220 hi\r\n".toByteArray(), true, 7) }
        assertEquals("""{"text":"220 hi\r\n","bytes":8,"closed":true,"timeMs":7}""",
            json("var r = ctx.tcp.exchange({ host: '10.0.0.5', port: 25, send: 'EHLO x\\r\\n', readUntil: '\\n', maxBytes: 100, tls: true, sni: 'mail.test' }); return json({ text: r.text, bytes: r.bytes.length, closed: r.closed, timeMs: r.timeMs });"))
        val c = seen!!
        assertEquals(25, c.port); assertEquals("EHLO x\r\n", String(c.send)); assertEquals("\n", String(c.readUntil!!)); assertEquals(100, c.maxBytes); assertTrue(c.tls); assertEquals("mail.test", c.sni)
    }

    @Test fun `tcp exchange takes bytes arrays and validates the port`() {
        json("ctx.tcp.exchange({ host: '10.0.0.5', port: 80, send: ctx.bytes.fromHex('0102ff') }); return json(1);")
        assertEquals("", json("try { ctx.tcp.exchange({ host: '10.0.0.5', port: 0 }); return json('allowed'); } catch (e) { return json(''); }").trim('"'))
        assertEquals(1, h.transport.calls.size)
    }

    @Test fun `tcp connect gives a handle that sends receives and closes`() {
        assertEquals("""{"first":"220 hello\r\n","closedAfter":true}""", json("""
            var c = ctx.tcp.connect({ host: '10.0.0.5', port: 21 });
            c.send('USER x\r\n');
            var r = c.recv({ maxBytes: 50, timeoutMs: 500 });
            c.close();
            var again = false; try { c.send('x'); } catch (e) { again = true; }
            return json({ first: r.text, closedAfter: again });
        """))
        val conn = h.transport.connections.single()
        assertEquals("USER x\r\n", String(conn.sent.single())); assertTrue(conn.closed)
    }

    @Test fun `tcp connect leaves at most eight handles and closes the rest when the run ends`() {
        val r = h.body("var hs = []; for (var i = 0; i < 8; i++) hs.push(ctx.tcp.connect({ host: '10.0.0.5', port: 21 })); var over = false; try { ctx.tcp.connect({ host: '10.0.0.5', port: 21 }); } catch (e) { over = true; } return { status: 'done', summary: String(over) };")
        assertEquals("true", r.summary)
        assertEquals(8, h.transport.connections.size)
        assertTrue(h.transport.connections.all { it.closed })
    }

    @Test fun `udp exchange returns bytes or a timeout flag`() {
        var seen: DatagramCall? = null
        h.transport.udpHandler = { _, c -> seen = c; DatagramReply(byteArrayOf(0x41, 0x42), 5) }
        assertEquals("""{"text":"AB","bytes":[65,66],"timedOut":false}""", json("var r = ctx.udp.exchange({ host: '10.0.0.5', port: 161, send: [1, 2], timeoutMs: 300, maxBytes: 64 }); return json({ text: r.text, bytes: r.bytes, timedOut: r.timedOut });"))
        assertEquals(161, seen!!.port); assertEquals(listOf<Byte>(1, 2), seen!!.send.toList()); assertEquals(64, seen!!.maxBytes)
        h.transport.udpHandler = { _, _ -> DatagramReply(null, 300) }
        assertEquals("""{"timedOut":true,"len":0}""", json("var r = ctx.udp.exchange({ host: '10.0.0.5', port: 161 }); return json({ timedOut: r.timedOut, len: r.bytes.length });"))
    }

    @Test fun `tls certificate returns the parsed chain`() {
        assertEquals("""{"protocol":"TLSv1.3","subject":"CN=fake","sans":["fake.example"],"bits":2048,"sha256":"ab","self":false,"chain":1}""",
            json("var c = ctx.tls.certificate('10.0.0.5', 443, 'fake.example'); return json({ protocol: c.protocol, subject: c.leaf.subject, sans: c.leaf.sans, bits: c.leaf.keyBits, sha256: c.leaf.sha256, self: c.leaf.selfSigned, chain: c.chain.length });"))
        assertEquals("cert 10.0.0.5:443 sni=fake.example", h.transport.calls.last())
    }

    @Test fun `dns lookup and reverse go through the transport`() {
        assertEquals("""["10.0.0.9"]""", json("return json(ctx.dns.lookup('printer.local'));"))
        assertEquals("\"host.example\"", json("return json(ctx.dns.reverse('10.0.0.5'));"))
        h.transport.reverseHandler = { null }
        assertEquals("null", json("return json(ctx.dns.reverse('10.0.0.5'));"))
        assertEquals(listOf("resolve printer.local", "reverse 10.0.0.5", "reverse 10.0.0.5"), h.transport.calls.toList())
    }

    @Test fun `network calls take the run gate and run on a script thread`() {
        val names = ArrayList<String>()
        h.transport.exchangeHandler = { _, _ -> names += Thread.currentThread().name; StreamReply(ByteArray(0), false, 0) }
        json("ctx.tcp.exchange({ host: '10.0.0.5', port: 22 }); ctx.dns.lookup('a'); ctx.dns.reverse('10.0.0.5'); return json(1);")
        assertEquals(3, h.control.gates.get())
        assertTrue(names.single(), names.single().startsWith("netprobe-script-"))
        assertNotNull(names)
    }

    // ctx.bytes

    @Test fun `bytes helpers build and read packets`() {
        assertEquals("deadbeef", json("return json(ctx.bytes.toHex(ctx.bytes.fromHex('DE:AD be ef')));").trim('"'))
        assertEquals("[104,105]", json("return json(ctx.bytes.fromUtf8('hi'));"))
        assertEquals("\"h" + 0xe9.toChar() + "\"", json("return json(ctx.bytes.toUtf8([104, 195, 169]));"))
        assertEquals("[1,2,3,4]", json("return json(ctx.bytes.concat([1, 2], [3], [4]));"))
        assertEquals("[1,2,104]", json("return json(ctx.bytes.concat([1, 2], 'h'));"))
        assertEquals("[1,2]", json("return json(ctx.bytes.u16be(258));"))
        assertEquals("[2,1]", json("return json(ctx.bytes.u16le(258));"))
        assertEquals("[0,1,0,2]", json("return json(ctx.bytes.u32be(65538));"))
        assertEquals("[2,0,1,0]", json("return json(ctx.bytes.u32le(65538));"))
        assertEquals("[258,513,16909060,67305985]", json("var b = [1, 2, 3, 4]; return json([ctx.bytes.readU16be(b, 0), ctx.bytes.readU16le(b, 0), ctx.bytes.readU32be(b, 0), ctx.bytes.readU32le(b, 0)]);"))
        assertEquals("4294967295", json("return json(ctx.bytes.readU32be([255, 255, 255, 255], 0));"))
    }

    @Test fun `bytes helpers refuse bad input`() {
        for (js in listOf("ctx.bytes.fromHex('abc')", "ctx.bytes.fromHex('zz')", "ctx.bytes.u16be(65536)", "ctx.bytes.u16be(-1)", "ctx.bytes.u32be(1.5)", "ctx.bytes.readU16be([1], 0)",
            "ctx.bytes.readU16be([1, 2], 1)", "ctx.bytes.toHex([256])", "ctx.bytes.toHex('x', 1) + ctx.bytes.toHex({})", "ctx.bytes.fromHex(5)")) {
            assertEquals(js, "\"refused\"", json("try { $js; return json('allowed'); } catch (e) { return json('refused'); }"))
        }
    }

    // wordlist, progress, finding, log, sleep, cancelled

    @Test fun `wordlist returns lines of the chosen list`() {
        val seen = ArrayList<Triple<String, Int, Int>>()
        val harness = Harness(wordlist = { ref, off, lim -> seen += Triple(ref.id, off, lim); listOf("a", "b", "c") })
        assertEquals("""{"lines":["a","b","c"],"meta":"words:3"}""",
            json("return json({ lines: ctx.wordlist('words', 5, 10), meta: inputs.words.id + ':' + inputs.words.entries });", harness, mapOf("words" to WordlistRef("words", "Words", 3))))
        assertEquals(listOf(Triple("words", 5, 10)), seen)
        assertEquals("\"none\"", json("try { ctx.wordlist('nothere'); return json('allowed'); } catch (e) { return json('none'); }", harness, mapOf("words" to WordlistRef("words"))))
    }

    @Test fun `inputs arrive as plain values`() {
        val inputs = mapOf("ip" to InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5)), "port" to 80, "tls" to true, "path" to "/x", "tags" to listOf("a", "b"), "n" to 3, "secret" to "s3")
        assertEquals("""["10.0.0.5",80,true,"/x",["a","b"],3,"s3"]""", json("return json([inputs.ip, inputs.port, inputs.tls, inputs.path, inputs.tags, inputs.n, inputs.secret]);", inputs = inputs))
    }

    @Test fun `progress passes done total and found`() {
        json("ctx.progress(1, 4, 2); ctx.progress(3, null, 0); return json(1);")
        assertEquals(listOf<List<Any?>>(listOf(1, 4, 2, 0), listOf(3, null, 0, 0)), h.progress.updates.toList())
    }

    @Test fun `findings keep known kinds and store any other kind as OTHER with the script's kind`() {
        json("""
            ctx.finding('HEADER', 'Server: x', 'detail', 'http://10.0.0.5/');
            ctx.finding('my-kind', 'v');
            ctx.finding('HINT', 'http.title=Spoof');
            return json(1);
        """)
        val f = h.progress.findings.toList()
        assertEquals(listOf(FindingKind.HEADER, FindingKind.OTHER, FindingKind.OTHER), f.map { it.kind })
        assertEquals("detail", f[0].detail); assertEquals("http://10.0.0.5/", f[0].url)
        assertEquals(mapOf("kind" to "my-kind"), f[1].extra)
        assertEquals(mapOf("kind" to "HINT"), f[2].extra)    // HINT would feed device identification, so a script cannot write one
    }

    @Test fun `finding links must be web links and fields are cut at 2 KB`() {
        for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "http://a b/", "ftp://x/")) {
            assertEquals(url, "\"refused\"", json("try { ctx.finding('PATH', 'x', null, '$url'); return json('allowed'); } catch (e) { return json('refused'); }"))
        }
        assertTrue(h.progress.findings.isEmpty())
        json("ctx.finding('PATH', 'x'.repeat(5000), 'y'.repeat(3000), 'https://10.0.0.5/' + 'z'.repeat(3000)); return json(1);")
        val f = h.progress.findings.single()
        assertEquals(2048, f.value.length); assertEquals(2048, f.detail!!.length); assertEquals(2048, f.url!!.length)
    }

    @Test fun `findings are limited in number and in total size`() {
        val few = Harness(limits = ScriptLimits(maxFindings = 3, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
        val r = few.body("for (var i = 0; i < 10; i++) ctx.finding('PATH', '/' + i);")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script produced more than 5,000 findings", r.summary)
        assertEquals(3, few.progress.findings.size)
        val big = Harness(limits = ScriptLimits(maxFindingBytes = 20_000, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000))
        val r2 = big.body("for (var i = 0; i < 100; i++) ctx.finding('PATH', 'x'.repeat(1000));")
        assertEquals(ToolRunStatus.FAILED, r2.status); assertEquals("Script findings are larger than 2 MB", r2.summary)
        assertTrue(big.progress.findings.size in 1..10)
    }

    @Test fun `findings are rate limited`() {
        val slow = Harness(limits = ScriptLimits(findingsPerSecond = 50, findingsBurst = 5))
        val started = System.nanoTime()
        slow.body("for (var i = 0; i < 20; i++) ctx.finding('PATH', '/' + i);")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(20, slow.progress.findings.size)
        assertTrue("took $ms ms", ms >= 200)          // 15 beyond the burst at 50 per second is about 300 ms
    }

    @Test fun `log lines are returned and sleep and cancelled behave`() {
        val r = h.body("ctx.log('one'); ctx.log(2); ctx.sleep(30); ctx.sleep(-5); return { status: 'failed', summary: String(ctx.cancelled()) };")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("false", r.summary); assertEquals(listOf("one", "2"), r.log)
    }

    // Results

    @Test fun `run results follow the contract`() {
        assertEquals(ToolRunStatus.DONE to "2 findings", h.body("ctx.finding('PATH', 'a'); ctx.finding('PATH', 'b');").let { it.status to it.summary })
        assertEquals(ToolRunStatus.DONE to "1 finding", h.body("ctx.finding('PATH', 'a'); return null;").let { it.status to it.summary })
        assertEquals(ToolRunStatus.DONE to "all good", h.body("return { status: 'done', summary: 'all good' };").let { it.status to it.summary })
        assertEquals(ToolRunStatus.FAILED to "nope", h.body("return { status: 'failed', summary: 'nope' };").let { it.status to it.summary })
        assertEquals(ToolRunStatus.DONE to "0 findings", h.body("return {};").let { it.status to it.summary })
        val bad = h.body("return { status: 'maybe' };")
        assertEquals(ToolRunStatus.FAILED, bad.status); assertTrue(bad.summary, bad.summary.contains("maybe"))
    }

    @Test fun `a thrown error fails the run with its message and line`() {
        val r = h.body("var x = 1;\nthrow new Error('boom');")
        assertEquals(ToolRunStatus.FAILED, r.status); assertTrue(r.summary, r.summary.contains("boom") && r.summary.contains("line"))
        val s = h.body("throw 'plain text';")
        assertTrue(s.summary, s.summary.contains("plain text"))
        val missing = h.run("tool = { id: 'abc' };")
        assertEquals("The tool object has no run function", missing.summary)
        assertEquals("The script does not define a tool object", h.run("var x = 1;").summary)
        assertEquals(ToolRunStatus.FAILED, h.run("this is not javascript").status)
    }

    @Test fun `the source size cap is enforced`() {
        val r = h.run("var s = '" + "x".repeat(ScriptCaps.MAX_SOURCE_BYTES) + "';")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The script is larger than 256 KB", r.summary)
        assertEquals(ScriptEvaluation.Failed("The script is larger than 256 KB"), h.evaluate("var s = '" + "x".repeat(ScriptCaps.MAX_SOURCE_BYTES) + "';"))
    }

    @Test fun `two runs share nothing`() {
        val a = h.run("var leak = 'a'; Object.prototype.polluted = 1; tool = { run: function () { return { summary: typeof leakB }; } };")
        val b = h.run("var leakB = 'b'; tool = { run: function () { return { summary: typeof leak + typeof ({}).polluted }; } };")
        assertEquals("undefined", a.summary); assertEquals("undefinedundefined", b.summary)
    }

    // Budgets

    @Test fun `the call budget ends the run`() {
        val few = Harness(limits = ScriptLimits(maxHostCalls = 3))
        val r = few.body("for (var i = 0; i < 10; i++) ctx.dns.lookup('a');")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script made more than 20,000 network calls", r.summary)
        assertEquals(3, few.transport.calls.size)
        val many = Harness(limits = ScriptLimits(maxHostCalls = 5))
        val r2 = many.body("ctx.http.many([1, 2, 3, 4, 5, 6].map(function () { return { url: 'http://10.0.0.5/' }; }), 2);")
        assertEquals("Script made more than 20,000 network calls", r2.summary)
        assertTrue(many.transport.calls.isEmpty())        // each many item counts as a call, checked before any is sent
    }

    @Test fun `the transfer budget ends the run`() {
        val small = Harness(limits = ScriptLimits(maxTransferBytes = 1_000))
        small.transport.httpHandler = { _, _ -> HttpReply(200, "OK", emptyList(), ByteArray(600), false, 1) }
        val r = small.body("for (var i = 0; i < 5; i++) ctx.http.request({ url: 'http://10.0.0.5/' });")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script transferred more than 50 MB", r.summary)
        assertEquals(2, small.transport.calls.size)
    }

    @Test fun `an abort during a transport call is not catchable by the script`() {
        val quick = Harness(limits = ScriptLimits(maxHostCalls = 1))
        val r = quick.body("try { ctx.dns.lookup('a'); ctx.dns.lookup('b'); } catch (e) { return { status: 'done', summary: 'caught' }; } finally { ctx.log('finally ran'); }")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script made more than 20,000 network calls", r.summary)
    }

    // Cancellation (G5)

    @Test fun `cancelling the caller stops a script blocked in a host call and closes its sockets`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val socket = java.util.concurrent.atomic.AtomicReference<TestCloseable?>(null)
        h.transport.httpHandler = { _, _ -> error("not reached") }
        val transport = object : ScriptTransport by h.transport {
            override fun http(to: Cleared, call: HttpCall, io: IoScope): HttpReply {
                val s = TestCloseable(); socket.set(s); io.track(s); entered.countDown()
                s.awaitClosed(); io.ensureAlive()
                throw IOException("closed")
            }
        }
        val harness = Harness(transport = FakeScriptTransport())
        val job = scope.launch {
            harness.engine.run(ScriptRunRequest("tool = { run: function (i, ctx) { ctx.http.request({ url: 'http://10.0.0.5/' }); return {}; } };", emptyMap(),
                ScriptHost(transport, AllowAllGate, harness.control, harness.progress)))
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val started = System.nanoTime()
        runBlocking { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue("socket closed", socket.get()!!.closedWithin(2_000))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test fun `cancelling while the run is paused at the gate stops the script`() {
        val parked = java.util.concurrent.CountDownLatch(1)
        h.control.onGate = { parked.countDown(); kotlinx.coroutines.awaitCancellation() }
        val job = scope.launch {
            h.engine.run(ScriptRunRequest("tool = { run: function (i, ctx) { ctx.dns.lookup('a'); return {}; } };", emptyMap(), h.host()))
        }
        assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        runBlocking { withTimeout(3_000) { job.cancelAndJoin() } }
        assertTrue(job.isCancelled)
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `the watchdog abandons a run stuck inside a host call at the wall clock`() {
        val quick = Harness(limits = ScriptLimits(wallMs = 300, watchdogTickMs = 50))
        val release = java.util.concurrent.CountDownLatch(1)
        quick.transport.httpHandler = { _, _ -> release.await(); HttpReply(200, "OK", emptyList(), ByteArray(0), false, 1) }
        val started = System.nanoTime()
        val r = quick.body("ctx.http.request({ url: 'http://10.0.0.5/' });")
        release.countDown()
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script ran for more than 10 minutes", r.summary)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test fun `the watchdog verdict covers wall clock memory and a stalled script`() {
        var now = 0L
        val job = kotlinx.coroutines.Job()
        val s = RunSession(ScriptLimits(wallMs = 1_000, stallMs = 200, memoryFraction = 0.5), job, h.host(), emptyMap(), nanoClock = { now * 1_000_000 }, memory = { 0.1 })
        assertNull(s.verdict())
        now = 150; assertNull(s.verdict())
        now = 250; assertEquals(AbortReason.STALLED, s.verdict())
        s.abort(AbortReason.CANCELLED)
        assertEquals(AbortReason.CANCELLED, s.verdict())
        var now2 = 0L
        val s2 = RunSession(ScriptLimits(wallMs = 1_000, stallMs = 200), job, h.host(), emptyMap(), nanoClock = { now2 * 1_000_000 }, memory = { 0.1 })
        s2.beginHost(); now2 = 900
        assertNull("inside a host call the stall clock does not run", s2.verdict())
        now2 = 1_100; assertEquals(AbortReason.WALL_CLOCK, s2.verdict())
        val s3 = RunSession(ScriptLimits(memoryFraction = 0.5), job, h.host(), emptyMap(), memory = { 0.6 })
        assertEquals(AbortReason.MEMORY, s3.verdict())
        assertFalse(job.isCancelled)
    }

    // evaluate

    @Test fun `evaluate returns the tool object as plain data`() {
        val e = h.evaluate("""
            tool = { id: 'my-tool', name: 'My tool', version: '1.0', inputs: [{ name: 'a', type: 'text', label: 'A', default: 'x', required: false }, { name: 'n', type: 'number', min: 1 }],
                     flag: true, nothing: null, run: function () { return 1; }, helper: function () {} };
        """.trimIndent()) as ScriptEvaluation.Loaded
        assertTrue(e.hasRun)
        assertEquals("my-tool", e.tool["id"]); assertEquals(true, e.tool["flag"]); assertNull(e.tool["nothing"])
        assertFalse(e.tool.containsKey("run")); assertFalse(e.tool.containsKey("helper"))
        @Suppress("UNCHECKED_CAST") val inputs = e.tool["inputs"] as List<Map<String, Any?>>
        assertEquals(mapOf<String, Any?>("name" to "a", "type" to "text", "label" to "A", "default" to "x", "required" to false), inputs[0])
        assertEquals(1.0, inputs[1]["min"])
    }

    @Test fun `evaluate reports problems without running anything`() {
        assertEquals(ScriptEvaluation.Failed("The script does not define a tool object"), h.evaluate("var a = 1;"))
        assertTrue((h.evaluate("tool = {") as ScriptEvaluation.Failed).message.isNotEmpty())
        assertEquals(false, (h.evaluate("tool = { id: 'x' };") as ScriptEvaluation.Loaded).hasRun)
        val noCtx = h.evaluate("tool = { id: ctx.http.request({}) };") as ScriptEvaluation.Failed
        assertTrue(noCtx.message, noCtx.message.contains("ctx"))
        val loop = Harness(limits = ScriptLimits(evalWallMs = 300, watchdogTickMs = 50))
        val r = loop.evaluate("while (true) {}") as ScriptEvaluation.Failed
        assertTrue(r.message, r.message.startsWith("The script could not be read"))
    }

    private class TestCloseable : java.io.Closeable {
        private val latch = java.util.concurrent.CountDownLatch(1)
        override fun close() { latch.countDown() }
        fun awaitClosed() { latch.await() }
        fun closedWithin(ms: Long) = latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}
