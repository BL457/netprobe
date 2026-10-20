package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

/** The one table of port schemes and URI forms (spec 2.3); ActionProvider's port fallback delegates here. */
object PortUris {
    val TRY_AS = listOf("http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb")
    private val NAMED = setOf("SMB", "SSH", "TELNET", "VNC", "RDP", "FTP")
    private val SMB_DEFAULT_PORTS = setOf(139, 445)
    private val HTTP_PORTS = setOf(80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000)
    private val HTTPS_PORTS = setOf(443, 8443)
    private val SSH_PORTS = setOf(22, 2222)
    private val VNC_PORTS = 5900..5910
    private const val TELNET_PORT = 23
    private const val RDP_PORT = 3389
    private const val FTP_PORT = 21

    fun uri(scheme: String, ip: String, port: Int): String = when (scheme) {
        "http", "https", "ftp" -> "$scheme://$ip:$port/"
        "smb" -> if (port in SMB_DEFAULT_PORTS) "smb://$ip/" else "smb://$ip:$port/"
        else -> "$scheme://$ip:$port"
    }

    /**
     * The scheme the app believes the port speaks, first match wins (spec 2.3). A bare "TLS" (a handshake with no HTTP answer)
     * is not a guess, so 993, 995, 465, 636, 853, 8883 and Cast's 8009 never get "Open in browser" (Gruff m6).
     */
    fun guess(port: Int, service: ServiceInfo): String? {
        val name = service.name.uppercase()
        return when {
            name == "HTTPS" -> "https"
            name == "HTTP" -> "http"
            name in NAMED -> name.lowercase()
            else -> byPort(port)
        }
    }

    /** "Open with..." for a port with no guess: https for a bare TLS port or one with a certificate, http otherwise (A8). */
    fun fallback(service: ServiceInfo): String = if (service.name.equals("TLS", ignoreCase = true) || service.certCn != null) "https" else "http"

    private fun byPort(port: Int): String? = when (port) {
        in HTTP_PORTS -> "http"; in HTTPS_PORTS -> "https"; in SMB_DEFAULT_PORTS -> "smb"; in SSH_PORTS -> "ssh"
        TELNET_PORT -> "telnet"; in VNC_PORTS -> "vnc"; RDP_PORT -> "rdp"; FTP_PORT -> "ftp"; else -> null
    }
}
