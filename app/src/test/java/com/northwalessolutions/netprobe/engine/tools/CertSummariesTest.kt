package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** spec 6.2 and 12.1: every finding, every flag, every key type and the summary from the committed keytool fixtures. */
class CertSummariesTest {
    private val dir = File("src/test/resources/tls")
    private val date: (Long) -> String = { "D$it" }
    private fun leaf(name: String): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12"); File(dir, "$name.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks.getCertificate(ks.aliases().nextElement()) as X509Certificate
    }
    private fun cer(name: String) = File(dir, name).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun details(chain: List<X509Certificate>) = TlsDetails("TLSv1.3", "TLS_AES_128_GCM_SHA256", chain, null)
    private fun findings(name: String, now: Long = leaf(name).notBefore.time + 1) = CertSummaries.of(details(listOf(leaf(name))), now, date)
    private fun value(name: String, kind: FindingKind) = findings(name).first { it.kind == kind }.value

    @Test fun `subject, issuer, signature, fingerprint and session`() {
        val f = findings("rsa2048")
        assertEquals("CN=rsa.lan,O=Synology Inc.,C=TW", f.first { it.kind == FindingKind.CERT_SUBJECT }.value)
        assertEquals("Subject", f.first { it.kind == FindingKind.CERT_SUBJECT }.detail)
        assertEquals("CN=rsa.lan,O=Synology Inc.,C=TW", f.first { it.kind == FindingKind.CERT_ISSUER }.value)
        assertEquals("SHA256withRSA", f.first { it.kind == FindingKind.CERT_SIGNATURE }.value)
        assertTrue(f.first { it.kind == FindingKind.CERT_FINGERPRINT }.value.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals("TLSv1.3 TLS_AES_128_GCM_SHA256", f.first { it.kind == FindingKind.TLS_SESSION }.value)
    }
    @Test fun `every key type is named`() {
        assertEquals("RSA 2048 bit", value("rsa2048", FindingKind.CERT_KEY))
        assertEquals("RSA 1024 bit", value("rsa1024_weak", FindingKind.CERT_KEY))
        assertEquals("EC 256 bit", value("ec_p256", FindingKind.CERT_KEY))
        assertEquals("DSA 2048 bit", value("dsa2048", FindingKind.CERT_KEY))
        assertEquals("Ed25519", value("ed25519", FindingKind.CERT_KEY))
    }
    @Test fun `the three flags`() {
        assertTrue(findings("rsa2048").any { it.kind == FindingKind.FLAG && it.value == "Self-signed" && it.detail == "Certificate" })
        assertTrue(findings("rsa1024_weak").any { it.kind == FindingKind.FLAG && it.value == "RSA key under 2048 bit" })
        assertTrue(findings("sha1").any { it.kind == FindingKind.FLAG && it.value == "Signed with SHA-1" })
        assertFalse(findings("rsa2048").any { it.kind == FindingKind.FLAG && it.value != "Self-signed" })
    }
    @Test fun `dns and ip sans each become a finding and a hint`() {
        val f = findings("sans")
        assertEquals(listOf("unifi.local" to "Alternative name (DNS)", "unifi" to "Alternative name (DNS)", "10.0.4.1" to "Alternative name (IP)"),
            f.filter { it.kind == FindingKind.CERT_SAN }.map { it.value to it.detail })
        assertEquals(listOf("tls.subject=CN=unifi.local,O=Ubiquiti Inc.", "tls.issuer=CN=unifi.local,O=Ubiquiti Inc.", "tls.san=unifi.local", "tls.san=unifi", "tls.san=10.0.4.1"),
            f.filter { it.kind == FindingKind.HINT }.map { it.value })
    }
    @Test fun `validity reads valid, expired, not yet valid and expires in N days`() {
        val c = leaf("rsa2048"); val day = 86_400_000L
        fun detail(now: Long) = CertSummaries.of(details(listOf(c)), now, date).first { it.kind == FindingKind.CERT_VALIDITY }.detail
        assertEquals("D${c.notBefore.time} to D${c.notAfter.time}", findings("rsa2048").first { it.kind == FindingKind.CERT_VALIDITY }.value)
        assertEquals("Valid", detail(c.notBefore.time + day))
        assertEquals("Not yet valid", detail(c.notBefore.time - day))
        assertEquals("Expires in 12 days", detail(c.notAfter.time - 12 * day))
        assertEquals("Expires in 1 day", detail(c.notAfter.time - day))
        assertEquals("Expired", CertSummaries.of(details(listOf(leaf("expired"))), System.currentTimeMillis(), date).first { it.kind == FindingKind.CERT_VALIDITY }.detail)
    }
    @Test fun `a two level chain lists the issuer certificate and the leaf is not self signed`() {
        val f = CertSummaries.of(details(listOf(cer("leaf.cer"), cer("ca.cer"))), System.currentTimeMillis(), date)
        assertEquals(listOf("CN=Test Root CA,O=NWS" to "Chain certificate 1 of 1"), f.filter { it.kind == FindingKind.CERT_CHAIN }.map { it.value to it.detail })
        assertFalse(f.any { it.kind == FindingKind.FLAG && it.value == "Self-signed" })
    }
    @Test fun `the summary names the cn, else the first san, and appends self-signed`() {
        val c = leaf("rsa2048")
        assertEquals("rsa.lan, expires D${c.notAfter.time}, self-signed", CertSummaries.summary(details(listOf(c)), 0L, date))
        assertEquals("leaf.lan, expires D${cer("leaf.cer").notAfter.time}", CertSummaries.summary(details(listOf(cer("leaf.cer"), cer("ca.cer"))), 0L, date))
    }
    @Test fun `untrusted certificate text is stripped of control characters and capped`() {
        assertEquals("a b c", CertSummaries.clean("a\u0000 b\u001B\n c"))
        assertEquals(CertSummaries.MAX_TEXT, CertSummaries.clean("x".repeat(5000)).length)
    }
}
