# Task 5 report

Status: DONE. Commit 023cd28.

Implemented: HomeStats.kt (HomeStats, ScanFinish.next/afterNetworkChange), HomeUiState five read-only fields, finishedAt flow, combine index 10, init collector, as briefed.

Judgement call (controller ruling): HomeStats.of gained a third parameter `finishedAt: Long? = null`; lastScanAt = max stored lastDiscoveryRun (>0) ?: finishedAt. The view model passes the scanFinishedAt value, so it is equivalent to `lastScanAt ?: scanFinishedAt` but unit-testable. Extra test: zero hosts + finishedAt=99 gives lastScanAt 99; stored 250 wins over 99.

RED (verbatim): `> Task :app:compileDebugUnitTestKotlin FAILED` / `e: ...HomeStatsTest.kt:19:22 Unresolved reference 'HomeStats'.` (BUILD FAILED in 3s)
GREEN: HomeStatsTest 5 tests, 0 failures; `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL (full suite + build).

Files: ui/home/HomeStats.kt (new), ui/home/HomeViewModel.kt, test ui/home/HomeStatsTest.kt.
Self-review: no em-dashes, block bodies where return is used, no new deps, no attribution lines, adb untouched.
Concerns: none. Note afterNetworkChange is not yet wired (Task 16 per brief). scanStartedAt is latestRun, which persists after scan end (consumer should gate on scanning).
