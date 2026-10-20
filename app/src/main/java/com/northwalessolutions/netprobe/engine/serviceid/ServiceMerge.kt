package com.northwalessolutions.netprobe.engine.serviceid

/**
 * The one merge of a stored port finding with a new one (spec 3.1): field by field a new non-null value wins and a null never
 * erases; the name and confidence never go down, so a PORT_TABLE guess (a timed-out HEAD) never replaces a CONFIRMED name,
 * while a new CONFIRMED name does replace an old one.
 */
object ServiceMerge {
    fun merge(stored: ServiceInfo?, fresh: ServiceInfo): ServiceInfo {
        if (stored == null) return fresh
        val keepName = stored.confidence == Confidence.CONFIRMED && fresh.confidence == Confidence.PORT_TABLE
        return ServiceInfo(
            name = if (keepName) stored.name else fresh.name,
            product = fresh.product ?: stored.product, version = fresh.version ?: stored.version, detail = fresh.detail ?: stored.detail,
            confidence = if (keepName) stored.confidence else fresh.confidence,
            title = fresh.title ?: stored.title, server = fresh.server ?: stored.server, realm = fresh.realm ?: stored.realm,
            poweredBy = fresh.poweredBy ?: stored.poweredBy, certCn = fresh.certCn ?: stored.certCn, certOrg = fresh.certOrg ?: stored.certOrg,
        )
    }
}
