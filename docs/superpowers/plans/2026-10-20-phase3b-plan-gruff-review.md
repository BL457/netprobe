# Phase 3B plan: Gruff review

Reviewer: Gruff (Devil's Advocate). Plan under review: `docs/superpowers/plans/2026-10-20-netprobe-phase3b.md` (413ca5d, 9248 lines, 18 tasks). Binding spec: `docs/superpowers/specs/2026-10-20-phase3b-followup-tools-design.md` (c79e6aa). My spec review: `docs/superpowers/specs/2026-10-20-phase3b-gruff-review.md`. Names checked against the actual code on branch `phase3a` (3A Tasks 1 to 15 committed), not only the 3A plan.

## Verdict

4 BLOCKING, 14 SHOULD FIX, 8 MINOR. Overall: **execute after fixes**. This is a strong plan. The byte layouts are written out and they add up, the engine is fake-driven, every 3A name it uses exists in the code with the right signature, and the migration is additive and well tested. But four things must change before an implementer touches it. First, Task 2 replaces 3A's Remove-device body with an older form, which undoes two committed 3A review fixes and reopens the "Undo silently loses the Director's marks" hole. Second, the `ToolRunner` tests, which pin Review Focus 1, use `advanceUntilIdle()` on `backgroundScope` work, so they fail as written. The project's own `HomeViewModelTest` documents why. An implementer under pressure will bend them. Third, the 100 requests per second ceiling is enforced per path, not per request, so a catch-all server gets up to 200 per second. Fourth, `HttpClient` sets no read timeout until after the TLS handshake, so a device that accepts TCP and stalls TLS hangs a run forever with the screen kept on. Each fix is small and local. Nothing needs a rewrite.

## Findings

### G1. Task 2 regresses two committed 3A fixes in `removeDevice`
- Severity: BLOCKING
- Location: Task 2 Step 3, lines 568 to 585.
- Problem: the replacement body marks the radar skip with `if (c.radarScanNetworkId.value == h.networkId)`. The committed code uses `c.radarScanActive.value` (3A review M2: a Remove during the radar's start-up window, before the network id is known). The replacement `restore` also clears `removedHostIds` and `removedDuringScan` outside `hostWriteLock` and not in `finally` (3A review M3). If `hosts.restore` throws, the host stays skipped by every later scan, and `toolRepo.restore` never runs.
- Evidence: `DetailViewModel.kt` lines 129 to 151 on the branch: `if (c.radarScanActive.value) ...` and `try { c.hosts.restore(snap) } finally { ...update... }` inside `withLock`. The plan text says "Replace the body of the `c.hostWriteLock.withLock { ... }` and the `PendingUndo` with" a version that has neither.
- Resolution: rewrite the Task 2 snippet as a minimal additive edit to the committed body. Take the token before the lock. Keep `radarScanActive` and the in-lock `try/finally` mark clearing. Inside that same `try`, after `hosts.restore(snap)`, call `toolRepo.restore(token)` (or fold both into one transaction). The commit lambda drops the snapshot.

### G2. The `ToolRunner` tests cannot pass as written: `advanceUntilIdle()` ignores `backgroundScope` work
- Severity: BLOCKING
- Location: Task 6 `ToolRunnerTest` (lines 2117 to 2215) and `ToolRunnerPauseTest` (lines 2235 to 2274). The note at line 2570 is wrong.
- Problem: every `Rig` launches the runner, its collectors, its runs and its ticker on `backgroundScope`. Since kotlinx-coroutines-test 1.7, `advanceUntilIdle()` returns once only background work is queued. I checked the 1.10.2 jar on this machine: `advanceUntilIdle` calls `advanceUntilIdleOr` with the foreground condition. So after `gate.complete(Unit); advanceUntilIdle()` nothing runs, and `store.finished`, `finishedHosts`, `messages.current` and `passed.isCompleted` stay empty. That breaks eight of the thirteen runner tests, including all three Review Focus 1 pause tests. The obvious workaround, using the test scope itself, hangs instead, because the ticker reschedules forever while a run is live.
- Evidence: `app/src/test/.../ui/home/HomeViewModelTest.kt` line 13: "runCurrent, not advanceUntilIdle: since kotlinx-coroutines-test 1.7, advanceUntilIdle returns once only backgroundScope work is left."
- Resolution: in every runner test, replace `advanceUntilIdle()` with `runCurrent()` (and `advanceTimeBy` where a delay is involved). Fix the line 2570 note to match. Tell the implementer that green tests reached any other way are bent tests.

### G3. The 100 requests per second ceiling is enforced per path, not per request
- Severity: BLOCKING
- Location: Task 10 `attemptCounted` and `attempt` (lines 4815 to 4848). Spec 5.3, the Global Constraints (line 44), and `RunControl.gate` ("before every request").
- Problem: `control.gate()` takes one token per loop iteration, but `attempt()` can then send HEAD and GET (every hit, and every path on a catch-all that answers HEAD 200) or HEAD 405 and GET (the fallback). On exactly the embedded catch-all servers M3 was written for, the run sends up to 200 requests per second. The spec 12.1 test "the rate ceiling holds at or under 100 requests in every 1 s window" is absent; `RateBucketTest` tests only the bucket.
- Evidence: there is one `control.gate()` per `while (true)` iteration, and `exchange.request` appears twice inside `attempt`.
- Resolution: pass `control` into `attempt` and call `gate()` before each `exchange.request`, including the GET after a HEAD. Add a `DirBruteFlowTest` with a fake clock that counts exchanges per window on a HEAD-200 catch-all and asserts at most 110 in the first second and at most 100 in any later one.

### G4. `HttpClient` has no read timeout during the TLS handshake
- Severity: BLOCKING
- Location: Task 5 `exchange` and `layerTls`, lines 1879 to 1904.
- Problem: `Probe.connect` never sets `soTimeout` (`Probe.kt` sets none). `layerTls` calls `startHandshake()` on the raw socket, and `socket.soTimeout = timeoutMs` comes after it. A device that accepts the TCP connection but never answers the ClientHello blocks every brute-force worker and the fingerprint forever. The 2x overall deadline starts after the handshake. Stop still works, but an unattended run never ends, never reports "the server stopped answering", and `KeepScreenOn` holds the screen on. Embedded TLS stacks that stall are exactly what a client network has. `TlsPeek.details` (Task 12) does set the timeout first, so the two tools behave differently.
- Evidence: plan line 1880 calls `layerTls` before line 1881 sets `soTimeout`. No HTTPS stall test exists (`TlsLoopbackTest` only covers handshakes that succeed).
- Resolution: set `raw.soTimeout = timeoutMs` before `layerTls`. Add a loopback test where a plain `ServerSocket` accepts and stays silent, and the `tls = true` request returns `Failed(Timeout)` within about 2 x timeout.

### G5. Companion tools ignore the pause excuse rule
- Severity: SHOULD FIX
- Location: Tasks 11 to 14 (`HttpFingerprintTool.run`, `TlsCertificateTool.run`, `SnmpWalkTool.Walk.next`, `SmbSessionTool.run`). Spec 4.8 bullet 4 and spec 6 intro.
- Problem: only brute-force uses `control.excused`. If a fingerprint or SNMP walk is in flight when the Director opens a found URL, a frozen read times out and the tool reports "No HTTP response on this port" or ends a step. That is the Review Focus 1 failure, just on a smaller tool. There is no test.
- Resolution: a shared helper `suspend fun <T> excusedOnce(control, block)` that records the epoch, runs the block, and retries once after `gate()` when `excused(epoch)` and the result is a failure. Use it in all four tools, with one fake-epoch test per tool.

### G6. The binding-propagation test the spec requires is missing
- Severity: SHOULD FIX
- Location: spec 12.1 "Binding propagation". It is in no task. The Self-Review (line 9207) claims full 12.1 coverage.
- Problem: routing tool sockets over Wi-Fi through `BoundNetwork` is the VPN safety guarantee on client networks. The design looks right (`Probe.connect` and `currentBinder()` everywhere, and workers inherit the context), but nothing pins it. One stray `withContext(EmptyCoroutineContext + Dispatchers.IO)` refactor would silently route a client's traffic down a VPN.
- Resolution: add a JVM test in Task 14 (the last tool). Under `withContext(BoundNetwork(recordingBinder))`, run each production tool factory against loopback and assert every socket reached the binder. A throwing binder must give FAILED "Tool skipped: could not bind to Wi-Fi" with no unbound socket opened.

### G7. Task 18 never exercises the copy-migration on the Director's data
- Severity: SHOULD FIX
- Location: Task 18 Steps 1 to 3 (lines 9123 to 9135) and the restore paragraph (line 9192).
- Problem: Step 2 streams the backup into `netprobe-migration-copy.db`, but no test reads that copy. `ToolMigrationTest` only migrates helper-created databases. 3A had `DirectorCopyMigrationTest` for this reason. Step 1 (the rollback APK build) and the restore procedure are references to "as 3A Task 19", with no commands. The executor is a subagent that sees only this task.
- Evidence: grep finds no test using "netprobe-migration-copy.db" in the plan.
- Resolution: add `DirectorCopyMigration23Test` (androidTest, compile only in Task 1). It opens the copy through `NetProbeDb.open(context, "netprobe-migration-copy.db")` and asserts host, note, status and port counts are unchanged and the two new tables exist. Write the worktree build commands and the three-file restore commands out in full.

### G8. `optionsJson.methodUsed` never records the calibration switch (deviation 4)
- Severity: SHOULD FIX
- Location: Task 10 `ToolOptionsJson` line 4452; deviation 4.
- Problem: spec 7.1 and 7.5 require the export to say "GET only (HEAD and GET disagree)" when calibration switched methods. The plan writes the chosen method at start, so `ToolCopy.optionsLine(switchedToGetOnly = true)` and its copy-table template can never come from real data. The test at line 3259 builds the switch by hand.
- Resolution: follow the spec. Add `ToolProgress.option(key, value)` (or a `ToolResult.methodUsed`) that the runner writes into `optionsJson` with one `UPDATE`. It is a few lines and one runner test.

### G9. Forget this network is not disabled while a tool holds a host on that network
- Severity: SHOULD FIX
- Location: spec 4.3 bullet 5 and 7.4. Tasks 15 to 17 guard only Remove device (lines 7806 and 8573).
- Problem: deviation 1 (no Forget snapshot) is the right call, because 3A's Forget is a soft delete (confirmed in code: `setForgotten`, `forgottenNetworkId`). But if the Undo commits while a tool runs on that network, the cascade deletes the run's row under the live job, later `addFinding` calls fail the foreign key (logged), and the end write is a silent no-op.
- Resolution: in Task 17, disable the Home "Forget this network" item while `hostBusy.busy` holds any host of that network, as the spec says, and add one Home content test.

### G10. Bundled wordlists contain state-changing endpoints
- Severity: SHOULD FIX
- Location: Task 8, admin-panels list (lines 3491 to 3650): `reboot.html`, `upgrade.html`, `apply.cgi`, `setup.cgi`, `cgi-bin/webproc`.
- Problem: under HEAD then GET, any of these that answers in the report set gets a plain GET. On some consumer routers and appliances a GET to an action CGI or a reboot page acts on it. A brute-force that reboots a client's router is the one thing that would end an engagement. The value of these entries is low: a panel path finds the panel anyway.
- Resolution: drop `reboot.html`, `upgrade.html`, `apply.cgi` and `setup.cgi` from the bundled lists. Add a `BundledWordlistsTest` deny-list assertion (reboot, restart, shutdown, reset, factory, upgrade, apply, logout) so they cannot come back. Imported lists remain the Director's choice.

### G11. Task 8 is malformed and its own list fails its own test
- Severity: SHOULD FIX
- Location: Task 8, lines 2751 to 3055 and 3185 to 3487.
- Problem: a full copy of the common-short list sits where the Step 1 heading and the `WordlistParserTest.kt` path should be (lines 2755 to 3054, outside any heading). In the copy to transcribe (Step 3), `repo` comes after `reports`, so `LC_ALL=C sort -c` fails at that line and `BundledWordlistsTest` fails. The "machine-checked while writing" claim (lines 2747 and 3799) is false. The stray copy is sorted correctly, so the two copies differ.
- Evidence: I extracted both copies: `diff` shows only the moved `repo` line, and `sort -c` reports "disorder: repo" on the Step 3 copy. The counts (300, 160, 137) are inside the spec ranges.
- Resolution: delete the stray copy, restore the Step 1 heading and the test path, and move `repo` before `report`. Re-run the sort check on the edited plan.

### G12. Tests that cannot fail, or do not test what their names claim
- Severity: SHOULD FIX
- Location and problems:
  - `WordlistStoreTest.withEntries closes the reader even when the block is cancelled` (line 3891) cancels nothing and checks no close. "Deleting a list during withEntries lets the block finish" (spec 12.1) is absent.
  - `WordlistStoreTest.too many entries and empty are rejected` (line 3880) tests only `Empty`.
  - `DirBruteFlowTest.the load limiter never exceeds the chosen concurrency` (line 4398) also passes with no limiter, because the worker count equals the concurrency. It tests the worker count, not the limiter.
  - `HttpClientTest.get request bytes are exact with the ip host and no port when 80` (line 1732) never uses port 80, so the omit-default-port branch is untested. Spec 12.1's "server that drips bytes" and "no framing, connection kept open, ended by the deadline" cases are missing.
- Resolution: make each test fail on the bug it names. Cancel the `withEntries` block with a closing-tracking stream. Feed 200,001 entries for TooMany. Assert the in-flight peak drops to the halved limit after an injected timeout. Add the port 80/443 Host test plus the drip and keep-open deadline cases.

### G13. `RadarCollector` parameter position breaks its trailing-lambda call sites
- Severity: SHOULD FIX
- Location: Task 7, line 2723.
- Problem: `RadarCollector`'s last parameter is `removed: () -> Set<...>`, passed as a trailing lambda (`HomeViewModel.kt` line 213) and in 3A's `RemoveDeviceTest` factory. Appending `hintsFor` as the new last parameter rebinds that trailing lambda to `hintsFor`, which is a compile error. The plan's "it still compiles" claim holds only if the new parameter goes before `removed`. Also, `hintsFor(id)` runs two queries per radar emission inside `hostWriteLock`, which lengthens the lock that Remove waits on.
- Resolution: state the exact position (before `removed`). Pass the hints only when the host has tool runs, for example via a cached `Set<Long>` of host ids with runs, refreshed per scan.

### G14. `ToolRunner.start` assigns `job` after launching it
- Severity: SHOULD FIX
- Location: Task 6, lines 2430 to 2431.
- Problem: the run is in `runs` before `run.job` is set. A Stop, or an env change, in that window calls `job?.cancel()` on null and is lost, so the run continues on a new network. The window is small, but the network-change case is exactly "runs on the wrong network".
- Resolution: `scope.launch(dispatcher, start = CoroutineStart.LAZY)`, assign, then `start()`. Do it inside `startLock` so the env collector sees the job.

### G15. `HttpClient` reads one unbuffered byte at a time and caps headers only per line
- Severity: SHOULD FIX
- Location: Task 5, `HeadReader` and the readers (lines 1936 to 1976).
- Problem: on a plain socket, `input.read()` per byte is one system call per byte (64 KB means 65,536 calls per GET). `line()` also has no length cap, so a server that streams bytes without a newline grows a `StringBuilder` until the 2x deadline. On a fast LAN that is tens of MB per worker across 16 workers.
- Resolution: wrap the stream in `BufferedInputStream(…, 8192)`, and have `line()` return Malformed once `headBytes > MAX_HEADER_BYTES` inside the loop. Add a test with a 1 MB header line and no LF.

### G16. Three tasks are too big for one implementer context
- Severity: SHOULD FIX
- Location: Task 10 (lines 4057 to 4922, about 870 lines), Task 14 (6310 to 7189, about 880), Task 16 (7826 to 8624, about 800).
- Problem: subagent-driven execution relies on each brief fitting comfortably in context alongside the code it edits. Task 14 mixes pure crypto, wire builders, transport and the sequence. Task 16 edits four 3A composables plus three new sheets.
- Resolution: split Task 14 into 14a (MD4, NTLM, SPNEGO with the published vectors; it stops here if a vector fails) and 14b (SMB2 builders and parsers, framing, transport, the tool). Split Task 16 into 16a (Tools block, pinned bar, port-sheet group, `DetailContent` wiring) and 16b (options sheet and results sheet). Task 10 can stay whole on the stronger model, or split into the pure filter, calibration and limiter, then the flow. Task 8 is transcription and fine for the cheaper model once G11 is fixed.

### G17. 3B silently depends on unfinished 3A tasks
- Severity: SHOULD FIX
- Location: Task 16 lines 8549 and 8591 ("3A's Task 18 progress glide", "row-arrival modifiers"), Task 17 line 9065 ("3A Task 18 form" of the notice `when`).
- Problem: on the branch, 3A Task 16 is in progress and Tasks 17 and 18 are not built. Tasks 16 and 17 of 3B edit code in the shape those tasks leave it. The spec says 3B starts after 3A is merged, but no task checks it.
- Resolution: add a Step 0 to Task 1: confirm 3A's final task is committed and its final review is recorded, else stop. Have Tasks 16 and 17 re-read the current `DetailComponents.kt`, `HomeContent.kt` and `ScanBar` before editing.

### G18. Running SMB and fingerprint probes on client hosts leaves visible traces
- Severity: SHOULD FIX (Director awareness, not code)
- Location: Task 14 and spec 13.
- Problem: the SMB session probe is bounded and safe: two TCP connections, NEGOTIATE plus at most two SESSION_SETUPs each, a 3 s timeout per exchange, a 64 KB cap, closed after, and nothing past SESSION_SETUP. But on Windows targets the anonymous and guest attempts appear in the Security event log as logon events, and a client's SIEM may alert on them. A brute-force is equally loud in web logs. That is expected for an assessment tool, but the Director should not learn it from a client.
- Resolution: add one line to the spec 13 assumptions and the Task 18 hand-over: "SMB session and directory brute-force are visible in the target's logs." Per the hard rule, put no UI warning in.

### G19. SMB response fixtures are partly built by the code under test
- Severity: MINOR
- Location: `SmbFixtures` (lines 6518 to 6530) uses `Smb2.header` and `Spnego.negTokenResp`.
- Problem: the parser tests read headers that the builder wrote, so a symmetric header bug (an offset wrong in both places) passes. The request side is fine: it is pinned to hand-written hex.
- Resolution: build the fixture header from a hex literal, as the request tests do.

### G20. `WordlistStoreTest` passes a `String` where an `InputStream` is required
- Severity: MINOR
- Location: lines 3886 and 3892 (`s.import("gone", "a\nb")`). The note at 3905 says it uses `byteInputStream()`, but the calls do not.
- Resolution: add `.byteInputStream()` to both calls.

### G21. `git add -A` in every commit step
- Severity: MINOR
- Location: every task's commit step.
- Problem: with another agent working in the same tree (as today), `-A` sweeps unrelated work into the wrong commit.
- Resolution: stage the files the task names, then run `git status` and check that nothing else is staged.

### G22. Findings can slightly exceed 500 under concurrency
- Severity: MINOR
- Location: Task 10 `report`, line 4862.
- Problem: two workers can both pass the `state.end == null` check before either increments `found`.
- Resolution: claim a slot with `found.incrementAndGet()` first, and drop the finding if the result is over 500.

### G23. WordlistStore leaves `.tmp` orphans and has unsynchronised import and delete
- Severity: MINOR
- Location: Task 9, `load` and `import`/`delete`.
- Resolution: delete `*.tmp` in `load()`, and guard the index read-modify-write with a `Mutex`.

### G24. Bundled-list content quality
- Severity: MINOR
- Location: Task 8 common-short.
- Problem: `.ds_store` should be `.DS_Store` (servers are case sensitive), and `knownhosts` should be `.ssh/known_hosts`.
- Resolution: fix the spellings and keep the sort order.

### G25. Tool chips start runs from inside the scrolling list
- Severity: MINOR (spec tension for the Director)
- Location: spec 8.1 and Task 16 `ToolsBlock`.
- Problem: the companion tools start immediately from chips inside `portList`, which sits awkwardly with the standing rule that Run controls never live in a scroll container. Stop is pinned, which is what the rule protects, so I accept the spec's choice. I am recording it so the Director rules on it rather than discovering it.
- Resolution: list it in the Task 18 hand-over as a ruling for the Director.

### G26. Global Constraints overstate "No instrumented test reads the production toolRunner"
- Severity: MINOR
- Location: line 31; Task 6 Nav change (line 2557).
- Problem: `NetProbeNav` reads `toolRunActive`, which forces `toolRunner`, which launches `markInterrupted` on `netprobe.db`. 3A's `HomeScreenTest` launches `MainActivity`, so it now also runs the 3B startup DB work. Task 18 does not run `HomeScreenTest`, so nothing breaks today, but the constraint text is false.
- Resolution: reword it to "only HomeScreenTest, which launches the real activity, reaches it; run it last, after Step 4", and add that ordering to Task 18.

## Deviations (A)

1 (no Forget snapshot): right call; the 3A code confirms the soft delete. Add G9. 2 (`ToolStore`): right. 3 (`markInterrupted` at first runner read): acceptable; see G26. 4 (`methodUsed`): follow the spec (G8). 5 (global sort): acceptable once G11 is fixed. 6, 7: right. 8 (SMB vectors): right protocol; see below. 9 ("as <name>" shows the attempted name): acceptable, but it misleads when the fallback succeeded. A follow-up could record `details.serverNameSent`. 10 (burst on top of the rate): acceptable, but it is moot until G3 is fixed. 11 to 16: right. 17: noted.

## NTLMv2 vectors and the SMB probe

The test vectors in Task 14 agree with my own knowledge of RFC 1320 and MS-NLMP sections 4.2.2 and 4.2.4: the MD4 suite, the NT hash of "Password", NTOWFv2, LMv2 and NTProofStr. The inputs (user, domain, server challenge, client challenge, zero time and the two AV pairs) match that section's setup, and the flag constants add up to the values asserted. I see no transcription error. The plan's rule stands: if a vector fails, stop and report, and never edit the vector. The request byte layouts (SMB2 header, NEGOTIATE, SESSION_SETUP, SPNEGO, the SNMP GETNEXT) are self-consistent; I checked every length field. The request tests are independent hex oracles; the response fixtures are not (G19). The probe itself is bounded and safe to send to the Director's and his clients' hosts. G18 records what it leaves in their logs.

## What the plan gets right

- `MIGRATION_2_3` is additive only, pinned by a recording-proxy test, a schema-diff test, and two instrumented chains that check the app database file is untouched.
- Every consumed 3A name exists on the branch with the right signature: `MIGRATION_1_2`, `DB_NAME`, `MIGRATION_TEST_DB`, `UndoStore`, `AppScopes`, `appScope`, `hostScanJobs`, `removedHostIds`, `hostWriteLock`, `radarScanActive`, `ServiceInfo` evidence fields, `ServiceMerge`, `setIdentityIfNotWeaker`, `DeviceIdentity.software`, `Rule.onPorts`, `PortUris.guess`, `ServiceIdentifier.HTTP/TLS`, `Probe.connect`, `currentBinder`, `bindOrFail`, `ScanBinding.run`, `NetworkSignature.identityKey`, `Ber`, `HttpTitleParser`, `TlsPeek.subjectField`. The exceptions are G1 and G13.
- `CancellableIo` inherits the caller's dispatcher, and its tests really fail if `Dispatchers.IO` creeps in.
- The calibration and filter design and its tests are solid: nonce band, path echo, redirect sets, extension groups, the HEAD/GET switch and the runaway check.
- Hints are kept per (tool, port), they never weaken an identity, and the pure rule is tested.
- The SMB scope is strictly NEGOTIATE and SESSION_SETUP, and a test asserts no other command is ever written.
- Copy discipline: every string is in `ToolCopy` or `WordlistCopy` with a template matrix. There are no em-dashes in the plan and no disclaimers in the UI.
