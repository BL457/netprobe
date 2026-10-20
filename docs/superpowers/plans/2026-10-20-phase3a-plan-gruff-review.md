# Phase 3A plan: Gruff review

Reviewer: Gruff (Devil's Advocate). Plan under review: `docs/superpowers/plans/2026-10-20-netprobe-phase3a.md` (edd9bef, 6079 lines, 18 tasks). Binding spec: `docs/superpowers/specs/2026-10-20-phase3a-polish-design.md` (fd110b8). My earlier spec review: `docs/superpowers/specs/2026-10-20-phase3a-gruff-review.md` (ad01848). Code checked at the working tree on edd9bef.

## Verdict

2 BLOCKING, 11 SHOULD FIX, 8 MINOR. Overall: **execute after fixes**. The plan is thorough, self-contained per task, and faithful to the spec; the probe-safety work (Task 5) and the migration work (Task 1) are the best I have seen from this team. But two things would hurt the Director's data. First, Task 13 makes the app open `netprobe.db` at every process start, including every instrumented run, so the Task 18 safety sequence (migration tests first, "never open netprobe.db", stop before the real migration) no longer holds. Second, Undo of Remove device can fail silently and lose the marks and notes it promised to bring back, and Quick scan all can write orphan port rows for a removed host. Both fixes are small and local to Tasks 13 and 14. Nothing needs a rewrite.

## Findings

### G1. Task 13 opens the Director's database at every process start, including under every instrumented test
- Severity: BLOCKING
- Location: Task 13, lines 4128 to 4134 (`init { appScope.launch { networks.purgeForgotten { ... } } }` as the last member of `AppContainer`); Task 18 steps 2 and 4 (lines 5953 to 5977).
- Problem: `NetProbeApp.onCreate` builds `AppContainer(this)` on every process start (`NetProbeApp.kt` line 9), and the AndroidJUnitRunner starts the real Application for every instrumented class. After Task 13 the container's `init` touches `networks`, which forces the lazy `db`, which runs `forgottenIds()`, which opens `netprobe.db` and, on the first schema 2 build, runs `MIGRATION_1_2` on the Director's live file. In Task 18 step 2 that happens while `MigrationTest` and `DirectorCopyMigrationTest` are running, so: (a) their "the app's own database file changed" assertion races the Application's own migration and checkpoint, so it fails or flakes; (b) the real database is migrated under the test harness before step 3's controlled install-over, so the plan's own gate "If either fails, stop: do not open the app" is meaningless because the database is already migrated; (c) it breaks the hard rule "no instrumented test touches netprobe.db" for every class in step 4, not just `HomeScreenTest` (deviation 12). It also races the env collector at normal start-up: if `ensure` runs before the purge finishes, it sees the forgotten row, returns null, and Home shows no network until the next env change, because nothing re-triggers `ensure` after the purge.
- Evidence: `AppContainer.kt` line 24 `val db by lazy { NetProbeDb.open(context) }`; `NetProbeApp.kt` `container = AppContainer(this)` in `onCreate`; plan line 5953 "they install the new APKs but never open `netprobe.db`" is false once Task 13 lands.
- Resolution: No database work at container construction. Run the purge as a one-shot step at the top of `NetworkRepository.ensure` (a `Mutex` plus a `purged` flag inside the repository, before the lookup), so it runs only when the real UI resolves a network and always before any `ensure` result is used. Add a JVM test that `purgeForgotten` runs once and before the first `ensure` lookup (fake DAO or a pure `PurgeOnce` helper), and state in the Task 13 brief that `AppContainer` construction must not touch `db`.

### G2. Undo of Remove device can fail silently and lose marks and notes; Quick scan all can write orphan ports for a removed host
- Severity: BLOCKING
- Location: Task 14 lines 4513 to 4562 (`removedDuringScan`, `removeDevice`), lines 4534 to 4540 (Quick scan all loop), Task 2 line 735 (`restore` with `OnConflictStrategy.ABORT`), Task 14 `RemoveDeviceTest` lines 4402 to 4432.
- Problem, path (a), Quick scan all: the loop takes a snapshot of the host list at its start (`HomeViewModel.kt` line 219, kept by the plan at line 4536). `removedDuringScan` is filled only when a radar scan is running on that network (line 4555), and Task 16 disables Quick scan all while the radar runs, so the two never overlap and the Quick scan all skip is dead code in practice. A host removed from Detail while Quick scan all is running, before the loop reaches it, is still scanned: `quickScanHostOn` calls `c.ports.upsertOpen(h.id, ...)` for a host row that no longer exists. There are no foreign keys, so orphan port rows are written. If the Director then taps Undo, `restore` inserts the host and then `insertAll` of the snapshot ports hits the `(hostId, port)` unique index on any port the late scan wrote, ABORT rolls back the whole transaction, the `appScope` handler logs it, and the host, its mark and its note are gone. Path (b), the radar: Remove does not serialise with the running `discover` collector. An emission for that IP that has already passed the new skip check (line 4531) calls `upsertDiscovered`, which re-inserts the IP with a new autogenerated id; Undo then ABORTs on the `(networkId, ip)` unique index with the same silent loss. Discovery emits a host more than once as evidence arrives, so the window is real.
- Evidence: `Entities.kt` indices `Index("hostId", "port", unique = true)` and `Index("networkId", "ip", unique = true)`; spec 5.3 step 4 calls the handler "the backstop", but here the backstop turns a promised Undo into data loss. `RemoveDeviceTest.aRemovedIpIsNotReinsertedByTheSameRun...` reimplements the guard inside the test (lines 4408 to 4410) rather than driving `discover`, and `removeDuringQuickScanAllLeavesNoOrphans...` covers only the host already in flight, never a host not yet reached.
- Resolution: (1) Remove records the host id in an `AppContainer.removedHostIds` set that `QuickScanAllLoop` checks immediately before each host, and `quickScanHostOn` re-checks `c.hosts.byIdOnce(h.id) != null` before its writes; the radar keeps the `(networkId, ip)` set. (2) Make `restore` never lose the Director's columns: in one transaction, delete any row now holding `(networkId, ip)` and its ports (scan-derived only), delete any ports under the snapshot's host id, then insert the snapshot host and ports. (3) Extract the per-emission body of `discover`'s collector and the per-host step of Quick scan all into testable functions and pin both race paths, in JVM tests with a fake repository where possible.

### G3. Task 18 has a backup but no restore, and no way back to the schema 1 build
- Severity: SHOULD FIX
- Location: Task 18 steps 0 and 3 (lines 5929 to 5968).
- Problem: Step 3 says "restore from `$BK` with the Director" but gives no commands, and the schema 1 APK is replaced at step 2 and never kept. Restoring a WAL database needs all three files put back together with the app force-stopped, and stale `-wal` or `-shm` files left in place can corrupt the restored copy.
- Evidence: plan lines 5933 to 5937 (backup only); line 5968 (restore, no procedure).
- Resolution: Add a written restore block (force-stop, `rm` the three live files through `run-as`, write the three backups back through `adb shell "run-as com.northwalessolutions.netprobe sh -c 'cat > databases/<f>'" < "$BK/<f>.bak"`, list sizes), and before step 2 build the master b37db04 APK from the local tree into `$BK` (no downloads) so the Director can roll back.

### G4. Forget's Undo and commit capture the HomeViewModel, so Undo can stop working after the activity is recreated
- Severity: SHOULD FIX
- Location: Task 13 lines 4148 to 4182 (`forgottenId`, `envEpoch` in `HomeViewModel`; the `PendingUndo` lambdas write them).
- Problem: The `UndoStore` lives in `AppContainer` (process lifetime), but the restore and commit lambdas update `forgottenId` and `envEpoch` of the HomeViewModel that posted them. Back out of the app and reopen it with a Forget pending: the new HomeViewModel shows the Undo notice (it reads `c.undo.pending`), but tapping Undo clears the flag in the database and bumps the epoch of the dead ViewModel, so Home stays empty until the next Wi-Fi change.
- Evidence: plan lines 4171 to 4176; `UndoStore` is a container field (line 4126).
- Resolution: Move `forgottenId` and the epoch to `AppContainer` (next to `currentNetwork`), and have HomeViewModel read them, so any HomeViewModel instance reacts to an Undo or commit.

### G5. The ring pulse test asserts exact frame timing it cannot rely on
- Severity: SHOULD FIX
- Location: Task 16, `ringPulsesAreGatedByTimeAndEveryPulseFinishes` (lines 4879 to 4892); also `springSettleUndershootsOnlyTheRingAndLandsCleanly` line 4908.
- Problem: A pulse starts at least two frames after `found` changes (the `LaunchedEffect(found, mode)` runs on the next frame, `snapshotFlow` resumes after the apply, then `withFrameMillis` waits for another frame), and the count reaches semantics one recomposition later. The second pulse becomes eligible 350 ms after the first one's frame time, which already lags about 32 ms, so "2 at 400 ms" sits within one frame of the boundary. On a real frame clock this either fails deterministically or flips with Compose versions. When it fails in Task 18 the temptation is to bend it. `fractions[27] == 0f` at 448 ms has about one to two frames of margin over the spring's estimated 353 to 400 ms rest time plus start lag.
- Evidence: plan line 4885 (`assertEquals("at 400 ms", 2, counts[7])`); spec 9.2 test 2 values copied without the lag.
- Resolution: Pin the contract, not the frame: count 1 by 150 ms, still 1 at 300 ms, 2 by 450 ms, the sliding-window invariant as written, and every pulse finished. For the settle, assert rest by 500 ms, which is still inside the spec's "about 350 to 400 ms" plus lag.

### G6. The no-per-frame-recomposition test cannot see a per-frame read in a child composable
- Severity: SHOULD FIX
- Location: Task 16, `steadyScanningDoesNotRecomposePerFrame` (lines 4912 to 4920) and the `onComposed` hook (line 5045).
- Problem: `SideEffect { onComposed() }` counts recompositions of `RadarBlock` only. The likeliest regression is someone calling `angle()` or `pulse()` in the body of `ArmLayer` or `LiveLayer` (for example to compute a colour), which recomposes that child every frame while `RadarBlock` stays still. The test passes and the radar janks.
- Evidence: plan lines 5176 to 5177 pass lambdas into child composables whose bodies are not counted.
- Resolution: Pass the counter into `ArmLayer`, `LiveLayer` and `DiscBackground` (a `SideEffect` in each), and assert the sum stays at 2 or below across the 60 frames.

### G7. CancellationTest passes whether or not the socket is closed
- Severity: SHOULD FIX
- Location: Task 4, `CancellationTest` (lines 1137 to 1165) and `closeOnCancel` (lines 1276 to 1279).
- Problem: `suspendCancellableCoroutine` resumes the caller with a cancellation the moment the job is cancelled, whatever `invokeOnCancellation` does. So "cancel completes within 300 ms" is true even if `close` is a no-op; the test pins that Stop returns quickly, not that the blocked IO thread is released. Up to 8 identifies times 2 threads per Stop would then leak until their timeouts. The test does fail against the old `runInterruptible` code, which is good, but it does not pin the new mechanism.
- Evidence: plan line 1277; the test measures only `job.cancelAndJoin()`.
- Resolution: In each case also assert the server side sees the connection closed (the accepted socket's `read()` returns -1 or throws) within 300 ms of the cancel. Keep "do not raise the limit" (line 1450).

### G8. Review Focus 4's "every new scan commits a pending Undo first" is not pinned by any test, and Task 18 does not check it
- Severity: SHOULD FIX
- Location: Review Focus 4 (line 43), Task 13 line 4160 and line 4184, Task 18 step 7 (lines 5999 to 6013).
- Problem: `UndoStoreTest` proves the store's own rules; nothing proves `discover`, `quickScanAllOn` and the Detail scan body call `c.undo.commit()` first. That call is the main guard against a restore landing mid-scan.
- Evidence: plan line 4078 claims the pin; the four `UndoStoreTest` tests never touch a scan.
- Resolution: Add a Task 18 behaviour check: Remove a marked host, start any scan, confirm the Undo notice is gone and the host stays removed; Forget, start a radar scan, confirm a fresh empty row. If G2's extraction lands, also add a JVM test that the extracted scan-start step calls commit before its first repository call.

### G9. Deviation 13 (p-proxmox-cert): match O, do not add OU
- Severity: SHOULD FIX (a Director decision; recommendation below)
- Location: Task 7 line 2569; spec 3.5 line 506; deviation 13 (line 6056).
- Problem: The planner is right. Proxmox VE's self-signed node certificate is issued with OU "PVE Cluster Node" and O "Proxmox Virtual Environment", so a `certOrg` rule matching "PVE Cluster" never fires on a real node. Reading OU would need a seventh port evidence field, a thirteenth column and a schema 2 change, for one rule.
- Evidence: `TlsSubjectTest` line 1186 already encodes the real layout (`OU=PVE Cluster Node,O=Proxmox Virtual Environment`); `NewRulesTest` passes only because the synthetic sample is fed straight into `certOrg`.
- Resolution: Recommend the Director approve changing the entry to `{"id":"p-proxmox-cert","field":"certOrg","pattern":"^Proxmox Virtual Environment$","model":"Proxmox VE","type":"SERVER","weight":90,"sample":"Proxmox Virtual Environment"}` and amending spec 3.5 to match. It has no schema impact. A host with an ACME certificate falls back to the existing title and port rules, as today. Execute with this change unless he objects; it is a one-line revert.

### G10. Task 17 is too large for one implementer context; split it
- Severity: SHOULD FIX
- Location: Task 17 (lines 5373 to 5907, 537 lines, 6 production files, 2 test files, 9 separate animations).
- Problem: It mixes navigation, three shared controls, the Home list, the notice, row cross-fades and Detail port arrival. A fresh implementer has to hold all of it at once, and a reviewer cannot check the reduced-motion branch of each animation in one pass. Task 16 is the same size, but it is one file replaced whole with a stronger model, so it can stay as it is.
- Evidence: Task 17 file list (line 5376); every other UI task touches at most four files.
- Resolution: Split it into 17a "Nav and controls" (`Nav.kt`, `ScanPill`, `PinnedSegmented`, `FilterPill`, `NavTransitionTest`, and the chip, segment and pill tests from `MotionTest`) and 17b "Lists and lines" (`DeviceList` stagger, `NoticeArea`, row cross-fade and edge, Detail port arrival, identity cross-fade, progress glide, and the rest of `MotionTest`). Give `MotionTest` one file per sub-task so neither task edits the other's tests.

### G11. RemoveDeviceTest and the Quick scan all tests mostly test the test
- Severity: SHOULD FIX
- Location: Task 14 lines 4402 to 4432; `QuickScanAllLoopTest` lines 4309 to 4334.
- Problem: The radar skip is reimplemented inside the test loop, and the Quick scan all case uses a hand-written `scanOne`, so neither touches `discover`'s collector or `quickScanHostOn`. If the production line at 4531 is omitted, or placed after `upsertDiscovered`, every test stays green.
- Evidence: plan line 4409 (`if (!RemovedHosts.skip(...)) hosts.upsertDiscovered(...)` written in the test).
- Resolution: Covered by G2's extraction. Test the extracted production functions, not a copy of their logic.

### G12. Two places where the plan relies on the spec's arithmetic about step length
- Severity: MINOR
- Location: Task 5 `ActiveProbeBudget` comment (lines 1741 to 1745); spec 2.2.4 "each step is bounded by its own timeout (2 s at most)".
- Problem: A GET through `HttpTitleFetcher` can take one connect timeout, one read timeout and a 2 s body deadline, then the same again for one same-host redirect: about 12 s, not 2 s. The overshoot bound is therefore about 8 times 12 s, so the worst case is about 156 s of summed probe time (about 20 s of wall time with 8 in parallel), not the spec's "about 76 s". That is still safe, but the comment and the spec's figure are wrong.
- Resolution: Correct the comment to "a started step finishes; the overshoot is at most 8 steps", and note the real GET bound in the Task 18 record. No code change.

### G13. Quick scan all child jobs can run before they are registered
- Severity: MINOR
- Location: Task 14 `QuickScanAllLoop` lines 4488 to 4498.
- Problem: In `viewModelScope` (`Main.immediate`), `launch { scanOne(h) }` runs the body up to its first suspension before `jobs[id] = job` executes, so a Remove in that window finds no job to cancel.
- Resolution: `val job = launch(start = CoroutineStart.LAZY) { scanOne(h) }; jobs[id] = job; job.start()`.

### G14. A Forget pending while the gateway MAC is unreadable creates a second row for the same network
- Severity: MINOR
- Location: Task 2 `ensure` (lines 677 to 703); `DaoTest.forgetIsASoftDeleteUntilTheCommit` line 793 relies on it.
- Problem: With the forgotten row excluded from adoption, an env emission with a null MAC inserts a fresh row on the same gateway and prefix. After Undo there are two rows for one network, and later adoption by gateway sees two candidates and adopts neither.
- Resolution: While any forgotten row matches the gateway and prefix, return null from `ensure` instead of inserting, and keep the test's "never adopts it" assertion.

### G15. The Task 18 copy step depends on run-as reading /data/local/tmp
- Severity: MINOR
- Location: Task 18 step 1 (lines 5942 to 5949).
- Problem: `run-as <pkg> cp /data/local/tmp/...` works on most builds, but SELinux policy on some Android 13 and 14 builds denies the app domain reading `shell_data_file`. The plan has no fallback, and the next step depends on it.
- Resolution: Use the stream form, which needs no shared file: `adb shell "run-as com.northwalessolutions.netprobe sh -c 'cat > databases/netprobe-migration-copy.db'" < "$BK/netprobe.db.bak"` (and the same for `-wal`). Drop the `-shm` copy; SQLite rebuilds it.

### G16. Port evidence never expires
- Severity: MINOR (for the Director, not this plan)
- Location: `ServiceMerge` (Task 3, lines 985 to 997).
- Problem: "A null never erases" means a title or certificate learned from a device that has since left an IP stays on that host row and keeps feeding identification after the IP is reused. Discovery's always-write rule does not help, because discovery reads the stored port evidence.
- Resolution: No change in 3A. Add it to the Task 18 handover as an open item (for example, drop port rows not seen in N port scans).

### G17. The Quick scan arc slows down as a side effect of D2
- Severity: MINOR
- Location: Task 15 line 4722 (`ARM_TURN_MS = 3000`); Task 16 line 5311 (`Motion.ARM_TURN_MS / 2`).
- Problem: The Quick scan all arc turns once every 1500 ms instead of 1200 ms. It is not mentioned anywhere.
- Resolution: Either give the arc its own token at 1200 ms or list the change in the Task 18 record.

### G18. Own phone: a vendor-only override is invisible on Home
- Severity: MINOR
- Location: Task 9 line 3030 (`if (own && h.userModel == null) OWN_LINE`); deviation 11.
- Problem: If the Director sets only a vendor on his own phone, Home keeps "Android device" and `Build.MODEL`, and the vendor shows only on Detail. This matches spec 4.2's wording, but he may read it as the edit not saving.
- Resolution: Use `h.userModel == null && h.userVendor == null` for the own-line test, or list it for the Director. One line either way.

### G19. The spec's own text still contradicts deviations 5 and 6
- Severity: MINOR
- Location: deviations 5 and 6 (lines 6048 and 6049); spec 1.10 and 9.2 test 8.
- Problem: Both resolutions are right (rows present at scan start must take the placement spec or the rows below a new port jump; 8123 does not sort between 80 and 443). But the binding spec still says otherwise, and the per-task reviewer checks against the spec.
- Resolution: Amend spec 1.10 ("rows present at scan start do not fade or rise; every row takes the placement spec while a scan runs") and 9.2 test 8 (80, 8123, 8443) in the fix round.

## The 15 flagged deviations

| # | Call |
|---|------|
| 1 | Agree: overrides in `data/` keep the data layer free of UI types. |
| 2 | Agree: a repository transaction is as atomic as a DAO `@Transaction`. |
| 3 | Agree, and extend per G2 (a removed-host set that Quick scan all honours whether or not the radar runs). |
| 4 | Agree. |
| 5 | Agree; amend the spec (G19). |
| 6 | Agree; amend the spec (G19). |
| 7 | Agree: the spec's test could not pass against its own `DeviceEdits.of` rule. |
| 8 | Agree: a handshake on an HTTP-set port is named by the unknown-port rule; the alternative mislabels plain TLS as HTTPS. |
| 9 | Agree: RTSP and RDP servers do not speak first, so the zero-byte rule adds nothing there. |
| 10 | Agree: the name above is a 48 dp target for the same action, and the 1.3 row floors matter more. |
| 11 | Agree, with G18's one-line widening if the Director wants it. |
| 12 | Agree for `HomeScreenTest` (run it last), but G1 must be fixed first or every class touches the database. |
| 13 | Match O, not OU (G9). |
| 14 | Agree: the only way the JVM can pin the reduced-motion blip look. |
| 15 | Agree with the mechanism; fix the test (G7). |

## Things the plan gets right that must not be lost in the fix round

- The Passive mode of the new `ServiceIdentifier` sends exactly what today's code sends; I traced every port class against the current file. The `active { }` wrapper (line 1867) makes it structurally impossible for Passive mode to send an active step. Keep that shape.
- `PASSIVE_ONLY` and `HEAD_ONLY` are pinned by value, and by a fake that fails on any byte written, in both modes (lines 1643 to 1668).
- The ladder tests assert exact call sequences, and line 1949 tells the reviewer the lists are the contract.
- Task 1's migration is additive only, `SchemaExportTest` compares v1 and v2 column by column from the exported JSON, `1.json` is protected (line 338), there is no destructive fallback, and both migration tests stamp the app's file.
- `ServiceMerge` is the single never-erase merge used by the repository, the evidence builder and the stability test; `PortMapping` is the single conversion. This closes Gruff M5 properly.
- The atomic `WHERE identConfidence <= :c` guard and the separate user columns that no scan writes.
- Task 16's three-layer structure, with angle and pulse read only in the arm's layer block and the live layer's draw, the clamped versus unclamped `fraction` table, and click enabled only at rest.
- `NetProbeNavHost` with slot screens, so no test runs a real discovery.
- Every task ends with the same three-part gate, every instrumented test compiles in its own task, and Task 18 runs the migration tests before the real migration and backs up before anything is installed.
- No em-dashes, no attribution trailers, no disclaimers or confirmation dialogs anywhere in the plan; Scan, Stop and the Detail scan bar stay outside every scroll container; the Edit dialog's buttons sit outside its scrolling body.
