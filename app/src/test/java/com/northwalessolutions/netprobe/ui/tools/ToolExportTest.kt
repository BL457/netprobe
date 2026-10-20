package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** spec 7.5: the share text for each tool, Name sent, not finished, the Options line, no hints. */
class ToolExportTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val opts = "{\"tls\":false,\"serverName\":null,\"wordlistId\":\"builtin:common-short\",\"wordlistName\":\"common-short\",\"wordlistEntries\":312," +
        "\"extensions\":[],\"method\":\"HEAD_THEN_GET\",\"methodUsed\":\"HEAD_THEN_GET\",\"concurrency\":8,\"timeoutS\":3,\"statusCodes\":[200,204,301,302,307,401,403],\"baseline\":true}"
    private fun run(tool: ToolId, port: Int, json: String, finished: Long? = 1_792_505_042_000L, status: ToolRunStatus = ToolRunStatus.DONE, summary: String = "7 found in 4614 paths") =
        ToolRunEntity(id = 1, hostId = 7, toolId = tool.key, port = port, startedAt = 1_792_504_991_000L, finishedAt = finished, status = status, optionsJson = json, summary = summary)

    @Test fun `the brute force export, exactly`() {
        val findings = listOf(ToolFindingEntity(1, 1, "PATH", "/admin", "200  1.2 KB", "{\"url\":\"http://10.0.3.10:5000/admin\",\"status\":\"200\"}"),
            ToolFindingEntity(2, 1, "PATH", "/old", null, null))
        val text = ToolExport.text(ToolExport.of(run(ToolId.DIR_BRUTEFORCE, 5000, opts), "10.0.3.10", findings, utc))
        assertEquals(listOf(
            "NetProbe Directory brute-force",
            "Target: http://10.0.3.10:5000/",
            "Started: 2026-10-20 14:03:11",
            "Finished: 2026-10-20 14:04:02",
            "Status: Done",
            "Summary: 7 found in 4614 paths",
            "Options: wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on",
            "",
            "/admin\t200  1.2 KB\thttp://10.0.3.10:5000/admin",
            "/old\t\t"), text.split("\n"))
    }
    @Test fun `a tls export names the name sent and has no options line, an unfinished run says so`() {
        val text = ToolExport.text(ToolExport.of(run(ToolId.TLS_CERTIFICATE, 443, "{\"tls\":true,\"serverName\":\"chnas.local\"}", finished = null, status = ToolRunStatus.RUNNING, summary = ""),
            "10.0.3.10", emptyList(), utc))
        assertTrue(text.contains("\nTarget: 10.0.3.10:443\n")); assertTrue(text.contains("\nName sent: chnas.local\n"))
        assertTrue(text.contains("\nFinished: not finished\n")); assertTrue(text.contains("\nStatus: Running\n")); assertFalse(text.contains("Options:"))
    }
    @Test fun `targets for every tool and the subject`() {
        assertEquals("https://10.0.3.10:5001/", ToolExport.target(ToolId.HTTP_FINGERPRINT, "10.0.3.10", 5001, true))
        assertEquals("smb://10.0.3.10/", ToolExport.target(ToolId.SMB_SESSION, "10.0.3.10", 445, false))
        assertEquals("udp://10.0.3.10:161", ToolExport.target(ToolId.SNMP_WALK, "10.0.3.10", 161, false))
        assertEquals("NetProbe TLS certificate 10.0.3.10:443", ToolExport.subject(ToolCopy.TLS_CERTIFICATE, "10.0.3.10", 443))
    }
}
