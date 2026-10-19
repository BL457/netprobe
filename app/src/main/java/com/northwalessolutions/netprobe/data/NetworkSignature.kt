package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import java.security.MessageDigest

object NetworkSignature {
    fun of(env: NetworkEnv): String {
        val raw = (env.bssid ?: env.ssid ?: "") + (env.gateway?.hostAddress ?: "") + "/" + env.prefixLength
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
object DisplayName {
    fun of(h: HostEntity): String = listOf(h.customName, h.mdnsName, h.netbiosName, h.upnpName, h.reverseDnsName).firstOrNull { !it.isNullOrBlank() } ?: h.ip
}
