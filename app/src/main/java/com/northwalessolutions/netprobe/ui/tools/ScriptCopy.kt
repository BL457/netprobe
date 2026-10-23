package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.AbortReason

/**
 * Strings of the script engine (tools platform B2): why a run ended, errors a script can catch, descriptor errors and the scope refusals.
 * The templates live in ScriptCopyTest. Nothing here echoes more than a short, cut excerpt of script-supplied text.
 */
object ScriptCopy {
    // Why a run ended (shown as "Failed: ...")
    fun abortSummary(reason: AbortReason): String = when (reason) {
        AbortReason.CANCELLED -> "Stopped"
        AbortReason.WALL_CLOCK -> "Script ran for more than 10 minutes"
        AbortReason.INSTRUCTIONS -> "Script used too much processing"
        AbortReason.CALLS -> "Script made more than 20,000 network calls"
        AbortReason.TRANSFER -> "Script transferred more than 50 MB"
        AbortReason.FINDINGS -> "Script produced more than 5,000 findings"
        AbortReason.FINDING_BYTES -> "Script findings are larger than 2 MB"
        AbortReason.MEMORY -> "Script used too much memory"
        AbortReason.STALLED -> "Script stopped responding"
    }
    fun findingsSummary(count: Int) = if (count == 1) "1 finding" else "$count findings"
    fun badStatus(status: String) = "Script returned the status $status, expected done or failed"
    fun scriptError(message: String, line: Int) = if (line > 0) "$message (line $line)" else message
    fun internalError(kind: String) = "Script engine error: $kind"
    const val SCRIPT_TOO_DEEP = "Script nests too deeply"
    const val SOURCE_TOO_LARGE = "The script is larger than 256 KB"
    const val NO_TOOL_OBJECT = "The script does not define a tool object"
    const val NO_RUN_FUNCTION = "The tool object has no run function"
    const val TOO_MANY_SCRIPTS = "Too many scripts are running or stuck, try again later"
    const val NO_NETWORK_IN_EVALUATION = "A script cannot use the network while it is being read"
    fun evaluationAborted(reason: String) = "The script could not be read: $reason"

    // Errors a script can catch
    const val HOST_FAILED = "The call failed"
    fun ioFailed(kind: String, message: String?) = if (message.isNullOrBlank()) "Network error: $kind" else "Network error: $kind, ${message.take(IO_ECHO)}"
    const val NOT_A_CONSTRUCTOR = "This function cannot be used with new"
    fun missingOption(what: String, key: String) = "$what needs the option $key"
    fun wrongType(what: String, key: String, type: String) = "$what option $key must be a $type"
    fun outOfRange(what: String, key: String, min: Int, max: Int) = "$what option $key must be a whole number from $min to $max"
    fun optionsExpected(what: String) = "$what expects an options object"
    fun wrongArgument(what: String, position: Int, type: String) = "$what argument $position must be a $type"
    fun notABytes(what: String, key: String) = "$what $key must be a string or an array of numbers from 0 to 255"
    fun tooManyBytes(what: String, key: String) = "$what $key is larger than 1 MB"
    const val BAD_HEX = "Not a valid hex string"
    const val BAD_BASE64 = "Not a valid base64 string"
    fun outOfRangeNumber(max: Long) = "The number must be a whole number from 0 to $max"
    const val READ_PAST_END = "Read past the end of the bytes"
    const val VALUE_TOO_LARGE = "The value is too large"
    const val VALUE_TOO_DEEP = "The value is nested too deeply"
    const val VALUE_NOT_PLAIN = "The value must be text, a number, true or false, an array or an object"
    const val FUNCTION_NOT_ALLOWED = "A function cannot be passed to the host"
    const val STRING_TOO_LONG = "Invalid string length: the limit is 1,000,000 characters"
    const val ARRAY_TOO_LONG = "Invalid array length: the limit is 100,000 elements"
    const val DYNAMIC_CODE_REFUSED = "eval and Function are not available in tools"
    const val JAVA_ACCESS_REFUSED = "Java objects are not available in tools"
    const val REGEXP_REFUSED = "RegExp is not available in tools, use ctx.regex"
    const val HANDLE_CLOSED = "The connection is closed"
    const val TOO_MANY_HANDLES = "At most 8 connections can be open at once"
    fun noWordlistInput(name: String) = "No wordlist input named ${name.take(IO_ECHO)}"

    // http
    const val BAD_METHOD = "The HTTP method must be letters only"
    const val TOO_MANY_HEADERS = "At most 64 headers can be sent"
    fun badHeader(name: String) = "The header ${name.take(IO_ECHO)} is not allowed"
    const val TOO_MANY_REDIRECTS = "Too many redirects"
    fun badUrl(url: String) = "Not a valid URL: ${url.take(IO_ECHO)}"
    const val URL_NOT_HTTP = "The URL must start with http:// or https://"
    const val URL_AUTHORITY_REFUSED = "The URL host must not contain a user name, a backslash, spaces or control characters"
    const val URL_PORT_BAD = "The URL port must be a number from 1 to 65535"
    const val URL_PATH_BAD = "The URL path must not contain spaces, backslashes or control characters"

    // Starting a script tool
    const val CONFIRM_REQUIRED = "This tool can contact any address and needs your confirmation for each run"
    fun inputsInvalid(first: String) = "The inputs are not valid: $first"

    // Reading a tool's description (every problem is listed, none is skipped)
    fun fieldMissing(field: String) = "The tool object has no $field"
    fun fieldNotText(field: String) = "The $field must be text"
    fun fieldTooLong(field: String, max: Int) = "The $field can be at most $max characters"
    const val BAD_ID = "The id must be 3 to 40 characters from a to z, 0 to 9 and -"
    const val BAD_VERSION = "The version must be 1 to 20 letters, digits, dots, dashes or underscores"
    const val BAD_TARGET = "The target must be port, host or none"
    const val BAD_NETWORK = "The network must be target, lan or any"
    const val BAD_CATEGORY = "The category must be web, network, identity or other"
    const val NO_RUN = "The tool needs a run function"
    const val BROADCAST_NOT_BOOLEAN = "The broadcast field must be true or false"
    const val BROADCAST_NEEDS_LAN = "A tool can declare broadcast only with the network set to lan"
    const val INPUTS_NOT_LIST = "The inputs must be a list"
    const val TOO_MANY_INPUTS = "A tool can have at most 24 inputs"
    fun inputNotObject(position: Int) = "Input $position must be an object"
    fun inputNameBad(position: Int) = "Input $position needs a name that starts with a letter and uses letters, digits and _"
    fun inputTypeBad(name: String) = "Input $name has an unknown type"
    fun inputLabelMissing(name: String) = "Input $name needs a label"
    fun inputTextTooLong(name: String, field: String, max: Int) = "Input $name: the $field can be at most $max characters"
    fun inputDefaultBad(name: String) = "The default of input $name does not fit its type"
    fun inputOptionsBad(name: String) = "Input $name needs 1 to 12 different options, as text or as value and label"
    fun inputRangeBad(name: String) = "Input $name has a minimum above its maximum"
    fun inputFieldBad(name: String, field: String) = "Input $name: the $field has the wrong kind of value"

    // network scope (D4, G2, G3)
    const val NO_SCOPE = "This tool has no network access"
    const val PORT_INVALID = "The port must be a number from 1 to 65535"
    const val NO_DEVICE_IN_SCOPE = "This tool can only contact the device it is run against, and none was chosen"
    const val TARGET_ONLY = "This tool can only contact the device it was run against"
    const val NO_LAN = "This tool needs a connected network"
    const val LAN_ONLY = "This tool can only contact addresses on the current network"
    const val ADDRESS_NOT_ALLOWED = "This address cannot be contacted by a tool"
    const val NAME_OUTSIDE_SCOPE = "The name does not lead to an address this tool may contact"
    const val DNS_TARGET_ONLY = "This tool can only look up the name of the device it was run against"
    const val DNS_LOCAL_ONLY = "This tool can only look up names on the local network"
    const val NOT_AN_ADDRESS = "Reverse lookup needs an IP address"
    const val HOST_INVALID = "Not a valid host name or address"
    const val NOT_CANONICAL = "Write IP addresses in the usual form, such as 192.168.1.10"

    // broadcast (spec section 11)
    const val BROADCAST_NOT_DECLARED = "This tool did not declare broadcast, so it cannot send to the broadcast address"
    const val BROADCAST_LIMIT = "At most 50 broadcast packets can be sent in one run"
    const val BROADCAST_TOO_BIG = "A broadcast packet can be at most 1 KB"
    const val BROADCAST_UDP_ONLY = "The broadcast address can only be used with ctx.udp.send and ctx.udp.exchange"

    // ctx.devices (spec section 12)
    const val DEVICES_NEED_LAN = "ctx.devices needs a tool with the network set to lan or any"

    // ctx.web.snapshot and image findings (spec section 12)
    const val SNAPSHOT_UNAVAILABLE = "Page snapshots are not available"
    const val SNAPSHOT_LIMIT = "At most 200 page snapshots can be taken in one run"
    const val SNAPSHOT_SIZE_LIMIT = "Page snapshots can use at most 20 MB in one run"
    const val SNAPSHOT_SAVE_FAILED = "The snapshot could not be saved"
    const val SNAPSHOT_TIMEOUT = "The page took too long to load"
    const val SNAPSHOT_ENGINE_FAILED = "The page could not be shown"
    const val SNAPSHOT_STOPPED = "The snapshot was stopped"
    fun snapshotLoadFailed(reason: String) = "The page could not be loaded: ${reason.take(IO_ECHO)}"
    const val IMAGE_UNKNOWN = "The image was not made by ctx.web.snapshot in this run"

    // dns
    const val DNS_TIMEOUT = "The DNS lookup timed out"
    const val DNS_FAILED = "The DNS lookup failed"

    // findings
    const val FINDING_URL_NOT_WEB = "A finding link must start with http:// or https://"

    // ctx.regex
    const val REGEX_PATTERN_TOO_LONG = "The pattern is longer than 1,000 characters"
    fun regexFlag(flag: Char) = "Unknown regex flag ${if (flag.code in PRINTABLE) flag.toString() else "?"}, use i, m or s"
    const val REGEX_INVALID = "The regular expression is not valid"
    const val REGEX_TOO_SLOW = "The regular expression took too many steps"
    const val REGEX_REPLACEMENT_INVALID = "The replacement text is not valid"

    private const val IO_ECHO = 60
    private val PRINTABLE = 0x21..0x7e
}
