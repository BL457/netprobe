# Task 12 review (679338f..365c836)

Ran `:app:testDebugUnitTest --tests *CandidateRangesTest --tests *RangeRulesTest`: BUILD SUCCESSFUL. No em-dashes in the four files. No new dependencies. RED evidence in the report is credible (unresolved references).

## Spec compliance
- ✅ Names/signatures match brief and spec 4.5/7 exactly (RangeSource, CandidateRange, CandidateResult, CandidateRanges.derive/MIN_CAP/MAX_CAP, Evidence.dnsServers, RangeRules.groupFor). Tasks 15/16 can consume them.
- ✅ Order manual (list order, /20-/23 expanded via CidrParser.units), evidence by distinct-address count desc then unsigned start(), then gateway hits by address. Matches spec 4.2/4.5 and decision table.
- ✅ Cap coerced 1..16; truncated holds only valid items rejected because the cap was full; overlap drops are not reported.
- ✅ Local (swept) range and anything overlapping it excluded, including manual items; exclusion by swept range only (11.2).
- ✅ RFC1918 only: 100.64/10, 169.254, public, TEST-NET all fail Rfc1918.contains. Reuses Task 11 helpers (units, overlaps, start, Rfc1918, SubnetMath.of); no duplicated maths. Unsigned compare, so 172.x/192.168.x sort correctly.
- ✅ Containment/de-dup: overlap against accepted and truncated, earlier wins (manual /28 beats evidence /24).
- ✅ groupFor follows the spec text literally (local -> swept/manual first match -> /24 fallback; no local -> /24,false; garbage -> (ip,false)).
- NOTE: the review prompt mentions DNS answer parsing (IPv6 answers, CNAMEs, junk). There is no such parser in this task: Evidence.dnsServers only filters NetworkEnv.dnsServers. IPv6 is dropped by filterIsInstance<Inet4Address>; nothing can throw. Nothing to fault.

Plan-text conflict: none found; brief and spec agree.

## groupFor first-match question
The spec states "First match wins" with scannedRanges before manualRanges, and says a manual /28 groups its hosts under the /28. In a consistent run the scenario (swept /24 before manual /28 inside it) cannot arise from derive, because derive drops the later overlapping item and manual always precedes evidence/probe, so scannedRanges never contains both. It can arise only if the manual list is edited after the run (swept 10.0.4.0/24 from evidence, then the Director adds manual 10.0.4.16/28): hosts in the /28 stay under the /24 until the next run. Spec 7 says manual changes regroup "immediately", so strictly this is a small spec tension, but the spec's own rule text is first-match-in-list-order. Not a Task 12 defect; flag to the Director/spec owner. A most-specific-match-wins rule would satisfy both statements and cost one line; recommend only if the spec is amended.

## Findings
- Important: RangeRulesTest.kt:12-14 (`a manual slash 28 ...` test) never exercises the `manualRanges` argument for a manual-only match. The /28 is passed in BOTH scannedRanges and manualRanges, so deleting `+ manualRanges` from RangeRules.kt:85 leaves every test green (the .50 host falls back to /24 either way). Failure scenario: a manual range that was not swept (truncated by cap) would silently regroup to its /24 and no test would notice. Add a test with the /28 only in manualRanges (expect "10.0.4.16/28" to true) and one with swept /24 plus manual /28 that pins the chosen precedence.
- Minor: CandidateRangesTest has no case where an item overlaps a TRUNCATED item (CandidateRanges.kt:53 `truncated.any`). Removing that clause passes all tests; the dropped item would then wrongly appear in truncated and double count.
- Minor: all ordering tests use 10.x only; the unsigned-start ordering for 172.16/12 and 192.168/16 (and the 172.15/172.32 RFC1918 boundary) is untested at this layer.
- Minor: cap test (CandidateRangesTest.kt:134-140) checks sizes only and truncated only at cap 16; truncated at cap 4 and order of ranges under the cap are not asserted.
- Minor: evidence at the edge of a sub-/24 swept range (scanned 10.0.3.0/25, evidence 10.0.3.200) is excluded because its /24 overlaps scanned, so the other half is never swept. Spec-conformant (overlap rule) but worth a spec note.
- Minor: `Evidence.dnsServers` does not de-duplicate; harmless since derive calls distinct().

## Task quality
Approved. Code is small, pure, readable, reuses Task 11 helpers; one test-gap Important finding above should be fixed in a follow-up commit (tests only, no production change).

## Re-review, round 1 (365c836..6f4d06a)
Only RangeRulesTest.kt changed (+6 lines); no production file touched. RangeRulesTest run: BUILD SUCCESSFUL.
- Manual-only test: swept list is [local] only, /28 is only in manualRanges, host .20 expects "10.0.4.16/28" to true. Without `+ manualRanges` the result would be the /24 fallback, so it genuinely pins the previous Important finding.
- Precedence test: swept /24 plus manual /28 containing .20 expects the /24, pinning spec first-match-in-list-order (scanned before manual). Exercises what it claims.
Verdict: Approved. Important finding resolved. Earlier Minor items remain open (optional).
