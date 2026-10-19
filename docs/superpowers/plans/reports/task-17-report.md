# Task 17 report

Status: DONE.

Implemented package `data` (Entities, Converters, Daos, NetProbeDb, NetworkSignature, Repositories, Settings) verbatim from the brief, plus NetworkSignatureTest, DisplayNameTest (JVM) and DaoTest (device). Added `androidx-test-core` (core-ktx 1.6.1) to libs.versions.toml and app/build.gradle.kts only. Schema exported at app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/1.json and committed.

TDD
- RED: with tests only, :app:compileDebugUnitTestKotlin failed with "Unresolved reference 'HostEntity' / 'HostStatus' / 'DisplayName'" (compile failure, build FAILED).
- GREEN: BUILD SUCCESSFUL; NetworkSignatureTest 3/3 (bssid separates..., same gateway and prefix..., is 64 hex chars), DisplayNameTest 1/1 (precedence).
- Full unit suite: 150 tests, 0 failures, 0 errors. assembleDebug: BUILD SUCCESSFUL.
- Device (Pixel 7 Pro, 28221FDH300AP9): "Starting 4 tests on Pixel 7 Pro - 17 / Finished 4 tests / BUILD SUCCESSFUL"; tests="4" failures="0": portCountsGroupByHost, uniqueIndexPerNetwork, upsertKeepsAnnotationsAndRefreshesScanData, macRankDecidesAndMdnsTxtUnionsAcrossEmissions.

Self-review / concerns
- Code is the brief's verbatim. Known non-blocking notes: toDiscovered does not restore macSource (DiscoveredHost.macSource left null), so merging a stored host re-ranks the mac as 0; consumers needing rank should read HostEntity.macSource. mdns TXT union is per service type (later emission of the same type replaces that type's map). Unused-but-specified helpers getAny/clear on VulnCacheRepository kept. Pre-existing Kotlin warnings in DiscoveryEngineTest unrelated.
- Initial multi-heredoc bash call failed to parse and wrote nothing; files were then created with the Write tool. No downloads/installs occurred.

## Fix round 1
Changes (commit "Make host upserts transactional and column-targeted; prove unique indices"):
- HostDao: removed `@Update`; added column-targeted `updateScan` (scan fields only, never customName/status/note) and `updateIdentity`.
- HostRepository.upsertDiscovered runs in `db.withTransaction`; insert path catches SQLiteConstraintException and falls back to the update path on the existing row. setIdentity uses updateIdentity.
- NetworkRepository.ensure and PortRepository.upsertOpen run in `db.withTransaction` with the same constraint-exception fallback.
- toDiscovered restores macSource from rank (1 -> NAME, 2 -> UPNP_UDN, 3 -> NETBIOS, 4 -> NEIGH).
- DaoTest now 6 tests: strengthened annotation test (setIdentity, null mdnsName re-emission), mac rank test also asserts macSource round trip, uniqueIndicesRejectDuplicates (hosts, ports, networks throw SQLiteConstraintException; same IP in another network inserts), annotationsSurviveConcurrentUpserts (50 upserts against a status/note writer on Dispatchers.Default), ensureIsRaceSafe (2 coroutines x 20 ensure, exactly 1 row).
- Schema unchanged (no column changes).
- First device run failed with "Method uniqueIndicesRejectDuplicates() should be void" (assertThrows return value leaked from runBlocking); fixed with a trailing Unit.

Commands and results
- JVM focused: NetworkSignatureTest + DisplayNameTest BUILD SUCCESSFUL.
- Device: `connectedDebugAndroidTest ... DaoTest`: "Starting 6 tests on Pixel 7 Pro - 17 / Finished 6 tests on Pixel 7 Pro - 17 / BUILD SUCCESSFUL"; tests="6" failures="0" errors="0".
- Full: `:app:testDebugUnitTest :app:assembleDebug` see final line of the task reply.

## Fix round 2
- annotationsSurviveConcurrentUpserts rewritten: scanner loops `while (writer.isActive)` so upserts overlap every one of 150 writer steps (setNote n$i, setStatus alternating, setCustomName c$i). Each note is recorded in a ConcurrentLinkedQueue before it is written; the scanner samples `byIdOnce` after each upsert and asserts the note was written and its index never goes backwards (no stale re-application). Final state asserted: note n150, status IGNORED, customName c150, plus samples > 0.
- upsertOpen return type pinned to Unit.
- RED evidence: temporarily made upsertDiscovered non-transactional with a whole-row `@Update` (since restored, not committed). Device run: annotationsSurviveConcurrentUpserts FAILED with "expected:<n150> but was:<n146>" (a lost annotation update). The other two failures in that run were artefacts of my hack dropping the mac write.
- GREEN device run: "Starting 6 tests on Pixel 7 Pro - 17 / Finished 6 tests ... BUILD SUCCESSFUL", tests="6" failures="0" errors="0"; annotations test 2.1 s.
- Full `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL.
