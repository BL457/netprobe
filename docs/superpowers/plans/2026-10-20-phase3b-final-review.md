# NetProbe Phase 3B: final whole-branch review (Dot)

Branch `phase3b`, range `ab90057..9f1e258` (41 commits), reviewed 2026-10-20 against the spec at c79e6aa, the plan at ab90057, the ledger (rulings R1 to R9) and every task report. Read-only on the checkout; the runtime probes and the trial rebase below were run in a throwaway clone in the session scratchpad, never in `K:/claude/netprobe`.

## Verdict: READY AFTER FIXES

The engine holds up across task boundaries. Every tool request goes through `RunControl.gate()` (pause first, then a per-host 100 per second bucket). Every blocking read sits in `CancellableIo.closing` on the 16-thread tool dispatcher. Hints are picked per (tool, port) with DONE over STOPPED and written only through `setIdentityIfNotWeaker`. The schema 3 migration is additive and no test touches `netprobe.db`. All strings are in `ToolCopy` / `WordlistCopy` with templates, there are no em-dashes and no attribution, and the gate is green (875 JVM tests). Four things must be fixed before the Director picks up the phone:
- a runtime bug I proved: a Stop during a TLS handshake or an SMB exchange is recorded as FAILED with a false "no response" summary;
- a build hazard I proved: the mandatory rebase rewrites the bundled wordlists as CRLF and turns the gate red;
- two defects in the Task 20 procedure. It still streams the database with `adb shell ... < file`, which 3A's device QA proved truncates binary data on this PC, and that includes the restore procedure. It also has no rebase step, points the rollback APK at the wrong 3A commit and lacks the destructive-check rule.

All four are small. Once they are done the branch is ready for Task 20.

## Must fix before the Director tests

### I1 (Important): a Stop during a TLS handshake or an SMB exchange is written FAILED, not STOPPED

- Where: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt:137` (`execute`). The socket error is swallowed by two tools: `engine/serviceid/TlsPeek.kt:72` onwards (`attemptDetails` catches `Exception` and returns `NoConnection`) and `engine/tools/smb/SmbTransport.kt` (`TcpSmbTransport.exchange` maps `IOException` to null).
- What happens:
  - Stop cancels the run job and the `CancellableIo` watcher closes the socket.
  - The blocked read throws `SocketException`. Under the kotlinx rule that a non-cancellation exception wins over the cancellation, it comes out of `closing` as a `SocketException`, not a `CancellationException`.
  - `TlsPeek` and `TcpSmbTransport` treat it as "no answer", and the tool returns `ToolResult(FAILED, ...)` without suspending again.
  - For a host on the local subnet `ScanBinding.run` calls the block directly, so nothing re-checks cancellation.
  - `HttpClient` (an explicit `ensureActive()`) and `UdpSnmpExchange` (`ensureActive()` on `IOException`) are correct. TLS and SMB are not.
- Proof: in the scratch clone I used real loopback sockets, the real `ToolRunner` on `Dispatchers.IO.limitedParallelism(16)` and a silent peer, and pressed Stop after 500 ms:
  - TLS certificate: `(FAILED, No TLS handshake on this port)`, expected STOPPED.
  - SMB session: `(FAILED, No SMB response on this port)`, expected STOPPED.
  The same happens on a network change, so "Network changed, run stopped" is lost. The Director sees "SMB session finished: No SMB response on this port" after pressing Stop, and the history line says Failed. The existing runner test passes only because its fake read is `Thread.sleep`, which the interrupt turns into a `CancellationException`.
- Fix: one line after the tool returns. I checked it in the clone: both probes go STOPPED and every `ToolRunner*`, `BindingPropagation*`, `SmbSession*` and `TlsCertificate*` test stays green.
  ```kotlin
  bind(target.ip, run.env) { result = tool.run(target, options, Control(run), progress) }
  currentCoroutineContext().ensureActive()   // a cancelled run is STOPPED whatever the tool returned
  ```
  Add the two loopback probes as a `ToolRunnerStopMidReadTest`: a silent `ServerSocket`, `TlsCertificateTool` with the real `TlsPeek().details`, `SmbSessionTool` with the real `tcpConnect`, and an assert of STOPPED with the stopped summary. That pins the rule for any future tool that catches `IOException`.

### I2 (Important): the rebase (or any fresh checkout) turns the bundled wordlists into CRLF and the gate red

- Where: the repository root (no `.gitattributes`), `app/src/main/res/raw/wordlist_*.txt` and `BundledWordlistsTest`.
- What happens:
  - This PC has `core.autocrlf=true` in both system and global config, and the repo has no `.gitattributes`.
  - The three lists are LF in the index, and in today's working tree only because they were written by hand.
  - Anything that rewrites them in a working tree checks them out as CRLF. That includes the mandatory rebase onto `phase3a`, a new worktree and a fresh clone.
  - Proof: my trial rebase in a clone failed exactly `BundledWordlistsTest` (admin panels, api paths and common short all said "must use LF only"). After I converted the three files back to LF the full gate was green.
  - The APK would also ship whatever line endings the checkout produced. The parser copes, the test does not, and spec 5.6.1 says LF.
- Fix: add `.gitattributes` with `app/src/main/res/raw/*.txt text eol=lf` (a wider `*.txt text eol=lf` is fine too). Commit it on `phase3b` before the rebase and run `git add --renormalize .` once. Re-run the gate after the rebase.

### I3 (Important, data safety): Task 20 still streams database files with `adb shell ... < file`

- Where: `docs/superpowers/plans/2026-10-20-netprobe-phase3b.md:9751` (step 2, the migration copy) and `:9850` (the restore procedure). The same lines are in `.superpowers/sdd/2026-10-20-netprobe-phase3b/task-20-brief.md:55` and `:154`.
- What happens:
  - 3A's device QA (finding F5 in `docs/qa/phase3a-on-device.md` on `phase3a`) proved that on this Windows PC `adb shell "run-as ... sh -c 'cat > ...'" < file` writes a truncated file (4220 of 323584 bytes), because `adb shell` does not pass binary stdin intact.
  - In step 2 that only spoils the copy, and the test then fails.
  - In the restore procedure it is worse. The procedure first runs `rm -f` on `netprobe.db`, `-wal` and `-shm`, then writes truncated files. A restore done as written destroys the Director's live database. The backup on the PC survives, but he would be left with a broken app until someone re-pushes it correctly.
- Fix: in both places use `"$ADB" -s $DEV exec-in run-as $PKG sh -c "cat > databases/<name>" < "$BK/<file>.bak"`. Before going on, compare each pushed file's MD5 against its backup (`adb exec-out run-as $PKG md5sum databases/<name>` against `md5sum` on the PC), as 3A's QA did.

### I4 (Important): Task 20 has no rebase step, the wrong rollback base, an incomplete class list and no destructive-check rule

- Where: Task 20 in the plan (line 9697 onwards), `task-20-brief.md`, and `docs/superpowers/plans/phase3b-base.txt` (which contains `3d31327`).
- What and fix, in the order Task 20 should run them:
  1. Rebase first (ruling R2 and the ledger note): after I2 lands, run `git rebase phase3a` on `phase3b`. My trial rebase in a clone gave exactly two conflicts, both in commit 38fcd2b (Task 7):
     - `ui/home/RadarCollector.kt`: keep 3A's F1 body (`previousMac`, `IdentityWrite.sameDevice` and the guarded `setIdentityIfNotWeaker`), and add `hintsFor(id)` as the last argument of `HostEvidenceBuilder.build`. Keep 3B's `hintsFor` constructor parameter before `removed`.
     - `engine/identify/DeviceIdentifierTest.kt`: set the rule count to `134` (118, plus 2 from 3A, plus 14 from 3B) and the `gateway - p-dsm-title` gap to `73`, and keep 3B's two hint-position assertions.

     `identify_rules.json` merges cleanly: 3A's two rules sit after `p-unifi-cert` and 3B's 14 sit before `gateway`. After the rebase and the LF fix the full gate was BUILD SUCCESSFUL with 882 JVM tests and 0 failures, and `compileDebugAndroidTestKotlin` passed. 3A's `RadarIdentityGuardTest` compiles against the new `RadarCollector` signature.
  2. Update `docs/superpowers/plans/phase3b-base.txt` to the `phase3a` tip (`87ab3c1`). The ledger says that build is on the phone now. A rollback APK built from `3d31327` would put back a schema 2 build without 3A's device-QA fixes (the F1 identity guard, the Edit dialog, the radar drawing), so "restore" would quietly downgrade the app.
  3. Step 5 class list:
     - Add `data.MigrationTest`. 3A's 1 to 2 test now also opens through the version 3 builder, so it is a real regression check.
     - Add `data.RadarIdentityGuardTest` after the rebase.
     - Every class added on 3B is already in the list: `ToolMigrationTest`, `DirectorCopyMigration23Test`, `ToolDaoTest`, `ToolsBlockTest`, `DirBruteSheetTest`, `ToolResultsSheetTest`, `WordlistsScreenTest`, `SettingsToolsTest`, and the extended `DetailContentTest` and `HomeContentTest`.
     - `NeighbourSpikeTest` and 3A's `DirectorCopyMigrationTest` stay out on purpose. Say so in the record.
  4. Destructive-check rule, carried from 3A's Task 19: item 16 ("Remove again and let the Undo lapse") permanently deletes a host's mark, note and custom name from his real database.
     - Run it only on an unmarked, note-free, unnamed host such as a phone or a smart plug, or on a disposable network. Never use CHNAS, which is marked Interesting with a note.
     - Record which host was used.
     - Items 13 (switch Wi-Fi) and 15 (force-stop) are not destructive.
  5. Item 9 expectation: 3A's QA showed his gateways are UniFi Security Gateways (title "UniFi Security Gateway", certificate `UbiquitiRouterUI`).
     - The fingerprint rule `unifi-os` is `^UniFi OS$`, so on a USG the fingerprint names no product. Expect "nginx" or "No product named".
     - "UniFi OS" is only expected on the UniFi OS console at 10.0.0.6.
     - Either add a fingerprint rule (M4) or correct the expectation. Do not record a USG result as a failure of the tool.
  6. Add a printer item if he has one. Printers are the most fragile embedded web servers on a LAN, which makes them a good adaptive-concurrency check. They are also the hosts most likely to answer SNMP `public`, so they are the realistic SNMP walk target (DSM ships with SNMP off).

## Minors to carry

### Fix now (small, before the build goes on the phone)

- **M1** `engine/serviceid/TlsPeek.kt:72`: `CancellableIo.closing(s)` closes the `SSLSocket` from the cancelling thread, and for the Stop pill that is the main thread. Conscrypt's close of a socket mid-handshake can go wrong in two ways:
  - It can try to send an alert. That is network I/O on the main thread, which StrictMode's default `penaltyDeathOnNetwork` turns into an exception. `runCatching` swallows it, so the read runs on to its 3 s timeout.
  - It can wait on the handshake lock.

  `HttpClient` already does the right thing by closing the plain `raw` socket; pass `raw` to `closing` here too. This is the ledger's parked "TLS close may block briefly on the cancelling thread". It cannot be shown on the JVM (SunJSSE), so it also goes on the Task 20 watch list.
- **M2** Remove device against a tool starting in the gap (`ui/detail/DetailViewModel.kt:283` and `:165`). The guard reads `hostBusy` once before the launch, so a chip tapped just before Remove can claim the host after the check. Two outcomes are possible:
  - The run row is cascaded away under a live job, and its findings fail their foreign key and are logged.
  - If the start lands before the snapshot, Undo restores a `RUNNING` row that stays "Running" until the next launch.

  Two lines close the gap. In `start`, return when `hostId in c.removedHostIds.value`. In `removeDevice`, re-check `removeAllowed` inside `hostWriteLock` before the snapshot, and bail out (clearing this call's marks) if a tool now holds the host. The appScope exception handler means none of this can crash the app.
- **M3** `app/src/main/res/raw/wordlist_common_short.txt:227` keeps `setup.php`, although ruling R7 removed `install` and `install.php` for the same reason (an installer on an unconfigured app). Remove `setup.php` and update `BundledWordlistsTest`'s count and deny pattern. `setup` and `setup.html` are view pages on the devices I know and can stay; that is the Director's call.
- **M4** `app/src/main/res/raw/http_fingerprints.json`: add `{"id":"unifi-usg","source":"title","pattern":"UniFi Security Gateway","name":"UniFi Security Gateway",...}` with a sample, so the fingerprint of his gateways names something. No hint rule is needed: after the rebase, 3A's `p-unifi-usg` already identifies a USG from the radar title.

### Task 20 watch list (device only)

- TEMP-table snapshot on the real WAL database. Remove a host with tool history and tap Undo: the history should be back with the same run ids. Then Remove again and let it lapse, on a disposable host (I4.4). In-memory tests cannot prove the single-connection assumption.
- Stop during a TLS certificate run against a slow port, after I1 and M1. Expect STOPPED within about a second and no jank on the Stop tap.
- On the Windows PC, open Event Viewer, Security log, and note the events one SMB session run leaves. Expected: 4624 or 4625 for the anonymous logon and 4625 for "guest", plus 4776 on the domain controller if the PC is domain-joined. Record the count against the "2 per run, 4 with a pause retry" expectation.
- Font scale 1.3 budget of spec 8.9 (five port rows with the Tools block collapsed). No instrumented test covers it, only Task 20 item 17. The options sheet footer at 1.3 is covered by `DirBruteSheetTest`.
- Quick scan all skipping a tool-held host and counting it (item 11). The claim, skip, count and release logic in `HomeViewModel.quickScanAllBody` has no JVM test, although spec 12.1 asked for one, so the device check is the only proof.
- Two runs ending close together. `AppMessages` holds one message at a time, so the first end message can be replaced before it is seen (spec 4.3 design, parked in Task 4).
- A pause during the HTTP fingerprint can repeat a fetch up to four times (name, IP, then both again after the resume). That is fine, but note it if a log shows doubled requests.
- The text/plain SAF filter (item 12) may hide `.lst` or extensionless lists (spec 14.3).
- Smaller device checks carried from the ledger:
  - the `PRAGMA foreign_keys` cascade check needs autocommit;
  - push a fresh copy before every `DirectorCopyMigration23Test` run, because the test deletes the copy;
  - the results-sheet long-press test targets the row centre;
  - check that the `StepSlider` inactive track is visible on the sheet;
  - check the options sheet with the keyboard up at the 0.9 height cap.
- Spec 8 motion: finding rows that arrive during a live run have no "rows arrive" treatment, and the Tools block expands without animation. Both are instant, so reduced motion is honoured by default. Record whether the Director wants the motion.

### Later phase (record, no action now)

- The production `RateBucket` and `activeMs` read the runner's wall clock (`ToolRunner.kt:35`, `:227`), not the monotonic default the Task 4 review asked for. A backwards clock step can show a negative elapsed time for a moment. Give `Run` its own monotonic clock; `startedAt` and `finishedAt` stay wall time.
- Calibration does not use the excused rule. A sample lost to a pause is retried once after the gate, but if the pause lands in the first second a group can be calibrated from fewer samples (Ada's Task 10 concern).
- Some 3xx catch-alls put the requested path, URL-encoded, in their `Location` (`/login?next=%2Fabc`). That path is not normalised, so the response reaches the runaway check instead of the band (spec 5.4.2 wording). Servers that redirect everything also hide real directories (spec rule).
- The fingerprint and TLS name-then-IP retries, and SMB's 6 requests on 4 gates, are not one token per request. All of these are sequential, with at most one extra request per step.
- SNMP:
  - there is no whole-walk deadline, so the worst case is about 400 exchanges of up to 2 s, roughly 13 minutes against a slow agent;
  - a late reply to attempt 1 is dropped;
  - empty values become findings;
  - the discovery `parseResponse` off-by-one is left for 3C.
- Parked items carried unchanged:
  - Tests:
    - the ExcusedOnceTest dead predicate;
    - no CancellableIo block-throws or late-result tests;
    - Stop while parked on the paused flow is untested;
    - the SAN-cap truncation is untested.
  - Engine and data:
    - obs-fold header lines;
    - a cancelled start caller leaves a RUNNING row until the next launch;
    - `reidentifyFromTools` ignores `removedHostIds`;
    - relative Locations without a slash are not followed;
    - the `isReset` heuristic;
    - the SNMP message-level length;
    - the session base key, for 3C.
  - Wordlists:
    - the WordlistParser dead line;
    - recovered lists are not checked against `MAX_ENTRIES`;
    - delete removes the file before the index write;
    - `withEntries` reads on `Dispatchers.IO` (one producer, not a starvation risk).
  - UI:
    - `serverNameSent` is cosmetic;
    - `shareRun` ignores a false return;
    - there is no per-block running marker;
    - an empty port list with tools hides the hint;
    - rotation mid-import resets the Importing label;
    - the import coroutine holds the Activity context until it ends;
    - `SettingsScreen` resolves the wordlist store twice.
- The ledger parked "AppContainer `{ t -> tcpConnect(t) }` lambda is not covered by BindingPropagationTest". I read it. It calls `SmbSessionTool.tcpConnect`, which calls `Probe.connect(..., keepOpen = true)` with no binder argument. So `currentBinder()` from the run's `BoundNetwork` context applies, which is the same seam the test pins. Closed.

## Cross-task trace (Review Focus 1 to 5 and checks A to H)

- **RF1 browser trip.** Holds, with the calibration caveat above.
  - The `MainActivity` observer sets `foreground`, and the runner's collector pauses every run under `pauseLock` (epoch up, clock stopped).
  - `gate()` re-checks the app flag and the run's own `paused` flow on every pass, so a worker parked in its rate wait never sends in the background. The resume empties the bucket.
  - Brute-force reads the epoch before the gate. On an excused failure it calls `continue` without counting it, touching the limiter or touching the consecutive count.
  - The four companion tools wrap every exchange in `excusedOnce`. SMB's guest leg uses it only on a lost reply, so a pause never causes a second 4625.
- **RF2 not-found filter.** Holds.
  - 5 random GETs per group, and a band per status with `pad = max(32, max - min)`.
  - Content-Length is preferred over body size, and Location is normalised.
  - A HEAD/GET disagreement forces GET only and is written to `methodUsed`.
  - The runaway check runs at exactly 200 completed paths.
  - The R9 dead-host exit comes after at most 4 root attempts.
- **RF3 IO starvation.** Holds.
  - `toolDispatcher = Dispatchers.IO.limitedParallelism(16)`, and `CancellableIo` has no dispatcher argument.
  - A grep finds `Dispatchers.IO` in `engine/tools/**`, `HttpClient` and `CancellableIo` only in comments.
  - The one IO use on the tool path is `WordlistStore.withEntries`, the single producer.
- **RF4 hints.** Holds after the rebase.
  - `ToolHintsRule.pick` groups by (host, tool, port) and takes the newest DONE run, else the newest STOPPED or INTERRUPTED one.
  - FAILED and RUNNING runs never reach `finishedRunsOnce`.
  - Pruning per (host, tool, port) (R5) can no longer delete another port's only DONE run.
  - `onFinished`, the Detail scan and Quick scan all write through `setIdentityIfNotWeaker`.
  - Discovery passes hints for `hostsWithRuns()` and, before the rebase, always writes. The rebase brings 3A's F1 guard, which closes the one gap: a host whose first tool run finished mid-radar could lose that identity to the radar's unguarded write.
- **RF5 migration.** Holds.
  - `MIGRATION_2_3` is four `CREATE ... IF NOT EXISTS` statements.
  - `1.json` and `2.json` are unchanged, and `3.json` is committed.
  - No androidTest opens, creates or deletes `netprobe.db`. The only uses are read-only `getDatabasePath(DB_NAME)` stamps in the two migration tests.
  - `NetProbeDb.open` adds both migrations and no destructive fallback.
- **C network safety, per run, with defaults.**
  - Brute-force:
    - calibration sends at most 16 requests (96 with 8 extensions);
    - then one HEAD per path, a GET only for report-set statuses, and one retry for Timeout or Reset;
    - common-short is 294 paths, so roughly 300 to 900 requests, each on its own TCP connection (`Connection: close`);
    - at most 16 in flight, at most 110 in the first second and then 100 per second;
    - the Host header is always the IP.
  - Fingerprint: 5 GETs (6 with the hop).
  - TLS: 1 handshake (2 with the IP retry).
  - SNMP: at most 400 GET or GETNEXT exchanges of up to 2 datagrams each, only when discovery got an SNMP answer to `public`, and never SET.
  - SMB: 2 TCP connections to 445, each with NEGOTIATE and two SESSION_SETUP messages and nothing after.
  - Nothing is sent without a chip, port-sheet row or Run tap, and port-scan re-identification never starts a tool.
  - Pause stops new requests, and a run killed with the process shows INTERRUPTED on the next launch.
  - Stop: within about a second for HTTP and SNMP. TLS and SMB also end fast, but are mislabelled until I1 is fixed.
- **D concurrency.** Holds; the one gap is M2.
  - `HostBusyRegistry` allows one run per host. The claim is atomic under `startLock` and released in `end()` on every path, including a throwing `startRun` and a failed registration.
  - The Detail port scan and Quick scan all claim `PORT_SCAN` and release it in `finally`.
  - Runs start ATOMIC behind the `registered` latch, and the ticker lifetime is guarded by `tickerLock`.
  - `appScope` has a logging exception handler.
  - `AppContainer` has no `init`. It touches the database only through the sanctioned `markInterrupted` on the first `toolRunner` read, which `NetProbeNav` triggers.
- **E UI.**
  - Stop is the pinned scan-bar pill, the options sheet's Run pill is in a fixed footer, and the Wordlists Import pill is in the pinned top bar.
  - The chips are in the scrolling list (G25, the Director's call).
  - There are no string literals in the new composables and no disclaimers.
  - A byte grep of the whole diff and every commit message finds 0 em-dashes.
  - The only new dialog is the wordlist delete `AlertDialog`.
  - The indeterminate bar is static under reduced motion, and Home shows the Undo notice before any app message.
- **F hygiene.** Commit messages are plain (the attribution grep is empty). There is no change to `app/build.gradle.kts`, `gradle/libs.versions.toml` or any Gradle file. `phase3b-base.txt` is present but needs the update in I4.2.
- **G tests.** The gate is below and the class list is in I4.3. The behaviour checks cover CHNAS, the UniFi hosts, the Pi-holes, the Windows PC and the Hue bridge, but not a printer (I4.6). The backup-first migration, the copy test and the restore procedure are present but need I3.
- **H parked items.** Split as above: M1 and M2 are promoted to fix-now, and the rest go to Task 20 or a later phase.

## Spec amendments to record (spec c79e6aa)

1. 7.2 and 7.4, ruling R5: prune per (host, tool, port) by `id DESC`. The growth bound becomes 10 runs per (host, tool, port), not 50 per host.
2. 5.6.1, plan review G10 and ruling R7: the action endpoints and `install`, `install.php`, `deploy`, `sync`, `ping` and `api/ping` are removed. The lists have 294, 153 and 135 entries, each in one globally sorted file (plan deviation 5), and `BundledWordlistsTest` enforces the deny pattern.
3. 5.6.3, ruling R8: a missing or corrupt index adopts the surviving `<uuid>.txt` files as "Recovered list N" instead of deleting them, and unreadable files are kept.
4. 5.4.1, ruling R9: when HEAD / and GET / both fail after their retries, calibration ends at once (at most 4 timeouts). Calibration retries a lost sample after the gate rather than applying the excused rule.
5. 6.3 and 4.8: the guest leg has a Lost outcome. Only a lost reply is excused and retried, never a refusal. A lost guest reply with no pause reads "Guest refused". SMB uses 4 gates for its 6 requests.
6. 7.4, plan deviation 1: Forget this network takes no tool snapshot, because 3A's Forget is a soft delete until the Undo lapses.
7. 4.6 and 4.9: `HttpFailure.Handshake` exists. The name-then-IP retry happens only on a TLS handshake failure (an `SSLException` in the cause chain), never on refused, reset or timed-out connections, and the TLS tool retries only after a connect has succeeded.
8. 9: new strings "Wordlist not found" (run failure), the summary prefix "Wordlist not found, common-short used", "Loading wordlists", "Recovered list N", the "Expand" click label and the " s" unit. Quick scan all's old "device(s)" wording is replaced by `ToolCopy.quickEnd`.
9. 6.4: the 400 cap counts exchanges (each up to 2 datagrams), and an excused retry counts against it. ifHighSpeed GETs stop after the first one fails. INTEGER and the unsigned types are 1 to 5 bytes.
10. 4.7: after the rebase, discovery no longer "always writes" for the same device (3A's F1 guard). A new MAC on the IP still writes unconditionally.
11. 5.3 and 12.1, plan deviation 10: the bucket admits its burst on top of the rate, so 110 in the first second and then 100 per second.
12. 4.1 and 4.4: the `ToolStore` interface, `ToolProgress.option`, `ToolOptions.DirBrute(options, wordlistName, wordlistEntries)` and `RunControl.excusedOnce` (plan deviations 2 and 13).
13. 4.3 and 8.8:
    - Home keeps an app message on the notice line until it is dismissed; Detail shows it as a snackbar and dismisses it.
    - A Detail port scan is refused by any work on the host, with the port-scan wording when another scan holds it.
    - Quick scan all skips a host held by another port scan silently (plan deviation 14).
14. 4.4 rule 4 (after I1): a cancelled run is STOPPED whatever the tool returns.

## Gate

- Controller's gate at 9f1e258 (`final-gate.log`): BUILD SUCCESSFUL, 875 JVM tests, 0 failures.
- My re-run at 9f1e258 in `K:/claude/netprobe`: the first run was all UP-TO-DATE, so I re-ran with `:app:testDebugUnitTest --rerun :app:assembleDebug :app:compileDebugAndroidTestKotlin`.
  - Result: BUILD SUCCESSFUL in 23 s, with 875 tests, 0 failures, 0 errors and 0 skipped, counted from the fresh XML results.
  - The androidTest compile was up to date and green.
- Trial rebase onto `phase3a` in a scratch clone:
  - 2 conflicts (see I4.1);
  - the gate was red only on the CRLF wordlists (I2);
  - after converting them to LF: BUILD SUCCESSFUL, 882 JVM tests, 0 failures, androidTest compile green.
- Instrumented tests: compile only (no phone), as every task recorded.

## What the Director should know

**What each tool sends to a host.** Nothing goes out until you tap one of three things: a tool chip, a "Run tool" row in a port's long-press sheet, or Run in the brute-force sheet. Every tool is held to 100 requests a second per host and pauses while NetProbe is in the background.
- Directory brute-force:
  - It starts with a short calibration of about 16 requests (HEAD and GET of `/`, and 5 random paths).
  - Then it sends one HEAD per path, and a GET only for paths that answer a reported status.
  - With common-short (294 paths) that is roughly 300 to 900 requests, each on its own connection.
  - It runs up to 8 at once by default (16 at most) and halves that when the server starts timing out.
  - The Host header is the IP address.
  - It fills the target's web access log with hundreds of 404s.
- HTTP fingerprint: 5 GETs (`/`, `robots.txt`, `sitemap.xml`, `security.txt` and one random path), plus one more if `/` redirects on the same address. It sends the device's mDNS or reverse-DNS name as the Host header when it has one.
- TLS certificate: one TLS handshake, plus a second without the name if the named one fails. Nothing else.
- SNMP walk: offered only for hosts that already answered SNMP community `public` during discovery. It sends read-only GET and GETNEXT requests, at most 400, and never SET.
- SMB session: two TCP connections to port 445. Each sends NEGOTIATE and two SESSION_SETUP messages and then closes. The first connection logs on anonymously and the second as "guest" with an empty password. It never connects to a share, and never reads or writes anything.

**SMB leaves logon events.**
- On a Windows host each run normally records two Security log events:
  - 4624 for a successful logon (for example ANONYMOUS LOGON);
  - 4625 for a failed logon (for example "guest").
- A run interrupted by a trip to the browser can add up to two more.
- On a domain-joined PC the domain controller may also log 4776.
- A Synology NAS logs the attempts in its connection log.

On a client network an administrator will see these, and an intrusion detection system may alert. Run the SMB probe and the brute-force only where the engagement allows it.

**Wordlists.**
- Three lists are bundled, written for this project rather than taken from SecLists: common-short (294 entries), admin-panels (153) and api-paths (135).
- Anything whose GET can change a device was removed: reboot, upgrade, apply, setup.cgi, logout, save, install, install.php, deploy, sync and ping. I recommend also removing `setup.php` (M3).
- These were kept because a GET only shows them: diag.html, firmware, backup files (a GET of a backup downloads at most 64 KB), export, cloudcmd, debug/pprof and actuator/threaddump.
- You can import your own plain-text lists from the phone, up to 10 MB and 200000 entries.
- A SecLists subset needs your approval of the download.

**The chips in the scrolling list (G25).** Stop is pinned in the scan bar, as the standing rule requires. The chips that start runs sit inside the Device screen's scrolling list (the Tools block is its first item), as the spec places them. Your call: keep them, or move tool starts out of the list.

**Hints feed identification.**
- Tool results become hints, for example "this port runs Synology DSM", kept per tool and per port.
- They only ever add evidence, and tool-triggered updates never lower an identity. So a fingerprint of port 80 cannot hide what a fingerprint of port 5000 found.
- Rules exist for:
  - web pages: Synology, Pi-hole, UniFi OS, Hue, Home Assistant, Proxmox and OpenWrt;
  - certificates: Synology and Ubiquiti;
  - SNMP object ids: Synology, MikroTik, Ubiquiti, Cisco and net-snmp.
- No rule uses the SMB hints yet; that comes in 3C.
- The fingerprint will not name your UniFi Security Gateways until the rule in M4 is added.

**Schema 3 and your data.** The update adds two tables for tool runs and findings, and changes nothing in your existing hosts, ports, marks or notes. Task 20 does it in this order:
1. Back up your database to the PC.
2. Migrate a copy and check every mark and note.
3. Only then install over your current build.

If anything is wrong, the restore puts the previous build and the backup back. That depends on I3 and I4.2 being fixed first: the current restore steps would write a truncated database on this PC, and would roll back to an older 3A build than the one on your phone.

**The 3A rebase.** Phase 3B was built on 3A before your device-QA fixes (the F1 identity guard, the Edit dialog and the radar drawing), so it must be rebased onto `phase3a` before Task 20. I did a trial run: there were two small conflicts with clear resolutions, and everything passes afterwards once the wordlist line endings are pinned (I2). The merge order stays as ruled: 3A first, then 3B after its device QA.
