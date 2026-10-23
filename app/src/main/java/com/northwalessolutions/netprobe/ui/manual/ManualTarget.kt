package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.end
import com.northwalessolutions.netprobe.netenv.start
import java.net.Inet4Address

/** The manual tools form checked (3C spec 4.2); [ip] and [port] are null whenever their field is not usable. */
data class ManualCheck(val ip: Inet4Address?, val port: Int?, val ipError: String?, val portError: String?) {
    val canRun: Boolean get() = ip != null && port != null
}

/**
 * Pure validation for "Run a tool" (3C spec 4.2, ruling 3): any unicast IPv4 address, public included, except the reserved blocks and the
 * home range's own network and broadcast addresses. No name lookup. Errors stay hidden until the first Run tap ([attempted]).
 */
object ManualTarget {
    const val PORT_MIN = 1
    const val PORT_MAX = 65_535
    /** Identify all services takes no port: the run reads the address stored open ports; 0 stands for all of them. */
    const val ALL_PORTS = 0
    private const val MAX_PORT_DIGITS = 5
    private const val UNSIGNED_MASK = 0xFFFFFFFFL
    /** Below /31 a subnet has a network and a broadcast address (RFC 3021 point-to-point links have neither). */
    private const val LAST_PREFIX_WITH_BROADCAST = 30
    /** 0.0.0.0/8 "this network", 127.0.0.0/8 loopback, 224.0.0.0/4 multicast, 240.0.0.0/4 reserved (255.255.255.255 included). */
    private val RESERVED = listOf(Subnet(0x00000000, 8), Subnet(0x7F000000, 8), Subnet(0xE0000000.toInt(), 4), Subnet(0xF0000000.toInt(), 4))

    fun fixedPort(tool: String): Int? = when (tool) {
        ToolKeys.SNMP_WALK -> ToolApplicability.SNMP_PORT
        ToolKeys.SMB_SESSION -> ToolApplicability.SMB_PORT
        ToolKeys.IDENTIFY_ALL -> ALL_PORTS
        else -> null
    }

    fun check(ipText: String, portText: String, tool: String, home: Subnet?, attempted: Boolean): ManualCheck {
        val ipTrim = ipText.trim(); val portTrim = portText.trim()
        val parsed = Ipv4.parse(ipTrim)
        val ipProblem = when {
            parsed == null -> ManualCopy.ERR_IP
            reserved(parsed, home) -> ManualCopy.ERR_ADDRESS
            else -> null
        }
        val fixed = fixedPort(tool)
        val port = fixed ?: portTrim.takeIf { it.length in 1..MAX_PORT_DIGITS && it.all(Char::isDigit) }?.toInt()?.takeIf { it in PORT_MIN..PORT_MAX }
        val portProblem = when {
            fixed != null -> null
            portTrim.isEmpty() -> ManualCopy.ERR_PORT_NEEDED
            port == null -> ManualCopy.ERR_PORT_RANGE
            else -> null
        }
        return ManualCheck(parsed.takeIf { ipProblem == null }, port.takeIf { portProblem == null },
            ipProblem.takeIf { attempted }, portProblem.takeIf { attempted })
    }

    /** TLS certificate always speaks TLS; SMB and SNMP never do; the web tools follow the "Use HTTPS" switch (spec 4.1 item 5). */
    fun tls(tool: String, https: Boolean): Boolean = when (tool) {
        ToolKeys.TLS_CERTIFICATE -> true
        ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT -> https
        else -> false
    }

    /** The switch's starting value (spec 4.1 item 5): a known host's stored service on the port decides, else a TLS port number. */
    fun httpsDefault(tool: String, port: Int?, service: ServiceInfo?): Boolean {
        if (port == null) return false
        if (service != null) return ToolApplicability.targetTls(tool, port, service)
        return port in ServiceIdentifier.TLS
    }

    private fun reserved(ip: Inet4Address, home: Subnet?): Boolean {
        if (RESERVED.any { it.contains(ip) }) return true
        if (home == null || home.prefix > LAST_PREFIX_WITH_BROADCAST || !home.contains(ip)) return false
        val v = SubnetMath.toInt(ip).toLong() and UNSIGNED_MASK
        return v == home.start() || v == home.end()
    }
}
