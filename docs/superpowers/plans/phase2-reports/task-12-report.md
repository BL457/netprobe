# Task 12 report
Status: DONE. Implemented CandidateRanges (derive, RangeSource, CandidateRange, CandidateResult, Evidence.dnsServers) and RangeRules.groupFor verbatim from the brief. Names from Task 11 (CidrParser.units/subnetOf, Subnet.start/overlaps, Rfc1918, SubnetMath.of, Ipv4) matched; no changes needed. RowRules.parseIpv4 exists in HomeViewModel.kt.
Judgement calls: none; code and tests as briefed (no em-dashes, block bodies, no regex).
RED: compileDebugUnitTestKotlin FAILED, e.g. "CandidateRangesTest.kt:16:9 Unresolved reference 'CandidateRanges'", ":17:17 'CandidateResult'", ":64:43 'Evidence'", "RangeRulesTest.kt:11:88 'RangeRules'" (plus cascading 'Cannot infer type' errors).
GREEN: targeted run BUILD SUCCESSFUL; full `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.
Files: engine/discovery/CandidateRanges.kt, ui/home/RangeRules.kt, and their two test files under app/src/test.
Concerns: none. Note groupFor treats ranges after index 0 and manual ranges with first-match-wins in list order, so a wider swept /24 listed before a manual /28 wins over it (per brief).

## Fix round 1
Added two RangeRulesTest cases (manual-only range groups its host; swept /24 listed before a manual /28 wins). Mutation check: with `+ manualRanges` removed from RangeRules.kt, "a manual only range groups its host as off subnet" FAILED (8 tests, 1 failed); code restored via git checkout, no production change. Full suite green; RangeRulesTest 8/8.
