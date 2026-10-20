# Task 17 report: Extended discovery settings section

Status: DONE_WITH_CONCERNS. Commits af71329 (code + tests), 112360f (screenshots).

Implemented: ExtendedSection.kt (ExtendedCopy, ManualCap, ExtendedSection, Stepper, ManualEditor) and the insert in SettingsScreen.kt between Large networks and Permissions, verbatim from the brief. Tests ExtendedCopyTest (3) and ExtendedSectionTest (6 instrumented in the file, device reported "Finished 6 tests").
Judgement calls: none to the code. Brief says the column keeps imePadding(); the real code (Task 8) uses contentWindowInsets navigationBars.union(ime), so no imePadding exists; I left that as is.

RED: JVM `Unresolved reference 'ExtendedCopy'` / `'ManualCap'` (ExtendedCopyTest.kt lines 9-19), BUILD FAILED; device `Unresolved reference 'ExtendedSection'` in compileDebugAndroidTestKotlin, BUILD FAILED.
GREEN: JVM ExtendedCopyTest BUILD SUCCESSFUL; device `Starting 6 tests on Pixel 7 Pro - 17 / Finished 6 tests`, BUILD SUCCESSFUL (leaveApksInstalledAfterRun accepted). Full `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.

Screenshots (docs/qa/shots/phase2/):
- t17-settings-extended-off.png: section, master off, cap/probe/manual rows dimmed at 0.5 and disabled, "Manual ranges for CHi".
- t17-settings-extended-on.png: master on, cap stepper (4, "At most 4 extra /24 networks (about 2 minutes)") and probe row enabled.
- t17-settings-bad-cidr.png: 8.8.8.0/24, red field, "Only private ranges are allowed", Add disabled-looking? (Add range button drawn teal; see concern 2).
- t17-settings-normalised.png: 10.0.4.20/24 with "Normalised to 10.0.4.0/24".
- t17-settings-manual-list.png: list row 10.0.4.0/24 with remove X, field cleared with placeholder.
- t17-settings-keyboard.png: keyboard up with 8.8.8.0/24: field and label visible above the keyboard, but the inline message "Only private ranges are allowed" is CUT OFF by the keyboard (only the top of the text is visible).

Settings restored: yes. Prefs read back from shared_prefs/netprobe.xml: extEnabled=false, extGatewayProbe=false, extMax=4 (default, never changed), zero extManual keys (entry removed via its X); manual field empty.

Concerns:
1. KEYBOARD CLEARANCE (the brief's stop condition): with the keyboard open the field and its label clear it, but the supporting error text below the field is hidden under the keyboard. The scaffold insets pad the column but the scroll does not bring the supporting text into view. Needs a Director decision / follow-up (e.g. BringIntoView on the supporting text, or extra bottom scroll space when the IME is up). I did not change the code beyond the brief. The message is visible once the keyboard is dismissed (bad-cidr shot).
2. In t17-settings-bad-cidr.png the Add range button looks enabled (teal) with an invalid entry. The instrumented test asserts it is not enabled and passes, so this is likely the drawn teal on a disabled TextButton text colour logic: the Text colour uses `enabled` (section) not the button's enabled state. Cosmetic: the button is disabled but does not look it. Not fixed (brief code verbatim).
3. Network name shows "CHi" (the real stored displayName).
4. Hidden-LF warnings from git (CRLF conversion) only.

Files: app/src/main/java/.../ui/settings/ExtendedSection.kt (new), SettingsScreen.kt (modified), app/src/test/.../ExtendedCopyTest.kt, app/src/androidTest/.../ExtendedSectionTest.kt, six t17-*.png. Pre-existing uncommitted docs/superpowers/specs/2026-10-19-radar-ui-design.md left untouched.

## Pre-review fix

Concern 1: the error/helper text now carries a BringIntoViewRequester, triggered by a LaunchedEffect keyed on the message and on the IME being up. t17-settings-keyboard.png recaptured and read: field, label and "Only private ranges are allowed" are all visible above the keyboard.
Concern 2: the Add range text colour now follows the button's own enabled state (canAdd). t17-settings-bad-cidr.png recaptured and read: the button is dim with the invalid entry. No colour assertion added (not cheap to read in Compose test; the existing test already asserts not enabled).
Tests: JVM suite + assembleDebug BUILD SUCCESSFUL; device ExtendedSectionTest "Finished 6 tests", BUILD SUCCESSFUL (leaveApksInstalledAfterRun=true). Build installed.
Settings restored: extEnabled=false, extGatewayProbe=false, extMax=4, no extManual keys (none ever added in this pass), field empty (read back from shared_prefs).
