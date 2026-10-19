# Task 4 report

Status: DONE. Commit b7b4901 "Add network environment monitor and mapper".

Implemented NetworkEnv.kt (Transport, NetworkEnv, LinkSnapshot, NetworkEnvMapper) and NetworkEnvMonitor.kt verbatim from the brief, plus NetworkEnvMapperTest.kt (6 tests).

TDD evidence
- RED: `:app:compileDebugUnitTestKotlin` failed with "Compilation error" (LinkSnapshot/NetworkEnvMapper/Transport unresolved), BUILD FAILED in 2s.
- GREEN: `:app:testDebugUnitTest --tests '*NetworkEnvMapperTest*' :app:assembleDebug` -> BUILD SUCCESSFUL.
- Full suite with --rerun-tasks: BUILD SUCCESSFUL. Per-suite: SmokeTest 1, NeighbourParserTest 5, NetworkEnvMapperTest 6, SubnetTest 8; 0 failures.
- No "Installing Android SDK" or license output.

Files: app/src/main/java/com/northwalessolutions/netprobe/netenv/NetworkEnv.kt, NetworkEnvMonitor.kt; app/src/test/java/com/northwalessolutions/netprobe/netenv/NetworkEnvMapperTest.kt.

Self-review / concerns
- Monitor is untested on device (Android-only).
- Only the mapper filters VPN-flagged snapshots; current() already skips VPN networks before mapping.
- `trySend` result ignored in callbackFlow (acceptable; flow is conflated by distinctUntilChanged downstream only, default buffer applies).
- git warns LF->CRLF on new files (autocrlf); harmless.
- Unused `context` property is private val (fine).

## Fix round 1
- NetworkEnvMapper.map: ssid and bssid are now null unless transport is WIFI (takeIf { isWifi }).
- New test `ethernet never carries wifi ids` (wifi.copy(isWifi=false, isEthernet=true), ids left set, both asserted null).
- NetworkEnvMonitor.current(): added @Suppress("DEPRECATION") for cm.allNetworks. Rebuild with --rerun-tasks showed no warnings.
- `./gradlew :app:testDebugUnitTest --tests '*NetworkEnvMapperTest*'` -> BUILD SUCCESSFUL in 5s.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` -> BUILD SUCCESSFUL in 10s.
- Per-suite: SmokeTest 1, NeighbourParserTest 5, NetworkEnvMapperTest 7, SubnetTest 8; 0 failures, 0 skipped.
