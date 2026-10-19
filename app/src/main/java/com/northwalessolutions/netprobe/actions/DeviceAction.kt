package com.northwalessolutions.netprobe.actions

sealed class DeviceAction(val label: String) {
    data class OpenUri(val uriLabel: String, val uri: String, val hasHandler: Boolean) : DeviceAction(uriLabel)
    data class Copy(val copyLabel: String, val text: String) : DeviceAction(copyLabel)
    data class OpenWith(val uri: String) : DeviceAction("Open with...")
}
fun interface HandlerResolver { fun hasHandler(uri: String): Boolean }
