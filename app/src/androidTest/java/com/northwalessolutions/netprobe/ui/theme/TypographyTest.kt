package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A font file without the tnum feature would make IPs and counts jitter; this catches it on the device. */
@RunWith(AndroidJUnit4::class)
class TypographyTest {
    @Test fun tabularStylesGiveEqualDigitWidths() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val measurer = TextMeasurer(createFontFamilyResolver(ctx), Density(ctx), LayoutDirection.Ltr)
        for ((name, style) in listOf("data" to NetProbeText.data, "port" to NetProbeText.port, "count" to NetProbeText.count, "radarCount" to NetProbeText.radarCount)) {
            assertEquals(name, measurer.measure("1111", style).size.width, measurer.measure("0000", style).size.width)
        }
    }
}
