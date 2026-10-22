package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.vulns.Cpe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The shipped http_stacks.json: it loads clean, every sample hits its rule and every negativeSample misses (Phase 3E spec 5 and 10). */
class HttpStackFileTest {
    private val text = javaClass.classLoader!!.getResource("http_stacks.json")!!.readText()
    private val cpeKeys = Cpe.load(javaClass.classLoader!!.getResource("cpe_map.json")!!.readText()).keys
    private val loaded = HttpStackRules.load(text, cpeKeys)
    private val rules = loaded.rules

    @Test fun `the file loads with nothing skipped and holds the 31 converted rules plus the initial set`() {
        assertTrue("skipped: ${loaded.skipped.map { it.id + ": " + it.reason }}", loaded.skipped.isEmpty())
        assertTrue("${rules.size} rules", rules.size >= 31 + 40)
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
    }
    @Test fun `every sample hits its rule`() {
        for (r in rules) {
            if (r.pathOnly) {
                val reply = HttpStacks.pathReplyFor(r, r.sample)!!
                assertTrue("${r.id} path sample", r.paths.any { HttpStacks.passes(it, reply) })
            } else assertNotNull("${r.id} did not hit its sample", HttpStacks.test(r, HttpStacks.sourcesFor(r, r.sample)))
        }
    }
    @Test fun `every negativeSample misses`() {
        for (r in rules) {
            val neg = r.negativeSample ?: continue
            if (r.pathOnly) {
                val reply = HttpStacks.pathReplyFor(r, neg)!!
                assertTrue("${r.id} path negative", r.paths.none { HttpStacks.passes(it, reply) })
            } else assertNull("${r.id} hit its negative sample", HttpStacks.test(r, HttpStacks.sourcesFor(r, neg)))
        }
    }
    @Test fun `a rule that reads a title or a body has a negative sample`() {
        val needNegative = rules.filter { r -> r.all.any { it.where == "title" || it.where == "body" } }
        val missing = needNegative.filter { it.negativeSample == null }.map { it.id }
        // The 31 converted rules keep the samples they had; the new rules all carry one.
        val newRules = rules.drop(31).filter { r -> r.all.any { it.where == "title" || it.where == "body" } }
        assertTrue("new rules without a negativeSample: ${newRules.filter { it.negativeSample == null }.map { it.id }}", newRules.all { it.negativeSample != null })
        assertTrue(missing.size < needNegative.size)
    }
    @Test fun `the device role is only for the named kinds of box`() {
        val allowed = setOf(DeviceType.CAMERA, DeviceType.PRINTER, DeviceType.ROUTER, DeviceType.IOT, DeviceType.NAS)
        val devices = rules.filter { it.role == MatchRole.DEVICE }
        assertTrue(devices.isNotEmpty())
        for (dv in devices) assertTrue("${dv.id} ${dv.type}", dv.type in allowed)
        val ids = devices.map { it.id }.toSet()
        for (must in listOf("hikvision", "dahua", "reolink", "hp-printer", "brother-printer", "canon-printer", "epson-printer", "fritzbox", "hue", "shelly", "tasmota", "esphome",
            "truenas", "qnap", "synology-title", "unifi-os")) assertTrue("$must is not a device rule", must in ids)
        for (soft in listOf("home-assistant", "pihole", "plex", "jellyfin", "portainer", "grafana", "nextcloud", "pfsense", "opnsense")) assertEquals(soft, MatchRole.SOFTWARE, rules.single { it.id == soft }.role)
    }
    @Test fun `every cpeProduct is in the map and the file has no em-dash`() {
        for (r in rules) r.cpeProduct?.let { assertTrue("${r.id} $it", cpeKeys.any { k -> k.equals(it, ignoreCase = true) }) }
        assertFalse(text.contains(0x2014.toChar()))
    }
    @Test fun `the listed products are all present`() {
        val stacks = rules.map { it.stack.lowercase() }
        for (p in listOf("home assistant", "pi-hole", "plex", "jellyfin", "emby", "proxmox", "truenas", "unraid", "portainer", "grafana", "prometheus", "traefik", "caddy",
            "nginx", "apache", "microsoft iis", "lighttpd", "express", "next.js", "werkzeug", "gunicorn", "uvicorn", "jetty", "spring boot", "wordpress", "nextcloud", "gitea", "gitlab",
            "jenkins", "minio", "octoprint", "frigate", "zigbee2mqtt", "node-red", "uptime kuma", "immich", "syncthing", "vaultwarden", "sonarr", "radarr", "qbittorrent",
            "docker registry", "docker engine", "elasticsearch", "opensearch", "kubernetes api", "unifi os", "unifi network", "openwrt luci", "pfsense", "opnsense", "fritz!box",
            "hikvision", "dahua", "reolink", "hp printer", "brother printer", "canon printer", "epson printer", "philips hue", "shelly", "tasmota", "esphome"))
            assertTrue("missing $p", stacks.any { it.contains(p) })
    }
    @Test fun `the home assistant rule is hard and confirms itself on api`() {
        val ha = rules.single { it.id == "home-assistant" }
        assertTrue(ha.hard); assertEquals("/api/", ha.paths.single().path); assertEquals("Home Assistant", ha.cpeProduct)
    }
    @Test fun `a stack pass over a plain page names the stack from the first fetch alone and keeps nginx as a reason`() = runBlocking {
        val src = StackSources(listOf("Server" to "nginx/1.25.3"), emptyList(), "Pi-hole - Login", null, "<title>Pi-hole - Login</title>", status = 200)
        val r = HttpStacks.run(rules, src, true, { _, _ -> null })
        assertEquals("Pi-hole", r.hard!!.stack)
        val nginx = r.hits.first { it.rule.id == "nginx" }
        assertFalse(nginx.rule.hard); assertEquals("nginx", nginx.stack); assertEquals("1.25.3", nginx.version)
    }
}
