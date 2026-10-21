# Phase 3C-2 final review (Dot)

Range 6373f3c..3427760 (12 commits, 79 files). Read: the full code diff, all five implementer reports, the spec sections 3, 4, 7.1, 8, the plan header, Global Constraints and Review Focus. Not run: Gradle, device, any network. Line numbers are in the current working tree (phase3d tip), so a few are shifted against the 3C-2 range; file and symbol are exact.

## Verdict: READY AFTER FIXES

Blocking: 0. Should fix: 5. Minors: 16.

The data-safety core is sound: the migration is two additive ALTERs (exactly pinned by `MigrationSql34Test`, `SchemaExportTest`, `4.json` carries `manualOnly` NOT NULL default 0 and nullable `route`), hidden rows are excluded from every Home read (`forNetwork`) and included only where meant (`allForNetwork`, the canary, Forget's tool check, `deleteNetworkCascade`), promotion is one SQL clause (`updateScan`), both deletes keep any row with a run or a promotion, and Remove device marks `removedWhileListening` inside `hostWriteLock` after the delete. The route rule is correct: `ManualRoute.bind` gives WIFI and ETHERNET a binder or `BindFailedException`, only OTHER runs unbound, the runner's default `routeBind` refuses a LAN route, and nothing in the app calls `bindProcessToNetwork`. The listener's lock is released on every exit path I traced (ON_STOP, switch off, reset, env null, network change, no ports, exception, cancellation) and the scan's `Locks.release` cannot touch it. No em-dash anywhere in the diff, no `run` naming breach, block bodies are used where `return` appears (labelled `return@withTransaction` follows the existing precedent in `upsertDiscovered`).

The five Should-fix items are all in the listener. None loses data. Two are about harm to other apps or to the "listener sends nothing" invariant, and one is a silent-failure path the plan itself forbids ("never stop silently").

## Blocking findings

None.

## Should fix

**S1. A reader that dies leaves the session "listening" but deaf, with the lock held and no retry.**
`engine/discovery/ListenController.kt:142-147` (`read`) and `:115-125` (the `coroutineScope`). `read` catches every `IOException` from `receive`, logs it and returns, so the reader coroutine just ends. The writer loop (`for (h in queue)`) never ends on its own, so `coroutineScope` keeps waiting, `supervise` never sees a failure, the status stays BOTH and the multicast lock stays held with nothing reading. The comment at `:139-141` says "any other failure of receive escapes"; it does not, because the only non-cancellation exception `receive` throws is `IOException`/`SocketException` (also thrown on a network-down or interface-gone event, not only on our own close). `ListenControllerTest` only exercises the IOException that comes from our own `close()`. Plan Review Focus 5 requires "never stop silently after an error".
Fix: in `read`, distinguish an expected close from a failure: after catching `IOException`, `if (!currentCoroutineContext().isActive) return else throw it`. Then a live-session socket error fails the `coroutineScope`, `supervise` logs it, shows RETRYING, backs off and reopens. Add a test where a `FakeSocket` throws `IOException` while the session is active and assert RETRYING then a second `lock.acquire`.

**S2. Binding the wildcard address on 5353 with SO_REUSEADDR can capture unicast replies meant for the system mDNS service.**
`engine/discovery/ListenSockets.kt:48` (`socket.bind(InetSocketAddress(port))`). On Linux with SO_REUSEADDR on every socket, unicast UDP to `:5353` is delivered to one socket (the most recently bound), multicast to all. mDNS responders commonly send their first query with the QU bit and get unicast answers, so while the listener is on, NsdManager/Nearby/Cast discovery in other apps can lose replies. This is a side effect on the rest of the phone, not on NetProbe. The same applies, less harmfully, to 1900. The JVM test cannot show it (it uses an ephemeral port on Windows).
Fix: bind to the group address, `socket.bind(InetSocketAddress(InetAddress.getByName(group), port))`, which on Linux and Android filters the socket to multicast for that group only (unicast to the interface address is no longer matched). Keep `reuseAddress = true`. Update `ListenSocketsTest` (loopback cannot bind a multicast address on Windows, so assert via the injected `newSocket` that the bound address is the group, or add a small `bindAddress` seam). Device check D4 below confirms Nsd still works with the switch on.

**S3. A heard packet can overwrite a stored identity with no confidence check, and can replace a stored MAC with a weaker derived one.**
`ui/home/RadarCollector.kt:43` reuses the radar rule `if (sameDevice(previousMac, currentMac)) setIdentityIfNotWeaker else setIdentity`. `data/Repositories.kt` `applyScan` takes a new MAC when `existing.mac == null || newRank >= existing.macSource`. A heard host carries only a derived MAC (rank 1 from a name or an EUI-64 link-local, rank 2 from a USN). So if the stored MAC is also rank 1 or 2 (a row never reached by the neighbour table, or a UPnP-only row), a heard MAC that differs is accepted, `sameDevice` becomes false, and `setIdentity` writes the new, thinner identity unconditionally, downgrading a port-scan identity. A scan is allowed this because a NEIGH MAC is authoritative; a heard MAC never is. Review Focus 4 says heard devices can never downgrade an identity.
Fix: give `upsertHeard` its own MAC rule (a heard MAC is taken only when `existing.mac == null`) and make the heard path always call `setIdentityIfNotWeaker` (add a `forHeard` flag on `RadarCollector`, default false, or pass the write as a function). Test: a stored rank-1 MAC and a stored confidence-80 identity, a heard packet with a different EUI-64, assert MAC and identity unchanged.

**S4. Any device in the known ranges can rename, re-type and bloat other devices' rows through an mDNS answer.**
`engine/discovery/Heard.kt:42-46` (`HeardPackets.mdns`) and `engine/discovery/HeardWriter.kt:accept`. The row written is the A record's address, not the sender's; the only check is that both are inside the known ranges. One misbehaving or hostile host can therefore set `mdnsName` and union service types and TXT for every other IP on the LAN. The union in `applyScan` (`parseTxt(existing.mdnsTxtJson) + h.mdnsTxt`) has no size cap, and 1,024 distinct repeat keys per minute are admitted, so a single device rotating service types grows `mdnsTxtJson` without bound in the Director's database. Also the phone's own announcements (loopback is on by default) can write the phone's own address as a device.
Fix: (a) accept an A-record address other than the datagram source only when the source is the env gateway or an existing visible host (the UniFi reflector case the code comment cites); (b) drop packets whose source or A address is `env.interfaceIp`; (c) cap the stored TXT union (for example 16 service types, 2 KB) in `applyScan` for heard writes. Tests for each.

**S5. The listener sends traffic and writes to the database before it has decided it may listen.**
`AppContainer.kt:133` (`resolve = { e -> networks.ensure(e, GatewayMac.lookup(e.gateway)) ... }`), called from `ListenController.kt:95-99` at every session start and every retry, before the R3 check. `GatewayMac.lookup` does `Probe.connect(gateway, 80, 300 ms)`, a TCP connect to the router on every foreground return, network change, epoch bump and backoff retry, which contradicts D11/A1 ("sends nothing except one UPnP GET") and is not recorded as a deviation. Because `ensure` also inserts a `networks` row, with R3 on a toggle left on creates a network row for every guest or public Wi-Fi the Director opens the app on, although the session then correctly refuses to listen.
Fix: split the resolve: read the gateway MAC from the neighbour table only (`NeighbourTable.dump()` + `GatewayMac.pick`, no connect), and look the row up without creating (`networks.find` by signature), so an unknown network is NOT_SCANNED without a write. If the lookup needs the nudge to be reliable, record that as a Director decision instead of shipping it silently.

## Minors

1. `engine/discovery/ListenController.kt:100-103` R3 wait has a small race: if a scan finishes between `scanned()` returning false and `scanActive.first { it }`, the session waits for the next scan although the network is now scanned. Re-evaluate on the falling edge of `scanActive` (`scanActive.drop(1).first { !it }` in a loop that rechecks `scanned`) or poll on `epoch` as well.
2. `engine/discovery/ListenGate.kt:29` and `ListenController.kt:62`: while a session is resolving its network (up to ~1 s, longer with S5's connect) a connected phone shows "Not listening: connect to Wi-Fi or Ethernet". Add a STARTING state or keep the last status until the session reports.
3. `ListenController.kt:145` `read` logs every normal teardown at error level (the IOException from our own close). With the S1 fix this goes away; otherwise log only when the context is still active.
4. `engine/discovery/ListenController.kt` repeat filter admits a key before the writer decides: a packet dropped as BUSY (scan, Undo pending) or WRONG_NETWORK still consumes its 60 s repeat window, so a device announcing every 30 minutes can be missed after a scan. Mark the key only when the outcome is WRITTEN, or clear it on a BUSY drop.
5. `ui/home/ContainerHeardSink.kt:30-35` `known()` re-reads the settings and runs `hostsForNetwork().first()` for every accepted packet, and `HeardWriter` has no overall rate cap: a device rotating USNs gets about 17 transactions a second (1,024 keys per minute). Cache `KnownRanges` for the session for a few seconds and add a token bucket on `store`.
6. `ui/home/ContainerHeardSink.kt:43-48` `store` guards (forgotten network, `byId` null) and `HomeViewModel` `allHosts` (Forget stays disabled for a hidden row) have no test. Both are Review Focus 2 and 4 behaviours. Add a JVM test with a fake container seam, or an androidTest on in-memory Room.
7. `ui/manual/ManualToolsViewModel.kt:169-172` `begin` is not single-flight: a double tap on Run starts two `begin`s; the second reports HostBusy in the snackbar although the first started. Disable Run (or ignore taps) while a start is in flight.
8. `ui/manual/ManualToolsViewModel.kt` `manualStartedHostId` (in `AppContainer`) is never cleared. After the run ends, a later Device-screen run on the same host makes this screen show Stop and progress for a run it did not start. Clear it when the run ends (observe `toolRunner.active`) or compare the run id.
9. `ui/manual/ManualToolsViewModel.kt` history and the known-device lookup use Home's `currentNetwork` id, while the start path uses `networks.ensure` (plan review G7). When they differ (a MAC-aware adoption) the run just made is missing from "Recent runs". Use the same id source for both, or return the resolved id from the starter and show it.
10. `ui/tools/ToolCopy.kt:70` `BIND_FAILED` reads "could not bind to Wi-Fi" for an Ethernet manual run. Needs a route-aware string (a new `ToolCopy` member plus a template) or neutral wording; this is the one place the Ethernet allowance (controller ruling) leaks.
11. `ui/manual/ManualStarter.kt` G15 check compares only `identityKey`; a roam from Wi-Fi to Ethernet on the same gateway and prefix keeps the stored route "WIFI" while the binder picks the Ethernet network, so the header can name the wrong transport. Compare transport too.
12. `ui/home/CopyTableTest.kt:172` still carries the template `"Run a tool\\.\\.\\."` after D16 and the catch-all `".+, .+"` accepts almost any string. Remove the dead template and tighten the history-line template (for example `".+, \\d+ (second|minute|hour|day)s? ago|.+, just now"`).
13. `ui/manual/ManualStarterTest.kt` "brute-force opens its sheet first" only tests the `firstStep` enum; the Cancel-creates-no-row guarantee (spec 8.1 item 22) is not exercised through the view model. Low risk, but the test name promises more than it checks.
14. `engine/discovery/ListenSocketsTest.kt` "binds beside a daemon" runs on the PC, on an ephemeral port, with the join stubbed; it does not demonstrate Android's 5353 sharing. The comment says so; keep it but do not count it as evidence for device item D3.
15. `engine/discovery/HeardWriter.kt:accept` calls `sink.context()` twice (`gate` then the next line); the second value is the one used for `known`/`exists`. The final `gate` re-check makes it safe, but read the context once at the top and pass it to `gate`, so the code states what the tests prove.
16. Docs: `docs/superpowers/plans/phase3c-base.txt` was not appended (reported deviation, ledger says do at Task 12), and Task 12 (on-device QA) is outstanding. Not a code issue; listed so it is not lost.

## Spec coverage

All of spec sections 3 and 4 and 7.1 are built, with these differences, none harmful: the Home overflow item is omitted per D16 (the `tools` route has no entry point until 3D); R3, G4, G12 and A2 to A5 are implemented as written in the plan; the removed-this-session set is cleared only when the listening row changes (A7). Spec 4.1 puts the progress text "beneath" the Run pill; it sits beside it, which is a fair reading of the pinned row and not worth a change. Pinned controls: the fields, tool row, Run/Stop pill and `LiveLine` are in the Scaffold `topBar`; the history `LazyColumn` is the only scroller.

## Test honesty

The tests that matter fail on the bug they name: `MigrationSql34Test` records the SQL; `ManualRouteTest` and `ToolRunnerRouteTest` pin "no binder means failure, never unbound"; `HeardWriterTest` uses a fake sink whose `store` changes `exists`, so the 32-row cap test is not a mirror; the network-change test in `ListenControllerTest` really parks the writer in a store. Gaps are S1 (no test with a live-session socket failure), S3 and S4 (no test of the real `RadarCollector` with `upsertHeard`, only a fake sink), and Minor 6. The `ListenController` suite cannot see anything that happens inside `ContainerHeardSink` and `RadarCollector`, which is where S3 lives.

## For the Director

Decisions (each is one constant, defaults shipped as the controller recommended):

- R1: the internet contact for QA item 3 (TLS to 1.1.1.1:443). Not a code flag; Task 12 needs a yes or a named alternative.
- R2: `HEARD_FETCH_DESCRIPTIONS = true` (`HeardWriter.kt`). On means the listener makes one bounded GET to a heard SSDP device on the LAN. Off means the listener never sends.
- R3: `LISTEN_SCANNED_NETWORKS_ONLY = true` (`ListenController.kt`). Listening starts on a network only after one completed scan.
- R4: `PRIVATE_TARGETS_OVER_LAN_WITHOUT_INTERNET = true` (`ManualRoute.kt`). A private target outside the known ranges goes over the LAN when the Wi-Fi has no internet and no VPN is the default.
- S5: whether a TCP connect to the router at each listening start is acceptable. Default recommendation: no.
- S4(a): whether an mDNS answer for another IP should be accepted only from the gateway or a known host. Default recommendation: yes.

Device checks (phone required, none done; Task 12 is the home for them):

- D1 (data safety, first): back up `netprobe.db*` and `shared_prefs/netprobe.xml` with MD5 on both sides, push the copy as `netprobe-migration-copy.db`, run `DirectorCopyMigration34Test` and confirm it reports 1 test, not an assumption failure, and that `netprobe.db*` size and time are unchanged; only then install in place and compare every mark, note, override, port and run.
- D2: orphan sweep: kill the app between a Run tap and the run start (or force-stop during the brute-force sheet), relaunch, confirm no hidden row is left, and that a row with a run survives.
- D3: `dumpsys wifi` shows `netprobe-listen` while NetProbe is in the foreground with the switch on, not after Home or the screen goes away; the scan's lock is released at scan end while the listen lock stays; Wi-Fi off then on releases and re-acquires with nothing written between.
- D4: after S2, with the switch on, run another app's mDNS discovery (a Chromecast or printer finder that is already installed) and confirm it still sees devices; also check what logcat says when 5353 cannot be shared (the NO_PORTS fallback).
- D5: a VPN-only target really goes out the VPN (needs a VPN app already on the phone); a public target shows "via VPN or other route"; a LAN target shows "via Wi-Fi". Record "NOT RUN" with the reason if no VPN exists.
- D6: battery measurement of 15 minutes with listening on and off (spec 8.4 item 6).
- D7: guest SSID, if one exists: nothing heard on it lands in the home network and the reverse.
- D8: confirm the phone's own address does not appear as a heard device (S4(b)).
