package com.northwalessolutions.netprobe.engine.serviceid

enum class Confidence { PORT_TABLE, CONFIRMED }
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null,
                       val detail: String? = null, val confidence: Confidence)
data class Evidence(val mdnsTypes: Set<String> = emptySet(), val upnpServer: String? = null)
