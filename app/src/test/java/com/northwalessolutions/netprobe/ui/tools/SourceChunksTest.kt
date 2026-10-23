package com.northwalessolutions.netprobe.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChunksTest {
    @Test fun `short lines stay as they are`() {
        assertEquals(listOf("a", "", "b c"), SourceChunks.of("a\n\nb c"))
        assertEquals(listOf("a", "b"), SourceChunks.of("a\r\nb"))
        assertEquals(listOf(""), SourceChunks.of(""))
    }

    @Test fun `a long line is cut into pieces and nothing is lost`() {
        val line = (0 until 2_500).joinToString("") { (it % 10).toString() }
        val chunks = SourceChunks.of("x\n$line\ny", max = 1_000)
        assertEquals(listOf("x", line.substring(0, 1000), line.substring(1000, 2000), line.substring(2000), "y"), chunks)
        assertTrue(chunks.all { it.length <= 1_000 })
    }

    @Test fun `a whole script of the largest size is chunked`() {
        val source = "// padding\n".repeat(20_000)
        assertEquals(20_001, SourceChunks.of(source).size)
        val single = "x".repeat(250_000)
        assertEquals(250, SourceChunks.of(single).size)
    }
}
