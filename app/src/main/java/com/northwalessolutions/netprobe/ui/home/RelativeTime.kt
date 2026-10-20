package com.northwalessolutions.netprobe.ui.home

import java.text.DateFormat
import java.util.Date

/** "just now", "N seconds ago", "N minutes ago", "N hours ago", "yesterday", then the date (spec section 8). */
object RelativeTime {
    private const val JUST_NOW_S = 5L
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val DAY_S = 86_400L
    private const val TWO_DAYS_S = 2 * DAY_S
    private const val MS_PER_S = 1000L

    fun defaultDate(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

    fun of(then: Long, now: Long, formatDate: (Long) -> String = ::defaultDate): String {
        val s = ((now - then) / MS_PER_S).coerceAtLeast(0)
        return when {
            s < JUST_NOW_S -> HomeCopy.JUST_NOW
            s < MINUTE_S -> "$s seconds ago"
            s < HOUR_S -> (s / MINUTE_S).let { if (it == 1L) "1 minute ago" else "$it minutes ago" }
            s < DAY_S -> (s / HOUR_S).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            s < TWO_DAYS_S -> HomeCopy.YESTERDAY
            else -> formatDate(then)
        }
    }
}
