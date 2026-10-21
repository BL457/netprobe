package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** At most this many records are emitted from one heard packet; the rest are dropped (3C spec 3.3, G4). */
const val HEARD_MAX_PER_PACKET = 16

/**
 * Unsolicited mDNS responses heard by the listener (3C spec 3.3). The bytes come from any device on the LAN, so every read is bounds-checked,
 * names stop at MAX_NAME_LABELS labels and DnsWire's jump limit, and a malformed packet yields nothing rather than a partial list. A record
 * is emitted only for a PTR whose instance has an SRV whose target has an A record in the same packet (controller ruling: a name-only
 * sighting is dropped).
 */
object MdnsPacket {
    /** DNS names have at most 127 labels; anything longer is hostile. */
    const val MAX_NAME_LABELS = 128
    private const val HEADER = 12
    private const val RR_FIXED = 10
    private const val QUESTION_FIXED = 4
    private const val FLAGS_AT = 2; private const val QD_AT = 4; private const val AN_AT = 6; private const val NS_AT = 8; private const val AR_AT = 10
    private const val TYPE_AT = 0; private const val RDLEN_AT = 8
    private const val QR_BIT = 0x8000
    private const val TYPE_A = 1; private const val TYPE_PTR = 12; private const val TYPE_TXT = 16; private const val TYPE_AAAA = 28; private const val TYPE_SRV = 33
    private const val A_LEN = 4; private const val AAAA_LEN = 16
    private const val SRV_FIXED = 6; private const val SRV_PORT_AT = 4
    private const val LOCAL_SUFFIX = ".local"
    private const val META_TYPE = "_services._dns-sd._udp"

    private class Srv(val target: String, val port: Int)

    fun parse(b: ByteArray): List<MdnsRecord> {
        if (b.size < HEADER) return emptyList()
        if ((DnsWire.u16(b, FLAGS_AT) and QR_BIT) == 0) return emptyList()   // a query, not an answer
        val questions = DnsWire.u16(b, QD_AT)
        val records = DnsWire.u16(b, AN_AT) + DnsWire.u16(b, NS_AT) + DnsWire.u16(b, AR_AT)
        var pos = HEADER
        repeat(questions) {
            val (_, end) = DnsWire.readName(b, pos, MAX_NAME_LABELS) ?: return emptyList()
            pos = end + QUESTION_FIXED
            if (pos > b.size) return emptyList()
        }
        val ptrs = ArrayList<Pair<String, String>>()            // (service type owner, lower case; instance full name), in packet order
        val srv = HashMap<String, Srv>()
        val txt = HashMap<String, Map<String, String>>()
        val v4 = HashMap<String, Inet4Address>()
        val v6 = HashMap<String, MutableList<String>>()
        repeat(records) {
            val (owner, nameEnd) = DnsWire.readName(b, pos, MAX_NAME_LABELS) ?: return emptyList()
            if (nameEnd + RR_FIXED > b.size) return emptyList()
            val type = DnsWire.u16(b, nameEnd + TYPE_AT)
            val rdLen = DnsWire.u16(b, nameEnd + RDLEN_AT)
            val rd = nameEnd + RR_FIXED
            if (rd + rdLen > b.size) return emptyList()
            val key = owner.lowercase()
            when (type) {
                TYPE_PTR -> { val target = DnsWire.readName(b, rd, MAX_NAME_LABELS) ?: return emptyList(); ptrs += key to target.first }
                TYPE_SRV -> if (rdLen > SRV_FIXED) {
                    val target = DnsWire.readName(b, rd + SRV_FIXED, MAX_NAME_LABELS) ?: return emptyList()
                    srv.putIfAbsent(key, Srv(target.first.lowercase(), DnsWire.u16(b, rd + SRV_PORT_AT)))
                }
                TYPE_TXT -> txt.putIfAbsent(key, DnsWire.parseTxtRdata(b, rd, rd + rdLen))
                TYPE_A -> if (rdLen == A_LEN) v4.putIfAbsent(key, InetAddress.getByAddress(b.copyOfRange(rd, rd + A_LEN)) as Inet4Address)
                TYPE_AAAA -> if (rdLen == AAAA_LEN) {
                    val addr = InetAddress.getByAddress(b.copyOfRange(rd, rd + AAAA_LEN))
                    if (addr is Inet6Address && addr.isLinkLocalAddress) v6.getOrPut(key) { ArrayList() } += addr.hostAddress.substringBefore('%')
                }
            }
            pos = rd + rdLen
        }
        return linked(ptrs, srv, txt, v4, v6)
    }

    private fun linked(ptrs: List<Pair<String, String>>, srv: Map<String, Srv>, txt: Map<String, Map<String, String>>,
                       v4: Map<String, Inet4Address>, v6: Map<String, List<String>>): List<MdnsRecord> {
        val out = ArrayList<MdnsRecord>()
        val seen = HashSet<String>()
        for ((typeOwner, instanceFull) in ptrs) {
            if (out.size >= HEARD_MAX_PER_PACKET) break
            val typeName = typeOwner.removeSuffix(LOCAL_SUFFIX)
            if (typeName == META_TYPE) continue
            val suffix = ".$typeOwner"
            val instKey = instanceFull.lowercase()
            if (!instKey.endsWith(suffix)) continue
            val instance = instanceFull.dropLast(suffix.length)
            if (instance.isEmpty() || !seen.add(instKey)) continue
            val s = srv[instKey] ?: continue
            val ip = v4[s.target] ?: continue
            out += MdnsRecord(ip, instance, MdnsType.normalise(typeName), s.port, txt[instKey].orEmpty(), v6[s.target].orEmpty())
        }
        return out
    }
}
