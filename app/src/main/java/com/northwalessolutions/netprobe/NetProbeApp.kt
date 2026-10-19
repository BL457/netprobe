package com.northwalessolutions.netprobe

import android.app.Application

class NetProbeApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
