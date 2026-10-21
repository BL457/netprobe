# Phase 3C-1 final review (Dot)

Range 5abf1d2..9414e56, 18 commits, branch phase3c-1. Read-only review of the full app/ diff, the spec (sections 1, 2, 5), the plan header, Global Constraints and Review Focus, the ledger, common.md and the six task reviews.

## Verdict: READY TO MERGE

Counts: Blocking 0, Should fix 2, Minors 14.

Gate confirmed: `./gradlew :app:testDebugUnitTest` from the repo root, BUILD SUCCESSFUL, 996 tests, 0 failures, 0 skipped (151 result files). Not run here and not claimable: any Compose test, the device fetch test, anything on the phone (Task 11).

Nothing blocks the merge to the phase3c-1 line. The two Should-fix items are recommended before the Task 11 device QA starts, because each changes what the Director would see on the phone, but neither corrupts data. Merge to master stays the Director's call (ruling R2).

What was checked and found sound:

- Names and behaviours across tasks: `RunSettings`, `HomeRange.of`, `ScanBinding.forRun`, `ExtendedOptionsOf.from`, `RunEnd.of(..., aggressive)`, `DiscoveryOptions.aggressive`, `WalkPlan`, `GatewayProbeResult.walkSkippedManySeeds`, `ExtendedNote.GATEWAY_WALK_SKIPPED_MANY` and the Copy objects line up. No leftover caller of the old `ScanBinding.run(ip, env, maxPrefix, extEnabled, ...)` or `SubnetMath.sweepRange` for the local range outside `HomeRange` itself. The `combine` indices in `baseState` (runSnapshot 17, settings 18) are correct.
- Rulings: R5 holds everywhere (`ExtendedOptionsOf` manual-only branch uses `s.extMax`, the stepper is always enabled, `ManualCap` marks entries past the cap, `capDropped` names "Maximum extra ranges"). R6 holds (`runSnapshot.value = null` is the first line of `discoverOn`, HomeViewModel.kt:~213; `forDisplay` uses the snapshot only while `scanning`).
- Data safety: no Entities, Migrations, NetProbeDb or schema file touched; the diff outside app/ is docs only. `AppSettings.update` writes the same 15 keys and `load()` reads the same keys with the same coercions (proved by `every key that is missing loads its SettingsState default`, which also asserts the key set is exactly those 15). Reset never touches `extManual.*`, wordlists, the vulnerability cache or `DirBruteOptionsStore`. The only deletion in the URL path is the fetcher deleting the list it has just stored when Cancel lands at the very end.
- Concurrency: the controller runs on appScope with `CoroutineStart.ATOMIC` plus a `finally` that always calls `finish`, owner check by `Job` identity, `isCompleted` (not `isActive`) guards the second start, `detach` drains an unconsumed message to `AppMessages`. appScope is `Dispatchers.Default`, so the `synchronized` barrier in the job body cannot run inline under the lock. The fetcher's store commit is `NonCancellable` with a disconnect watcher; every `HttpURLConnection` is disconnected in a `finally`; `CancellationException` is re-thrown on every path. The walk: bounded `for (k in 1..16)`, `probed.add` makes a unit probe-once, rounds are `coroutineScope` + `async` so cancellation propagates, progress totals grow under the same lock.
- Pinned controls: the Settings overflow, both Wordlists pills and the URL Cancel are in `topBar` (FlowRow), none inside the scroll Column or LazyColumn. Dialog buttons are in `AlertDialog` slots.
- Copy: zero em-dashes and zero en-dashes in the 4,255-line diff; every new string is in a Copy object with a template (`CopyTableTest`, `ToolCopyTableTest`); no old "Extended discovery", "Probe for gateways" or "Gateway probe" text remains in user-visible strings (only a Log line). Cleartext is allowed (`usesCleartextTraffic="true"`), so http:// URLs work on targetSdk 30.
- Test honesty: every edit to a pre-existing test is a rename forced by the copy change, the `gatewayProbe` lambda arity, or the `ScanBinding` signature; `toggle off changes nothing` is untouched. All Review Focus tests named in the plan exist and assert behaviour (the spec example walk test checks the exact unit order and progress totals, the 5-seed test checks the flag, the controller tests drive real coroutine states). The one test that was bent for compilation (`184.toByte()` in the G4 fetcher test) keeps the same bits.

## Blocking findings

None.

## Should fix

S1. Cancel is not honoured during a stalled DNS lookup (spec 5.1: "Cancel ends the fetch within a second"). UrlWordlistFetcher.kt:~77-82 (`conn.responseCode` under `CancellableIo.closing`) and :64 (`resolvesLocal`, run inline in `follow`). `disconnect()` and cancellation do not abort `getaddrinfo`, and the calling coroutine is blocked in the call, so on a poor connection the Cancel button does nothing until the resolver gives up. Task 9's review parked this as a nit; on a phone it is the most likely way Cancel feels broken. Fix: run the status read and the redirect-target resolve as `async(Dispatchers.IO) { ... }.await()` so the awaiting coroutine is cancelled at once and the blocked thread is abandoned (the `finally` disconnect still runs). Add a test with a `resolve` lambda that blocks on a `CountDownLatch` and assert `cancelAndJoin` returns inside a second.

S2. A body shorter than its declared Content-Length can be stored as a complete list. UrlWordlistFetcher.kt, `CountingStream` (:~178-190). Android's OkHttp-backed stream throws on a premature EOF (then the S1-era `ErrorRecordingStream` path reports a fetch failure), but the desktop JDK stream returns EOF quietly and no test covers it, so Review Focus 5 ("stores junk") rests on platform behaviour nobody has run. Fix: in `CountingStream.read`, when a read returns -1 and `total != null && count < total`, throw `EOFException`; add a "short body" test (server declares 1,000 bytes, sends 100, expect `Failed(Connection)` and no file). Gzip responses have `total == null` on Android, so they are unaffected.

## Minors

M1. HomeRangeTest.kt: no Aggressive-on case for a /8 or a /32 mask (Task 4 nit, still open). Add `HomeRange.of(ip("10.20.37.5"), 8, 22, true)` expecting `10.20.36.0/22`, clamped, originalPrefix 8, and a /32 expecting an unclamped `10.20.37.5/32`.

M2. ScanBindingTest.kt: no case for a manual range inside the masked subnet with Aggressive off (Task 4 nit, still open). With env16 and manual `172.20.30.0/24`, assert `172.20.30.5` is bound and `172.20.9.20` is not.

M3. ExtendedOptionsOfTest.kt:11-27 compares against literal `ExtendedOptions(...)`, restating the `when`. Add one case with Aggressive on, `extGatewayProbe = true` and a manual list, showing both survive. DiscoveryEngineExtendedTest.kt imports sit after `kotlin.random.Random` (order only).

M4. HomeViewModel.kt:~141 `c.settings.manual.forNetwork(networkId.value)` is evaluated on every `baseState` emission even while scanning, when `forDisplay` discards it. Harmless; make it lazy only if `forDisplay` is ever changed.

M5. RunSnapshotTest.kt:~52: `assertSame(started, eff)` restates `RunSettings.effective`'s `snapshot ?: ...`. The binding, grouping and home assertions beside it are real, but no JVM test drives `HomeViewModel`, so "snapshot set at start, ignored after the run, cleared at the next start" (HomeViewModel.kt:~213-221, :141) is verified only by reading. Not fixable cheaply (no VM harness); covered by device check 3 below.

M6. GatewayWalkTest.kt:~105 (`four answering seeds are allowed`) passes budget 0, so it proves only that the flag is false. Pass a budget of 16 and assert `walked(t).isNotEmpty()`. `CandidateRanges.sweptUnits` has no direct test for the evidence branch (CandidateRanges.kt:~59): add one RFC1918 evidence address outside the scanned range and one inside. The `cancellation mid walk` test cancels a cooperative `delay`, so socket closure on cancel stays a device matter.

M7. UrlImport.kt:77 `validate` goes through `java.net.URI`, which rejects characters browsers send (`|`, `{`, `}`, `^`, a bare `%`) and gives a null host for underscore and IDN names, all shown as "Enter a web address...". Fall back to `URL(full)` plus the host and user-info checks on `URISyntaxException`, and add a row `example.com/a|b.txt`.

M8. UrlImport.kt:135 `isLocalLiteral` does not treat `0.0.0.0/8` or `::` as local, so the pure `redirect()` follows `http://0.0.0.0:8080/` from a public host; only the fetcher's resolve step (`isLocalAddress`) refuses it. End to end the result is right, but the rule and its unit test disagree with `isLocalAddress`. Add both to `isLocalLiteral` and to the "never bounces" test.

M9. UrlWordlistFetcher.kt:~116-119: a Cancel that lands after `importBody` returned but before the active check leaves a stored list while the job ends with `CancellationException` (no snackbar, list appears; no corruption). Also: the `catch (e: IOException)` in `importBody` binds an unused `e`; document on `fetch` that `onProgress` is called from an IO thread and must not throw. Tests missing: fetcher-level https-to-http (`Insecure`) via the `open` seam, and the delete-after-cancel branch via a fake connection whose body completes while the job is cancelled.

M10. WordlistsScreen.kt:242 ignores the Boolean from `urlImport.start(url)`; in the microsecond window between `finish()` setting Idle and the job reporting `isCompleted`, an Import tap closes the dialog and does nothing. `StateFlow` de-duplicates equal strings, so two "Import cancelled" results inside one snackbar period show once. `attach`/`detach` versus the post decision is not atomic (UrlImportController.kt:~41-45). Cancel gives no feedback while the `NonCancellable` store commit finishes. All cosmetic edge cases; fix only if the Director sees them.

M11. WordlistsScreen.kt:215 `importing` is local `remember` state: leave and re-enter during a file import and both pills re-enable, so a URL import can overlap a file import against the same `WordlistStore` (pre-existing 3B shape, now reachable). Hoist into the controller layer in 3D. WordlistsScreenTest `cancelShowsImportCancelled` passes the message in directly and asserts Cancel absent while `urlState` is Idle, which is close to vacuous: drive it through a real `UrlImportController` with a fake fetch, or drop it.

M12. AppContainer.kt:~144 binds a tool run with the manual list of `currentNetwork.value?.id`, while DetailViewModel.kt:~258 uses the host's own `h.networkId`. They agree because Home and Detail list only the current network's hosts (plan review G12), but a network switch with a Detail screen open could split them. If `ToolRunner.bind` ever receives the host, use its network id.

M13. ExtendedNotices.kt:~99 `sweptPrefix` parses the prefix out of `status.ranges[0].subnet` text with a /24 fallback. Correct today; fragile. Prefer carrying the swept prefix on `DiscoveryStatus` (it already has `clampedFrom`). HomeCopy.kt:56 `capDropped` says "N more ranges found" when the dropped ranges were typed by the Director (Aggressive off, R5); "N more ranges not swept, raise Maximum extra ranges in Settings" would be true for both. Copy choice, see For the Director.

M14. Housekeeping. (a) ExtendedSection.kt:~60 and SettingsScreen.kt:~42 each declare `private const val NO_UNIT = ""`; share one. (b) The plan's Global Constraint "`HttpURLConnection` appears only in `wordlists/UrlWordlistFetcher.kt`" and Task 9 step 4's grep expectation are false (NvdClient.kt:36, HttpTitle.kt:74 and UpnpDescription.kt:85 use it); correct the plan text and tell Task 11 so a four-file grep is not read as a failure. (c) Task 5's report said `assembleDebug` was skipped; the fix round ran the full gate, so nothing open. (d) Parked Task 7 nit: a `BindFailedException` thrown inside the walk propagates out of `GatewayProbe.run`, the engine's outer catch adds `SKIPPED_BIND_FAILED`, and the first-pass hits are dropped. That matches the rule "a bind failure skips the extended phase", so leave it.

## For the Director

Decide:

1. Large networks wording (plan review G6, proposed, not yours yet). Caption: "Largest block of a big network to sweep. With Aggressive scan off, a network wider than this gets your /24 only. A /22 takes about 1 minute, a /20 about 4." (SettingsCopy.kt:9). The timings are extrapolated from the measured 16 s per /24 (about 4 x 16 s and 16 x 16 s), not measured on a /22 or /20. Notices: "Scanned your /24 only. Turn on Aggressive scan in Settings." (mask wider than /20, Aggressive off) and "Scanned your /20 only." (Aggressive on at the widest limit, nothing left to widen). One constant each to reword.
2. Default markers (spec 1.3): keep the tappable "Default N" marker or drop the tap target and keep the caption, after you have seen it on the phone.
3. R5 confirmation: with Aggressive off, a typed range wider than "Maximum extra ranges" is partly swept and the rest reported as "N more ranges found, raise Maximum extra ranges in Settings". Say if you would rather typed ranges ignore the cap (one line in `ExtendedOptionsOf`, one test), and whether M13's "found" wording should change.
4. VPN-only manual ranges: a manual range is swept bound to Wi-Fi/Ethernet (spec 2.4, D8), so a range reachable only through a VPN will time out rather than go over the VPN. Say if that matters to you.
5. R3: the Task 11 URL list (GitHub raw redirect, Dropbox, and so on) needs your approval before it is run. R2: 3B merge to master is yours; 3C-1 is stacked on phase3b and gets rebased if 3B moves.

Check on the device (Pixel 7 Pro 28221FDH300AP9, Task 11), in this order of risk:

1. Mid-run snapshot: start a scan with Aggressive off, turn it on (and separately use Reset) while it runs; the list must not regroup, the hero subnet must not change and the scan must not start binding. After the scan ends Home should regroup to the live settings at once. This is the one Review Focus item no JVM test reaches (M5).
2. Aggressive off with one typed range: it is swept and its hosts appear, summary "1 other range checked", line "10.0.4.0/24  manual  swept, N found"; with no typed range the scan is the old one (no extended phase, no new notice).
3. Reset to defaults from the overflow, then Undo; check the NVD key survives both, manual ranges survive Reset, "Default N" markers appear and disappear, the menu item is disabled at defaults, and the drop-down is legible on the dark theme. Leaving Settings with the snackbar up must end the Undo.
4. Smart discovery on your LAN (no seed should answer, so the walk adds nothing and the attempt count matches the first pass); cap 8 per Review Focus 3.
5. URL import: a cross-host redirect (GitHub raw), an http:// URL (cleartext), a HTML page (refused), a tiny list, Cancel mid-body on a slow link, Cancel with mobile data only or Wi-Fi off (S1), leaving the screen mid-import and finding the result on Home's notice line, the pill row at font scale 1.3 (Cancel wraps, still pinned), and the dialog's Import button enabling only on a valid URL.
6. Run the device fetch test and the Compose tests listed in Task 11 (they compile but have never run); `HomeScreenTest` last, after the backup.

Checkpoint APK: netprobe_apk/netprobe-0.3.0-9414e56-debug.apk.
