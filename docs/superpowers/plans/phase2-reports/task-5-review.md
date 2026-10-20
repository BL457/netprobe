# Task 5 review (5764db3..023cd28)

## Spec compliance
- ✅ HomeUiState gains lastScanAt Long?, totalHosts Int, otherSubnetHosts Int, scanStartedAt Long?, scanFinishedAt Long?, all defaulted (HomeViewModel.kt:33); names/types/nullability match the brief.
- ✅ HomeStats(lastScanAt, totalHosts, otherSubnetHosts) and HomeStats.of; ScanFinish.next and afterNetworkChange match the brief bodies exactly.
- ✅ Controller ruling applied: HomeStats.of(hosts, rows, finishedAt = null) gives `max(lastDiscoveryRun).takeIf{>0} ?: finishedAt`; VM passes `done` (HomeViewModel.kt, stats line). Stored value wins over finishedAt (tested).
- ✅ Combine gains finishedAt at index 10; rows computed once unfiltered (`all`), stats from unfiltered lists, `rows` still HomeFilter.apply(all, f, q); status null when idle unchanged.
- ✅ init collector (was/next) matches brief; first emission (scanning=false) leaves null.
- ✅ otherSubnetHosts = rows.count{offSubnet && !stale}; offSubnet comes from RowRules.row against the live env's sweepRange subnet (and is false when env is null), so it follows the live local subnet.
- ✅ No em-dashes in changed files (grep), no new dependencies, existing HomeUiState constructor calls compile via defaults.
- ✅ Tests: brief's 4 present plus 1 extra for the ruling. `./gradlew :app:testDebugUnitTest` BUILD SUCCESSFUL.
- Plan conflict: none. The third parameter on HomeStats.of is an additive deviation from the brief signature, authorised by the controller ruling; default keeps the brief's 2-arg call valid.

## Task quality: Approved

Combine adds one input that changes only twice per scan; the per-emission work is the same single map over hosts as before plus one count and one max (O(n)), no new threads or main-thread blocking. No emission storm.

## Findings
Critical: none. Important: none.

Minor
1. HomeViewModel.kt (finishedAt / stats): until Task 16 wires afterNetworkChange, scanFinishedAt survives a network switch. Scenario: scan on network A (finishedAt set), switch to never-scanned zero-host network B: lastScanAt falls back to A's finishedAt, so mode is SETTLED instead of HERO and scanFinishedAt sits against B. Known/accepted, but Task 16 must also reset it (and note a Stop/cancelled scan also counts as a finish, so a cancelled first scan on an empty network goes SETTLED not HERO).
2. HomeStatsTest.kt: the "other subnet" test feeds hand-built rows with otherSubnet set, so it proves the count and stale exclusion but not the live-subnet derivation, and the VM wiring (index 10, scanStartedAt = run>0) is untested. Coverage for the latter belongs with Task 6/16 or a VM test.
3. HomeViewModel.kt: scanStartedAt = latestRun is session-wide, not per network, and stays set after the scan ends; Task 6 must gate on scanning (already noted in the report).
