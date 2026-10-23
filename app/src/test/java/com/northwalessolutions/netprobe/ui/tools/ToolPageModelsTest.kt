package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.PortEntity
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import org.junit.Assert.*
import org.junit.Test

/** The tool page's pure models: the device picker, the port picker, the OTHER + extra.kind label (ruling c), the failed-run log split and the previous runs. */
class ToolPageModelsTest {
    private fun host(id: Long, ip: String, custom: String? = null, vendor: String? = null, manual: Boolean = false) =
        TestHosts.host(ip, vendor = vendor).copy(id = id, customName = custom, manualOnly = manual)

    // device picker

    @Test fun `devices sort by address numerically, hide manual targets and name themselves like the device list`() {
        val rows = DevicePick.rows(listOf(host(1, "10.0.3.100", "NAS"), host(2, "10.0.3.9", vendor = "Google"), host(3, "10.0.3.50", manual = true), host(4, "10.0.2.200")))
        assertEquals(listOf("10.0.2.200", "10.0.3.9", "10.0.3.100"), rows.map { it.ip })
        assertEquals(DeviceChoice(1, "10.0.3.100", "NAS", "10.0.3.100"), rows[2])
        assertEquals("10.0.3.9", rows[1].name); assertEquals("Google", rows[1].detail)
        assertEquals("", rows[0].detail)
    }
    @Test fun `search needs every word and looks in the name, the address and the vendor`() {
        val rows = DevicePick.rows(listOf(host(1, "10.0.3.100", "Living room TV", "Samsung"), host(2, "10.0.3.101", "NAS", "Synology"), host(3, "10.0.3.9")))
        assertEquals(3, DevicePick.search(rows, "").size); assertEquals(3, DevicePick.search(rows, "   ").size)
        assertEquals(listOf(1L), DevicePick.search(rows, "living tv").map { it.hostId })
        assertEquals(listOf(2L), DevicePick.search(rows, "SYNOLOGY").map { it.hostId })
        assertEquals(listOf(1L, 2L), DevicePick.search(rows, "10.0.3.10").map { it.hostId })
        assertEquals(listOf(3L), DevicePick.search(rows, "10.0.3.9").map { it.hostId })
        assertTrue(DevicePick.search(rows, "printer").isEmpty())
        assertTrue(DevicePick.search(rows, "living nas").isEmpty())
    }
    @Test fun `a typed address no device has is offered, anything else is not`() {
        val rows = DevicePick.rows(listOf(host(1, "10.0.3.100")))
        assertEquals("8.8.8.8", DevicePick.typedAddress(rows, " 8.8.8.8 "))
        assertNull(DevicePick.typedAddress(rows, "10.0.3.100")); assertNull(DevicePick.typedAddress(rows, "10.0.3")); assertNull(DevicePick.typedAddress(rows, "nas"))
        assertNull(DevicePick.typedAddress(rows, "")); assertNull(DevicePick.typedAddress(rows, "300.1.1.1"))
    }

    // port picker

    @Test fun `open ports list in port order with their service name and product`() {
        fun port(p: Int, name: String, product: String? = null) = PortEntity(hostId = 1, port = p, serviceName = name, product = product, version = null, detail = null, confidence = Confidence.CONFIRMED, lastSeen = 0)
        val choices = PortChoices.of(listOf(port(5001, "HTTPS", "nginx"), port(22, "SSH"), port(445, ""), port(80, "HTTP", " ")))
        assertEquals(listOf(22, 80, 445, 5001), choices.map { it.port })
        assertEquals(listOf("22  SSH", "80  HTTP", "445", "5001  HTTPS, nginx"), choices.map { it.label })
        assertTrue(PortChoices.of(emptyList()).isEmpty())
    }

    // finding labels (ruling c)

    private fun finding(kind: String, extra: String?) = ToolFindingEntity(id = 1, runId = 1, kind = kind, value = "v", detail = null, extra = extra)

    @Test fun `an OTHER finding with extra kind is labelled with it, nothing else is`() {
        assertEquals("banner", ToolResultsModel.rows(listOf(finding("OTHER", """{"kind":"banner"}""")))[0].label)
        assertEquals("banner", ToolResultsModel.rows(listOf(finding("OTHER", """{"url":"https://a.example/","kind":" banner "}""")))[0].label)
        assertEquals("https://a.example/", ToolResultsModel.rows(listOf(finding("OTHER", """{"url":"https://a.example/","kind":"x"}""")))[0].url)
        assertNull(ToolResultsModel.rows(listOf(finding("OTHER", null)))[0].label)
        assertNull(ToolResultsModel.rows(listOf(finding("OTHER", """{"url":"https://a.example/"}""")))[0].label)
        assertNull(ToolResultsModel.rows(listOf(finding("OTHER", """{"kind":"  "}""")))[0].label)
        assertNull("a built-in kind keeps no label even if extra has a kind", ToolResultsModel.rows(listOf(finding("HEADER", """{"kind":"banner"}""")))[0].label)
        assertEquals("a kind this version does not know reads as OTHER", "x", ToolResultsModel.rows(listOf(finding("RETIRED_KIND", """{"kind":"x"}""")))[0].label)
        assertNull(ToolResultsModel.rows(listOf(finding("OTHER", "not json")))[0].label)
    }
    @Test fun `FindingLabel matches the rule directly`() {
        assertEquals("log", FindingLabel.of("OTHER", mapOf("kind" to "log"))); assertNull(FindingLabel.of("PATH", mapOf("kind" to "log"))); assertNull(FindingLabel.of("OTHER", emptyMap()))
        assertTrue(FindingLabel.isLog(FindingRow(1, "x", null, null, "log"))); assertFalse(FindingLabel.isLog(FindingRow(1, "x", null, null, "banner"))); assertFalse(FindingLabel.isLog(FindingRow(1, "x", null, null)))
    }

    // the log of a failed run

    private val rows = listOf(FindingRow(1, "found", null, null, "banner"), FindingRow(2, "line one", null, null, "log"), FindingRow(3, "path", null, null), FindingRow(4, "line two", null, null, "log"))

    @Test fun `a failed run shows its log lines in a collapsed section apart from the findings`() {
        val s = RunFindingsSplit.of(rows, failed = true)
        assertEquals(listOf(1L, 3L), s.findings.map { it.id }); assertEquals(listOf(2L, 4L), s.log.map { it.id }); assertTrue(s.showLog)
    }
    @Test fun `a run that did not fail shows no log section, and a failed run without log lines none either`() {
        assertFalse(RunFindingsSplit.of(rows, failed = false).showLog)
        assertEquals(listOf(1L, 3L), RunFindingsSplit.of(rows, failed = false).findings.map { it.id })
        assertFalse(RunFindingsSplit.of(rows.filterNot { it.label == "log" }, failed = true).showLog)
        assertTrue(RunFindingsSplit.of(emptyList(), failed = true).findings.isEmpty())
    }

    // previous runs

    private fun run(id: Long, tool: String, port: Int, ip: String?, summary: String = "3 findings", status: ToolRunStatus = ToolRunStatus.DONE) =
        NetworkRun(ToolRunEntity(id = id, hostId = if (ip == null) null else 7, toolId = tool, port = port, startedAt = 1_000_000L - id, finishedAt = 1_000_000L, status = status,
            optionsJson = "{}", summary = summary), ip)

    @Test fun `previous runs are this tool's runs only, newest first as given, titled by device and port`() {
        val runs = listOf(run(5, "dns", 0, null), run(4, "other", 80, "10.0.3.5"), run(3, "dns", 443, "10.0.3.5"), run(2, "dns", 0, "10.0.3.6", summary = ""), run(1, "dns", 22, "10.0.3.7"))
        val out = PreviousRuns.of(runs, "dns", now = 1_100_000L, formatDate = { "date" })
        assertEquals(listOf(5L, 3L, 2L, 1L), out.map { it.runId })
        assertEquals(listOf("No device", "10.0.3.5:443", "10.0.3.6", "10.0.3.7:22"), out.map { it.title })
        assertEquals("Show results for 10.0.3.5:443", out[1].description)
        assertTrue(out[0].line.startsWith("3 findings, ")); assertTrue("an empty summary reads as the status", out[2].line.startsWith("Done, "))
    }
    @Test fun `previous runs stop at the limit`() {
        val runs = (1L..40L).map { run(it, "dns", 53, "10.0.3.5") }
        assertEquals(PreviousRuns.LIMIT, PreviousRuns.of(runs, "dns", 1_100_000L).size)
        assertTrue(PreviousRuns.of(emptyList(), "dns", 0L).isEmpty())
    }
}
