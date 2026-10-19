# NetProbe MVP Design

Date: 2026-10-19
Status: draft for Director review
Project: `K:\claude\netprobe` (working name NetProbe, rename at any time)

## 1. Purpose

NetProbe is a network discovery and port scanning app for non-rooted Android phones. It replaces the paywalled parts of Fing for two kinds of use, weighted equally:

- Everyday use on the Director's own networks: see what is connected, name devices, open their services.
- Authorised assessment work: quickly enumerate hosts and services on a client network the Director is engaged to test, annotate findings, and keep an inventory per network.

Success for the MVP: on the Director's home network the app finds the devices the router lists (phones, PCs, media boxes, printers, IoT), port-scans a chosen host in seconds, labels the common services correctly, launches the right app for a service with one tap, and remembers investigated/interesting marks when the phone rejoins the network.

## 2. Scope

### 2.1 MVP (this spec)

1. Auto-discover devices on the current Wi-Fi or Ethernet network with no manual IP range entry.
2. Fast TCP port scan of a host (quick, full, or custom profile), and a "quick scan all" over every discovered host.
3. Service identification per open port: well-known port table, mDNS/UPnP evidence, and a light banner read on high-value ports.
4. Launch actions per service (browser, SMB, SSH, VNC, RDP, FTP, Telnet) with copy and "open with" fallbacks.
5. Annotation: status (Unmarked, Interesting, Investigated, Ignored), custom name, free-text note. Filter by status and search by text.
6. Persistence of hosts, ports and annotations per network so they return when the phone rejoins that network.
7. A "your network" panel: interface IP and prefix, gateway, DNS, SSID when permitted.
8. Device identification: vendor, model, operating system and device type inferred on the device from mDNS TXT records, UPnP descriptions, NetBIOS, hostnames, SNMP sysDescr, HTTP titles and headers, open-port patterns and, where a MAC is known, the OUI vendor. The device list shows "what it is", not just an IP.
9. Look up vulnerabilities: an on-demand search of the NVD for CVEs matching an identified product and version (per service) or vendor and model (per device), shown with CVSS score and a link out.

### 2.2 Roadmap (not in MVP, listed so any item can be pulled forward)

- Network snapshots and new/unknown device alerts (foreground service plus notifications).
- Extended discovery across other RFC1918 ranges to test segmentation.
- Informational exposure flags (Telnet open, SMBv1, plaintext admin panels).
- Wake-on-LAN, per-host ping and traceroute.
- CSV/JSON export and a shareable report.
- Custom tags with colours.
- Bundled OUI vendor database (needs Director approval of the IEEE download; see 11).
- UDP probes beyond SNMP (NTP, DNS, mDNS unicast), IPv6 link-local discovery.
- Additional vulnerability sources alongside the NVD (VulDB, Vulners, CIRCL vulnerability-lookup).
- Apple model identifier table (`MacBookPro18,3` to its marketing name); the MVP shows the raw code.
- Identification rule updates shipped as a downloadable rules file instead of an app update.
- NIO selector scan engine, adaptive timeouts, and automatic concurrency halving on resource errors.
- Own mDNS enumerator querying `_services._dns-sd._udp.local`.
- Public IP/ISP panel (opt-in, the only internet call the app would ever make).
- Suggesting an app to install when no handler exists for a service.

### 2.3 Non-goals

- Anything requiring root: raw sockets, SYN or stealth scans, ARP injection, packet capture.
- Exploitation, credential testing, or vulnerability scanning. NetProbe reports what is listening and hands off to other apps.
- iOS, desktop, or web builds.

## 3. Decisions already made

| Decision | Choice | Rejected alternative and why |
|---|---|---|
| Stack | Native Kotlin, Jetpack Compose, coroutines/Flow, Room | Flutter would reuse the Procrastinkitting toolchain but needs platform channels for NSD, Wi-Fi and connectivity APIs, and the whole product is network I/O. |
| First release | Lean MVP (section 2.1) | A full-featured v1 delays the empirical proof that discovery works on real networks. |
| Host liveness | TCP connect probes to a short port list; connect or refused both mean alive | `InetAddress.isReachable()` depends on ICMP, which Android often blocks, and its fallback is unreliable. |
| mDNS | Android `NsdManager` with a known service-type list | A hand-rolled mDNS enumerator is more complete but is more work; roadmap. |
| Port scan engine | Blocking sockets on `Dispatchers.IO.limitedParallelism(n)` with a semaphore | NIO selector scales further but adds complexity the MVP does not need; roadmap. |
| targetSdkVersion | 30 (Android 11); compileSdk and minSdk unaffected | Targeting 31 or higher loses the netlink neighbour dump on Android 13 and later, and with it most MAC addresses. Cost: sideload only (Play requires a current target), and Android 11 era runtime behaviours. Acceptable for a personal and professional tool. |
| Device identification | Local rules engine over gathered evidence (Appendix D) | A cloud recognition service like Fing's needs a backend and sends network data off the phone. |
| Vulnerability data | NVD API 2.0 with an optional free API key | VulDB needs an account and its free tier is tightly rate-limited; Shodan CVEDB needs no key but carries thinner metadata. Either can be added as a second source later. |
| Dependency injection | Manual `AppContainer` | Hilt adds annotation processing and boilerplate for a single-module app. |
| Project layout | Single Gradle module `app`, packages per layer | Multi-module is premature. |

## 4. Platform constraints (non-root Android)

- No raw sockets. All probing is ordinary TCP connect and UDP datagrams.
- `/proc/net/arp` is unreadable from Android 10. The kernel neighbour table is still readable through a netlink `RTM_GETNEIGH` dump (what `ip neigh show` does), but from Android 13 the AOSP SELinux policy allows that only for apps whose `targetSdkVersion` is 30 or lower, and `bind()` on netlink route sockets is denied to every third-party app. NetProbe therefore sets `targetSdkVersion = 30` (compileSdk stays current) and performs the dump itself over an unbound netlink socket in a small JNI helper, after the liveness sweep has populated the table. This is the primary MAC source; NetBIOS node-status replies and UPnP descriptions are secondary. Phones using per-network randomised MACs show their randomised address, never the hardware one. A feasibility spike on the Pixel 7 Pro is plan step 0 (section 11); if the dump is denied there, the app keeps the secondary sources and identification relies on names, service types and port fingerprints.
- Hosts that drop every TCP probe (iPhones, hardened devices) are found only through mDNS or SSDP. This is documented in the UI as "found via mDNS" and is the same limitation Fing has.
- Multicast reception requires a `WifiManager.MulticastLock` for the duration of discovery.
- Wi-Fi SSID and BSSID require the runtime permission `ACCESS_FINE_LOCATION` with location services enabled, on every Android version the app supports (`NEARBY_WIFI_DEVICES` does not unlock them). The app requests this lazily and works without it.
- `Dispatchers.IO` defaults to 64 threads. Blocking connects at higher concurrency use `limitedParallelism(n)`, which the IO dispatcher honours above 64.
- Android 11 package visibility: every implicit intent scheme the app resolves must be declared under `<queries>` in the manifest, otherwise `PackageManager` reports no handler.

## 5. Architecture

Single Activity, Compose UI, MVVM. Each layer is a Kotlin package in the `app` module and depends only on the layer below it. Engines expose Kotlin `Flow`s so the UI fills in as results arrive and cancellation is structured.

```
ui            Compose screens and ViewModels
actions       service -> Intent mapping, handler resolution
data          Room entities, DAOs, repositories, network signature
engine        discovery, portscan, serviceid (pure Kotlin + sockets)
netenv        ConnectivityManager / WifiManager / LinkProperties reader
```

Package root: `com.northwalessolutions.netprobe` (applicationId the same; rename with the project).

### 5.1 netenv: network environment

Reads the active network that has `TRANSPORT_WIFI` or `TRANSPORT_ETHERNET` (never cellular; a VPN-only active network is ignored and the underlying Wi-Fi network is used when present).

Output model:

```kotlin
data class NetworkEnv(
    val interfaceIp: Inet4Address,
    val prefixLength: Int,
    val gateway: Inet4Address?,
    val dnsServers: List<InetAddress>,
    val ssid: String?,        // null unless location permission granted
    val bssid: String?,       // same
    val transport: Transport, // WIFI or ETHERNET
)
```

Subnet rule: if `prefixLength >= 22` the sweep covers the whole subnet (at most 1022 hosts). If smaller, the sweep covers the /24 containing `interfaceIp` and the UI shows a notice: "Large network (/16). Scanned your /24 only. Widen in Settings." A setting allows up to /20 (4094 hosts).

Exposes `Flow<NetworkEnv?>` driven by a `NetworkCallback`; `null` means no usable network. A change of network during a scan cancels the scan and shows a message.

### 5.2 engine.discovery

`DiscoveryEngine.discover(env: NetworkEnv, options: DiscoveryOptions): Flow<DiscoveredHost>` runs the strategies below concurrently inside one coroutine scope and merges by IPv4 address. Emits a host the first time any strategy sees it and re-emits with merged fields as more evidence arrives.

```kotlin
data class DiscoveredHost(
    val ip: Inet4Address,
    val mac: String? = null,
    val reverseDnsName: String? = null,
    val mdnsName: String? = null,
    val netbiosName: String? = null,
    val upnpFriendlyName: String? = null,
    val upnpManufacturer: String? = null,
    val upnpModel: String? = null,
    val mdnsServiceTypes: Set<String> = emptySet(),
    val mdnsTxt: Map<String, Map<String, String>> = emptyMap(), // service type -> TXT attributes
    val mdnsInstanceNames: Set<String> = emptySet(),
    val ipv6LinkLocal: Set<String> = emptySet(),
    val snmpSysDescr: String? = null,
    val snmpSysName: String? = null,
    val snmpSysObjectId: String? = null,
    val httpTitle: String? = null,
    val httpServer: String? = null,
    val httpAuthRealm: String? = null,
    val probedOpenPorts: Set<Int> = emptySet(),
    val sources: Set<Source> = emptySet(),  // SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP
    val isGateway: Boolean = false,
)
```

Strategies:

1. **TCP liveness sweep.** For each address in the sweep range, connect to the liveness ports in order: 80, 443, 22, 445, 139, 135, 3389, 8080, 62078, 7, 53, 548. Stop that host on the first definitive answer. Connect success or `ECONNREFUSED` means alive. `EHOSTUNREACH`, `ENETUNREACH` or timeout on every port means not listed. Default concurrency 128 hosts in flight, per-connect timeout 600 ms. Ports that connected are recorded in `probedOpenPorts`.
2. **mDNS via NsdManager.** `discoverServices` for each type in Appendix B. Resolutions go through a single queue because `NsdManager.resolveService` is serialised on most Android versions. Each resolved service yields host IP, `mdnsName` and a service type. Discovery for all types runs for 6 seconds then stops.
3. **SSDP/UPnP.** Send `M-SEARCH * HTTP/1.1` with `ST: ssdp:all`, `MX: 2` to 239.255.255.250:1900 three times over 3 seconds; listen for 4 seconds. Parse `LOCATION`, `SERVER`, `ST`, `USN`. Fetch each distinct `LOCATION` XML (GET, 2 s timeout, 64 KB cap) and read `friendlyName`, `manufacturer`, `modelName`.
4. **NetBIOS node status.** For each alive host, send one NBSTAT request to UDP 137 (1 s timeout). Parse the name table (first unique group-0x00 name is the computer name, group names give workgroup) and the unit ID field, which is the MAC address.
5. **Reverse DNS.** For each alive host, `InetAddress.getByAddress(...).hostName` on the IO dispatcher with a 1 s timeout; keep it only when it differs from the IP string.
6. **Neighbour table.** After the sweep, dump the IPv4 neighbour table once via netlink `RTM_GETNEIGH` with `NLM_F_DUMP` (JNI, `AF_NETLINK` + `NETLINK_ROUTE`, no `bind()`), keeping entries in REACHABLE, STALE, DELAY or PROBE state. Each entry supplies a MAC for a swept host. On Android 12 and lower, read `/proc/net/arp` if netlink fails. On `EACCES` do nothing and record that the source is unavailable so the UI can say so.

7. **mDNS TXT records.** Every resolved service's TXT attributes and instance name are kept, for example `_device-info._tcp` `model=MacBookPro18,3`, `_airplay._tcp` `model=AppleTV6,2`, `_googlecast._tcp` `md=Chromecast Ultra` and `fn=Living Room`, `_hap._tcp` `md=`. AAAA answers are kept as `ipv6LinkLocal`. Instance names or TXT values containing a MAC pattern, and EUI-64 link-local addresses (`ff:fe` in the middle of the interface identifier), are secondary MAC sources.
8. **SNMP sysDescr.** One SNMPv2c GET to UDP 161 with community `public` for `sysDescr`, `sysName` and `sysObjectID` per alive host, 1 s timeout, hand-written BER encoder and decoder (no library). Routers, switches, printers and NAS units commonly answer with an exact model string.
9. **HTTP title.** For hosts whose liveness probe opened 80, 443, 8080 or 8443, fetch `/` once (2 s timeout, 64 KB cap, follow one same-host redirect) and keep the `<title>`, the `Server` header and any `WWW-Authenticate` realm. Router admin pages, printers and cameras name themselves here.

The gateway from `NetworkEnv` is always included and flagged `isGateway` even if it drops all probes.

Multicast lock and `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) are held for the whole discovery run and released in `finally`.

Expected duration for a /24 on a home Wi-Fi: 10 to 20 seconds, with first hosts appearing within 2 seconds.

### 5.3 engine.portscan

`PortScanEngine.scan(ip: Inet4Address, profile: ScanProfile, options: ScanOptions): Flow<PortResult>`.

```kotlin
enum class PortState { OPEN, CLOSED, FILTERED }
data class PortResult(val port: Int, val state: PortState, val rttMs: Int?)
data class ScanOptions(val concurrency: Int = 128, val connectTimeoutMs: Int = 600)
sealed class ScanProfile {
    object Quick : ScanProfile()                  // Appendix A, 115 ports
    object Full : ScanProfile()                   // 1..65535
    data class Custom(val ports: List<Int>) : ScanProfile()
}
```

Classification: connect success is OPEN; `ConnectException` with refused is CLOSED; `SocketTimeoutException` or unreachable is FILTERED. Only OPEN results are stored; CLOSED and FILTERED feed the progress counter.

Each connect runs as `Socket().connect(addr, timeout)` inside `Dispatchers.IO.limitedParallelism(concurrency)`. Cancellation closes the socket from the cancelling coroutine (a blocking `connect` is not interruptible; `close()` unblocks it) and the whole scan is one child scope so Stop tears everything down.

Expected durations at defaults on Wi-Fi: Quick profile 1 to 3 seconds per host; Full profile 2 to 6 minutes against a host that drops unknown ports, under 1 minute against one that refuses them. Progress and Stop are always visible.

Custom profile input accepts `22,80,8000-8100` syntax; invalid input is rejected inline.

"Quick scan all" runs Quick against every known host sequentially with the same engine so concurrency is bounded.

### 5.4 engine.serviceid

`ServiceIdentifier.identify(ip, port, evidence): ServiceInfo` combines:

1. Port table (Appendix C, bundled JSON resource): the baseline name.
2. mDNS service type seen for that host and port (for example `_rfb._tcp` on 5900 confirms VNC, `_ipp._tcp` on 631 confirms IPP).
3. UPnP `SERVER` header or description for the HTTP port named in `LOCATION`.
4. Banner read, only for OPEN ports, each bounded to 1 second and 1 KB:
   - Server-speaks-first protocols: read first line. `SSH-2.0-OpenSSH_9.6` gives product and version. FTP (21), SMTP (25, 587), POP3 (110), IMAP (143) likewise.
   - HTTP ports (80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400): send `HEAD / HTTP/1.0\r\nHost: <ip>\r\n\r\n`, read status line and `Server` header.
   - TLS ports (443, 8443, 7443, 8006, 5001): TLS handshake with a trust-all manager used only to read the certificate subject CN and SANs, then the same HEAD. The browser launch action never uses this trust setting.
   - Unknown open ports: read up to 256 bytes for 1 second; if the bytes start with `SSH-` or `HTTP/` reclassify; if printable, keep as raw banner.

```kotlin
data class ServiceInfo(
    val name: String,            // "HTTP", "SSH", "VNC", "Unknown"
    val product: String? = null, // "OpenSSH", "nginx"
    val version: String? = null,
    val detail: String? = null,  // cert CN, HTTP title, raw banner excerpt
    val confidence: Confidence,  // PORT_TABLE, CONFIRMED
)
```

### 5.4.1 engine.identify: device identification

`DeviceIdentifier.identify(evidence: HostEvidence): DeviceIdentity` runs after discovery and again after any port scan of the host. `HostEvidence` is the `DiscoveredHost` plus its open ports with `ServiceInfo` and the OUI vendor when a MAC is known.

```kotlin
data class DeviceIdentity(
    val vendor: String?,      // "Apple", "Synology", "TP-Link"
    val model: String?,       // "MacBookPro18,3", "DS920+", "Archer AX55"
    val os: String?,          // "iOS", "Android", "Windows", "macOS", "Linux", "RouterOS"
    val type: DeviceType,     // Router, Phone, Computer, Printer, Media, IoT, NAS, Camera, Server, Unknown
    val confidence: Int,      // 0..100
    val reasons: List<String> // "mDNS _device-info model=MacBookPro18,3", "SNMP sysDescr"
)
```

Two layers:

1. **Direct extraction**, highest confidence: UPnP `manufacturer` and `modelName` are taken verbatim; SNMP `sysDescr` is parsed with per-vendor regexes (Appendix D lists the first set); mDNS `model=` and `md=` TXT values become the model; the OUI vendor becomes the vendor when nothing better exists.
2. **Rules table** in a bundled resource `identify_rules.json`, hand-authored, no download. Each rule has a match (regex on a named evidence field, a required port set, or an mDNS service type) and the fields it sets with a weight. Per field the highest-weight match wins, ties go to the earlier rule, and every applied rule adds a reason. Appendix D is the seed table; adding a rule is a JSON edit plus a fixture test.

Display: the device row's secondary line becomes `vendor model` or, failing that, `os type` (for example "Apple MacBookPro18,3", "Android Phone", "Windows Computer", "Printer"). Device detail lists each reason so the user can see why. The type still picks the icon. Unknown stays honest: "Unknown device" with whatever names were found.

### 5.5 actions

`ActionProvider.actionsFor(host, port, service): List<DeviceAction>`.

| Service | Primary action | Intent |
|---|---|---|
| HTTP | Open in browser | `ACTION_VIEW http://ip:port/` |
| HTTPS / TLS | Open in browser | `ACTION_VIEW https://ip:port/` |
| SMB | Open share | `ACTION_VIEW smb://ip/` |
| SSH | Connect | `ACTION_VIEW ssh://ip:port` |
| Telnet | Connect | `ACTION_VIEW telnet://ip:port` |
| VNC | Connect | `ACTION_VIEW vnc://ip:port` |
| RDP | Connect | `ACTION_VIEW rdp://ip:port` |
| FTP | Open | `ACTION_VIEW ftp://ip:port/` |
| Any | Copy `ip`, Copy `ip:port`, Open with... | clipboard, `ACTION_CHOOSER` |

Each scheme is declared under `<queries>`. Before showing an action, `PackageManager.queryIntentActivities` confirms a handler exists; otherwise the row shows "No app installed for smb://" and the copy actions. "Open with..." always shows the system chooser for the URI.

### 5.6 data

Room database `netprobe.db`, schema version 1, exported schema committed for future migrations.

```kotlin
@Entity data class NetworkEntity(
    @PrimaryKey val id: Long, val signature: String, val displayName: String,
    val gatewayIp: String, val prefixLength: Int, val ssid: String?, val lastSeen: Long)

@Entity(indices = [Index("networkId","ip", unique = true)])
data class HostEntity(
    @PrimaryKey val id: Long, val networkId: Long, val ip: String, val mac: String?,
    val reverseDnsName: String?, val mdnsName: String?, val netbiosName: String?,
    val upnpName: String?, val upnpManufacturer: String?, val upnpModel: String?,
    val vendor: String?, val deviceType: DeviceType, val customName: String?,
    val identVendor: String?, val identModel: String?, val identOs: String?,
    val identConfidence: Int, val identReasons: List<String>,
    val snmpSysDescr: String?, val httpTitle: String?, val mdnsTxtJson: String?,
    val status: HostStatus, val note: String, val isGateway: Boolean,
    val firstSeen: Long, val lastSeen: Long, val lastPortScan: Long?)

@Entity(indices = [Index("hostId","port", unique = true)])
data class PortEntity(
    @PrimaryKey val id: Long, val hostId: Long, val port: Int,
    val serviceName: String, val product: String?, val version: String?,
    val detail: String?, val confidence: Confidence, val lastSeen: Long)

@Entity data class VulnCacheEntity(
    @PrimaryKey val query: String, val json: String, val fetchedAt: Long)

enum class HostStatus { UNMARKED, INTERESTING, INVESTIGATED, IGNORED }
```

Network signature: `sha256(bssid ?: ssid ?: "") + gatewayIp + "/" + prefixLength`. Without location permission two different networks that both use 192.168.1.1/24 share a signature; the Settings screen explains that granting location fixes this.

Display name precedence for a host: `customName`, `mdnsName`, `netbiosName`, `upnpName`, `reverseDnsName`, then the IP.

Repositories: `NetworkRepository`, `HostRepository`, `PortRepository`. Discovery results are upserted as they arrive; a host not seen in the latest discovery is kept with its old `lastSeen` and shown dimmed, never deleted automatically. Annotations are never overwritten by scan data.

### 5.7 ui

Material 3, dynamic colour, dark theme follows the system. Per the Director's standing rule, Scan/Stop and the detail-screen scan controls are pinned outside any scrolling list.

**Home**
- Pinned header: network panel (SSID or "Wi-Fi", `ip/prefix`, gateway), Scan / Stop button, linear progress with "Found 14, probing 203/254".
- Pinned filter row: chips All, Interesting, Investigated, Ignored, Unmarked; search field matching name, IP, vendor, service names.
- Scrolling device list sorted by IP. Row: type icon, display name, IP, secondary line with the identity ("Apple MacBookPro18,3", "Android Phone", "Unknown device, found via mDNS"), open-port count badge, status chip. Gateway row pinned to the top of the list. Hosts not seen in the latest run are dimmed.
- Overflow menu: Quick scan all, Settings.
- Empty states: "Connect to Wi-Fi or Ethernet to scan", "No devices found yet, tap Scan".

**Device detail**
- Header: display name (tap to edit custom name), IP, MAC and vendor when known, identity (vendor, model, OS, type) with an expandable "why" list of reasons, every discovered name with its source, and a "Look up vulnerabilities" button for the device.
- Status: segmented control with the four statuses. Note: multi-line text field, saved on change.
- Pinned scan bar: profile selector (Quick, Full, Custom), Scan / Stop, progress "312 / 65535".
- Scrolling ports list: port, service name, product and version, detail line, primary action button, overflow with the remaining actions and "Look up vulnerabilities" for that service.

**Settings**
- Discovery: toggles for mDNS, SSDP, NetBIOS, reverse DNS; sweep concurrency (32 to 512, default 128); connect timeout (200 to 2000 ms, default 600); large-network behaviour.
- Port scan: concurrency and timeout with the same ranges.
- Permissions: a row that requests location so the SSID and network signature improve.
- Vulnerabilities: optional NVD API key field, and a clear-cache button.

ViewModels hold `StateFlow<HomeUiState>` and `StateFlow<DetailUiState>` built from repository flows combined with in-progress scan state. Scan jobs live in the ViewModel scope and are cancelled when the user taps Stop or the network changes; they are not cancelled on rotation.

### 5.8 vulns: Look up vulnerabilities

Runs only when the user taps "Look up vulnerabilities" on a service row or on the device header. Nothing is queried automatically.

- `VulnLookup.forService(service: ServiceInfo)`: when product and version are known, build a CPE 2.3 name through a small product-to-CPE table `cpe_map.json` (`OpenSSH` to `cpe:2.3:a:openbsd:openssh:<version>:*:*:*:*:*:*:*`, `nginx` to `cpe:2.3:a:f5:nginx:<version>:...`, `Apache httpd`, `lighttpd`, `Dropbear`, `ProFTPD`, `vsftpd`, `Samba`, `MiniUPnPd`, `BusyBox`, `Exim`, `Postfix`, `Dovecot`, `Pure-FTPd`, `Microsoft IIS`) and query by `cpeName`. Otherwise query `keywordSearch="<product> <version>"`.
- `VulnLookup.forDevice(identity: DeviceIdentity)`: `keywordSearch="<vendor> <model>"`.
- Source: NVD API 2.0, `https://services.nvd.nist.gov/rest/json/cves/2.0`, `resultsPerPage=50`, optional `apiKey` header from Settings. A token bucket enforces the published limit: 5 requests per rolling 30 s without a key, 50 with one.
- Result model `Vuln(cveId, cvssScore, severity, published, summary, url)`, sorted by CVSS descending. Cached in Room (`VulnCacheEntity`: query, json, fetchedAt) with a 24 h TTL; a second tap within that window is served from cache, offline too.
- UI: a bottom sheet with counts per severity, the query that was sent (editable, so a noisy keyword match can be refined and re-run), and the list: CVE ID, score chip, one-line summary. Tap opens the NVD page in the browser. Empty state: "No CVEs found for <query>". No connection: "Offline, showing cached results from <time>" or "Offline".
- Only product, version, vendor and model strings leave the phone. IPs, MACs, hostnames and the network identity never do.

## 6. Data flow

1. `NetworkEnvMonitor` emits `NetworkEnv`. Home shows the panel and enables Scan.
2. Tap Scan: ViewModel resolves or creates the `NetworkEntity` by signature, launches `DiscoveryEngine.discover`, and for each emitted `DiscoveredHost` upserts via `HostRepository`. Room flows push the updated list to the UI.
3. Open a host: Detail reads the host and its ports from Room. Tap Scan: `PortScanEngine.scan` emits `PortResult`s; for each OPEN, `ServiceIdentifier.identify` runs (bounded) and the result is upserted into `PortEntity`. The list fills as results land.
4. Tap an action: `ActionProvider` builds the intent, `startActivity` with a catch for `ActivityNotFoundException` that falls back to the copy actions.
5. Change status or note: written to Room immediately; filters react through the same flow.

## 7. Permissions and manifest

Normal permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
Runtime, optional: `ACCESS_FINE_LOCATION`, requested only from Settings or when the user taps the SSID placeholder.
`<queries>` entries for schemes: http, https, smb, ssh, telnet, vnc, rdp, ftp.
`android:usesCleartextTraffic="true"` is required for the UPnP description fetch and HTTP banner reads.

## 8. Error handling

- No usable network: scanning disabled with the empty-state message. Cellular-only is treated as no usable network.
- Network change mid-scan: cancel, keep partial results, show "Network changed, scan stopped".
- Per-probe exceptions are caught inside the engine task, counted, and never propagate. The progress text shows "errors: n" when non-zero. Resource errors (`EMFILE` and similar) are counted there; automatic concurrency halving is deferred to the roadmap unless on-device QA shows resource errors at the default concurrency.
- `NsdManager` failure codes are logged and mDNS is skipped for that run without failing discovery.
- Multicast lock or Wi-Fi lock acquisition failure: log and continue (SSDP and mDNS may miss results).
- Room write errors are surfaced as a snackbar; scans continue.
- The app never crashes on malformed SSDP, UPnP XML, NetBIOS or banner bytes; parsers return `null` on anything unexpected and are fuzz-style tested with truncated and garbage inputs.

## 9. Privacy and network behaviour

- Scanning traffic is LAN only: probes plus the UPnP description, SNMP and HTTP title fetches to LAN addresses. The single internet call is the NVD query behind "Look up vulnerabilities", made only when the user taps it, carrying product, version, vendor and model strings and nothing that identifies the network. No telemetry, no analytics.
- All data is stored on the device in the app's private database. Nothing is exported in the MVP.

## 10. Testing and verification

Unit tests (JVM, no device):
- CIDR maths: sweep range for each prefix, clamping rule, address iteration excluding network and broadcast.
- Port table lookup, Quick profile list integrity (no duplicates, all within range), custom profile parser including bad input.
- Banner parsers: SSH, FTP, SMTP, HTTP status and `Server`, TLS certificate name extraction, with fixtures including truncated and binary garbage.
- SSDP response parser, UPnP description parser, NetBIOS node-status parser, all from recorded fixture packets committed under `app/src/test/resources`.
- `DiscoveredHost` merge logic across sources; display-name precedence.
- `DeviceIdentifier`: one fixture evidence set per Appendix D row, asserting vendor, model, OS, type and that the reasons name the evidence used; plus an all-empty evidence set yielding Unknown.
- SNMP BER encoder and response decoder, including malformed and truncated replies.
- HTTP title and header extraction, including pages with no title, non-UTF-8 bytes and oversized bodies.
- CPE builder for every entry in `cpe_map.json`; NVD JSON parser against a recorded response fixture; token-bucket rate limiter timing; cache TTL expiry.
- `ActionProvider` mapping with a fake handler resolver, including the no-handler fallback.
- Filter and search reducer for the Home list.
- Port scan engine against in-process `ServerSocket`s on loopback: open port detected, closed port classified, a never-accepting port times out as FILTERED, cancellation closes sockets within 1 second.

Instrumented tests (device or emulator):
- Room DAO round trips and the unique index behaviour.
- Compose tests: Home list renders hosts, filter chips hide and show rows, Detail status change persists.

On-device verification (mandatory per the QA process; static review alone is not acceptable):
1. Build a debug APK and install it on the Pixel 7 Pro.
2. On the Director's home network, run discovery. Compare the found set with the router's client list; every router-listed device that is not an always-dropping phone must appear. Record the count and the run time.
3. Port-scan the Director's PC with the Quick profile; confirm the known listening ports show with correct service names and that a Full scan completes with Stop working mid-run.
4. Tap the HTTP action on the router; the browser opens the admin page. Tap an SSH or VNC action where an app is installed; where none is installed, confirm the fallback text and copy action.
5. Mark one host Interesting with a note, force-stop the app, relaunch, re-run discovery; the mark and note survive and the filter shows only that host.
6. Switch to another Wi-Fi network and back; annotations from the first network reappear only on that network.
7. Identification: the router, a Windows PC, a phone and a media or IoT device each show a sensible identity line; record which reasons each used and which devices stayed Unknown.
8. Look up vulnerabilities on an SSH service with a version banner and on the router's device identity; results load with scores, a second tap is served from cache with Wi-Fi off.

Definition of done: all unit and instrumented tests pass, every step in the on-device list is recorded with result and run time, and the Director has confirmed the discovered device set matches expectations.

## 11. Open items

1. OUI vendor database: the IEEE `oui.csv` download needs the Director's explicit approval before any agent fetches it. The MVP ships without it and shows vendor only when UPnP supplies a manufacturer.
2. Final app name, icon and `applicationId`. The icon needs Director approval before it goes on the app.
3. Toolchain check at plan step 0: JDK 17, Android SDK platform 35, Gradle wrapper, Compose BOM, `adb` location (not on PATH; Procrastinkitting notes hold the full path).
4. Verify on the Pixel 7 Pro that SSID and BSSID are returned once `ACCESS_FINE_LOCATION` is granted and location services are on. If the build redacts them anyway, the network signature falls back to gateway and prefix only.
5. NVD API key: optional and free from nvd.nist.gov. The Director requests one only if the keyless limit of 5 requests per 30 s gets in the way. VulDB is added only if the Director already holds an account.
6. Spike, plan step 0: a throwaway `targetSdkVersion = 30` app that sweeps the subnet then performs the netlink neighbour dump on the Pixel 7 Pro. Pass means MACs come back for hosts just swept with no SELinux denial in logcat. Fail means the netlink source is dropped from the MVP and the spec's MAC wording reverts to secondary sources only.

## Appendix A: Quick profile ports (115)

nmap top 100 TCP:
7, 9, 13, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143, 144, 179, 199, 389, 427, 443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587, 631, 646, 873, 990, 993, 995, 1025, 1026, 1027, 1028, 1029, 1110, 1433, 1720, 1723, 1755, 1900, 2000, 2001, 2049, 2121, 2717, 3000, 3128, 3306, 3389, 3986, 4899, 5000, 5009, 5051, 5060, 5101, 5190, 5357, 5432, 5631, 5666, 5800, 5900, 6000, 6001, 6646, 7070, 8000, 8008, 8009, 8080, 8081, 8443, 8888, 9100, 9999, 10000, 32768, 49152, 49153, 49154, 49155, 49156, 49157

LAN extras:
1883 (MQTT), 2222 (alt SSH), 3001, 5001 (Synology DSM), 5555 (adb), 5985 (WinRM), 8006 (Proxmox), 8096 (Jellyfin), 8123 (Home Assistant), 8291 (MikroTik), 8880, 9000, 9090, 32400 (Plex), 62078 (iOS lockdown)

## Appendix B: mDNS service types queried

`_http._tcp`, `_https._tcp`, `_ssh._tcp`, `_sftp-ssh._tcp`, `_smb._tcp`, `_afpovertcp._tcp`, `_nfs._tcp`, `_ftp._tcp`, `_telnet._tcp`, `_rfb._tcp`, `_printer._tcp`, `_ipp._tcp`, `_ipps._tcp`, `_pdl-datastream._tcp`, `_airplay._tcp`, `_raop._tcp`, `_googlecast._tcp`, `_spotify-connect._tcp`, `_androidtvremote2._tcp`, `_hap._tcp`, `_matter._tcp`, `_mqtt._tcp`, `_esphomelib._tcp`, `_workstation._tcp`, `_device-info._tcp`, `_companion-link._tcp`, `_homekit._tcp`, `_sonos._tcp`, `_hue._tcp`, `_amzn-wplay._tcp`

## Appendix C: port table

Bundled as `app/src/main/res/raw/ports.json`, a list of `{port, proto, name}` covering the IANA well-known assignments for every port in Appendix A plus the common LAN services (RTSP 554, MQTT 1883, AMQP 5672, Redis 6379, PostgreSQL 5432, MySQL 3306, MongoDB 27017, Elasticsearch 9200, Node-RED 1880, Octoprint 5000, Kodi 8080, Plex 32400, Jellyfin 8096, Home Assistant 8123, Proxmox 8006, Synology 5000/5001, QNAP 8080/443, Unifi 8443, Pi-hole 80/4711, VNC 5900 to 5910, RDP 3389, WinRM 5985/5986, SMB 445/139, NetBIOS 137/138, LDAP 389/636, Kerberos 88, iOS lockdown 62078, adb 5555). Authored by hand in the repo; no download required.

## Appendix D: identification rules seed

Direct extraction always wins over these. Weights: 90 strong, 70 good, 50 hint.

| Evidence | Sets | Weight |
|---|---|---|
| mDNS `_device-info._tcp` TXT `model=` present | vendor Apple, model = value, type Computer | 90 |
| mDNS `_airplay._tcp` TXT `model=AppleTV*` | vendor Apple, model = value, type Media | 90 |
| mDNS `_googlecast._tcp` TXT `md=` | vendor Google, model = value, type Media | 90 |
| mDNS `_hap._tcp` or `_matter._tcp` | type IoT; `md=` as model when present | 70 |
| mDNS `_companion-link._tcp` or `_rdlink._tcp` without `_device-info` | vendor Apple, os iOS, type Phone | 70 |
| mDNS `_sonos._tcp` | vendor Sonos, type Media | 90 |
| mDNS `_hue._tcp` | vendor Philips Hue, type IoT | 90 |
| mDNS `_ipp._tcp`, `_printer._tcp` or `_pdl-datastream._tcp` | type Printer; TXT `ty=` or `product=` as model | 90 |
| mDNS `_androidtvremote2._tcp` | os Android TV, type Media | 90 |
| mDNS `_workstation._tcp` | os Linux, type Computer | 50 |
| mDNS `_smb._tcp` with `_afpovertcp._tcp` or `_device-info` | os macOS | 70 |
| UPnP `deviceType` contains `InternetGatewayDevice` | type Router | 90 |
| UPnP `deviceType` contains `MediaRenderer` or `MediaServer` | type Media | 70 |
| UPnP `deviceType` contains `Printer` | type Printer | 90 |
| SNMP `sysDescr` matches `RouterOS` | vendor MikroTik, os RouterOS, type Router | 90 |
| SNMP `sysDescr` matches `^Linux DiskStation` or `Synology` | vendor Synology, type NAS | 90 |
| SNMP `sysDescr` matches `QNAP` | vendor QNAP, type NAS | 90 |
| SNMP `sysDescr` matches `HP (LaserJet|OfficeJet|Color)` or `Brother` or `EPSON` or `Canon` | vendor = match, type Printer | 90 |
| SNMP `sysDescr` matches `Cisco IOS` | vendor Cisco, os IOS, type Router | 90 |
| HTTP title matches `Synology|DiskStation` | vendor Synology, type NAS | 70 |
| HTTP title matches `OpenWrt|LuCI` | os OpenWrt, type Router | 90 |
| HTTP title matches `pfSense|OPNsense` | os = match, type Router | 90 |
| HTTP title matches `Home Assistant` or port 8123 open | model Home Assistant, type Server | 90 |
| HTTP title matches `Proxmox` or port 8006 open | model Proxmox VE, type Server | 90 |
| HTTP title matches `Plex` or port 32400 open | model Plex Media Server, type Server | 70 |
| HTTP title matches `Pi-hole` | model Pi-hole, type Server | 90 |
| HTTP title matches `UniFi` | vendor Ubiquiti, type Router | 70 |
| HTTP `WWW-Authenticate` realm matches `TP-LINK|NETGEAR|D-Link|ASUS|Zyxel|Technicolor|Sagemcom` | vendor = match, type Router | 70 |
| HTTP `Server` matches `Hikvision|Dahua|Reolink|Axis` or `_rtsp` seen or port 554 with 80 | vendor = match when present, type Camera | 70 |
| hostname matches `^(Pixel|Galaxy|OnePlus|Xiaomi|Redmi|POCO|android-)` | os Android, type Phone | 70 |
| hostname matches `iPhone|iPad` | vendor Apple, os iOS, type Phone | 70 |
| hostname matches `MacBook|iMac|Mac-mini|Mac-Studio` | vendor Apple, os macOS, type Computer | 70 |
| hostname matches `^DESKTOP-|^LAPTOP-|^WIN-` or NetBIOS name present | os Windows, type Computer | 70 |
| hostname matches `^(ESP_|ESP-|Shelly|Tasmota|wled|Sonoff)` | type IoT, vendor = match | 70 |
| hostname matches `raspberrypi|^rpi` | vendor Raspberry Pi, os Linux, type Computer | 70 |
| port 62078 open | vendor Apple, os iOS, type Phone | 70 |
| port 5555 open | os Android | 50 |
| ports 135 and 445 open, or 3389 open | os Windows, type Computer | 70 |
| ports 548 and 5900 open | vendor Apple, os macOS, type Computer | 50 |
| port 9100 or 631 or 515 open | type Printer | 70 |
| port 8009 and 8008 open | vendor Google, type Media | 70 |
| port 1400 open | vendor Sonos, type Media | 70 |
| port 8291 open | vendor MikroTik, type Router | 90 |
| ports 5000 and 5001 open | vendor Synology, type NAS | 50 |
| port 1883 open | type IoT | 50 |
| SSH banner matches `Ubuntu|Debian|Raspbian` | os = match (Linux) | 70 |
| SSH banner matches `OpenSSH_for_Windows` | os Windows | 90 |
| SSH banner matches `dropbear` | os Linux (embedded), type IoT | 50 |
| `isGateway` | type Router | 90 |
| OUI vendor known and nothing else | vendor = OUI vendor | 50 |
