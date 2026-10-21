package com.northwalessolutions.netprobe.ui.settings

/** Every Settings string 3C adds (spec 7.2). Templates live in CopyTableTest. */
object SettingsCopy {
    const val MENU = "Settings menu"
    const val RESET = "Reset to defaults"
    const val RESET_DONE = "Settings reset to defaults"
    /** The Large networks caption (plan review G6, proposed wording for the Director): the old one was false with Aggressive scan on. */
    const val LARGE_NETWORKS_CAPTION = "Largest block of a big network to sweep. With Aggressive scan off, a network wider than this gets your /24 only. A /22 takes about 1 minute, a /20 about 4."
    /** [unit] is written as the value shows it: "" or " ms". */
    fun defaultMarker(value: Int, unit: String) = "Default $value$unit"
    fun resetDescription(label: String, value: Int, unit: String) = "Reset $label to $value$unit"
}

/** The marker beside a moved slider or stepper (spec 1.3): null, so it takes no space, while the value is the default. */
object DefaultMarker {
    fun text(value: Int, default: Int, unit: String): String? = if (value == default) null else SettingsCopy.defaultMarker(default, unit)
}
