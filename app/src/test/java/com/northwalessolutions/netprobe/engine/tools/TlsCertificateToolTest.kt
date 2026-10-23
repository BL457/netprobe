package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
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

class TlsCertificateToolTest {
    private val target = ToolTarget(7, ip("10.0.3.9"), 5001, tls = true, serverName = "chnas.local")
    private fun leaf(): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/rsa2048.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks.getCertificate("a") as X509Certificate
    }
    @Test fun `no handshake is FAILED`() = runTest {
        val r = TlsCertificateTool({ _, _ -> null }).run(target, ToolInputs.NONE, control(), CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No TLS handshake on this port", r.summary)
    }
    @Test fun `a handshake lost to a pause is retried once after the resume`() = runTest {
        val c = ToolTestSupport.EpochControl(); var calls = 0
        val r = TlsCertificateTool({ _, name ->
            calls++
            if (calls == 1) { c.epoch++; null } else TlsDetails("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", listOf(leaf()), name)
        }, { leaf().notBefore.time + 1 }).run(target, ToolInputs.NONE, c, CollectingProgress())
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(2, calls)
    }
    @Test fun `a handshake reports its findings, passes the target name and summarises`() = runTest {
        var asked: String? = "unset"
        val p = CollectingProgress()
        val r = TlsCertificateTool({ _, name -> asked = name; TlsDetails("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", listOf(leaf()), name) }, { leaf().notBefore.time + 1 })
            .run(target, ToolInputs.NONE, control(), p)
        assertEquals("chnas.local", asked); assertEquals(ToolRunStatus.DONE, r.status)
        assertTrue(r.summary.startsWith("rsa.lan, expires ")); assertTrue(r.summary.endsWith(", self-signed"))
        assertTrue(p.of(FindingKind.CERT_SUBJECT).isNotEmpty())
    }
}
