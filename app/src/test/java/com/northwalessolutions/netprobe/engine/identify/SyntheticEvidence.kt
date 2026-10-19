package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import java.net.Inet4Address

object SyntheticEvidence {
    /** Evidence that exercises exactly the rule [r]: its own sample placed in the field the rule reads. */
    fun forRule(r: Rule, ip: Inet4Address): HostEvidence {
        val sample = r.sample.orEmpty()
        val h = DiscoveredHost(ip)
        fun ev(host: DiscoveredHost, ports: Set<Int> = emptySet(), services: Map<Int, ServiceInfo> = emptyMap()) = HostEvidence(host, ports, services, null)
        return when (r.field) {
            "mdnsType" -> ev(h.copy(mdnsServiceTypes = setOf(sample)))
            "mdnsTxt" -> {
                val type = sample.substringBefore(':'); val kv = sample.substringAfter(':')
                ev(h.copy(mdnsServiceTypes = setOf(type), mdnsTxt = mapOf(type to mapOf(kv.substringBefore('=') to kv.substringAfter('=')))))
            }
            "upnpDeviceType" -> ev(h.copy(upnpDeviceType = sample))
            "snmpSysDescr" -> ev(h.copy(snmpSysDescr = sample))
            "httpTitle" -> ev(h.copy(httpTitle = sample))
            "httpRealm" -> ev(h.copy(httpAuthRealm = sample))
            "httpServer" -> ev(h.copy(httpServer = sample))
            "hostname" -> ev(h.copy(reverseDnsName = sample))
            "netbiosName" -> ev(h.copy(netbiosName = sample))
            "sshBanner" -> ev(h, services = mapOf(22 to ServiceInfo("SSH", "OpenSSH", null, sample, Confidence.CONFIRMED)))
            "ports", "anyPort" -> ev(h, ports = (r.ports ?: r.anyPort).orEmpty().toSet())
            "gateway" -> ev(h.copy(isGateway = true))
            else -> error("no synthetic evidence for field ${r.field} (rule ${r.id})")
        }
    }
}
