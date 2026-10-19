package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

class ActionProvider(private val resolver: HandlerResolver) {
    private enum class Kind(val scheme: String, val label: String) {
        HTTP("http", "Open in browser"), HTTPS("https", "Open in browser"), SMB("smb", "Open share"), SSH("ssh", "Connect with SSH app"),
        TELNET("telnet", "Connect with Telnet app"), VNC("vnc", "Connect with VNC app"), RDP("rdp", "Connect with RDP app"), FTP("ftp", "Open with FTP app")
    }
    private fun kindFor(port: Int, service: ServiceInfo): Kind? = when (service.name.uppercase()) {
        "HTTP" -> Kind.HTTP; "HTTPS", "TLS" -> Kind.HTTPS; "SMB" -> Kind.SMB; "SSH" -> Kind.SSH; "TELNET" -> Kind.TELNET
        "VNC" -> Kind.VNC; "RDP" -> Kind.RDP; "FTP" -> Kind.FTP
        else -> when (port) {
            80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000 -> Kind.HTTP; 443, 8443 -> Kind.HTTPS; 445, 139 -> Kind.SMB
            22, 2222 -> Kind.SSH; 23 -> Kind.TELNET; in 5900..5910 -> Kind.VNC; 3389 -> Kind.RDP; 21 -> Kind.FTP; else -> null
        }
    }
    private fun uri(k: Kind, ip: String, port: Int) = when (k) {
        Kind.HTTP, Kind.HTTPS, Kind.FTP -> "${k.scheme}://$ip:$port/"
        Kind.SMB -> "smb://$ip/"
        else -> "${k.scheme}://$ip:$port"
    }

    fun actionsFor(ip: String, port: Int, service: ServiceInfo): List<DeviceAction> {
        val out = ArrayList<DeviceAction>()
        kindFor(port, service)?.let { k ->
            val u = uri(k, ip, port); val has = resolver.hasHandler(u)
            out += DeviceAction.OpenUri(if (has) k.label else "No app installed for ${k.scheme}://", u, has)
            out += DeviceAction.OpenWith(u)
        }
        out += DeviceAction.Copy("Copy IP", ip); out += DeviceAction.Copy("Copy IP:port", "$ip:$port")
        return out
    }
    fun primary(ip: String, port: Int, service: ServiceInfo): DeviceAction? =
        actionsFor(ip, port, service).filterIsInstance<DeviceAction.OpenUri>().firstOrNull { it.hasHandler }
}
