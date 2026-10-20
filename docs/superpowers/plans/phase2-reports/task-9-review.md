# Task 9 review (ac89432 + 081a0ca)

Spec compliance: PASS. Quality: PASS with Minor findings.

(a) Touch target and sharing: the padding change is on the inner 40 dp visual Box only (Controls.kt, `.padding(horizontal = SEGMENT_TEXT_PAD)`); the outer Box with `weight(1f).heightIn(min = Dimens.touch).selectable` is untouched, so the 48 dp target and equal widths are unchanged. PinnedSegmented is used only by StatusSegments and the profile row (DetailComponents.kt:100,156) and the Settings prefix control (SettingsScreen.kt:66). Home filter chips do not use it. Settings labels are short ("/22"), so 2 dp is harmless there.

(b) Test: segmentLabelsStayOnOneLineAtFontScale13OnAPixel7Pro (DetailContentTest.kt:134) is meaningful. It uses 411 dp, 1.3 scale, checks lineCount == 1 for all 7 labels with every status active (widest W500). The executor reports it failed on device before the fix ("Investigated lines with UNMARKED active expected:<1> but was:<2>") and passed after (13/13). I did not re-run it (no adb). Font scale and size are set via composition-scoped DeviceConfigurationOverride, same as existing tests, so no global state leaks.

(c) Evidence: every png named in the QA record exists in docs/qa/shots/phase2/ (checked by script, none missing). Opened a10-detail-top-130 and a10-detail-scanning-130: "Unmarked / Interesting / Investigated / Ignored" and "Quick / Full / Custom" are all on single lines.

(d) No em-dashes in the record, Controls.kt, the test, or the two commit messages; no attribution lines.

Findings
- Minor, Controls.kt SEGMENT_TEXT_PAD: at 1.3 "Investigated" sits roughly 2 dp from the neighbouring active fill (visible in the screenshot); it is tight. Already recorded as note F. A smaller label or 3 dp would be the alternative if the Director objects.
- Minor, DetailContentTest.kt:134-: the test only covers DetailContent; the Settings /22 control at 1.3 is not asserted (low risk, labels are short).
- Minor, QA record: "Finished 58 tests" vs 57 started is explained as AGP reporting; acceptable.
