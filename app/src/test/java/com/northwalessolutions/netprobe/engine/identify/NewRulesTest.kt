package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.identify.DeviceType.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 18: every new or replaced rule, alone, on its own sample, yields exactly the values the spec gives it. */
class NewRulesTest {
    private data class Expect(val vendor: String? = null, val model: String? = null, val os: String? = null, val type: DeviceType = UNKNOWN,
                              val software: List<String> = emptyList())
    private val rules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText())
    private val ip = InetAddress.getByName("192.168.1.2") as Inet4Address
    private val expected: Map<String, Expect> = linkedMapOf(
        "title-hass" to Expect(type = SERVER, software = listOf("Home Assistant")),
        "port-hass" to Expect(type = SERVER, software = listOf("Home Assistant")),
        "title-plex" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "port-plex" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "title-pihole" to Expect(type = SERVER, software = listOf("Pi-hole")),
        "title-unifi" to Expect(software = listOf("UniFi Network")),
        "port-rdp" to Expect(os = "Windows", type = COMPUTER),
        "p-dsm-title" to Expect(vendor = "Synology", type = NAS),
        "p-dsm-cert" to Expect(vendor = "Synology", type = NAS),
        "p-dsm-ftp" to Expect(vendor = "Synology", type = NAS),
        "p-srm-title" to Expect(vendor = "Synology", model = "Synology Router", type = ROUTER),
        "p-unifi-os" to Expect(vendor = "Ubiquiti", model = "UniFi OS console"),
        "p-unifi-cert" to Expect(software = listOf("UniFi Network")),
        "p-hue-title" to Expect(vendor = "Philips Hue", model = "Hue Bridge", type = IOT),
        "p-hue-cert" to Expect(vendor = "Philips Hue", model = "Hue Bridge", type = IOT),
        "p-plex-http" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "p-jellyfin" to Expect(type = SERVER, software = listOf("Jellyfin")),
        "p-emby" to Expect(type = SERVER, software = listOf("Emby Server")),
        "p-proxmox-cert" to Expect(model = "Proxmox VE", type = SERVER),
        "p-cups-server" to Expect(type = PRINTER, software = listOf("CUPS")),
        "p-printer-title" to Expect(vendor = "HP", type = PRINTER),
        "p-hp-server" to Expect(vendor = "HP", type = PRINTER),
        "p-rtsp-vendor" to Expect(vendor = "Hikvision", type = CAMERA),
        "p-rtsp-service" to Expect(type = CAMERA),
        "p-router-title" to Expect(vendor = "NETGEAR", type = ROUTER),
        "p-isp-hub" to Expect(model = "Hub Manager", type = ROUTER),
        "p-fritz" to Expect(vendor = "AVM", model = "FRITZ!Box", type = ROUTER),
        "p-fritz-cert" to Expect(vendor = "AVM", model = "FRITZ!Box", type = ROUTER),
        "p-draytek" to Expect(vendor = "DrayTek", type = ROUTER),
        "p-mikrotik-title" to Expect(vendor = "MikroTik", os = "RouterOS", type = ROUTER),
        "p-mikrotik-ssh" to Expect(vendor = "MikroTik", os = "RouterOS", type = ROUTER),
        "p-edgeos" to Expect(vendor = "Ubiquiti", os = "EdgeOS", type = ROUTER),
        "p-rdp" to Expect(os = "Windows"),
        "p-iis" to Expect(os = "Windows", type = SERVER),
        "p-httpapi" to Expect(os = "Windows"),
        "p-aspnet" to Expect(os = "Windows"),
        "p-openssh" to Expect(os = "Linux"),
        "p-ssh-freebsd" to Expect(os = "FreeBSD"),
        "p-ssh-raspbian" to Expect(vendor = "Raspberry Pi", type = COMPUTER),
        "p-esxi-title" to Expect(vendor = "VMware", model = "ESXi", os = "ESXi", type = SERVER),
        "p-esxi-cert" to Expect(vendor = "VMware", type = SERVER),
        "p-qnap-title" to Expect(vendor = "QNAP", type = NAS),
        "p-qnap-cert" to Expect(vendor = "QNAP", type = NAS),
        "p-truenas-title" to Expect(model = "TrueNAS", type = NAS),
        "p-truenas-cert" to Expect(model = "TrueNAS", type = NAS),
        "p-unraid-title" to Expect(model = "Unraid", type = NAS),
        "p-unraid-cert" to Expect(model = "Unraid", type = NAS),
        "p-omv" to Expect(model = "OpenMediaVault", type = NAS),
        "p-grafana" to Expect(type = SERVER, software = listOf("Grafana")),
        "p-portainer" to Expect(type = SERVER, software = listOf("Portainer")),
        "p-nodered-title" to Expect(type = SERVER, software = listOf("Node-RED")),
        "p-nodered-http" to Expect(software = listOf("Node-RED")),
        "p-nextcloud" to Expect(type = SERVER, software = listOf("Nextcloud")),
        "p-adguard" to Expect(type = SERVER, software = listOf("AdGuard Home")),
        "p-octoprint" to Expect(type = IOT, software = listOf("OctoPrint")),
        "p-kodi" to Expect(type = MEDIA, software = listOf("Kodi")),
        "p-tasmota" to Expect(model = "Tasmota", type = IOT),
        "p-shelly" to Expect(vendor = "Shelly", type = IOT),
        "p-frigate" to Expect(type = SERVER, software = listOf("Frigate NVR")),
        "p-z2m" to Expect(type = SERVER, software = listOf("Zigbee2MQTT")),
        "p-kuma" to Expect(type = SERVER, software = listOf("Uptime Kuma")),
        "p-idrac" to Expect(vendor = "Dell", model = "iDRAC", type = SERVER),
        "p-ilo" to Expect(vendor = "HPE", model = "iLO", type = SERVER),
        "p-airplay" to Expect(vendor = "Apple"),
    )

    @Test fun `every new or replaced rule yields exactly its own values on its own sample`() {
        assertEquals(64, expected.size)
        for ((ruleId, want) in expected) {
            val r = rules.single { it.id == ruleId }
            val got = DeviceIdentifier(listOf(r)).identify(SyntheticEvidence.forRule(r, ip))
            assertEquals(ruleId, want, Expect(got.vendor, got.model, got.os, got.type, got.software))
            assertEquals("$ruleId weight", r.weight, got.confidence)
        }
    }
}
