package com.northwalessolutions.netprobe.wordlists

import org.junit.Assert.*
import org.junit.Test

/**
 * spec 5.6.1: each list in its range, parses to itself, ASCII, no leading slash or duplicate, globally sorted, and (plan review G10)
 * no entry whose GET can change a device's state: a reboot, firmware or apply handler, a logout, a save. Brute-force sends HEAD and
 * GET to every entry on the Director's and his clients' devices, so an action endpoint in a bundled list is a footgun.
 */
class BundledWordlistsTest {
    private companion object {
        val STATE_CHANGING = Regex("(?i)(reboot|restart|shutdown|reset|factory|upgrade|apply|logout|logoff|signout|webproc|formlogin|setup\\.cgi|(^|/)save($|\\.))")
    }
    private fun read(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun check(name: String, min: Int, max: Int) {
        val text = read(name)
        assertFalse("$name must use LF only", text.contains("\r"))
        val lines = text.split("\n").filter { it.isNotEmpty() }
        assertTrue("$name has ${lines.size} entries, want $min..$max", lines.size in min..max)
        assertEquals("$name has a blank or trailing line", lines, text.trimEnd('\n').split("\n"))
        assertEquals("$name has duplicates", lines.size, lines.toSet().size)
        lines.forEach { assertFalse("$name entry has a leading slash: $it", it.startsWith("/")) }
        lines.forEach { l -> assertTrue("$name entry not ascii: $l", l.all { it.code in 33..126 }) }
        lines.forEach { assertFalse("$name entry can change device state: $it", STATE_CHANGING.containsMatchIn(it)) }
        assertEquals("$name is not sorted (LC_ALL=C)", lines.sortedWith(compareBy { it }), lines)
        val parsed = WordlistParser.parse(text.toByteArray(Charsets.UTF_8).inputStream())
        assertEquals("$name does not parse to itself", lines, parsed.entries); assertEquals(0, parsed.skipped)
    }
    @Test fun `common short`() { check("wordlist_common_short.txt", 250, 350) }
    @Test fun `the deny pattern catches the endpoints it exists for`() {
        for (bad in listOf("reboot.html", "cgi-bin/webproc", "apply.cgi", "boaform/admin/formLogin", "logout", "save", "admin/factory_reset"))
            assertTrue(bad, STATE_CHANGING.containsMatchIn(bad))
        for (fine in listOf("settings", "setup.html", "saved-searches", "firmware")) assertFalse(fine, STATE_CHANGING.containsMatchIn(fine))
    }
    @Test fun `admin panels`() { check("wordlist_admin_panels.txt", 120, 180) }
    @Test fun `api paths`() { check("wordlist_api_paths.txt", 120, 180) }
}
