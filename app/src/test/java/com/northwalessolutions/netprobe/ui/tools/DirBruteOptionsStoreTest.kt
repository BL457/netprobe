package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirMethod
import org.junit.Assert.*
import org.junit.Test

/** spec 5.1: saved globally as JSON under tool.dirBrute.options; invalid or absent JSON loads the defaults; values are coerced. */
class DirBruteOptionsStoreTest {
    private var stored: String? = null
    private val store = DirBruteOptionsStore({ stored }, { stored = it })
    @Test fun `absent and invalid json load defaults`() {
        assertEquals(DirBruteOptions(), store.load()); stored = "{ nope"; assertEquals(DirBruteOptions(), store.load())
    }
    @Test fun `a saved option set round trips and out of range values are coerced on load`() {
        val o = DirBruteOptions(wordlistId = "user:abc", extensions = listOf("php"), method = DirMethod.GET_ONLY, concurrency = 4, timeoutS = 5, statusCodes = listOf(200), baseline = false)
        store.save(o); assertEquals(o, store.load())
        stored = stored!!.replace("\"concurrency\":4", "\"concurrency\":99"); assertEquals(16, store.load().concurrency)
        assertEquals("tool.dirBrute.options", DirBruteOptionsStore.KEY)
    }
}
