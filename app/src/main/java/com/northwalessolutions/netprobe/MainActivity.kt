package com.northwalessolutions.netprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.northwalessolutions.netprobe.ui.NetProbeNav
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme

private const val BAR_COLOUR = 0xFF16202B.toInt()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Bars use the background token with light icons; screens apply the insets themselves.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(BAR_COLOUR), navigationBarStyle = SystemBarStyle.dark(BAR_COLOUR))
        super.onCreate(savedInstanceState)
        val container = (application as NetProbeApp).container
        // NetProbe is single-activity, so this activity's own lifecycle is the app's foreground state (spec 4.8). No new dependency.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { container.foreground.value = false }
        })
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
    }
}
