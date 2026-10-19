# Task 9 report: mDNS discovery, resolve queue, _device-info TXT

Status: DONE. Commit b836dec on branch mvp.

## Implemented
- engine/discovery/Mdns.kt: MdnsRecord, NsdFound(handle), NsdSource, MdnsResolveQueue (serial resolves, per-resolve timeout, explicit CancellationException rethrow, dedupe by (name,type), 2 s drain grace).
- engine/discovery/MdnsDiscovery.kt: AndroidNsdSource as per brief (found NsdServiceInfo passed to resolveService; hostAddresses on API 34+, host fallback; IPv6 list kept).
- engine/discovery/DeviceInfoTxt.kt: buildQuery, parseTxtResponse (pointer compression, jump cap, never throws), query (unicast + 224.0.0.251, reply filtered to host).
- res/raw/mdns_types.txt: 30 types from spec Appendix B (verified 30 lines).
- Tests: MdnsResolveQueueTest (3), DeviceInfoTxtTest (6), verbatim from brief.

## TDD evidence
MdnsResolveQueueTest RED: `:app:compileDebugUnitTestKotlin` FAILED, Compilation error (NsdSource/MdnsResolveQueue unresolved), BUILD FAILED.
MdnsResolveQueueTest GREEN: BUILD SUCCESSFUL; testcases: "a hanging resolve is timed out and the queue continues", "a throwing resolve is skipped and the queue continues", "resolves each found service once, serially" - 0 failures.

DeviceInfoTxtTest RED (impl moved aside after first writing it, to capture failure honestly):
`e: .../DeviceInfoTxtTest.kt:33:17 Unresolved reference 'DeviceInfoTxt'.` then 34:33 Unresolved reference 'size' ... BUILD FAILED.
DeviceInfoTxtTest GREEN: tests=6 failures=0 errors=0 (query packet QU bit; empty/over-long label; plain answer; compression pointer; other owner/type ignored; truncated/garbage/pointer loop).

Full suite: BUILD SUCCESSFUL with assembleDebug; 57 tests, 0 failures (13 suites).

## Files
app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/{Mdns,MdnsDiscovery,DeviceInfoTxt}.kt; app/src/main/res/raw/mdns_types.txt; app/src/test/.../engine/discovery/{MdnsResolveQueueTest,DeviceInfoTxtTest}.kt.

## Self-review / concerns
- Deviation: in MdnsResolveQueue.run I wrapped `delay(durationMs)` in try/finally so discovery handles are closed and the queue closed if the collector cancels early (otherwise NSD listeners would leak). Behaviour otherwise identical to the brief.
- Process slip: DeviceInfoTxt.kt was first written before the RED run; RED was then captured by temporarily removing it, restoring it byte-identical.
- AndroidNsdSource is not unit tested (device, Task 22). On API 34+ the deprecated resolveService still works; NsdManager serialisation is handled by the queue. The simple `resolveService` listener is reused per call, fine.
- DeviceInfoTxt.query matches replies by source address only (no transaction-id check; id is 0 per mDNS convention).
- LF to CRLF git warnings on commit are cosmetic.
