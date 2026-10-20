package com.northwalessolutions.netprobe.ui.home

/** Every Home string (Radar spec section 8). This file is the only place Home wording lives. */
object HomeCopy {
    const val SCAN = "Scan"
    const val STOP = "Stop"
    const val FOUND = "found"
    const val NO_DEVICES = "No devices found"
    const val HERO_TITLE = "Nothing scanned yet"
    const val HERO_NO_NETWORK = "No network"
    const val HERO_NO_NETWORK_SUB = "Connect to Wi-Fi or Ethernet to scan"
    const val STARTING = "starting"
    const val QUICK_SCANNING = "Quick scanning"
    const val SEARCH_PLACEHOLDER = "Search name, IP, vendor or MAC"
    const val EMPTY_HERO = "Devices appear here as they are found"
    const val EMPTY_SCANNING = "Looking for devices"
    const val EMPTY_NONE = "No devices found. Check the network and scan again."
    const val NETWORK_CHANGED = "Network changed, scan stopped"
    const val JUST_NOW = "just now"
    const val YESTERDAY = "yesterday"

    fun heroSubtitle(subnet: String) = "Tap to find what is on $subnet"
    fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    fun lastScan(relative: String) = "Last scan $relative"
    fun scannedIn(seconds: Long) = "Scanned in $seconds s"
    fun onAnotherSubnet(k: Int) = "$k on another subnet"
    fun emptySearch(query: String) = "No devices match \"$query\""
    fun emptyFilter(label: String) = "No devices marked $label"
    fun largeNetwork(prefix: Int) = "Large network (/$prefix). Scanned your /24 only. Widen in Settings."
    fun probing(probed: Int, total: Int, elapsedS: Long) = "probing $probed of $total  $elapsedS s"
    fun identifying(found: Int, elapsedS: Long) = "identifying ${devices(found)}  $elapsedS s"
}
