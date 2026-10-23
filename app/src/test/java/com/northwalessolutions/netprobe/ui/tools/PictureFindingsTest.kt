package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Page pictures in findings (tools platform spec section 12): the results-list model of a thumbnail row, and the export. */
class PictureFindingsTest {
    private fun finding(id: Long, kind: String, extra: String?, value: String = "NAS", detail: String? = "Synology DSM") =
        ToolFindingEntity(id = id, runId = 7, kind = kind, value = value, detail = detail, extra = extra)

    private val picture = finding(1, "OTHER", """{"url":"http://192.168.1.20:5000/","kind":"page","image":"7/1.jpg"}""")

    // the thumbnail row

    @Test fun `a finding with a picture is a thumbnail row with the value as caption and the url for the page`() {
        val row = ToolResultsModel.rows(listOf(picture)).single()
        assertEquals("7/1.jpg", row.image)
        assertEquals("NAS", row.value); assertEquals("Synology DSM", row.detail); assertEquals("http://192.168.1.20:5000/", row.url); assertEquals("page", row.label)
    }

    @Test fun `a built in kind keeps its kind and gains the picture, and a finding without one has none`() {
        val rows = ToolResultsModel.rows(listOf(finding(1, "TITLE", """{"image":"7/2.jpg"}"""), finding(2, "TITLE", null), finding(3, "OTHER", """{"url":"https://a.example/"}""")))
        assertEquals(listOf("7/2.jpg", null, null), rows.map { it.image })
        assertNull(rows[0].label); assertNull(rows[0].url)
    }

    @Test fun `only the exact picture path is kept`() {
        for (bad in listOf("../7/1.jpg", "7/../8/1.jpg", "/7/1.jpg", "7/1.png", "7/1.jpg/", "7/1", "x/1.jpg", "7\\\\1.jpg", "", "7/1.jpg ")) {
            val row = ToolResultsModel.rows(listOf(finding(1, "OTHER", """{"image":"$bad"}""")))[0]
            assertNull("'$bad'", row.image)
        }
        assertEquals("12/345.jpg", FindingImage.of(mapOf("image" to "12/345.jpg")))
        assertNull(FindingImage.of(emptyMap()))
    }

    @Test fun `picture rows keep their place among the other findings and stay out of the log`() {
        val rows = ToolResultsModel.rows(listOf(finding(1, "PATH", null, "/a"), picture, finding(3, "OTHER", """{"kind":"log"}""", "a log line")))
        val split = RunFindingsSplit.of(rows, failed = true)
        assertEquals(2, split.findings.size)
        assertEquals(listOf("/a", "NAS"), split.findings.map { it.value })
        assertEquals("7/1.jpg", split.findings[1].image)
        assertEquals(listOf("a log line"), split.log.map { it.value })
        assertTrue(split.log.all { it.image == null })
    }

    @Test fun `the results state carries the picture rows for the sheet and the page`() {
        val rows = ToolResultsModel.rows(listOf(picture, finding(2, "OTHER", """{"kind":"page","image":"7/2.jpg"}""", "Router")))
        assertEquals(listOf("7/1.jpg", "7/2.jpg"), rows.map { it.image })
        assertEquals(listOf(1L, 2L), rows.map { it.id })
    }

    @Test fun `a row model is a plain value so history keeps the same thumbnail after a reload`() {
        assertEquals(ToolResultsModel.rows(listOf(picture)), ToolResultsModel.rows(listOf(picture)))
    }

    // export

    private val utc = TimeZone.getTimeZone("UTC")
    private fun run() = ToolRunEntity(id = 7, hostId = null, toolId = "user:web-gallery", port = 0, startedAt = 1_792_504_991_000L, finishedAt = 1_792_505_042_000L,
        status = ToolRunStatus.DONE, optionsJson = "{}", summary = "2 pages from 2 devices", toolName = "Web gallery")

    @Test fun `export writes the picture path after the url and leaves other rows alone`() {
        val text = ToolExport.text(ToolExport.of(run(), null, listOf(picture, finding(2, "PATH", """{"url":"http://a/"}""", "/x", null), finding(3, "TITLE", """{"image":"7/3.jpg"}""", "Router", null)), utc))
        val lines = text.split("\n")
        val findings = lines.takeLast(3)
        assertEquals("NAS\tSynology DSM\thttp://192.168.1.20:5000/\tsnapshots/7/1.jpg", findings[0])
        assertEquals("/x\t\thttp://a/", findings[1])
        assertEquals("Router\t\t\tsnapshots/7/3.jpg", findings[2])
    }

    @Test fun `a malformed picture path never reaches the export`() {
        val text = ToolExport.text(ToolExport.of(run(), null, listOf(finding(1, "OTHER", """{"image":"../../databases/netprobe.db"}""")), utc))
        assertFalse(text.contains("netprobe.db")); assertFalse(text.contains("snapshots"))
    }
}
