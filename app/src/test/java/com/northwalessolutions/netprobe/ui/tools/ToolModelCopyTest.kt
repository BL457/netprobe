package com.northwalessolutions.netprobe.ui.tools

import org.junit.Assert.*
import org.junit.Test

/** Every ToolModelCopy string against its template, with no em-dash and no disclaimer. */
class ToolModelCopyTest {
    private val templates = listOf(
        "Required", "Enter a whole number", "Use -?\\d+ or more", "Use -?\\d+ or less", "Enter an IPv4 address such as 192\\.168\\.1\\.10", "Use a port from 1 to 65535",
        "Choose one of the listed options", "Use at most \\d+ items", "The input name \\w+ is reserved", "The input name \\w+ is used twice", "The input \\w+ has no options",
        "Device", "Port", "Use HTTPS", "This tool needs a device", "Community", "Version",
        "Tries a wordlist of paths on a web server and lists the ones that answer", "Reads the home page, headers, cookies and site files to name the web server",
        "Reads the certificate and TLS session a port presents", "Checks the SMB dialect, signing, and whether anonymous or guest sessions are allowed",
        "Reads the system details and interfaces with the public community", "Probes one port to name the service running on it",
        "Probes every open port of a device to name its services",
    ).map { Regex(it) }

    @Test fun `every string is in the table, has no em-dash, and the helpers read as written`() {
        val all = listOf(ToolModelCopy.REQUIRED, ToolModelCopy.NOT_A_NUMBER, ToolModelCopy.tooSmall(1), ToolModelCopy.tooSmall(-3), ToolModelCopy.tooBig(16),
            ToolModelCopy.BAD_IP, ToolModelCopy.BAD_PORT, ToolModelCopy.NOT_AN_OPTION, ToolModelCopy.tooManyItems(8), ToolModelCopy.reservedName("ip"),
            ToolModelCopy.duplicateName("a"), ToolModelCopy.noOptions("c"), ToolModelCopy.IP_LABEL, ToolModelCopy.PORT_LABEL, ToolModelCopy.TLS_LABEL,
            ToolModelCopy.NO_TARGET, ToolModelCopy.SNMP_COMMUNITY, ToolModelCopy.SNMP_VERSION, ToolModelCopy.DIR_BRUTEFORCE_DESCRIPTION,
            ToolModelCopy.HTTP_FINGERPRINT_DESCRIPTION, ToolModelCopy.TLS_CERTIFICATE_DESCRIPTION, ToolModelCopy.SMB_SESSION_DESCRIPTION,
            ToolModelCopy.SNMP_WALK_DESCRIPTION, ToolModelCopy.IDENTIFY_DESCRIPTION, ToolModelCopy.IDENTIFY_ALL_DESCRIPTION)
        all.forEach { s -> assertTrue("not in the table: '$s'", templates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains(0x2014.toChar())) }
        assertEquals("Use 1 or more", ToolModelCopy.tooSmall(1)); assertEquals("Use 16 or less", ToolModelCopy.tooBig(16))
    }
}
