package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
import java.io.File
import java.net.Inet4Address

/** The historical tool keys (spec D5). They are stored in tool_runs.toolId, so they never change. */
object ToolKeys {
    const val DIR_BRUTEFORCE = "dir-bruteforce"
    const val HTTP_FINGERPRINT = "http-fingerprint"
    const val TLS_CERTIFICATE = "tls-certificate"
    const val SMB_SESSION = "smb-session"
    const val SNMP_WALK = "snmp-walk"
    const val IDENTIFY = "identify-service"
    const val IDENTIFY_ALL = "identify-all-services"
    /** Imported tools are keyed "user:<slug>" (spec D5). */
    const val USER_PREFIX = "user:"
    val BUILT_IN: List<String> = listOf(DIR_BRUTEFORCE, HTTP_FINGERPRINT, TLS_CERTIFICATE, SMB_SESSION, SNMP_WALK, IDENTIFY, IDENTIFY_ALL)
}

enum class ToolCategory { WEB, NETWORK, IDENTITY, EXAMPLES, MINE }
enum class TargetKind { PORT, HOST, NONE }
enum class NetworkScope { TARGET, LAN, ANY }

sealed interface ToolSource {
    object BuiltIn : ToolSource
    data class Script(val file: File, val sha256: String) : ToolSource
}

/** The fixed icon set (spec section 6); the UI maps each to a Material icon, so this file has no Compose. Unknown names fall back to TERMINAL. */
enum class ToolIcon(val id: String) {
    WEB("web"), SEARCH("search"), LOCK("lock"), FOLDER("folder"), SHARE("share"), DNS("dns"), TERMINAL("terminal"),
    FINGERPRINT("fingerprint"), NETWORK("network"), WIFI("wifi"), INFO("info"), BOLT("bolt");
    companion object {
        fun parse(name: String?): ToolIcon = entries.firstOrNull { it.id == name } ?: TERMINAL
    }
}

/** One choice option: [value] is what the tool receives, [label] what the form shows. */
data class ChoiceOption(val value: String, val label: String = value)

/** A chosen wordlist as the tool sees it; [name] and [entries] are recorded in optionsJson so history still names a deleted list. */
data class WordlistRef(val id: String, val name: String = "", val entries: Int = 0)

/** Declared inputs (spec D7). Defaults are typed per input; the form pre-fills them. */
sealed interface InputSpec {
    val name: String
    val label: String
    val help: String?
    val required: Boolean

    data class Ip(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                  val default: String? = null) : InputSpec
    data class Port(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                    val default: Int? = null) : InputSpec
    data class Text(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                    val default: String? = null, val placeholder: String? = null) : InputSpec
    data class Number(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                      val default: Int? = null, val min: Int? = null, val max: Int? = null) : InputSpec
    data class Bool(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                    val default: Boolean = false) : InputSpec
    data class Choice(override val name: String, override val label: String, val options: List<ChoiceOption>, override val help: String? = null,
                      override val required: Boolean = true, val default: String? = null) : InputSpec
    data class Wordlist(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                        val default: String? = null) : InputSpec
    /** Comma separated text; the tool receives a List of the trimmed, non-blank items. */
    data class TextList(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                        val default: String? = null, val placeholder: String? = null, val maxItems: Int? = null) : InputSpec
    /** A set of [options]; the raw form is the chosen values joined by commas, the tool receives them as a List in option order. */
    data class MultiChoice(override val name: String, override val label: String, val options: List<ChoiceOption>, override val help: String? = null,
                           override val required: Boolean = true, val default: List<String> = emptyList()) : InputSpec
    /** Text the UI masks. Never written to optionsJson, never exported, never remembered (see [ToolInputs.withoutSecrets]). */
    data class Secret(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                      val default: String? = null) : InputSpec
    /** A boolean the form shows as a switch; scripts get a plain boolean. The name "tls" is reserved, so this type is for other switches. */
    data class Tls(override val name: String, override val label: String, override val help: String? = null, override val required: Boolean = true,
                   val default: Boolean = false) : InputSpec
}

data class ToolDescriptor(
    val key: String, val name: String, val description: String, val icon: ToolIcon, val category: ToolCategory,
    val target: TargetKind, val inputs: List<InputSpec>, val network: NetworkScope = NetworkScope.TARGET,
    val version: String = "1", val author: String? = null, val source: ToolSource = ToolSource.BuiltIn,
    /** Only a LAN tool may declare it: it may send datagrams to the current subnet's directed broadcast address (spec section 11). */
    val broadcast: Boolean = false,
)

/** Rules for a descriptor's own inputs: "ip", "port" and "tls" belong to the implicit target inputs (spec D7), and names are unique. */
object DescriptorRules {
    val RESERVED: Set<String> = setOf(ToolInputs.IP, ToolInputs.PORT, ToolInputs.TLS)
    fun errors(d: ToolDescriptor): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (spec in d.inputs) {
            // "mac" is the device's MAC address for a host tool; only a host tool may declare a text input of that name, which overrides it.
            val macAllowed = spec.name == ToolInputs.MAC && d.target == TargetKind.HOST && spec is InputSpec.Text
            // "broadcast" carries the subnet's directed broadcast address to a tool that declared broadcast.
            val broadcastTaken = spec.name == ToolInputs.BROADCAST && d.broadcast
            if (spec.name in RESERVED || (spec.name == ToolInputs.MAC && !macAllowed) || broadcastTaken) out += ToolModelCopy.reservedName(spec.name)
            else if (!seen.add(spec.name)) out += ToolModelCopy.duplicateName(spec.name)
            if (spec is InputSpec.Choice && spec.options.isEmpty()) out += ToolModelCopy.noOptions(spec.name)
            if (spec is InputSpec.MultiChoice && spec.options.isEmpty()) out += ToolModelCopy.noOptions(spec.name)
        }
        return out
    }
}

/** What validate returns: typed inputs, or one error string per field name. */
sealed interface InputsResult {
    data class Valid(val inputs: ToolInputs) : InputsResult
    data class Invalid(val errors: Map<String, String>) : InputsResult
}

/**
 * Validated, typed values keyed by input name (spec section 3): ip as Inet4Address, port as Int, wordlist as [WordlistRef], number as Int,
 * bool and tls as Boolean, text and choice as String. An absent value reads as the empty value of its helper.
 */
data class ToolInputs(val values: Map<String, Any?> = emptyMap()) {
    fun ip(name: String = IP): Inet4Address = values[name] as Inet4Address
    fun port(name: String = PORT): Int = values[name] as Int
    fun text(name: String): String = values[name] as? String ?: ""
    fun int(name: String): Int = values[name] as Int
    fun bool(name: String): Boolean = values[name] as? Boolean ?: false
    fun choice(name: String): String = values[name] as? String ?: ""
    fun wordlist(name: String): WordlistRef = values[name] as WordlistRef
    fun list(name: String): List<String> = (values[name] as? List<*>)?.map { it.toString() } ?: emptyList()
    fun has(name: String): Boolean = values[name] != null
    fun with(name: String, value: Any?): ToolInputs = ToolInputs(values + (name to value))
    /** The values with every [InputSpec.Secret] of [descriptor] removed: what may be recorded, exported or remembered. */
    fun withoutSecrets(descriptor: ToolDescriptor): ToolInputs {
        val secret = descriptor.inputs.filterIsInstance<InputSpec.Secret>().mapTo(HashSet()) { it.name }
        return ToolInputs(values.filterKeys { it !in secret })
    }

    companion object {
        const val IP = "ip"
        const val PORT = "port"
        const val TLS = "tls"
        /** Host tools receive the chosen device's MAC address here: lower-case, colon separated, or an empty string when it is not known. */
        const val MAC = "mac"
        /** A tool that declared broadcast receives the current subnet's directed broadcast address here, or an empty string when there is none. */
        const val BROADCAST = "broadcast"
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
        val NONE = ToolInputs(emptyMap())

        /**
         * Turns the form's raw strings into typed values. The implicit "ip" (PORT and HOST targets) and "port" (PORT target) inputs are
         * checked first (spec D7). A key missing from [raw] takes the input's default; a blank value is an error for a required input,
         * and takes the default (or is left out) for an optional one. [resolveWordlist] fills a wordlist's name and entry count; an id
         * it does not know is kept with an empty name, because the tool falls back to its built-in list.
         */
        fun validate(descriptor: ToolDescriptor, raw: Map<String, String>, resolveWordlist: (String) -> WordlistRef? = { null }): InputsResult {
            val values = LinkedHashMap<String, Any?>()
            val errors = LinkedHashMap<String, String>()
            if (descriptor.target != TargetKind.NONE) check(InputSpec.Ip(IP, ToolModelCopy.IP_LABEL), raw[IP], values, errors, resolveWordlist)
            if (descriptor.target == TargetKind.PORT) check(InputSpec.Port(PORT, ToolModelCopy.PORT_LABEL), raw[PORT], values, errors, resolveWordlist)
            if (descriptor.target == TargetKind.PORT) check(InputSpec.Tls(TLS, ToolModelCopy.TLS_LABEL), raw[TLS], values, errors, resolveWordlist)
            for (spec in descriptor.inputs) check(spec, raw[spec.name], values, errors, resolveWordlist)
            return if (errors.isEmpty()) InputsResult.Valid(ToolInputs(values)) else InputsResult.Invalid(errors)
        }

        private fun check(spec: InputSpec, rawValue: String?, values: MutableMap<String, Any?>, errors: MutableMap<String, String>,
                          resolveWordlist: (String) -> WordlistRef?) {
            val text = rawValue?.trim().orEmpty()
            if (spec is InputSpec.Bool || spec is InputSpec.Tls) {
                val fallback = (spec as? InputSpec.Bool)?.default ?: (spec as InputSpec.Tls).default
                values[spec.name] = if (text.isEmpty()) fallback else parseBool(text, fallback)
                return
            }
            if (text.isEmpty()) {
                val default = defaultOf(spec)
                if (spec.required && !(rawValue == null && default != null)) { errors[spec.name] = ToolModelCopy.REQUIRED; return }
                if (default != null) values[spec.name] = typed(spec, default, resolveWordlist)
                return
            }
            when (spec) {
                is InputSpec.Ip -> {
                    val ip = Ipv4.parse(text)
                    if (ip == null) errors[spec.name] = ToolModelCopy.BAD_IP else values[spec.name] = ip
                }
                is InputSpec.Port -> {
                    val p = text.toIntOrNull()
                    if (p == null || p < MIN_PORT || p > MAX_PORT) errors[spec.name] = ToolModelCopy.BAD_PORT else values[spec.name] = p
                }
                is InputSpec.Text -> values[spec.name] = text
                is InputSpec.Secret -> values[spec.name] = text
                is InputSpec.TextList -> {
                    val items = splitList(text)
                    val max = spec.maxItems
                    if (max != null && items.size > max) errors[spec.name] = ToolModelCopy.tooManyItems(max) else values[spec.name] = items
                }
                is InputSpec.MultiChoice -> {
                    val chosen = splitList(text)
                    if (chosen.any { c -> spec.options.none { it.value == c } }) errors[spec.name] = ToolModelCopy.NOT_AN_OPTION
                    else values[spec.name] = spec.options.map { it.value }.filter { it in chosen }
                }
                is InputSpec.Number -> {
                    val n = text.toIntOrNull()
                    val min = spec.min
                    val max = spec.max
                    when {
                        n == null -> errors[spec.name] = ToolModelCopy.NOT_A_NUMBER
                        min != null && n < min -> errors[spec.name] = ToolModelCopy.tooSmall(min)
                        max != null && n > max -> errors[spec.name] = ToolModelCopy.tooBig(max)
                        else -> values[spec.name] = n
                    }
                }
                is InputSpec.Choice ->
                    if (spec.options.none { it.value == text }) errors[spec.name] = ToolModelCopy.NOT_AN_OPTION else values[spec.name] = text
                is InputSpec.Wordlist -> values[spec.name] = typed(spec, text, resolveWordlist)
                is InputSpec.Bool, is InputSpec.Tls -> Unit
            }
        }

        private fun splitList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun parseBool(text: String, fallback: Boolean): Boolean = when (text.lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> fallback
        }

        private fun defaultOf(spec: InputSpec): String? = when (spec) {
            is InputSpec.Ip -> spec.default
            is InputSpec.Port -> spec.default?.toString()
            is InputSpec.Text -> spec.default
            is InputSpec.Number -> spec.default?.toString()
            is InputSpec.Choice -> spec.default
            is InputSpec.Wordlist -> spec.default
            is InputSpec.Secret -> spec.default
            is InputSpec.TextList -> spec.default
            is InputSpec.MultiChoice -> spec.default.joinToString(",").ifEmpty { null }
            is InputSpec.Bool, is InputSpec.Tls -> null
        }

        private fun typed(spec: InputSpec, text: String, resolveWordlist: (String) -> WordlistRef?): Any? = when (spec) {
            is InputSpec.Ip -> Ipv4.parse(text)
            is InputSpec.Port, is InputSpec.Number -> text.toIntOrNull()
            is InputSpec.Wordlist -> resolveWordlist(text) ?: WordlistRef(text)
            is InputSpec.TextList -> splitList(text)
            is InputSpec.MultiChoice -> spec.options.map { it.value }.filter { it in splitList(text) }
            else -> text
        }
    }
}

/**
 * A tool the runner can start (spec section 3). [applies] and [appliesToHost] feed the device page's per-port and host buttons; tools
 * that are not offered there return false. The run's target is null only for NONE tools.
 */
interface ToolModule {
    val descriptor: ToolDescriptor
    fun applies(port: Int, service: ServiceInfo): Boolean
    fun appliesToHost(host: HostEntity): Boolean = false
    /** Checks that need the tool's own rules, for example a list of status codes; field name to error. Empty when the inputs are fine. */
    fun check(inputs: ToolInputs): Map<String, String> = emptyMap()
    /** The inputs as recorded in the run's optionsJson; a module may add or reshape values (dir-brute adds methodUsed and list fields). */
    fun recorded(inputs: ToolInputs): ToolInputs = inputs
    /** Suspends until done. Cancellation of the calling coroutine is the only stop. Calls control.gate() before every request. */
    suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult
}
