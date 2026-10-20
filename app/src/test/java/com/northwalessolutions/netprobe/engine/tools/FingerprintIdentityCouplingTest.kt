package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Hint-to-rule coupling: the hints the real tool emits for the seven named products set the vendor, model, os and type of the real identify rules. */
class FingerprintIdentityCouplingTest {
    private val fpRules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText())
    private val identifier = DeviceIdentifier(IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()))
    private val host = ip("10.0.3.9")

    private fun page(body: String) = HttpGet { _, _, path ->
        val b = if (path == "/") body else ""
        HttpResult.Ok(HttpResponse(if (path == "/") 200 else 404, "", emptyList(), b.toByteArray(), false, b.length.toLong()))
    }
    private suspend fun hintsFor(body: String): List<String> {
        val p = CollectingProgress()
        HttpFingerprintTool({ page(body) }, fpRules) { "abcdef012345" }.run(ToolTarget(1, host, 80, false, null), ToolOptions.None, control(), p)
        return p.hints()
    }
    private suspend fun identify(body: String) = identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = hintsFor(body)))

    @Test fun `synology, ubiquiti and philips hue set vendor and type`() = runTest {
        identify("<title>Synology DiskStation</title>").let { assertEquals("Synology", it.vendor); assertEquals(DeviceType.NAS, it.type) }
        identify("<title>UniFi OS</title>").let { assertEquals("Ubiquiti", it.vendor); assertEquals(DeviceType.ROUTER, it.type) }
        identify("<html>hue personal wireless lighting</html>").let { assertEquals("Philips Hue", it.vendor); assertEquals(DeviceType.IOT, it.type) }
    }
    @Test fun `pi-hole, home assistant and proxmox set model and type`() = runTest {
        identify("<title>Pi-hole - Login</title>").let { assertEquals("Pi-hole", it.model); assertEquals(DeviceType.SERVER, it.type) }
        identify("<title>Home Assistant</title>").let { assertEquals("Home Assistant", it.model); assertEquals(DeviceType.SERVER, it.type) }
        identify("<title>pve - Proxmox Virtual Environment</title>").let { assertEquals("Proxmox VE", it.model); assertEquals(DeviceType.SERVER, it.type) }
    }
    @Test fun `openwrt sets os and type`() = runTest {
        identify("<title>OpenWrt - LuCI</title>").let { assertEquals("OpenWrt", it.os); assertEquals(DeviceType.ROUTER, it.type) }
    }
}
