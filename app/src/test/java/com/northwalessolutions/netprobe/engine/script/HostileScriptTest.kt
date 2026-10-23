package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hostile suite (G1, G4; Gruff review findings 1 and 4). Each test is a script an attacker could write; each must be refused or end
 * harmlessly. "Refused" means the script gets an error or undefined, never a Java object, and the run's outcome is the expected one.
 */
class HostileScriptTest {
    private val h = Harness()

    /** The error message a refused expression produced; [contains] fails with the expression and the actual text. */
    private inner class Refusal(val expr: String, val text: String) {
        fun contains(needle: String): Boolean { assertTrue("$expr gave: $text", text.contains(needle)); return true }
        fun isNotEmpty(): Boolean = text.isNotEmpty()
    }
    private fun thrown(expr: String): Refusal = h.value(expr).let { assertTrue("expected a refusal for $expr, got: $it", it.startsWith("THROWN:")); Refusal(expr, it) }
    private fun refused(expr: String, needle: String) { thrown(expr).contains(needle) }

    // Finding 1: reaching Java

    @Test fun `java globals do not exist`() {
        for (name in listOf("java", "javax", "Packages", "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass", "org", "com", "edu", "net", "Continuation")) {
            assertEquals(name, "undefined", h.value("typeof $name"))
        }
    }

    @Test fun `new JavaAdapter and Packages calls are refused`() {
        assertTrue(thrown("new JavaAdapter(Object, {})").contains("JavaAdapter"))
        assertTrue(thrown("Packages.java.lang.System.getProperty('user.dir')").contains("Packages"))
        assertTrue(thrown("java.lang.Runtime.getRuntime()").contains("java"))
        assertTrue(thrown("importClass(java.io.File)").isNotEmpty())
    }

    @Test fun `Function constructor in every spelling is refused`() {
        assertTrue(thrown("Function('return this')()").contains("not available"))
        assertTrue(thrown("new Function('return this')()").contains("not available"))
        thrown("(function () {}).constructor('return this')()").contains("not available")
        assertTrue(thrown("[].constructor.constructor('return this')()").contains("not available"))
        assertTrue(thrown("({}).constructor.constructor('return this')()").contains("not available"))
        assertTrue(thrown("''.constructor.constructor('return this')()").contains("not available"))
        assertTrue(thrown("ctx.http.request.constructor('return this')()").contains("not available"))
        assertTrue(thrown("Object.getPrototypeOf(function () {}).constructor('return this')()").contains("not available"))
    }

    @Test fun `eval is refused`() {
        assertTrue(thrown("eval('1 + 1')").contains("not available"))
        thrown("(function () { return this; })().eval('1 + 1')").contains("not available")
        assertTrue(thrown("(0, eval)('1 + 1')").contains("not available"))
    }

    @Test fun `getClass and class reflection are absent on every kind of value`() {
        for (expr in listOf("ctx", "ctx.http", "ctx.http.request", "({})", "[]", "'x'", "(1)", "true", "function () {}", "new Error('e')")) {
            assertEquals(expr, "undefined", h.value("typeof ($expr).getClass"))
            assertEquals(expr, "undefined", h.value("typeof ($expr).class"))
            assertEquals(expr, "undefined", h.value("typeof ($expr).javaClass"))
        }
    }

    @Test fun `getter and setter lookup walks and parent or proto links are gone`() {
        for (name in listOf("__defineGetter__", "__defineSetter__", "__lookupGetter__", "__lookupSetter__", "__parent__", "__proto__", "__noSuchMethod__")) {
            assertEquals(name, "undefined", h.value("typeof ({}).$name"))
            assertEquals(name, "undefined", h.value("typeof ctx.$name"))
            assertEquals(name, "undefined", h.value("typeof (function () {}).$name"))
        }
        assertTrue(thrown("({}).__defineGetter__('x', function () { return this; })").isNotEmpty())
        assertEquals("false", h.value("Object.getOwnPropertyNames(this).join(',').indexOf('Packages') >= 0"))
    }

    @Test fun `errors from host calls and from the engine carry no Java exception`() {
        val r = h.body(
            """
            var seen = [];
            try { ctx.http.request({ url: 'not a url' }); } catch (e) { seen.push(typeof e.javaException, typeof e.rhinoException, typeof e.getClass, e instanceof Error); }
            try { null.x; } catch (e) { seen.push(typeof e.javaException, typeof e.rhinoException, typeof e.getClass); }
            try { undefinedFunction(); } catch (e) { seen.push(typeof e.javaException, typeof e.rhinoException); }
            try { ctx.bytes.fromHex('zz'); } catch (e) { seen.push(typeof e.javaException, e.name); }
            return { status: 'done', summary: seen.join(',') };
            """.trimIndent())
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("undefined,undefined,undefined,true,undefined,undefined,undefined,undefined,undefined,undefined,Error", r.summary)
    }

    @Test fun `host objects cannot be replaced, deleted or extended by the script`() {
        val r = h.body(
            """
            var before = ctx.http.request;
            var refusals = 0;
            try { ctx.http.request = function () { return 'owned'; }; } catch (e) { refusals++; }
            try { ctx.http = null; } catch (e) { refusals++; }
            try { ctx.extra = 1; } catch (e) { refusals++; }
            try { delete ctx.dns; } catch (e) { refusals++; }
            var kept = (ctx.http.request === before) && typeof ctx.extra === 'undefined' && typeof ctx.dns.lookup === 'function';
            return { status: 'done', summary: kept + ':' + refusals };
            """.trimIndent())
        assertEquals("true:4", r.summary)
    }

    @Test fun `ctx exposes exactly the documented members`() {
        fun names(path: String) = h.value("Object.getOwnPropertyNames($path).sort().join(',')")
        assertEquals("bytes,cancelled,dns,finding,http,log,progress,regex,sleep,tcp,tls,udp,wordlist", names("ctx"))
        assertEquals("many,request", names("ctx.http"))
        assertEquals("connect,exchange", names("ctx.tcp"))
        assertEquals("exchange", names("ctx.udp"))
        assertEquals("certificate", names("ctx.tls"))
        assertEquals("lookup,reverse", names("ctx.dns"))
        assertEquals("concat,fromHex,fromUtf8,readU16be,readU16le,readU32be,readU32le,toHex,toUtf8,u16be,u16le,u32be,u32le", names("ctx.bytes"))
        assertEquals("exec,matchAll,replace,split,test", names("ctx.regex"))
    }

    @Test fun `every value a host call returns is a plain script value`() {
        val r = h.body(
            """
            var out = [];
            var rs = [ctx.http.request({ url: 'http://10.0.0.5/' }), ctx.tcp.exchange({ host: '10.0.0.5', port: 22 }), ctx.udp.exchange({ host: '10.0.0.5', port: 161 }),
                      ctx.tls.certificate('10.0.0.5', 443), ctx.dns.lookup('a'), ctx.bytes.fromHex('0a'), ctx.regex.exec('a', 'a', '')];
            rs.forEach(function (v) { out.push(Object.prototype.toString.call(v)); });
            return { status: 'done', summary: out.join(',') };
            """.trimIndent())
        assertEquals("[object Object],[object Object],[object Object],[object Object],[object Array],[object Array],[object Object]", r.summary)
    }

    @Test fun `the engine never hands a script a wrapped Java object even when asked to`() {
        // The WrapFactory is the last line: a direct request to wrap a Java object throws.
        val cx = Sandbox.Factory().enterContext()
        try {
            val scope = Sandbox.newScope(cx, ScriptLimits.DEFAULT)
            val e = runCatching { Sandbox.DenyWrapFactory.wrapAsJavaObject(cx, scope, java.util.ArrayList<Any>(), null as Class<*>?) }.exceptionOrNull()
            assertTrue(e is org.mozilla.javascript.RhinoException)
            assertEquals(false, Sandbox.DenyWrapFactory.isJavaPrimitiveWrap)
        } finally { org.mozilla.javascript.Context.exit() }
    }

    // Finding 4: native denial of service

    @Test fun `RegExp is not available in any form`() {
        assertEquals("undefined", h.value("typeof RegExp"))
        refused("new RegExp('(a+)+')", "RegExp")
        refused("'abc'.search('b')", "ctx.regex")
        refused("'abc'.match('b')", "ctx.regex")
        // A regex literal is compiled when the script loads, so the whole script is refused with a pointer to ctx.regex.
        for (literal in listOf("/a+/.test('aaa')", "'abc'.match(/b/)", "'abc'.replace(/b/g, 'x')", "'a,b'.split(/,/)")) {
            val r = h.body("return { status: 'done', summary: String($literal) };")
            assertEquals(literal, ToolRunStatus.FAILED, r.status)
            assertTrue(r.summary, r.summary.contains("ctx.regex"))
        }
    }

    @Test fun `plain string split and replace still work`() {
        assertEquals("a|b|c", h.value("'a,b,c'.split(',').join('|')"))
        assertEquals("a,b,c", h.value("'abc'.split('').join(',')"))
        assertEquals("2", h.value("'a,b,c'.split(',', 2).length"))
        assertEquals("axc", h.value("'abc'.replace('b', 'x')"))
        assertEquals("xbc", h.value("'abc'.replace('a', 'x')"))
        assertEquals("-abc", h.value("'abc'.replace('', '-')"))
        assertEquals("a-b c", h.value("'a b c'.replace(' ', '-')"))
    }

    @Test fun `catastrophic backtracking through ctx regex stops at the step limit`() {
        val evil = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab"
        val started = System.nanoTime()
        thrown("ctx.regex.test('(a+)+\$', '$evil', '')").contains("too many steps")
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test fun `ctx regex matches the documented shapes`() {
        assertEquals("true", h.value("ctx.regex.test('^a+\$', 'aaa', '')"))
        assertEquals("true", h.value("ctx.regex.test('ABC', 'xabcx', 'i')"))
        assertEquals("abc,1,x", h.value("(function(){ var m = ctx.regex.exec('(a)b(c)', 'xabcx', ''); return [m.match, m.index, 'x'].join(','); })()"))
        assertEquals("a,b", h.value("ctx.regex.matchAll('[ab]', 'a-b', '', 10).map(function (m) { return m.match; }).join(',')"))
        assertEquals("x-x", h.value("ctx.regex.replace('[ab]', 'a-b', 'x', '')"))
        assertEquals("a,b,c", h.value("ctx.regex.split('[;,]', 'a;b,c', 10).join(',')"))
        assertEquals("null", h.value("ctx.regex.exec('z', 'abc', '')"))
        assertTrue(thrown("ctx.regex.test('(', 'a', '')").contains("not valid"))
        assertTrue(thrown("ctx.regex.test('a', 'a', 'q')").contains("flag"))
    }

    @Test fun `huge strings are refused by the builtins`() {
        assertTrue(thrown("'x'.repeat(Math.pow(2, 30))").contains("Invalid string length"))
        assertTrue(thrown("'x'.repeat(1000001)").contains("Invalid string length"))
        assertTrue(thrown("'x'.padStart(2000000, 'y')").contains("Invalid string length"))
        assertTrue(thrown("'x'.padEnd(2000000, 'y')").contains("Invalid string length"))
        assertTrue(thrown("(function () { var s = 'x'.repeat(600000); return s.concat(s); })()").contains("Invalid string length"))
        assertTrue(thrown("(function () { var s = 'x'.repeat(600000); return [s, s].join(''); })()").contains("Invalid string length"))
        assertTrue(thrown("(function () { var s = 'x'.repeat(10000); return s.replace('x', 'y'.repeat(900000)) + s.replace('', 'z'.repeat(900000)); })()").contains("Invalid string length"))
        assertEquals("1000000", h.value("'x'.repeat(1000000).length"))
    }

    @Test fun `host calls refuse a string beyond the cap`() {
        val r = h.body("var s = 'x'.repeat(1000000); s = s + 'y'; try { ctx.log(s); ctx.bytes.fromUtf8(s); } catch (e) { return { status: 'done', summary: e.message }; } return { status: 'done', summary: 'accepted' };")
        assertTrue(r.summary, r.summary.contains("Invalid string length"))
    }

    @Test fun `huge arrays are refused by the builtins`() {
        assertTrue(thrown("new Array(1e9)").contains("Invalid array length"))
        assertTrue(thrown("Array(1e9)").contains("Invalid array length"))
        assertTrue(thrown("(function () { var a = []; a.length = 1e9; return a.join(','); })()").contains("Invalid array length"))
        assertTrue(thrown("(function () { var a = []; a.length = 1e9; return a.map(function (x) { return x; }); })()").contains("Invalid array length"))
        assertTrue(thrown("(function () { var a = new Array(100000); return a.concat(a); })()").contains("Invalid array length"))
        assertTrue(thrown("(function () { var a = new Array(100000); a.push(1); return a.length; })()").contains("Invalid array length"))
        assertEquals("100000", h.value("new Array(100000).length"))
        assertEquals("true", h.value("[] instanceof Array && Array.isArray([]) && new Array(3).length === 3 && [].constructor === Array"))
    }

    @Test fun `a join of huge elements is refused before it allocates`() {
        assertTrue(thrown("(function () { var s = 'x'.repeat(1000000); var a = []; for (var i = 0; i < 3; i++) a.push(s); return a.join(','); })()").contains("Invalid string length"))
    }

    // Budgets: the error scripts cannot catch

    @Test fun `an endless loop is ended by the wall clock and try finally cannot absorb it`() {
        val quick = Harness(limits = ScriptLimits(wallMs = 400, watchdogTickMs = 50))
        val started = System.nanoTime()
        val r = quick.body("var n = 0; for (;;) { try { n++; } catch (e) { } finally { n++; } }")
        assertEquals(ToolRunStatus.FAILED, r.status)
        assertEquals("Script ran for more than 10 minutes", r.summary)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test fun `a script that catches everything around a host call still ends`() {
        val quick = Harness(limits = ScriptLimits(wallMs = 400, watchdogTickMs = 50))
        val r = quick.body("for (;;) { try { ctx.sleep(20); } catch (e) { } finally { } }")
        assertEquals(ToolRunStatus.FAILED, r.status)
        assertEquals("Script ran for more than 10 minutes", r.summary)
    }

    @Test fun `an instruction budget ends a busy loop`() {
        val quick = Harness(limits = ScriptLimits(maxInstructions = 200_000))
        val r = quick.body("var i = 0; while (true) { i++; }")
        assertEquals(ToolRunStatus.FAILED, r.status)
        assertEquals("Script used too much processing", r.summary)
    }

    @Test fun `deep recursion fails the run instead of the process`() {
        val r = h.body("function f(n) { return f(n + 1) + 1; } return { status: 'done', summary: String(f(0)) };")
        assertEquals(ToolRunStatus.FAILED, r.status)
    }

    @Test fun `memory pressure ends the run`() {
        val pressure = Harness(memory = MemoryProbe { 0.99 })
        val r = pressure.body("var n = 0; while (true) { n++; }")
        assertEquals(ToolRunStatus.FAILED, r.status)
        assertEquals("Script used too much memory", r.summary)
    }

    @Test fun `the cancel poll lets a polite script leave but the run is still marked failed after a budget`() {
        val quick = Harness(limits = ScriptLimits(wallMs = 300, watchdogTickMs = 50))
        val r = quick.body("while (!ctx.cancelled()) { } return { status: 'done', summary: 'left politely' };")
        assertEquals(ToolRunStatus.FAILED, r.status)
        assertFalse(r.summary.contains("politely"))
    }
}
