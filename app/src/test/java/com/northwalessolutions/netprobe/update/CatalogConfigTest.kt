package com.northwalessolutions.netprobe.update

import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.isScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

class CatalogConfigTest {
    @Test fun `the catalog urls are the repository's catalog folder on https`() {
        assertEquals("https://raw.githubusercontent.com/BL457/netprobe/master/catalog/index.json", UpdateConfig.CATALOG_INDEX_URL)
        assertEquals("https://raw.githubusercontent.com/BL457/netprobe/master/catalog/", UpdateConfig.CATALOG_BASE_URL)
        assertTrue(UpdateRedirects.allowedStart(URL(UpdateConfig.CATALOG_INDEX_URL)))
    }

    @Test fun `a catalog script counts as a script tool`() {
        assertTrue(ToolSource.Catalog(File("a.js"), "x").isScript); assertTrue(ToolSource.Script(File("a.js"), "x").isScript); assertFalse(ToolSource.BuiltIn.isScript)
    }
}
