# NetProbe Phase 3A: on-device QA record (Task 19)

- Date: 2026-10-20
- Tester: Bugsy (QA)
- Build: branch `phase3a`, HEAD ab90057 (code identical to 721d2db; later commits are plan and review documents only)
- Device: Pixel 7 Pro `28221FDH300AP9`, Android 17, density 560 (3.5x), refresh up to 120 Hz
- The Director's own font scale is 1.15. Brief steps that say "restore to 1.0" were restored to 1.15. The device was left at 1.15, with animator scale default and transition and window scales at 1.0.
- Network: the Director's home Wi-Fi "CHi" (10.0.3.0/24, gateway 10.0.3.1)

## Summary

| Step | Result |
|---|---|
| 0 Backup of live DB and prefs | PASS |
| 1 Schema 1 rollback APK (b37db04, offline) | PASS |
| 2 Migration copy pushed | PASS after a fix to the procedure (see "Procedure defect") |
| 3 MigrationTest, DirectorCopyMigrationTest | PASS, 1/1 and 1/1, real DB untouched |
| 4 In-place migration of the real DB | PASS, every mark, note and port intact |
| 5 Rest of the instrumented suite | FAIL: 2 classes fail deterministically (3 tests); all other classes pass |
| 6 Schema 2 backup | PASS |
| 7 Recordings | PASS (all five, plus three extra) |
| 8 Screenshots at 1.0 and 1.3 | PASS, with two substitutions noted |
| 9 Behaviour checks | Mixed, see the table. Two FAIL findings, one gfxinfo FAIL. Destructive checks NOT RUN (no disposable network). Forget then Undo NOT RUN (blocked by the permission system) |
| 10 Record and hand over | This file |

## Step 0: backup

App force-stopped first. Files in `K:/claude/netprobe_db_backup/phase3a/` (outside the repo):

| File | Size | MD5 |
|---|---|---|
| netprobe.db.bak | 323584 | b268b5eba74a7560914a25fc95d55903 |
| netprobe.db-wal.bak | 524288 | dc8efdeb359e53490637647d40cfaf8b |
| netprobe.db-shm.bak | 32768 | cd3e908650be801c51cd4655f1b0f861 |
| netprobe.xml.bak | 697 | caac07398b5aca4d9f901dc2d1fccbb2 |

Sizes match the on-device files. Baseline content, read from a copy: schema 1, 1 network ("CHi"), 33 hosts, 64 ports. One marked host: 10.0.3.59 CHNAS, Interesting, note "QA note NAS". No custom names. The prefs file holds no `extManual.*` keys, so there were no manual ranges to protect.

## Step 1: rollback APK

`git worktree add` at b37db04, `local.properties` copied in, `./gradlew :app:assembleDebug --offline`: BUILD SUCCESSFUL in 27 s, nothing downloaded. APK saved as `netprobe-schema1-b37db04.apk` (19020051 bytes). The temporary worktree was removed.

## Step 2: migration copy, and a procedure defect

The brief's `adb shell "run-as ... sh -c 'cat > ...'" < file` wrote only 4220 bytes of the 323584-byte database on this Windows host: `adb shell` does not pass binary stdin through intact here. The truncated copy was deleted. The real database was never touched.

Re-pushed with `adb exec-in run-as $PKG sh -c "cat > databases/netprobe-migration-copy.db" < file`. Both files verified byte for byte by MD5 against the backups.

**The same defect affects both restore procedures in the brief** ("Restore schema 2 data" and "Rollback to schema 1"). Both stream with `adb shell ... < file`. On this machine they must use `adb exec-in` instead, or the restore writes a truncated database. Nothing was restored during this QA.

## Step 3: migration tests

`leaveApksInstalledAfterRun=true` on every run. `before-tests.txt` is in the backup folder.

| Class | Tests | Result |
|---|---|---|
| data.MigrationTest | 1 | PASS |
| data.DirectorCopyMigrationTest | 1 | PASS (not skipped: tests=1, skipped=0) |

Afterwards `netprobe.db` (323584, 06:10:59), `-wal` (524288, 06:15:48) and `-shm` (32768, 06:15:48) had the same sizes and timestamps as before. The copy was deleted by the test. Two empty `.lck` files remain (`netprobe-migration-copy.db.lck`, `netprobe-migration-test.db.lck`); they are harmless.

## Step 4: in-place migration

`installDebug` over the existing install, cold launch in 886 ms. Logcat showed no FATAL, AndroidRuntime or Migration errors. Shots: `q00-after-migration.png`, `q01-marked-host-detail.png`.

I pulled the migrated database and compared it with the step 0 copy column by column:

- `user_version` 1 -> 2. New host columns: userType, userVendor, userModel, identSoftware. New port columns: title, server, realm, poweredBy, certCn, certOrg. New network columns: lastRunAt, forgotten.
- All 33 hosts are identical on every schema 1 column, and all 64 ports are identical. The network row differs only in `lastSeen` (the expected `ensure` touch on open).
- On screen: Home shows 33 devices. CHNAS shows Interesting, and its Detail page shows the note "QA note NAS". There are no custom names in his data to check.

Result: PASS.

## Step 5: instrumented suite

| Class | Tests | Fail | Skipped | Result |
|---|---|---|---|---|
| data.DaoTest | 19 | 0 | 0 | PASS |
| data.RemoveDeviceTest | 6 | 0 | 0 | PASS |
| ui.home.RadarBlockTest | 14 | 0 | 0 | PASS (pulse timing held) |
| ui.home.HomeContentTest | 26 | 0 | 0 | PASS |
| ui.home.RadarStateTest | 2 | 0 | 0 | PASS |
| ui.detail.DetailContentTest | 23 | 0 | 0 | PASS |
| ui.NavTransitionTest | 2 | 2 | 0 | **FAIL** |
| ui.ControlsMotionTest | 4 | 1 | 0 | **FAIL** |
| ui.ListMotionTest | 4 | 0 | 0 | PASS |
| ui.settings.SettingsRowsTest | 2 | 0 | 0 | PASS |
| ui.settings.ExtendedSectionTest | 7 | 0 | 0 | PASS |
| ui.theme.TypographyTest | 1 | 0 | 0 | PASS |
| ui.KeepScreenOnTest | 1 | 0 | 0 | PASS |
| ui.HomeScreenTest (last) | 3 | 0 | 1 | PASS (`scanIsDisabledWithAMessageWhenOffWifi` skipped by assumption: the phone was on Wi-Fi) |

Totals: 117 tests, 3 failures, 1 skipped (115 run, 112 passed), plus the 2 migration tests from step 3.

Failures. Each class was rerun once at 1.15, as allowed, and both reruns failed the same way. One labelled diagnostic run at font scale 1.0 also failed the same way, so neither failure depends on font scale. Nothing was relaxed or retried into a pass. These are faults, not flakes.

1. `NavTransitionTest.homeToDetailSlidesAndFadesBothWays`:
   `AssertionError: Failed: assertDoesNotExist. Reason: Did not expect any node but found '1' node that satisfies: (TestTag = 'fakeHome')`, Node #4 at (l=-144.0, t=144.0, r=1296.0, b=2952.0)px. Home is still composed 260 ms after navigate.
2. `NavTransitionTest.reducedMotionShowsOnlyTheDestination`: same assertion. Node #10 at (l=0.0, t=144.0, r=1440.0, b=2952.0)px: Home is still present two frames after navigate with reduced motion.
   On the real app (`r3-navigation.mp4`) both directions complete. The likely cause is the test's frame budget against Navigation's transition completion under a paused clock, but the developer must decide whether it is the test or the NavHost.
3. `ControlsMotionTest.scanPillMorphsWithoutMovingTheProfileControl`:
   `AssertionError: pill height 47.970764.dp at start` (47.970734 dp at 1.0) at `ControlsMotionTest.kt:103`. At density 3.5, 48 dp is exactly 168 px; the pill measures about 167.9 px on the first morph frame. The touch target is short by 0.1 px. Either the strict `>= 48.dp` check needs to round to whole pixels, or something clips the pill's bounds by a fraction of a pixel at the start of the morph. This is for Ada to rule on. Production code is `Controls.kt:102` (`heightIn(min = Dimens.touch)`).

`ServiceIdentifierLadderTest` is a JVM test and is not part of this device run.

## Step 6: schema 2 backup

Taken after step 5. App force-stopped first. Files in `K:/claude/netprobe_db_backup/phase3a/schema2/`: netprobe.db.bak 323584, -wal 61832, -shm 32768, netprobe.xml.bak 697. Every mark and note was still intact.

A second snapshot, `schema2-pre-undo/` (db 319488, wal 412032, shm 32768, xml 697), was taken just before the Undo checks.

HomeScreenTest starts a real radar scan on his network. That scan, and the scans since, re-identified several hosts (see the identity finding below). This is scan-derived data only; no mark or note changed.

## Step 7: recordings (font scale 1.0, motion on unless stated)

| File | Content | Result |
|---|---|---|
| r1-radar-scan.mp4 | Full radar scan from settled: expand, sweep with blips lighting, found counter climbing | PASS. The scan takes about 85 s (17 hosts on other subnets), so the 20 s clip ends mid-scan. The settle is covered by gfxinfo below |
| r2-radar-reduced.mp4 | Remove animations on (all three scales 0): arm static, instant changes | PASS. Settings restored afterwards |
| r3-navigation.mp4 | Home to Detail and back, Home to Settings and back | PASS. Finding: the first Detail frame shows an empty header and "No open ports yet, run a scan" before the host loads (a one-frame flash, cosmetic) |
| r4-detail-full.mp4 | Detail Full scan on CHNAS: pill morph, progress line, rows | PASS |
| r5-selection.mp4 | Filter chips, status segments, profile segments | PASS (status set back to Unmarked on 10.0.3.50) |
| r6-remove-exit.mp4 | Remove device with Detail open | Recorded. The header blank (M7) is not visible at 8 fps; the one exit frame still shows the header |
| r7-full-stop.mp4 | Full scan, Stop at 15 s | Stop returns to Scan in about 0.5 s |

r1, r4 and r7 are 720p re-encodes for the repo. The raw device recordings are in `K:/claude/netprobe_db_backup/phase3a/raw_recordings/`.

## Step 8: screenshots

At font scale 1.0: `s06-mid-sweep.png` (12 found; `-a` and `-c` are neighbours at 9 and 12), `s07-port-sheet.png` (8096: "Open with...", Try as http, https, ssh, vnc, rdp, ftp, telnet), `s07b-http-chip-opens-browser.png`, `s07c-try-as-ssh.png`, `s08-why.png`, `s09-edit.png`, `s09b-row.png`, `s09c-detail.png`, `s10-undo-remove.png`, `s11-stopped.png` ("Stopped after 23 s, 1 on another subnet"), `s12-restart-stale.png`.

At 1.3: `fs13-home.png`, `fs13-detail-nas-floor.png`, `fs13-why.png`, `fs13-port-sheet.png`, `fs13-s06-mid-sweep.png`, `fs13-s11-stopped.png`, `s09c-detail-fs13.png`, `q06-edit-fs13-keyboard.png`, `q07-edit-fs13-keyboard-after-scroll.png`.

Substitutions:
- `s07b-chooser`: no chooser appeared. Chrome is the only http handler on the phone (`cmd package query-activities` lists only com.android.chrome), so both the http chip and "Open with..." open Chrome directly. That shot showed his Jellyfin library, so it is kept outside the repo, in the raw_recordings folder.
- `s10b-undo-forget`: not taken. See "Forget then Undo" below.

## Step 9: behaviour checks

| Check | Result |
|---|---|
| Non-standard HTTP port shows "Open" and opens the browser | PASS. After a Full scan, 5000, 5357, 8096 (Jellyfin, Kestrel), 50001 and 50002 on CHNAS show Open. 8096 opened Jellyfin in Chrome |
| Try as ssh on port 22 offers his SSH app | PASS. JuiceSSH opened at 10.0.3.59 (cancelled) |
| Synology identity after Full | PASS. "Synology Inc DS220+ NAS" plus a "Runs Jellyfin" chip. Why lines: upnp Synology Inc DS220+, upnpDeviceType MediaServer:1, upnpManufacturer Synology Inc, netbiosName CHNAS, ports 5000,5001, port 443 certOrg: Synology Inc., port 8096 httpTitle: Jellyfin, port 22 sshBanner: OpenSSH |
| Hue | PASS. Gained model: "Philips Hue Hue Bridge" (mdnsType _hue._tcp, httpTitle hue personal wireless lighting) |
| Hub Manager (192.168.1.254) | PASS. Gained ROUTER "Hub Manager", 75 |
| UniFi | **FAIL**. See finding F1 |
| Home Assistant, 8123 | N/A. No host on his network has 8123 open and no Home Assistant was found |
| Proxmox reads "Proxmox VE" | N/A. No Proxmox node found |
| Identity never drops after a Quick scan | PASS. Quick on CHNAS kept it. Quick scan all over 34 hosts dropped nothing; 3 UniFi hosts went 40 -> 70 and 4 unknowns became IOT 50. Marks unchanged. Ports 67 -> 97 |
| Identity never drops after the next radar scan | **FAIL**. See F1 |
| Remove device, then Undo (on 10.0.3.50, which I marked Investigated with note "QA_throwaway_note"; never his host) | PASS. Notice "Removed 10.0.3.50" with Undo. After Undo the row is identical (same id 12, status, note, every column). Mark and note were then cleared back to Unmarked and empty |
| Forget this network, then Undo (home network) | **NOT RUN**. The permission system blocked the Forget tap as irreversible. Needs the Director's go-ahead |
| Remove with Detail open: header blanks during the exit slide | Recorded (r6). Not visible at 8 fps |
| Stop a radar scan, force-stop, relaunch: no mass dimming (A10) | PASS. After the stopped run, `networks.lastRunAt` stayed at the last completed run (13:33:24). Only the 6 hosts missing from that run are dimmed; the 11 hosts the stopped run touched are not (`q02`, `s12`) |
| Edit device at 1.3 with the keyboard up: Save and Cancel reachable | **FAIL**. See F2 |
| TalkBack reads the identity line as "Edit device, <name>" | PARTIAL. TalkBack was not turned on. The accessibility tree exposes content-desc "Edit device, CHNAS" and "Edit device, 10.0.3.50" on the identity line, which is what TalkBack reads. The Director should confirm it by ear |
| Detail at 1.3 keeps the five-row floor with the chip row in the pinned header | PASS. CHNAS with the "Runs Jellyfin" chip shows 5 full port rows plus a sixth (`fs13-detail-nas-floor.png`) |
| Full scan of a NAS ends promptly on Stop | PARTIAL. Stop during the sweep returns in about 0.5 s (r7). On a repeat Full scan the identify phase took under 1 s after the sweep (the ports were already known), so a Stop inside a slow TLS handshake could not be timed |
| Host with 9100 open prints nothing | N/A. No host on his network has 9100 open |
| Janky frames under 5 percent | **FAIL** for expand and settle, PASS for steady. See below |
| DB migrated in place with marks and notes intact; schema 2 backup after it | PASS |
| **Destructive checks (disposable network)** | **NOT RUN, pending a disposable network** |

### gfxinfo (radar scan on the LAN, font scale 1.0, no recording running)

| Phase | Frames | Janky | Percent | p90 / p95 / p99 |
|---|---|---|---|---|
| Expand, first 1.5 s | 156 | 10 | 6.41 % | 21 / 27 / 65 ms |
| Expand, repeat, first 0.7 s | 80 | 11 | 13.75 % | |
| Steady scanning, 30 s | 3506 | 38 | 1.08 % | 10 / 15 / 23 ms |
| Tail of scan plus settle, 75 s | 6162 | 103 | 1.67 % | 11 / 14 / 21 ms |
| Settle alone (framestats, final 33 frames, 358 ms) | 33 | 5 missed deadline | 15.2 % | max 30.9 ms |

Steady scanning is well under 5 %. The expand and settle are over 5 %. This matches the parked Task 16 concern: the live layer cache rebuilds per frame while the size changes.

## Findings for Ada and the Director

- **F1 (identity drop, Review Focus 3)**. After a radar scan on the Phase 3A build, the UniFi devices lost their identity:
  - 10.0.4.1, 10.0.0.1 and 10.0.2.1: ROUTER, Ubiquiti, 70 -> UNKNOWN, no vendor, 40, with only a "UniFi Network" chip.
  - 192.168.1.1: ROUTER, Ubiquiti, 70 -> UNKNOWN, 70.
  - 10.0.0.6: ROUTER, Ubiquiti -> UNKNOWN, Ubiquiti "UniFi OS console", 80.
  - The gateway 10.0.3.1 keeps ROUTER but loses "Ubiquiti" (95 -> 95); Home now reads "Linux (Debian) Router".
  - The Pi-holes lost their model: 90 -> 70, now "Linux (Debian) Server" plus a chip.

  Two causes:
  1. The `title-unifi` rule now gives only the software chip (A9), although "UniFi Security Gateway" is hardware.
  2. The radar re-identification write lowers confidence (70 -> 40). That contradicts "an identity never drops after the next radar scan".

  Quick scan all partly lifts confidence back (40 -> 70) but does not restore the type or vendor. Scan-derived only; no marks were affected.
- **F2 (Edit dialog)**. With the keyboard up, Save and Cancel are under the keyboard at 1.3 (y 2019 to 2092, keyboard from about y 1950), and also at 1.0. The dialog does not scroll. Pressing Back to hide the keyboard makes them reachable.
- **F3**. NavTransitionTest (2) and ControlsMotionTest (1) fail deterministically. Messages are under step 5.
- **F4 (jank)**. Expand 6.4 to 13.8 % and settle 15.2 %, against a 5 % target.
- **F5 (procedure)**. On this host `adb shell ... < file` truncates binary input. The brief's restore procedures must use `adb exec-in`.
- **F6 (cosmetic)**. The first Detail frame flashes an empty header and "No open ports yet, run a scan" (r3). While the Edit dialog was open, a lighter rectangle showed over the top bar behind the scrim, from x 0 to about 815 (`s09-edit.png`).
- **F7 (copy)**. "Last scan 1 minute ago" showed straight after a scan finished. It looks like it counts from the run's start, not its end.

## Destructive checks: pending a disposable network

None of these were run. The Director has not provided a disposable network, and the brief forbids running them on the home network. To run them I need him to:

1. Join the phone to a disposable network: the phone's hotspot shared from a second device, or a second Wi-Fi he does not keep.
2. Have at least two other devices on it.
3. Tell Bugsy it is joined. Bugsy then scans it, marks and notes one throwaway host there, and runs: Remove then Undo; Remove during a radar scan; Remove during Quick scan all, then Undo (G2); Remove, then a Detail scan on another host (G8); Forget, then a radar scan; Forget, then dismiss; Forget, back out, reopen, then Undo (G4). Then he rejoins the home Wi-Fi.

Also pending his go-ahead: **Forget this network, then Undo on the home network**. The brief allows it, but the permission system blocked it as irreversible. If he approves, the snapshots in `schema2-pre-undo/` are the fallback, restored with `adb exec-in` (F5).

## For the Director to rule on

Spec section 10 assumptions:
- A1, motion: the 3 s arm turn, blips dimming to 0.45 between sweeps, and the extra cross-fades.
- A2: active probes on Full and Custom only, with no Settings toggle.
- A3: a port scan replaces the identity at equal confidence. See F1, where this lets the gateway lose "Ubiquiti".
- A4: Edit replaces Rename, and confirming a detected value pins nothing.
- A5: twelve new columns.
- A6: Undo is in memory only.
- A7: your values never feed identification.
- A8: "Open with..." defaults to http, or https for bare TLS.
- A9: apps become chips. See F1: "UniFi Security Gateway" is hardware.
- A10: only completed runs persist dimming (verified on device).

Spec section 11 open items:
1. Rule samples confirmed on his hosts: Synology yes, Hue yes, Hub Manager yes, UniFi no (F1), Home Assistant absent.
2. SMB has no banner (now Phase 3B).
3. Quick scan all bind wording.
4. Phase 2 minors M3, M5, M6, M7, M9, M11 and M12 are still open.
5. App icon approval.
6. Optional active-probe toggle.
7. Industrial control ports (102, 502, 20000, 44818, 47808) are not on the never-probe list.
8. Software names are not searchable and not used in the vulnerability lookup.

Plan-review items:
- G12: the worst case is about 60 s + 8 x 12 s of probe time, not 76 s.
- G16: port evidence never expires on a reused IP.
- G17: Quick scan all keeps its 1200 ms arc.
- G18: a vendor-only override on his own phone shows on Detail, but Home keeps "Android device".
- G9: `p-proxmox-cert` matches O "Proxmox Virtual Environment", not "PVE Cluster".
- The pulse ring is not dimmed during the settle.

Copy awaiting his ruling: "Forgot <network>", "Removed <device>" (seen on device: "Removed 10.0.3.50"), "Runs <app>" (seen: "Runs Jellyfin"), "Set by you: ...", "identifying N ports", and whether Quick scan all should also say "Scan skipped: could not bind to Wi-Fi".

## State left on the device

- Phase 3A debug build and its test APK installed (never uninstalled, never cleared).
- Font scale 1.15. Animation scales back to their defaults.
- His data: schema 2, CHNAS Interesting with "QA note NAS" intact, no stray marks, notes or overrides (checked from a pulled copy).
- Hosts are now 35, and identities have changed from scans (F1).
- `svc power stayon usb` left on, as instructed.
