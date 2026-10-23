package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.update.UpdateManifest
import com.northwalessolutions.netprobe.update.UpdateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBannerRulesTest {
    private val manifest = UpdateManifest(8, "0.3.5", "https://example.invalid/a.apk", "00")

    @Test fun idleIsHidden() {
        assertFalse(UpdateBannerRules.visible(UpdateState.Idle))
    }

    @Test fun everyOtherStateIsVisible() {
        assertTrue(UpdateBannerRules.visible(UpdateState.Available(manifest)))
        assertTrue(UpdateBannerRules.visible(UpdateState.Downloading(manifest, null)))
        assertTrue(UpdateBannerRules.visible(UpdateState.Verifying(manifest)))
        assertTrue(UpdateBannerRules.visible(UpdateState.Ready(manifest, java.io.File("a.apk"))))
        assertTrue(UpdateBannerRules.visible(UpdateState.Failed(manifest, "x")))
    }
}
