# Task 17 review (f1abb09..8693b56)

Verdicts: Spec compliance PASS. Task quality APPROVED. 0 Critical, 0 Important, 4 Minor.

## Spec compliance
- Keys: extEnabled / extGatewayProbe / extMax via AppSettings.update (Settings.kt:36-38, extMax coerced 1..16); extManual.<id> via ManualRangeStore.key (Settings.kt:59). HomeViewModel.kt:128,175 reads the same keys/state. OK
- Manual field per current network (SettingsScreen insert uses network?.id; rememberSaveable keyed by networkName); null network: disabled + "Connect to a network to edit its ranges". OK
- Validation uses CidrParser.parse/merge/units only (no second parser). "Normalised to X" from CidrParser. Duplicates collapsed with "Duplicate removed" note, own network rejected (ERR_OWN). OK
- Max-16: stepper buttons gated by value>min / value<max, bounds from AppSettings.EXT_MAX_MIN/MAX; load and save also coerce. OK
- Master off: stepper, probe row, manual editor at DISABLED_ALPHA and disabled (SwitchRow enabled=false, IconButtons enabled=false), never hidden. OK
- Strings match spec 8.3 verbatim; capLine uses 25 s/range. OK
- 48 dp: IconButtons default 48, list rows and Add button heightIn(Dimens.touch), Stepper row 56. OK
- Tests: ExtendedCopyTest 3, ExtendedSectionTest 6 present and meaningful (per-network isolation, bad CIDR, own network, no network, master off, stepper+beyond cap). I did not run them (no instrumented runs allowed); relied on the report. 
- No em-dashes in the 3 source/test files; no build file changes (no new deps).
- Controller rulings applied: defaults off/off/4/16; error brought into view (BringIntoViewRequester, ExtendedSection.kt:214-222); disabled Add uses border colour (canAdd, :226,:231); no imePadding premise.

## Device check log (Pixel 7 Pro, build 8693b56, debuggable so run-as worked)
1. Start state read from shared_prefs/netprobe.xml: extEnabled=false, extGatewayProbe=false, extMax=4, no extManual keys.
2. Settings > master ON: file shows extEnabled=true; rows enabled.
3. Typed 8.8.8.0/24 with keyboard up: red field, "Only private ranges are allowed" fully visible above the keyboard, Add dim. PASS
4. Replaced with 10.0.4.1/24: "Normalised to 10.0.4.0/24" shown, Add teal. Tapped Add: file has extManual.1 = 10.0.4.0/24, row shown with "Remove 10.0.4.0/24".
5. Tapped remove: extManual key gone (grep count 0).
6. Master OFF: rows dimmed and disabled (screenshot read).
Final stored state: extEnabled=false, extGatewayProbe=false, extMax=4, no extManual keys. Matches the start. Device left on Settings screen.

## Findings
- Minor 1: SettingsScreen.kt (inserted `val local = remember(s.maxPrefix) {...current()...}`): not keyed on the network, so if Wi-Fi changes while Settings is open the own-network check keeps the old local subnet (or null if no network at composition time), letting the user add the new local range. Derivation re-check (spec 4.4) still drops it, so impact is cosmetic. Key it on network?.id.
- Minor 2: ExtendedSection.kt:210 `notes` is remember'd, not keyed on networkName, so a "Duplicate removed" note survives a network switch.
- Minor 3: No test covers the stepper floor/ceiling (1 and 16 disabling) or that Settings writes land under the exact keys; the keyboard clearance fix (BringIntoView) has only screenshot/manual evidence. Add a stepper-bounds test.
- Minor 4: Error text still shows in a dimmed/disabled editor if text was typed before the master toggle was turned off (error computed independent of `enabled`). Harmless.

## Plan-text conflict
Brief premise "column keeps .imePadding()" is false; the code uses contentWindowInsets navigationBars.union(ime) (Task 8). Ruled by controller; behaviour verified on device (keyboard check above).
