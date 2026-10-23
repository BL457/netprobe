package com.northwalessolutions.netprobe.ui.tools

/**
 * Strings of the tool model (tools platform B1): input errors from ToolInputs.validate, the implicit device inputs, the built-in tool
 * descriptions and the no-target failure. Templates live in ToolModelCopyTest.
 */
object ToolModelCopy {
    // Input errors, shown under the field
    const val REQUIRED = "Required"
    const val NOT_A_NUMBER = "Enter a whole number"
    fun tooSmall(min: Int) = "Use $min or more"
    fun tooBig(max: Int) = "Use $max or less"
    const val BAD_IP = "Enter an IPv4 address such as 192.168.1.10"
    const val BAD_PORT = "Use a port from 1 to 65535"
    const val NOT_AN_OPTION = "Choose one of the listed options"

    fun tooManyItems(max: Int) = "Use at most $max items"
    fun reservedName(name: String) = "The input name $name is reserved"
    fun duplicateName(name: String) = "The input name $name is used twice"
    fun noOptions(name: String) = "The input $name has no options"

    // The implicit device inputs (spec D7)
    const val IP_LABEL = "Device"
    const val PORT_LABEL = "Port"
    const val TLS_LABEL = "Use HTTPS"

    // A tool that needs a device was started without one
    const val NO_TARGET = "This tool needs a device"

    // SNMP walk inputs
    const val SNMP_COMMUNITY = "Community"
    const val SNMP_VERSION = "Version"

    // Built-in tool descriptions
    const val DIR_BRUTEFORCE_DESCRIPTION = "Tries a wordlist of paths on a web server and lists the ones that answer"
    const val HTTP_FINGERPRINT_DESCRIPTION = "Reads the home page, headers, cookies and site files to name the web server"
    const val TLS_CERTIFICATE_DESCRIPTION = "Reads the certificate and TLS session a port presents"
    const val SMB_SESSION_DESCRIPTION = "Checks the SMB dialect, signing, and whether anonymous or guest sessions are allowed"
    const val SNMP_WALK_DESCRIPTION = "Reads the system details and interfaces with the public community"
    const val IDENTIFY_DESCRIPTION = "Probes one port to name the service running on it"
    const val IDENTIFY_ALL_DESCRIPTION = "Probes every open port of a device to name its services"
}
