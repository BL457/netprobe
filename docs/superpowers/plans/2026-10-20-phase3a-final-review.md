# NetProbe Phase 3A: final whole-branch review

Reviewer: Dot (QA). Branch `phase3a`, range c4855c1 (master) .. 721d2db, 27 commits (18 build tasks, fix rounds, two Phase 3B plan documents not reviewed). Spec `docs/superpowers/specs/2026-10-20-phase3a-polish-design.md` (c4855c1, with the 1.10 and test 8 amendments), plan `docs/superpowers/plans/2026-10-20-netprobe-phase3a.md` (6b992d4), ledger rulings R1 to R7.

## Verdict: READY AFTER FIXES

The code is ready for device QA. I traced every Review Focus path end to end across the tasks and found no defect in production code: printer ports get a passive read only in both modes, the 64-port and 60 s budget is a single choke point with Quick and Quick scan all passive by construction, port evidence never erases and a CONFIRMED name never drops back to PORT_TABLE on any write or read path, Remove and Undo are serialised against the radar collector and Quick scan all with no orphan path I could construct, the migration is additive with no destructive fallback, and no instrumented test opens `netprobe.db`. The gate is green (539 JVM tests, 0 failures, rerun fresh). What blocks is the Task 19 procedure itself. As written, step 8 tells Bugsy to run three checks that permanently delete the Director's real data on his phone: his whole network inventory twice, and one marked host. The only way back is a rollback to the schema 1 build, and the step 0 backup leaves out the SharedPreferences file that holds his manual ranges. Several watch items the per-task reviews parked "for Task 19" are also missing from the checklist. Both fixes are plan text only; no code changes are needed before the Director tests.

## Must fix before the Director tests

### I1. Task 19 step 8 destroys the Director's live data, and the backup cannot fully restore it
- Severity: High (Director's data).
- Where: `docs/superpowers/plans/2026-10-20-netprobe-phase3a.md:6410`, `:6411`, `:6409`; backup `:6319` to `:6329`; restore `:6429` to `:6439`.
- What: these step 8 checks run on the live `netprobe.db` after the in-place migration:
  - "Forget this network, then start a radar scan: a fresh empty network row". This commits the Forget, which deletes every host, port, mark, note and override on his home network and clears its manual ranges.
  - "Forget, then dismiss: a fresh empty network row". Same result.
  - "Remove a marked host, start a Detail scan on another host: ... the host stays removed". This permanently deletes a marked host with its note and overrides.
  - "Forget, then back out of the app and reopen it, then Undo" is safe only while the process survives. If Android kills it while the app is in the background, the next start purges the Forget and it becomes final (spec A6).
  - "Remove device on a marked host, then Undo" and "Remove a host while Quick scan all runs ... Undo still restores it" lose the mark if the Undo under test fails, and finding out whether it fails is the point of the check.
- Why: the restore procedure only goes back to the schema 1 build plus the step 0 backup, so the Director loses Phase 3A as well as anything learned since step 0. Step 0 copies only the three database files. Manual ranges live in `shared_prefs/netprobe.xml` (`AppSettings`, key `extManual.<networkId>`), and a committed Forget clears them, so even a full restore would not bring them back.
- Fix (plan text only):
  1. Step 0: also back up `shared_prefs/netprobe.xml` (`run-as $PKG cat shared_prefs/netprobe.xml`). Add it to the restore procedure, written while the app is force-stopped.
  2. New step 4b, after the in-place migration passes: force-stop, then back up the schema 2 database files and prefs into `$BK/schema2/`. Add a "restore data only" procedure that keeps the Phase 3A build (force-stop, remove the three database files, stream the schema 2 backups back in, restore the prefs).
  3. Rewrite the destructive step 8 checks to use throwaway data only. Run the two Forget-then-commit checks and "Forget, back out, reopen, Undo" on a second network: a phone hotspot or guest Wi-Fi, scanned once so it has rows. Run the Remove checks on hosts Bugsy marks and notes for the test (for example a TV or a printer), never on hosts the Director marked himself. Keep "Forget, then Undo" on the home network, because Undo is the behaviour the Director asked for, but run it only after step 4b.
  4. State in step 8: "No check may commit a Forget or a Remove on the home network without the Director's go-ahead."

### I2. The Task 19 checklist is missing watch items the per-task reviews parked for it
- Severity: Low (coverage; these items only get checked if they are on the list).
- Where: `docs/superpowers/plans/2026-10-20-netprobe-phase3a.md`, step 3 (`:6356`), step 8 (`:6404`) and step 9 (`:6421`).
- What and fix: add these lines.
  - Step 3: DirectorCopyMigrationTest's `@After` deletes the copy even when the test fails, so re-run step 2 before any re-run of it (Task 1 minor d). The "unchanged" check compares `netprobe.db`, `-wal` and `-shm`, by size and time (Task 1 minor b).
  - Step 8: Home Assistant on 8123 shows "Open" and launches the browser (Task 11). Stop a radar scan after a few seconds, force-stop, relaunch: no mass dimming (A10, Task 12). Edit device at font scale 1.3 with the keyboard up: Save and Cancel are reachable (Task 10). With TalkBack on, the Detail identity line reads "Edit device, <name>" in place of the identity text (deviation 10, Task 10). A Full scan of a slow-handshake TLS device (a NAS or a printer's 443) ends promptly on Stop (Task 5: no overall TLS handshake deadline). During the gfxinfo run, also note frames in the expand and the settle (Task 16: the live layer cache rebuilds per frame while the size changes). Remove a device while its Detail page is open: the header blanks for the length of the exit slide (new, see M7).
  - Step 5: record a failure in `RadarBlockTest` pulse timing (about a 1 to 2 frame margin at 464 ms) or in `ServiceIdentifierLadderTest.eight parallel identifies` (wall-clock) as a timing flake to rule on. Do not relax either test (ledger notes for Tasks 5 and 16).
  - Step 9, for the Director to rule on: the pulse ring is not dimmed with the arm during the settle (Task 16, cosmetic).

## Minors to carry

### Fix now
None in code. I1 and I2 cover the procedure, and every code minor below is safe to carry into device QA.

### Task 19 (watch or record)
- M1. Task 1 (c): DirectorCopyMigrationTest compares marks, notes and custom names row by row, but ports only by count. That is enough for the migration (ADD COLUMN cannot change rows); if a port-level question comes up, read the copy directly.
- M2. Task 8: the software chip row sits in the pinned header. The five-port-row floor at font scale 1.3 is pinned by DetailContentTest (spec 9.2 test 17), which runs in step 5.
- M3. Task 7: IdentityStabilityTest case 3 pins the merge function, not the write path. The instrumented backstop `DaoTest.portEvidenceSurvivesRediscoveryAndATimedOutRescan` runs in step 5, and step 8 checks "identity never drops after a Quick scan".
- M4. Task 14: Remove during Quick scan all and during a radar scan are already in step 8. Keep them, on throwaway hosts per I1.

### Later phase
- M5. `AppContainer.radarScanNetworkId` is written (`HomeViewModel.kt:196`, cleared at `:187`) but never read. Remove depends on `radarScanActive` since the Task 14 fix round. Delete it, or document it for 3B, whose Gruff review G1 already touches this area.
- M6. `DetailViewModel.setOverrides` (`DetailViewModel.kt:157`) launches on the cancellable `viewModelScope`, unlike `setNote` (NonCancellable). Save followed by an instant Back could in theory drop the edit. The window is a few milliseconds, so this is not realistic, but it is a one-word change for consistency (`setStatus`, from Phase 2, has the same shape).
- M7. After Remove device, the Detail screen's host flow emits null during the 240 ms exit slide, so the header blanks and "No open ports yet, run a scan" shows briefly. Cosmetic; the Task 19 recording will show whether it is noticeable.
- M8. Race window of tens of milliseconds: if Forget is tapped on Home before Remove's appScope job has posted its Undo, the Remove post commits the Forget, and a later Undo of the Remove would re-insert the host under the deleted network id (an invisible row). This needs a Forget within the Remove's stop, lock and delete time. Note it for a later Undo rework.
- M9. Task 13: a failed Forget cascade clears the in-memory forgotten state while the row stays soft-deleted, so `ensure` returns null and Home shows no network until the next process start purges the row. This only happens on a database error. Later: log it, and retry the purge in the same process.
- M10. Task 2 M2: the env collector swallows `ensure` and purge failures silently. Add a log line.
- M11. Task 2 M1 and Task 13: the commit clears `extManual.<id>` before the cascade, while `purgeForgotten` clears it after. Both are harmless because network ids are AUTOINCREMENT and never reused. Align them when the code is next touched.
- M12. Task 3 minor 3: `ServiceMerge` lets a PORT_TABLE result that carries product or detail overwrite a CONFIRMED row's product and detail. No producer emits that today. Pin it with a test if a producer appears.
- M13. Task 4 (2): `TlsPeek.subjectField` edge cases (an escaped comma before the key, multi-valued RDNs with `+`, hex escapes). Display data only.
- M14. Task 5: the `HttpTitleFetcher` cancelled flag has no direct test. The parallel budget test can flake in the window the ledger describes; if it ever does, drop the `startedAt` assertion and keep the bound.
- M15. Task 6: the OUI dispatch split and the duplicated regex-to-Hit code are refactors only.
- M16. Task 10: the Edit draft uses `remember`, not `rememberSaveable` (typed text is lost on process death); there is no test that resets all four fields; detected values are passed live into the dialog.
- M17. Task 14: RemoveDeviceTest's helper repeats the removeDevice steps (as the brief required); the early `removedHostIds` mark stays if `stopAndJoin` throws (harmless).
- M18. Task 15: test message wording; PulseGate does not guard a clock that runs backwards.
- M19. Task 16 and 17: the RadarBlock body is about 175 lines; animated colours are read in composition at the leaves (cheap, as the brief allows).
- M20. Task 18: a filter change mid-scan staggers newly visible rows; rows rise again after scrolling out and back (inherited from Phase 2); removing animateItem at scan end snaps a running slide; the identity Crossfade sits inside `?.let`, so the first identity on a previously blank line does not fade; unused test helpers.
- M21. Spec 1.14 asks for the Home row cross-fade on "the identity part". The implementation cross-fades the whole data line (`RowText.dataLine`). The visible result is the same; noted as a small deviation.
- M22. Housekeeping outside the branch: the controller's ledger heading (`.superpowers/sdd/2026-10-20-netprobe-phase3a/progress.md:1`, git-ignored) contains one em-dash.
- M23. Inherited from Phase 2 and not introduced here: a few literal strings outside the Copy objects (`Controls.kt` "Scan", "Stop", "Back to Home"; `DetailComponents.kt` "More actions"; `DetailContent.kt` empty-state text; `HomeViewModel.kt` Quick scan all failure text; the `HomeComponents.kt` chip description). Phase 3A added none; fold them into HomeCopy and DetailCopy in a later copy pass.

## Cross-task checks (summary)

- A. Spec coverage. Every section 1 to 7 requirement and decisions D1 to D21 have a delivering task, and D18 stays unchanged as ruled. The c4855c1 amendments are implemented: during a scan, rows already listed get placement only, and rows get no animation when no scan runs (`DetailContent.kt`); test 8 uses 80, 8123 and 8443 (`ListMotionTest.aNewPortSlidesTheRowsBelowItIntoPlace`, including the no-scan -1 case). No requirement was delivered two different ways. Plan deviations 1 to 19 match the code (purge in the first `ensure`, `QUICK_ARC_TURN_MS`, Proxmox on O, and the rest).
- B. Review Focus.
  - 1: `ServiceIdentifier.identify` routes PASSIVE_ONLY before every other set, and every active step goes through `Ladder.active`. The strict-fake test fails on any byte sent to the 9100, 515, 2000 or 6000 ranges or to 25, 110 and 143, in both modes.
  - 2: the only callers are Detail (`ProbeModes.forProfile`, one budget per scan) and Quick scan all (`QuickScanAllPolicy.mode()` = Passive). The default parameter is Passive, and discovery never calls the identifier. Tests cover the 64-port cap, the 60 s stop, refused ports, today's steps not counting, and the parallel overshoot.
  - 3: every write goes through `ServiceMerge` (`PortMapping.merge`, `HostEvidenceBuilder`). Radar re-identification reads all eleven fields through `servicesFor`. Port scans write through `setIdentityIfNotWeaker`.
  - 4: Undo commits run NonCancellable. `committedBefore` wraps all three scan bodies. `removedHostIds` is marked before any suspension; `radarScanActive` is set straight after the commit. The marks are cleared in a `finally` inside `hostWriteLock`. Scan writes go through `upsertOpenIfHostExists`, and restore clears strays first. There are no nested lock cycles (UndoStore mutex, then hostWriteLock; never the reverse).
  - 5: the migration is additive and has no destructive fallback. `1.json` keeps identityHash 01cb3d4a14204fafc8d16708cf6b8f6a, and `2.json` is committed. A grep of androidTest finds `DB_NAME` and `getDatabasePath(DB_NAME)` only in the read-only stamps of MigrationTest and DirectorCopyMigrationTest. There is no `databaseBuilder` or `deleteDatabase`. `NetProbeDb.open` runs only on `MIGRATION_TEST_DB`, and `AppContainer` touches no database at construction.
- C. Director's data. No code path can wipe or corrupt the database outside an explicit Forget commit or a purge of rows marked forgotten. `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun` exists in the local AGP 8.10.1 jar, so the step 3 runs will not uninstall the app. Gradle ignores unknown `-P` properties silently, so the plan's "if it is rejected, stop" can never trigger; the `ls -l databases/` after step 3 is the real check. The procedure problems are I1.
- D. Interactions.
  - Forget during a Detail scan: not reachable, because the Detail scan dies with its ViewModel when Home shows. A late write is still dropped by `upsertOpenIfHostExists`.
  - Remove during Quick scan all plus a radar scan: both covered as above.
  - Edit device, then Undo a Remove: the snapshot is the full `HostEntity`, so the overrides come back (DaoTest fix round 8cd3d70).
  - Open with on a port-identified host: independent of identity.
  - Forget while a Remove Undo is pending: `post` commits the Remove (final, as A6 says). See M8 for the reverse race.
  - Process death with a pending Undo: a Remove was already deleted and stays deleted. A pending Forget is purged by the first `ensure` of the next process.
  - Network switch mid-scan: `radarScanActive` and `radarScanNetworkId` are reset in `finally`; `lastRunCompleted` stays false, so no `markRun` happens and nothing is published, by `RunEnd`'s guard. The TalkBack announcement says "Scan stopped", which is accurate.
- E. UI. The radar, Stop pill and Detail ScanBar sit in Scaffold topBars, outside the lists. The Edit dialog's buttons are outside its scroll. Every Phase 3A string is in HomeCopy, DetailCopy, EditCopy or DetailText and has a CopyTableTest template. A byte grep of the diff and of all commit messages finds no em-dashes. There are no disclaimers or confirmations. In RadarBlock, per-frame values are read only in `graphicsLayer {}` and `onDrawBehind`; `fraction` in composition is the sanctioned exception. Reduced motion is honoured in every animated composable: radar, nav, pill, segments, chips, notice, row and port arrival, identity cross-fades, edge colour and progress.
- F. Build hygiene. The commit-message grep for attribution is empty. `gradle/libs.versions.toml` is unchanged. `app/build.gradle.kts` adds only the androidTest schemas asset source set.

## Gate result

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL. JVM tests were rerun fresh with `--rerun`: 539 tests in 90 classes, 0 failures, 0 errors, 0 skipped. androidTest compiles. The instrumented classes are compile-only; Task 19 steps 3 and 5 cover every class the branch added or changed: MigrationTest 1, DirectorCopyMigrationTest 1, DaoTest 19, RemoveDeviceTest 6, RadarBlockTest 14, HomeContentTest 26, DetailContentTest 23, NavTransitionTest 2, ControlsMotionTest 4, ListMotionTest 4. The only class not listed is the Phase 1 NeighbourSpikeTest, which is not part of this branch.

## What the Director should know

What you will see that is new:
- The radar sweeps with a bright leading edge and a long tail. It turns once every 3 s instead of 2.4 s. Blips dim between sweeps and light up as the arm passes. A ring pulses from the centre when a device lands, the outline "breathes" while scanning, and the disc springs back with a small bounce when the scan ends.
- Screens slide. The Detail Scan pill morphs into Stop. Chips and segments fade, the notice line slides in, new port rows slide into place during a Detail scan, and the progress line glides. "Remove animations" in Android settings turns all of it off.
- Every port's long-press sheet has "Open with..." and a "Try as" row of apps you have installed. HTTP on an unusual port now gets "Open" as its main action, but only after a Full or Custom scan has confirmed it, because Quick scans still send nothing new.
- Full and Custom scans now probe quiet ports (TLS, HTTP, RTSP, RDP), up to 64 ports and about a minute of probe time per scan. Printer ports (9100 to 9107, 515) and a few others are never probed, so nothing prints.
- Identities improve from port findings, and "Why this identity" shows lines such as "port 5000 httpTitle: ...". Apps such as Home Assistant, Plex, Pi-hole and UniFi Network now show as "Runs ..." chips rather than as the device's identity. A Pi running Home Assistant may now read "Raspberry Pi Computer" with a "Runs Home Assistant" chip.
- Tapping the name or the identity line opens "Edit device" (name, type, vendor, model, each with "Reset to detected"). Your values show everywhere and survive scans.
- Home overflow has "Forget this network" and Detail overflow has "Remove device". Both act at once and offer Undo in the notice line. The Undo lasts until you dismiss it, start any scan, or close the app.
- A stopped scan says "Stopped after N s". Stale dimming now survives a restart, but only after a scan that completed.

Choices waiting for your ruling (spec section 10, A1 to A10, plus the plan-review items):
- A1, the motion details: the 3 s turn, blips dimming to 0.45 between sweeps (one token brings back today's look), and the extra cross-fades.
- A2: active probes on Full and Custom only, with no Settings toggle.
- A3: a port scan replaces the identity at equal confidence, not only higher.
- A4: the Edit dialog replaces Rename; confirming a detected value pins nothing.
- A5: twelve new columns.
- A6: the Undo is memory only.
- A7: your values never feed identification.
- A8: "Open with..." defaults to http, or https for bare TLS.
- A9: apps become chips.
- A10: only completed runs persist dimming.
- The real worst-case probe time is about 60 s + 8 x 12 s, not 76 s.
- Port evidence never expires on a reused IP.
- Quick scan all keeps its 1.2 s arc.
- A vendor-only override on your own phone shows on Detail, but Home keeps "Android device".
- The pulse ring is not dimmed with the arm during the settle.
- The copy: "Forgot <network>", "Removed <device>", "Runs <app>", "Set by you: ...", "identifying N ports", and whether Quick scan all should also say "Scan skipped: could not bind to Wi-Fi" (open item 3).

The Proxmox rule: `p-proxmox-cert` matches the certificate Organisation "Proxmox Virtual Environment", not "PVE Cluster" as the spec says. A real node puts "PVE Cluster Node" in OU, which NetProbe does not read, so the spec's version would never fire. Reverting it is one line if you prefer the spec.

SMB: in this branch, Windows is still inferred from ports 135 and 445; there is no SMB probe. The SMB negotiate probe, and the point that it leaves logon events in a Windows host's security log, belong to Phase 3B and need your ruling there, not here.

Open items (spec section 11):
1. Rule samples come from vendor defaults; your Synology, Home Assistant, Hue, UniFi and Hub Manager hosts confirm them in Task 19.
2. SMB has no banner (now Phase 3B).
3. The Quick scan all bind wording.
4. Phase 2 minors M3, M5, M6, M7, M9, M11 and M12 are still open; M8 is softened.
5. App icon approval is still pending.
6. An optional Settings toggle for active probes.
7. Industrial control ports (102, 502, 20000, 44818, 47808) are not on the never-probe list. Decide before any client-site use.
8. Software names are not searchable and do not feed the vulnerability lookup.

Before you test: the Task 19 steps that delete a network or a marked host will be moved to a hotspot network and test-marked devices, and a second backup will be taken after the upgrade (I1). Nothing will run against your own marks without your say-so.
