package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.home.IdentityLine

/** Edit device dialog wording (spec 7). */
object EditCopy {
    const val TITLE = "Edit device"
    const val NAME = "Name"
    const val TYPE = "Type"
    const val VENDOR = "Vendor"
    const val MODEL = "Model"
    const val RESET = "Reset to detected"
    const val NOT_SET = "Not set"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    private const val NONE = "none"
    fun detected(value: String?): String {
        return "Detected: ${value?.takeIf { it.isNotBlank() } ?: NONE}"
    }
    /** The nine real types, in this order; "unknown" is never offered (spec 4.3). */
    val TYPES = listOf(DeviceType.ROUTER, DeviceType.PHONE, DeviceType.COMPUTER, DeviceType.PRINTER, DeviceType.MEDIA,
        DeviceType.IOT, DeviceType.NAS, DeviceType.CAMERA, DeviceType.SERVER)
    fun typeLabel(t: DeviceType): String = with(IdentityLine) { t.label }
}
