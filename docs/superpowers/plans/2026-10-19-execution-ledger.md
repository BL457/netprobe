# SDD ledger - plan: docs/superpowers/plans/2026-10-19-netprobe-mvp.md

Spec: docs/superpowers/specs/2026-10-19-netprobe-mvp-design.md (approved by Director 2026-10-19, rigour: standard)
Branch: mvp (from master at dd10ecf). Plan BASE before Task 1: dd10ecfed4dd5025f2d1f1bd7d62dd988467f0a8
Roles: implementer = Ada (sonnet unless noted), task reviewer = Bugsy (sonnet), device gates = Bugsy, final review = Dot (opus)

Ruling: work on branch `mvp` in place rather than a git worktree - the repo is brand new (docs only), the plan's commands assume K:/claude/netprobe, and a branch gives the isolation the skill wants - cost if wrong: none beyond a branch switch; nothing else lives in this repo.
Ruling: Gruff finding 12 resolved as option A (direct mDNS TXT query for _device-info in Task 9) - Director delegated the call; keeps the identification goal for Apple devices - cost if wrong: ~80 lines of unused code if the query never answers on his network.
Ruling: Gruff finding 20 spec change applied by controller (EMFILE halving deferred to roadmap) - Ada could not edit the spec - cost if wrong: none.

## Pre-flight conflict scan (after Gruff review + Ada fixes, commit dd10ecf)

| Producer task | Consumer task(s) | Interface | Finding |
|---|---|---|---|
| 2 NeighbourTable.dump(): Result<List<NeighbourEntry>> | 15 neighbours() | same signature | clean |
| 3 SubnetMath.sweepRange, Subnet.hosts/contains/size | 15 | same names | clean |
| 4 NetworkEnv | 15, 17 NetworkSignature.of, 21 | fields ip/prefix/gateway/ssid/bssid | clean |
| 5 PortTable.nameFor, ScanProfile, ProfileParser | 8, 21 | same | clean |
| 6 Probe.connect -> Outcome.Connected(rttMs, socket: Socket?) | 7, 8 | nullable socket (Gruff 16 fixed) | clean |
| 6 PortScanEngine.scan/progress, ScanOptions | 21 | same | clean |
| 7 open class Sweep / open fun run | 15 fakes | open declared in 7 (Gruff 11) | clean |
| 8 ServiceInfo, Evidence, ServiceIdentifier.identify (IO dispatcher), MAX_PARALLEL_IDENTIFIES | 17, 18, 19, 21 | same | clean |
| 9 MdnsRecord, NsdSource(NsdFound), MdnsResolveQueue, DeviceInfoTxt | 15 | same | clean |
| 10 SsdpResponse, UpnpDevice, UpnpFetcher, MacExtract | 15 | same | clean |
| 11 NetBiosInfo, 12 SnmpInfo, 13 HttpPeek, 14 ReverseDns/Eui64 | 15 | same | clean |
| 15 DiscoveredHost (+macSource), DiscoveryEngine, DiscoveryOptions/Status | 16, 17, 21 | superset fields | clean |
| 16 DeviceIdentity, DeviceType, HostEvidence, Rule | 17, 19, 21 | same | clean |
| 17 Entities, repos incl. byIdOnce/mdnsTypesFor/toDiscovered, PortDao.counts, AppSettings.scanOptions() | 20, 21 | defined in 17 (Gruff 16) | clean |
| 18 DeviceAction, ActionProvider, AndroidActionRunner | 21 | same | clean |
| 19 VulnLookup (two limiters), VulnQuery.ByCpe fallback, VulnResult.Ok.queryText | 20, 21 | same | clean |
| 20 AppContainer members | 21 | wiring before UI (Gruff 10) | clean |
| Self-consistency per task | 1..22 | tests vs code vs files | Gruff review checked each; Ada applied fixes 1-23 | clean |

Global-constraint check: no task downloads anything beyond Maven deps; Task 1 bans jvmToolchain; targetSdk 30 everywhere; no em-dashes in plan (grep = 0).
Plan-mandated items the rubric would flag: none found (SNMP encoder moved to test support; "every rule fires" test is now differential).

## Progress
Task 1: dispatched to Ada (sonnet), BASE dd10ecf
Task 1: implementer DONE (commit e012702); review dispatched to Bugsy (sonnet)
Task 1: review clean (Bugsy). Controller checks: local.properties untracked OK; commit body no attribution OK; gradlew CRLF see below
Task 1: minor (deferred): gradlew may carry CRLF endings, add .gitattributes (gradlew eol=lf, *.bat eol=crlf)
Task 1: minor (deferred): lint abortOnError=false is broader than needed; revisit once lint baseline known
Task 1: complete (commits dd10ecf..e012702, review clean)
Ruling: Task 2 device spike (Step 8) runs only when the Pixel is attached; code and build steps proceed regardless and later tasks are not blocked on the gate, since the engine treats the neighbour source as optional (Result) - gate must pass or be recorded as failed before Task 22 completes - cost if wrong: MAC column empty, nothing else.
Task 2: dispatched to Ada (sonnet), BASE e012702
Task 1: note - committed gradlew blob has 0 CRLF bytes; the deferred .gitattributes minor is moot
Task 2: phone not attached at dispatch (adb devices = 0); device gate expected to be deferred
Task 2: implementer DONE_WITH_CONCERNS (commit cf8bdbc): gate not run (phone absent, per ruling); static C buffer races on concurrent dump() - noted as deferred minor, dump is called once per run; review dispatched to Bugsy (sonnet)
Task 2: minor (deferred): neigh.c static 2048x64 line buffer is not re-entrant; acceptable while dump() is called once per discovery run
Task 2: review (Bugsy): 1 Important plan-mandated (static C line buffer not re-entrant), 11 minors
Ruling: static buffer finding accepted despite plan text mandating that code - fix is @Synchronized on NeighbourTable.dump() (one call per discovery run today, but the never-throws contract should not depend on callers) plus DeleteLocalRef per string in the JNI loop (minor 1, same lines) - cost if wrong: a few lines of code
Task 2: controller resolved warnings: INTERNET permission is in the Task 1 manifest; arm64-v8a-only ABI is intended; minify is off so no R8 keep rules needed
Task 2: minor (deferred): unchecked JNI allocation results; silent 2048 entry cap; NLM_F_DUMP_INTR unhandled; ipv4Re accepts octets > 255; weak malformed-line assertion; no NOARP/PERMANENT state tests; spike passes on any pre-existing entry (compare before/after counts when reading the gate)
Task 2: fix round 1/5 dispatched (resume Ada)
Task 2: fix round 1 implemented (commit 9fc413d); scoped re-review dispatched (Bugsy, sonnet)
Task 2: fix round 1/5 (2 addressed + evidence gap satisfied, 0 open; commits cf8bdbc..9fc413d)
Task 2: minor (deferred): FindClass local ref not deleted (one per call); no NULL checks after NewStringUTF/NewObjectArray
Task 2: complete (commits e012702..9fc413d, review clean) - DEVICE GATE STILL PENDING (Step 8), run by Bugsy when the Pixel is attached
Task 3: dispatched to Ada (sonnet), BASE 9fc413d
Task 3: implementer DONE (commit b0682f1); concern noted: Subnet /0 size overflow unreachable via sweepRange; review dispatched to Bugsy (sonnet)
Task 3: review clean (Bugsy). Minor (deferred): Subnet.size wrong at /0 and constructor unvalidated (unreachable via sweepRange); maxPrefix floor - Task 17 AppSettings coerces to 20..24, carry pointer to Task 17 dispatch; test gaps on size/high-network/boundary 21-22
Task 3: complete (commits 9fc413d..b0682f1, review clean)
Task 4: dispatched to Ada (sonnet), BASE b0682f1
Task 4: implementer DONE (commit b7b4901); review dispatched to Bugsy (sonnet)
Task 4: review (Bugsy): Approved with 1 Important plan-mandated (Ethernet env can carry Wi-Fi SSID/BSSID), 7 minors
Ruling: fix the Important now via the mapper (null ssid/bssid when transport is ETHERNET, plus test) rather than nc.transportInfo - smallest change, spec says SSID is a Wi-Fi property - cost if wrong: none, Ethernet rarely used
Task 4: minor (deferred): untested quote stripping/loopback skip/blank ids; publish() unguarded against exceptions from current(); arbitrary candidate order with both Wi-Fi and Ethernet up; link-local 169.254 accepted; onLost race
Task 4: fix round 1/5 dispatched (resume Ada)
Task 4: fix round 1 implemented (commit 2ae5766); scoped re-review dispatched (Bugsy, sonnet)
Task 4: fix round 1/5 (2 addressed, 0 open; commits b7b4901..2ae5766)
Task 4: complete (commits b0682f1..2ae5766, review clean)
Task 5: dispatched to Ada (sonnet), BASE 2ae5766
Task 5: implementer DONE (commit 2957ce4, ports.json 135 entries); review dispatched to Bugsy (sonnet)
Task 5: review clean (Bugsy). Minor (deferred): overflow digit strings give "Not a number" message; Full profile allocates per access (consider lazy when scanner reads it); reject test does not assert message specificity; bundled-table test does not enforce Appendix C ports or duplicates; loose names for 1025-1029, 1110, 2001, 3986, 6646
Task 5: complete (commits 2ae5766..2957ce4, review clean)
Task 6: dispatched to Ada (sonnet), BASE 2957ce4
Task 6: implementer DONE_WITH_CONCERNS (commit 0ffc686). Deviation: cancellation watcher launched with Dispatchers.Unconfined + UNDISPATCHED instead of inheriting the saturated scan dispatcher (brief code failed its own cancel test at 1716 ms; now ~20 ms)
Ruling: accept the deviation - the brief code was defective; the spec requirement (Stop closes sockets promptly) and the brief test are both met - cost if wrong: none observed, Unconfined only runs the socket close
Task 6: minor (deferred): ScanProgress.errors stays 0 because Probe maps unexpected exceptions to Timeout; unused Channel import
Task 6: review dispatched to Bugsy (sonnet)
Task 6: review clean (Bugsy). Minor (deferred): keepOpen socket can leak when cancellation lands after finished.set(true) - CARRY INTO TASK 8 dispatch as a small Probe.kt pre-step (close socket on CancellationException, explicit catch ahead of catch Exception); cancellation and unroutable tests depend on 192.0.2.1 timing out; progressive-results test weak (41 < buffer 64); errors counter dead; progress updated after send; unused imports; Unconfined rationale missing from KDoc
Task 6: complete (commits 2957ce4..0ffc686, review clean)
Task 7: dispatched to Ada (sonnet), BASE 0ffc686
Task 7: implementer DONE_WITH_CONCERNS (commit 0c3d3cf). Deviation: test fixture reordered (open port first) because the brief listed a refused port first, which the stop-at-first-answer rule never probes past
Ruling: accept - the implementation follows the spec rule; the brief test contradicted it - cost if wrong: none
Task 7: minor (deferred): Sweep shares one progress StateFlow per instance; concurrent run() calls would interleave progress
Task 7: review dispatched to Bugsy (sonnet)
Task 7: review clean (Bugsy). Minor (deferred): lost coverage that Refused stops before a later open port; stop-early timing test vacuous; Unreachable branch and Timeout-then-Connected untested; no exception-path sweep test; hosts.toList() materialises the range
Task 7: complete (commits 0ffc686..0c3d3cf, review clean)
Task 8: dispatched to Ada (sonnet), BASE 0c3d3cf, carrying Task 6 keepOpen-leak minor as pre-step
Task 8: implementer DONE (commits 3cbeebf pre-step, a969b5c task); review dispatched to Bugsy (sonnet)
Task 8: review (Bugsy): Approved with 2 Important plan-mandated (TLS branch returns HTTPS even when handshake fails, overriding table name; TlsPeek SSLSocket leaks when connect fails before use{}), minors
Ruling: fix both now (fix round 1) plus the CancellationException rethrow minor in BannerReader/TlsPeek (same files) - cheap, and Task 21 UI depends on TLS-port names - cost if wrong: none
Task 8: minor (deferred): cancellation not prompt during banner read (bounded 1 s); copyOf per loop iteration and no CRLFCRLF early exit for HTTP; parser edge cases (SSH-1.99-Cisco, substring product match, 220abc, case-sensitive realm, no SNI, CN with escaped commas); silent-port tests see EOF not timeout; precedence beyond mDNS untested; IPv6 Host header scope suffix
Task 8: fix round 1/5 dispatched (resume Ada)
Task 8: fix round 1 implemented (commit 08b72ba); scoped re-review dispatched (Bugsy, sonnet)
Task 8: fix round 1/5 (3 addressed, 0 open; commits a969b5c..08b72ba)
Task 8: minor (deferred): successful TLS cert read names the service HTTPS even where the table is more specific (5001 Synology HTTPS)
Task 8: complete (commits 0c3d3cf..08b72ba, review clean)
Task 9: dispatched to Ada (sonnet), BASE 08b72ba
Task 9: implementer DONE (commit b836dec). Deviation: try/finally around delay(durationMs) in MdnsResolveQueue.run so NSD handles and queue close on early cancel
Ruling: accept - matches spec 8 cleanup intent; no behaviour change on the normal path - cost if wrong: none
Task 9: process note - implementer wrote DeviceInfoTxt before capturing RED, then staged the RED by moving the file aside; RED evidence is genuine but TDD order was not strict
Task 9: review dispatched to Bugsy (sonnet)
Director plugged the Pixel in (Android 17, SDK 37). Fing 12.13.4 on the phone has targetSdk 36 (confirms Fing cannot use netlink). Task 2 device gate dispatched to Bugsy (sonnet). Task 10 dispatch held until Bugsy commits the QA note to avoid concurrent commits.
Task 9: review clean (Bugsy). Minor (deferred): early-cancel path and 2 s drain grace untested; garbage TXT test never reaches record parsing; DeviceInfoTxt.query untested (fixed port 5353) and soTimeout overshoot; no stopServiceResolution/invokeOnCancellation on timed-out resolve - CHECK ON DEVICE IN TASK 22; multicast send unbound to Wi-Fi interface; discover failures silent; partial discover-failure handle leak
Task 9: complete (commits 08b72ba..b836dec, review clean)
Task 2: DEVICE GATE PASS (commit e588f99): Pixel 7 Pro Android 17 SDK 37, subnet 10.0.3.0/24 on wlan1, 6 MACs before sweep, 11 after, no avc denied, no errno 13. Netlink neighbour source stays in the MVP.
Note: phone Wi-Fi interface is wlan1 (not wlan0); subnet 10.0.3.0/24; gateway 10.0.3.1 e0:63:da:26:f1:37. Carry to Task 22.
Task 10: dispatched to Ada (sonnet), BASE e588f99
Task 10: implementer DONE (commit b31ef04). Deviation: UpnpFetcher.fetch expression body with return null did not compile; changed to block body, same behaviour (accepted, compile fix)
Task 10: review dispatched to Bugsy (sonnet)
Task 10: review (Bugsy): Needs fixes - 4 Important (CancellationException swallowed in fetch; XXE hardening silent, no resolveEntity override, untested; UpnpFetcher host check/size cap/no-redirect untested; no overall deadline on fetch loop)
Ruling: fix all four now; the SSDP dedupe/socket-close test is parked as deferred (needs a multicast socket; exercised on device in Task 22) - cost if wrong: dedupe bug shows as duplicate rows on device, caught by Task 22
Task 10: minor (deferred): root-device test does not prove depth logic; TV test weak; empty XML fields yield "" not null; UUID regex unanchored and first-match only; no scheme check or disconnect in fetcher; broadcast=true unnecessary; SSDP flow waits for sender child; MulticastLock responsibility is Task 20
Task 10: fix round 1/5 dispatched (resume Ada)
Task 10: fix round 1 implemented (commit 726f38e); scoped re-review dispatched (Bugsy, sonnet)
Task 10: fix round 1/5 (2 addressed, 2 open - entity test vacuous; host-mismatch, size-cap and no-redirect tests vacuous; commits b31ef04..726f38e)
Task 10: fix round 2/5 dispatched (resume Ada)
Task 10: fix round 2 implemented (commit 82580c6); scoped re-review dispatched (Bugsy, sonnet)
Task 10: fix round 2/5 (4 addressed, 0 open; commits 726f38e..82580c6)
Task 10: minor (deferred): disallow-doctype-decl feature may be unsupported on Android SAX (silently skipped; resolveEntity + entity features remain) - CHECK ON DEVICE IN TASK 22; HttpURLConnection never disconnected; deadline only checked between reads (worst case ~2x timeout)
Task 10: complete (commits e588f99..82580c6, review clean)
Task 11: dispatched to Ada (sonnet), BASE 82580c6
Task 11: implementer DONE (commit 06bad8e); deviations: block body for compile, test regex whitespace fix, cancellation pattern (all accepted); review dispatched to Bugsy (sonnet)
Task 11: review clean (Bugsy). Minor (deferred): 1024-byte receive buffer vs 64 names (~1209 bytes); unconnected socket accepts any source and txId unchecked (s.connect one-liner); garbage test never reaches name walk; no __MSBROWSE__ or group-first test
Task 11: complete (commits 82580c6..06bad8e, review clean)
Task 12: dispatched to Ada (sonnet), BASE 06bad8e
Task 12: implementer DONE (commit c6b0ce9); review dispatched to Bugsy (sonnet)
Ruling: Director asked to see builds on the phone as they land - controller installs the debug APK after each completed task from now on (cheap bookkeeping, no code change); UI only exists from Task 21 - cost if wrong: none
Installed current debug APK (Task 12 build, shell UI) on the Pixel.
Task 12: review clean (Bugsy). Minor (deferred): tag()/length() rely on the broad catch rather than require; int() accepts n=0 or >4; varbind inner length ignored; request id/version unvalidated and socket unconnected; 0x81 length form untested; Ber.len truncates >= 0x10000
Task 12: complete (commits 06bad8e..c6b0ce9, review clean)
Task 13: dispatched to Ada (sonnet), BASE c6b0ce9
Task 13: implementer DONE (commit b19a666); review dispatched to Bugsy (sonnet)
Task 13: review (Bugsy): Needs fixes - 4 Important (entity decode order double-decodes, plan-mandated; mid-body IOException discards received headers; cap test vacuous; no negative redirect tests)
Ruling: fix all four now, plus decode body as UTF-8 (plan said ISO_8859_1, which garbles non-ASCII titles the Director will see on real kit) - cost if wrong: none
Task 13: minor (deferred): whitespace class differs from ICU \s on Android (NBSP); title regex matches <titlefoo>; non-http redirect scheme relies on ClassCastException; realm with comma truncated; test server runCatching kills accept loop after one failure
Task 13: fix round 1/5 dispatched (resume Ada)
Task 13: fix round 1 implemented (commit f019daf); scoped re-review dispatched (Bugsy, sonnet)
Task 13: fix round 1/5 (5 addressed, 0 open; commits b19a666..f019daf)
Task 13: minor (deferred): Content-Type charset not honoured (Latin-1 pages show U+FFFD); numeric entities other than &#39; not decoded; blocking read bounded only by timeout
Task 13: complete (commits c6b0ce9..f019daf, review clean)
Task 14: dispatched to Ada (sonnet), BASE f019daf
Task 14: implementer DONE (commit 1fdee66). Deviation: ReverseDns runs the blocking resolver on a detached SupervisorJob+IO scope and times out only the await, because canonicalHostName ignores interrupts (brief test took 4.6 s vs 2.5 s bound) - provisionally accepted pending review; Task 15 bounds enrichment to 32 in flight
Task 14: review dispatched to Bugsy (sonnet)
Task 14: review (Bugsy): Needs fixes - 2 Important (detached lookup not cancelled on caller cancel; detached scope unbounded on shared IO pool), minors (literal guard bypass, weak resolver-dependent test, test file placement)
Ruling: accept the detached-scope design (brief design could not honour its own timeout) with the two fixes, plus injectable resolver for deterministic tests and stricter Eui64 literal guard - cost if wrong: none
Task 14: fix round 1/5 dispatched (resume Ada)
Task 14: fix round 1 implemented (commit a52866b); scoped re-review dispatched (Bugsy, sonnet)
Task 14: fix round 1/5 (4 addressed, 0 open; commits 1fdee66..a52866b)
Task 14: minor (deferred): cancellation test does not observe the detached job being cancelled; TEST-NET resolver test is network-dependent and misnamed
Task 14: complete (commits f019daf..a52866b, review clean)
Task 15: dispatched to Ada (sonnet), BASE a52866b
Task 15: implementer DONE (commit cd2a152); additions: softly{} helper for per-host enrichment, neighbours() failure wrapped; review dispatched to Bugsy (sonnet)
Task 15: review (Bugsy): Needs fixes - 4 Important (final progress update can be dropped; mDNS/SSDP strategy failure kills the whole discovery; added softly/neighbour error handling untested; disabled-strategy test covers only snmp)
Ruling: fix all four plus deterministic web-port choice (tiny) - cost if wrong: none
Task 15: minor (deferred): mdnsTxt map union overwrites per service type; SSDP fetch sequential inside collect; softly swallows silently (no log/counter); neighbour-only hosts listed as found (intended); device-info cap/semaphore bound/UDN fallback/TLS flag untested
Task 15: fix round 1/5 dispatched (resume Ada)
Task 15: fix round 1 implemented (commit 6e3e2a4; Sweep.progress made open val for fakes); scoped re-review dispatched (Bugsy, sonnet)
Task 15: fix round 1/5 (5 addressed, 0 open; commits cd2a152..6e3e2a4)
Task 15: minor (deferred): progress-flush test is a weak guard (make deterministic with a never-delivering StateFlow); sweep-collect isolation path untested; strategy failures invisible in DiscoveryStatus (isolated swallows silently)
Task 15: complete (commits a52866b..6e3e2a4, review clean)
Task 16: dispatched to Ada (sonnet), BASE 6e3e2a4
Task 16: implementer DONE (commit afd0412, 58 rules). Deviation: group1Of helper guards m.groups[1] on regexes without a capture group (brief code threw) - accepted, bug fix
Task 16: review dispatched to Bugsy (sonnet)
Task 16: review (Bugsy): Needs fixes - 8 Important, all plan-mandated data/test defects (snmp-printers over-fires; host-android unbounded; netbios-present outranks weight-50 hints; printer rules beat gateway/igd on ties; unanchored Plex/ASUS/Axis; blank UPnP strings accepted at weight 100; two precedence tests vacuous; no negative tests)
Ruling: accept all eight against the plan text - the spec goal (correct identification) is the authority; rule weights and patterns are data, not architecture - cost if wrong: a few rules need re-tuning on device
Task 16: minor (deferred): differential test proves firing not field change; regexes recompiled per host; identify() dense; vendor/model unbounded length; load() fails silently; Hikvision-Webs sample may be invented
Task 16: fix round 1/5 dispatched (resume Ada)
Task 16: fix round 1 implemented (commit 4abb37f, 59 rules); scoped re-review dispatched (Bugsy, sonnet)
Task 16: fix round 1/5 (8 addressed, 0 open; commits afd0412..4abb37f)
Task 16: minor (deferred): gateway (95) now beats SERVER rules (90) for a router that is also Pi-hole/HA; netbios-only host has os Windows with type UNKNOWN; canonicalVendor not applied to UPnP manufacturer; Pixel-Buds/Galaxy-Buds match host-android; no positive ASUS realm test
Task 16: complete (commits 6e3e2a4..4abb37f, review clean)
Task 17: dispatched to Ada (sonnet), BASE 4abb37f; phone attached for DaoTest
Task 17: implementer DONE (commit 278df9e); DaoTest 4/4 on the Pixel; schema exported; review dispatched to Bugsy (sonnet)
Task 17: review (Bugsy): Approved with 4 Important plan-mandated (read-modify-write race can overwrite annotations; check-then-insert races hit unique indices unhandled; uniqueIndexPerNetwork test proves nothing; annotations test sequential only)
Ruling: fix now - spec 5.6 (annotations never overwritten) is binding and the constraint races would crash the app on concurrent scans - plus restore macSource in toDiscovered - cost if wrong: none
Task 17: minor (deferred): toDiscovered lossy for instance names/workgroup; no tests for ensure/setIdentity/VulnCache TTL/Settings clamping; ensure returns stale ssid; Settings.update non-atomic
Task 17: fix round 1/5 dispatched (resume Ada)
Task 17: fix round 1 implemented (commit 321e9d1; DaoTest 6/6 on device); scoped re-review dispatched (Bugsy, sonnet)
Task 17: fix round 1/5 (4 addressed + code for 1; open: race test weak; commits 278df9e..321e9d1)
Task 17: fix round 2/5 dispatched (resume Ada)
Task 17: fix round 2 implemented (commit 66503d6; race test shown RED against whole-row update: expected n150 got n146); scoped re-review dispatched (Bugsy, sonnet)
Task 17: fix round 2/5 (2 addressed, 0 open; commits 321e9d1..66503d6)
Task 17: minor (deferred): scanner-field persistence assertion dropped from the race test; samples > 0 is a weak floor
Task 17: complete (commits 4abb37f..66503d6, review clean)
Task 18: dispatched to Ada (sonnet), BASE 66503d6
Task 18: implementer DONE (commit 872eb9d); review dispatched to Bugsy (sonnet)
Task 18: review clean (Bugsy). Controller resolved warning: manifest <queries> declares the 8 schemes (Task 1, verified by grep). Minor (deferred): port fallback applies to any unrecognised name not only Unknown; label/ordering/case-insensitivity tests thin; primary builds whole list; IPv6 unbracketed
Task 18: complete (commits 66503d6..872eb9d, review clean)
Task 19: dispatched to Ada (sonnet), BASE 872eb9d
Task 19: implementer DONE_WITH_CONCERNS (commit 125f95c): cache key is "<QueryClass>:<text>" (test seed fixed; Task 20 adapter treats key as opaque); illegal test name fixed; cancellation rethrow + disconnect added; review dispatched to Bugsy (sonnet)
Task 19: review (Bugsy): Needs fixes - 4 Important (privacy/key rules untested; non-200 and stale-with-failure paths untested; parser V30/UNKNOWN fallbacks untested; score and severity can come from different metrics), minors
Ruling: fix the four plus explicit cache key discriminator, CPE version sanitising, and instanceFollowRedirects=false (apiKey header must not follow a redirect) - cost if wrong: none
Task 19: controller resolved privacy warning: ServiceInfo.product/version come from banner product fields and DeviceIdentity vendor/model from rule constants, UPnP manufacturer/model or mDNS model TXT, never hostnames
Task 19: minor (deferred): non-IO exceptions labelled Offline; cache calls unguarded; wall-clock limiter; 403 message when a key is set; no body size cap; missing totalResults triggers retry; thin Cpe tests
Task 19: fix round 1/5 dispatched (resume Ada)
Task 19: fix round 1 implemented (commit 9d17da8); scoped re-review dispatched (Bugsy, sonnet)
Task 19: fix round 1/5 (7 addressed, 0 open; commits 125f95c..9d17da8)
Task 19: minor (deferred): TTL boundary proven against the test fake only (real VulnCacheRepository.get untested); no test that cpe:/kw: keys do not collide
Task 19: complete (commits 872eb9d..9d17da8, review clean)
Task 20: dispatched to Ada (sonnet), BASE 9d17da8
Task 20: implementer DONE (commit 5208ae3); installed and launched on the Pixel, no crash (container lazy, exercised by Task 21); review dispatched to Bugsy (sonnet)
Task 20: review clean (Bugsy). Minor (deferred): Locks construction outside runCatching; partial lock acquisition silent; RawResourcesTest uses Kotlin assert(); AppContainer wiring has no JVM test (device launch only)
Task 20: complete (commits 9d17da8..5208ae3, review clean)
Task 21: dispatched to Ada (opus - UI needs design judgement), BASE 5208ae3
Task 21: implementer DONE_WITH_CONCERNS (commit 8360488): installed + launches; screenshot black (phone locked); 3 Compose device tests crash in espresso-core 3.6.1 on Android 17 (InputManager.getInstance removed); HomeViewModelTest switched advanceUntilIdle -> runCurrent (brief tests could not pass); 2 extra tests for scan-controller race/crash bugs Ada found
Ruling: bumping espresso-core (test-only, already declared) to 3.7.x is within the Maven-dependency allowance; proceed in the fix round and tell the Director - cost if wrong: one test dependency version
Task 21: review dispatched to Bugsy (opus, large UI diff)
Task 21: CONTROLLER DEVICE CHECK (phone unlocked, app had been uninstalled by the instrumented run; reinstalled 8360488): Home renders with pinned header; Scan found 12 devices in ~13 s on 10.0.3.0/24; gateway=Ubiquiti, CHNAS=Synology DS220+, pi.hole=Pi-hole identified; locks acquired/released; nlmsg_getneigh granted; no crash. Screenshots in workspace shot-home.png, shot-scan-6s.png, shot-scan-22s.png
Task 21: QA finding for Task 22: Google Cast/Nest/Home Mini rows show "Unknown device" despite mDNS discovery - googlecast md= TXT not reaching the identifier (check NsdManager TXT attributes on resolve, mdnsTxtJson storage, toDiscovered rebuild of mdnsServiceTypes)
Task 21: minor (deferred): row secondary line repeats the IP when the display name is the IP
Task 21: review (Bugsy, opus): Needs fixes - 1 Important (Stop-then-Scan or network-change-then-Scan: old discovery job releases non-refcounted locks under the new scan; runs overlap), minors (Detail header height with keyboard; tight fits; device test text ambiguity; message masking; note save on Back may be cancelled; blank rename; vuln URL fallback copies IP; stale dimming; no cancel for quick-scan-all; JVM coverage gaps)
Ruling: fix round 1 = Important + Espresso 3.7.x bump + device tests + screenshots + Google mDNS identification diagnosis + cheap minors (testTag, note save NonCancellable, blank rename, vuln URL copy, IP-repeat line) - cost if wrong: none
Task 21: fix round 1/5 dispatched (resume Ada, opus)
Task 21: fix round 1 implemented (commits 63c1759, 0382dff, b38bbfc): join-before-start race fix; espresso 3.7.0; device tests 2 pass 1 skipped-by-design; Google rows identified (NSD types carry a leading dot; normalised at resolve and read-back); status labels shrink at font scale 1.15; screenshots committed; scoped re-review dispatched (Bugsy, sonnet)
Task 21: fix round 1/5 (6 addressed, 0 open; commits 8360488..b38bbfc)
Task 21: minor (deferred): mixed font sizes in status row; vendor dedupe is a prefix test; join delays next scan while a slow cancel finishes; Hue Bridge seen at 10.0.4.20 outside the /24 passed through by mDNS (check in QA); NAS icon coarse; Wi-Fi name not refreshed until next link change after permission grant
Task 21: complete (commits 5208ae3..b38bbfc, review clean)
Task 22: dispatched to Bugsy (sonnet), BASE b38bbfc; step 7 (other Wi-Fi) needs the Director
Task 22: Bugsy QA recorded (commit bbad9cf): rows 1,1b,4,6,9,10,11 PASS; 2,3,5,8 PARTIAL (no Windows PC or unhandled-scheme host on the LAN); 7 NEEDS DIRECTOR. Defects D1-D7.
Ruling: QA fix round before final review = D1 (label off-subnet hosts, no NEIGH MAC claim; keeping them is the Director stated wish), D2 (adopt existing idless network record on first location grant), D3 (label this phone), D4 (Home hint for Wi-Fi name), D5 (upnpManufacturer rule -> NAS). D6 (router model text, Pi-hole double row - likely two IPs) and D7 (Synology iSCSI ports, info) deferred to final review notes - cost if wrong: small UI/data polish
Task 22: fix dispatched to Ada (sonnet), BASE bbad9cf
Task 22: QA fixes D1-D5 implemented and device-verified (commit e9e6641; DaoTest 7/7; screenshots qa-fix-home*.png)
Ruling: the QA-fix diff is re-reviewed inside the final whole-branch review rather than a separate scoped round (the final review covers the same commits; reviewer told to verify D1-D5 first) - cost if wrong: one extra review seat if the final reviewer misses something
Task 22: complete (commits b38bbfc..e9e6641, QA recorded; row 7 and router-list confirmation pending the Director)
FINAL REVIEW: dispatched to Dot (opus) over dd10ecf..e9e6641
FINAL REVIEW (Dot): With fixes. C1 network signature keyed on BSSID is unstable (roaming/band change/location toggle splits networks) - spec defect; I1 allowBackup unset (DB + NVD key backed up to Google); I2 Detail scan and quick-scan-all keep probing after a Wi-Fi switch and quick-scan-all has no Stop; I3 identity rebuilt without stored ports/services on rediscovery; I4 ssh-windows rule can never fire (banner parse) and bad CPE; I5 DNS servers not shown; D3 stores "This phone" as vendor (leaks to NVD query); D4 hint not tappable; 19 minors in docs/superpowers/plans/2026-10-19-final-review.md
Ruling: C1 resolved as Dot recommends - signature = sha256((gatewayMac ?: "") + gatewayIp + "/" + prefix), gateway MAC from the neighbour table after a quick probe of the gateway, SSID display-only; existing rows with the same gateway/prefix adopted - cost if wrong: two networks that share gateway IP, prefix and gateway MAC would merge, which cannot happen; networks whose gateway MAC is unavailable key on gateway/prefix only as before
Ruling: fix wave = C1 + I1..I5 + D3/D4 refinements + two cheap robustness minors (Proxy.NO_PROXY for LAN fetches, DOCTYPE pre-check against billion laughs OOM); README and the rest deferred to finishing - cost if wrong: one more review seat
FINAL FIX WAVE: dispatched to Ada (opus), BASE e9e6641
FINAL FIX WAVE: implemented (commits 5366746, 5ef5312, 635395c, 8eff65c; 216 JVM tests; DaoTest 10/10 on device; CHNAS annotations survived signature migration and Location off). Ada guard: no adoption when the stored gateway MAC differs (accepted)
FINAL RE-REVIEW: dispatched (Bugsy, opus) over e9e6641..8eff65c
FINAL RE-REVIEW (Bugsy, opus): all findings addressed, no new Critical/Important. Residuals adjudicated:
FINAL: parked - N1 null gateway MAC re-keys a row and could merge two same-addressed sites - Ruling: real but rare (needs netlink denied or gateway unresolved in 300 ms); first follow-up before the APK goes to anyone but the Director - cost if wrong: a stranded inventory at a second site
FINAL: parked - N2 rows already split by the old BSSID rule are not adopted - Ruling: the Director DB has one row; follow-up - cost if wrong: stranded marks for other users only
FINAL: parked - N3 same-addressing site switch without an intermediate null does not stop scans - Ruling: cheap follow-up (cancel when networkId changes); edge case needing make-before-break - cost if wrong: hosts written to the wrong network row in that edge case
FINAL: parked - N4 gateway SYN on every env emission (app open) - Ruling: follow-up (dump first, nudge only if missing) - cost if wrong: one SYN to the gateway per app open
FINAL: parked - N5 quick-scan finish message after Stop; Windows keyword fallback string; commits 5366746/5ef5312/635395c do not build alone - Ruling: cosmetic/process, defer
FINAL: noted - O2 router replacement at the same site creates a fresh network row (consequence of keying on gateway MAC) - tell the Director; O3 legacy "This phone" vendor rows; O6 QA step 7 still needs the Director
FINAL: clean at 8eff65c. Proceeding to finishing-a-development-branch.
