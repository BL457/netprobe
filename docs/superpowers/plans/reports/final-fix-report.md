# Final-review fix wave (Ada)

Branch `mvp`, base e9e6641, HEAD 8eff65c. All items 1 to 9 done. Schema unchanged (version 1).

## Commits
- 5366746 Key network identity on gateway MAC instead of BSSID
- 5ef5312 Disable backups; stop scans on network change; add quick scan stop
- 635395c Rebuild identity from stored services; parse OpenSSH for Windows; show DNS servers
- 8eff65c Compute own-phone label at row time; tappable location hint; harden LAN fetches

Commits are grouped by file. HomeViewModel and DetailViewModel carry several items each, so only HEAD is build-verified; the intermediate commits do not build on their own.

## Per item
1. C1. `NetworkSignature.of(env, gatewayMac)` = sha256(lowercase MAC or "" + gatewayIp + "/" + prefix). `identityKey(env)` = gatewayIp/prefix. `GatewayMac.lookup` (new, engine/neigh) runs `Probe.connect(gw, 80, 300)` on IO, then `NeighbourTable.dump()`, and picks the gateway entry. It is used in `HomeViewModel.discover` and in the env collector that calls `ensure`. `NetworkRepository.ensure(env, gatewayMac)` first matches the signature. If nothing matches, it adopts the single row with the same gatewayIp and prefix (updating signature, ssid and displayName). Otherwise it inserts. **One refinement beyond the ruling:** a row is not a candidate if its stored gateway host (the hosts row at gatewayIp) has a MAC that differs from the current gateway MAC. Without this, a second client site on the same 192.168.1.1/24 would be merged into the first site's inventory. No schema change; it reads the existing hosts.mac. `ScanController` compares `identityKey` only. The Settings text now says the location permission is for display only.
   Tests:
   - NetworkSignatureTest (7): BSSID and SSID do not change the signature, the MAC does, MAC case is ignored, a null MAC gives sha256("gw/prefix"), and identityKey.
   - GatewayMacTest (2).
   - HomeViewModelTest roaming test: a BSSID change or Location off keeps the scan running; a prefix change stops it.
   - DaoTest: locationGrantKeepsTheSameNetwork (replaces the old adoption test; also covers a roam and Location off keeping the row), ensureAdoptsARowKeyedByTheOldRule, anotherSiteWithTheSameAddressingIsNotMerged.
2. I1. Manifest: allowBackup=false, fullBackupContent=false, dataExtractionRules=@xml/data_extraction_rules. The rules file excludes every domain from both cloud backup and device transfer.
3. I2. `ScanController.start(work)` now takes the work to run on each start. Detail port scans and Quick scan all each have their own ScanController watching the env StateFlow. When env goes null or the gateway/prefix changes, the scan is cancelled with "Network changed, scan stopped". While Quick scan all runs, the menu shows "Stop quick scan", which calls `stopQuickScan()` (controller.stop(), which cancels the job). quickScanning is the controller's scanning flag, cleared in its finally.
   Tests: `a detail port scan is cancelled when wi-fi switches` (no probe after the switch) and `quick scan all stops on request and on network loss` (cleanup runs, the flag clears, no more hosts are scanned).
4. I3. `HostEvidenceBuilder.build(host, stored, newPorts, newServices, oui)`. Open ports are the stored ports plus the new ports plus the new service ports. Services merge field by field, so a null never erases a stored product, version or detail. All three call sites use it with `PortRepository.servicesFor(hostId)`. Tests: HostEvidenceBuilderTest (3, including "a narrow rescan does not downgrade" for Linux (Ubuntu)) and DaoTest servicesForReturnsStoredPorts.
5. I4. `BannerParsers.ssh` parses OpenSSH_for_Windows_8.1 as product OpenSSH_for_Windows, version 8.1; OpenSSH_9.6p1 is unchanged. `Cpe.forService` treats OpenSSH_for_Windows as OpenSSH (cpe:2.3:a:openbsd:openssh:8.1:...). SyntheticEvidence now puts the sshBanner sample in both product and detail. New DeviceIdentifierTest: `ssh-windows fires on a parsed windows banner`. RED shown: with the parser change reverted, 3 of these tests fail.
6. I5. `networkDetailLine(env)` gives "ip/prefix  gateway X  dns a, b" (the first two DNS servers). Tests: NetworkDetailLineTest (2).
7. D3. `RowRules.ownIdentity()` is gone. `row()` labels the phone's own address at build time: "This phone", "Android device", PHONE type. Discovery now identifies the phone like any other host. Detail shows "This phone" as the title, and `lookupVulnsForDevice` refuses with "This is your phone; nothing to look up". RowRulesTest covers:
   - the own label regardless of the stored identity
   - a custom name taking priority
   - other addresses keeping their identity
   - a DHCP move
8. D4. The placeholder now reads "Wi-Fi (tap to show the name, needs location)". A tap requests ACCESS_FINE_LOCATION via rememberLauncherForActivityResult, then `refreshEnv(granted)` re-reads `networkEnvMonitor.current()`. Added: if the permission is granted but there is still no SSID, it shows "Turn on Location to show the Wi-Fi name"; if denied, "Wi-Fi name stays hidden without location permission".
9. Minors.
   - (a) UpnpFetcher and HttpTitleFetcher use `openConnection(Proxy.NO_PROXY)`.
   - (b) UpnpDescriptionParser refuses any input containing `<!DOCTYPE` (case-insensitive) before parsing; the existing test covers it.
   - (c) `RowRules.parseIpv4` is a pure literal parser, so RowRulesTest no longer does DNS. The test also builds its subnet with getByAddress.

## Tests
- JVM: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL. 216 tests, 0 failures, 0 skipped (was 198).
- Device DaoTest, Pixel 7 Pro 28221FDH300AP9: `OK (10 tests)`. The tests were ensureIsRaceSafe, portCountsGroupByHost, uniqueIndicesRejectDuplicates, anotherSiteWithTheSameAddressingIsNotMerged, upsertKeepsAnnotationsAndRefreshesScanData, ensureAdoptsARowKeyedByTheOldRule, macRankDecidesAndMdnsTxtUnionsAcrossEmissions, annotationsSurviveConcurrentUpserts, locationGrantKeepsTheSameNetwork and servicesForReturnsStoredPorts.
- I ran these through installed APKs and `am instrument -w -e class ...data.DaoTest`, not `connectedDebugAndroidTest`. The Gradle task uninstalls the app afterwards, which would wipe the data needed for the adoption check. The ledger shows that is what happened in the last round.

## Device verification (adoption)
- On arrival, the phone's DB had no CHNAS mark or note (13 hosts, all UNMARKED). The earlier instrumented run's uninstall had wiped them, so the dispatch's assumption that they were there was wrong.
- I recreated the starting state on the old build:
  - built e9e6641 in a scratch worktree and installed it with `install -r`
  - granted location and scanned; row 1 got the old BSSID-based signature c4eec0f7...
  - marked CHNAS Interesting with the note "QA note NAS" and saved a copy of the DB (in the scratchpad)
- Upgraded to HEAD with `install -r`, launched, tapped Scan and waited 20 s. Network row 1 was adopted in place: its signature is now 6122bb04... = sha256("e0:63:da:26:f1:37" + "10.0.3.1/24"). There is still 1 network, and CHNAS (networkId 1) is INTERESTING with "QA note NAS". The stale stored "This phone" vendor on 10.0.3.242 was replaced by its real (empty) identity, and the row still reads "This phone" because RowRules computes it.
- Turned system Location off (`cmd location set-location-enabled false`), relaunched and scanned: still the same single row, CHNAS intact, hint shown. With e9e6641 this was the C1 failure. Location was turned back on afterwards.
- Panel reads "10.0.3.242/24  gateway 10.0.3.1  dns 10.0.3.170". This network only advertises one resolver (the Pi-hole), so one address shows; the two-server case is covered by the JVM test.
- Quick scan all: while it ran, the menu showed "Stop quick scan", and Stop ended it (no progress bar, "15 shown").
- Own-phone Detail: "Look up vulnerabilities" shows "This is your phone; nothing to look up".
- No FATAL or AndroidRuntime lines in logcat.

Screenshots (docs/qa/shots):
- final-fix-home.png
- final-fix-home-phone.png (the This phone row)
- final-fix-chnas.png (Interesting + QA note NAS)
- final-fix-phone-vuln.png
- final-fix-quick-stop-menu.png
- final-fix-quick-stopped.png
- final-fix-location-off.png

## Not done / caveats
- Location hint tap: the launcher and the refresh callback run (the "stays hidden" snackbar appeared). But Android would not show the permission dialog on this install, even after `pm revoke` and `pm clear-permission-flags user-set user-fixed`, so I never saw the name appear after a grant on device. The permission is left revoked, as it was on arrival.
- No real AP or band roam and no second network on device (QA step 7). Roaming is covered by JVM tests and by the Location-off device check, which fails the same way.
- The I2 VM wiring is only tested through ScanController. The VMs take AppContainer (Context), so they have no JVM tests.
- Quick scan all still includes off-subnet hosts (M8, not in this wave).
- The "Found n" counter and the other minors outside 9a to 9c are unchanged.
