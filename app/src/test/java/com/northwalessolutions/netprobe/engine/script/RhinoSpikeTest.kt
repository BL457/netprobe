package com.northwalessolutions.netprobe.engine.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function

/** Rhino spike (tools platform G13): the artifact loads, and an ES5 script runs with bytecode generation forced off. */
class RhinoSpikeTest {
    private fun <T> inContext(block: (Context) -> T): T {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1                    // interpreted mode: Dalvik cannot load generated class files
            cx.languageVersion = Context.VERSION_ES6
            return block(cx)
        } finally { Context.exit() }
    }

    @Test fun `a script evaluates in interpreted mode`() {
        val result = inContext { cx ->
            val scope = cx.initSafeStandardObjects()
            cx.evaluateString(scope, "var t = { run: function (a, b) { return [a, b].map(function (x) { return x * 2; }).join(','); } }; t.run(2, 3)", "spike", 1, null)
        }
        assertEquals("4,6", Context.toString(result))
    }

    @Test fun `calling a function value from Kotlin works and the interpreter is in use`() {
        inContext { cx ->
            val scope = cx.initSafeStandardObjects()
            val f = cx.evaluateString(scope, "(function (n) { var s = 0; for (var i = 0; i < n; i++) s += i; return s; })", "spike", 1, null) as Function
            assertEquals(4950.0, Context.toNumber(f.call(cx, scope, scope, arrayOf<Any>(100))), 0.0)
            assertEquals(-1, cx.optimizationLevel)
        }
    }

    @Test fun `the message bundle is on the classpath so error text is not a bare key`() {
        val msg = inContext { cx ->
            val scope = cx.initSafeStandardObjects()
            try { cx.evaluateString(scope, "null.x", "spike", 1, null); "" } catch (e: org.mozilla.javascript.RhinoException) { e.message.orEmpty() }
        }
        assertTrue(msg, msg.contains("Cannot read") || msg.contains("null"))
        assertTrue(msg, !msg.startsWith("msg."))
    }
}
