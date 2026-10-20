package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.util.Date

fun mediumDate(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

/** Pure mapper from one handshake to findings (spec 6.2); every path is pinned by keytool fixtures. Certificate text is untrusted and cleaned. */
object CertSummaries {
    const val EXPIRY_SOON_DAYS = 30
    const val WEAK_RSA_BITS = 2048
    /** Longest certificate-supplied string that reaches a finding. */
    const val MAX_TEXT = 200
    private const val DAY_MS = 86_400_000L
    private const val SAN_DNS = 2
    private const val SAN_IP = 7
    /** SubjectPublicKeyInfo AlgorithmIdentifier OIDs 1.3.101.112 and 1.3.101.113, as DER (RFC 8410). */
    private val ED25519_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x70)
    private val ED448_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x71)

    /** Control characters removed (a name must never carry a newline or escape into a finding or a hint), then capped at [MAX_TEXT]. */
    fun clean(text: String): String = text.filterNot { it.isISOControl() }.take(MAX_TEXT)

    fun of(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): List<Finding> {
        val leaf = details.chain.firstOrNull() ?: return emptyList()
        val subject = clean(leaf.subjectX500Principal.name); val issuer = clean(leaf.issuerX500Principal.name)
        val sans = sans(leaf)
        val out = ArrayList<Finding>()
        out += Finding(FindingKind.CERT_SUBJECT, subject, ToolCopy.SUBJECT)
        out += Finding(FindingKind.CERT_ISSUER, issuer, ToolCopy.ISSUER)
        sans.forEach { (value, ip) -> out += Finding(FindingKind.CERT_SAN, value, if (ip) ToolCopy.SAN_IP else ToolCopy.SAN_DNS) }
        out += Finding(FindingKind.CERT_VALIDITY, ToolCopy.validityRange(formatDate(leaf.notBefore.time), formatDate(leaf.notAfter.time)), validity(leaf, now))
        out += Finding(FindingKind.CERT_KEY, keyText(leaf.publicKey), ToolCopy.PUBLIC_KEY)
        out += Finding(FindingKind.CERT_SIGNATURE, clean(leaf.sigAlgName), ToolCopy.SIGNATURE_ALGORITHM)
        out += Finding(FindingKind.CERT_FINGERPRINT, sha256(leaf), ToolCopy.SHA256_FINGERPRINT)
        val chain = details.chain.drop(1)
        chain.forEachIndexed { i, c -> out += Finding(FindingKind.CERT_CHAIN, clean(c.subjectX500Principal.name), ToolCopy.chainCertificate(i + 1, chain.size)) }
        out += Finding(FindingKind.TLS_SESSION, clean("${details.protocol} ${details.cipherSuite}"), ToolCopy.PROTOCOL_AND_CIPHER)
        if (selfSigned(leaf)) out += Finding(FindingKind.FLAG, ToolCopy.SELF_SIGNED, ToolCopy.CERTIFICATE)
        (leaf.publicKey as? RSAPublicKey)?.let { if (it.modulus.bitLength() < WEAK_RSA_BITS) out += Finding(FindingKind.FLAG, ToolCopy.WEAK_RSA, ToolCopy.PUBLIC_KEY) }
        if (leaf.sigAlgName.contains("SHA1", ignoreCase = true)) out += Finding(FindingKind.FLAG, ToolCopy.SHA1_SIGNED, ToolCopy.SIGNATURE_ALGORITHM)
        out += Finding(FindingKind.HINT, "tls.subject=$subject")
        out += Finding(FindingKind.HINT, "tls.issuer=$issuer")
        sans.forEach { (value, _) -> out += Finding(FindingKind.HINT, "tls.san=$value") }
        return out
    }

    fun summary(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): String {
        val leaf = details.chain.firstOrNull() ?: return ToolCopy.TLS_NO_HANDSHAKE
        val name = commonName(leaf.subjectX500Principal.name) ?: sans(leaf).firstOrNull()?.first ?: clean(leaf.subjectX500Principal.name)
        return ToolCopy.tlsSummary(name, formatDate(leaf.notAfter.time), selfSigned(leaf))
    }

    private fun validity(leaf: X509Certificate, now: Long): String = when {
        now > leaf.notAfter.time -> ToolCopy.EXPIRED
        now < leaf.notBefore.time -> ToolCopy.NOT_YET_VALID
        leaf.notAfter.time - now <= EXPIRY_SOON_DAYS * DAY_MS -> ToolCopy.expiresIn((leaf.notAfter.time - now) / DAY_MS)
        else -> ToolCopy.VALID
    }
    private fun keyText(key: PublicKey): String = when (key) {
        is RSAPublicKey -> ToolCopy.keyRsa(key.modulus.bitLength())
        is ECPublicKey -> ToolCopy.keyEc(key.params.curve.field.fieldSize)
        is DSAPublicKey -> ToolCopy.keyDsa(key.params.p.bitLength())
        else -> when {
            contains(key.encoded ?: ByteArray(0), ED25519_OID) -> ToolCopy.ED25519
            contains(key.encoded ?: ByteArray(0), ED448_OID) -> ToolCopy.ED448
            else -> clean(key.algorithm)
        }
    }
    private fun contains(hay: ByteArray, needle: ByteArray): Boolean = (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
    private fun sans(leaf: X509Certificate): List<Pair<String, Boolean>> =
        runCatching { leaf.subjectAlternativeNames }.getOrNull().orEmpty().mapNotNull { e ->
            val type = e.getOrNull(0) as? Int; val value = (e.getOrNull(1) as? String)?.let { clean(it) }?.ifEmpty { null }
            when { value == null -> null; type == SAN_DNS -> value to false; type == SAN_IP -> value to true; else -> null }
        }
    private fun commonName(x500: String): String? = TlsPeek.subjectField(x500, "CN")?.let { clean(it) }?.ifEmpty { null }
    private fun selfSigned(leaf: X509Certificate): Boolean =
        leaf.issuerX500Principal == leaf.subjectX500Principal && runCatching { leaf.verify(leaf.publicKey); true }.getOrDefault(false)
    private fun sha256(leaf: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":") { "%02X".format(it) }
}
