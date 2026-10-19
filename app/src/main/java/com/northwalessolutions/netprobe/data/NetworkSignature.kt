package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import java.security.MessageDigest

/**
 * A network is its gateway: gateway MAC + gateway IP + prefix. SSID and BSSID are display-only, so roaming between
 * access points or bands, or toggling Location, never changes the identity.
 */
object NetworkSignature {
    fun of(env: NetworkEnv, gatewayMac: String?): String {
        val raw = (gatewayMac?.lowercase() ?: "") + identityKey(env)
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    /** What "the same network" means while a scan runs: gateway IP and prefix. Used by every scan watcher. */
    fun identityKey(env: NetworkEnv): String = (env.gateway?.hostAddress ?: "") + "/" + env.prefixLength
}
object DisplayName {
    fun of(h: HostEntity): String = listOf(h.customName, h.mdnsName, h.netbiosName, h.upnpName, h.reverseDnsName).firstOrNull { !it.isNullOrBlank() } ?: h.ip
}
