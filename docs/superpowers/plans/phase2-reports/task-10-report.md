# Task 10 report: bind extended-phase sockets to the Wi-Fi network

Status: DONE.

## Implemented
Verbatim from the brief: SocketBinder.kt (SocketBinder, NetworkBinder, BindFailedException, BoundNetwork, currentBinder, bindOrFail), Probe.connect(binder), Sweep rethrow, NetBios/Snmp bind + rethrow, HttpTitle binder-aware openConnection, NetworkEnvMonitor.binderFor. Cancellation watcher in Probe.connect untouched (bind happens before it is launched; socket closed on bind failure).

## Callers (grep authoritative)
Probe.connect callers: HostProber, PortScanEngine, BannerReader, GatewayMac. All use the default (no explicit binder) and pick up BoundNetwork from the coroutine context via currentBinder(), so none needed editing; with no BoundNetwork they behave as before. No extra callers beyond the brief's list. Note: GatewayMac nudges the local gateway, so it must not run inside a BoundNetwork context unless that is intended (Task 15 concern).

## Judgement call
The brief's test "binder failure surfaces..." made the JUnit method non-void (assertThrows value became the runBlocking result), giving InvalidTestClassError ("should be void"). Appended `; Unit` after the assertThrows call. No other test change.

## RED
compileDebugUnitTestKotlin FAILED: Unresolved reference 'SocketBinder'; 'bind' overrides nothing; No parameter with name 'binder' found; Unresolved reference 'BoundNetwork'.

## GREEN
ProbeBindTest: 7 tests pass. `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL (47 actionable tasks).
(An intermediate run showed the InvalidTestClassError above before the `; Unit` fix.)

## Files
New: engine/common/SocketBinder.kt, test engine/common/ProbeBindTest.kt.
Modified: engine/common/Probe.kt, engine/discovery/{Sweep,NetBios,Snmp,HttpTitle}.kt, netenv/NetworkEnvMonitor.kt.

## Self-review
No shadowing, no magic numbers added, sockets closed on bind failure, DatagramSocket in use{}. binderFor is not unit-tested (needs ConnectivityManager); covered by device QA later.

## Concerns
binderFor untested on JVM. HttpTitle still swallows non-Bind exceptions (BindFailedException from openConnection not applicable; Network.openConnection failures surface as IOException -> null), spec rule lists only Probe/Sweep/NetBios/Snmp so left as brief.

## Fix round 1
1. PortScanEngine per-port handler now rethrows BindFailedException (CancellationException was already rethrown); test: port scan under a failing BoundNetwork throws BindFailedException.
2. bindOrFail (both overloads) rethrows CancellationException untouched; test added.
Result: ProbeBindTest 9/9; full `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.
