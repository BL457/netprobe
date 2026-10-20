package com.northwalessolutions.netprobe.engine.serviceid

enum class Confidence { PORT_TABLE, CONFIRMED }

/**
 * One open port's findings. The six evidence fields (Phase 3A spec 3.1) come after [confidence] and default to null, so every
 * positional call site compiles unchanged: page [title], full [server] header (HTTP, HTTPS or RTSP), auth [realm], X-Powered-By
 * ([poweredBy]), and the leaf certificate's subject CN and O ([certCn], [certOrg]).
 */
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null, val detail: String? = null,
                       val confidence: Confidence, val title: String? = null, val server: String? = null, val realm: String? = null,
                       val poweredBy: String? = null, val certCn: String? = null, val certOrg: String? = null)
data class Evidence(val mdnsTypes: Set<String> = emptySet(), val upnpServer: String? = null)
