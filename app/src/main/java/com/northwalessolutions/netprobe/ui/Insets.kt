package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * The bottom inset a root screen must clear (3D spec 1.4): the bar's height plus the gesture area while the bar shows, else the gesture
 * area. NetProbeShell provides it; null (a test, or a screen outside the shell) means the navigation bars alone, which is what every
 * screen used before the bar existed.
 */
val LocalBarInset = compositionLocalOf<WindowInsets?> { null }

/** What a root screen's Scaffold uses where it used `WindowInsets.navigationBars.union(WindowInsets.ime)`: the bar inset joined with the keyboard. */
@Composable
fun rootInsets(): WindowInsets = (LocalBarInset.current ?: WindowInsets.navigationBars).union(WindowInsets.ime)
