package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

class ActionProvider(private val resolver: HandlerResolver) {
    private val labels = mapOf("http" to "Open in browser", "https" to "Open in browser", "smb" to "Open share", "ssh" to "Connect with SSH app",
        "telnet" to "Connect with Telnet app", "vnc" to "Connect with VNC app", "rdp" to "Connect with RDP app", "ftp" to "Open with FTP app")

    /** The guessed open (or its "No app installed" entry, filtered by the sheet), always one "Open with...", then the two copies (spec 2.3). */
    fun actionsFor(ip: String, port: Int, service: ServiceInfo): List<DeviceAction> {
        val out = ArrayList<DeviceAction>()
        val guess = PortUris.guess(port, service)
        if (guess != null) {
            val u = PortUris.uri(guess, ip, port); val has = resolver.hasHandler(u)
            out += DeviceAction.OpenUri(if (has) labels.getValue(guess) else "No app installed for $guess://", u, has)
        }
        out += DeviceAction.OpenWith(PortUris.uri(guess ?: PortUris.fallback(service), ip, port))
        out += DeviceAction.Copy("Copy IP", ip); out += DeviceAction.Copy("Copy IP:port", "$ip:$port")
        return out
    }
    fun primary(ip: String, port: Int, service: ServiceInfo): DeviceAction? =
        actionsFor(ip, port, service).filterIsInstance<DeviceAction.OpenUri>().firstOrNull { it.hasHandler }
    /** (scheme, uri) for each Try as scheme an installed app handles, in TRY_AS order; empty when none (Gruff m7, D9). */
    fun tryAs(ip: String, port: Int): List<Pair<String, String>> =
        PortUris.TRY_AS.map { it to PortUris.uri(it, ip, port) }.filter { (_, u) -> resolver.hasHandler(u) }
}
