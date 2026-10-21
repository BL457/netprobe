package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** House rule: no em-dashes anywhere (code, comments, UI strings). */
class NoEmDashTest {
    @Test fun `no Kotlin source file contains an em dash`() {
        val dash = 0x2014.toChar()
        val root = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains(dash) }.map { it.path }.toList()
        assertTrue("em dash in: $hits", hits.isEmpty())
    }
}
