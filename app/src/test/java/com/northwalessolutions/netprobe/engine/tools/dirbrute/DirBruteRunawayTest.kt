package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 2: more than 50 alike findings in the first 200 paths ends the run (spec 5.4.3). */
class DirBruteRunawayTest {
    @Test fun `more than fifty alike in the first two hundred ends the run`() = runTest {
        val p = CollectingProgress()
        val r = tool(Exchange { _, _ -> sized(200, 1024) }, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped: the server answers every path alike", r.summary)
        assertTrue("stopped near the 200 mark, at ${p.lastDone}", p.lastDone in 200..240)
    }
    @Test fun `fifty or fewer alike does not trigger the runaway`() = runTest {
        var i = 0
        val ex = Exchange { method, _ -> if (method == "GET" && i++ < 50) sized(200, 1024) else sized(404, 0) }
        val r = tool(ex, (1..250).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, method = DirMethod.GET_ONLY, concurrency = 1)), TestControl(), CollectingProgress())
        assertEquals("50 found in 250 paths", r.summary)
    }
    @Test fun `alike means within sixty four bytes, spread sizes never trigger`() {
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it) }))
        assertFalse(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it * 100) }))
        assertFalse(DirBruteforceTool.runaway((1..50).map { 200 to 1000L }))
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to null }))
    }
}
