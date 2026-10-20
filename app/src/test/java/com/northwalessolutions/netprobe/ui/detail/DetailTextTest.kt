package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class DetailTextTest {
    private val http = ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE)
    private fun port(n: Int, uri: String?, has: Boolean = true): PortRow {
        val open = uri?.let { DeviceAction.OpenUri(if (has) "Open in browser" else "No app installed for ${it.substringBefore("://")}://", it, has) }
        val actions = listOfNotNull(open, uri?.let { DeviceAction.OpenWith(it) }, DeviceAction.Copy("Copy IP", "10.0.3.59"), DeviceAction.Copy("Copy IP:port", "10.0.3.59:$n"))
        return PortRow(n, http, open?.takeIf { has }, actions)
    }

    @Test fun `action words by scheme`() {
        for (s in listOf("http", "https", "ftp")) assertEquals("Open", actionWord(s))
        for (s in listOf("ssh", "telnet", "vnc", "rdp")) assertEquals("Connect", actionWord(s))
        assertEquals("Open share", actionWord("smb")); assertEquals("Copy", actionWord(null)); assertEquals("Copy", actionWord("gopher"))
    }
    @Test fun `no handler means copy ip and port`() {
        val p = port(5357, "http://10.0.3.59:5357/", has = false)
        assertNull(schemeOf(p)); assertEquals("Copy", actionWord(schemeOf(p)))
        assertEquals(DeviceAction.Copy("Copy IP:port", "10.0.3.59:5357"), primaryAction("10.0.3.59", p))
        assertEquals("Copy 10.0.3.59:5357", actionDescription("10.0.3.59", p))
    }
    @Test fun `primary action and descriptions`() {
        val p = port(80, "http://10.0.3.59:80/")
        assertEquals("http", schemeOf(p)); assertEquals(p.primary, primaryAction("10.0.3.59", p))
        assertEquals("Open port 80 in browser", actionDescription("10.0.3.59", p))
        assertEquals("Connect to port 22", actionDescription("10.0.3.59", port(22, "ssh://10.0.3.59:22")))
        assertEquals("Open share on port 445", actionDescription("10.0.3.59", port(445, "smb://10.0.3.59/")))
    }
    @Test fun `sheet labels map provider wording and drop the no app entry`() {
        assertEquals("Copy IP and port", sheetLabel(DeviceAction.Copy("Copy IP:port", "x")))
        assertEquals("Copy IP", sheetLabel(DeviceAction.Copy("Copy IP", "x")))
        assertEquals("Open with...", sheetLabel(DeviceAction.OpenWith("http://x/")))
        assertNull(sheetLabel(DeviceAction.OpenUri("No app installed for ssh://", "ssh://x", false)))
        val labels = sheetActions(port(5357, "http://10.0.3.59:5357/", has = false)).map { it.first }
        assertEquals(listOf("Open with...", "Copy IP", "Copy IP and port"), labels)
        assertFalse(sheetActions(port(80, "http://10.0.3.59:80/")).any { it.second is DeviceAction.OpenUri })   // the primary is the row tap
    }
    @Test fun `identity with type`() {
        assertEquals("Synology DS220+  NAS", identityWithType("Synology DS220+", DeviceType.NAS))
        assertEquals("Android Phone", identityWithType("Android Phone", DeviceType.PHONE))
        assertEquals("Android phone", identityWithType("Android phone", DeviceType.PHONE))
        assertEquals("NAS", identityWithType("", DeviceType.NAS))
        assertEquals("Linksys", identityWithType("Linksys", DeviceType.UNKNOWN))
        assertNull(identityWithType(" ", DeviceType.UNKNOWN))
    }
    @Test fun `version line and progress`() {
        assertEquals("OpenSSH 8.2p1", versionLine(ServiceInfo("SSH", "OpenSSH", "8.2p1", null, Confidence.CONFIRMED)))
        assertEquals("cert chnas.local", versionLine(ServiceInfo("HTTPS", null, null, "cert chnas.local", Confidence.CONFIRMED)))
        assertNull(versionLine(http))
        assertEquals("312 of 65535, found 4", detailProgress(ScanProgress(312, 65535, 4, 0))); assertEquals("starting", detailProgress(null))
    }

    private fun bare() = HostEntity(networkId = 1, ip = "10.0.3.59", mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null, upnpName = null,
        upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null,
        identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED,
        note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    @Test fun `software chips read the stored column in order`() {
        assertEquals(listOf("Runs Home Assistant", "Runs Grafana"), softwareLabels(bare().copy(identSoftware = "Home Assistant\nGrafana")))
        assertEquals(emptyList<String>(), softwareLabels(bare()))
    }
    @Test fun `set by you lists only the set fields, in order`() {   // spec 9.1 test 25
        val h = bare()
        assertNull(setByYouLine(h)); assertNull(setByYouLine(h.copy(customName = "Mine")))   // the name is the title, not listed
        assertEquals("Set by you: type NAS, vendor Synology, model DS220+", setByYouLine(h.copy(userType = DeviceType.NAS, userVendor = "Synology", userModel = "DS220+")))
        assertEquals("Set by you: vendor Synology", setByYouLine(h.copy(userVendor = "Synology")))
        assertEquals("Set by you: type IoT, model Hue", setByYouLine(h.copy(userType = DeviceType.IOT, userModel = "Hue")))
    }
    @Test fun `port sub line puts the title before the version line`() {
        assertEquals("Synology DiskStation  nginx 200 OK", portSubLine(ServiceInfo("HTTP", "nginx", null, "200 OK", Confidence.CONFIRMED, title = "Synology DiskStation")))
        assertEquals("Synology DiskStation", portSubLine(ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = "Synology DiskStation")))
        assertEquals("OpenSSH 8.2p1", portSubLine(ServiceInfo("SSH", "OpenSSH", "8.2p1", null, Confidence.CONFIRMED)))
        assertNull(portSubLine(http))
    }
    @Test fun `the sheet always has open with and splits around the try as row`() {
        val (head, tail) = sheetSplit(port(8090, "http://10.0.3.59:8090/"))
        assertEquals(listOf("Open with..."), head.map { it.first }); assertEquals(listOf("Copy IP", "Copy IP and port"), tail.map { it.first })
    }
    @Test fun `detail progress counts identifies`() {
        assertEquals("312 of 65535, found 4", detailProgress(ScanProgress(312, 65535, 4, 0), identifying = 3))   // the sweep runs: text unchanged
        assertEquals("identifying 3 ports", detailProgress(ScanProgress(65535, 65535, 4, 0), identifying = 3))
        assertEquals("identifying 1 port", detailProgress(ScanProgress(1000, 1000, 1, 0), identifying = 1))
        assertEquals("1000 of 1000, found 2", detailProgress(ScanProgress(1000, 1000, 2, 0), identifying = 0))
        assertEquals(0.5f, detailFraction(ScanProgress(100, 100, 100, 0), identified = 0), 0.0001f)
        assertEquals(1f, detailFraction(ScanProgress(100, 100, 4, 0), identified = 4), 0.0001f)
        assertEquals(0f, detailFraction(null, 0), 0f); assertEquals(0f, detailFraction(ScanProgress(0, 0, 0, 0), 0), 0f)
    }
}
