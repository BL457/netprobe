package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/** Hint-to-rule coupling: the tls.* hints the real tool emits from real certificates set the vendor and type through the real identify rules. */
class TlsIdentityCouplingTest {
    private val identifier = DeviceIdentifier(IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()))
    private val host = ip("10.0.3.9")
    private fun leaf(name: String): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/$name.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks.getCertificate("a") as X509Certificate
    }
    private suspend fun identify(fixture: String): DeviceIdentity {
        val p = CollectingProgress()
        TlsCertificateTool({ _, _ -> TlsDetails("TLSv1.3", "TLS_AES_128_GCM_SHA256", listOf(leaf(fixture)), null) })
            .run(ToolTarget(1, host, 443, true, null), ToolOptions.None, control(), p)
        return identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = p.hints()))
    }
    @Test fun `a synology subject sets vendor and type`() = runTest {
        identify("rsa2048").let { assertEquals("Synology", it.vendor); assertEquals(DeviceType.NAS, it.type) }
    }
    @Test fun `a ubiquiti issuer sets vendor and type`() = runTest {
        identify("sans").let { assertEquals("Ubiquiti", it.vendor); assertEquals(DeviceType.ROUTER, it.type) }
    }
}
