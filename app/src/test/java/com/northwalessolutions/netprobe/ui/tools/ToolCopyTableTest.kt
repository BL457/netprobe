package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import org.junit.Assert.*
import org.junit.Test

/**
 * spec 9 held as regex templates. Each test drives a matrix of inputs through every ToolCopy and WordlistCopy helper and asserts the
 * output matches a template, so wording outside the table fails here. No template contains an em-dash, a disclaimer or a prompt.
 */
class ToolCopyTableTest {
    private val date = "3 Jan 2026"
    private val ts = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"
    private val templates: List<Regex> = listOf(
        // names, Tools row and blocks
        "No device", ".+  No device", "Directory brute-force", "HTTP fingerprint", "TLS certificate", "SMB session", "SNMP walk", "Identify service", "Identify all services",
        // Identify all services and the port row action (R2)
        "No services recognised", "port \\d+ of \\d+", "\\d+ of \\d+ ports named", "\\d+: not recognised", "\\d+: .+", "Identify", "Identifying", "Identify service on port \\d+", "all open ports",
        // Identify service (3E)
        "[a-z0-9-]+: \\d+ bytes?, .+", "[a-z0-9-]+: .+", ".+, \\d+ probes?", "No service recognised, \\d+ probes?", "probe \\d+ of \\d+",
        "Tools", "\\d+ available", "Last run .+: .+ on (\\d+|UDP 161), .+", "No tools run yet", "Tools, \\d+ available, (expand|collapse)",
        "Last run .+ on (\\d+|UDP 161): .+", "Not run yet", "Results", "Show .+ results", "\\d+", "UDP 161", "Run .+ on port (\\d+|UDP 161)", "Run tool",
        // busy, Home, Quick scan all
        "A tool is already running on this device", "Wait for the port scan to finish", "Four tools are already running, stop one first",
        "A tool is running on this device", "running", "port scan running", "tool running",
        "Quick scan finished, \\d+ devices? could not be scanned", "Quick scan finished, \\d+ devices? skipped: a tool is running",
        "Quick scan finished, \\d+ devices? could not be scanned, \\d+ skipped: a tool is running",
        // pinned bar, progress, end message, statuses
        ".+ on (\\d+|UDP 161|all open ports)", "\\d+ of \\d+, \\d+ found(, \\d+ errors)?  \\d+ s", "\\d+ done, \\d+ found  \\d+ s", "starting", "Stop tool run",
        ".+ finished: .+", "Running", "Done", "Stopped", "Failed", "Interrupted",
        // summaries
        "Network changed, run stopped", "Tool skipped: could not bind to Wi-Fi", "Failed: .+", "Interrupted, \\d+ found",
        "Stopped after \\d+ of \\d+, \\d+ found", "Stopped after \\d+ steps?, \\d+ found",
        "\\d+ found in \\d+ paths?(, \\d+ errors?)?", "Stopped at 500 findings", "Stopped: the server answers every path alike",
        "The server did not answer the first request", "Wordlist not found", "Wordlist not found, common-short used, .+", "The server stopped answering after \\d+ paths?",
        ".+, \\d+ findings?", "No HTTP response on this port", ".+, expires .+(, self-signed)?", "No TLS handshake on this port",
        "SMB [0-9.]+, signing (required|not required), anonymous (allowed|refused), guest (allowed|refused)",
        "No SMB2 dialect in common", "No SMB response on this port", ".+, \\d+ interfaces?", "No SNMP answer to public",
        // options sheet and errors
        "Wordlist", "\\d+ entr(y|ies)", "Manage wordlists", "Extensions", "php, html, txt", "Each word is also tried with these endings",
        "Method", "HEAD then GET", "GET only", "Concurrency", "Timeout per request", "\\d+ s", "Report status codes", "200, 204, 301, 302, 307, 401, 403",
        "Filter not-found pages", "Checks random paths first and hides answers that match them", "\\d+ paths?", "Run",
        "Use letters and digits only, for example php, html", "At most 8 extensions", "Use codes from 100 to 599, for example 200, 301, 403",
        "Add at least one status code", "Wordlist not found, using common-short",
        // results sheet
        ".+  \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+", "(Running|Done|Stopped|Failed|Interrupted), .+", "(Done|Stopped|Failed|Interrupted), .+, as .+",
        "Run \\d+ of \\d+", "Older run", "Newer run", "Open with\\.\\.\\.", "Copy", "Share", "Copied",
        "Running, findings appear as they are found", "No findings", "Open .+ with", "Copy .+",
        // export
        "NetProbe .+ \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+", "NetProbe .+", "Target: .+", "Name sent: .+", "Started: $ts", "Finished: ($ts|not finished)",
        "Status: (Running|Done|Stopped|Failed|Interrupted)", "Summary: .*",
        "Options: wordlist .+ \\(\\d+ entr(y|ies)\\), extensions .+, (HEAD then GET|GET only|GET only \\(HEAD and GET disagree\\)), concurrency \\d+, timeout \\d+ s, codes [0-9 ]+, not-found filter (on|off)",
        // brute-force finding detail and sizes
        "\\d{3}(  (\\d+ B|\\d+\\.\\d KB|64 KB\\+))?(  to .+)?",
        // fingerprint details
        "GET /", "GET /, to .+", "GET .+", "Cookie(, Secure)?(, HttpOnly)?(, SameSite=.+)?", "Page title", "Generator meta tag", "\\d+ rules?",
        "Disallowed in robots\\.txt", "Allowed in robots\\.txt", "\\d+ URLs?", "Contact: .+", "Present", "Error page signature", "Matched .+: .*",
        // TLS details
        "Subject", "Issuer", "Alternative name \\((DNS|IP)\\)", ".+ to .+", "Valid", "Expired", "Not yet valid", "Expires in \\d+ days?",
        "(RSA|EC|DSA) \\d+ bit", "Ed25519", "Ed448", "Public key", "Signature algorithm", "SHA-256 fingerprint", "Chain certificate \\d+ of \\d+",
        "Protocol and cipher", "Certificate", "Self-signed", "RSA key under 2048 bit", "Signed with SHA-1",
        // SMB details
        "Dialect", "SMB (2\\.0\\.2|2\\.1|3\\.0|3\\.0\\.2)", "Signing", "Required", "Enabled, not required", "Session", "Anonymous (allowed|refused)",
        "Guest (allowed|refused|mapped to guest)", "NetBIOS name", "NetBIOS domain", "DNS name", "DNS domain", "OS build \\(NTLM\\)",
        // SNMP details
        "sysDescr", "sysObjectID", "Uptime", "sysContact", "sysName", "sysLocation", "\\d+ days? \\d+ h", "\\d+ h \\d+ min",
        "((up|down|testing|unknown|dormant|notPresent|lowerLayerDown|ethernetCsmacd|softwareLoopback|propVirtual|ieee80211|gigabitEthernet|tunnel|l2vlan|ieee8023adLag|bridge|type \\d+|\\d+ (M|G)bit/s|([0-9a-f]{2}:){5}[0-9a-f]{2})(, )?)*",
        "and \\d+ more", "Interface address", "\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+",
        // Wordlists screen, import results, Settings
        "Back", "Wordlists", "Import file", "Importing", "Built in", "Imported", "\\d+ entr(y|ies), built in", "\\d+ entr(y|ies), imported $date",
        "Delete", "Delete .+\\?", "Cancel", "No imported wordlists yet", "Delete .+", "Imported .+, \\d+ entr(y|ies)( \\(\\d+ skipped\\))?",
        "That file is too large \\(limit 10 MB\\)", "That file has more than 200000 entries", "That file has no entries", "Could not read that file",
        "Expand", " s", "Wordlist not found, common-short used", "Imported list", "Recovered list \\d+", "3 built in, \\d+ imported", "Loading wordlists", "3 built in",
        // 3C-1 URL import (spec 7.2)
        "Import from URL", "URL", "https://example\\.com/wordlist\\.txt", "Import", "Enter a web address starting with https:// or http://",
        "Remove the user name and password from the address", "That address is too long", "Fetching \\d+\\.\\d( of \\d+\\.\\d)? MB", "Import cancelled",
        "Could not fetch: (unknown host|no answer|secure connection failed|connection failed|redirected to an insecure address|redirected to a local address|too many redirects)",
        "Could not fetch: the server answered \\d{3}", "Could not fetch: not a text file( \\(\\S+\\))?",
    ).map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the tool copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `no string carries an em-dash`() {
        val all = everyString()
        all.forEach { assertFalse("em-dash in '$it'", it.contains('\u2014')) }
        assertTrue(all.size > 150)
    }
    @Test fun `every helper over its input matrix is in the table`() { everyString().forEach { inTable(it) } }
    @Test fun `exact wording of the key strings`() {
        assertEquals("1234 of 4614, 7 found  12 s", ToolCopy.progress(1234, 4614, 7, 0, 12))
        assertEquals("1234 of 4614, 7 found, 3 errors  12 s", ToolCopy.progress(1234, 4614, 7, 3, 12))
        assertEquals("12 done, 3 found  2 s", ToolCopy.progress(12, null, 3, 0, 2))
        assertEquals("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths",
            ToolCopy.rowHistory("2 hours ago", ToolCopy.DIR_BRUTEFORCE, "5000", ToolCopy.bruteDone(7, 4614, 0)))
        assertEquals("Wordlist not found, common-short used, 7 found in 312 paths", ToolCopy.bruteFallback(ToolCopy.bruteDone(7, 312, 0)))
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", ToolCopy.smbSummary("SMB 3.0.2", true, true, false))
        assertEquals("200  1.2 KB", ToolCopy.pathDetail(200, ToolCopy.size(1229, false), null))
        assertEquals("301  0 B  to /admin/", ToolCopy.pathDetail(301, ToolCopy.size(0, false), "/admin/"))
        assertEquals("12 days 4 h", ToolCopy.uptime((12L * 86_400 + 4L * 3_600) * 100))
        assertEquals("up, ethernetCsmacd, 1 Gbit/s, 00:11:22:33:44:55", ToolCopy.interfaceDetail(1, 6, 1_000_000_000L, "00:11:22:33:44:55"))
        assertEquals("Cookie, Secure, HttpOnly", ToolCopy.cookieDetail(secure = true, httpOnly = true, sameSite = null))
    }

    private fun everyString(): List<String> = buildList {
        ToolKeys.BUILT_IN.forEach { add(ToolCopy.toolName(it)) }
        for (n in listOf(1, 3)) { add(ToolCopy.available(n)); add(ToolCopy.rowDescription(n, false)); add(ToolCopy.rowDescription(n, true)) }
        for (snmp in listOf(false, true)) {
            val p = ToolCopy.portLabel(5000, snmp)
            add(p); add(ToolCopy.rowHistory("just now", ToolCopy.SNMP_WALK, p, "gw, 3 interfaces")); add(ToolCopy.blockHistory("yesterday", p, "Done"))
            add(ToolCopy.chipDescription(ToolCopy.TLS_CERTIFICATE, p)); add(ToolCopy.pinnedLabel(ToolCopy.SNMP_WALK, p))
        }
        addAll(listOf(ToolCopy.TOOLS, ToolCopy.NO_TOOLS_RUN, ToolCopy.NOT_RUN_YET, ToolCopy.RESULTS, ToolCopy.resultsDescription(ToolCopy.HTTP_FINGERPRINT),
            ToolCopy.RUN_TOOL, ToolCopy.BUSY_TOOL, ToolCopy.BUSY_PORT_SCAN, ToolCopy.BUSY_TOO_MANY, ToolCopy.SCAN_REFUSED, ToolCopy.RUNNING_MARKER,
            ToolCopy.rowSuffix(true), ToolCopy.rowSuffix(false), ToolCopy.STARTING, ToolCopy.STOP_DESCRIPTION, ToolCopy.finished(ToolCopy.SMB_SESSION, "x"),
            ToolCopy.NETWORK_CHANGED, ToolCopy.BIND_FAILED, ToolCopy.failed("Connection reset"), ToolCopy.interrupted(4)))
        for (f in 0..2) for (s in 0..2) ToolCopy.quickEnd(f, s)?.let(::add)
        for (total in listOf<Int?>(null, 4614)) for (e in listOf(0, 3)) add(ToolCopy.progress(1234, total, 7, e, 12))
        ToolRunStatus.entries.forEach { add(ToolCopy.statusLabel(it)) }
        for (t in listOf<Int?>(null, 4614)) for (d in listOf(1, 12)) add(ToolCopy.stopped(d, t, 7))
        for (e in listOf(0, 1, 3)) for (p in listOf(1, 4614)) add(ToolCopy.bruteDone(7, p, e))
        addAll(listOf(ToolCopy.BRUTE_CAP, ToolCopy.BRUTE_RUNAWAY, ToolCopy.BRUTE_NO_ANSWER, ToolCopy.bruteStoppedAnswering(1), ToolCopy.bruteStoppedAnswering(51),
            ToolCopy.BRUTE_NO_WORDLIST, ToolCopy.failed(ToolCopy.BRUTE_NO_WORDLIST), ToolCopy.bruteFallback(ToolCopy.bruteDone(7, 312, 0))))
        addAll(listOf(ToolCopy.probeLine("redis-info", 312, "Redis 7.2.4"), ToolCopy.probeLine("ssh", 1, null), ToolCopy.probeLine("null", 0, null),
            ToolCopy.stackLine("home-assistant", "Home Assistant", null), ToolCopy.stackLine("jenkins", "Jenkins", "2.4"),
            ToolCopy.identifySummary("Redis", "Redis", "7.2.4", 3), ToolCopy.identifySummary("HTTP", "nginx", null, 1), ToolCopy.identifySummary("SSH", "OpenSSH", "8.2p1", 2),
            ToolCopy.identifyNone(1), ToolCopy.identifyNone(5), ToolCopy.identifyProgress(2, 6),
            ToolCopy.IDENTIFY_ALL_NONE, ToolCopy.identifyAllProgress(0, 5), ToolCopy.identifyAllProgress(5, 5), ToolCopy.identifyAllSummary(3, 7),
            ToolCopy.identifyAllLine(22, true, "SSH", "OpenSSH", "9.6p1"), ToolCopy.identifyAllLine(6379, true, "Redis", "Redis", null), ToolCopy.identifyAllLine(9999, false, "Unknown", null, null),
            ToolCopy.IDENTIFY_PORT, ToolCopy.IDENTIFYING, ToolCopy.identifyPortDescription(443),
            ToolCopy.pinnedLabel(ToolCopy.IDENTIFY_ALL, ToolCopy.portLabel(0, false))))
        for (n in listOf(1, 6)) { add(ToolCopy.fingerprintSummary("Pi-hole", n)); add(ToolCopy.fingerprintSummary(null, n)) }
        addAll(listOf(ToolCopy.FINGERPRINT_NO_RESPONSE, ToolCopy.tlsSummary("unifi.local", date, false), ToolCopy.tlsSummary("unifi.local", date, true), ToolCopy.TLS_NO_HANDSHAKE))
        for (a in listOf(true, false)) for (b in listOf(true, false)) for (c in listOf(true, false)) add(ToolCopy.smbSummary("SMB 2.1", a, b, c))
        addAll(listOf(ToolCopy.SMB_NO_DIALECT, ToolCopy.SMB_NO_RESPONSE, ToolCopy.snmpSummary("gw", 1), ToolCopy.snmpSummary("gw", 5), ToolCopy.SNMP_NO_ANSWER))
        addAll(listOf(ToolCopy.WORDLIST, ToolCopy.entries(1), ToolCopy.entries(312), ToolCopy.MANAGE_WORDLISTS, ToolCopy.EXTENSIONS, ToolCopy.EXTENSIONS_PLACEHOLDER,
            ToolCopy.EXTENSIONS_SUPPORT, ToolCopy.METHOD, ToolCopy.HEAD_THEN_GET, ToolCopy.GET_ONLY, ToolCopy.CONCURRENCY, ToolCopy.TIMEOUT, ToolCopy.seconds(3),
            ToolCopy.REPORT_CODES, ToolCopy.DEFAULT_CODES_TEXT, ToolCopy.FILTER, ToolCopy.FILTER_SUPPORT, ToolCopy.paths(1), ToolCopy.paths(312), ToolCopy.RUN,
            ToolCopy.ERR_EXT_CHARS, ToolCopy.ERR_EXT_TOO_MANY, ToolCopy.ERR_CODE_RANGE, ToolCopy.ERR_CODE_EMPTY, ToolCopy.WORDLIST_FALLBACK, ToolCopy.BRUTE_FALLBACK_PREFIX, ToolCopy.EXPAND, ToolCopy.SECONDS_UNIT))
        addAll(listOf(ToolCopy.NO_DEVICE, ToolCopy.resultsTitle(ToolCopy.DIR_BRUTEFORCE, null, 0), ToolCopy.exportSubject(ToolCopy.DIR_BRUTEFORCE, null, 0), ToolCopy.resultsTitle(ToolCopy.DIR_BRUTEFORCE, "10.0.3.10", 5000), ToolCopy.resultsStatus("Done", "2 hours ago", null),
            ToolCopy.resultsStatus("Done", "2 hours ago", "chnas.local"), ToolCopy.resultsRunning("12 of 300, 2 found  3 s"), ToolCopy.runOf(1, 3),
            ToolCopy.OLDER_RUN, ToolCopy.NEWER_RUN, ToolCopy.OPEN_WITH, ToolCopy.COPY, ToolCopy.SHARE, ToolCopy.COPIED, ToolCopy.EMPTY_RUNNING, ToolCopy.NO_FINDINGS,
            ToolCopy.openDescription("http://10.0.3.10:5000/admin"), ToolCopy.copyDescription("/admin")))
        addAll(listOf(ToolCopy.exportSubject(ToolCopy.TLS_CERTIFICATE, "10.0.3.10", 443), ToolCopy.exportTitle(ToolCopy.TLS_CERTIFICATE),
            ToolCopy.exportTarget("10.0.3.10:443"), ToolCopy.exportNameSent("chnas.local"), ToolCopy.exportStarted("2026-10-20 14:03:11"),
            ToolCopy.exportFinished("2026-10-20 14:04:02"), ToolCopy.exportFinished(null), ToolCopy.exportStatus("Done"), ToolCopy.exportSummary("7 found in 4614 paths")))
        for (ext in listOf(emptyList(), listOf("php", "html"))) for (m in listOf(Triple(false, false, true), Triple(true, false, false), Triple(true, true, true)))
            add(ToolCopy.exportOptions(ToolCopy.optionsLine("common-short", 312, ext, m.first, m.second, 8, 3, listOf(200, 204, 301), m.third)))
        for (size in listOf(ToolCopy.size(0, false), ToolCopy.size(1023, false), ToolCopy.size(1229, false), ToolCopy.size(null, true), null))
            for (loc in listOf(null, "/login")) add(ToolCopy.pathDetail(302, size, loc))
        addAll(listOf(ToolCopy.GET_ROOT, ToolCopy.getRootTo("https://10.0.3.9:5001/"), ToolCopy.getUrl("https://10.0.3.9:5001/"),
            ToolCopy.cookieDetail(false, false, null), ToolCopy.cookieDetail(true, true, "Lax"), ToolCopy.PAGE_TITLE, ToolCopy.GENERATOR_META,
            ToolCopy.rules(1), ToolCopy.rules(7), ToolCopy.DISALLOWED, ToolCopy.ALLOWED, ToolCopy.urls(1), ToolCopy.urls(40), ToolCopy.contact("mailto:a@b.c"),
            ToolCopy.PRESENT, ToolCopy.ERROR_SIGNATURE, ToolCopy.matched("title", "Synology DiskStation")))
        addAll(listOf(ToolCopy.SUBJECT, ToolCopy.ISSUER, ToolCopy.SAN_DNS, ToolCopy.SAN_IP, ToolCopy.validityRange("3 Jan 2026", "3 Jan 2027"), ToolCopy.VALID,
            ToolCopy.EXPIRED, ToolCopy.NOT_YET_VALID, ToolCopy.expiresIn(1), ToolCopy.expiresIn(12), ToolCopy.keyRsa(2048), ToolCopy.keyEc(256), ToolCopy.keyDsa(2048),
            ToolCopy.ED25519, ToolCopy.ED448, ToolCopy.PUBLIC_KEY, ToolCopy.SIGNATURE_ALGORITHM, ToolCopy.SHA256_FINGERPRINT, ToolCopy.chainCertificate(1, 2), ToolCopy.andMore(3),
            ToolCopy.PROTOCOL_AND_CIPHER, ToolCopy.CERTIFICATE, ToolCopy.SELF_SIGNED, ToolCopy.WEAK_RSA, ToolCopy.SHA1_SIGNED))
        addAll(listOf(ToolCopy.DIALECT, ToolCopy.SIGNING, ToolCopy.REQUIRED, ToolCopy.ENABLED_NOT_REQUIRED, ToolCopy.SESSION, ToolCopy.ANONYMOUS_ALLOWED,
            ToolCopy.ANONYMOUS_REFUSED, ToolCopy.GUEST_ALLOWED, ToolCopy.GUEST_MAPPED, ToolCopy.GUEST_REFUSED, ToolCopy.NETBIOS_NAME, ToolCopy.NETBIOS_DOMAIN,
            ToolCopy.DNS_NAME, ToolCopy.DNS_DOMAIN, ToolCopy.OS_BUILD))
        listOf(0x0202, 0x0210, 0x0300, 0x0302).forEach { add(ToolCopy.dialectName(it)!!) }
        addAll(listOf(ToolCopy.SYS_DESCR, ToolCopy.SYS_OBJECT_ID, ToolCopy.UPTIME, ToolCopy.SYS_CONTACT, ToolCopy.SYS_NAME, ToolCopy.SYS_LOCATION,
            ToolCopy.uptime(100L * 86_400 * 2 + 360_000), ToolCopy.uptime(100L * 86_400 + 360_000), ToolCopy.uptime(1_152_000), ToolCopy.INTERFACE_ADDRESS,
            ToolCopy.address("10.0.3.1", 24)))
        for (st in listOf<Long?>(null, 1, 7)) for (ty in listOf<Long?>(null, 6, 999)) for (sp in listOf<Long?>(null, 0, 100_000_000, 10_000_000_000))
            add(ToolCopy.interfaceDetail(st, ty, sp, if (st == 1L) "00:11:22:33:44:55" else null))
        addAll(listOf(WordlistCopy.BACK, WordlistCopy.TITLE, WordlistCopy.IMPORT, WordlistCopy.IMPORTING, WordlistCopy.BUILT_IN, WordlistCopy.IMPORTED,
            WordlistCopy.entriesBuiltIn(1), WordlistCopy.entriesBuiltIn(312), WordlistCopy.entriesImported(4614, date), WordlistCopy.DELETE,
            WordlistCopy.deletePrompt("my list"), WordlistCopy.CANCEL, WordlistCopy.NO_IMPORTED, WordlistCopy.deleteDescription("my list"),
            WordlistCopy.imported("my list", 1, 0), WordlistCopy.imported("my list", 4614, 3), WordlistCopy.TOO_LARGE, WordlistCopy.TOO_MANY,
            WordlistCopy.EMPTY_FILE, WordlistCopy.UNREADABLE, WordlistCopy.DEFAULT_NAME, WordlistCopy.recoveredName(1), WordlistCopy.recoveredName(12), WordlistCopy.SETTINGS_SECTION, WordlistCopy.SETTINGS_ROW,
            WordlistCopy.settingsSupport(0), WordlistCopy.settingsSupport(2), WordlistCopy.settingsSupport(null), WordlistCopy.LOADING))
        addAll(listOf(WordlistCopy.IMPORT_URL, WordlistCopy.URL_LABEL, WordlistCopy.URL_PLACEHOLDER, WordlistCopy.URL_IMPORT, WordlistCopy.ERR_URL,
            WordlistCopy.ERR_URL_USER, WordlistCopy.ERR_URL_LONG, WordlistCopy.CANCELLED, WordlistCopy.fetching(0, null), WordlistCopy.fetching(1_258_291, 3_565_158),
            WordlistCopy.fetching(10_485_760, null)))
        val failures = listOf(FetchFailure.UnknownHost, FetchFailure.NoAnswer, FetchFailure.Tls, FetchFailure.Connection, FetchFailure.Status(404),
            FetchFailure.Status(503), FetchFailure.NotText("text/html"), FetchFailure.NotText(null), FetchFailure.Insecure, FetchFailure.LocalTarget,
            FetchFailure.TooManyRedirects)
        failures.forEach { add(WordlistImport.message(UrlImportResult.Failed(it))) }
        listOf<ImportResult>(ImportResult.TooLarge, ImportResult.TooMany, ImportResult.Empty, ImportResult.Unreadable).forEach { add(WordlistImport.message(UrlImportResult.Stored(it))) }
        add(WordlistImport.message(UrlImportResult.Cancelled))
    }

    @Test fun theFallbackLineSplitsOffTheSummaryAndPlainSummariesPass() {
        assertEquals("Wordlist not found, common-short used" to "7 found in 312 paths", ToolCopy.splitFallback(ToolCopy.bruteFallback(ToolCopy.bruteDone(7, 312, 0))))
        assertEquals(null to "7 found in 312 paths", ToolCopy.splitFallback(ToolCopy.bruteDone(7, 312, 0)))
    }

    @Test fun `url import strings read as the spec table`() {
        assertEquals("Fetching 1.2 of 3.4 MB", WordlistCopy.fetching(1_258_291, 3_565_158))
        assertEquals("Fetching 1.2 MB", WordlistCopy.fetching(1_258_291, null))
        assertEquals("Could not fetch: the server answered 404", WordlistImport.message(UrlImportResult.Failed(FetchFailure.Status(404))))
        assertEquals("Could not fetch: not a text file (text/html)", WordlistImport.message(UrlImportResult.Failed(FetchFailure.NotText("text/html"))))
        assertEquals("Could not fetch: not a text file", WordlistImport.message(UrlImportResult.Failed(FetchFailure.NotText(null))))
        assertEquals("That file is too large (limit 10 MB)", WordlistImport.message(UrlImportResult.Stored(ImportResult.TooLarge)))
        assertEquals("Import file", WordlistCopy.IMPORT)
        assertFalse("FETCH_OTHER_SITE" in WordlistCopy::class.java.declaredFields.map { it.name })   // spec 7.2: removed from the draft
    }
    @Test fun `route header strings`() {
        val routeTemplates = listOf("via Wi-Fi", "via Ethernet", "via VPN or other route").map { Regex(it) }
        val shown = ToolRoute.entries.map { ToolCopy.routeLine(it)!! }
        shown.forEach { s -> assertTrue("not in the route table: '$s'", routeTemplates.any { it.matches(s) }); assertFalse(s.contains('\u2014')) }
        assertEquals(listOf("via Wi-Fi", "via Ethernet", "via VPN or other route"), shown)
        assertNull("a Device-screen run shows no route", ToolCopy.routeLine(null))
    }
}
