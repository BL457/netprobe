package com.northwalessolutions.netprobe.ui.manual

/** Every manual tools string (3C spec 7.2). Templates live in CopyTableTest; Run, Stop and the results sheet reuse ToolCopy and HomeCopy. */
object ManualCopy {
    const val IP = "IP address"
    const val IP_PLACEHOLDER = "10.0.3.20"
    const val PORT = "Port"
    const val PORT_PLACEHOLDER = "443"
    fun toolRow(name: String) = "Tool: $name"
    const val USE_HTTPS = "Use HTTPS"
    const val FIXED_SMB = "Uses port 445"
    const val FIXED_SNMP = "Uses UDP 161"
    const val FIXED_ALL = "Uses the open ports stored for this address"
    fun knownDevice(name: String) = "Known device: $name, results also show on its page"
    const val NO_LAN = "Connect to Wi-Fi or Ethernet to run a tool"
    const val ERR_IP = "Enter an address like 10.0.3.20"
    const val ERR_ADDRESS = "That address cannot be used"
    const val ERR_PORT_NEEDED = "This tool needs a port"
    const val ERR_PORT_RANGE = "Use a port from 1 to 65535"
    const val HISTORY = "Recent runs on this network"
    const val HISTORY_EMPTY = "No tools run on this network yet"
    private fun at(ip: String, portLabel: String) = if (portLabel == com.northwalessolutions.netprobe.ui.tools.ToolCopy.ALL_PORTS_LABEL) ip else "$ip:$portLabel"
    fun historyRow(tool: String, ip: String, portLabel: String) = "$tool on ${at(ip, portLabel)}"
    /** A run that had no device (schema 6). */
    fun historyRowNoDevice(tool: String, noDevice: String) = "$tool on $noDevice"
    fun historyDescriptionNoDevice(tool: String, noDevice: String) = "Show $tool results for $noDevice"
    fun historyLine(summary: String, relative: String) = "$summary, $relative"
    fun historyDescription(tool: String, ip: String, portLabel: String) = "Show $tool results for ${at(ip, portLabel)}"
}
