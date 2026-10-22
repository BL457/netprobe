package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge

/** The one place a port row and a ServiceInfo convert: discovery, Detail and Quick scan all read all fifteen fields (spec 3.1, Gruff M5). */
fun PortEntity.toServiceInfo(): ServiceInfo =
    ServiceInfo(serviceName, product, version, detail, confidence, title, server, realm, poweredBy, certCn, certOrg, stack, stackRole, cpe, probe)

fun PortEntity.withService(s: ServiceInfo, now: Long): PortEntity = copy(
    serviceName = s.name, product = s.product, version = s.version, detail = s.detail, confidence = s.confidence, lastSeen = now,
    title = s.title, server = s.server, realm = s.realm, poweredBy = s.poweredBy, certCn = s.certCn, certOrg = s.certOrg,
    stack = s.stack, stackRole = s.stackRole, cpe = s.cpe, probe = s.probe)

object PortMapping {
    /** A stored row updated by a new finding through [ServiceMerge]: never erases, never lowers a confirmed name. */
    fun merge(stored: PortEntity, fresh: ServiceInfo, now: Long): PortEntity = stored.withService(ServiceMerge.merge(stored.toServiceInfo(), fresh), now)
    fun newRow(hostId: Long, port: Int, info: ServiceInfo, now: Long): PortEntity =
        PortEntity(hostId = hostId, port = port, serviceName = info.name, product = null, version = null, detail = null, confidence = info.confidence, lastSeen = now)
            .withService(info, now)
}
