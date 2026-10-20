package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import java.net.Inet4Address

object SyntheticEvidence {
    /** A port no rule names, for the port evidence of rules without onPorts. */
    const val NEUTRAL_PORT = 40000

    /** Evidence that exercises exactly the rule [r]: its own sample placed in the field the rule reads, in that field's candidate form. */
    fun forRule(r: Rule, ip: Inet4Address): HostEvidence {
        val sample = r.sample.orEmpty()
        val h = DiscoveredHost(ip)
        val port = r.onPorts?.first() ?: NEUTRAL_PORT
        fun ev(host: DiscoveredHost, ports: Set<Int> = emptySet(), services: Map<Int, ServiceInfo> = emptyMap()) = HostEvidence(host, ports, services, null)
        fun onPort(s: ServiceInfo) = ev(h, ports = setOf(port), services = mapOf(port to s))
        fun web(title: String? = null, server: String? = null, realm: String? = null, poweredBy: String? = null) =
            ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title, server = server, realm = realm, poweredBy = poweredBy)
        return when (r.field) {
            "mdnsType" -> ev(h.copy(mdnsServiceTypes = setOf(sample)))
            "mdnsTxt" -> {
                val type = sample.substringBefore(':'); val kv = sample.substringAfter(':')
                ev(h.copy(mdnsServiceTypes = setOf(type), mdnsTxt = mapOf(type to mapOf(kv.substringBefore('=') to kv.substringAfter('=')))))
            }
            "upnpManufacturer" -> ev(h.copy(upnpManufacturer = sample))
            "upnpDeviceType" -> ev(h.copy(upnpDeviceType = sample))
            "snmpSysDescr" -> ev(h.copy(snmpSysDescr = sample))
            "httpTitle" -> if (r.onPorts != null) onPort(web(title = sample)) else ev(h.copy(httpTitle = sample))
            "httpRealm" -> if (r.onPorts != null) onPort(web(realm = sample)) else ev(h.copy(httpAuthRealm = sample))
            "httpServer" -> if (r.onPorts != null) onPort(web(server = sample)) else ev(h.copy(httpServer = sample))
            "hostname" -> ev(h.copy(reverseDnsName = sample))
            "netbiosName" -> ev(h.copy(netbiosName = sample))
            // The rule reads product and detail of SSH ports; the parser puts the software token (for example OpenSSH_for_Windows) in product.
            "sshBanner" -> ev(h, services = mapOf(22 to ServiceInfo("SSH", sample, null, sample, Confidence.CONFIRMED)))
            "poweredBy" -> onPort(web(poweredBy = sample))
            "certName" -> onPort(ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = sample))
            "certOrg" -> onPort(ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certOrg = sample))
            "portBanner" -> onPort(ServiceInfo("FTP", detail = sample, confidence = Confidence.CONFIRMED))   // joined form = the sample
            "service" -> onPort(ServiceInfo(sample, confidence = Confidence.CONFIRMED))
            "ports", "anyPort" -> ev(h, ports = (r.ports ?: r.anyPort).orEmpty().toSet())
            "gateway" -> ev(h.copy(isGateway = true))
            else -> error("no synthetic evidence for field ${r.field} (rule ${r.id})")
        }
    }
}
