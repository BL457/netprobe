package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** The device a run is aimed at: its address, and the name the app knows it by (used for SNI and Host), if any. */
class ScopeTarget(val ip: Inet4Address, val name: String?)

/** The network the phone is on: its own address and prefix length. */
class ScopeLan(val phoneIp: Inet4Address, val prefixLength: Int) {
    /** The subnet's directed broadcast address, or null for a /31 or /32 (there is none) or a /0 (it would be the limited broadcast 255.255.255.255). */
    fun directedBroadcast(): Inet4Address? {
        if (prefixLength >= NO_BROADCAST_PREFIX || prefixLength <= 0) return null
        val v = SubnetMath.of(phoneIp, prefixLength).network or (-1 ushr prefixLength)
        return InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())) as Inet4Address
    }
    private companion object { const val NO_BROADCAST_PREFIX = 31 }
}

/**
 * The network scope of a script (spec D4, rulings G2 and G3), consulted by every transport call through [ScopeGate].
 *  - TARGET: the run's device only. Its own name is accepted and never resolved; the connection goes to the device's address.
 *  - LAN: an address inside the phone's current subnet, minus this phone, the network address, the broadcast address, loopback and multicast.
 *  - ANY: any unicast address except loopback, unspecified, multicast, broadcast and reserved space, and except this phone.
 * Host literals must be canonical: a dotted quad with decimal octets and no leading zeros. Decimal, octal, hex, short forms and IPv4-mapped IPv6
 * are refused, so the text the script wrote is the address that gets checked. A name is resolved once, here, by [resolver], and the first
 * answer that passes the scope is the one address the transport connects to: nothing is resolved a second time, so a name cannot change
 * its answer between the check and the connection. The current subnet's directed broadcast address is refused everywhere except [clearDatagram]
 * for a LAN tool that declared broadcast; 255.255.255.255 is refused always. DNS lookups are limited too: under TARGET only the device's own name, under LAN only
 * local-looking names, so DNS cannot carry data out.
 */
class NetworkScopeGuard(
    private val scope: NetworkScope,
    private val target: ScopeTarget?,
    private val lan: ScopeLan?,
    /** The tool declared `broadcast: true` (spec section 11): with scope LAN it may send datagrams to the directed broadcast address. */
    private val broadcast: Boolean = false,
    private val resolver: (ClearedName) -> List<InetAddress>,
) : ScopeGate {

    private sealed interface Host {
        class Literal(val address: InetAddress) : Host
        class Name(val name: String) : Host
    }

    override fun clearConnect(host: String, port: Int): Cleared {
        if (port < MIN_PORT || port > MAX_PORT) throw ScopeViolation(ScriptCopy.PORT_INVALID)
        return when (val h = classify(host)) {
            is Host.Literal -> { requireAllowed(h.address); Cleared(h.address, host) }
            is Host.Name -> clearNamedHost(h.name, host)
        }
    }

    override fun clearDatagram(host: String, port: Int): Cleared {
        if (port < MIN_PORT || port > MAX_PORT) throw ScopeViolation(ScriptCopy.PORT_INVALID)
        val literal = (classify(host) as? Host.Literal)?.address
        val directed = directedBroadcast()
        if (scope == NetworkScope.LAN && broadcast && literal != null && directed != null && literal == directed) return Cleared(literal, host, broadcast = true)
        return clearConnect(host, port)
    }

    private fun directedBroadcast(): Inet4Address? = lan?.directedBroadcast()

    private fun clearNamedHost(name: String, written: String): Cleared {
        if (scope == NetworkScope.TARGET) {
            val t = target ?: throw ScopeViolation(ScriptCopy.NO_DEVICE_IN_SCOPE)
            if (t.name == null || !t.name.equals(name, ignoreCase = true)) throw ScopeViolation(ScriptCopy.TARGET_ONLY)
            return Cleared(t.ip, written)           // the device's own name: no lookup at all
        }
        if (scope == NetworkScope.LAN && lan == null) throw ScopeViolation(ScriptCopy.NO_LAN)
        val answers = resolver(ClearedName(name))
        val picked = answers.firstOrNull { allowed(it) } ?: throw ScopeViolation(ScriptCopy.NAME_OUTSIDE_SCOPE)
        return Cleared(picked, written)
    }

    override fun clearName(name: String): ClearedName {
        return when (val h = classify(name)) {
            is Host.Literal -> { requireAllowed(h.address); ClearedName(name) }
            is Host.Name -> {
                when (scope) {
                    NetworkScope.TARGET -> {
                        val t = target ?: throw ScopeViolation(ScriptCopy.NO_DEVICE_IN_SCOPE)
                        if (t.name == null || !t.name.equals(h.name, ignoreCase = true)) throw ScopeViolation(ScriptCopy.DNS_TARGET_ONLY)
                    }
                    NetworkScope.LAN -> {
                        if (lan == null) throw ScopeViolation(ScriptCopy.NO_LAN)
                        if (!isLocalName(h.name)) throw ScopeViolation(ScriptCopy.DNS_LOCAL_ONLY)
                    }
                    NetworkScope.ANY -> Unit
                }
                ClearedName(h.name)
            }
        }
    }

    override fun clearReverse(ip: String): Cleared {
        val h = classify(ip)
        if (h !is Host.Literal) throw ScopeViolation(ScriptCopy.NOT_AN_ADDRESS)
        requireAllowed(h.address)
        return Cleared(h.address, ip)
    }

    // The address rules

    private fun requireAllowed(a: InetAddress) {
        if (allowed(a)) return
        throw ScopeViolation(when (scope) {
            NetworkScope.TARGET -> if (target == null) ScriptCopy.NO_DEVICE_IN_SCOPE else ScriptCopy.TARGET_ONLY
            NetworkScope.LAN -> when {
                lan == null -> ScriptCopy.NO_LAN
                a == directedBroadcast() -> if (broadcast) ScriptCopy.BROADCAST_UDP_ONLY else ScriptCopy.BROADCAST_NOT_DECLARED
                inLan(a) -> ScriptCopy.ADDRESS_NOT_ALLOWED
                else -> ScriptCopy.LAN_ONLY
            }
            NetworkScope.ANY -> ScriptCopy.ADDRESS_NOT_ALLOWED
        })
    }

    private fun allowed(a: InetAddress): Boolean = when (scope) {
        NetworkScope.TARGET -> target != null && a is Inet4Address && a == target.ip
        NetworkScope.LAN -> lan != null && a is Inet4Address && inLan(a) && usableInLan(a, lan)
        NetworkScope.ANY -> anyUnicast(a) && !(lan != null && a == lan.phoneIp) && a != directedBroadcast()
    }

    private fun inLan(a: InetAddress): Boolean {
        val l = lan ?: return false
        return a is Inet4Address && SubnetMath.of(l.phoneIp, l.prefixLength).contains(a)
    }

    private fun usableInLan(a: Inet4Address, l: ScopeLan): Boolean {
        if (a == l.phoneIp || a.isLoopbackAddress || a.isMulticastAddress || a.isAnyLocalAddress) return false
        val subnet = SubnetMath.of(l.phoneIp, l.prefixLength)
        val v = SubnetMath.toInt(a)
        if (l.prefixLength >= POINT_TO_POINT_PREFIX) return l.prefixLength == POINT_TO_POINT_PREFIX     // a /31 has two hosts, a /32 none
        val broadcast = subnet.network or (-1 ushr l.prefixLength)
        return v != subnet.network && v != broadcast
    }

    private fun anyUnicast(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        if (a is Inet4Address) {
            val b = a.address
            val first = b[0].toInt() and 0xff
            if (first == 0 || first >= RESERVED_FIRST_OCTET) return false           // 0.0.0.0/8, 240.0.0.0/4 and the limited broadcast
            return true
        }
        if (a is Inet6Address) {
            if (a.isLinkLocalAddress) return false
            val b = a.address
            if ((0 until 12).all { b[it].toInt() == 0 }) return false                // ::, ::1 and IPv4-compatible forms
            return true
        }
        return false
    }

    private fun isLocalName(name: String): Boolean {
        if (!name.contains('.')) return true
        return LOCAL_SUFFIXES.any { name.endsWith(it) }
    }

    // Host literals and names

    private fun classify(raw: String): Host {
        if (raw.isEmpty() || raw.length > MAX_HOST || raw.endsWith('.')) throw ScopeViolation(ScriptCopy.HOST_INVALID)
        if (raw.contains(':')) return v6(raw)
        if (NUMERIC.matches(raw)) return v4(raw)
        val name = raw.lowercase()
        if (!NAME.matches(name) || name.split('.').any { it.length > MAX_LABEL }) throw ScopeViolation(ScriptCopy.HOST_INVALID)
        return Host.Name(name)
    }

    private fun v4(raw: String): Host {
        val parts = raw.split('.')
        val octets = parts.map { p -> if (p.length > MAX_OCTET_DIGITS || (p.length > 1 && p.startsWith("0")) || !p.all { it in '0'..'9' }) null else p.toInt().takeIf { it <= OCTET_MAX } }
        if (parts.size != OCTET_COUNT || octets.any { it == null }) throw ScopeViolation(ScriptCopy.NOT_CANONICAL)
        return Host.Literal(InetAddress.getByAddress(ByteArray(OCTET_COUNT) { octets[it]!!.toByte() }))
    }

    private fun v6(raw: String): Host {
        if (!V6_CHARS.matches(raw)) throw ScopeViolation(ScriptCopy.NOT_CANONICAL)
        val address = try { InetAddress.getByName(raw) } catch (_: UnknownHostException) { throw ScopeViolation(ScriptCopy.NOT_CANONICAL) }
        // Java hands back an Inet4Address for a v4-mapped literal; the mapped spelling is refused so the checked text is the connected address.
        if (address !is Inet6Address) throw ScopeViolation(ScriptCopy.NOT_CANONICAL)
        return Host.Literal(address)
    }

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
        const val MAX_HOST = 253
        const val MAX_LABEL = 63
        const val OCTET_COUNT = 4
        const val OCTET_MAX = 255
        const val MAX_OCTET_DIGITS = 3
        const val POINT_TO_POINT_PREFIX = 31
        const val RESERVED_FIRST_OCTET = 240
        /** Labels made only of decimal digits or a 0x prefix: such a host is an address in some spelling, never a name. */
        val NUMERIC = Regex("(0[xX][0-9a-fA-F]*|[0-9]+)(\\.(0[xX][0-9a-fA-F]*|[0-9]+))*")
        val NAME = Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*")
        val V6_CHARS = Regex("[0-9A-Fa-f:]+")
        val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".intranet", ".private")
    }
}
