package com.northwalessolutions.netprobe.engine.serviceid

/**
 * The one merge of a stored port finding with a new one (spec 3.1): field by field a new non-null value wins and a null never
 * erases; the name and confidence never go down, so a PORT_TABLE guess (a timed-out HEAD) never replaces a CONFIRMED name,
 * while a new CONFIRMED name does replace an old one. A service that changed is not haunted by its predecessor (3E spec 7): when the
 * fresh name is CONFIRMED and differs from the stored CONFIRMED name, the stored product, version, detail, stack, stackRole, cpe and
 * probe are cleared before the merge. The four stack fields otherwise merge like product.
 */
object ServiceMerge {
    fun merge(stored: ServiceInfo?, fresh: ServiceInfo): ServiceInfo {
        if (stored == null) return fresh
        val renamed = stored.confidence == Confidence.CONFIRMED && fresh.confidence == Confidence.CONFIRMED && stored.name != fresh.name
        return mergeInto(if (renamed) stored.copy(product = null, version = null, detail = null, stack = null, stackRole = null, cpe = null, probe = null) else stored, fresh)
    }

    private fun mergeInto(stored: ServiceInfo, fresh: ServiceInfo): ServiceInfo {
        val keepName = stored.confidence == Confidence.CONFIRMED && fresh.confidence == Confidence.PORT_TABLE
        return ServiceInfo(
            name = if (keepName) stored.name else fresh.name,
            product = fresh.product ?: stored.product, version = fresh.version ?: stored.version, detail = fresh.detail ?: stored.detail,
            confidence = if (keepName) stored.confidence else fresh.confidence,
            title = fresh.title ?: stored.title, server = fresh.server ?: stored.server, realm = fresh.realm ?: stored.realm,
            poweredBy = fresh.poweredBy ?: stored.poweredBy, certCn = fresh.certCn ?: stored.certCn, certOrg = fresh.certOrg ?: stored.certOrg,
            stack = fresh.stack ?: stored.stack, stackRole = fresh.stackRole ?: stored.stackRole, cpe = fresh.cpe ?: stored.cpe, probe = fresh.probe ?: stored.probe,
        )
    }
}
