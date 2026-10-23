package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.AbortReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every ScriptCopy string against its template, with no em-dash, no disclaimer and no unbounded echo of script text. */
class ScriptCopyTest {
    private val templates = listOf(
        // why a run ended
        "Stopped", "Script ran for more than 10 minutes", "Script used too much processing", "Script made more than 20,000 network calls", "Script transferred more than 50 MB",
        "Script produced more than 5,000 findings", "Script findings are larger than 2 MB", "Script used too much memory", "Script stopped responding",
        "\\d+ findings?", "Script returned the status .{1,40}, expected done or failed", ".{1,300}( \\(line \\d+\\))?", "Script engine error: \\w+", "Script nests too deeply",
        "The script is larger than 256 KB", "The script does not define a tool object", "The tool object has no run function",
        "Too many scripts are running or stuck, try again later", "A script cannot use the network while it is being read", "The script could not be read: .+",
        // errors a script can catch
        "The call failed", "Network error: \\w+(, .{1,60})?", "This function cannot be used with new", "ctx\\.[a-z.]+ needs the option \\w+", "ctx\\.[a-z.]+ option \\w+ must be a \\w+",
        "ctx\\.[a-z.]+ option \\w+ must be a whole number from -?\\d+ to \\d+", "ctx\\.[a-z.]+ expects an options object", "ctx\\.[a-z.]+ argument \\d must be a \\w+",
        "[a-z.]+ [a-z ]+ must be a string or an array of numbers from 0 to 255", "[a-z.]+ [a-z ]+ is larger than 1 MB", "Not a valid hex string", "Not a valid base64 string", "The number must be a whole number from 0 to \\d+",
        "Read past the end of the bytes", "The value is too large", "The value is nested too deeply", "The value must be text, a number, true or false, an array or an object",
        "A function cannot be passed to the host", "Invalid string length: the limit is 1,000,000 characters", "Invalid array length: the limit is 100,000 elements",
        "eval and Function are not available in tools", "Java objects are not available in tools", "RegExp is not available in tools, use ctx\\.regex", "The connection is closed",
        "At most 8 connections can be open at once", "No wordlist input named .{1,60}",
        // http
        "The HTTP method must be letters only", "At most 64 headers can be sent", "The header .{1,60} is not allowed", "Too many redirects", "Not a valid URL: .{0,60}",
        "The URL must start with http:// or https://", "The URL host must not contain a user name, a backslash, spaces or control characters", "The URL port must be a number from 1 to 65535",
        "The URL path must not contain spaces, backslashes or control characters",
        "ctx\\.devices needs a tool with the network set to lan or any",
        // dns, findings, regex
        "The DNS lookup timed out", "The DNS lookup failed", "A finding link must start with http:// or https://",
        "The pattern is longer than 1,000 characters", "Unknown regex flag \\S, use i, m or s", "The regular expression is not valid", "The regular expression took too many steps",
        "The replacement text is not valid",
        // starting a script tool and reading its description
        "This tool can contact any address and needs your confirmation for each run", "The inputs are not valid: .{1,120}", "The tool object has no \\w+", "The \\w+ must be text",
        "The \\w+ can be at most \\d+ characters", "The id must be 3 to 40 characters from a to z, 0 to 9 and -",
        "The version must be 1 to 20 letters, digits, dots, dashes or underscores", "The target must be port, host or none", "The network must be target, lan or any",
        "The category must be web, network, identity or other", "The tool needs a run function", "The inputs must be a list", "A tool can have at most 24 inputs",
        "Input \\d+ must be an object", "Input \\d+ needs a name that starts with a letter and uses letters, digits and _", "Input \\w{1,40} has an unknown type",
        "Input \\w{1,40} needs a label", "Input \\w{1,40}: the \\w+ can be at most \\d+ characters", "The default of input \\w{1,40} does not fit its type",
        "Input \\w{1,40} needs 1 to 12 different options, as text or as value and label", "Input \\w{1,40} has a minimum above its maximum",
        "Input \\w{1,40}: the \\w+ has the wrong kind of value",
        "The broadcast field must be true or false", "A tool can declare broadcast only with the network set to lan",
        "This tool did not declare broadcast, so it cannot send to the broadcast address", "At most 50 broadcast packets can be sent in one run", "A broadcast packet can be at most 1 KB",
        "The broadcast address can only be used with ctx\\.udp\\.send and ctx\\.udp\\.exchange",
        // network scope
        "This tool has no network access", "The port must be a number from 1 to 65535", "This tool can only contact the device it is run against, and none was chosen",
        "This tool can only contact the device it was run against", "This tool needs a connected network", "This tool can only contact addresses on the current network",
        "This address cannot be contacted by a tool", "The name does not lead to an address this tool may contact", "This tool can only look up the name of the device it was run against",
        "This tool can only look up names on the local network", "Reverse lookup needs an IP address", "Not a valid host name or address", "Write IP addresses in the usual form, such as 192\\.168\\.1\\.10",
    ).map { Regex(it) }

    private fun all(): List<String> = buildList {
        AbortReason.entries.forEach { add(ScriptCopy.abortSummary(it)) }
        addAll(listOf(
            ScriptCopy.findingsSummary(0), ScriptCopy.findingsSummary(1), ScriptCopy.findingsSummary(2), ScriptCopy.badStatus("maybe"), ScriptCopy.scriptError("boom", 3), ScriptCopy.scriptError("boom", 0),
            ScriptCopy.internalError("IllegalStateException"), ScriptCopy.SCRIPT_TOO_DEEP, ScriptCopy.SOURCE_TOO_LARGE, ScriptCopy.NO_TOOL_OBJECT, ScriptCopy.NO_RUN_FUNCTION, ScriptCopy.TOO_MANY_SCRIPTS,
            ScriptCopy.NO_NETWORK_IN_EVALUATION, ScriptCopy.evaluationAborted("Stopped"),
            ScriptCopy.HOST_FAILED, ScriptCopy.ioFailed("SocketTimeoutException", null), ScriptCopy.ioFailed("ConnectException", "refused"), ScriptCopy.NOT_A_CONSTRUCTOR,
            ScriptCopy.missingOption("ctx.http.request", "url"), ScriptCopy.wrongType("ctx.tcp.exchange", "port", "number"), ScriptCopy.outOfRange("ctx.tcp.exchange", "port", 1, 65535),
            ScriptCopy.optionsExpected("ctx.udp.exchange"), ScriptCopy.wrongArgument("ctx.dns.lookup", 1, "string"), ScriptCopy.notABytes("ctx.tcp.exchange", "send"),
            ScriptCopy.tooManyBytes("ctx.tcp.exchange", "send"), ScriptCopy.BAD_HEX, ScriptCopy.BAD_BASE64, ScriptCopy.DEVICES_NEED_LAN, ScriptCopy.outOfRangeNumber(65535), ScriptCopy.READ_PAST_END, ScriptCopy.VALUE_TOO_LARGE, ScriptCopy.VALUE_TOO_DEEP,
            ScriptCopy.VALUE_NOT_PLAIN, ScriptCopy.FUNCTION_NOT_ALLOWED, ScriptCopy.STRING_TOO_LONG, ScriptCopy.ARRAY_TOO_LONG, ScriptCopy.DYNAMIC_CODE_REFUSED, ScriptCopy.JAVA_ACCESS_REFUSED,
            ScriptCopy.REGEXP_REFUSED, ScriptCopy.HANDLE_CLOSED, ScriptCopy.TOO_MANY_HANDLES, ScriptCopy.noWordlistInput("words"),
            ScriptCopy.BAD_METHOD, ScriptCopy.TOO_MANY_HEADERS, ScriptCopy.badHeader("X-A"), ScriptCopy.TOO_MANY_REDIRECTS, ScriptCopy.badUrl("nope"), ScriptCopy.URL_NOT_HTTP,
            ScriptCopy.URL_AUTHORITY_REFUSED, ScriptCopy.URL_PORT_BAD, ScriptCopy.URL_PATH_BAD, ScriptCopy.DNS_TIMEOUT, ScriptCopy.DNS_FAILED, ScriptCopy.FINDING_URL_NOT_WEB,
            ScriptCopy.REGEX_PATTERN_TOO_LONG, ScriptCopy.regexFlag('q'), ScriptCopy.regexFlag('\n'), ScriptCopy.REGEX_INVALID, ScriptCopy.REGEX_TOO_SLOW, ScriptCopy.REGEX_REPLACEMENT_INVALID,
            ScriptCopy.CONFIRM_REQUIRED, ScriptCopy.inputsInvalid("port Required"), ScriptCopy.fieldMissing("target"), ScriptCopy.fieldMissing("id"), ScriptCopy.fieldNotText("name"),
            ScriptCopy.fieldTooLong("name", 40), ScriptCopy.BAD_ID, ScriptCopy.BAD_VERSION, ScriptCopy.BAD_TARGET, ScriptCopy.BAD_NETWORK, ScriptCopy.BAD_CATEGORY, ScriptCopy.NO_RUN,
            ScriptCopy.INPUTS_NOT_LIST, ScriptCopy.TOO_MANY_INPUTS, ScriptCopy.inputNotObject(2), ScriptCopy.inputNameBad(3), ScriptCopy.inputTypeBad("path"), ScriptCopy.inputLabelMissing("path"),
            ScriptCopy.inputTextTooLong("path", "label", 40), ScriptCopy.inputDefaultBad("path"), ScriptCopy.inputOptionsBad("mode"), ScriptCopy.inputRangeBad("n"), ScriptCopy.inputFieldBad("path", "help"),
            ScriptCopy.BROADCAST_NOT_BOOLEAN, ScriptCopy.BROADCAST_NEEDS_LAN, ScriptCopy.BROADCAST_NOT_DECLARED, ScriptCopy.BROADCAST_LIMIT, ScriptCopy.BROADCAST_TOO_BIG, ScriptCopy.BROADCAST_UDP_ONLY,
            ScriptCopy.NO_SCOPE, ScriptCopy.PORT_INVALID, ScriptCopy.NO_DEVICE_IN_SCOPE, ScriptCopy.TARGET_ONLY, ScriptCopy.NO_LAN, ScriptCopy.LAN_ONLY, ScriptCopy.ADDRESS_NOT_ALLOWED,
            ScriptCopy.NAME_OUTSIDE_SCOPE, ScriptCopy.DNS_TARGET_ONLY, ScriptCopy.DNS_LOCAL_ONLY, ScriptCopy.NOT_AN_ADDRESS, ScriptCopy.HOST_INVALID, ScriptCopy.NOT_CANONICAL,
        ))
    }

    @Test fun `every string is in the table and has no em-dash or disclaimer`() {
        val bad = all().filterNot { s -> templates.any { it.matches(s) } }
        assertTrue("not in the table: $bad", bad.isEmpty())
        all().forEach { s ->
            assertFalse("em-dash in '$s'", s.contains(0x2014.toChar()))
            assertFalse("disclaimer in '$s'", s.contains("please", ignoreCase = true) || s.contains("sorry", ignoreCase = true))
        }
    }

    @Test fun `echoed script text is cut`() {
        val long = "x".repeat(500)
        assertEquals(60, ScriptCopy.badUrl(long).removePrefix("Not a valid URL: ").length)
        assertEquals(60, ScriptCopy.badHeader(long).removePrefix("The header ").removeSuffix(" is not allowed").length)
        assertTrue(ScriptCopy.ioFailed("IOException", long).length < 100)
        assertEquals(60, ScriptCopy.noWordlistInput(long).removePrefix("No wordlist input named ").length)
    }
}
