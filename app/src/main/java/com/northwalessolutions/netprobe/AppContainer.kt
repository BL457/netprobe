package com.northwalessolutions.netprobe

import android.content.Context
import com.northwalessolutions.netprobe.actions.ActionProvider
import com.northwalessolutions.netprobe.actions.AndroidActionRunner
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.common.Locks
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngine
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.portscan.PortScanEngine
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.vulns.*
import com.northwalessolutions.netprobe.netenv.NetworkEnvMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency container of lazily-created singletons. */
class AppContainer(val context: Context) {
    /** The network row Home resolved for the current env; Settings edits that network's manual ranges (extended spec 8.3 item 4). */
    val currentNetwork = MutableStateFlow<NetworkEntity?>(null)
    /** True from the start of an extended run until its discover() returns, whatever screen is showing. */
    val extendedRunActive = MutableStateFlow(false)
    val settings by lazy { AppSettings(context) }
    val db by lazy { NetProbeDb.open(context) }
    val networks by lazy { NetworkRepository(db) }
    val hosts by lazy { HostRepository(db) }
    val ports by lazy { PortRepository(db) }
    val vulnCache by lazy { VulnCacheRepository(db) }
    val networkEnvMonitor by lazy { NetworkEnvMonitor(context) }
    val portTable by lazy { PortTable.fromJson(RawResources.read(context, R.raw.ports)) }
    val mdnsTypes by lazy { RawResources.lines(RawResources.read(context, R.raw.mdns_types)) }
    val identifyRules by lazy { IdentifyRules.load(RawResources.read(context, R.raw.identify_rules)) }
    val cpeMap by lazy { Cpe.load(RawResources.read(context, R.raw.cpe_map)) }
    val discoveryEngine by lazy { DiscoveryEngine.production(context, mdnsTypes) }
    val portScanEngine by lazy { PortScanEngine() }
    val serviceIdentifier by lazy { ServiceIdentifier(portTable) }
    val deviceIdentifier by lazy { DeviceIdentifier(identifyRules) }
    val actionRunner by lazy { AndroidActionRunner(context) }
    val actions by lazy { ActionProvider(actionRunner) }
    val locks by lazy { Locks(context) }
    val ouiLookup: (String) -> String? = { null }
    val vulnLookup by lazy {
        VulnLookup(UrlHttpGet(), object : VulnCache {
            override suspend fun get(query: String, maxAgeMs: Long) = vulnCache.get(query, maxAgeMs)?.let { it.json to it.fetchedAt }
            override suspend fun getAny(query: String) = vulnCache.getAny(query)?.let { it.json to it.fetchedAt }
            override suspend fun put(query: String, json: String) = vulnCache.put(query, json)
        }, { settings.state.value.nvdApiKey }, cpeMap)
    }
}
