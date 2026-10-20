package com.northwalessolutions.netprobe.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun flagSet() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test fun flagFollowsTheExtendedRunAndIsClearedOnDispose() {
        var active by mutableStateOf(false)
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) KeepScreenOn(active) }
        rule.waitForIdle(); assertFalse(flagSet())
        active = true; rule.waitForIdle(); assertTrue(flagSet())
        active = false; rule.waitForIdle(); assertFalse(flagSet())
        active = true; rule.waitForIdle(); shown = false; rule.waitForIdle(); assertFalse(flagSet())
    }
}
