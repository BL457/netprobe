package com.northwalessolutions.netprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.northwalessolutions.netprobe.ui.NetProbeNav
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NetProbeApp).container
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
    }
}
