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
