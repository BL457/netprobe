# Task 10 review (081a0ca..51f8a99)

Ran `./gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL (up-to-date, 7 ProbeBindTest + existing suite green).

## Spec compliance
- ✅ SocketBinder / NetworkBinder / BindFailedException / BoundNetwork / currentBinder / bindOrFail: signatures match the brief exactly (Tasks 13-15 can consume).
- ✅ Probe.connect(binder) signature matches; bind happens before connect; BindFailedException thrown, socket closed, never Timeout.
- ✅ Sweep, NetBios.query, Snmp.query rethrow BindFailedException; HttpTitle honours binder (non-bind swallow accepted).
- ✅ binderFor present, VPN excluded, Wi-Fi/Ethernet only, null when none.
- ✅ Local-subnet path unchanged: no BoundNetwork means route == null, no bind call, same code after.
- ✅ Cancellation watcher: bind happens before `closer` is launched; on a bind failure nothing is left to watch and the socket is closed explicitly. After bind the watcher/finished/runInterruptible/catch/finally block is byte-identical, so a cancelled coroutine still closes the socket via the UNDISPATCHED awaitCancellation finally. Traced OK.
- ✅ currentBinder(): one CoroutineContext.get on the existing context, no allocation, never actually suspends. Not blocking main (bindSocket runs on the caller's background dispatcher).
- ✅ Callers: HostProber, PortScanEngine, BannerReader, GatewayMac all call Probe.connect with no explicit binder, so they inherit BoundNetwork via context (Sweep/channelFlow/coroutineScope/withContext(Dispatchers.IO) all preserve it). No new dependencies, no em-dashes (grep clean in src, test and report).
- ✅ GatewayMac noted for Task 15 (report line 9). Confirmed: lookup() uses withContext(Dispatchers.IO), which keeps BoundNetwork, so a bind failure there would throw from lookup; the two HomeViewModel callers (lines 136, 154) run outside any BoundNetwork today.
- ✅ Tests are meaningful: order assertion ("connected=false"), context pickup, negative control, nothing-reached-server, Sweep propagation, UDP/URL honour and UDP rethrow. Not tautological.

## Task quality: Approved (with Important items to carry into Task 15 and a test gap)

### Important
1. PortScanEngine.kt:40 `catch (e: Exception)` swallows BindFailedException as a per-port error (done++, errors++). If a port scan or service-ID pass ever runs under a BoundNetwork context (the extended phase for extra-range hosts), a failed bind (e.g. VPN refusing bypass) reports every port as an "error" and the phase never ends with SKIPPED_BIND_FAILED. Scenario: BoundNetwork(binder that throws) + scan(ip) yields a full list of errors and no exception. The brief's rethrow list omits PortScanEngine, so this is not a deviation, but Task 15 must either add `catch (e: BindFailedException) { throw e }` there or guarantee the scan never runs bound. Recommend flagging to the Task 13-15 briefs.
2. TlsPeek.kt:20-21 (SSL socket via createSocket()+connect), DeviceInfoTxt.kt:106 (DatagramSocket), UpnpDescription.kt:85 (url.openConnection) and NvdClient are sockets built outside Probe/binder and ignore BoundNetwork. Brief scope does not include them, but if the extended phase invokes TlsPeek or UpnpDescription for extra-range hosts they would leak onto default routing (mobile data). Task 15 must not call these under BoundNetwork without adding a binder, or explicitly exclude them.

### Minor
1. Probe.kt:34-36 `Socket()` is created before `currentBinder()`/bind; a cancellation or Error between creation and the try (only Exception is wrapped by bindOrFail, so an Error from bind skips socket.close()) leaks the socket. Trivial; resolve binder first or use finally.
2. SocketBinder.kt:41-42 bindOrFail catches Exception, which includes a CancellationException if a custom binder ever throws one; it would be wrapped into BindFailedException. Cosmetic for NetworkBinder.
3. ProbeBindTest: no test that PortScanEngine/BannerReader/GatewayMac pick up the binder (claim rests on grep plus Probe test); no test that Sweep cancels siblings/remaining hosts after a bind failure; the "context" test does not assert the outcome is Connected.
4. ProbeBindTest HttpTitle case fetches from a closed port, so it proves openConnection is routed but not that the fetch works through the binder.
5. NetworkEnvMonitor.kt:5-6 new imports placed above `android.net.*` (import ordering only).
6. binderFor uses `lp.linkAddresses.any { it.address == env.interfaceIp }`, which assumes env.interfaceIp is the same InetAddress type (Inet4Address equality works); Task 20 device QA should confirm with a dual-stack Wi-Fi.

## Plan-text conflict
None. The `; Unit` deviation and the spec 5.5 SocketBinder-for-Network substitution are recorded and accepted.

## Re-review, round 1 (51f8a99..a01c236)
JVM suite: BUILD SUCCESSFUL.
- I1 fixed: PortScanEngine.kt rethrows BindFailedException after the CancellationException rethrow and before the generic `catch (Exception)`. Ordering is correct, so an ordinary per-port IOException still falls to the generic branch (done++/errors++, treated as not-open), not thrown. The `finally` still releases the semaphore.
- CancellationException minor fixed: both bindOrFail overloads rethrow CancellationException before wrapping.
- New tests meaningful: the port-scan test collects inside withContext(BoundNetwork(failing)), so the channelFlow inherits the binder; before the fix it would have returned error-counted results without throwing, so it would fail. The cancellation test fails if the catch were removed (it would become BindFailedException).
- Minor (new, trivial): the cancellation test covers only the Socket overload, not the DatagramSocket one. No regression found.
Verdict: Approved.
