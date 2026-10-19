# Gruff review: NetProbe MVP implementation plan (22 tasks)

Reviewer: Gruff (Devil's Advocate), 2026-10-19. Read-only review of `docs/superpowers/plans/2026-10-19-netprobe-mvp.md` against `docs/superpowers/specs/2026-10-19-netprobe-mvp-design.md`. Facts marked "verified" were checked against the cited source or the local machine today.

## Verdict: PROCEED WITH FIXES

The core technical bets hold: the netlink neighbour dump exemption for `untrusted_app_30` exists verbatim in AOSP sepolicy (android-16.0.0_r4), `bind()` is denied to every third-party app so the unbound-socket design is right, `Dispatchers.IO.limitedParallelism(n)` is documented as elastic above 64, the declared library versions are real releases that work together on Gradle 8.14 / JDK 17 / compileSdk 35, and the NDK 28.2.13676358, CMake 3.22.1, platform 35 and build-tools 35.0.0 are already present in the SDK directory (verified by `ls`), so no component download will be triggered. Nothing here needs a redesign. But the plan as written has eight tests that fail or cannot compile on the Windows JVM, two silent on-device defects that would make service identification and Stop appear broken in QA, two identification paths that can never fire (Apple `_device-info` via NsdManager, OpenSSH CPE names), and one task ordering error. Apply findings 1 to 15 to the plan text before Task 1 starts (they are all plan edits, no code exists yet); findings 16 onward are advisories the implementer can apply in-task.

## Findings

### 1. MAJOR, Task 6 (also affects 7, 8, 20): cancellation does not close the socket, so Stop and "network changed" take up to the full connect timeout, and the plan's own cancel test fails

What is wrong: `Probe.connect` wraps `socket.connect(addr, timeout)` in `runInterruptible` and relies on the interrupt to unblock it. `java.net.Socket` operations are not interruptible on platform threads (JDK 13+ `NioSocketImpl` parks in `poll()`; Android's libcore behaves the same). The interrupt flag is set, nothing wakes up, and the `finally` that would close the socket only runs after connect returns on its own. The spec explicitly requires the opposite: "Cancellation closes the socket from the cancelling coroutine (a blocking connect is not interruptible; close() unblocks it)" (spec 5.3). The plan says "Cancellation: suspendCancellableCoroutine is not needed; wrap the blocking connect in runInterruptible" (Task 6 Interfaces) which is the wrong conclusion.

Evidence: Task 6 test `cancellation stops the flow quickly` launches 64 connects to 192.0.2.1 with `connectTimeoutMs = 2000`, cancels at 300 ms and asserts the join took under 1000 ms. With no asynchronous close, `cancelAndJoin` waits for the in-flight connects to time out (about 1700 ms). The test fails as written on Windows and on the phone.

Fix: replace `Probe.connect` with a version that closes the socket from a watcher coroutine on cancellation:

```kotlin
suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false): Outcome = coroutineScope {
    val socket = Socket()
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    // A blocking connect is not interruptible; close() from another coroutine unblocks it (spec 5.3).
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!finished.get()) runCatching { socket.close() } }
    }
    try {
        runInterruptible {
            val t0 = System.nanoTime()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val rtt = ((System.nanoTime() - t0) / 1_000_000).toInt()
            if (keepOpen) Outcome.Connected(rtt, socket) else { socket.close(); Outcome.Connected(rtt, null) }
        }
    } catch (e: SocketTimeoutException) { socket.close(); Outcome.Timeout
    } catch (e: NoRouteToHostException) { socket.close(); Outcome.Unreachable
    } catch (e: ConnectException) { socket.close(); classifyConnect(e) }
    catch (e: Exception) { socket.close(); Outcome.Timeout }
    finally { finished.set(true); closer.cancel() }
}
```

When the caller is cancelled, `coroutineScope` rethrows the `CancellationException` after the block, so the `Outcome.Timeout` produced by the "Socket closed" exception is never observed. Delete the sentence "suspendCancellableCoroutine is not needed..." from Task 6 and replace with "Cancellation closes the socket from a watcher coroutine; the interrupt alone does not unblock connect()". Keep the existing test; it now proves the behaviour.

### 2. MAJOR, Tasks 8, 15, 20: banner reads, TLS peeks and the neighbour dump run on the main thread on Android, fail with NetworkOnMainThreadException, and the catch-alls hide it

What is wrong: `Probe.connect`, `BannerReader.read` and `TlsPeek.certNames` use `runInterruptible { }` with no dispatcher, so they run on whatever thread calls them. `PortScanEngine` and `Sweep` call them from `launch(dispatcher)` (fine), but `ServiceIdentifier.identify` is called from `DetailViewModel.startScan` and `HomeViewModel.quickScanAll` inside `.collect { }` on `viewModelScope`, which is `Dispatchers.Main`. On a device every banner socket operation throws `android.os.NetworkOnMainThreadException`, which is caught by `catch (e: Exception) { socket.close(); Outcome.Timeout }` in `Probe.connect` and `catch (_: Exception) { null }` in `TlsPeek`. Result: every port falls back to the port-table name, `Confidence.CONFIRMED` never appears, no product or version is ever captured, and the CVE lookup (which needs product and version) has nothing to query. JVM tests pass because the JVM has no StrictMode. Likewise `DiscoveryEngine.discover` calls `neighbours()` (a blocking JNI netlink round trip) directly in the `channelFlow` body, which runs on Main when collected from `viewModelScope`.

Evidence: Task 8 `TlsPeek.certNames` body is `runInterruptible { ... s.connect(...) ... }` with no dispatcher argument; Task 20 `DetailViewModel.startScan` calls `c.serviceIdentifier.identify(ip, r.port, ...)` inside `.collect { r -> ... }` launched with `viewModelScope.launch`. Compare Task 11/12/13 which correctly use `runInterruptible(Dispatchers.IO)`.

Fix: (a) `ServiceIdentifier.identify` becomes `suspend fun identify(...): ServiceInfo = withContext(Dispatchers.IO) { ...existing body... }`. (b) `TlsPeek.certNames` uses `runInterruptible(Dispatchers.IO) { ... }`. (c) In `DiscoveryEngine.discover` replace `val neigh = neighbours()` with `val neigh = withContext(Dispatchers.IO) { neighbours() }`. (d) Add to the `Probe.connect` KDoc: "Must be called from a background dispatcher; the engines call it from their limitedParallelism view." Do not put `Dispatchers.IO` inside `Probe.connect` itself: that would funnel the 128-wide scan onto the 64-thread IO pool and halve throughput.

### 3. MAJOR, Task 20: service identification is serialised inside the scan collector, so a Quick scan takes 1 to 1.5 s per open port instead of "1 to 3 seconds per host"; quick-scan-all never re-identifies

What is wrong: `DetailViewModel.startScan` and `HomeViewModel.quickScanAll` call `serviceIdentifier.identify` inline in `collect { }`. Each identify does a connect plus up to a 1 s read (1.5 s for TLS). A host with 12 open ports adds 12 to 18 s of sequential banner reads after the scan itself, and `channelFlow`'s 64-item buffer back-pressures the engine once it fills. Also `quickScanAll` does a DB read of `mdnsTypesFor` per open port (Detail) and never calls `deviceIdentifier.identify` afterwards although the interface text says "then re-identify the device with the services map".

Evidence: Task 20 `DetailViewModel.startScan`: `.collect { r -> val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = c.hosts.mdnsTypesFor(hostId))); found[r.port] = info; c.ports.upsertOpen(...) }`. Spec 5.3 promises "Quick profile 1 to 3 seconds per host" and spec 6.3 says identify "runs (bounded)".

Fix: in both places read `val types = c.hosts.mdnsTypesFor(hostId)` once before the scan, then:

```kotlin
coroutineScope {
    val sem = Semaphore(8)
    c.portScanEngine.scan(ip, resolved, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
        launch { sem.withPermit {
            val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types))
            synchronized(found) { found[r.port] = info }
            c.ports.upsertOpen(hostId, r.port, info)
        } }
    }
}
```

`coroutineScope` waits for the identifies before `markScanned` and re-identify. In `quickScanAll` add the same and, after each host, `c.hosts.byIdOnce(h.id)?.let { e -> c.hosts.setIdentity(h.id, c.deviceIdentifier.identify(HostEvidence(c.hosts.toDiscovered(e), found.keys, found, e.mac?.let(c.ouiLookup)))) }`.

### 4. MAJOR, Task 9: `AndroidNsdSource.resolve` drops any service whose first resolved address is IPv6 and never populates `ipv6`, so iPhones and Macs found only by mDNS can vanish and the EUI-64 MAC source is dead

What is wrong: `onServiceResolved` does `val v4 = i.host as? Inet4Address` and resumes `null` when `host` is not IPv4. `v6` is built from the same single `host`, so it is non-empty only when `v4` is null, in which case the record is discarded anyway. `NsdServiceInfo.getHost()` is deprecated at API 34 in favour of `getHostAddresses()`, which returns the full list; on dual-stack LANs the single `host` can be the IPv6 address. Verified: Android reference for `NsdServiceInfo` states `getHost()` "was deprecated in API level 34. Use getHostAddresses() to get the entire list of addresses for the host" (https://developer.android.com/reference/android/net/nsd/NsdServiceInfo). Spec 5.2 item 7 requires "AAAA answers are kept as ipv6LinkLocal".

Fix:

```kotlin
override fun onServiceResolved(i: NsdServiceInfo) {
    val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses else listOfNotNull(i.host)
    val v4 = addrs.filterIsInstance<Inet4Address>().firstOrNull()
    val v6 = addrs.filterIsInstance<Inet6Address>().map { it.hostAddress.substringBefore('%') }
    val txt = i.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
    if (cont.isActive) cont.resume(v4?.let { MdnsRecord(it, i.serviceName, i.serviceType.trimEnd('.'), i.port, txt, v6) })
}
```

Also: pass the `NsdServiceInfo` received in `onServiceFound` straight into `resolveService` (store it in the queue instead of the `(name, type)` pair) rather than reconstructing one; that is the documented pattern and avoids type-string mismatches. And replace `runCatching { source.resolve(name, type) }.getOrNull()` with a catch of `Exception` that rethrows `CancellationException`, because `runCatching` swallows cancellation.

### 5. MAJOR, Task 10 (also 15): `MacExtract.fromText` fails its own test, and the UDN "MAC" is wrong for random UUIDs

What is wrong: the colon regex ends with `(?![0-9a-fA-F:])`. In the fixture USN `uuid:5c6f-1234-aa:bb:cc:dd:ee:ff::urn` the character after `ff` is `:`, so the negative lookahead rejects the only candidate and the test `mac extraction from usn and names` fails on its first assertion. Separately, treating the last 12 hex digits of every `uuid:` UDN as a MAC is correct only for version 1 (time-based) UUIDs; version 4 UDNs are random, so the app would display and later OUI-match a fabricated MAC on many UPnP devices. That is a direct hit on the Director's "what is on my network" goal.

Evidence: Task 10 test: `assertEquals("aa:bb:cc:dd:ee:ff", MacExtract.fromText("uuid:5c6f-1234-aa:bb:cc:dd:ee:ff::urn"))` versus regex `(?<![0-9a-fA-F:])([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})(?![0-9a-fA-F:])`.

Fix:

```kotlin
object MacExtract {
    private val colon = Regex("(?<![0-9a-fA-F])(?<![0-9a-fA-F]{2}:)([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})(?![0-9a-fA-F])(?!:[0-9a-fA-F]{2})")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-([0-9a-fA-F])[0-9a-fA-F]{3}-[0-9a-fA-F]{4}-([0-9a-fA-F]{12})")
    private val bare = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{12})(?![0-9a-fA-F])")
    fun fromText(s: String): String? {
        colon.find(s)?.let { return it.groupValues[1].lowercase() }
        uuid.find(s)?.let { m -> return if (m.groupValues[1] == "1") m.groupValues[2].lowercase().chunked(2).joinToString(":") else null }
        bare.find(s)?.let { m -> return m.groupValues[1].lowercase().chunked(2).joinToString(":") }
        return null
    }
}
```

The existing test still passes (`uuid:12345678-1234-1234-...` has version nibble 1). Add: `assertNull(MacExtract.fromText("uuid:12345678-1234-4234-1234-aabbccddeeff"))` (v4 UUID, no MAC).

### 6. MAJOR, Tasks 15 and 17: MAC precedence is "first emitted wins", so the primary source (neighbour table, emitted last) loses to any earlier derived MAC

What is wrong: `DiscoveredHost.merge` and `HostRepository.upsertDiscovered` keep the existing non-null `mac`. `emit()` applies `withDerivedMac()` to every emission, so an mDNS record with an EUI-64 link-local, or an SSDP USN, sets `mac` seconds before the sweep finishes and the netlink dump runs. The spec order is netlink primary, NetBIOS and UPnP secondary, names and EUI-64 tertiary (spec 4 and 5.2 item 7). The Task 15 test comment "NetBIOS beats EUI-64" is not tested: both values are `00:11:22:33:44:55`, so any ordering passes.

Evidence: Task 15 `merge`: `mac = mac ?: o.mac`; Task 17: `mac = h.mac ?: existing.mac`; Task 15 test fixture uses `fe80::211:22ff:fe33:4455` (EUI-64 of 00:11:22:33:44:55) and NetBIOS `00:11:22:33:44:55`.

Fix: add `enum class MacSource(val rank: Int) { EUI64(1), NAME(1), UPNP_UDN(2), NETBIOS(3), NEIGH(4) }` and `val macSource: MacSource? = null` to `DiscoveredHost`; set it wherever `mac` is set (NETBIOS in enrichment, NEIGH in the dump, UPNP_UDN/NAME/EUI64 in `withDerivedMac` and the SSDP branch). `merge`: `val (m, ms) = if ((o.macSource?.rank ?: 0) > (macSource?.rank ?: 0)) o.mac to o.macSource else mac to macSource`. Add `macSource: Int` (rank, default 0) to `HostEntity` and in `upsertDiscovered` replace `mac` only when `(h.macSource?.rank ?: 0) >= existing.macSource` (ties refresh). Change the test fixture's link-local to `fe80::211:22ff:fe33:4499` and keep the assertion that `pc.mac == "00:11:22:33:44:55"`; now it proves precedence.

### 7. MAJOR, Task 10: `UpnpDescriptionParser` uses `XmlPullParserFactory`, which is an `android.jar` stub on the JVM; every UPnP test fails

What is wrong: `org.xmlpull.v1.XmlPullParserFactory` ships in the mockable `android.jar`. With `unitTests.isReturnDefaultValues = true` (Task 1) `newInstance()` returns null, `newPullParser()` throws NPE, the `catch` returns null, and `router description`, `takes the root device` and `tv description` all fail. There is no pull-parser implementation on the local unit test classpath (junit, coroutines-test, turbine only).

Fix: parse with `javax.xml.parsers.SAXParserFactory`, which is real on both runtimes (the JDK's `java.xml` module takes precedence over the android.jar stub on the test classpath; Android ships its own). Harden it because the bytes come from an arbitrary LAN device:

```kotlin
val f = SAXParserFactory.newInstance().apply {
    isNamespaceAware = false
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
}
```

Use a `DefaultHandler` that tracks depth, enters the first `<device>`, ignores everything under `<deviceList>`, and records text of direct children (`friendlyName`, `manufacturer`, `modelName`, `modelNumber`, `deviceType`, `UDN`). Return null on any `SAXException`. Do not add kxml2 or Robolectric to get XmlPull working; it is an unnecessary dependency.

### 8. MAJOR, Task 12: the SNMP tests only prove the encoder agrees with the decoder, and the hand-written "real-shaped" hex is wrong in three places

What is wrong: every positive assertion in `SnmpTest` decodes bytes produced by `Snmp.encodeResponseForTest`, which shares `Ber` with the decoder. A symmetric bug (for example in multi-byte sub-identifier encoding) is invisible. The only independent bytes are the hand-written `response`, which the plan itself labels "(truncated descr for brevity)" and uses only as `response.copyOf(20)` in the garbage test, where any 20 bytes pass. Checked byte by byte: the outer length `30 5d` does not match the content; the third varbind declares `30 1b` (27) but contains 21 bytes; the sysObjectID declares `06 0f` (15) and gives 9 bytes; and `14988` is encoded as `81 f5 1c`, which is wrong (14988 = 117 * 128 + 12, so the correct base-128 encoding is `f5 0c`).

Fix: replace the `response` constant with this correct GetResponse for sysDescr "RouterOS RB750Gr3", sysName "gw", sysObjectID 1.3.6.1.4.1.14988.1, request-id 0x5a5a (lengths verified: varbinds 0x1d, 0x0e, 0x14; list 0x45; PDU 0x51; message 0x5e):

```
30 5e 02 01 01 04 06 70 75 62 6c 69 63 a2 51 02 02 5a 5a 02 01 00 02 01 00 30 45
30 1d 06 08 2b 06 01 02 01 01 01 00 04 11 52 6f 75 74 65 72 4f 53 20 52 42 37 35 30 47 72 33
30 0e 06 08 2b 06 01 02 01 01 05 00 04 02 67 77
30 14 06 08 2b 06 01 02 01 01 02 00 06 08 2b 06 01 04 01 f5 0c 01
```

Add `@Test fun parses a hand-written get response` asserting `Snmp.parseResponse(response)` yields exactly those three values, and `@Test fun oid encodes multi-byte subidentifiers` asserting `Snmp.buildGet()` is unaffected but `Ber.oid(intArrayOf(1,3,6,1,4,1,14988,1))` equals `06 08 2b 06 01 04 01 f5 0c 01` (make `Ber` `internal` instead of `private` so the test can reach it). Keep the encoder round-trip tests as extras. Move `encodeResponseForTest` into the test source set; it has no business in the APK.

### 9. MAJOR, Task 16: the "every rule fires" test is underspecified and does not test what it claims

What is wrong: (a) `SyntheticEvidence.forRule` is never defined; the plan says it "lives in the test sources" and sketches it in one sentence, so the implementer invents it. (b) The assertion `out.reasons.any { it.startsWith("${r.field}:") }` passes when any rule on the same field fired, not the rule under test (several `anyPort` and `hostname` rules overlap). (c) Nothing checks that a rule's `type` is a valid `DeviceType` name (a typo like `"Computer"` silently becomes `UNKNOWN` via `runCatching { DeviceType.valueOf(it) }`), or that `$1` is only used when the pattern has a group, or that regex rules carry a `sample` (the plan says the loader test asserts this but the test code does not).

Fix: define `SyntheticEvidence.forRule(r, ip)` in the plan: `mdnsType` -> `mdnsServiceTypes = setOf(sample)`; `mdnsTxt` -> split sample on the first `:` and `=` into `mdnsServiceTypes = setOf(type)`, `mdnsTxt = mapOf(type to mapOf(key to value))`; `upnpDeviceType`, `snmpSysDescr`, `httpTitle`, `httpRealm`, `httpServer` -> that `DiscoveredHost` field = sample; `hostname` -> `reverseDnsName = sample`; `sshBanner` -> `services = mapOf(22 to ServiceInfo("SSH", "OpenSSH", null, sample, CONFIRMED))`; `ports`/`anyPort` -> `openPorts = (ports ?: anyPort).toSet()`; `gateway` -> `isGateway = true`. Replace the assertion with a differential check:

```kotlin
for (r in rules.filter { it.field != "oui" }) {
    val e = SyntheticEvidence.forRule(r, ip)
    assertNotEquals("rule ${r.id} contributed nothing", DeviceIdentifier(rules - r).identify(e), DeviceIdentifier(rules).identify(e))
}
```

Extend the loader test: `r.type == null || DeviceType.values().any { it.name == r.type }`; `r.pattern == null || r.sample != null`; `r.pattern?.let { Regex(it) }` compiles; if any of `vendor`/`model`/`os` contains `$1` then `Regex(r.pattern!!).find(r.sample!!)?.groups?.get(1) != null`.

### 10. MAJOR, Tasks 20 and 21: Task 20 cannot meet its own done criterion because it compiles against `AppContainer` members that only Task 21 creates

What is wrong: `HomeViewModel`, `DetailViewModel`, `Nav` and the screens reference `c.networkEnvMonitor`, `c.discoveryEngine`, `c.hosts`, `c.settings`, `c.actions`, `c.vulnLookup`, `c.ouiLookup`, `c.locks` and so on. Task 20 says "this task declares what it needs on AppContainer and Task 21 provides them", then Step 6 runs `:app:assembleDebug` and expects success. It will not compile. The global rule says a task is done only when `assembleDebug` succeeds and the commit is made.

Fix: swap the two tasks. Task 21 (wiring: `AppContainer`, `Locks`, `RawResources`) depends only on Tasks 2 to 19 and compiles on its own; move its Step 4 "install on the device, launch" to the end of the UI task, since before the UI exists the activity only shows "NetProbe". Renumber so the plan reads 20 = wiring, 21 = ViewModels and screens, 22 = QA.

### 11. MAJOR, Task 7 (declared in Task 15): `Sweep` must be `open` from the start

What is wrong: Task 7 defines `class Sweep` with a final `fun run`. Task 15's tests subclass it (`object : Sweep(HostProber(), 8) { override fun run(...) }`) and the plan admits "Sweep must be declared open class Sweep with open fun run for these fakes; adjust Task 7's class accordingly." A Task 7 implementer working from Task 7 alone ships a final class and Task 15 fails to compile.

Fix: in Task 7's Interfaces block and code, write `open class Sweep(...)` and `open fun run(hosts: Sequence<Inet4Address>): Flow<Liveness>`. Delete the retrofit sentence from Task 15.

### 12. MAJOR, Tasks 9 and 16 (goal: identification): the top Apple rule can never fire, because `_device-info._tcp` is not a browsable service

What is wrong: Appendix D's strongest Apple evidence is "mDNS `_device-info._tcp` TXT `model=`" (weight 90) and Task 16's first rule and test (`apple device-info model`) depend on it. Apple devices publish `<Name>._device-info._tcp.local TXT model=...` but no PTR record for the type, so a DNS-SD browse (`NsdManager.discoverServices("_device-info._tcp")`, which is PTR-based) returns nothing. The record can only be obtained by querying that instance name's TXT record directly, which `NsdManager` cannot do. Evidence: Google Fiber's `dnssdmon` obtains the model by passively capturing TXT records for "`<device name>._device-info._tcp.local`" rather than browsing (https://gfiber.googlesource.com/vendor/google/platform/+/master/cmds/dnssdmon.cc); RFC 6763 section 4 defines browsing as PTR lookup of the service type (https://datatracker.ietf.org/doc/rfc6763/). The spec already lists "Own mDNS enumerator" as roadmap, which is the honest reflection of this limit, but the rules table and tests pretend the data arrives.

Fix (Director's choice; I recommend A): A. Add a small `DeviceInfoTxt` probe to Task 9: for each distinct mDNS instance name N resolved for a host (Mac SMB/AFP instance names and iPhone `_companion-link` instance names are the device name Apple also uses for `_device-info`), send one DNS query (QTYPE TXT, QU bit set) for `N._device-info._tcp.local` to 224.0.0.251:5353 and unicast to the host on 5353, 1 s timeout, parse the TXT RDATA into `mdnsTxt["_device-info._tcp"]`. This is about 80 lines of `DatagramSocket` code with a fixture test, the same style as `NetBios`. B. Keep NsdManager only: delete `_device-info._tcp` from `mdns_types.txt` and Appendix B, demote the `apple-device-info` rule to documentation ("fires only when a future enumerator supplies the TXT"), and tell the Director that Macs will be identified as "Apple macOS Computer" via `_smb`/`_afpovertcp`/hostname rules and iPhones via `_companion-link`, without model codes.

### 13. MAJOR, Task 19 (goal: CVE lookup): the OpenSSH CPE the plan builds does not exist in the NVD, so QA step 8's headline case returns nothing

What is wrong: `cpe_map.json` renders `cpe:2.3:a:openbsd:openssh:9.6p1:*:*:*:*:*:*:*`. NVD records OpenSSH 9.6p1 as version `9.6` with update `p1`. Verified: NVD CPE detail lists `cpe:2.3:a:openbsd:openssh:9.6:p1:*:*:*:*:*:*` (https://nvd.nist.gov/products/cpe/detail/1601884) and CVE-2025-26466's configuration uses the same form (https://sec-certs.org/vuln/cve/CVE-2025-26466). The `cpeName` parameter matches against configuration version ranges; a non-existent version string yields zero results. The SSH banner is the first thing the Director will try (spec 10, step 8).

Fix: (a) In `Cpe.forService`, before substitution, if the product is OpenSSH and the version matches `^(\d+\.\d+)p(\d+)$`, substitute `{v}` with `"$1:p$2"`; generalise by allowing templates to carry `{v}:{u}` and parsing `version[p|-]update`. Change the test expectation to `cpe:2.3:a:openbsd:openssh:9.6:p1:*:*:*:*:*:*`. (b) In `VulnLookup.run`, when a `ByCpe` query returns `Ok` with `total == 0`, automatically retry once as `ByKeyword("<product> <version>")` and report that query text in the sheet (the sheet already shows the query). Add a test with a fake `HttpGet` returning `{"totalResults":0,"vulnerabilities":[]}` for the CPE URL and the fixture for the keyword URL, asserting the second call happened and the result is `Ok` with 2 vulns.

### 14. MAJOR, Task 13: the title test's expected value contradicts the parser

What is wrong: `HttpTitleParser.title` decodes `&amp;` to `&`, so `"<TITLE>\n  Archer   AX55 &amp; Login </TITLE>"` yields `"Archer AX55 & Login"`. The test asserts `"Archer AX55 Login"`. Fails as written.

Fix: change the expectation to `"Archer AX55 & Login"`. While there: the `fetches title server and realm` fixture declares `Content-Length: 40` for a 42-byte body; set it to 42 so the client does not read a truncated body by accident.

### 15. MAJOR, Task 2: the gate's logcat grep will show `avc: granted` lines that look like failures, and the plan's targetSdk claim is narrower than the policy

What is wrong: verified in AOSP android-16.0.0_r4: `private/untrusted_app_30.te` contains `allow untrusted_app_30 self:netlink_route_socket nlmsg_getneigh;` and `auditallow untrusted_app_30 self:netlink_route_socket nlmsg_getneigh;` plus the matching `allowxperm`/`auditallowxperm` for `RTM_GETNEIGH RTM_GETNEIGHTBL` (https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-16.0.0_r4/private/untrusted_app_30.te). `auditallow` means every successful dump logs `avc: granted { nlmsg_getneigh }`. Task 2 Step 8 greps `avc:.*netlink` and says "no avc: denied lines", so a correct run prints granted lines that a subagent may read as denials and wrongly trigger the STOP gate. Also, the same file's header reads "29 < targetSdkVersion <= 31", so targetSdk 31 keeps the dump too; the plan and spec say "30 or lower". The neverallow in `app_neverallows.te` exempts exactly `untrusted_app_25/27/29/30` and denies `bind` and `nlmsg_readpriv` (RTM_GETLINK) to all untrusted apps with no exemption (https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-16.0.0_r4/private/app_neverallows.te), which confirms the unbound-socket design.

Fix: change the grep to `grep -E "NeighbourSpike|avc: denied.*netlink"` and add the sentence "`avc: granted { nlmsg_getneigh }` lines are expected (auditallow) and are not a failure." Correct the plan's comment in `neigh.c` and Global Constraints to "targetSdkVersion <= 31 (untrusted_app_30 domain)". Keep targetSdk 30 unless the Director wants 31; it costs nothing to stay at 30, but the Director should know 31 is available if an Android 12 behaviour is ever wanted.

### 16. MINOR, Tasks 6, 8, 17, 19, 20: interface blocks disagree with the code that follows

- Task 6 Interfaces: `Connected(val rttMs: Int, val socket: Socket)`; code: `Socket?`. Use `Socket?` in the Interfaces block (BannerReader already handles null).
- Task 8 Interfaces: `object TlsPeek`; code: `open class TlsPeek` and `ServiceIdentifier(..., tls: TlsPeek = TlsPeek())`. Use `open class`.
- Task 8 Interfaces omit the `portOverride: Map<Int, Int> = emptyMap()` constructor parameter the tests use.
- Task 17 never declares `HostDao.byIdOnce`, `HostRepository.byIdOnce`, `mdnsTypesFor`, `toDiscovered`, `PortDao.counts(): Flow<List<PortCount>>`, `data class PortCount(val hostId: Long, val n: Int)` or `AppSettings.scanOptions()`; Task 20 adds them as asides. Move all of them into Task 17's Interfaces and code so Task 17's implementer writes them and Task 20 only consumes.
- Task 19 Interfaces omit `RateLimiter`'s `sleeper` parameter and `VulnLookup`'s two-limiter constructor; align the block with the code.
- Task 20 Interfaces omit `HomeUiState.quickScanning`; add it.
- Spec 5.2 and Task 15 use `Source` without `GATEWAY`; the plan adds it. Fine, but note it in the spec coverage list.

### 17. MINOR, Task 11: fixture RDLENGTH is wrong (harmless because the parser ignores it)

`"0053"` with the comment "RDLENGTH: 1 + 3*18 + 46 = 101". 101 is `0x65`. Change to `"0065"` so the fixture is a faithful packet. Everything else in the NetBIOS fixture checks out byte for byte (header 12, name 34, type/class/TTL 8, RDLENGTH 2, count 1, 3 x 18, MAC at offset 111 = `size - 46`, which matches the `all zero mac` test).

### 18. MINOR, Task 14: `Eui64.macFromLinkLocal` calls `InetAddress.getByName` on arbitrary strings

For a non-literal such as `"not an address"` the JVM performs a DNS lookup (slow, network-dependent) before failing. Guard with `if (!ipv6.contains(':') || ipv6.any { it !in "0123456789abcdefABCDEF:.%" }) return null` before `getByName`.

### 19. MINOR, Task 1: deprecated `kotlinOptions`, and one download trap to forbid explicitly

`kotlinOptions { jvmTarget = "17" }` is deprecated in Kotlin 2.1 (warning now, error later); use `kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }`. Add to Global Constraints: "Never add `kotlin { jvmToolchain(...) }` or the foojay toolchain resolver; toolchain auto-provisioning downloads a JDK. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop: a component is missing and must not be fetched without the Director." (All required components were verified present today: platforms android-35, ndk 28.2.13676358, cmake 3.22.1, build-tools 35.0.0, gradle-8.14-all cached, jdk-17.0.2.)

### 20. MINOR, Tasks 15 and 20: spec items that quietly dropped out

- Spec 5.2 item 6: "On Android 12 and lower, read /proc/net/arp if netlink fails." Not implemented anywhere. Either add a 15-line fallback in `NeighbourTable.dump()` for `Build.VERSION.SDK_INT <= 28` or strike the sentence from the spec.
- Spec 5.4 item 3: UPnP `SERVER` header as service evidence. `Evidence.upnpServer` exists but `DetailViewModel` only ever passes `mdnsTypes`. Pass `upnpServer = entity.upnpServer` (add the column) or remove the field.
- Spec 8: concurrency halving on `EMFILE` bursts. The plan's self-review defers it; say so in the spec's "Decisions" table rather than leaving the spec promising it.
- Appendix D row "NetBIOS name present -> Windows 70" has no rule in `identify_rules.json`; add `{"id":"netbios-present","field":"netbiosName","pattern":".+","os":"Windows","type":"COMPUTER","weight":60,"sample":"OFFICE-PC"}` and the `netbiosName` field (matched against `host.netbiosName`), or strike the row.

### 21. MINOR, Task 16: `snmp-printers` pattern is a case-insensitive substring match on `HP`

`(HP|Hewlett-Packard|Brother|EPSON|Canon|Lexmark|Kyocera|Xerox)` with `IGNORE_CASE` matches `hp` inside words. Use word boundaries: `\b(HP|Hewlett-Packard|Brother|EPSON|Canon|Lexmark|Kyocera|Xerox)\b`.

### 22. MINOR, Task 20: `HomeScreenTest.scanTogglesToStopWhenOnWifi` passes whatever happens

The try/catch on `AssertionError` makes the test green in both branches and starts a real LAN scan from an instrumented test. Replace with two tests gated on `ConnectivityManager` state via `Assume.assumeTrue(...)`, so one runs on Wi-Fi and the other off Wi-Fi, and each asserts one outcome.

### 23. MINOR, Director-facing notes on the targetSdk 30 decision (no plan change required)

- Install on Android 16/17: the on-device install block refuses only apps targeting below API 24 on Android 15 and 16 (Android 14 was 23); Android 17's floor is not yet published (https://bayton.org/android/android-minimum-targetsdk-matrix/). targetSdk 30 installs via adb or sideload without `--bypass-low-target-sdk-block`. The Pixel 7 Pro's last OS update is Android 16 in any case.
- Behaviours a targetSdk 30 app keeps that newer targets lose: `WifiManager.connectionInfo` SSID/BSSID with fine location (no `NEARBY_WIFI_DEVICES` dance), `ConnectivityManager.allNetworks`, `queryIntentActivities` with `<queries>`, implicit `exported` defaults. Behaviours it does not get: Android 12 approximate-location toggle in the permission dialog is still shown (system adds COARSE automatically), Android 13 runtime notification permission is granted implicitly on first channel (relevant only to the roadmap alerts), predictive back and per-app language are unavailable, and Google Play upload is impossible (already accepted in spec 3).
- `Dispatchers.IO.limitedParallelism(128)` is safe: the official docs state views of `Dispatchers.IO` are not restricted by its 64-thread parallelism (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html).
- Library versions in Task 1 (AGP 8.10.1, Kotlin 2.1.21, KSP 2.1.21-2.0.1, Compose BOM 2025.06.00, Room 2.7.1, navigation 2.9.0, lifecycle 2.9.1, core-ktx 1.16.0, coroutines 1.10.2, serialization 1.8.1, turbine 1.2.0, activity-compose 1.10.1) are all released artifacts from before June 2025 and are mutually compatible with Gradle 8.14 (AGP 8.10 requires 8.11.1+) and JDK 17; compileSdk 35 satisfies the AndroidX 2025 minimums. I did not re-fetch each Maven page; the Task 1 fallback ("pick the nearest resolvable version") covers a typo.
- NVD limits quoted in the spec (5 per rolling 30 s without key, 50 with, header `apiKey`) match the NVD developer documentation as I know it; the NVD pages render client-side and could not be re-fetched today.

## Must-fix before Task 1 starts

Apply findings 1 to 15 as edits to the plan text now (they touch Tasks 2, 6, 7, 8, 9, 10, 12, 13, 15, 16, 17, 19, 20, 21). Findings 16 to 22 should also be applied to the plan since they are one-line edits, but they will not block a task if missed. Finding 12 needs a Director decision (A or B) before Task 9 starts. Finding 23 is information for the Director; no action.
