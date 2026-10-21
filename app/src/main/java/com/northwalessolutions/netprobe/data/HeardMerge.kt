package com.northwalessolutions.netprobe.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Most service types a heard write may leave in a row's stored mDNS TXT (final review S4c). */
const val HEARD_TXT_TYPES_MAX = 16
/** Most bytes of encoded TXT a heard write may leave in a row (final review S4c). */
const val HEARD_TXT_BYTES_MAX = 2_048

/** The rules that make a listener sighting weaker than a scan (3C spec 3.4, final review S3 and S4c). Pure, so the JVM can pin them. */
object HeardMerge {
    /** A scan takes a MAC of equal or higher rank; a heard MAC is only ever derived, so it is taken only when the row has none (S3). */
    fun takeMac(existingMac: String?, existingRank: Int, newMac: String?, newRank: Int, heard: Boolean): Boolean {
        if (newMac == null) return false
        if (existingMac == null) return true
        return !heard && newRank >= existingRank
    }

    /** True when the identity write must be setIdentityIfNotWeaker: always for a heard write, else when it is the same device (S3). */
    fun keepsStrongerIdentity(forHeard: Boolean, previousMac: String?, currentMac: String?): Boolean {
        return forHeard || IdentityWrite.sameDevice(previousMac, currentMac)
    }

    /**
     * The per-type TXT union. A scan merges everything; a heard write adds a type only while the row holds fewer than [HEARD_TXT_TYPES_MAX]
     * and keeps the stored TXT whole when the union would pass [HEARD_TXT_BYTES_MAX] (S4c).
     */
    fun txtUnion(existing: Map<String, Map<String, String>>, incoming: Map<String, Map<String, String>>, heard: Boolean): Map<String, Map<String, String>> {
        if (!heard) return existing + incoming
        var merged = existing
        for ((type, txt) in incoming) {
            if (type !in merged && merged.size >= HEARD_TXT_TYPES_MAX) continue
            val next = merged + (type to txt)
            if (Json.encodeToString(next).toByteArray().size > HEARD_TXT_BYTES_MAX) continue
            merged = next
        }
        return merged
    }
}
