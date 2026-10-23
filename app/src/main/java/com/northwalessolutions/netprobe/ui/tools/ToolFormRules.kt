package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.InputsResult
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.manual.ManualCopy
import com.northwalessolutions.netprobe.ui.manual.ManualTarget
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import java.net.Inet4Address

/**
 * What the Director has typed and chosen on a tool page. [values] are the raw strings of the tool's own inputs by name (the form of
 * ToolInputs.validate: a bool is "true" or "false", a list or multi-choice is comma separated); [touched] are the fields he has edited,
 * which decides which "required" errors show (an untouched blank field keeps Run disabled without scolding).
 */
data class ToolForm(
    val deviceHostId: Long? = null, val deviceIp: String = "", val portText: String = "", val httpsChoice: Boolean? = null,
    val values: Map<String, String> = emptyMap(), val touched: Set<String> = emptySet(),
)

/** What the form needs from the app to judge itself: the home subnet for the reserved-address rule, the link, the chosen device's services, the wordlists. */
data class FormContext(
    val home: Subnet? = null, val lan: Boolean = true, val services: Map<Int, ServiceInfo> = emptyMap(), val wordlists: List<WordlistMeta> = emptyList(),
)

/** How the form shows the TLS choice: a switch for the web tools and scripts, fixed for the rest. */
enum class TlsMode { SWITCH, ALWAYS, NEVER }

/**
 * The form judged (G14): every error by field name ("ip", "port", an input name), the ones to show, whether Run is enabled, and, when it
 * is, the typed inputs, address, port and TLS choice to start with.
 */
data class FormEvaluation(
    val errors: Map<String, String>, val visibleErrors: Map<String, String>, val canRun: Boolean, val inputs: ToolInputs?,
    val ip: Inet4Address?, val port: Int?, val tls: Boolean, val showDevice: Boolean, val showPort: Boolean, val tlsMode: TlsMode, val fixedLine: String?,
    val https: Boolean,
)

object ToolFormRules {
    /** A "Required" or "choose" error stays hidden until its field is touched. */
    private val QUIET = setOf(ToolModelCopy.REQUIRED, ToolsUiCopy.NO_DEVICE_CHOSEN, ManualCopy.ERR_PORT_NEEDED)

    /** The raw text a form starts from for [spec]: the descriptor's default (spec section 5: no remembered values in this batch). */
    fun defaultRaw(spec: InputSpec): String = when (spec) {
        is InputSpec.Ip -> spec.default.orEmpty()
        is InputSpec.Port -> spec.default?.toString().orEmpty()
        is InputSpec.Text -> spec.default.orEmpty()
        is InputSpec.Number -> spec.default?.toString().orEmpty()
        is InputSpec.Bool -> spec.default.toString()
        is InputSpec.Choice -> spec.default.orEmpty()
        is InputSpec.Wordlist -> spec.default.orEmpty()
        is InputSpec.Secret -> spec.default.orEmpty()
        is InputSpec.TextList -> spec.default.orEmpty()
        is InputSpec.MultiChoice -> spec.default.joinToString(",")
        is InputSpec.Tls -> spec.default.toString()
    }

    /** The form for [d], with the device and port a Device screen passed (null for none) and every own input at its default. */
    fun initial(d: ToolDescriptor, hostId: Long? = null, ip: String? = null, port: Int? = null): ToolForm {
        val device = d.target != TargetKind.NONE && hostId != null && ip != null
        val fixed = ManualTarget.fixedPort(d.key)
        val prefillPort = if (device && d.target == TargetKind.PORT && fixed == null && port != null && port > 0) port.toString() else ""
        return ToolForm(deviceHostId = hostId.takeIf { device }, deviceIp = ip.takeIf { device }.orEmpty(), portText = prefillPort,
            values = d.inputs.associate { it.name to defaultRaw(it) })
    }

    /** Choosing another device drops the port and the HTTPS choice: they belonged to the old one. */
    fun withDevice(form: ToolForm, hostId: Long?, ip: String): ToolForm =
        if (hostId == form.deviceHostId && ip == form.deviceIp) form
        else form.copy(deviceHostId = hostId, deviceIp = ip, portText = "", httpsChoice = null, touched = form.touched + ToolInputs.IP)

    fun withPort(form: ToolForm, text: String): ToolForm =
        form.copy(portText = text.filter(Char::isDigit).take(ManualTarget.MAX_PORT_DIGITS), httpsChoice = null, touched = form.touched + ToolInputs.PORT)

    fun withHttps(form: ToolForm, on: Boolean): ToolForm = form.copy(httpsChoice = on, touched = form.touched + ToolInputs.TLS)

    fun withInput(form: ToolForm, name: String, raw: String): ToolForm = form.copy(values = form.values + (name to raw), touched = form.touched + name)

    /** Built-ins keep their TLS rule (ManualTarget.tls); a script port tool gets the switch. */
    fun tlsMode(d: ToolDescriptor): TlsMode = when {
        d.target != TargetKind.PORT -> TlsMode.NEVER
        d.key == ToolKeys.TLS_CERTIFICATE -> TlsMode.ALWAYS
        d.key == ToolKeys.DIR_BRUTEFORCE || d.key == ToolKeys.HTTP_FINGERPRINT -> TlsMode.SWITCH
        d.source is ToolSource.Script -> TlsMode.SWITCH
        else -> TlsMode.NEVER
    }

    /** The switch's starting value: a stored service on the port decides, else a TLS port number (as the old manual screen did). */
    fun defaultHttps(port: Int?, service: ServiceInfo?): Boolean {
        if (port == null) return false
        if (service != null) return ToolApplicability.webScheme(port, service) == "https"
        return port in ServiceIdentifier.TLS
    }

    fun needsConfirm(d: ToolDescriptor): Boolean = d.network == NetworkScope.ANY

    fun evaluate(module: ToolModule, form: ToolForm, ctx: FormContext): FormEvaluation {
        val d = module.descriptor
        val errors = LinkedHashMap<String, String>()
        val hasDevice = d.target != TargetKind.NONE
        val fixed = ManualTarget.fixedPort(d.key)
        var ip: Inet4Address? = null
        var port: Int? = null
        if (hasDevice) {
            val checked = ManualTarget.check(form.deviceIp, form.portText, d.key, ctx.home, attempted = true)
            ip = checked.ip
            if (form.deviceIp.isBlank()) errors[ToolInputs.IP] = ToolsUiCopy.NO_DEVICE_CHOSEN else checked.ipError?.let { errors[ToolInputs.IP] = it }
            if (d.target == TargetKind.PORT) { port = checked.port; checked.portError?.let { errors[ToolInputs.PORT] = it } }
            else port = fixed ?: 0
        }
        val mode = tlsMode(d)
        val https = form.httpsChoice ?: defaultHttps(port, port?.let { ctx.services[it] })
        val tls = when (mode) { TlsMode.ALWAYS -> true; TlsMode.NEVER -> false; TlsMode.SWITCH -> https }

        val raw = LinkedHashMap<String, String>(form.values)
        if (hasDevice) {
            raw[ToolInputs.IP] = ip?.hostAddress ?: form.deviceIp
            if (d.target == TargetKind.PORT) { raw[ToolInputs.PORT] = port?.toString() ?: form.portText; raw[ToolInputs.TLS] = tls.toString() }
        }
        val resolve = { id: String -> ctx.wordlists.firstOrNull { it.id == id }?.let { WordlistRef(it.id, it.name, it.entries) } }
        var inputs: ToolInputs? = null
        when (val checked = ToolInputs.validate(d, raw, resolve)) {
            is InputsResult.Invalid -> checked.errors.forEach { (name, error) -> if (name != ToolInputs.IP && name != ToolInputs.PORT) errors.putIfAbsent(name, error) }
            is InputsResult.Valid -> inputs = checked.inputs
        }
        if (ctx.wordlists.isNotEmpty()) for (spec in d.inputs) if (spec is InputSpec.Wordlist) {
            val id = form.values[spec.name].orEmpty()
            if (id.isNotEmpty() && ctx.wordlists.none { it.id == id }) errors.putIfAbsent(spec.name, ToolsUiCopy.WORDLIST_GONE)
        }
        if (errors.isEmpty() && inputs != null) module.check(inputs).forEach { (name, error) -> errors.putIfAbsent(name, error) }
        val valid = errors.isEmpty() && inputs != null
        val visible = errors.filter { (name, error) -> name in form.touched || error !in QUIET }
        val fixedLine = when (d.key) {
            ToolKeys.SMB_SESSION -> ManualCopy.FIXED_SMB
            ToolKeys.SNMP_WALK -> ManualCopy.FIXED_SNMP
            ToolKeys.IDENTIFY_ALL -> ManualCopy.FIXED_ALL
            else -> null
        }
        return FormEvaluation(errors, visible, canRun = valid && ctx.lan, inputs = inputs.takeIf { valid }, ip = ip, port = port, tls = tls,
            showDevice = hasDevice, showPort = d.target == TargetKind.PORT && fixed == null, tlsMode = mode, fixedLine = fixedLine.takeIf { hasDevice }, https = https)
    }

    /**
     * The inputs to start with: the validated ones, plus the per-run confirmation for a tool whose network is "any" (ruling a). Null when the form
     * cannot run, or when a network "any" tool has not been confirmed: the page asks first, and only a confirmed run gets here with [confirmed] true.
     */
    fun startInputs(d: ToolDescriptor, evaluation: FormEvaluation, confirmed: Boolean): ToolInputs? {
        val inputs = evaluation.inputs ?: return null
        if (!needsConfirm(d)) return inputs
        return if (confirmed) inputs.with(ScriptToolModule.CONFIRM_ANY, true) else null
    }

    /** The hint above a disabled Run: why nothing happens. Null while Run is enabled. */
    fun hint(evaluation: FormEvaluation, lan: Boolean): String? = when {
        !lan -> ManualCopy.NO_LAN
        evaluation.canRun -> null
        else -> ToolsUiCopy.COMPLETE_FORM
    }
}
