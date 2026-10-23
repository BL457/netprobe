package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.tools.InputsResult
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolOptionsJson
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Golden: the optionsJson of a dir-brute run is byte for byte what the old ToolOptions.DirBrute encoding wrote (tls, serverName, then
 * wordlistId, wordlistName, wordlistEntries, extensions, method, methodUsed, concurrency, timeoutS, statusCodes, baseline), so history,
 * export and the options line read old and new runs alike.
 */
class DirBruteOptionsJsonGoldenTest {
    private val module = TestRegistry.registry().byKey("dir-bruteforce")!!
    private val target = ToolTarget(7, InetAddress.getByName("10.0.3.10") as Inet4Address, 5000, tls = false, serverName = null)
    private fun encode(inputs: ToolInputs, t: ToolTarget = target) = ToolOptionsJson.encode(t, module.descriptor, module.recorded(inputs))

    private val goldenDefaults = "{\"tls\":false,\"serverName\":null,\"wordlistId\":\"builtin:common-short\",\"wordlistName\":\"common-short\",\"wordlistEntries\":312," +
        "\"extensions\":[],\"method\":\"HEAD_THEN_GET\",\"methodUsed\":\"HEAD_THEN_GET\",\"concurrency\":8,\"timeoutS\":3," +
        "\"statusCodes\":[200,204,301,302,307,401,403],\"baseline\":true}"

    @Test fun `the default inputs encode exactly as the old encoding did`() {
        assertEquals(goldenDefaults, encode(DirBruteInputs.of(DirBruteOptions(), "common-short", 312)))
    }
    @Test fun `inputs that came through validate encode the same bytes`() {
        val raw = mapOf("ip" to "10.0.3.10", "port" to "5000")        // every dir-brute input is left to its default
        val v = ToolInputs.validate(module.descriptor, raw) { id -> WordlistRef(id, "common-short", 312) } as InputsResult.Valid
        assertEquals(goldenDefaults, encode(v.inputs))
    }
    @Test fun `chosen options encode as before, with the extension list, GET only and the filter off`() {
        val o = DirBruteOptions(wordlistId = "user:api", extensions = listOf("php", "html"), method = DirMethod.GET_ONLY, concurrency = 4, timeoutS = 5,
            statusCodes = listOf(200, 301), baseline = false)
        assertEquals("{\"tls\":true,\"serverName\":\"nas.lan\",\"wordlistId\":\"user:api\",\"wordlistName\":\"api-paths\",\"wordlistEntries\":137," +
            "\"extensions\":[\"php\",\"html\"],\"method\":\"GET_ONLY\",\"methodUsed\":\"GET_ONLY\",\"concurrency\":4,\"timeoutS\":5," +
            "\"statusCodes\":[200,301],\"baseline\":false}", encode(DirBruteInputs.of(o, "api-paths", 137), target.copy(tls = true, serverName = "nas.lan")))
    }
    @Test fun `the form's raw strings give the same options the sheet would`() {
        val raw = mapOf("ip" to "10.0.3.10", "port" to "5000", "wordlist" to "user:api", "extensions" to ".php, html", "method" to "GET_ONLY", "concurrency" to "4",
            "timeoutS" to "5", "statusCodes" to "301,200", "baseline" to "false")
        val v = ToolInputs.validate(module.descriptor, raw) as InputsResult.Valid
        assertEquals(DirBruteOptions("user:api", listOf("php", "html"), DirMethod.GET_ONLY, 4, 5, listOf(200, 301), false), DirBruteInputs.options(v.inputs))
        assertTrue(module.check(v.inputs).isEmpty())
    }
    @Test fun `bad extensions or no status code are caught by the module check`() {
        fun checked(ext: String, codes: String) = module.check((ToolInputs.validate(module.descriptor, mapOf("ip" to "10.0.3.10", "port" to "5000", "extensions" to ext, "statusCodes" to codes)) as InputsResult.Valid).inputs)
        assertEquals(setOf("extensions"), checked("p h p", "200").keys)
        assertTrue(checked("php", "200").isEmpty())
    }
    @Test fun `the old sheet options survive the round trip through inputs`() {
        val o = DirBruteOptions(extensions = listOf("bak"), concurrency = 16, timeoutS = 10, statusCodes = listOf(403, 404))
        assertEquals(o, DirBruteInputs.options(DirBruteInputs.of(o)))
    }
    @Test fun `no inputs at all run the defaults`() {
        assertEquals(DirBruteOptions(), DirBruteInputs.options(ToolInputs.NONE))
    }
}
