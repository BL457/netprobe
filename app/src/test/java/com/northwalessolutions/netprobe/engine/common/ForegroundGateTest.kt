package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 3.3: no new probe starts while NetProbe is in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundGateTest {
    @Test fun `await returns at once in the foreground`() = runTest {
        ForegroundGate(MutableStateFlow(true)).await()
    }
    @Test fun `await waits in the background and resumes when the app returns`() = runTest {
        val foreground = MutableStateFlow(false)
        var passed = false
        backgroundScope.launch { ForegroundGate(foreground).await(); passed = true }
        runCurrent()
        assertFalse("must wait while in the background", passed)
        foreground.value = true; runCurrent()
        assertTrue(passed)
    }
}
