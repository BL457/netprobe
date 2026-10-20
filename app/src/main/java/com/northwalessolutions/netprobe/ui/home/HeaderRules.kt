package com.northwalessolutions.netprobe.ui.home

/** D12: chips and search collapse only while the large disc shows AND the filter is All AND the query is blank. */
object HeaderRules {
    fun showFilters(mode: RadarMode, filter: StatusFilter, query: String): Boolean =
        mode == RadarMode.SETTLED || filter != StatusFilter.ALL || query.isNotBlank()
}
