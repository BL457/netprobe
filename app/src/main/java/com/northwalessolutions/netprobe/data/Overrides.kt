package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** The Director's own values for a host (spec 4.1). null means "use the detected value". */
data class DeviceEdits(val name: String? = null, val type: DeviceType? = null, val vendor: String? = null, val model: String? = null) {
    companion object {
        /** The identifier's MAX_VALUE_LEN; longer input is not accepted by the dialog and is cut here (spec 4.3). */
        const val MAX_LEN = 80
        /**
         * What Save stores (spec 4.3): values trimmed; a blank field stores null; a value equal to the detected value stores null,
         * so confirming a detection never pins it; a type equal to the detected type stores null.
         */
        fun of(draft: EditDraft, detected: Detected): DeviceEdits = DeviceEdits(
            name = text(draft.name, detected.name),
            type = draft.type?.takeIf { it != detected.type && it != DeviceType.UNKNOWN },
            vendor = text(draft.vendor, detected.vendor),
            model = text(draft.model, detected.model),
        )
        private fun text(value: String, detected: String?): String? =
            value.trim().take(MAX_LEN).takeIf { it.isNotEmpty() && it != detected?.trim() }
    }
}

/** Detected values shown under each field as "Detected: ..." (spec 4.3). The name ignores customName, never "This phone". */
data class Detected(val name: String, val type: DeviceType, val vendor: String?, val model: String?) {
    companion object {
        fun of(h: HostEntity): Detected = Detected(DisplayName.of(h.copy(customName = null)), h.deviceType, h.identVendor, h.identModel)
    }
}

/** The dialog's editable values; [type] null shows "Not set" and is only possible while the effective type is UNKNOWN. */
data class EditDraft(val name: String, val type: DeviceType?, val vendor: String, val model: String) {
    companion object {
        fun initial(h: HostEntity): EditDraft = EditDraft(
            name = h.customName?.takeIf { it.isNotBlank() } ?: Detected.of(h).name,
            type = Effective.type(h).takeIf { it != DeviceType.UNKNOWN },
            vendor = Effective.vendor(h).orEmpty(),
            model = Effective.model(h).orEmpty(),
        )
    }
}

/** Manual values override detected ones everywhere they show (spec 4.2); they never enter identification evidence (A7). */
object Effective {
    fun type(h: HostEntity): DeviceType = h.userType ?: h.deviceType
    fun vendor(h: HostEntity): String? = h.userVendor ?: h.identVendor
    fun model(h: HostEntity): String? = h.userModel ?: h.identModel
}

/** hosts.identSoftware: software names joined by a newline, trimmed, distinct, in rule-file order (spec 3.6). */
object SoftwareColumn {
    private const val SEPARATOR = "\n"
    fun encode(names: List<String>): String? =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    fun decode(column: String?): List<String> = column?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** After a port scan the stored identity is replaced only by one at least as confident (spec 3.3); the DAO query is the atomic form. */
object IdentityWrite {
    fun replaces(storedConfidence: Int, newConfidence: Int): Boolean = newConfidence >= storedConfidence
}
