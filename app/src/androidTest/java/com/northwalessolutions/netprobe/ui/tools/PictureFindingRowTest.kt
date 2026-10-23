package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The thumbnail row and the full-size viewer (tools platform spec section 12), fake rows only; the picture file itself is not needed. */
@RunWith(AndroidJUnit4::class)
class PictureFindingRowTest {
    @get:Rule val rule = createComposeRule()
    private val row = FindingRow(1, "NAS", "Synology DSM", "http://192.168.1.20:5000/", "page", "7/1.jpg")

    @Test fun aPictureFindingIsAThumbnailRowWithItsCaptionAndTheViewerOpensOnTap() {
        val copied = ArrayList<FindingRow>()
        rule.setContent { NetProbeTheme { FindingRowItem(row, onCopy = { copied += it }, onOpen = {}) } }
        rule.onNodeWithTag("thumb:1").assertExists()
        rule.onNodeWithTag("caption:1").assertTextEquals("NAS")
        rule.onNodeWithTag("pictureViewer").assertDoesNotExist()
        rule.onNodeWithTag("thumb:1").performClick()
        rule.onNodeWithTag("pictureViewer").assertExists()
        rule.onNodeWithTag("viewerCopyUrl").performClick()
        assertEquals(listOf(row), copied)
        rule.onNodeWithTag("viewerClose").performClick()
        rule.onNodeWithTag("pictureViewer").assertDoesNotExist()
    }

    @Test fun aRowWithoutAPictureIsTheUsualRow() {
        rule.setContent { NetProbeTheme { FindingRowItem(FindingRow(2, "/admin", null, null), onCopy = {}, onOpen = {}) } }
        rule.onNodeWithTag("thumb:2").assertDoesNotExist()
        rule.onNodeWithTag("copy:2").assertExists()
    }
}
