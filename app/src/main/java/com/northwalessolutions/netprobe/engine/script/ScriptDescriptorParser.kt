package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.ChoiceOption
import com.northwalessolutions.netprobe.engine.tools.DescriptorRules
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy

sealed interface ParsedTool {
    data class Valid(val descriptor: ToolDescriptor) : ParsedTool
    /** Every problem found, each one a Copy string, so an import can show them all at once. */
    data class Invalid(val errors: List<String>) : ParsedTool
}

/**
 * Turns the plain `tool` data of an evaluated script into a [ToolDescriptor] (spec D2, D5, D7, G14 limits), collecting every error
 * rather than stopping at the first. Nothing here runs script code.
 */
object ScriptDescriptorParser {
    private val ID = Regex("[a-z0-9-]{3,40}")
    private val VERSION = Regex("[0-9A-Za-z._-]{1,20}")
    private val INPUT_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
    private const val MAX_NAME = 40
    private const val MAX_DESCRIPTION = 200
    private const val MAX_AUTHOR = 40
    private const val MAX_LABEL = 40
    private const val MAX_HELP = 200
    private const val MAX_PLACEHOLDER = 40
    private const val MAX_OPTIONS = 12
    private const val MAX_INPUTS = 24
    private const val ECHO = 40
    /** Reserved by the script module beyond DescriptorRules.RESERVED: the per-run confirmation flag for network "any". */
    const val CONFIRM_ANY = "confirmAny"

    /**
     * [keyPrefix] goes before the id to make the tool key ("user:" for imports, "example:" for the bundled examples); [category] replaces the
     * script's own category when set (the examples); [source] is where the script came from.
     */
    fun parse(tool: Map<String, Any?>, hasRun: Boolean, keyPrefix: String, category: ToolCategory?, source: ToolSource): ParsedTool {
        val errors = ArrayList<String>()
        fun text(field: String, max: Int, required: Boolean): String? {
            val v = tool[field]
            if (v == null) { if (required) errors += ScriptCopy.fieldMissing(field); return null }
            if (v !is String || v.isBlank()) { errors += ScriptCopy.fieldNotText(field); return null }
            if (v.length > max) { errors += ScriptCopy.fieldTooLong(field, max); return null }
            return v
        }
        val id = text("id", MAX_NAME, true)
        if (id != null && !ID.matches(id)) errors += ScriptCopy.BAD_ID
        val name = text("name", MAX_NAME, true)
        val description = text("description", MAX_DESCRIPTION, false) ?: ""
        val version = text("version", MAX_NAME, false) ?: "1"
        if (!VERSION.matches(version)) errors += ScriptCopy.BAD_VERSION
        val author = text("author", MAX_AUTHOR, false)
        val target = when (tool["target"]) { "port" -> TargetKind.PORT; "host" -> TargetKind.HOST; "none" -> TargetKind.NONE; null -> { errors += ScriptCopy.fieldMissing("target"); null }; else -> { errors += ScriptCopy.BAD_TARGET; null } }
        val network = when (tool["network"]) { null, "target" -> NetworkScope.TARGET; "lan" -> NetworkScope.LAN; "any" -> NetworkScope.ANY; else -> { errors += ScriptCopy.BAD_NETWORK; null } }
        val broadcast = when (val b = tool["broadcast"]) {
            null -> false
            is Boolean -> b
            else -> { errors += ScriptCopy.BROADCAST_NOT_BOOLEAN; false }
        }
        if (broadcast && network != NetworkScope.LAN) errors += ScriptCopy.BROADCAST_NEEDS_LAN
        val parsedCategory = category ?: when (tool["category"]) {
            null, "other" -> ToolCategory.MINE; "web" -> ToolCategory.WEB; "network" -> ToolCategory.NETWORK; "identity" -> ToolCategory.IDENTITY
            else -> { errors += ScriptCopy.BAD_CATEGORY; null }
        }
        val icon = ToolIcon.parse(tool["icon"] as? String)
        if (!hasRun) errors += ScriptCopy.NO_RUN
        val inputs = inputs(tool["inputs"], errors)

        if (errors.isNotEmpty() || id == null || name == null || target == null || network == null || parsedCategory == null) return ParsedTool.Invalid(errors.distinct())
        val descriptor = ToolDescriptor(keyPrefix + id, name, description, icon, parsedCategory, target, inputs, network, version, author, source, broadcast)
        val rules = DescriptorRules.errors(descriptor) + inputs.filter { it.name == CONFIRM_ANY }.map { ToolModelCopy.reservedName(it.name) }
        return if (rules.isEmpty()) ParsedTool.Valid(descriptor) else ParsedTool.Invalid(rules.distinct())
    }

    private fun inputs(raw: Any?, errors: MutableList<String>): List<InputSpec> {
        if (raw == null) return emptyList()
        if (raw !is List<*>) { errors += ScriptCopy.INPUTS_NOT_LIST; return emptyList() }
        if (raw.size > MAX_INPUTS) { errors += ScriptCopy.TOO_MANY_INPUTS; return emptyList() }
        val out = ArrayList<InputSpec>()
        raw.forEachIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?>
            if (m == null) { errors += ScriptCopy.inputNotObject(index + 1); return@forEachIndexed }
            input(index + 1, m, errors)?.let { out += it }
        }
        return out
    }

    private fun input(position: Int, m: Map<String, Any?>, errors: MutableList<String>): InputSpec? {
        val name = m["name"] as? String
        if (name == null || !INPUT_NAME.matches(name)) { errors += ScriptCopy.inputNameBad(position); return null }
        val before = errors.size
        val shown = name.take(ECHO)
        fun text(field: String, max: Int): String? {
            val v = m[field] ?: return null
            if (v !is String) { errors += ScriptCopy.inputFieldBad(shown, field); return null }
            if (v.length > max) { errors += ScriptCopy.inputTextTooLong(shown, field, max); return null }
            return v
        }
        val label = text("label", MAX_LABEL)
        if (m["label"] == null) errors += ScriptCopy.inputLabelMissing(shown)
        val help = text("help", MAX_HELP)
        val placeholder = text("placeholder", MAX_PLACEHOLDER)
        val required = when (val r = m["required"]) { null -> true; is Boolean -> r; else -> { errors += ScriptCopy.inputFieldBad(shown, "required"); true } }
        fun whole(field: String): Int? {
            val v = m[field] ?: return null
            val d = v as? Double
            if (d == null || d != Math.floor(d) || d.isNaN() || Math.abs(d) > MAX_NUMBER) { errors += ScriptCopy.inputFieldBad(shown, field); return null }
            return d.toInt()
        }
        fun badDefault() { errors += ScriptCopy.inputDefaultBad(shown) }
        val l = label ?: ""
        val spec: InputSpec? = when (val type = m["type"]) {
            "text" -> InputSpec.Text(name, l, help, required, (m["default"] as? String).also { if (m["default"] != null && it == null) badDefault() }, placeholder)
            "secret" -> InputSpec.Secret(name, l, help, required, (m["default"] as? String).also { if (m["default"] != null && it == null) badDefault() })
            "list" -> InputSpec.TextList(name, l, help, required, (m["default"] as? String).also { if (m["default"] != null && it == null) badDefault() }, placeholder, whole("maxItems"))
            "wordlist" -> InputSpec.Wordlist(name, l, help, required, (m["default"] as? String).also { if (m["default"] != null && it == null) badDefault() })
            "bool" -> InputSpec.Bool(name, l, help, required, (m["default"] as? Boolean) ?: false.also { if (m["default"] != null && m["default"] !is Boolean) badDefault() })
            "tls" -> InputSpec.Tls(name, l, help, required, (m["default"] as? Boolean) ?: false.also { if (m["default"] != null && m["default"] !is Boolean) badDefault() })
            "number" -> {
                val min = whole("min"); val max = whole("max"); val default = whole("default")
                if (min != null && max != null && min > max) errors += ScriptCopy.inputRangeBad(shown)
                if (default != null && ((min != null && default < min) || (max != null && default > max))) badDefault()
                InputSpec.Number(name, l, help, required, default, min, max)
            }
            "port" -> {
                val default = whole("default")
                if (default != null && (default < 1 || default > MAX_PORT)) badDefault()
                InputSpec.Port(name, l, help, required, default)
            }
            "ip" -> InputSpec.Ip(name, l, help, required, (m["default"] as? String).also { if (m["default"] != null && (it == null || com.northwalessolutions.netprobe.netenv.Ipv4.parse(it) == null)) badDefault() })
            "choice" -> {
                val options = options(shown, m["options"], errors)
                val default = m["default"] as? String
                if (m["default"] != null && (default == null || options.none { it.value == default })) badDefault()
                InputSpec.Choice(name, l, options, help, required, default)
            }
            "multichoice" -> {
                val options = options(shown, m["options"], errors)
                val raw = m["default"]
                val default = when (raw) { null -> emptyList(); is List<*> -> raw.map { it as? String ?: "" }; else -> { badDefault(); emptyList() } }
                if (default.any { d -> options.none { it.value == d } }) badDefault()
                InputSpec.MultiChoice(name, l, options, help, required, default)
            }
            else -> { errors += ScriptCopy.inputTypeBad(shown); null }
        }
        return if (errors.size > before) null else spec
    }

    private fun options(shown: String, raw: Any?, errors: MutableList<String>): List<ChoiceOption> {
        val list = raw as? List<*>
        if (list == null || list.isEmpty() || list.size > MAX_OPTIONS) { errors += ScriptCopy.inputOptionsBad(shown); return emptyList() }
        val out = ArrayList<ChoiceOption>()
        for (item in list) {
            val option = when (item) {
                is String -> ChoiceOption(item, item)
                is Map<*, *> -> (item["value"] as? String)?.let { v -> ChoiceOption(v, (item["label"] as? String) ?: v) }
                else -> null
            }
            if (option == null || option.value.isEmpty() || option.value.length > MAX_LABEL || option.label.length > MAX_LABEL || option.value.contains(',')) { errors += ScriptCopy.inputOptionsBad(shown); return emptyList() }
            out += option
        }
        if (out.map { it.value }.toSet().size != out.size) { errors += ScriptCopy.inputOptionsBad(shown); return emptyList() }
        return out
    }

    private const val MAX_NUMBER = 1_000_000_000.0
    private const val MAX_PORT = 65_535
}
