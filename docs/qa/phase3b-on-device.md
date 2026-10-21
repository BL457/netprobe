# NetProbe Phase 3B: on-device QA record (Task 20)

- Date: 2026-10-21 (02:45 to 04:05 phone time)
- Tester: Bugsy (QA)
- Build: branch `phase3b`, code tip 40b88f0 (HEAD 33a6970 adds Phase 3C spec documents only). Installed APK md5 `33ecfce9e725f17a532f9ec405c3565a`, identical to `K:/claude/netprobe_apk/netprobe-phase3b-40b88f0-debug.apk` and to the local `app-debug.apk`.
- Device: Pixel 7 Pro `28221FDH300AP9`, Android 17, 1440x3120, 120 Hz. `svc power stayon usb` left as set. The screen never locked during the session.
- Network: the Director's home Wi-Fi "CHi" (10.0.3.0/24, gateway 10.0.3.1), extended discovery on.
- Font scale: the Director's 1.15 was the working scale. Shots also at 1.0 and 1.3 (spec 8.9 budget). Restored to 1.15 at the end.
- Every instrumented run passed `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. No `pm clear`, no uninstall, nothing downloaded or installed except the NetProbe debug build the test runs reinstall over itself.

## Deviation from the brief (set by the controller)

The Director installed this exact build himself on 2026-10-20 19:32 and used it, so his live database was already schema 3 when QA started. Therefore:
- Step 0 backs up the live schema 3 database.
- Step 1 (rollback APK) is not rebuilt: the schema 2 build is already on disk as `K:/claude/netprobe_apk/netprobe-phase3a-87ab3c1-debug.apk` (md5 `4905a6a904db79a99324641f342b5716`). Note that rolling back now also means going back to the 3A schema 2 backup of 2026-10-20 13:26 and losing everything recorded since (see "Restore position" below).
- Steps 2 and 3 run `DirectorCopyMigration23Test` on the schema 2 copy from the 3A QA (`K:/claude/netprobe_db_backup/phase3a/schema2/`).
- Step 4 (in-place migration) had already happened at his first launch; it is replaced by a comparison of the live database against that schema 2 backup.

## Summary

| Step | Result |
|---|---|
| 0 Backup of the live schema 3 DB and prefs | PASS |
| 1 Rollback APK | Not rebuilt (already on disk, see above) |
| 2 Schema 2 copy pushed with `exec-in`, MD5 checked | PASS |
| 3 ToolMigrationTest, DirectorCopyMigration23Test | PASS, 2/2 and 1/1 (not skipped); live DB untouched |
| 4 Live data vs schema 2 backup | PASS with one item for the Director: CHNAS mark is now Unmarked (was Interesting); note intact. Migration proven not to be the cause |
| 5 Rest of the instrumented suite | FAIL: 3 classes fail deterministically (7 tests, confirmed by one rerun); all others pass |
| 6 Recordings | PASS (7 recordings) |
| 7 Screenshots at 1.15, 1.0 and 1.3 | PASS |
| 8 Behaviour checks | Mostly PASS. FAIL: Synology fingerprint rule misses DSM 7; DSM dotfile 403s trigger a false "answers every path alike" stop; gfxinfo jank; silent wordlist fallback. Several items BLOCKED by host state (Pi-hole web UIs hung, no SMB-reachable Windows host, no SNMP host, no printer). Destructive checks PENDING (no disposable network) |
| 9 Record | This file |

## Step 0: backup of the live database

App force-stopped first. Files in `K:/claude/netprobe_db_backup/phase3b/live-schema3/` (outside the repo), each MD5 checked against the phone after the copy:

| File | Size | MD5 |
|---|---|---|
| netprobe.db.bak | 397312 | 2dc6213881940f7a3e6a0454797d8396 |
| netprobe.db-wal.bak | 420272 | 647e24203556f333b761fc1ec4ee02fa |
| netprobe.db-shm.bak | 32768 | 3036a4b9e2b379c6df99995aea6f383c |
| netprobe.xml.bak | 750 | 34e8d776f0f41097ea254ae0b8053ac9 |

Content (read from a PC copy): schema 3 (`user_version` 3), 4 networks (CHi, 172.17.248.1, 10.0.0.1, AddysgConwy__GUEST), 40 hosts, 98 ports, 6 tool runs and 20 findings from the Director's own use last night, `PRAGMA integrity_check` ok, no foreign key violations.

Further snapshots taken during QA, same procedure: `pre-remove-undo/`, `post-remove-undo/`, `post-qa/` (final state: db 495616 md5 887df035..., wal 420272 md5 ccf82498...).

## Steps 2 and 3: copy migration

Pushed `phase3a/schema2/netprobe.db.bak` and `-wal.bak` to `databases/netprobe-migration-copy.db*` with `adb exec-in`: `MD5 OK` for both (050bb401..., 38da0655...). `before-tests.txt` is in the backup folder.

| Class | Tests | Result |
|---|---|---|
| data.ToolMigrationTest | 2 | PASS |
| data.DirectorCopyMigration23Test | 1 | PASS (tests=1, skipped=0, so the copy was found) |

The copy test asserts every host's `id|status|note|customName|userType` row is identical after 2 to 3, and the host and port counts. That copy has CHNAS as Interesting, so the migration keeps marks. Afterwards `netprobe.db` (397312, 00:07) and `-wal` (420272, 00:37) had the same sizes and times as before; the copy was deleted by the test.

## Step 4 replacement: live data against the schema 2 backup

Compared read-only on PC copies (python sqlite3), never on the phone:
- All 33 schema 2 hosts are present with the same ids, IPs and MACs; all 64 schema 2 ports are present. The live DB adds 7 hosts (5 on CHi, 2 on the guest network) and 34 ports from the Director's scans.
- Notes, custom names and user overrides: identical (the only note is CHNAS "QA note NAS"; there are no custom names or overrides).
- **One difference: CHNAS (10.0.3.59, host 3) `status` is `UNMARKED` in the live DB; it was `INTERESTING` in the schema 2 backup and in the 3A baseline.** The note is intact. `DirectorCopyMigration23Test` proves the 2 to 3 migration keeps it, and the only code path that writes `hosts.status` is `HostDao.setStatus`, called only from the Detail status segments (`DetailViewModel.setStatus`). So it was changed by a tap on the Detail screen, either by the Director last night or under 3A after 13:26. The WAL contains no older copy of that row to date it. **Director: did you unmark CHNAS? If not, tell me and I will treat it as a defect.** I did not change it back.
- The two new tables exist (`tool_runs`, `tool_findings`, with ON DELETE CASCADE).

## Step 5: instrumented suite

Class order as the brief, `HomeScreenTest` last. Totals: 164 tests, 156 passed, 7 failed, 1 skipped.

| Class | Tests | Result |
|---|---|---|
| data.MigrationTest | 1 | PASS |
| data.RadarIdentityGuardTest | 4 | PASS |
| data.ToolDaoTest | 9 | PASS (prune, clock-back, FAILED run and cascade cases executed) |
| data.DaoTest | 19 | PASS |
| data.RemoveDeviceTest | 6 | PASS |
| ui.tools.ToolsBlockTest | 5 | **FAIL 5/5** |
| ui.tools.DirBruteSheetTest | 6 | PASS |
| ui.tools.ToolResultsSheetTest | 8 | PASS |
| ui.wordlists.WordlistsScreenTest | 5 | PASS |
| ui.settings.SettingsToolsTest | 2 | PASS |
| ui.home.RadarBlockTest | 14 | PASS |
| ui.home.HomeContentTest | 29 | **FAIL 1** |
| ui.home.RadarStateTest | 2 | PASS |
| ui.detail.DetailContentTest | 27 | **FAIL 1** |
| ui.NavTransitionTest | 2 | PASS |
| ui.ControlsMotionTest | 4 | PASS |
| ui.ListMotionTest | 4 | PASS |
| ui.settings.SettingsRowsTest | 2 | PASS |
| ui.settings.ExtendedSectionTest | 7 | PASS |
| ui.theme.TypographyTest | 1 | PASS |
| ui.KeepScreenOnTest | 1 | PASS |
| ui.HomeScreenTest | 3 | PASS 2, 1 skipped (`scanIsDisabledWithAMessageWhenOffWifi` assumes no Wi-Fi; expected on the LAN) |

`NeighbourSpikeTest` (a spike) and 3A's `DirectorCopyMigrationTest` (schema 1 source no longer on the phone) were left out on purpose.

Failures (none is timing-sensitive; each class was rerun once and failed identically):
1. `ToolsBlockTest`, all 5 tests: `Expected exactly '1' node but could not find any node that satisfies: (TestTag = 'toolsHeader')` (4 tests, at the first `performClick`), and `collapsedByDefaultWithAvailableAndTheNewestHistory`: `Assert failed: The component is not displayed!` on `onNodeWithText("Tools")`.
2. `DetailContentTest.theToolsBlockIsTheFirstListItem`: `Failed to retrieve bounds of the node ... (TestTag = 'toolsHeader')`.
   Cause for 1 and 2: `ToolsBlock.kt:47` applies `.clearAndSetSemantics { ... }.testTag("toolsHeader")`. The tag sits after `clearAndSetSemantics` in the chain, so it is cleared, and the visible "Tools" and "N available" texts are cleared too. On the device the header works for users and TalkBack: uiautomator shows one clickable node "Tools, 4 available, expand". Fix in code (put `testTag` before `clearAndSetSemantics`) and in the first test (it cannot find text that `clearAndSetSemantics` removed; assert the description instead). Not fixed by QA.
3. `HomeContentTest.aHeldHostShowsRunningInPlaceOfItsPortCount`: `Expected exactly '1' node ... (TestTag = 'rowRunning'). However, the unmerged tree contains '1' node that matches. Are you missing useUnmergedNode = true`. The row merges its children. Test fault; the marker shows correctly on the device (s12, s13).

## Step 6: recordings (`docs/qa/shots/phase3b/`)

720p re-encodes for the repo; raw device files are in `K:/claude/netprobe_db_backup/phase3b/raw_recordings/`.

| File | Content | Result |
|---|---|---|
| r1-brute-force.mp4 | 5001 with extensions php, html, txt: ends in 4 s with the false "answers every path alike" (finding F2) | Recorded |
| r1b-brute-force-stop.mp4 | Concurrency 1 on 5001: ran to completion before Stop could be tapped (294 paths, 6 s) | Recorded |
| r1c-stop.mp4 | 4614-path run on 5000, Stop tapped at 03:22:49.27, run Finished 03:22:49, "Stopped after 549 of 4614, 47 found" | PASS |
| r2-browser-roundtrip.mp4 | Open with... a found path during a run, 60 s in Chrome, return: run resumes, ends Done | PASS |
| r4-home-running.mp4 | Run, Home row "running", menu with Forget disabled, Quick scan all, end message | PASS |
| r4b-quickscanall-skip.mp4 | Second Quick scan all during a run: skip counted | PASS |
| r5-remove-undo.mp4 | Remove Hue Bridge (with 2 tool runs), Undo | PASS |

The r3 results/switcher/Share recording was replaced by screenshots and the Share text captured from the chooser preview (the chooser shows the Director's contacts, so it was not kept).

## Step 7: screenshots (`docs/qa/shots/phase3b/`)

- Home and Detail after migration: q00-home-after-migration, q01-chnas-detail
- Tools block: s05-tools-collapsed-fs115, s05b-expanded-fs115, s05-tools-collapsed-fs100, s05b-expanded-fs100
- Options sheet: s06-options-fs115, s06-options-fs100, s06-options-fs130-scrolled (header and footer visible with the body scrolled), s06c-options-ime-fs115 (keyboard up)
- Fingerprint: s07-fingerprint-chnas-5000, s07-fingerprint-chnas-5001, s07-fingerprint-pihole (failed, see 8), s07c-fingerprint-hue (names Philips Hue); identity: s07b-identity-chnas (shows the `toolHint: tls.subject=...` reason)
- TLS: s08-tls-chnas-5001, s08b-tls-usg, s08b-tls-usg-fs100
- SMB: s09-smb-chnas, s09b-smb-windows (failed, host offline)
- SNMP: none possible (s10 not taken, see item 7)
- Wordlists: s11a-wordlists-empty, s11-wordlists-imported, s21-settings-tools, s22-delete-dialog, s23-options-after-delete, s24-fallback-results
- Home and runs: s12-home-running, s13-home-menu-running, s14-detail-menu-remove-disabled, s15-quickscanall-running, s15b-quickscanall-while-tool, s16-home-after-brute-end, s17-quickscanall-end, s17b-quickscanall-skipped-msg
- Remove and Undo: s18-removed-notice, s18b-after-undo, s18c-hue-tools-after-undo
- s19-interrupted, s20-network-changed, s25-results-fs100, s26-budget-fs130
- Working shots: t01 to t09 (running bar, live results sheet, browser round trip, paste check)

## Step 8: behaviour checks (spec 12.3)

| # | Check | Result |
|---|---|---|
| 1 | Install over the 3A build, data survives | PASS (done by the Director; data compared above, one status question) |
| 2 | CHNAS brute-force, common-short, defaults | PASS with findings. 5000: 25 found in 294 paths in 4 s: `/mobile`, `/oauth`, `/scripts` (301), `/robots.txt` (200), plus 21 dotfile paths at 403 11.7 KB (F3). `/webman` is in admin-panels only; with the 4614-path list it was found (301). Stop: within the same second (r1c) |
| 3 | Mid-run browser round trip | PASS. Started 03:19:56, finished 03:21:51 (normal run 47 s, plus about 60 s paused in Chrome), Done, "47 found in 4614 paths", no errors, never "The server stopped answering" |
| 4 | CHNAS fingerprint names Synology DSM; identity line shows a toolHint; TLS on 5001 | **FAIL on the fingerprint** (F1): 5000 "No product named, 3 findings" (5000 only returns a 232-byte JavaScript redirect to https://:5001); 5001 "No product named, 4 findings", title "CHNAS". Identity line: PASS, "toolHint: tls.subject=CN=synology,O=Synology Inc.,L=Taipel,C=TW". TLS 5001: PASS, subject CN=synology, SAN synology, valid 25 Jul 2026 to 26 Jul 2027, RSA 2048, TLSv1.3. Issued by "Synology Inc. CA", so not self-signed (correctly not flagged). No "as <name>": CHNAS has only NetBIOS/UPnP names, no mDNS or reverse-DNS name, so none is sent (spec 4.9) |
| 5 | CHNAS SMB session | PASS. "SMB 3.0.2, signing not required, anonymous refused, guest refused"; NetBIOS name and domain CHNAS, DNS name chnas, OS build 6.1.0. Matches the DSM 7 row with the guest account disabled. Director: confirm the DSM guest account is disabled |
| 6 | Windows PC SMB | BLOCKED. STEVES-MSI-LAPT (10.0.3.138) was offline (no ping, no route): tool reported "No SMB response on this port" correctly in 3 s. The Director's PC (10.0.0.14) firewalls 445 from the LAN, and this session has no admin rights to read its Security log, so the 4624/4625 count is not verified |
| 7 | SNMP walk | N/A: no host in the database answered SNMP `public` during discovery (no `snmpSysDescr` anywhere), so the gate offers SNMP walk on no host. Correct gating |
| 8 | Pi-hole fingerprint and admin brute-force | BLOCKED by the hosts. Both Pi-holes (10.0.3.170, .234) accept TCP on 80 and 443 but never answer HTTP or TLS (checked independently from the PC with curl and from the phone with nc). The tools reported "No HTTP response on this port" and "No TLS handshake on this port" correctly, in 3 s. Director: both Pi-hole web interfaces appear hung |
| 9 | UniFi gateway 10.0.3.1 | PASS. TLS 443: UbiquitiRouterUI, self-signed (flagged), valid to 5 Sept 2028, TLSv1.2. Fingerprint 443: "PHP, 5 findings", title "UniFi Security Gateway" (expected without M4's `unifi-usg` rule). api-paths brute-force on 443: Done, "0 found in 135 paths, 1 error", 21 s |
| 10 | Hue bridge off-subnet 10.0.4.20 | PASS. Fingerprint: "Philips Hue, 5 findings" (body rule), nginx. Brute-force common-short: "3 found in 294 paths" (`/api`, `/debug`, `/index.html`) in 11 s, no stall. The host is only reachable over the Wi-Fi LAN, so the run used Wi-Fi. No name sent: its mDNS name "Hue Bridge - 317AA9" is not DNS-shaped (deviation 15) |
| 11 | Run, Home "running", Quick scan all skip, Forget disabled, end message | PASS. "running" on CHNAS; Forget this network disabled; Detail menu Remove device disabled; end message on Home's notice line; "Quick scan finished, 1 device skipped: a tool is running" (second attempt; the first attempt's sweep reached CHNAS after the run ended and so skipped nothing). Note: the tool's end message has priority, so the Quick scan message appears only after the tool message is dismissed (s17, s17b) |
| 12 | Import, run, delete, fallback | PASS for import via SAF (text/plain picker listed the .txt; "qa-wordlist-4614, 4614 entries, imported 21 Oct 2026"), run, and delete (dialog, then "No imported wordlists yet"; Settings "3 built in, 0 imported"). **FAIL (minor) on the notice** (F5): after the delete the options sheet silently shows common-short selected and the run's summary carries no "Wordlist not found, common-short used" line. History still names the deleted list (9 runs' optionsJson) |
| 13 | Wi-Fi change mid-run | PASS. Wi-Fi disabled 5 s into a run: "Network changed, run stopped". Wi-Fi re-enabled and back on CHi |
| 14 | Copy and Share | PASS. Copy on a finding put `http://10.0.3.59:5000/api/config` (the URL) on the clipboard (pasted into Home search). Share produces the 7.5 text (Target, Name sent, Started, Finished, Status, Summary, Options, then value TAB detail TAB URL rows). Share is disabled while the run is active |
| 15 | Force-stop mid-run, relaunch | PASS. "Interrupted, 46 found", Finished "not finished", findings kept |
| 16 | Remove with tool history, Undo; then lapse | Remove + Undo: PASS on the real WAL database, on the Hue Bridge (no mark, note or name): same host row, run ids 28 and 29, 14 findings and 3 ports byte-identical before and after (DB snapshots compared). The Undo-lapse half: **PENDING, disposable network** |
| 17 | Font scale 1.3 | PASS. Device screen with the Tools block collapsed shows 5 full port rows (22, 80, 139, 443, 445; budget is 5). Options sheet header and footer with Run stay visible with the body scrolled |
| 18 | Printer | N/A: no printer on the network |
| - | Stop during a TLS run against a slow port (M1, I1) | PASS. TLS on Pi-hole 443 (accepts, never handshakes): Stop at 03:49:01.98, Finished 03:49:02, "Stopped", no StrictMode or main-thread network log |
| - | gfxinfo, results sheet open during a run | **FAIL** (F4), see below |

Retention check: after QA the largest (host, tool, port) group holds exactly 10 runs; pruning works on the device. Final DB: 34 runs, 688 findings, integrity ok, no FK violations, every host annotation identical to the start of QA.

### gfxinfo (janky frames, target under 5 percent)

| Scenario | Frames | Janky | Percentiles |
|---|---|---|---|
| 4614-path HTTPS run on 5001, results sheet open, no touch | 268 | 242 (90.30%) | 50th 21 ms, 90th 30 ms, 99th 57 ms |
| 4614-path run on 5000, results sheet scrolled up and down during the run | 489 | 39 (7.98%) | 50th 7 ms, 90th 16 ms; 39 slow UI thread |
| Baseline: finished run's results sheet scrolled, no run | 310 | 1 (0.32%) | 50th 8 ms, 90th 11 ms |

During a run nearly every frame the live sheet draws misses its 8.3 ms deadline (slow UI thread), so the live updates are produced at about 6 frames a second, and touch scrolling during a run is 8% janky. Without a run the same sheet is smooth.

### Timings (spec 5.7)

| Target | Measured | Table |
|---|---|---|
| common-short, LAN HTTP (CHNAS 5000) | 4 s | 4 to 8 s, OK |
| common-short, off-subnet HTTP (Hue) | 11 s | 4 to 8 s, slower embedded server, within 50% of the top |
| 4614 entries, LAN HTTP | 46 to 47 s (4 runs) | 50 to 90 s, OK |
| 4614 entries, LAN HTTPS (DSM nginx) | 47 s | 1 to 3 min: about 50% under the midpoint. Suggest "about 50 s (rate-capped at 100 per second)" |
| api-paths 135, USG embedded HTTPS | 21 s (about 6 paths a second) | Consistent with "can exceed 10 min" for 4614 |

## Findings

- **F1 (fingerprint rule gap, Important):** `http_fingerprints.json` `synology-body` matches `SYNO\.SDS|/webman/`; DSM 7.x's login page on 5001 contains `SYNO.Core.Desktop.*` and neither marker, and its title is the device name. So the HTTP fingerprint never names Synology DSM on his DS220+. Candidate patterns: body `SYNO\.Core\.`, or the `content-security-policy` header containing `synology.com`. Rule change, not a tool failure.
- **F2 (Important):** DSM's nginx answers every path whose segment starts with a dot with 403 11.7 KB. Calibration uses random paths without a dot, so these are not filtered; the bundled lists sort dot entries first, so with extensions php, html, txt the first 200 attempts include 84 such 403s and the runaway check stops the run: "Stopped: the server answers every path alike" (r1). The run never tries the real paths. Without extensions the 21 dot 403s are reported as findings (noise). Suggest calibrating a dot path too (for example `/.<random>`), or excluding the dot group from the runaway count.
- **F3 (Minor):** DSM also answers `/api/*` and `*.cgi` paths with the same 403 "page not found" body (s/t08c), reported as 26 findings in the 4614 run. Same idea: a soft-404 band that ignores the status code.
- **F4 (Important, performance):** gfxinfo above. The live results sheet (and possibly the pinned progress line) does main-thread work per update.
- **F5 (Minor):** deleted saved wordlist falls back silently in the sheet; spec 5.1 and 11.10 ask for a notice.
- **F6 (test defects):** the three failing instrumented classes above; the code fix for `toolsHeader` is one line.
- **F7 (cosmetic, watch item):** the StepSlider inactive track is invisible on the sheet surface; only the tick dots show (s06).
- **F8 (cosmetic):** the Tools block's expanded state is lost when you leave the Device screen and come back (it survives only within the screen); spec 8.1 says saved per host.
- Watch items confirmed fine: the options sheet with the keyboard up keeps the field and the Run footer visible above the keyboard (s06c); Remove device is disabled while a tool runs; two runs' end messages do not collide in practice, but the Quick scan message waits behind the tool message.
- Data: CHNAS changed from Interesting to Unmarked before QA (step 4). His guest-network run last night (172.30.135.254, 18:33) failed "The server stopped answering after 50 paths"; it is in his history and was not investigated further.

## Pending: needs a disposable network

Not run on the home network, per the destructive-check rule:
- Remove a device with tool history and let the Undo lapse (item 16, second half).
- Remove, then rescan.
- Forget this network, then dismiss.
- 3A's leftover destructive list (Remove during a radar scan, during Quick scan all, Forget then Undo flows) if still open.

## For the Director to rule on

- The CHNAS mark question (step 4).
- F1 to F5, and the test fixes in F6.
- **Tools leave traces on the hosts they probe (plan review G18).** The SMB probe's anonymous and guest SESSION_SETUP attempts are logon attempts: a Windows host logs 4625 for a refused logon and 4624 for an accepted anonymous or guest one (about 2 per run, 4 with a pause retry; 4776 on a domain controller), and a Synology NAS logs them in its connection log. Today's QA put more than 20,000 requests into CHNAS's web access log and ran one SMB session against it. Not verified on Windows today (item 6). Run these tools on client hosts only where the engagement allows it.
- **Tool chips start runs from inside the scrolling list (plan review G25).** Stop is pinned in the scan bar (verified); the chips sit in the scrolling Tools block as spec 8.1 places them. Keep or move.
- Spec section 13 assumptions 1 to 15 and section 14 open items 1 to 6 stand as written; device evidence on them: 3 (pause and resume) verified; 4 (registry, Quick scan all skip, running marker) verified; 5 (100 per second) observed at about 98 per second; 7 (name sent) verified as "Name sent: pi.hole" on the Pi-holes; 9 (SNMP gate) means SNMP walk is offered nowhere on his network today; 13 (text/plain) worked for a .txt file; 14.3 (`.lst` files hidden) not tested.
- Plan deviations 1 to 21 in the plan's Self-Review Notes; deviation 10 (burst) not measurable from the device.
- Final review M3 (`setup.php` still in common-short) and M4 (`unifi-usg` fingerprint rule): unchanged in this build; M4 confirmed needed (item 9).
- Motion: finding rows arrive and the Tools block expands without animation. Your call whether to add motion.

## Restore position

Nothing was restored. If you ever need to go back: the schema 3 data from before QA is in `K:/claude/netprobe_db_backup/phase3b/live-schema3/` (restore it with this same build, `put` with MD5 checks, app stopped, all three files replaced). Going back to the 3A build means the 3A schema 2 backup of 2026-10-20 13:26 and loses everything since, because Room will not open a schema 3 file on the schema 2 build.

## Left on the device

The app is installed and running on Home; font scale 1.15; Wi-Fi on CHi; the pushed test wordlist was removed from Downloads and from the app. QA added to his history: tool runs on CHNAS, the USG 10.0.3.1, the Hue bridge and the Pi-holes (now 34 runs in total), and one Quick scan all.
