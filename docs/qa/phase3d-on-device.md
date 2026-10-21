# Phase 3D on-device QA (with the 3C-2 schema 4 and listener checks)

Date: 2026-10-21. Tester: controller with Bugsy's checklist (3D plan Task 12, 3C-2 plan Task 12). Device: Pixel 7 Pro 28221FDH300AP9, Android 17, font scale 1.15 (the Director's setting). Network: home LAN "CHi" (10.0.3.0/24, gateway 10.0.3.1).

Build under test: branch phase3d tip 36db725, APK K:/claude/netprobe_apk/netprobe-0.3.0-36db725-debug.apk, md5 3b2b7777e0835ebdcbbb3d0692497520. The Director had installed this build himself at 13:18 before QA started, so the schema 3 to 4 migration had already run in place on his data (see "Migration state" below). Never pm clear, never uninstall.

## Backup (before any instrumented run)

Location: K:/claude/netprobe_db_backup/phase3d/pre-qa-1442 (outside the repository).

| File | Size | MD5 (PC = phone) |
|---|---|---|
| databases/netprobe.db | 483328 | 8631a3b2055470ff0d6d8ee7cd745806 |
| databases/netprobe.db-wal | 524288 | copied with the db |
| databases/netprobe.db-shm | 32768 | copied with the db |
| shared_prefs/netprobe.xml | 802 | 25a9a0198183e833b393ee9177873271 |
| files/wordlists/59aced4a-33ea-4a3d-8803-e00ca2c8a999.txt, index.json | 471036, 148 | copied |

## Migration state (3C-2 Task 12 Steps 0 to 4, as found)

The copy-first migration test could not run before the in-place migration because the Director installed the schema 4 build before the phone came in. Read from the backup copy with Python's sqlite3: PRAGMA user_version = 4, networks 1, hosts 30, ports 84, tool_runs 5, tool_findings 6, hidden (manualOnly) 0, routed runs 1, marked hosts 0. No crash or Room migration error is in logcat (checked below). Preferences already hold listenMulticast = true (the Director turned the listener on himself).

Baseline from the 3B post-QA backup (schema 3): networks 4, hosts 40 (38 on CHi, 2 on AddysgConwy__GUEST), one QA note on 10.0.3.59. Differences for the Director to confirm as his own actions: networks 2 (172.17.248.1/22), 3 (10.0.0.1/24) and 4 (AddysgConwy__GUEST) are gone (only Forget this network deletes a network row); 8 CHi hosts are gone (10.0.3.22, .92, .136, .138, .162, .229, .250, 192.168.1.35) and the QA note on 10.0.3.59 is gone (Remove device or an edit). Nothing in this QA deleted them; the backup was taken first.

## Instrumented runs

| Class | Result |
|---|---|
| ui.NavBarTest | FAIL 6/6 on 36db725 (bar absent in the harness: the test activity reports the IME visible at zero height on Android 17; the real app shows the bar). Fixed in 20f04e7 (shell reads the keyboard height). PASS 6/6 on 20f04e7 |
| ui.NavBadgeTest | PASS 4 |
| ui.NavTransitionTest | PASS 3 |
| ui.scans.ScansContentTest | FAIL 1/8 on 36db725 (the eighth Stop sits past the Running block's 40 percent cap; the assertion predates review fix S2). Test now scrolls to it. PASS 8/8 on 20f04e7 |
| ui.detail.DetailContentTest | PASS 31 |
| ui.settings.SettingsContentTest | PASS 7 |
| ui.manual.ManualToolsContentTest | PASS 9 |
| ui.wordlists.WordlistsScreenTest | PASS 11 |
| ui.home.HomeContentTest | PASS 31 |
| data.DirectorCopyMigration34Test | PASS 1 (on a pushed copy of the 3B schema 3 backup, main file only; the copy was deleted by the test; netprobe.db untouched) |
| data.Migration34Test 2, ToolMigrationTest 2, MigrationTest 1, ManualHostDaoTest 9, ToolDaoTest 9, DaoTest 19, RemoveDeviceTest 6, RadarIdentityGuardTest 4 | PASS |
| ui.KeepScreenOnTest 1, ControlsMotionTest 4, ListMotionTest 4, home.RadarBlockTest 14, home.RadarStateTest 2, settings.ExtendedSectionTest 8, settings.SettingsListenTest 3, settings.SettingsRowsTest 2, settings.SettingsToolsTest 2, theme.TypographyTest 1, tools.DirBruteSheetTest 6, tools.RouteLineTest 2, tools.ToolResultsSheetTest 8, tools.ToolsBlockTest 5 | PASS |
| wordlists.UrlWordlistFetcherDeviceTest 5, NeighbourSpikeTest 1 | PASS |
| ui.HomeScreenTest 4 (last; opens the real database) | PASS. It ran a radar scan on the Director's data (31 devices after it). |
| data.DirectorCopyMigration23Test, DirectorCopyMigrationTest | NOT RUN: they assert a schema 2 or schema 1 copy. |

Total on the device: 262 tests, 0 failures after the two fixes (268 counting the two reruns).

## Device checks (3D plan Task 12, spec 9.3)

Driven over adb (uiautomator taps and screenshots in the session scratchpad; the device shots are not committed). Stop pills were always pinned: the Running block on Scans, the Stop pill on a device page, the radar Stop on Devices.

| # | Check | Result |
|---|---|---|
| 1 | NAS brute-force, Back, badge, Stop from Scans | PASS. The common-short list finished in seconds and its end message landed on the Devices screen ("Directory brute-force finished: 4 found in 294 paths"). With raft-medium-words-lowercase: badge "1" on Devices, Scans showed "Directory brute-force on CHNAS:5000, 2309 of 56293, 3 found, 24 s" with a progress line; Stop from Scans ended it as "Stopped after 7067 of 56293, 4 found, just now"; badge gone. |
| 2 | Full port scan, Back, Scans row, open device, Back, end message | PASS. "Port scan on Weather Station, 5506 of 65535, found 1" on Scans; tapping it opened the device with "Back to Scans", the live bar and Stop, no nav bar; Back returned to Scans. The Pi-hole scan's end ("Port scan finished: Done in 53 s, 5 open ports") showed as a snackbar on the Scans screen while that device page was closed. A Recent tool row tapped by accident opened its results sheet on Scans ("Run 1 of 1", Share). |
| 3 | Third port scan refused | PASS. With two Full scans running, CHNAS showed a dimmed Scan and "Two port scans are already running, stop one in Scans"; a tap did nothing. |
| 4 | Load check | PASS. Together: Full on Weather Station (done in 5 min 10 s, 1 open), Full on pi.hole (53 s, 5 open), Quick scan all (29 s, 29 devices scanned, the two running hosts skipped), brute-force on CHNAS:5000 (stopped by hand at 12592 of 56293, 5 found). Logcat: 0 "Skipped N frames", no ANR. Parity: the solo Full scan of pi.hole took 52 s and reported 4 open; the device page lists 22, 53, 80, 443, 5900 (the union); one port was missed by the solo run, not by the loaded one, so load did not degrade results. MAX_PORT_SCANS stays 2. |
| 5 | Radar scan on Scans, Stop there | PASS. "Radar scan, probing 61 of 254, 5 s"; Stop from Scans left Devices reading "Stopped after 7 s, 1 on another subnet". Arm resume not observed (3A behaviour, unchanged). |
| 6 | Quick scan all | PASS. "4 of 31 devices, 5 s" advancing; Stop from Scans: "Quick scan all, Stopped after 7 s". |
| 7 | Root state restore, Back from Tools | PASS. Devices kept the Interesting filter and the search text, Tools kept both fields, Settings kept its scroll position; Back from Tools went to Devices, Back again left the app (launcher on top). |
| 8 | Background pause | PASS. Done count 4097 at Home, 4610 on return after 30 s (in-flight probes and the ON_STOP lag), 7811 fifteen seconds later; FLAG_KEEP_SCREEN_ON on NetProbe's window and a SCREEN_BRIGHT_WAKE_LOCK attributed to it while the scan ran. |
| 9 | Process death | PASS for the port scan: after am kill and relaunch, Scans showed "Port scan on Weather Station, Interrupted when NetProbe closed"; the in-memory outcomes (pi.hole, Quick scan all, radar) were gone; stored tool runs remained; the restored device page showed Scan, not Stop. Tool-run half: see below. |
| 10 | Forget disabled during a port scan | PASS (overflow showed "Forget this network" dimmed with three scans running; not tapped). |
| 11 | Remove device from a page opened via Scans | PASS. Popped to Scans with "Removed pi.hole" and Undo; Undo restored pi.hole with its 5 open ports. |
| 12 | URL import on Scans | NOT RUN: no wordlist URL approved by the Director yet (3C-1 Task 11 U1 to U7 pending). Covered by UrlImportLiveWorkTest and UrlWordlistFetcherDeviceTest (5 device tests green). |
| 13 | Bar hiding and font scale | PASS. No bar on a device page, on Wordlists ("Back to Tools") or while typing in Devices' search; at font scale 1.3 the four labels fit on one line and the last rows of Devices and Scans clear the bar. Reduced motion NOT RUN by hand (NavTransitionTest reduced case green). Font scale restored to 1.15. |
| 14 | Clear history and Undo; Reset to defaults | PASS. Clear left "Nothing running" and "History cleared" with Undo; Undo brought the list back. Reset to defaults ("Settings reset to defaults", Undo) left the Scans list as it was; Undo restored listenMulticast = true in the preferences. |
| 15 | Wi-Fi off during a port scan | PASS. "Port scan on Weather Station, Network changed, scan stopped". Wi-Fi re-enabled, back on CHi. |
| 16 | Icons and labels | FOR THE DIRECTOR: Devices (target rings), Scans (pulse card), Tools (spanner), Settings (gear). |

## 3C-2 Task 12 checks (manual tools and the listener), run in the same session

Fix applied during this QA: the Tools tab's IP and Port fields reordered or dropped typed characters ("10.0.3.253" came out as "0.0.3.2531" even at human speed) because they bound the view model's StateFlow directly; they now keep their own text (commit fcab7ee, ManualToolsContentTest 9/9 on the device, typing verified). The Home search and the Detail custom-ports field use the same pattern but did not misbehave; parked as M-QA1 for the Director.

| # | Check | Result |
|---|---|---|
| 1 | Manual tools from the Tools tab | PASS. HTTP fingerprint 10.0.3.170:80: "No HTTP response on this port" (the Pi-hole's port 80 also ignored plain GETs from the PC and from the phone's netcat during QA; the same tool on CHNAS:80 gave "No product named, 4 findings"); TLS certificate 10.0.3.1:443: "UbiquitiRouterUI, expires 5 Sept 2028, self-signed"; SMB session 10.0.3.59:445: "SMB 3.0.2, signing not required, anonymous refused, guest refused"; SNMP walk 10.0.3.1:UDP 161: "No SNMP answer to public". Each run showed its progress under the pinned Stop, added a history row, and its results sheet opened from the row with "via Wi-Fi" under the title ("Run 1 of 1"); Share opened the system share sheet with the NetProbe export text. "Known device: <name>, results also show on its page" shows for listed addresses; an invalid address shows "Enter an address like 10.0.3.20". Hidden target: HTTP fingerprint on 10.0.3.253 (not a device) ran and left a manualOnly row; Devices stayed at 33 and never listed it. |
| 2 | Stop and busy | PASS for Stop: a brute-force on 10.0.3.175:5001 (raft list) read "Stopped after 68 of 56293" on the screenshot taken 1.2 s after the tap; the end message "Directory brute-force finished: Stopped after 68 of 56293, 0 found" showed on the visible screen. Cancelled sheet: a brute-force for 10.0.3.254 opened its options sheet; closing it left the hidden rows unchanged (10.0.3.253, 10.0.3.175). Busy snackbar: see below. |
| 3 | Route via VPN or other route (1.1.1.1:443) | NOT RUN: ruling R1 (one TLS handshake to 1.1.1.1:443) still awaits the Director; no VPN app was used. Covered by ManualRouteTest and ToolRunnerRouteTest. Known LAN targets read "via Wi-Fi". |
| 4 | Forget disabled while a hidden-row run is active | PASS. With the brute-force running on the removed Weather Station (a hidden row), the Devices overflow showed "Forget this network" dimmed; not tapped. |
| 2b | Busy snackbar and promotion | PASS. With a device-page brute-force running on CHNAS, Run on the Tools tab for 10.0.3.59:80 showed "A tool is already running on this device". Promotion: the Weather Station was removed (Remove device), a brute-force from Tools on 10.0.3.175:5001 left a hidden row (Devices stayed at 32), and the next radar scan promoted it: manualOnly 0, a normal row on Devices, both runs (ids 18 and 19, route WIFI) attached to the new host id. Gap M-QA2: the promoted page read "Unknown device, No open ports yet, run a scan" with no Tools block, so its history is not visible until a port scan finds a port (the 3B Tools block only shows when a port offers a tool). |
| 5 | Listening (10 min, no scan) | PASS. Listening was already on in the Director's preferences. 15-minute window on Devices (16:06 to 16:21) with no scan: baseline max lastSeen 1792595351141 and 33 rows; afterwards max lastSeen 1792596274240 and 33 rows (nothing invented, sightings refreshed). Heard: 10.0.3.217 (Google Home Mini), 10.0.3.196 (Nest Hub), 10.0.3.248, 10.0.3.7 (Nest Mini), 10.0.3.67 (HP printer), 10.0.3.175, 10.0.3.59 (mDNS and SSDP announcers). Lock "netprobe-listen": held with the app in the foreground (Multicaster entry in dumpsys wifi), gone 3 s after Home, held again on return, gone after am force-stop (process death, G8). Battery lines of the on window in K:/claude/netprobe_db_backup/phase3d/battery-on.txt (history shows +wifi_multicast while listening). |
| 6 | Listening during a scan, Wi-Fi off and on, scan parity | PASS. Status line "Listening for mDNS and SSDP"; during a radar scan "Paused while a scan runs"; after it "Listening for mDNS and SSDP" again, no duplicate IPs, only the netprobe-listen Multicaster entry left (no netprobe-mdns). Wi-Fi off: "Not listening: connect to Wi-Fi or Ethernet" and the lock gone (within 3 s on the second attempt; on the first attempt the line and the lock still showed Listening 6 s after svc wifi disable and were not re-polled, so the switch-over can lag behind the system's network callback); Wi-Fi on: "Listening for mDNS and SSDP" and the lock held again. Parity: mDNS-evidenced hosts in the last scan were 8 with listening on and 8 with listening off (33 hosts both times). Listen off: status line gone, listenMulticast false, lock released. |
| 7 | Battery | Listening-on window recorded (battery-on.txt); the listening-off window runs at the end of the session (battery-off.txt). |
| 8 | Network change mid-listen | NOT RUN, no disposable network: covered by ListenControllerTest "packets from network A queued at a change are discarded and B's are dropped until Home catches up" and HeardWriterTest "a packet for another network or another row is dropped". |
| 9 | Settings reset turns listening off | PASS (seen under 3D check 14: Reset to defaults took the switch off, Undo put it back; the preference followed). |

## 3C-1 Task 11 checks (subset run in this session; the full list needs the Director's URL approval and a second network)

| # | Check | Result |
|---|---|---|
| 1 | Settings sliders, Default markers, Reset, Undo | Markers: dragging Sweep concurrency to 416 showed "Default 128" (content description "Reset Sweep concurrency to 128") and the preference followed; see the next row for the marker taps. Reset to defaults and Undo were exercised under 3D check 14 ("Settings reset to defaults" with Undo; Undo restored the previous values, including the listener switch). Observation M-QA3: an adb tap on the slider track moved the thumb to 352 without committing (preference stayed 128, no marker) until a drag followed; the Director should try a finger tap on a slider, since a real tap that does not commit would leave the thumb and the stored value apart. |
| 8 | Stop while an extra range is swept | PASS. Aggressive scan stopped at 25 s: hero "Stopped after 25 s, 1 on another subnet", notice "Stopped at 10.0.4.0/24", the 33 hosts stayed, and the only NetProbe multicast entry left was the listener's netprobe-listen (listening was on). |
| 9 | Mid-run toggle | PASS for the running scan: with Aggressive off a scan ran home-only ("probing 186 of 254"); Aggressive was switched on during it and the grouping, progress and result ("Scanned in 13 s, 1 on another subnet") did not change. The "Home follows the live settings at once" half is not distinguishable on this network without another scan and was not pursued. |
| 2 to 7, 10, 11 | Scan baselines, Smart discovery counts, URL import, wide mask | NOT RUN in this session (the Director asked for a lighter QA load; URL import also needs his approval of U1 to U7). Observed in passing: Aggressive scans on CHi took 96 to 102 s and reported "5 other ranges checked" with 18 hosts on other subnets; 10.0.0.0/24 was "nothing found". |

## Wrap-up

Restored: the Director's preferences from the backup (MD5 checked), font scale 1.15, `svc power stayon false`. Not restored on purpose: the QA rows this session created on his network, listed so he can remove any he does not want: hidden row 10.0.3.253 (manual-only), 10.0.3.175 re-created by the promotion (its earlier marks were none), and the tool runs and port scans of this session in Scans and in the device histories. The battery-off window (3C-2 item 7 second half) was not run.

Code changes made during this QA, both on phase3d: 20f04e7 (bar visibility reads the keyboard height, Scans test scroll) and fcab7ee (Tools fields keep their own text). APK with both: K:/claude/netprobe_apk/netprobe-0.3.0-fcab7ee-debug.apk (md5 b7377e862a49f7c383fa4b752defa119).

For the Director: networks 2 to 4 and eight CHi hosts present in the 3B post-QA backup were gone before this QA started (Forget or Remove on his side?); tab icons (check 16); the wordlist URLs U1 to U7; ruling R1 (1.1.1.1:443); M-QA1 (Home search and Detail custom-ports fields share the flow-bound text pattern), M-QA2 (promoted host shows no Tools block until a port scan), M-QA3 (slider tap without commit, to try with a finger).
