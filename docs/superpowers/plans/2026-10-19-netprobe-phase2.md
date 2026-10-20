# NetProbe Phase 2 Implementation Plan: Radar UI, then Extended Discovery

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Part A gives NetProbe its agreed visual identity: a dark slate Radar theme with IBM Plex Sans, a radar scan control with HERO, SCANNING and SETTLED states, a grouped device list with type edges, a restyled Detail screen with a pinned scan bar and port action words, and a restyled Settings screen. Part B lets a scan reach beyond the phone's own range: evidence-led, gateway-probe and per-network manual candidate ranges, each swept through a phantom-range guard on sockets bound to the Wi-Fi network, grouped on Home by range.

**Architecture:** Same single module and MVVM shape as the MVP. Part A is UI only: theme tokens and pure text helpers (JVM-tested) feed two stateless content composables (`HomeContent`, `DetailContent`) that thin screen wrappers drive from the existing view models; the only view-model change is five read-only `HomeUiState` fields. Part B adds pure range logic (`CidrParser`, `CandidateRanges`, `RangeRules`, `GatewayTargets`, `PhantomGuard`, `NeighbourRules`) and extends `DiscoveryEngine.discover` with an extended phase that runs after every local strategy has joined, behind function-typed inputs so every path is tested with fakes. No schema change.

**Tech Stack:** Unchanged from the MVP: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00 (foundation 1.8.2, material3 1.3.x), Room 2.7.1, kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, JUnit 4, Turbine, AndroidX test, espresso-core 3.7.0. No additions.

**Spec:** `docs/superpowers/specs/2026-10-19-radar-ui-design.md` (binding for Tasks 1 to 9) and `docs/superpowers/specs/2026-10-19-extended-discovery-design.md` (binding for Tasks 10 to 20). Their Gruff reviews (`2026-10-19-radar-ui-gruff-review.md`, `2026-10-19-extended-discovery-gruff-review.md`) explain the reasoning; where a review and a spec differ, the spec wins. The Radar spec owns every Home string, including the extended-discovery progress line, group headers and notices; the extended-discovery spec owns the Settings copy of its section.

## Global Constraints

- `targetSdk = 30` unchanged (never above 31), `minSdk = 26`, `compileSdk = 35`. Package `com.northwalessolutions.netprobe`.
- **No downloads or installs of anything.** Gradle may resolve the Maven dependencies already declared in `gradle/libs.versions.toml`; no new dependencies at all, no version bumps, no new repositories, no Gradle plugins. Forbidden by the Radar spec section 9: `ui-text-google-fonts` (downloadable fonts reach Google at runtime), `material3-adaptive` or window-size-class, Paparazzi or Roborazzi (screenshots are `adb exec-out screencap` only), and any animation library. No subagent downloads or installs anything. The IBM Plex Sans files (`app/src/main/res/font/ibm_plex_sans.ttf`, `ibm_plex_sans_italic.ttf`) and `app/src/main/res/raw/ibm_plex_sans_ofl.txt` are already committed; no step fetches a font. Never add `kotlin { jvmToolchain(...) }` or the foojay resolver. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- Commit messages are plain text with no `Co-Authored-By` or any other attribution line.
- No disclaimers, confirmations or prompts of any kind in the UI. No dialog asks "are you sure"; no "results may be inaccurate" text.
- Scan / Stop (the radar ring, the HERO Scan circle and the Stop pill) and the Detail scan bar stay pinned outside any scrolling content. Only the device list and the port list scroll.
- Portrait lock: `android:screenOrientation="portrait"` on `MainActivity` (Task 1). Landscape is out of scope.
- Room schema stays at version 1: no entity, DAO or migration change.
- The only internet call remains the user-tapped NVD query.
- All LAN probes of the extended phase (TEST-NET pre-check, gateway probe, per-range canaries, extended sweeps and their HTTP, NetBIOS and SNMP enrichment) bind to the Wi-Fi or Ethernet `Network` (Task 10). If the bind fails, the extended phase is skipped with its notice; the local run stands.
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`, never a global `gradle`. adb is `ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"`, device serial `28221FDH300AP9`.
- Every task ends with `./gradlew :app:testDebugUnitTest :app:assembleDebug` green. Every UI task (1, 4, 6, 7, 8, 9, 16, 17, 19, 20) also installs the debug APK on the Pixel 7 Pro (`./gradlew :app:installDebug`) and captures the screenshots it names to `docs/qa/shots/phase2/` with `"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/<name>.png`. If the phone is not connected, stop and ask the Director; never skip the step.
- Instrumented tests run with `./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=<fully qualified class>`. The `leaveApksInstalledAfterRun` property stops the run from uninstalling NetProbe, which would delete the Director's stored hosts, marks and notes (the MVP ledger records that happening). If the property is rejected by this AGP version, stop and ask the Director before running any instrumented test without it. Compose tests drive `HomeContent`, `DetailContent` and the Settings rows with fake state and never touch app data; only `HomeScreenTest` launches the real activity.
- Never kill python or java processes wholesale; the Director runs other programs alongside.

## Review Focus

The five inputs most likely to bite the Director that the specs' test lists do not exercise as written. Each has a pinned test in its owning task.

1. **A filter active while scanning.** The Director picks "Interesting" and taps Scan: the chips and search must stay visible, otherwise a hidden filter keeps hiding rows during the run. Pinned: Task 2 (`HeaderRulesTest`, the pure rule) and Task 6 (`HomeContentTest.chipsStayWhileScanningWithAFilter`, `searchStaysWhileScanningWithAQuery`).
2. **Font scale 1.3 on Detail with the Custom error showing and the note expanded.** The worst-case pinned block must still leave three port rows, and the scan bar must not jump when a scan starts. Pinned: Task 7 (`DetailContentTest.worstCaseAtFontScale13LeavesThreePortRows`, `scanBarHeightDoesNotJumpWhenScanStarts`).
3. **A firewall that refuses everything.** A REJECT-ruled router or a captive portal answers for every address; unguarded, each range invents 254 hosts and 254 HTTP fetches. Pinned: Task 15 (`refuse-everything network yields phantom ranges and no extra hosts`, `accept-everything range is discarded by the sweep guard without enrichment`).
4. **A VPN active.** The bind to the Wi-Fi network fails: the extended phase is skipped with `SKIPPED_BIND_FAILED`, nothing is probed on default routing, the local result stands. Pinned: Task 10 (`ProbeBindTest.binder failure surfaces as BindFailedException and never connects`), Task 15 (`bind failure skips the extended phase and keeps the local result`), Task 16 (notice wording).
5. **Stop pressed mid-range during an extended run.** The run ends within a second, the running range becomes `CANCELLED`, hosts still held by the phantom check are dropped, and Home says "Stopped at <range>". Pinned: Task 15 (`stop mid range cancels within a second and drops held hosts`) and Task 16 (`ExtendedNoticesTest.cancelled range names the range that was running`).

## File Structure

```
app/src/main/AndroidManifest.xml                          portrait lock (Task 1)
app/src/main/res/values/themes.xml, colors.xml             dark window, no white flash (Task 1)
app/src/main/java/com/northwalessolutions/netprobe/
  MainActivity.kt                                           enableEdgeToEdge, dark system bars (Task 1)
  AppContainer.kt                                           currentNetwork (Task 16)
  ui/theme/    Tokens.kt (colours, edges, dimens), Theme.kt (scheme, Plex typography, shapes), Motion.kt (durations, reduced motion)
  ui/          Controls.kt (StopPill, ScanPill, PinnedSegmented, BackToHome, field colours), Common.kt (EmptyState), KeepScreenOn.kt, Nav.kt
  ui/home/     HomeCopy.kt, RadarModes.kt, RelativeTime.kt, ProgressLine.kt, HeaderRules.kt, RowText.kt, RadarGeometry.kt,
               RadarBlock.kt, HomeContent.kt, HomeScreen.kt (wrapper), HomeComponents.kt, HomeViewModel.kt, HomeStats.kt,
               RangeRules.kt, ExtendedNotices.kt
  ui/detail/   DetailText.kt, DetailContent.kt, DetailScreen.kt (wrapper), DetailComponents.kt
  ui/settings/ SettingsScreen.kt, ExtendedSection.kt
  ui/vulns/    VulnSheet.kt
  engine/common/    Probe.kt (binder), SocketBinder.kt
  engine/discovery/ DiscoveryOptions.kt (extended types), Sweep.kt (runAll), DiscoveryEngine.kt (extended phase), CandidateRanges.kt,
                    GatewayProbe.kt, PhantomGuard.kt, NeighbourRules.kt, HttpTitle.kt, NetBios.kt, Snmp.kt (bound sockets)
  netenv/      Ipv4.kt, SubnetOps.kt, Rfc1918.kt, CidrParser.kt, NetworkEnvMonitor.kt (binderFor)
  data/        Settings.kt (extended keys, ManualRangeStore)
app/src/test/java/...            JVM tests mirror the packages above
app/src/androidTest/java/...     Compose tests: RadarBlockTest, HomeContentTest, DetailContentTest, SettingsRowsTest, RadarStateTest,
                                 TypographyTest, ExtendedSectionTest, KeepScreenOnTest, HomeScreenTest (updated), test fakes
docs/qa/phase2-radar-ui-on-device.md, docs/qa/phase2-extended-discovery-on-device.md, docs/qa/shots/phase2/
```

---

# Part A: Radar UI

### Task 1: Radar theme: tokens, Plex typography, shapes, reduced motion, dark window and portrait lock

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Tokens.kt`, `ui/theme/Motion.kt`, `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Theme.kt` (replace), `MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/theme/Contrast.kt` (test helper), `ContrastTest.kt`, `EdgeColorTest.kt`, `ReducedMotionTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/theme/TypographyTest.kt`

**Interfaces:**
- Consumes: `DeviceType` (`engine/identify/DeviceIdentity.kt`), `R.font.ibm_plex_sans`, `R.font.ibm_plex_sans_italic` (already in `res/font`).
- Produces:
```kotlin
object NetProbeColors { val background: Color; val surface: Color; val rowDivider: Color; val borderSubtle: Color; val border: Color
    val text: Color; val textSecondary: Color; val textMuted: Color; val signal: Color; val mark: Color; val error: Color
    val discCentre: Color; val severityHigh: Color }
object EdgeColors { val router: Color; val nas: Color; val media: Color; val iot: Color; val phone: Color; val computer: Color
    val server: Color; val camera: Color; val printer: Color; val unknown: Color }
fun DeviceType.edgeColor(isGateway: Boolean = false): Color
const val STALE_EDGE_ALPHA: Float     // 0.6f, see the deviation note in Step 3
const val STALE_NAME_ALPHA: Float     // 0.6f
const val DISABLED_ALPHA: Float       // 0.5f
object Dimens { gutter, rowStart, edgeWidth, edgeHeight, edgeRadius, rowGap, rowVertical, touch, ringDiameter, ringTarget,
    discDiameter, heroButton, discInnerRing1, discInnerRing2, discCentreMask, countMaxWidth, blip, blipGlow }   // all Dp
object NetProbeText { title, rowName, summary, secondary, secondaryItalic, data, count, chip, action, scanPill, port, caption,
    groupHeader, radarCount, radarLabel, ringLabel, heroButton }   // all TextStyle
val PlexSans: FontFamily
val NetProbeTypography: Typography
@Composable fun NetProbeTheme(content: @Composable () -> Unit)   // the `dark` parameter is removed
object Motion { SIZE_MS, LABEL_FADE_MS, PILL_FADE_MS, PILL_DELAY_MS, BLIP_IN_MS, BLIP_OUT_MS, ARM_TURN_MS, ROW_ARRIVAL_MS: Int
    STATIC_ARM_DEG: Float; PROGRESS_SAMPLE_MS: Long; rowEasing: Easing; ROW_RISE: Dp }
val LocalReducedMotion: ProvidableCompositionLocal<Boolean>
object ReducedMotion { fun fromScale(scale: Float): Boolean }
@Composable fun rememberReducedMotion(): Boolean
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/theme/Contrast.kt` (test-only helper):
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2.x relative luminance and contrast ratio, plus sRGB-space alpha blending as Android composites it. */
object Contrast {
    private fun channel(c: Float): Double { val v = c.toDouble(); return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    fun over(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha), green = fg.green * alpha + bg.green * (1 - alpha), blue = fg.blue * alpha + bg.blue * (1 - alpha))
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/theme/ContrastTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of Radar spec 7.1. A token edit that breaks a floor fails the build. */
class ContrastTest {
    private val bg = NetProbeColors.background
    private val surface = NetProbeColors.surface
    private fun atLeast(min: Double, actual: Double, what: String) = assertTrue("$what is $actual, needs $min", actual >= min)

    @Test fun `text tokens meet 4_5 on background and surface`() {
        for ((name, c) in listOf("text" to NetProbeColors.text, "textSecondary" to NetProbeColors.textSecondary, "textMuted" to NetProbeColors.textMuted,
            "signal" to NetProbeColors.signal, "mark" to NetProbeColors.mark, "error" to NetProbeColors.error)) {
            atLeast(4.5, Contrast.ratio(c, bg), "$name on background"); atLeast(4.5, Contrast.ratio(c, surface), "$name on surface")
        }
    }
    @Test fun `background text on teal and yellow fills`() {
        atLeast(4.5, Contrast.ratio(bg, NetProbeColors.signal), "background on signal")
        atLeast(4.5, Contrast.ratio(bg, NetProbeColors.mark), "background on mark")
    }
    @Test fun `type edges meet the non-text 3 to 1 floor except unknown`() {
        DeviceType.entries.filter { it != DeviceType.UNKNOWN }.forEach { t ->
            atLeast(3.0, Contrast.ratio(t.edgeColor(), bg), "$t edge on background")
            atLeast(3.0, Contrast.ratio(t.edgeColor(), surface), "$t edge on surface")
        }
    }
    @Test fun `stale name and stale edge keep their floors`() {
        atLeast(4.5, Contrast.ratio(Contrast.over(NetProbeColors.text, STALE_NAME_ALPHA, bg), bg), "stale name")
        DeviceType.entries.filter { it != DeviceType.UNKNOWN }.forEach { t ->
            atLeast(3.0, Contrast.ratio(Contrast.over(t.edgeColor(), STALE_EDGE_ALPHA, bg), bg), "stale $t edge")
        }
    }
    @Test fun `radar centre text sits on the plain disc fill`() {
        atLeast(4.5, Contrast.ratio(NetProbeColors.text, NetProbeColors.discCentre), "count on disc centre")
        atLeast(4.5, Contrast.ratio(NetProbeColors.textMuted, NetProbeColors.discCentre), "found label on disc centre")
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/theme/EdgeColorTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeColorTest {
    @Test fun `every type has a colour and the ten are pairwise distinct`() {
        val colours = DeviceType.entries.map { it.edgeColor() }
        assertEquals(DeviceType.entries.size, colours.toSet().size)
    }
    @Test fun `drawn and chosen values match the spec`() {
        assertEquals(Color(0xFF35D4C8), DeviceType.ROUTER.edgeColor()); assertEquals(Color(0xFF7FA7FF), DeviceType.NAS.edgeColor())
        assertEquals(Color(0xFFC98BFF), DeviceType.MEDIA.edgeColor()); assertEquals(Color(0xFFFFB86B), DeviceType.IOT.edgeColor())
        assertEquals(Color(0xFF8FA3B5), DeviceType.PHONE.edgeColor()); assertEquals(Color(0xFF9BE07A), DeviceType.COMPUTER.edgeColor())
        assertEquals(Color(0xFFF2A0D6), DeviceType.SERVER.edgeColor()); assertEquals(Color(0xFFFF8A8A), DeviceType.CAMERA.edgeColor())
        assertEquals(Color(0xFFD6C7A1), DeviceType.PRINTER.edgeColor()); assertEquals(Color(0xFF3B5368), DeviceType.UNKNOWN.edgeColor())
    }
    @Test fun `gateway overrides the type colour`() {
        for (t in DeviceType.entries) assertEquals(NetProbeColors.signal, t.edgeColor(isGateway = true))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/theme/ReducedMotionTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionTest {
    @Test fun `only an animator scale of zero means reduced`() {
        assertTrue(ReducedMotion.fromScale(0f))
        assertFalse(ReducedMotion.fromScale(0.5f)); assertFalse(ReducedMotion.fromScale(1f)); assertFalse(ReducedMotion.fromScale(10f))
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/theme/TypographyTest.kt` (spec 10.2 test 9, instrumented because it loads the real font files):
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A font file without the tnum feature would make IPs and counts jitter; this catches it on the device. */
@RunWith(AndroidJUnit4::class)
class TypographyTest {
    @Test fun tabularStylesGiveEqualDigitWidths() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val measurer = TextMeasurer(createFontFamilyResolver(ctx), Density(ctx), LayoutDirection.Ltr)
        for ((name, style) in listOf("data" to NetProbeText.data, "port" to NetProbeText.port, "count" to NetProbeText.count, "radarCount" to NetProbeText.radarCount)) {
            assertEquals(name, measurer.measure("1111", style).size.width, measurer.measure("0000", style).size.width)
        }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.theme.*" --console=plain 2>&1 | tail -15
```
```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.theme.TypographyTest --console=plain 2>&1 | tail -8
```
Expected: the first run gives `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'NetProbeColors'`, `'edgeColor'`, `'STALE_NAME_ALPHA'` and `'ReducedMotion'`; the second gives `compileDebugAndroidTestKotlin FAILED` with `Unresolved reference 'NetProbeText'`.

- [ ] **Step 3: Implement the tokens, motion constants and the theme**

Deviation from Radar spec 2.1 and 7.1, recorded for the Director: at 0.5 alpha over #16202B the stale NAS (2.78), MEDIA (2.71), PHONE (2.62) and CAMERA (2.77) edges fall below the spec's own 3:1 floor (sRGB blending, measured while writing this plan). `STALE_EDGE_ALPHA` is therefore 0.6, the lowest tenth that keeps every edge at 3:1 or better (PHONE 3.18). The contrast test is the arbiter, as the spec intends.

`app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Tokens.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** Radar palette (Radar spec 2.1). The token `surface` is NOT the Material role `surface`: see the role table in Theme.kt. */
object NetProbeColors {
    val background = Color(0xFF16202B)
    val surface = Color(0xFF233447)
    val rowDivider = Color(0xFF1D2A38)
    val borderSubtle = Color(0xFF2A3E54)
    val border = Color(0xFF3B5368)
    val text = Color(0xFFE6EDF3)
    val textSecondary = Color(0xFFB9C7D4)
    val textMuted = Color(0xFF8FA3B5)
    val signal = Color(0xFF35D4C8)
    val mark = Color(0xFFFFD56B)
    val error = Color(0xFFFF8A8A)
    /** Plain fill under the radar count; the arm is masked out of this centre (spec 3.3). */
    val discCentre = Color(0xFF1B2836)
    /** "High" severity label in the vulnerability sheet (spec 5.3). */
    val severityHigh = Color(0xFFFFB86B)
}

/** Type edge colours (spec 2.1). Four are chosen values reviewed on device (open item 11.4). */
object EdgeColors {
    val router = Color(0xFF35D4C8)
    val nas = Color(0xFF7FA7FF)
    val media = Color(0xFFC98BFF)
    val iot = Color(0xFFFFB86B)
    val phone = Color(0xFF8FA3B5)
    val computer = Color(0xFF9BE07A)
    val server = Color(0xFFF2A0D6)
    val camera = Color(0xFFFF8A8A)
    val printer = Color(0xFFD6C7A1)
    val unknown = Color(0xFF3B5368)
}

/** Gateway always teal (D15: any ROUTER is teal too). For a gateway-only teal, return #5FB8B0 for non-gateway routers here. */
fun DeviceType.edgeColor(isGateway: Boolean = false): Color = if (isGateway) EdgeColors.router else when (this) {
    DeviceType.ROUTER -> EdgeColors.router; DeviceType.NAS -> EdgeColors.nas; DeviceType.MEDIA -> EdgeColors.media
    DeviceType.IOT -> EdgeColors.iot; DeviceType.PHONE -> EdgeColors.phone; DeviceType.COMPUTER -> EdgeColors.computer
    DeviceType.SERVER -> EdgeColors.server; DeviceType.CAMERA -> EdgeColors.camera; DeviceType.PRINTER -> EdgeColors.printer
    DeviceType.UNKNOWN -> EdgeColors.unknown
}

/** Stale edge alpha: 0.6, not the spec's 0.5, so every stale edge keeps 3:1 (ContrastTest). */
const val STALE_EDGE_ALPHA = 0.6f
/** Stale name alpha (spec 2.1); ContrastTest keeps it at or above 4.5:1. */
const val STALE_NAME_ALPHA = 0.6f
/** Disabled controls and the extended-discovery rows while the master switch is off. */
const val DISABLED_ALPHA = 0.5f

/** Spacing and sizes from the mockups (spec 2.3, 3). */
object Dimens {
    val gutter = 18.dp
    val rowStart = 14.dp
    val edgeWidth = 4.dp
    val edgeHeight = 34.dp
    val edgeRadius = 2.dp
    val rowGap = 12.dp
    val rowVertical = 9.dp
    val touch = 48.dp
    val ringDiameter = 56.dp
    val ringTarget = 64.dp
    val discDiameter = 172.dp
    val heroButton = 84.dp
    val discInnerRing1 = 28.dp
    val discInnerRing2 = 58.dp
    val discCentreMask = 40.dp
    val countMaxWidth = 96.dp
    val blip = 6.dp
    val blipGlow = 8.dp
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Motion.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Radar timing (spec 3.6). */
object Motion {
    const val SIZE_MS = 360
    const val LABEL_FADE_MS = 160
    const val PILL_FADE_MS = 160
    const val PILL_DELAY_MS = 120
    const val BLIP_IN_MS = 300
    const val BLIP_OUT_MS = 200
    const val ARM_TURN_MS = 2400
    const val ROW_ARRIVAL_MS = 600
    const val STATIC_ARM_DEG = 40f
    const val PROGRESS_SAMPLE_MS = 250L
    val rowEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val ROW_RISE = 6.dp
}

/** Provided by NetProbeTheme; tests override it without touching system settings (spec 3.7). */
val LocalReducedMotion = compositionLocalOf { false }

object ReducedMotion {
    /** Android's "Remove animations" and a developer animator scale of "off" both set the scale to 0. */
    fun fromScale(scale: Float): Boolean = scale == 0f
}

private fun readReducedMotion(ctx: Context): Boolean =
    ReducedMotion.fromScale(Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/** Re-read every time the lifecycle resumes, so a change in system settings applies on return. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    var reduced by remember { mutableStateOf(readReducedMotion(ctx)) }
    LifecycleResumeEffect(ctx) {
        reduced = readReducedMotion(ctx)
        onPauseOrDispose { }
    }
    return reduced
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Theme.kt` (replace the whole file):
```kotlin
package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.northwalessolutions.netprobe.R

@OptIn(ExperimentalTextApi::class)
private fun plex(weight: Int, italic: Boolean = false) = Font(
    resId = if (italic) R.font.ibm_plex_sans_italic else R.font.ibm_plex_sans,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Two variable TTFs (wdth, wght); weights come from the wght axis (spec 2.2). The italic file is used so nothing synthesises a slant. */
val PlexSans = FontFamily(plex(400), plex(500), plex(600), plex(400, italic = true), plex(500, italic = true))

private fun style(size: Int, weight: Int, line: Int, tnum: Boolean = false) = TextStyle(
    fontFamily = PlexSans, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = 0.sp,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/** The Radar type scale (spec 2.2). Colour is set by the component, never by the style. */
object NetProbeText {
    val title = style(20, 600, 26)
    val rowName = style(14, 500, 19)
    val summary = style(15, 500, 20)
    val secondary = style(13, 400, 18)
    val secondaryItalic = secondary.copy(fontStyle = FontStyle.Italic)
    val data = style(12, 400, 16, tnum = true)
    /** Counts share the data metrics and tabular figures. */
    val count = style(12, 400, 16, tnum = true)
    val chip = style(12, 400, 16)
    val action = style(12, 500, 16)
    val scanPill = style(13, 600, 18)
    val port = style(15, 600, 20, tnum = true)
    val caption = style(11, 400, 15)
    val groupHeader = caption.copy(letterSpacing = 0.02.em)
    val radarCount = style(30, 600, 36, tnum = true)
    val radarLabel = style(11, 400, 15)
    val ringLabel = style(12, 600, 16)
    val heroButton = style(16, 600, 22)
}

/** Every Material slot is overridden so no stock style leaks through. */
val NetProbeTypography = Typography(
    displayLarge = NetProbeText.title, displayMedium = NetProbeText.title, displaySmall = NetProbeText.title,
    headlineLarge = NetProbeText.title, headlineMedium = NetProbeText.title, headlineSmall = NetProbeText.title,
    titleLarge = NetProbeText.title, titleMedium = NetProbeText.rowName, titleSmall = NetProbeText.rowName,
    bodyLarge = NetProbeText.secondary, bodyMedium = NetProbeText.secondary, bodySmall = NetProbeText.data,
    labelLarge = NetProbeText.action, labelMedium = NetProbeText.chip, labelSmall = NetProbeText.caption,
)

/** Binding role table (spec 2.1): Material `surface` is the token `background`; containers are the token `surface`. */
private val RadarColorScheme = darkColorScheme(
    primary = NetProbeColors.signal, onPrimary = NetProbeColors.background,
    primaryContainer = NetProbeColors.surface, onPrimaryContainer = NetProbeColors.text,
    secondary = NetProbeColors.signal, onSecondary = NetProbeColors.background,
    secondaryContainer = NetProbeColors.surface, onSecondaryContainer = NetProbeColors.text,
    tertiary = NetProbeColors.mark, onTertiary = NetProbeColors.background,
    background = NetProbeColors.background, onBackground = NetProbeColors.text,
    surface = NetProbeColors.background, onSurface = NetProbeColors.text,
    surfaceVariant = NetProbeColors.surface, onSurfaceVariant = NetProbeColors.textSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = NetProbeColors.surface, surfaceContainerLow = NetProbeColors.surface, surfaceContainer = NetProbeColors.surface,
    surfaceContainerHigh = NetProbeColors.surface, surfaceContainerHighest = NetProbeColors.surface,
    outline = NetProbeColors.border, outlineVariant = NetProbeColors.borderSubtle,
    error = NetProbeColors.error, onError = NetProbeColors.background,
)

private val NetProbeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp),
)

/** Dark Radar look always; light mode and dynamic colour are out of scope (D1, D9). */
@Composable
fun NetProbeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(colorScheme = RadarColorScheme, typography = NetProbeTypography, shapes = NetProbeShapes, content = content)
    }
}
```

`app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="np_background">#FF16202B</color>
</resources>
```

`app/src/main/res/values/themes.xml` (replace):
```xml
<resources>
    <!-- Dark from the first frame: no white flash on cold start or navigation (Radar spec 2.5). -->
    <style name="Theme.NetProbe" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/np_background</item>
        <item name="android:statusBarColor">@color/np_background</item>
        <item name="android:navigationBarColor">@color/np_background</item>
    </style>
</resources>
```

`app/src/main/java/com/northwalessolutions/netprobe/MainActivity.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.northwalessolutions.netprobe.ui.NetProbeNav
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme

private const val BAR_COLOUR = 0xFF16202B.toInt()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Bars use the background token with light icons; screens apply the insets themselves.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(BAR_COLOUR), navigationBarStyle = SystemBarStyle.dark(BAR_COLOUR))
        super.onCreate(savedInstanceState)
        val container = (application as NetProbeApp).container
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
    }
}
```

`app/src/main/AndroidManifest.xml`: change only the activity element (portrait lock; `orientation` removed from `configChanges`, `screenSize` kept):
```xml
        <activity android:name=".MainActivity" android:exported="true"
            android:screenOrientation="portrait"
            android:configChanges="screenSize">
```

Until Tasks 6 to 8 add inset handling, the existing screens draw under the status bar; that is expected for this task only and is visible in the screenshot.

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.theme.*" --console=plain 2>&1 | tail -15
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.theme.TypographyTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `ContrastTest` (5), `EdgeColorTest` (3), `ReducedMotionTest` (1) pass; `TypographyTest` 1 passed on `28221FDH300AP9`; then `BUILD SUCCESSFUL`. If `TypographyTest` fails, the committed font file lacks the `tnum` feature: stop and tell the Director; no other font may be fetched. If `FontVariation` or `ExperimentalTextApi` is reported as unknown or the opt-in as an error, read the compiler message: drop the `@OptIn` line only if the compiler says the marker does not exist.

- [ ] **Step 5: Install and capture**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
mkdir -p docs/qa/shots/phase2
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s 28221FDH300AP9 shell am force-stop com.northwalessolutions.netprobe
"$ADB" -s 28221FDH300AP9 shell am start -W -n com.northwalessolutions.netprobe/.MainActivity
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t01-cold-start.png
ROT=$("$ADB" -s 28221FDH300AP9 shell settings get system accelerometer_rotation | tr -d '')
"$ADB" -s 28221FDH300AP9 shell content insert --uri content://settings/system --bind name:s:accelerometer_rotation --bind value:i:0
"$ADB" -s 28221FDH300AP9 shell content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:1
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t01-rotation-locked.png
"$ADB" -s 28221FDH300AP9 shell content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:0
"$ADB" -s 28221FDH300AP9 shell content insert --uri content://settings/system --bind name:s:accelerometer_rotation --bind value:i:$ROT
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|FATAL" | tail -5
```
Expected: `t01-cold-start.png` shows dark status and navigation bars and the Plex font; `t01-rotation-locked.png` is still portrait (auto-rotate is switched off first because Android ignores `user_rotation` while `accelerometer_rotation` is 1; both settings are restored afterwards, auto-rotate to its original value); no `AndroidRuntime` lines.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add Radar theme tokens, Plex typography, reduced motion, dark window and portrait lock"
```

---

### Task 2: Radar copy, modes, summaries, relative time, progress line and the header rule

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`, `ui/home/RadarModes.kt`, `ui/home/RelativeTime.kt`, `ui/home/ProgressLine.kt`, `ui/home/HeaderRules.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RadarModesTest.kt`, `RelativeTimeTest.kt`, `ScanSummaryTextTest.kt`, `ProgressLineTest.kt`, `HeaderRulesTest.kt`, `CopyTableTest.kt`

**Interfaces:**
- Consumes: `DiscoveryStatus` (`engine/discovery/DiscoveryOptions.kt`, fields `probed`, `total`, `found`), `StatusFilter` (`ui/home/HomeViewModel.kt`).
- Produces:
```kotlin
object HomeCopy { /* every Home string of Radar spec section 8 as constants or small formatters */ }
enum class RadarMode { HERO, SCANNING, SETTLED }
data class RadarSummary(val headline: String, val detail: String)
object RadarModes { fun of(scanning: Boolean, totalHosts: Int, lastScanAt: Long?): RadarMode }
object ScanSummaryText { fun of(totalHosts: Int, otherSubnetHosts: Int, durationMs: Long): RadarSummary }
object RadarSummaries { fun settled(totalHosts: Int, otherSubnetHosts: Int, lastScanAt: Long?, scanStartedAt: Long?, scanFinishedAt: Long?,
                                    now: Long, formatDate: (Long) -> String = RelativeTime::defaultDate): RadarSummary }
object RadarText { const val RING_DISABLED: String; const val STOP_SCAN: String; const val STOP_QUICK: String
    fun ringDescription(s: RadarSummary): String; fun scanningDescription(found: Int, progressLine: String?): String
    fun heroDescription(subnet: String?): String; fun finished(headline: String): String }
object RelativeTime { fun of(then: Long, now: Long, formatDate: (Long) -> String = ::defaultDate): String; fun defaultDate(at: Long): String }
object ProgressLine { fun of(status: DiscoveryStatus?, elapsedS: Long): String }
object HeaderRules { fun showFilters(mode: RadarMode, filter: StatusFilter, query: String): Boolean }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RadarModesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarModesTest {
    @Test fun `scanning wins over everything`() {
        assertEquals(RadarMode.SCANNING, RadarModes.of(scanning = true, totalHosts = 0, lastScanAt = null))
        assertEquals(RadarMode.SCANNING, RadarModes.of(scanning = true, totalHosts = 12, lastScanAt = 1L))
    }
    @Test fun `no stored hosts and no completed scan is hero`() { assertEquals(RadarMode.HERO, RadarModes.of(false, 0, null)) }
    @Test fun `hosts present is settled`() { assertEquals(RadarMode.SETTLED, RadarModes.of(false, 3, null)) }
    @Test fun `a finished scan that found nothing is settled not hero`() { assertEquals(RadarMode.SETTLED, RadarModes.of(false, 0, 1_000L)) }
    @Test fun `a filtered empty list is not hero because totalHosts ignores the filter`() {
        // The caller passes the unfiltered count; a filter that matches nothing still has totalHosts > 0.
        assertEquals(RadarMode.SETTLED, RadarModes.of(false, totalHosts = 13, lastScanAt = 1_000L))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RelativeTimeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val now = 1_000_000_000L
    private fun ago(ms: Long) = RelativeTime.of(now - ms, now) { "date:$it" }
    private val s = 1_000L; private val m = 60 * s; private val h = 60 * m; private val d = 24 * h

    @Test fun `seconds and just now`() {
        assertEquals("just now", ago(0)); assertEquals("just now", ago(4 * s))
        assertEquals("5 seconds ago", ago(5 * s)); assertEquals("59 seconds ago", ago(59 * s))
    }
    @Test fun `minute boundaries`() {
        assertEquals("1 minute ago", ago(60 * s)); assertEquals("2 minutes ago", ago(2 * m)); assertEquals("59 minutes ago", ago(59 * m))
    }
    @Test fun `hour boundaries`() {
        assertEquals("1 hour ago", ago(60 * m)); assertEquals("23 hours ago", ago(23 * h))
    }
    @Test fun `yesterday then the date`() {
        assertEquals("yesterday", ago(24 * h)); assertEquals("yesterday", ago(47 * h))
        assertEquals("date:${now - 2 * d}", ago(2 * d))
    }
    @Test fun `a time in the future reads as just now`() { assertEquals("just now", RelativeTime.of(now + 5 * s, now) { "x" }) }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ScanSummaryTextTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSummaryTextTest {
    @Test fun `duration only`() { assertEquals(RadarSummary("13 devices", "Scanned in 12 s"), ScanSummaryText.of(13, 0, 12_000)) }
    @Test fun `with other subnet count`() { assertEquals(RadarSummary("13 devices", "Scanned in 12 s, 1 on another subnet"), ScanSummaryText.of(13, 1, 12_000)) }
    @Test fun `zero devices`() { assertEquals(RadarSummary("No devices found", "Scanned in 12 s"), ScanSummaryText.of(0, 0, 12_000)) }
    @Test fun `singular device and rounding`() {
        assertEquals(RadarSummary("1 device", "Scanned in 13 s"), ScanSummaryText.of(1, 0, 12_600))
        assertEquals("Scanned in 1 s", ScanSummaryText.of(1, 0, 100).detail)
    }
    @Test fun `settled uses the done summary only for a finished run`() {
        val now = 10_000_000L
        assertEquals("Scanned in 12 s", RadarSummaries.settled(13, 0, 5_000L, 1_000_000L, 1_012_000L, now) { "d" }.detail)
        assertEquals("Last scan 2 minutes ago", RadarSummaries.settled(13, 0, now - 120_000, null, null, now) { "d" }.detail)
        assertEquals(RadarSummary("No devices found", "Last scan just now"), RadarSummaries.settled(0, 0, now, null, null, now) { "d" })
        assertEquals("", RadarSummaries.settled(3, 0, null, null, null, now) { "d" }.detail)
    }
    @Test fun `semantics strings`() {
        assertEquals("Scan, 13 devices, last scan 2 minutes ago", RadarText.ringDescription(RadarSummary("13 devices", "Last scan 2 minutes ago")))
        assertEquals("Scanning, 9 devices found, probing 171 of 254", RadarText.scanningDescription(9, "probing 171 of 254  7 s"))
        assertEquals("Scanning, 1 device found", RadarText.scanningDescription(1, null))
        assertEquals("Scan the network 10.0.3.0/24", RadarText.heroDescription("10.0.3.0/24"))
        assertEquals(RadarText.RING_DISABLED, RadarText.heroDescription(null))
        assertEquals("Scan finished, 13 devices", RadarText.finished("13 devices"))
        assertEquals("Scan finished, no devices found", RadarText.finished("No devices found"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ProgressLineTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressLineTest {
    @Test fun `start of run before the total is known`() {
        assertEquals("starting", ProgressLine.of(null, 0)); assertEquals("starting", ProgressLine.of(DiscoveryStatus(), 1))
    }
    @Test fun `local sweep uses two spaces before the elapsed time`() {
        assertEquals("probing 171 of 254  7 s", ProgressLine.of(DiscoveryStatus(probed = 171, total = 254, found = 9), 7))
    }
    @Test fun `tail after the sweep`() {
        assertEquals("identifying 14 devices  9 s", ProgressLine.of(DiscoveryStatus(probed = 254, total = 254, found = 14), 9))
        assertEquals("identifying 1 device  3 s", ProgressLine.of(DiscoveryStatus(probed = 254, total = 254, found = 1), 3))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/HeaderRulesTest.kt` (Review Focus 1, pure rule):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderRulesTest {
    @Test fun `settled always shows chips and search`() {
        for (f in StatusFilter.entries) assertTrue(HeaderRules.showFilters(RadarMode.SETTLED, f, ""))
    }
    @Test fun `large disc with All and a blank query collapses them`() {
        assertFalse(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, ""))
        assertFalse(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, "   "))
        assertFalse(HeaderRules.showFilters(RadarMode.HERO, StatusFilter.ALL, ""))
    }
    @Test fun `an active filter or query keeps them while scanning`() {
        assertTrue(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.INTERESTING, ""))
        assertTrue(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, "nas"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt` (spec 10.1: every state a helper can reach must produce text that belongs to the Radar spec section 8 copy table, not just one hand-picked example per branch):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Radar spec section 8 copy table held as regex templates. Each test generates a matrix of reachable states and asserts every
 * produced string matches a template, so a state that yields wording outside the table fails here. Task 16 appends its own templates.
 */
class CopyTableTest {
    private val date = "3 Jan 2026"
    private val templates: List<Regex> = listOf(
        "Scan", "Stop", "found", "No devices found", "\d+ devices?",
        "Last scan (just now|\d+ seconds ago|\d+ minutes? ago|\d+ hours? ago|yesterday|$date)",
        "Scanned in \d+ s(, \d+ on another subnet)?",
        "Nothing scanned yet", "Tap to find what is on \d+\.\d+\.\d+\.\d+/\d+", "No network", "Connect to Wi-Fi or Ethernet to scan",
        "starting", "probing \d+ of \d+  \d+ s", "identifying \d+ devices?  \d+ s", "Quick scanning", "Search name, IP, vendor or MAC",
        "Devices appear here as they are found", "Looking for devices", "No devices found\. Check the network and scan again\.",
        "No devices match \".+\"", "No devices marked \w+", "Network changed, scan stopped",
        "Large network \(/\d+\)\. Scanned your /24 only\. Widen in Settings\.",
    ).map { Regex(it) }
    /** The only blank string a helper may return: a settled radar with stored hosts and no known scan time (summary detail). */
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })
    private fun detailOk(s: String, blankAllowed: Boolean) = if (s.isEmpty()) assertTrue("blank detail where text is expected", blankAllowed) else inTable(s)

    @Test fun `progress states`() {
        val statuses = listOf(null, DiscoveryStatus(), DiscoveryStatus(probed = 0, total = 254), DiscoveryStatus(probed = 171, total = 254, found = 9),
            DiscoveryStatus(probed = 254, total = 254, found = 0), DiscoveryStatus(probed = 254, total = 254, found = 1), DiscoveryStatus(probed = 254, total = 254, found = 14))
        for (st in statuses) for (elapsed in listOf(0L, 7L, 125L)) inTable(ProgressLine.of(st, elapsed))
    }
    @Test fun `every summary state matches the table`() {
        val now = 10_000_000L
        val lastScans = listOf<Long?>(null, now, now - 30_000, now - 120_000, now - 7_200_000, now - 100_000_000L, now - 300_000_000L)
        val finishes = listOf<Pair<Long, Long>?>(null, 1_000_000L to 1_012_000L, 1_000_000L to 1_000_100L)
        for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) for (finish in finishes) for (last in lastScans) {
            val mode = RadarModes.of(false, hosts, last ?: finish?.second)
            if (mode == RadarMode.HERO) continue   // HERO shows the hero strings, checked below
            val s = RadarSummaries.settled(hosts, other, last, finish?.first, finish?.second, now) { date }
            inTable(s.headline)
            detailOk(s.detail, blankAllowed = hosts > 0 && last == null && finish == null)   // the one reachable blank
        }
        for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) ScanSummaryText.of(hosts, other, 12_000).let { inTable(it.headline); inTable(it.detail) }
    }
    @Test fun `hero, empty and notice strings`() {
        listOf(HomeCopy.SCAN, HomeCopy.STOP, HomeCopy.FOUND, HomeCopy.HERO_TITLE, HomeCopy.heroSubtitle("10.0.3.0/24"), HomeCopy.HERO_NO_NETWORK,
            HomeCopy.HERO_NO_NETWORK_SUB, HomeCopy.QUICK_SCANNING, HomeCopy.SEARCH_PLACEHOLDER, HomeCopy.EMPTY_HERO, HomeCopy.EMPTY_SCANNING,
            HomeCopy.EMPTY_NONE, HomeCopy.emptySearch("nas"), HomeCopy.emptyFilter("Interesting"), HomeCopy.NETWORK_CHANGED,
            HomeCopy.largeNetwork(16)).forEach { inTable(it) }
        StatusFilter.entries.filter { it != StatusFilter.ALL }.forEach { inTable(HomeCopy.emptyFilter(it.label)) }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -15
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'RadarModes'`, `'RelativeTime'`, `'ScanSummaryText'`, `'ProgressLine'`, `'HeaderRules'`, `'HomeCopy'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

/** Every Home string (Radar spec section 8). This file is the only place Home wording lives. */
object HomeCopy {
    const val SCAN = "Scan"
    const val STOP = "Stop"
    const val FOUND = "found"
    const val NO_DEVICES = "No devices found"
    const val HERO_TITLE = "Nothing scanned yet"
    const val HERO_NO_NETWORK = "No network"
    const val HERO_NO_NETWORK_SUB = "Connect to Wi-Fi or Ethernet to scan"
    const val STARTING = "starting"
    const val QUICK_SCANNING = "Quick scanning"
    const val SEARCH_PLACEHOLDER = "Search name, IP, vendor or MAC"
    const val EMPTY_HERO = "Devices appear here as they are found"
    const val EMPTY_SCANNING = "Looking for devices"
    const val EMPTY_NONE = "No devices found. Check the network and scan again."
    const val NETWORK_CHANGED = "Network changed, scan stopped"
    const val JUST_NOW = "just now"
    const val YESTERDAY = "yesterday"

    fun heroSubtitle(subnet: String) = "Tap to find what is on $subnet"
    fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    fun lastScan(relative: String) = "Last scan $relative"
    fun scannedIn(seconds: Long) = "Scanned in $seconds s"
    fun onAnotherSubnet(k: Int) = "$k on another subnet"
    fun emptySearch(query: String) = "No devices match \"$query\""
    fun emptyFilter(label: String) = "No devices marked $label"
    fun largeNetwork(prefix: Int) = "Large network (/$prefix). Scanned your /24 only. Widen in Settings."
    fun probing(probed: Int, total: Int, elapsedS: Long) = "probing $probed of $total  $elapsedS s"
    fun identifying(found: Int, elapsedS: Long) = "identifying ${devices(found)}  $elapsedS s"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarModes.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

enum class RadarMode { HERO, SCANNING, SETTLED }   // HERO = first launch: no hosts, no completed scan, not scanning
data class RadarSummary(val headline: String, val detail: String)

object RadarModes {
    /** [totalHosts] is unfiltered, so a search or filter with no matches is never a first launch (spec 3.1). */
    fun of(scanning: Boolean, totalHosts: Int, lastScanAt: Long?): RadarMode = when {
        scanning -> RadarMode.SCANNING
        totalHosts == 0 && lastScanAt == null -> RadarMode.HERO
        else -> RadarMode.SETTLED
    }
}

object ScanSummaryText {
    private const val MS_PER_S = 1000L
    private const val HALF_S = 500L
    fun of(totalHosts: Int, otherSubnetHosts: Int, durationMs: Long): RadarSummary {
        val seconds = ((durationMs + HALF_S) / MS_PER_S).coerceAtLeast(1)
        val detail = HomeCopy.scannedIn(seconds) + if (otherSubnetHosts > 0) ", " + HomeCopy.onAnotherSubnet(otherSubnetHosts) else ""
        return RadarSummary(headline(totalHosts), detail)
    }
    fun headline(totalHosts: Int): String = if (totalHosts == 0) HomeCopy.NO_DEVICES else HomeCopy.devices(totalHosts)
}

object RadarSummaries {
    /** The done summary while this view model holds a finished run; otherwise the idle text (duration is not persisted, spec 3.4). */
    fun settled(totalHosts: Int, otherSubnetHosts: Int, lastScanAt: Long?, scanStartedAt: Long?, scanFinishedAt: Long?, now: Long,
                formatDate: (Long) -> String = RelativeTime::defaultDate): RadarSummary {
        if (scanStartedAt != null && scanFinishedAt != null && scanFinishedAt >= scanStartedAt)
            return ScanSummaryText.of(totalHosts, otherSubnetHosts, scanFinishedAt - scanStartedAt)
        val detail = lastScanAt?.let { HomeCopy.lastScan(RelativeTime.of(it, now, formatDate)) } ?: ""
        return RadarSummary(ScanSummaryText.headline(totalHosts), detail)
    }
}

/** Accessibility strings (spec 7.3). */
object RadarText {
    const val RING_DISABLED = "Scan, unavailable, not connected"
    const val STOP_SCAN = "Stop scan"
    const val STOP_QUICK = "Stop quick scan"
    private const val TIME_SEPARATOR = "  "
    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercaseChar() }
    fun ringDescription(s: RadarSummary): String = listOf("Scan", s.headline, lowerFirst(s.detail)).filter { it.isNotEmpty() }.joinToString(", ")
    fun scanningDescription(found: Int, progressLine: String?): String {
        val base = "Scanning, ${HomeCopy.devices(found)} found"
        return progressLine?.substringBefore(TIME_SEPARATOR)?.takeIf { it.isNotBlank() }?.let { "$base, $it" } ?: base
    }
    fun heroDescription(subnet: String?): String = subnet?.let { "Scan the network $it" } ?: RING_DISABLED
    fun finished(headline: String): String = "Scan finished, ${lowerFirst(headline)}"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RelativeTime.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import java.text.DateFormat
import java.util.Date

/** "just now", "N seconds ago", "N minutes ago", "N hours ago", "yesterday", then the date (spec section 8). */
object RelativeTime {
    private const val JUST_NOW_S = 5L
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val DAY_S = 86_400L
    private const val TWO_DAYS_S = 2 * DAY_S
    private const val MS_PER_S = 1000L

    fun defaultDate(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

    fun of(then: Long, now: Long, formatDate: (Long) -> String = ::defaultDate): String {
        val s = ((now - then) / MS_PER_S).coerceAtLeast(0)
        return when {
            s < JUST_NOW_S -> HomeCopy.JUST_NOW
            s < MINUTE_S -> "$s seconds ago"
            s < HOUR_S -> (s / MINUTE_S).let { if (it == 1L) "1 minute ago" else "$it minutes ago" }
            s < DAY_S -> (s / HOUR_S).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            s < TWO_DAYS_S -> HomeCopy.YESTERDAY
            else -> formatDate(then)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ProgressLine.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus

/** The radar's progress line (spec 3.3 and section 8). Task 16 adds the extended-discovery phases. */
object ProgressLine {
    fun of(status: DiscoveryStatus?, elapsedS: Long): String {
        if (status == null || status.total == 0) return HomeCopy.STARTING
        return if (status.probed < status.total) HomeCopy.probing(status.probed, status.total, elapsedS)
        else HomeCopy.identifying(status.found, elapsedS)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HeaderRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

/** D12: chips and search collapse only while the large disc shows AND the filter is All AND the query is blank. */
object HeaderRules {
    fun showFilters(mode: RadarMode, filter: StatusFilter, query: String): Boolean =
        mode == RadarMode.SETTLED || filter != StatusFilter.ALL || query.isNotBlank()
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: the six new classes pass with the existing `ui.home` tests; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add radar copy, modes, summaries, relative time, progress line and header rule"
```

---

### Task 3: Row, group-header and Detail text helpers

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RowText.kt`, `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowTextTest.kt`, `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt`

**Interfaces:**
- Consumes: `HostRow` (fields `id, ip, displayName, identityLine, type, openPorts, status, isGateway, stale, mac, otherSubnet`), `RowRules.parseIpv4`, `IdentityLine` and its `DeviceType.label` member extension (`ui/home/HomeViewModel.kt`), `label()` for `HostStatus` (`ui/home/HomeComponents.kt`), `PortRow` (`ui/detail/DetailViewModel.kt`), `DeviceAction` (`actions/DeviceAction.kt`), `ServiceInfo`, `ScanProgress`, `DeviceType`.
- Produces:
```kotlin
data class HostGroup(val subnet: String, val local: Boolean, val rows: List<HostRow>)
object RowText {
    fun dataLine(r: HostRow, ownModel: String?): String          // replaces rowSecondaryLine (removed in Task 6)
    fun description(r: HostRow, ownModel: String?): String       // merged row semantics, spec 7.3
}
object GroupHeaders { fun text(subnet: String, local: Boolean): String }
object HomeGroups { fun of(rows: List<HostRow>, localSubnet: String?): List<HostGroup> }   // Task 16 drops localSubnet and keys on HostRow.subnet
fun actionWord(uriScheme: String?): String
fun schemeOf(p: PortRow): String?
fun primaryAction(ip: String, p: PortRow): DeviceAction
fun actionDescription(ip: String, p: PortRow): String
fun sheetLabel(action: DeviceAction): String?
fun sheetActions(p: PortRow): List<Pair<String, DeviceAction>>
fun versionLine(s: ServiceInfo): String?
fun identityWithType(identity: String, type: DeviceType): String?
fun detailProgress(p: ScanProgress?): String
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowTextTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTextTest {
    private fun row(ip: String = "10.0.3.59", name: String = "CHNAS", identity: String = "Synology DS220+", mac: String? = "00:11:32:9a:1f:e4",
                    other: String? = null, status: HostStatus = HostStatus.UNMARKED, gateway: Boolean = false, stale: Boolean = false, open: Int = 0, id: Long = 1) =
        HostRow(id, ip, name, identity, DeviceType.NAS, open, status, gateway, stale, mac, other)

    @Test fun `ip with identity uses two spaces`() { assertEquals("10.0.3.59  Synology DS220+", RowText.dataLine(row(), null)) }
    @Test fun `unknown identity falls back to the mac`() {
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = "Unknown device"), null))
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = "Unknown device, found via mDNS"), null))
        assertEquals("10.0.3.59  00:11:32:9a:1f:e4", RowText.dataLine(row(identity = " "), null))
    }
    @Test fun `ip alone when nothing else is known`() { assertEquals("10.0.3.59", RowText.dataLine(row(identity = "Unknown device", mac = null), null)) }
    @Test fun `name equal to the ip shows identity or mac only`() {
        assertEquals("Synology DS220+", RowText.dataLine(row(name = "10.0.3.59"), null))
        assertEquals("00:11:32:9a:1f:e4", RowText.dataLine(row(name = "10.0.3.59", identity = "Unknown device"), null))
        assertEquals("10.0.3.59", RowText.dataLine(row(name = "10.0.3.59", identity = "Unknown device", mac = null), null))
    }
    @Test fun `no other subnet suffix`() { assertEquals("10.0.4.20  Philips Hue Bridge", RowText.dataLine(row(ip = "10.0.4.20", identity = "Philips Hue Bridge", other = "10.0.4.0/24"), null)) }
    @Test fun `own phone row shows the supplied model`() {
        assertEquals("10.0.3.242  Pixel 7 Pro", RowText.dataLine(row(ip = "10.0.3.242", name = "This phone", identity = "Android device"), "Pixel 7 Pro"))
    }
    @Test fun `merged row description`() {
        assertEquals("Ubiquiti gateway, gateway, 10.0.3.1, 3 open ports", RowText.description(row(ip = "10.0.3.1", name = "Ubiquiti gateway", gateway = true, open = 3), null))
        assertEquals("CHNAS, 10.0.3.59, 1 open port, marked Interesting, not seen in the last scan",
            RowText.description(row(open = 1, status = HostStatus.INTERESTING, stale = true), null))
        assertEquals("CHNAS, 10.0.3.59", RowText.description(row(), null))
    }
    @Test fun `group headers are fixed wording with no count and no network name`() {
        assertEquals("Home subnet 10.0.3.0/24", GroupHeaders.text("10.0.3.0/24", local = true))
        assertEquals("Other subnet 10.0.4.0/24", GroupHeaders.text("10.0.4.0/24", local = false))
    }
    @Test fun `groups put local first then others by network address`() {
        val rows = listOf(row(id = 1, ip = "10.0.3.1", gateway = true), row(id = 2, ip = "10.0.10.5", other = "10.0.10.0/24"),
            row(id = 3, ip = "10.0.3.9"), row(id = 4, ip = "10.0.4.20", other = "10.0.4.0/24"))
        val g = HomeGroups.of(rows, "10.0.3.0/24")
        assertEquals(listOf("10.0.3.0/24", "10.0.4.0/24", "10.0.10.0/24"), g.map { it.subnet })
        assertEquals(listOf(true, false, false), g.map { it.local })
        assertEquals(listOf(1L, 3L), g[0].rows.map { it.id })   // input order (gateway first, then IP) is kept
    }
    @Test fun `a single group still has its header and empty groups are omitted`() {
        assertEquals(listOf("10.0.3.0/24"), HomeGroups.of(listOf(row(ip = "10.0.3.9")), "10.0.3.0/24").map { it.subnet })
        assertEquals(emptyList<HostGroup>(), HomeGroups.of(emptyList(), "10.0.3.0/24"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class DetailTextTest {
    private val http = ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE)
    private fun port(n: Int, uri: String?, has: Boolean = true): PortRow {
        val open = uri?.let { DeviceAction.OpenUri(if (has) "Open in browser" else "No app installed for ${it.substringBefore("://")}://", it, has) }
        val actions = listOfNotNull(open, uri?.let { DeviceAction.OpenWith(it) }, DeviceAction.Copy("Copy IP", "10.0.3.59"), DeviceAction.Copy("Copy IP:port", "10.0.3.59:$n"))
        return PortRow(n, http, open?.takeIf { has }, actions)
    }

    @Test fun `action words by scheme`() {
        for (s in listOf("http", "https", "ftp")) assertEquals("Open", actionWord(s))
        for (s in listOf("ssh", "telnet", "vnc", "rdp")) assertEquals("Connect", actionWord(s))
        assertEquals("Open share", actionWord("smb")); assertEquals("Copy", actionWord(null)); assertEquals("Copy", actionWord("gopher"))
    }
    @Test fun `no handler means copy ip and port`() {
        val p = port(5357, "http://10.0.3.59:5357/", has = false)
        assertNull(schemeOf(p)); assertEquals("Copy", actionWord(schemeOf(p)))
        assertEquals(DeviceAction.Copy("Copy IP:port", "10.0.3.59:5357"), primaryAction("10.0.3.59", p))
        assertEquals("Copy 10.0.3.59:5357", actionDescription("10.0.3.59", p))
    }
    @Test fun `primary action and descriptions`() {
        val p = port(80, "http://10.0.3.59:80/")
        assertEquals("http", schemeOf(p)); assertEquals(p.primary, primaryAction("10.0.3.59", p))
        assertEquals("Open port 80 in browser", actionDescription("10.0.3.59", p))
        assertEquals("Connect to port 22", actionDescription("10.0.3.59", port(22, "ssh://10.0.3.59:22")))
        assertEquals("Open share on port 445", actionDescription("10.0.3.59", port(445, "smb://10.0.3.59/")))
    }
    @Test fun `sheet labels map provider wording and drop the no app entry`() {
        assertEquals("Copy IP and port", sheetLabel(DeviceAction.Copy("Copy IP:port", "x")))
        assertEquals("Copy IP", sheetLabel(DeviceAction.Copy("Copy IP", "x")))
        assertEquals("Open with...", sheetLabel(DeviceAction.OpenWith("http://x/")))
        assertNull(sheetLabel(DeviceAction.OpenUri("No app installed for ssh://", "ssh://x", false)))
        val labels = sheetActions(port(5357, "http://10.0.3.59:5357/", has = false)).map { it.first }
        assertEquals(listOf("Open with...", "Copy IP", "Copy IP and port"), labels)
        assertFalse(sheetActions(port(80, "http://10.0.3.59:80/")).any { it.second is DeviceAction.OpenUri })   // the primary is the row tap
    }
    @Test fun `identity with type`() {
        assertEquals("Synology DS220+  NAS", identityWithType("Synology DS220+", DeviceType.NAS))
        assertEquals("Android Phone", identityWithType("Android Phone", DeviceType.PHONE))
        assertEquals("Android phone", identityWithType("Android phone", DeviceType.PHONE))
        assertEquals("NAS", identityWithType("", DeviceType.NAS))
        assertEquals("Linksys", identityWithType("Linksys", DeviceType.UNKNOWN))
        assertNull(identityWithType(" ", DeviceType.UNKNOWN))
    }
    @Test fun `version line and progress`() {
        assertEquals("OpenSSH 8.2p1", versionLine(ServiceInfo("SSH", "OpenSSH", "8.2p1", null, Confidence.CONFIRMED)))
        assertEquals("cert chnas.local", versionLine(ServiceInfo("HTTPS", null, null, "cert chnas.local", Confidence.CONFIRMED)))
        assertNull(versionLine(http))
        assertEquals("312 of 65535, found 4", detailProgress(ScanProgress(312, 65535, 4, 0))); assertEquals("starting", detailProgress(null))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.RowTextTest" --tests "com.northwalessolutions.netprobe.ui.detail.DetailTextTest" --console=plain 2>&1 | tail -15
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'RowText'`, `'GroupHeaders'`, `'HomeGroups'`, `'actionWord'`, `'identityWithType'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RowText.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.netenv.SubnetMath

data class HostGroup(val subnet: String, val local: Boolean, val rows: List<HostRow>)

object RowText {
    private const val GAP = "  "
    private const val UNKNOWN_PREFIX = "Unknown device"

    /** Known identity: not blank and not the "Unknown device" fallback. The own-phone row substitutes Build.MODEL (spec 4.3). */
    private fun identity(r: HostRow, ownModel: String?): String? =
        ownModel ?: r.identityLine.trim().takeUnless { it.isEmpty() || it.startsWith(UNKNOWN_PREFIX) }

    /** Line 2: "10.0.3.59  Synology DS220+"; identity, else MAC, else the IP alone. No subnet suffix: the group header carries it. */
    fun dataLine(r: HostRow, ownModel: String?): String {
        val second = identity(r, ownModel) ?: r.mac
        return when {
            r.displayName == r.ip -> second ?: r.ip
            second != null -> r.ip + GAP + second
            else -> r.ip
        }
    }

    /** "Ubiquiti gateway, gateway, 10.0.3.1, 3 open ports", then the mark and the stale note (spec 7.3). */
    fun description(r: HostRow, ownModel: String?): String = buildList {
        add(r.displayName)
        if (r.isGateway) add("gateway")
        add(r.ip)
        if (r.openPorts > 0) add(if (r.openPorts == 1) "1 open port" else "${r.openPorts} open ports")
        if (r.status != HostStatus.UNMARKED) add("marked ${r.status.label()}")
        if (r.stale) add("not seen in the last scan")
    }.joinToString(", ")
}

object GroupHeaders {
    fun text(subnet: String, local: Boolean): String = (if (local) "Home subnet " else "Other subnet ") + subnet
}

object HomeGroups {
    private const val UNSIGNED_MASK = 0xFFFFFFFFL
    private fun networkKey(cidr: String): Long =
        RowRules.parseIpv4(cidr.substringBefore('/'))?.let { SubnetMath.toInt(it).toLong() and UNSIGNED_MASK } ?: Long.MAX_VALUE

    /** Local group first, then other groups by network address ascending; rows keep their input order (spec 4.2). */
    fun of(rows: List<HostRow>, localSubnet: String?): List<HostGroup> {
        val local = rows.filter { it.otherSubnet == null }
        val others = rows.filter { it.otherSubnet != null }.groupBy { it.otherSubnet!! }.entries.sortedBy { networkKey(it.key) }
        return buildList {
            if (local.isNotEmpty()) add(HostGroup(localSubnet.orEmpty(), true, local))
            others.forEach { (subnet, rs) -> add(HostGroup(subnet, false, rs)) }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.home.IdentityLine

private const val WORD_OPEN = "Open"
private const val WORD_CONNECT = "Connect"
private const val WORD_SHARE = "Open share"
private const val WORD_COPY = "Copy"
private const val COPY_IP_PORT = "Copy IP:port"
private val OPEN_SCHEMES = setOf("http", "https", "ftp")
private val CONNECT_SCHEMES = setOf("ssh", "telnet", "vnc", "rdp")
private val BROWSER_SCHEMES = setOf("http", "https")

/** One word per port row, keyed on the primary action's URI scheme; `ActionProvider` is untouched (spec 5.2). */
fun actionWord(uriScheme: String?): String = when (uriScheme?.lowercase()) {
    in OPEN_SCHEMES -> WORD_OPEN
    in CONNECT_SCHEMES -> WORD_CONNECT
    "smb" -> WORD_SHARE
    else -> WORD_COPY
}

/** Scheme of the primary action; null when no app handles the service (the provider returns no primary then). */
fun schemeOf(p: PortRow): String? = (p.primary as? DeviceAction.OpenUri)?.uri?.substringBefore("://")?.lowercase()

/** The row tap: the primary action, or copying "ip:port" when no app handles it. */
fun primaryAction(ip: String, p: PortRow): DeviceAction = p.primary ?: DeviceAction.Copy(COPY_IP_PORT, "$ip:${p.port}")

fun actionDescription(ip: String, p: PortRow): String {
    val scheme = schemeOf(p)
    return when (actionWord(scheme)) {
        WORD_OPEN -> if (scheme in BROWSER_SCHEMES) "Open port ${p.port} in browser" else "Open port ${p.port}"
        WORD_CONNECT -> "Connect to port ${p.port}"
        WORD_SHARE -> "Open share on port ${p.port}"
        else -> "Copy $ip:${p.port}"
    }
}

/** Sheet wording; null drops the "No app installed" entry exactly as the MVP row did. */
fun sheetLabel(action: DeviceAction): String? = when {
    action is DeviceAction.OpenUri && !action.hasHandler -> null
    action is DeviceAction.Copy && action.label == COPY_IP_PORT -> "Copy IP and port"
    else -> action.label
}

/** Long-press sheet rows: every action except the primary (that is the row tap) and the no-app entry. */
fun sheetActions(p: PortRow): List<Pair<String, DeviceAction>> =
    p.actions.filter { it != p.primary }.mapNotNull { a -> sheetLabel(a)?.let { it to a } }

fun versionLine(s: ServiceInfo): String? = listOfNotNull(s.product, s.version, s.detail).joinToString(" ").trim().ifEmpty { null }

/** "Synology DS220+  NAS"; the label is skipped when the identity already ends with it or the type is UNKNOWN (spec 5.1). */
fun identityWithType(identity: String, type: DeviceType): String? {
    val label = with(IdentityLine) { type.label }
    val id = identity.trim()
    return when {
        label.isEmpty() -> id.ifEmpty { null }
        id.isEmpty() -> label
        id.endsWith(label, ignoreCase = true) -> id
        else -> "$id  $label"
    }
}

fun detailProgress(p: ScanProgress?): String = p?.let { "${it.done} of ${it.total}, found ${it.open}" } ?: "starting"
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.RowTextTest" --tests "com.northwalessolutions.netprobe.ui.detail.DetailTextTest" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `RowTextTest` (10) and `DetailTextTest` (6) pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add row, group header and Detail text helpers"
```

---

### Task 4: The radar component: three states, HERO, quick-scan ring and the animation rules

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarGeometry.kt`, `ui/home/RadarBlock.kt`, `app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/BlipOffsetTest.kt`, `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/RadarBlockTest.kt`

**Interfaces:**
- Consumes: `RadarMode`, `RadarSummary`, `RadarText`, `HomeCopy` (Task 2); `NetProbeColors`, `NetProbeText`, `Dimens`, `Motion`, `LocalReducedMotion`, `NetProbeTheme` (Task 1).
- Produces:
```kotlin
object RadarGeometry { const val MAX_BLIPS = 64; const val MIN_RADIUS_FRACTION = 0.22f; const val MAX_RADIUS_FRACTION = 0.88f; fun mix(i: Int): Int
                       fun armMask(maskPx: Float, discRadiusPx: Float): Float }
fun blipOffset(index: Int, radiusPx: Float): Offset   // relative to the disc centre
val ArmSpinningKey: SemanticsPropertyKey<Boolean>      // test hook: true only while the arm turns
@Composable fun RadarBlock(mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean,
                           onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
                           heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {})
@Composable fun StopPill(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Stop scan", tag: String = "stopPill")
@Composable fun npFieldColors(): TextFieldColors
```
- Tags: `radarBlock`, `radarRing` (SETTLED, the 64 dp touch target around the 56 dp ring), `radarDisc` (HERO and SCANNING), `radarStop`, `radarCount`, `radarProgress`, `scanButton` (HERO circle). One tag per node.
- Deviation from Radar spec 3.3, recorded for the Director: a plain hash of the index to angle and radius (as the spec words it) puts two of the first 64 blips 0.99 dp apart, failing the spec's own 4 dp guard (computed while writing this plan). `blipOffset` uses a golden-angle spiral with a hashed jitter of plus or minus 8 degrees: still stable per index and scattered in angle; the closest pair of the first 64 is 8.1 dp apart on the 172 dp disc.
- Deviation from Radar spec 10.2 test 3: `radarRing` is asserted at 64 dp, because the tagged node is the touch target (spec 3.2 pads the 56 dp ring to 64 dp) and a tag on the inner ring is lost in the merged semantics tree.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/BlipOffsetTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BlipOffsetTest {
    private val r = 86f   // the 172 dp disc at 1 px per dp
    @Test fun `deterministic per index`() { for (i in 0 until 64) assertEquals(blipOffset(i, r), blipOffset(i, r)) }
    @Test fun `radius stays within the band and inside the disc`() {
        for (i in 0 until 64) {
            val o = blipOffset(i, r); val d = hypot(o.x, o.y)
            assertTrue("blip $i at $d", d >= RadarGeometry.MIN_RADIUS_FRACTION * r - 0.01f && d <= RadarGeometry.MAX_RADIUS_FRACTION * r + 0.01f && d < r)
        }
    }
    @Test fun `first 64 blips are at least 4 dp apart`() {
        val pts = (0 until 64).map { blipOffset(it, r) }
        for (i in pts.indices) for (j in 0 until i) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            assertTrue("blips $i and $j are $d apart", d >= 4f)
        }
    }
    @Test fun `centre mask never outgrows the disc while it grows`() {
        val mask = 40f
        assertEquals(40f, RadarGeometry.armMask(mask, 86f), 0.01f)         // full disc: the 40 dp mask as specified
        assertEquals(26.32f, RadarGeometry.armMask(mask, 56f), 0.01f)      // ring size at the first scanning frame
        for (r in listOf(28f, 40f, 56f, 70f, 86f)) assertTrue("mask ${RadarGeometry.armMask(mask, r)} at radius $r", RadarGeometry.armMask(mask, r) < r)
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/RadarBlockTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarBlockTest {
    @get:Rule val rule = createComposeRule()
    private val summary = RadarSummary("13 devices", "Last scan 2 minutes ago")

    private fun show(mode: RadarMode, reduced: Boolean = false, canScan: Boolean = true, quick: Boolean = false,
                     onScan: () -> Unit = {}, onStop: () -> Unit = {}, onStopQuick: () -> Unit = {}) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
            RadarBlock(mode, found = 9, progressLine = "probing 171 of 254  7 s", summary = summary, canScan = canScan, onScan = onScan,
                onStop = onStop, heroSubnet = "10.0.3.0/24", quickScanning = quick, onStopQuick = onStopQuick)
        } }
    }

    @Test fun discDoesNothingAndOnlyStopStops() {   // spec 10.2 test 4
        rule.mainClock.autoAdvance = false
        var stops = 0; var scans = 0
        show(RadarMode.SCANNING, onStop = { stops++ }, onScan = { scans++ })
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").performClick(); rule.mainClock.advanceTimeByFrame()
        assertEquals(0, stops); assertEquals(0, scans)
        rule.onNodeWithTag("radarStop").performClick(); rule.mainClock.advanceTimeByFrame()
        assertEquals(1, stops)
    }
    @Test fun settledRingStartsAScanAndCarriesTheSummary() {
        var scans = 0
        show(RadarMode.SETTLED, onScan = { scans++ })
        rule.onNodeWithTag("radarRing").assertWidthIsEqualTo(Dimens.ringTarget)
            .assert(hasContentDescription("Scan, 13 devices, last scan 2 minutes ago"))
        rule.onNodeWithText("13 devices").assertIsDisplayed()
        rule.onNodeWithTag("radarStop").assertDoesNotExist(); rule.onNodeWithTag("radarDisc").assertDoesNotExist()
        rule.onNodeWithTag("radarRing").performClick(); assertEquals(1, scans)
    }
    @Test fun scanningShowsTheDiscTheCountAndStop() {
        rule.mainClock.autoAdvance = false
        show(RadarMode.SCANNING); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
        rule.onNodeWithTag("radarCount", useUnmergedTree = true).assertTextEquals("9")
        rule.onNodeWithTag("radarStop").assertIsDisplayed(); rule.onNodeWithTag("radarRing").assertDoesNotExist()
        rule.onNodeWithTag("radarProgress").assertTextEquals("probing 171 of 254  7 s")
        rule.onNode(SemanticsMatcher.expectValue(ArmSpinningKey, true), useUnmergedTree = true).assertExists()
    }
    @Test fun heroWithoutANetworkDisablesTheCircleAndSaysSoOnce() {
        show(RadarMode.HERO, canScan = false)
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
        rule.onNodeWithText("No network").assertIsDisplayed()
    }
    @Test fun heroWithANetworkOffersTheSubnet() {
        var scans = 0
        show(RadarMode.HERO, onScan = { scans++ })
        rule.onNodeWithText("Nothing scanned yet").assertIsDisplayed(); rule.onNodeWithText("Tap to find what is on 10.0.3.0/24").assertIsDisplayed()
        rule.onNodeWithTag("scanButton").assert(hasContentDescription("Scan the network 10.0.3.0/24")).performClick()
        assertEquals(1, scans)
    }
    @Test fun quickScanRingShowsStopAndStopsTheQuickScan() {
        var stops = 0
        show(RadarMode.SETTLED, quick = true, onStopQuick = { stops++ })
        rule.onNodeWithText("Quick scanning").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").assert(hasContentDescription("Stop quick scan")).performClick()
        assertEquals(1, stops)
    }
    @Test fun reducedMotionGrowsInOneFrameWithAStaticArm() {   // spec 10.2 test 5
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {})
        } } }
        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
        rule.onNode(SemanticsMatcher.expectValue(ArmSpinningKey, false), useUnmergedTree = true).assertExists()
    }
    @Test fun normalMotionGrowsOverTheTransition() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {})
        } } }
        mode = RadarMode.SCANNING
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val early = rule.onNodeWithTag("radarDisc").getBoundsInRoot().width
        assertTrue("disc already full size after two frames: $early", early < Dimens.discDiameter - 1.dp)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(Dimens.discDiameter)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.BlipOffsetTest" --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.RadarBlockTest --console=plain 2>&1 | tail -8
```
Expected: first command `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'blipOffset'`; second `compileDebugAndroidTestKotlin FAILED` with `Unresolved reference 'RadarBlock'` and `'ArmSpinningKey'`.

- [ ] **Step 3: Implement the geometry, the shared pill and field colours, and the radar**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarGeometry.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RadarGeometry {
    const val MAX_BLIPS = 64
    const val MIN_RADIUS_FRACTION = 0.22f
    const val MAX_RADIUS_FRACTION = 0.88f
    const val GOLDEN_ANGLE_DEG = 137.50776f
    const val JITTER_DEG = 16f
    const val ARM_MASK_FRACTION = 0.47f   // 40 dp of the 86 dp full radius
    private const val BYTE_MASK = 0xFF
    private const val BYTE_MAX = 255f
    private const val HALF = 0.5f

    /** The plain-fill centre behind the count never exceeds [ARM_MASK_FRACTION] of the disc radius, so it stays inside the disc while the disc grows. */
    fun armMask(maskPx: Float, discRadiusPx: Float): Float = minOf(maskPx, discRadiusPx * ARM_MASK_FRACTION)

    /** Fixed 32-bit integer hash (murmur3 finaliser); Int overflow wraps exactly as the unsigned arithmetic. */
    fun mix(i: Int): Int {
        var x = i * -0x61c88647            // 0x9E3779B9
        x = x xor (x ushr 16); x *= -0x7a143595   // 0x85EBCA6B
        x = x xor (x ushr 13); x *= -0x3d4d51cb   // 0xC2B2AE35
        return x xor (x ushr 16)
    }

    internal fun jitterDeg(i: Int): Float = ((mix(i) and BYTE_MASK) / BYTE_MAX - HALF) * JITTER_DEG
    internal fun radiusFraction(i: Int): Float =
        MIN_RADIUS_FRACTION + (MAX_RADIUS_FRACTION - MIN_RADIUS_FRACTION) * sqrt(((i % MAX_BLIPS) + HALF) / MAX_BLIPS)
}

/** Stable, scattered blip position relative to the disc centre: a golden-angle spiral with a hashed jitter (see the Task 4 note). */
fun blipOffset(index: Int, radiusPx: Float): Offset {
    val angle = Math.toRadians((index * RadarGeometry.GOLDEN_ANGLE_DEG + RadarGeometry.jitterDeg(index)).toDouble())
    val d = RadarGeometry.radiusFraction(index) * radiusPx
    return Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat())
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val PILL_STROKE = 1.5.dp

/** Outlined teal "Stop" pill, as drawn, with a 48 dp minimum touch height (spec 3.3, 7.2). */
@Composable
fun StopPill(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Stop scan", tag: String = "stopPill") {
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.border(PILL_STROKE, NetProbeColors.signal, CircleShape).padding(vertical = 4.dp, horizontal = 14.dp)) {
            Text("Stop", style = NetProbeText.action, color = NetProbeColors.signal)
        }
    }
}

/** Outlined field restyle (spec 4.1): 1 dp border, teal focus and cursor, muted placeholder. */
@Composable
fun npFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NetProbeColors.signal, unfocusedBorderColor = NetProbeColors.border, disabledBorderColor = NetProbeColors.border,
    errorBorderColor = NetProbeColors.error, cursorColor = NetProbeColors.signal,
    focusedTextColor = NetProbeColors.text, unfocusedTextColor = NetProbeColors.text, disabledTextColor = NetProbeColors.textMuted,
    focusedPlaceholderColor = NetProbeColors.textMuted, unfocusedPlaceholderColor = NetProbeColors.textMuted,
    focusedLabelColor = NetProbeColors.textSecondary, unfocusedLabelColor = NetProbeColors.textMuted,
    focusedLeadingIconColor = NetProbeColors.textMuted, unfocusedLeadingIconColor = NetProbeColors.textMuted,
    errorSupportingTextColor = NetProbeColors.error, focusedSupportingTextColor = NetProbeColors.textMuted, unfocusedSupportingTextColor = NetProbeColors.textMuted,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarBlock.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.northwalessolutions.netprobe.ui.StopPill
import com.northwalessolutions.netprobe.ui.theme.*
import kotlin.math.abs

/** Test hook: true only while the arm is turning (SCANNING, resumed, motion allowed). */
val ArmSpinningKey = SemanticsPropertyKey<Boolean>("ArmSpinning")
private var SemanticsPropertyReceiver.armSpinning by ArmSpinningKey

private const val ANNOUNCE_STEP = 5
private const val MIN_COUNT_SP = 12
private const val ARM_HEAD_ALPHA = 0.55f
private const val ARM_TAIL_ALPHA = 0.08f
private const val ARM_TAIL_STOP = 70f / 360f
private const val ARM_CLEAR_STOP = 110f / 360f
private const val GLOW_ALPHA = 0.35f
private const val FILL_INNER_STOP = 0.3f
private const val FILL_OUTER_STOP = 0.7f
private const val QUICK_ARC_SWEEP = 90f
private val RING_STROKE = 3.dp
private val DISC_STROKE = 2.dp
private val INNER_RING_STROKE = 1.dp
private val TEXT_GAP = 10.dp
/** Three static dots suggest a radar at rest in HERO, as fractions of the disc radius. */
private val HERO_DOTS = listOf(Offset(-0.45f, -0.30f), Offset(0.50f, -0.10f), Offset(0.15f, 0.55f))

/**
 * The scan control (Radar spec section 3): one composable owns HERO, SCANNING and SETTLED so the size change is one continuous
 * animation driven by a single `fraction` (0 = ring, 1 = disc). Only the ring, the HERO circle and the Stop pill are actionable.
 */
@Composable
fun RadarBlock(
    mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean,
    onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
    heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val spinning = mode == RadarMode.SCANNING && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !reduced
    val transition = updateTransition(mode, label = "radar")
    val fraction by transition.animateFloat(
        transitionSpec = { tween(if (reduced) 0 else Motion.SIZE_MS, easing = FastOutSlowInEasing) }, label = "fraction",
    ) { if (it == RadarMode.SETTLED) 0f else 1f }
    val labelAlpha by animateFloatAsState(if (mode == RadarMode.SETTLED) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "label")
    val heroAlpha by animateFloatAsState(if (mode == RadarMode.HERO) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "hero")
    val blipsAlpha by animateFloatAsState(
        if (mode == RadarMode.SCANNING) 1f else 0f,
        tween(if (reduced) 0 else if (mode == RadarMode.SCANNING) Motion.BLIP_IN_MS else Motion.BLIP_OUT_MS), label = "blips")

    // Blips: one per new host, the newest batch fades in; at most 64 are drawn, the count stays exact.
    val newest = remember { Animatable(1f) }
    var shownBlips by remember { mutableIntStateOf(0) }
    var newestFrom by remember { mutableIntStateOf(0) }
    LaunchedEffect(found, mode) {
        if (mode != RadarMode.SCANNING) return@LaunchedEffect
        val n = found.coerceAtMost(RadarGeometry.MAX_BLIPS)
        if (n < shownBlips) { shownBlips = n; newestFrom = n; return@LaunchedEffect }
        if (n > shownBlips) {
            newestFrom = shownBlips; shownBlips = n
            if (reduced) newest.snapTo(1f) else { newest.snapTo(0f); newest.animateTo(1f, tween(Motion.BLIP_IN_MS)) }
        }
    }

    // TalkBack: polite updates in steps of 5, one announcement when the scan finishes (spec 7.3).
    var announced by remember { mutableStateOf(0 to progressLine) }
    LaunchedEffect(mode) { if (mode == RadarMode.SCANNING) announced = 0 to progressLine }
    LaunchedEffect(found) { if (mode == RadarMode.SCANNING && abs(found - announced.first) >= ANNOUNCE_STEP) announced = found to progressLine }
    val view = LocalView.current
    var previous by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        @Suppress("DEPRECATION")
        if (previous == RadarMode.SCANNING && mode == RadarMode.SETTLED) view.announceForAccessibility(RadarText.finished(summary.headline))
        previous = mode
    }

    val live = canScan || quickScanning
    BoxWithConstraints(modifier.fillMaxWidth().testTag("radarBlock")) {
        val width = maxWidth
        val diameter = lerp(Dimens.ringDiameter, Dimens.discDiameter, fraction)
        val target = diameter + (Dimens.ringTarget - Dimens.ringDiameter) * (1f - fraction)
        val centreX = lerp(Dimens.gutter + Dimens.ringTarget / 2, width / 2, fraction)
        val areaHeight = lerp(Dimens.ringTarget, Dimens.discDiameter, fraction)
        val stroke = lerp(RING_STROKE, DISC_STROKE, fraction)
        val strokeColor = lerp(if (live) NetProbeColors.signal else NetProbeColors.border, NetProbeColors.borderSubtle, fraction)
        val modeModifier = when (mode) {
            RadarMode.SETTLED -> Modifier.clip(CircleShape)
                .clickable(enabled = live, role = Role.Button, onClick = if (quickScanning) onStopQuick else onScan)
                .semantics { contentDescription = when { quickScanning -> RadarText.STOP_QUICK; canScan -> RadarText.ringDescription(summary); else -> RadarText.RING_DISABLED } }
                .testTag("radarRing")
            RadarMode.SCANNING -> Modifier
                .semantics { contentDescription = RadarText.scanningDescription(announced.first, announced.second); liveRegion = LiveRegionMode.Polite }
                .testTag("radarDisc")
            RadarMode.HERO -> Modifier.testTag("radarDisc")
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = areaHeight)) {
                Box(Modifier.offset(x = centreX - target / 2, y = (areaHeight - target) / 2).size(target).then(modeModifier), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                        DiscBackground(fraction, stroke, strokeColor, shownBlips, newestFrom, { newest.value }, blipsAlpha, mode == RadarMode.HERO,
                            Modifier.matchParentSize().clearAndSetSemantics { })
                        if (mode == RadarMode.SCANNING) Arm(spinning, Modifier.matchParentSize())
                        if (mode == RadarMode.SETTLED && quickScanning) QuickArc(reduced, Modifier.matchParentSize())
                        if (mode != RadarMode.HERO && labelAlpha > 0f) Text(if (quickScanning) HomeCopy.STOP else HomeCopy.SCAN, style = NetProbeText.ringLabel,
                            color = if (live) NetProbeColors.signal else NetProbeColors.border, modifier = Modifier.alpha(labelAlpha))
                        if (mode == RadarMode.SCANNING || (mode == RadarMode.SETTLED && labelAlpha < 1f))
                            CountColumn(found, Modifier.alpha((1f - labelAlpha) * (1f - heroAlpha)))
                        if (heroAlpha > 0f) HeroButton(canScan && mode == RadarMode.HERO, canScan, heroSubnet, onScan, Modifier.alpha(heroAlpha))
                    }
                }
                if (fraction < 1f) Column(Modifier.align(Alignment.CenterStart).padding(start = Dimens.gutter + Dimens.ringTarget + TEXT_GAP, end = Dimens.gutter)
                    .alpha(1f - fraction)) {
                    Text(summary.headline, style = NetProbeText.summary, color = NetProbeColors.text, maxLines = 1)
                    Text(if (quickScanning) HomeCopy.QUICK_SCANNING else summary.detail, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                }
            }
            if (mode == RadarMode.HERO) Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = Dimens.gutter, end = Dimens.gutter),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (canScan) HomeCopy.HERO_TITLE else HomeCopy.HERO_NO_NETWORK, style = NetProbeText.summary, color = NetProbeColors.text)
                Text(if (canScan && heroSubnet != null) HomeCopy.heroSubtitle(heroSubnet) else HomeCopy.HERO_NO_NETWORK_SUB,
                    style = NetProbeText.secondary, color = NetProbeColors.textMuted, textAlign = TextAlign.Center)
            }
            if (mode == RadarMode.SCANNING) {
                val pillAlpha = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    pillAlpha.animateTo(1f, tween(if (reduced) 0 else Motion.PILL_FADE_MS, delayMillis = if (reduced) 0 else Motion.PILL_DELAY_MS))
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).graphicsLayer { alpha = pillAlpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                    StopPill(onStop, description = RadarText.STOP_SCAN, tag = "radarStop")
                    Spacer(Modifier.height(6.dp))
                    Text(progressLine.orEmpty(), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Dimens.gutter).testTag("radarProgress"))
                }
            }
        }
    }
}

/** Static disc, inner rings, HERO dots and blips; re-recorded only when its inputs or the size change (spec 3.6). */
@Composable
private fun DiscBackground(fraction: Float, stroke: Dp, strokeColor: Color, blips: Int, newestFrom: Int, newestAlpha: () -> Float,
                           blipsAlpha: Float, hero: Boolean, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val r = size.minDimension / 2
        val c = center
        val fill = Brush.radialGradient(0f to NetProbeColors.discCentre, FILL_INNER_STOP to NetProbeColors.discCentre,
            FILL_OUTER_STOP to NetProbeColors.background, 1f to NetProbeColors.background, center = c, radius = r.coerceAtLeast(1f))
        val ring1 = r - Dimens.discInnerRing1.toPx(); val ring2 = r - Dimens.discInnerRing2.toPx()
        val strokePx = stroke.toPx(); val thin = INNER_RING_STROKE.toPx()
        val blipR = Dimens.blip.toPx() / 2; val glowR = blipR + Dimens.blipGlow.toPx()
        val points = (0 until blips).map { i -> (c + blipOffset(i, r)).let { p -> p to Brush.radialGradient(listOf(NetProbeColors.signal.copy(alpha = GLOW_ALPHA), Color.Transparent), center = p, radius = glowR) } }
        val dots = HERO_DOTS.map { c + it * r }
        onDrawBehind {
            if (fraction > 0f) {
                drawCircle(fill, r, c, alpha = fraction)
                if (ring1 > 0f) drawCircle(NetProbeColors.borderSubtle, ring1, c, alpha = fraction, style = Stroke(thin))
                if (ring2 > 0f) drawCircle(NetProbeColors.borderSubtle, ring2, c, alpha = fraction, style = Stroke(thin))
            }
            drawCircle(strokeColor, r - strokePx / 2, c, style = Stroke(strokePx))
            if (hero) dots.forEach { drawCircle(NetProbeColors.textMuted, blipR, it) }
            points.forEachIndexed { i, (p, glow) ->
                val a = blipsAlpha * if (i >= newestFrom) newestAlpha() else 1f
                drawCircle(glow, glowR, p, alpha = a); drawCircle(NetProbeColors.signal, blipR, p, alpha = a)
            }
        }
    })
}

/** The sweep arm: its own layer, rotated in the layer block so a frame never recomposes or re-records the disc (spec 3.6). */
@Composable
private fun Arm(spinning: Boolean, modifier: Modifier) {
    if (spinning) {
        val angle = rememberInfiniteTransition(label = "arm")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.ARM_TURN_MS, easing = LinearEasing)), label = "angle")
        ArmLayer({ angle.value }, spinning = true, modifier)
    } else ArmLayer({ Motion.STATIC_ARM_DEG }, spinning = false, modifier)
}

@Composable
private fun ArmLayer(angle: () -> Float, spinning: Boolean, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { armSpinning = spinning }.graphicsLayer { rotationZ = angle() }.drawWithCache {
        val r = size.minDimension / 2
        val sweep = Brush.sweepGradient(0f to NetProbeColors.signal.copy(alpha = ARM_HEAD_ALPHA), ARM_TAIL_STOP to NetProbeColors.signal.copy(alpha = ARM_TAIL_ALPHA),
            ARM_CLEAR_STOP to Color.Transparent, 1f to Color.Transparent, center = center)
        val mask = Dimens.discCentreMask.toPx()
        onDrawBehind { drawCircle(sweep, r, center); drawCircle(NetProbeColors.discCentre, RadarGeometry.armMask(mask, r), center) }   // count sits on plain fill; the mask never outgrows the disc
    })
}

/** Quick scan all: an indeterminate teal arc on the ring; the disc never grows for it (spec 3.3). */
@Composable
private fun QuickArc(reduced: Boolean, modifier: Modifier) {
    if (reduced) QuickArcLayer({ Motion.STATIC_ARM_DEG }, modifier)
    else {
        val angle = rememberInfiniteTransition(label = "quick")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.ARM_TURN_MS / 2, easing = LinearEasing)), label = "quickAngle")
        QuickArcLayer({ angle.value }, modifier)
    }
}

@Composable
private fun QuickArcLayer(angle: () -> Float, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { }.graphicsLayer { rotationZ = angle() }.drawWithCache {
        val w = RING_STROKE.toPx()
        onDrawBehind {
            drawArc(NetProbeColors.signal, 0f, QUICK_ARC_SWEEP, false, topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
        }
    })
}

@Composable
private fun CountColumn(found: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("$found", style = NetProbeText.radarCount.copy(color = NetProbeColors.text, textAlign = TextAlign.Center), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_COUNT_SP.sp, maxFontSize = NetProbeText.radarCount.fontSize),
            modifier = Modifier.widthIn(max = Dimens.countMaxWidth).testTag("radarCount"))
        Text(HomeCopy.FOUND, style = NetProbeText.radarLabel, color = NetProbeColors.textMuted)
    }
}

/** The 84 dp filled Scan circle on the HERO disc: the only actionable part of the disc (spec 3.5). */
@Composable
private fun HeroButton(enabled: Boolean, canScan: Boolean, subnet: String?, onScan: () -> Unit, modifier: Modifier) {
    Box(modifier.size(Dimens.heroButton).clip(CircleShape).background(if (canScan) NetProbeColors.signal else NetProbeColors.border)
        .clickable(enabled = enabled, role = Role.Button, onClick = onScan)
        .semantics { contentDescription = RadarText.heroDescription(if (canScan) subnet else null) }.testTag("scanButton"),
        contentAlignment = Alignment.Center) {
        Text(HomeCopy.SCAN, style = NetProbeText.heroButton, color = if (canScan) NetProbeColors.background else NetProbeColors.textMuted)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.BlipOffsetTest" --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.RadarBlockTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `BlipOffsetTest` 4 passed; `RadarBlockTest` 8 passed on `28221FDH300AP9`; `BUILD SUCCESSFUL`. If `TextAutoSize.StepBased` or `BasicText(autoSize = ...)` does not resolve, it is the same API `DetailComponents.kt` already compiles against; check the import, do not add a dependency.

- [ ] **Step 5: Install and capture**

The component is not yet on Home; this capture is the regression baseline for Task 6.
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s 28221FDH300AP9 shell am start -n com.northwalessolutions.netprobe/.MainActivity; sleep 2
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t04-home-before-radar.png
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add the radar component with HERO, scanning, settled and quick-scan states"
```

---

### Task 5: Read-only HomeUiState additions

The Radar spec section 9 allows exactly five read-only fields, exposing data the view model already holds. This task comes before the Home screen (Task 6) because `HomeContent` consumes them.

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeStats.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt` (`HomeUiState`, the `state` combine, one `init` collector, one field)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/HomeStatsTest.kt`

**Interfaces:**
- Consumes: `HostEntity.lastDiscoveryRun` (`data/Entities.kt`), `HostRow.offSubnet`, `HostRow.stale`, `ScanController.state`.
- Produces:
```kotlin
data class HomeStats(val lastScanAt: Long?, val totalHosts: Int, val otherSubnetHosts: Int) { companion object { fun of(hosts: List<HostEntity>, rows: List<HostRow>): HomeStats } }
object ScanFinish { fun next(wasScanning: Boolean, scanning: Boolean, previous: Long?, now: Long): Long?
                    fun afterNetworkChange(previousNetworkId: Long?, networkId: Long?, finishedAt: Long?): Long? }
// HomeUiState gains (all read-only, defaults keep every existing constructor call compiling):
val lastScanAt: Long? = null; val totalHosts: Int = 0; val otherSubnetHosts: Int = 0; val scanStartedAt: Long? = null; val scanFinishedAt: Long? = null
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/HomeStatsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

class HomeStatsTest {
    private fun host(ip: String, run: Long) = HostEntity(networkId = 1, ip = ip, mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null,
        upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null,
        identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null,
        status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = run)
    private fun row(id: Long, other: String?, stale: Boolean) = HostRow(id, "10.0.4.$id", "h$id", "x", DeviceType.UNKNOWN, 0, HostStatus.UNMARKED, false, stale, null, other)

    @Test fun `counts are unfiltered and other subnet excludes stale hosts`() {
        val hosts = listOf(host("10.0.3.1", 100), host("10.0.4.2", 250), host("10.0.4.3", 90))
        val rows = listOf(row(1, null, false), row(2, "10.0.4.0/24", false), row(3, "10.0.4.0/24", true))
        assertEquals(HomeStats(lastScanAt = 250, totalHosts = 3, otherSubnetHosts = 1), HomeStats.of(hosts, rows))
    }
    @Test fun `no hosts means no last scan`() {
        assertEquals(HomeStats(null, 0, 0), HomeStats.of(emptyList(), emptyList()))
        assertNull(HomeStats.of(listOf(host("10.0.3.1", 0)), emptyList()).lastScanAt)
    }
    @Test fun `finish time is recorded when scanning goes false and cleared when a scan starts`() {
        assertNull(ScanFinish.next(wasScanning = false, scanning = true, previous = 5L, now = 10L))
        assertEquals(10L, ScanFinish.next(wasScanning = true, scanning = false, previous = null, now = 10L))
        assertEquals(5L, ScanFinish.next(wasScanning = false, scanning = false, previous = 5L, now = 10L))
    }
    @Test fun `a network change drops the finish time so the old duration never sits against the new network's count`() {
        assertEquals(5L, ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = 1L, finishedAt = 5L))
        assertNull(ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = 2L, finishedAt = 5L))
        assertNull(ScanFinish.afterNetworkChange(previousNetworkId = 1L, networkId = null, finishedAt = 5L))
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.HomeStatsTest" --console=plain 2>&1 | tail -8
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'HomeStats'` and `'ScanFinish'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeStats.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity

/** Read-only figures for the radar (Radar spec section 9). [hosts] and [rows] are the unfiltered lists. */
data class HomeStats(val lastScanAt: Long?, val totalHosts: Int, val otherSubnetHosts: Int) {
    companion object {
        fun of(hosts: List<HostEntity>, rows: List<HostRow>): HomeStats = HomeStats(
            lastScanAt = hosts.maxOfOrNull { it.lastDiscoveryRun }?.takeIf { it > 0 },
            totalHosts = hosts.size,
            otherSubnetHosts = rows.count { it.offSubnet && !it.stale },
        )
    }
}

/** The completion time lives in view-model state, so a Detail round trip mid-scan still measures the true duration (spec 3.4). */
object ScanFinish {
    fun next(wasScanning: Boolean, scanning: Boolean, previous: Long?, now: Long): Long? = when {
        scanning -> null
        wasScanning -> now
        else -> previous
    }

    /** "Scanned in N s" belongs to one network: when the resolved network id changes, the finish time goes (Task 16 calls this). */
    fun afterNetworkChange(previousNetworkId: Long?, networkId: Long?, finishedAt: Long?): Long? =
        if (previousNetworkId == networkId) finishedAt else null
}
```

`HomeViewModel.kt`: replace the `HomeUiState` declaration with:
```kotlin
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false,
                       val lastScanAt: Long? = null, val totalHosts: Int = 0, val otherSubnetHosts: Int = 0, val scanStartedAt: Long? = null, val scanFinishedAt: Long? = null)
```

In `class HomeViewModel`, add the field after `extraMessage`:
```kotlin
    private val finishedAt = MutableStateFlow<Long?>(null)
```

Replace the `state` property with (the combine gains `finishedAt` at index 10; rows are computed once unfiltered for the stats, then filtered as before):
```kotlin
    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts, finishedAt) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as ScanController.State; val msg = arr[8] as String?
        val done = arr[10] as Long?
        val maxPrefix = c.settings.state.value.maxPrefix
        val all = hs.map { h -> RowRules.row(h, e, maxPrefix, pc[h.id] ?: 0, stale = run > 0 && h.lastDiscoveryRun < run) }
        val stats = HomeStats.of(hs, all)
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, HomeFilter.apply(all, f, q), f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning,
            lastScanAt = stats.lastScanAt, totalHosts = stats.totalHosts, otherSubnetHosts = stats.otherSubnetHosts,
            scanStartedAt = run.takeIf { it > 0 }, scanFinishedAt = done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())
```

In `init`, add a third collector:
```kotlin
        viewModelScope.launch {
            var was = false
            controller.state.collect { st ->
                finishedAt.value = ScanFinish.next(was, st.scanning, finishedAt.value, System.currentTimeMillis())
                was = st.scanning
            }
        }
```

No other behaviour changes: `rows` stays filtered and searched, `status` stays null when idle.

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.HomeStatsTest" --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 4 passed; every existing test still passes; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Expose last scan, host counts and scan start and finish times in HomeUiState"
```

---

### Task 6: HomeContent split and the Radar Home screen

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt` (replace: thin wrapper), `ui/home/HomeComponents.kt` (replace), `ui/Common.kt` (replace `EmptyState`), `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowRulesTest.kt` (delete the `secondary line appends the other subnet` test: `rowSecondaryLine` is removed, its replacement is `RowTextTest`), `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt` (replace)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeFakes.kt`, `HomeContentTest.kt`, `RadarStateTest.kt` (same folder)

**Interfaces:**
- Consumes: `RadarBlock`, `StopPill`, `npFieldColors` (Task 4); `RadarModes`, `RadarSummaries`, `ProgressLine`, `HeaderRules`, `HomeCopy` (Task 2); `RowText`, `GroupHeaders`, `HomeGroups`, `HostGroup` (Task 3); `HomeUiState` with the Task 5 fields; `NetProbeColors`, `EdgeColors`, `edgeColor`, `NetProbeText`, `Dimens`, `Motion`, `LocalReducedMotion`, `STALE_EDGE_ALPHA`, `STALE_NAME_ALPHA` (Task 1); `RowRules.isOwn`, `HomeViewModel` public methods.
- Produces:
```kotlin
data class HomeActions(val onScan: () -> Unit = {}, val onStop: () -> Unit = {}, val onOpenHost: (Long) -> Unit = {}, val onDismissMessage: () -> Unit = {},
                       val onFilter: (StatusFilter) -> Unit = {}, val onQuery: (String) -> Unit = {}, val onQuickScanAll: () -> Unit = {},
                       val onStopQuickScan: () -> Unit = {}, val onOpenSettings: () -> Unit = {}, val onRequestLocation: () -> Unit = {})
@Composable fun HomeContent(state: HomeUiState, actions: HomeActions, progress: DiscoveryStatus? = state.status,
                            now: Long = System.currentTimeMillis(), ownModel: String = Build.MODEL)
val EdgeColorKey: SemanticsPropertyKey<Color>; val TextAlphaKey: SemanticsPropertyKey<Float>   // test hooks
fun networkTitle(env: NetworkEnv, networkName: String?): String
fun networkLine(env: NetworkEnv): String
@Composable fun NoticeLine(text: String, onDismiss: () -> Unit)
@Composable fun TypeEdge(color: Color, stale: Boolean)
@Composable fun MarkChip(text: String, modifier: Modifier = Modifier)
@Composable fun GroupHeader(text: String)
@Composable fun EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp))   // ui/Common.kt, restyled
```
- Removed: `DeviceType.icon()` and its icon imports, `rowSecondaryLine`, the Snackbar on Home (D17: every message shows in the notice line). `networkDetailLine` and its test stay (the Home line drops the DNS suffix by calling `networkLine` instead).
- Tags kept: `homeMenu`, `search`, `filterRow`, `deviceList`, `wifiNameHint`; new: `groupHeader`, `hostRow`, `rowData`, `rowName`, `notice`.
- Summary persistence: the done summary is derived from `scanStartedAt` and `scanFinishedAt`, which live in the view model (Task 5), so it survives navigation to Detail and back and is gone after a process restart, exactly as spec 3.4 requires. No `rememberSaveable` copy is kept: a saved copy would be restored after a system-initiated process death, which the spec says must show the idle text. Spec 10.2 test 8 (`RadarStateTest`) is written against this rule.

- [ ] **Step 1: Write the failing tests**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeFakes.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import java.net.Inet4Address
import java.net.InetAddress

object HomeFakes {
    fun v4(s: String) = InetAddress.getByName(s) as Inet4Address
    val env = NetworkEnv(v4("10.0.3.242"), 24, v4("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)

    /** The one place a fake HostRow is built; Task 16 changes the subnet fields here only. */
    fun row(id: Long, ip: String, name: String = "host$id", identity: String = "Synology DS220+", type: DeviceType = DeviceType.NAS, open: Int = 0,
            status: HostStatus = HostStatus.UNMARKED, gateway: Boolean = false, stale: Boolean = false, mac: String? = null, other: String? = null) =
        HostRow(id, ip, name, identity, type, open, status, gateway, stale, mac, other)

    fun rows(local: Int, other: Int = 0): List<HostRow> =
        (1..local).map { row(it.toLong(), "10.0.3.$it", name = if (it == 1) "Ubiquiti gateway" else "host$it", type = if (it == 1) DeviceType.ROUTER else DeviceType.NAS, gateway = it == 1) } +
        (1..other).map { row(100L + it, "10.0.4.${it + 10}", type = DeviceType.IOT, other = "10.0.4.0/24") }

    fun settled(rows: List<HostRow> = rows(20), filter: StatusFilter = StatusFilter.ALL, query: String = "", message: String? = null) =
        HomeUiState(env = env, rows = rows, filter = filter, query = query, networkName = "Home", message = message, lastScanAt = 1_000L, totalHosts = rows.size)

    fun scanning(rows: List<HostRow> = rows(5), filter: StatusFilter = StatusFilter.ALL, query: String = "") =
        settled(rows, filter, query).copy(scanning = true, status = DiscoveryStatus(probed = 171, total = 254, found = rows.size), scanStartedAt = 1_000L)

    fun hero(env: NetworkEnv? = this.env) = HomeUiState(env = env, networkName = env?.ssid)
}

class RecordingHomeActions {
    var scans = 0; var stops = 0; var dismissals = 0; val opened = mutableListOf<Long>(); val events = mutableListOf<String>()
    val actions: HomeActions get() = HomeActions(onScan = { scans++; events += "scan" }, onStop = { stops++ }, onOpenHost = { opened += it },
        onDismissMessage = { dismissals++; events += "dismiss" })
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.theme.EdgeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.ui.theme.STALE_NAME_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule val rule = createComposeRule()
    private val rec = RecordingHomeActions()
    private fun show(state: HomeUiState) = rule.setContent { NetProbeTheme { HomeContent(state, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }

    @Test fun pinnedHeaderStaysWhileTheListScrolls() {   // spec 10.2 test 1, first half
        show(HomeFakes.settled(HomeFakes.rows(40)))
        val radar = rule.onNodeWithTag("radarBlock").getBoundsInRoot(); val ring = rule.onNodeWithTag("radarRing").getBoundsInRoot()
        rule.onNodeWithTag("deviceList").performScrollToIndex(40)
        assertEquals(radar, rule.onNodeWithTag("radarBlock").getBoundsInRoot()); assertEquals(ring, rule.onNodeWithTag("radarRing").getBoundsInRoot())
        val listTop = rule.onNodeWithTag("deviceList").getBoundsInRoot().top
        assertEquals(listTop, rule.onAllNodesWithTag("groupHeader")[0].getBoundsInRoot().top)   // sticky header stays at the top
    }
    @Test fun scanningAtFontScale13LeavesThreeRowsAndStopIsOutsideTheList() {   // spec 10.2 test 1, second half
        rule.mainClock.autoAdvance = false
        rule.setContent { NetProbeTheme { androidx.compose.ui.test.DeviceConfigurationOverride(
            androidx.compose.ui.test.DeviceConfigurationOverride.FontScale(1.3f) then
                androidx.compose.ui.test.DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))) {
            HomeContent(HomeFakes.scanning(HomeFakes.rows(20)), rec.actions, now = 8_000L, ownModel = "Pixel 7 Pro")
        } } }
        rule.mainClock.advanceTimeBy(1_000)
        val h = rule.onNodeWithTag("deviceList").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("list is $h tall", h >= 186.dp)
        rule.onNode(hasTestTag("radarStop") and hasAnyAncestor(hasTestTag("deviceList"))).assertDoesNotExist()
        rule.onNodeWithTag("radarStop").assertIsDisplayed()
    }
    @Test fun heroHidesChipsAndSearchAndShowsThePlaceholder() {   // spec 10.2 test 3, HERO
        show(HomeFakes.hero())
        rule.onNodeWithTag("scanButton").assertIsDisplayed(); rule.onNodeWithTag("radarDisc").assertIsDisplayed()
        rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
        rule.onNodeWithText("Nothing scanned yet").assertIsDisplayed(); rule.onNodeWithText("Devices appear here as they are found").assertIsDisplayed()
    }
    @Test fun heroWithoutANetworkSaysSoOnce() {
        show(HomeFakes.hero(env = null))
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
        rule.onAllNodesWithText("No network").assertCountEquals(1)   // the network line stays silent in HERO
    }
    @Test fun settledWithNoNetworkSaysSoOnTheNetworkLine() {   // the ring's reason must be visible, not only in its content description
        show(HomeFakes.settled().copy(env = null, networkName = null))
        rule.onAllNodesWithText("No network").assertCountEquals(1)
        rule.onNodeWithTag("radarRing").assertExists()
    }
    @Test fun aFinishedScanThatFoundNothingStaysSettled() {   // spec 3.1: SETTLED includes a finished scan with zero devices; lastScanAt is null because nothing was stored
        show(HomeFakes.hero().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L))
        rule.onNodeWithTag("scanButton").assertDoesNotExist()
        rule.onNodeWithTag("radarRing").assertIsDisplayed()
        rule.onNodeWithText("No devices found").assertIsDisplayed(); rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
    }
    @Test fun scanningWithAllAndBlankQueryCollapsesChipsAndSearch() {   // spec 10.2 test 3, SCANNING
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning()); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("radarDisc").assertWidthIsEqualTo(172.dp); rule.onNodeWithTag("radarStop").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").assertDoesNotExist()
        rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
    }
    @Test fun chipsStayWhileScanningWithAFilter() {   // Review Focus 1
        rule.mainClock.autoAdvance = false
        val marked = HomeFakes.rows(3).map { it.copy(status = HostStatus.INTERESTING) }
        show(HomeFakes.scanning(marked, filter = StatusFilter.INTERESTING)); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("filterRow").assertIsDisplayed()
        rule.onNodeWithTag("filterRow").onChildren().filterToOne(hasText("Interesting")).assertIsSelected()
    }
    @Test fun searchStaysWhileScanningWithAQuery() {   // Review Focus 1
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning(query = "nas")); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun settledShowsTheRingAndTheSummary() {   // spec 10.2 test 3, SETTLED
        show(HomeFakes.settled())
        rule.onNodeWithTag("radarRing").assertWidthIsEqualTo(64.dp)
        rule.onNodeWithText("20 devices").assertIsDisplayed(); rule.onNodeWithText("Last scan 2 minutes ago").assertIsDisplayed()
        rule.onNodeWithTag("radarStop").assertDoesNotExist(); rule.onNodeWithTag("filterRow").assertIsDisplayed()
    }
    @Test fun rowAnatomy() {   // spec 10.2 test 6
        val rows = listOf(
            HomeFakes.row(1, "10.0.3.1", name = "Ubiquiti gateway", type = DeviceType.ROUTER, gateway = true, open = 3),
            HomeFakes.row(2, "10.0.3.242", name = "This phone", identity = "Android device", type = DeviceType.PHONE),
            HomeFakes.row(3, "10.0.3.59", name = "CHNAS", status = HostStatus.INTERESTING, open = 9),
            HomeFakes.row(4, "10.0.3.77", name = "Old box", stale = true, mac = "aa:bb:cc:dd:ee:ff", identity = "Unknown device"))
        show(HomeFakes.settled(rows))
        rule.onNode(SemanticsMatcher.expectValue(EdgeColorKey, EdgeColors.router) and hasContentDescription("gateway", substring = true)).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(EdgeColorKey, EdgeColors.phone) and hasContentDescription("This phone", substring = true)).assertExists()
        rule.onNodeWithText("10.0.3.242  Pixel 7 Pro", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasText("Interesting") and hasAnyAncestor(hasTestTag("hostRow")), useUnmergedTree = true).assertIsDisplayed()   // the chip, not the filter
        rule.onNodeWithText("9 open", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("not seen in the last scan", substring = true)).assertExists()
        rule.onAllNodes(hasTestTag("rowData") and SemanticsMatcher.expectValue(TextAlphaKey, 1f), useUnmergedTree = true).assertCountEquals(4)   // data line never dimmed
        rule.onAllNodes(hasTestTag("hostRow") and SemanticsMatcher.expectValue(TextAlphaKey, 1f), useUnmergedTree = true).assertCountEquals(4)   // no row-wide alpha, stale or not
        rule.onNode(hasTestTag("rowName") and SemanticsMatcher.expectValue(TextAlphaKey, STALE_NAME_ALPHA), useUnmergedTree = true).assertExists()
    }
    @Test fun staleDataLineIsDrawnAtFullStrength() {   // the MVP dimmed the whole row; pixels, not a semantics constant, prove the data line is not dimmed
        var st by mutableStateOf(HomeFakes.settled(listOf(HomeFakes.row(1, "10.0.3.50", name = "Old box", identity = "Unknown device"))))
        rule.setContent { NetProbeTheme { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }
        fun brightest(): Float {
            val img = rule.onNode(hasTestTag("rowData"), useUnmergedTree = true).captureToImage().toPixelMap()
            var best = 0f
            for (x in 0 until img.width) for (y in 0 until img.height) best = maxOf(best, img[x, y].luminance())
            return best
        }
        val fresh = brightest()
        st = st.copy(rows = st.rows.map { it.copy(stale = true) }); rule.waitForIdle()
        val stale = brightest()
        assertTrue("fresh $fresh, stale $stale", stale >= fresh - 0.02f)
    }
    @Test fun otherSubnetGetsItsOwnStickyHeader() {
        show(HomeFakes.settled(HomeFakes.rows(3, other = 2)))
        rule.onNodeWithText("Home subnet 10.0.3.0/24").assertIsDisplayed()
        rule.onNodeWithTag("deviceList").performScrollToNode(hasText("Other subnet 10.0.4.0/24"))
        rule.onNodeWithText("Other subnet 10.0.4.0/24").assertIsDisplayed()
    }
    @Test fun scanDismissesTheNoticeFirstAndTheNoticeDismisses() {
        show(HomeFakes.settled(message = "Large network (/16). Scanned your /24 only. Widen in Settings."))
        rule.onNodeWithTag("notice").assertIsDisplayed()
        rule.onNodeWithTag("radarRing").performClick()
        assertEquals(listOf("dismiss", "scan"), rec.events)
        rule.onNodeWithContentDescription("Dismiss notice").performClick(); assertEquals(2, rec.dismissals)
    }
    @Test fun emptyStatesUseTheSpecWording() {
        show(HomeFakes.settled(rows = emptyList(), query = "zzz").copy(totalHosts = 5))
        rule.onNodeWithText("No devices match \"zzz\"").assertIsDisplayed()
    }
    @Test fun tappingARowOpensIt() {
        show(HomeFakes.settled(HomeFakes.rows(3)))
        rule.onAllNodesWithTag("hostRow")[1].performClick()
        assertEquals(listOf(2L), rec.opened)
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/RadarStateTest.kt` (spec 10.2 test 8):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarStateTest {
    @get:Rule val rule = createComposeRule()

    /** The summary is a pure function of the view-model state passed in; that it survives a Detail round trip is checked on the device (Task 9, a05). */
    @Test fun doneSummaryFollowsTheViewModelStateAndClearsWhenANewScanStarts() {
        val restore = StateRestorationTester(rule)
        var state by mutableStateOf(HomeFakes.settled().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L))
        restore.setContent { NetProbeTheme { HomeContent(state, HomeActions(), now = 20_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()   // recomposes from the same state: no local copy is needed or kept
        rule.onNodeWithText("Scanned in 12 s").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        state = state.copy(scanning = true, scanStartedAt = 30_000L, scanFinishedAt = null, status = DiscoveryStatus(total = 254))
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Scanned in 12 s").assertDoesNotExist()
    }
    @Test fun aFreshStateWithoutAFinishedRunShowsTheIdleText() {   // process restart: the view model holds no finish time
        rule.setContent { NetProbeTheme { HomeContent(HomeFakes.settled(), HomeActions(), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Last scan 2 minutes ago").assertIsDisplayed()
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt` (replace; the real activity runs against whatever data the phone holds, so the launch test checks the rule for either state rather than assuming a fresh install):
```kotlin
package com.northwalessolutions.netprobe.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.MainActivity
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun radarIsPinnedAndChipsFollowTheHeroRule() {
        rule.onNodeWithTag("radarBlock").assertIsDisplayed()
        val hero = rule.onAllNodesWithTag("scanButton").fetchSemanticsNodes().isNotEmpty()
        if (hero) {   // first launch on this network: chips and search are hidden (D12)
            rule.onNodeWithTag("filterRow").assertDoesNotExist(); rule.onNodeWithTag("search").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("radarRing").assertIsDisplayed()
            val chips = rule.onNodeWithTag("filterRow").onChildren()
            chips.filterToOne(hasText("All")).assertIsDisplayed(); chips.filterToOne(hasText("Interesting")).assertIsDisplayed()
            rule.onNodeWithTag("search").assertIsDisplayed()
        }
    }
    private fun onLan(): Boolean {
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }
    // Two gated tests, one outcome each: exactly one runs.
    @Test fun scanTogglesToStopWhenOnWifi() {
        Assume.assumeTrue("needs Wi-Fi or Ethernet", onLan())
        rule.onNode(hasTestTag("scanButton") or hasTestTag("radarRing")).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("radarStop").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("radarStop").performClick()   // stopped at once; the scan only touches the LAN
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("radarRing") or hasTestTag("scanButton")).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun scanIsDisabledWithAMessageWhenOffWifi() {
        Assume.assumeFalse("needs no Wi-Fi or Ethernet", onLan())
        rule.onNodeWithTag("scanButton").assertIsNotEnabled()
        rule.onAllNodesWithText("Connect to Wi-Fi or Ethernet to scan").assertCountEquals(1)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.HomeContentTest --console=plain 2>&1 | tail -8
```
Expected: `compileDebugAndroidTestKotlin FAILED` with `Unresolved reference 'HomeContent'`, `'HomeActions'`, `'EdgeColorKey'`, `'TextAlphaKey'` (the same compile failure blocks `RadarStateTest`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/Common.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** Centred placeholder for an empty list area (spec 4.4); [pad] is the Scaffold content padding when the caller has one. */
@Composable
fun EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp)) {
    Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = NetProbeText.secondary, textAlign = TextAlign.Center, color = NetProbeColors.textMuted)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*

/** Test hooks: the edge colour of a row and the alpha a text is drawn with. Not announced by TalkBack. */
val EdgeColorKey = SemanticsPropertyKey<Color>("EdgeColor")
private var SemanticsPropertyReceiver.edgeColor by EdgeColorKey
val TextAlphaKey = SemanticsPropertyKey<Float>("TextAlpha")
private var SemanticsPropertyReceiver.textAlpha by TextAlphaKey

/** Draws at alpha [a] and publishes the same value to tests, so a test reads what is drawn and a hard-coded constant cannot pass it (spec 10.2 test 6). */
fun Modifier.drawnAlpha(a: Float): Modifier = alpha(a).semantics { textAlpha = a }

private const val DNS_SHOWN = 2
private val CHIP_HEIGHT = 32.dp
private val SEGMENT_RADIUS = 8.dp
private val MARK_RADIUS = 4.dp
private val ICON_SMALL = 16.dp

fun HostStatus.label(): String = when (this) {
    HostStatus.UNMARKED -> "Unmarked"; HostStatus.INTERESTING -> "Interesting"; HostStatus.INVESTIGATED -> "Investigated"; HostStatus.IGNORED -> "Ignored"
}

/** Address, gateway and the first two DNS servers (MVP spec 2.1 item 7). Kept with its test; Home now shows [networkLine]. */
fun networkDetailLine(env: NetworkEnv): String {
    val dns = env.dnsServers.take(DNS_SHOWN).mapNotNull { it.hostAddress }.joinToString(", ")
    val base = "${env.interfaceIp.hostAddress}/${env.prefixLength}  gateway ${env.gateway?.hostAddress ?: "none"}"
    return if (dns.isEmpty()) base else "$base  dns $dns"
}

/** The SSID when Android reveals it, else "Wi-Fi" or "Ethernet" (unchanged name rule). */
fun networkTitle(env: NetworkEnv, networkName: String?): String {
    val transport = if (env.transport == Transport.WIFI) "Wi-Fi" else "Ethernet"
    return networkName?.takeIf { it == env.ssid } ?: transport
}

/** "10.0.3.0/24  gateway 10.0.3.1": one line, no DNS suffix (spec 4.1). */
fun networkLine(env: NetworkEnv): String =
    "${SubnetMath.of(env.interfaceIp, env.prefixLength)}  gateway ${env.gateway?.hostAddress ?: "none"}"

/** The only place the network name appears; the overflow menu sits at its right end (spec 4.1 item 1). */
@Composable
fun NetworkLine(env: NetworkEnv?, networkName: String?, onRequestLocation: () -> Unit, offlineText: String? = null, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (env != null) {
                if (env.ssid == null && env.transport == Transport.WIFI) {
                    Text("Wi-Fi (tap to show the name, needs location)", style = NetProbeText.rowName, color = NetProbeColors.signal, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onRequestLocation).testTag("wifiNameHint"))
                } else {
                    Text(networkTitle(env, networkName), style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(networkLine(env), style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (offlineText != null) {   // settled with no live network: say why the ring is grey (HERO owns its own wording)
                Text(offlineText, style = NetProbeText.rowName, color = NetProbeColors.textMuted, maxLines = 1)
            }
        }
        menu()
    }
}

@Composable
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("homeMenu")) { Icon(Icons.Default.MoreVert, "Menu", tint = NetProbeColors.textSecondary) }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(if (quickScanning) "Stop quick scan" else "Quick scan all") }, enabled = quickEnabled,
                onClick = { open = false; if (quickScanning) onStopQuick() else onQuick() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
fun FilterRow(selected: StatusFilter, onSelect: (StatusFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("filterRow").horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusFilter.entries.forEach { f -> FilterPill(f.label, f == selected) { onSelect(f) } }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Dimens.touch).selectable(selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = "Filter: $label" + if (selected) ", selected" else "" }, contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = CHIP_HEIGHT).clip(CircleShape).background(if (selected) NetProbeColors.surface else Color.Transparent)
            .border(1.dp, if (selected) NetProbeColors.surface else NetProbeColors.border, CircleShape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.chip, color = if (selected) NetProbeColors.text else NetProbeColors.textSecondary)
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).testTag("search"),
        placeholder = { Text(HomeCopy.SEARCH_PLACEHOLDER, style = NetProbeText.secondary) },
        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, textStyle = NetProbeText.secondary,
        shape = RoundedCornerShape(SEGMENT_RADIUS), colors = npFieldColors())
}

/** One notice line under the radar for every transient message (D17), with a 48 dp dismiss target. */
@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter).testTag("notice"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
        Box(Modifier.size(Dimens.touch).clickable(onClick = onDismiss).semantics { contentDescription = "Dismiss notice"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(ICON_SMALL))
        }
    }
}

/** Sticky group header: caption on opaque background with a 1 dp surface rule above (spec 4.2). */
@Composable
fun GroupHeader(text: String) {
    Column(Modifier.fillMaxWidth().background(NetProbeColors.background).testTag("groupHeader").semantics { heading() }) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(text, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = 10.dp, end = Dimens.gutter, bottom = 4.dp))
    }
}

@Composable
fun TypeEdge(color: Color, stale: Boolean) {
    Box(Modifier.width(Dimens.edgeWidth).height(Dimens.edgeHeight).clip(RoundedCornerShape(Dimens.edgeRadius))
        .background(color.copy(alpha = if (stale) STALE_EDGE_ALPHA else 1f)))
}

@Composable
fun MarkChip(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NetProbeText.caption, color = NetProbeColors.background,
        modifier = modifier.clip(RoundedCornerShape(MARK_RADIUS)).background(NetProbeColors.mark).padding(vertical = 1.dp, horizontal = 6.dp))
}

/** Row anatomy (spec 4.3): edge, name with mark chip, data line, open-port count. The whole row is the click target. */
@Composable
fun HostRowItem(r: HostRow, own: Boolean, ownModel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val edge = if (own) EdgeColors.phone else r.type.edgeColor(r.isGateway)
    val description = RowText.description(r, if (own) ownModel else null)
    val nameAlpha = if (r.stale) STALE_NAME_ALPHA else 1f
    Row(modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClick = onClick).drawnAlpha(1f)   // the row itself is never dimmed: only edge and name are (spec 4.3)
        .semantics(mergeDescendants = true) { contentDescription = description; edgeColor = edge }.testTag("hostRow")
        .padding(start = Dimens.rowStart, end = Dimens.gutter, top = Dimens.rowVertical, bottom = Dimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically) {
        TypeEdge(edge, r.stale)
        Spacer(Modifier.width(Dimens.rowGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.displayName, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).drawnAlpha(nameAlpha).testTag("rowName"))
                if (r.status != HostStatus.UNMARKED) MarkChip(r.status.label(), Modifier.padding(start = 6.dp))
            }
            Text(RowText.dataLine(r, if (own) ownModel else null), style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.drawnAlpha(1f).testTag("rowData"))
        }
        if (r.openPorts > 0) Text("${r.openPorts} open", style = NetProbeText.count, color = NetProbeColors.signal, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp))
    }
}

/** HERO list area: a group-header-style line, as drawn (spec 3.5). */
@Composable
fun HeroPlaceholder() {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(HomeCopy.EMPTY_HERO, style = NetProbeText.caption, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = 10.dp, end = Dimens.gutter))
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.theme.*

data class HomeActions(
    val onScan: () -> Unit = {}, val onStop: () -> Unit = {}, val onOpenHost: (Long) -> Unit = {}, val onDismissMessage: () -> Unit = {},
    val onFilter: (StatusFilter) -> Unit = {}, val onQuery: (String) -> Unit = {}, val onQuickScanAll: () -> Unit = {},
    val onStopQuickScan: () -> Unit = {}, val onOpenSettings: () -> Unit = {}, val onRequestLocation: () -> Unit = {},
)

private const val MS_PER_S = 1000L

private fun emptyText(s: HomeUiState): String = when {
    s.query.isNotBlank() -> HomeCopy.emptySearch(s.query)
    s.filter != StatusFilter.ALL -> HomeCopy.emptyFilter(s.filter.label)
    s.scanning -> HomeCopy.EMPTY_SCANNING
    else -> HomeCopy.EMPTY_NONE
}

/**
 * All Home layout (Radar spec section 4). Stateless: tests drive any state and record which action fired.
 * PINNED: network line, radar, notice, chips and search live in the Scaffold topBar, never inside the list.
 */
@Composable
fun HomeContent(state: HomeUiState, actions: HomeActions, progress: DiscoveryStatus? = state.status,
                now: Long = System.currentTimeMillis(), ownModel: String = Build.MODEL) {
    // lastScanAt is null when a finished scan stored no host; the in-memory finish time keeps that case SETTLED (spec 3.1), not HERO.
    val mode = RadarModes.of(state.scanning, state.totalHosts, state.lastScanAt ?: state.scanFinishedAt)
    val localSubnet = state.env?.let { SubnetMath.of(it.interfaceIp, it.prefixLength).toString() }
    val elapsedS = state.scanStartedAt?.let { ((now - it) / MS_PER_S).coerceAtLeast(0) } ?: 0L
    val summary = RadarSummaries.settled(state.totalHosts, state.otherSubnetHosts, state.lastScanAt, state.scanStartedAt, state.scanFinishedAt, now)
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = Dimens.gutter, bottom = 10.dp)) {
            NetworkLine(state.env, state.networkName, actions.onRequestLocation, offlineText = HomeCopy.HERO_NO_NETWORK.takeIf { mode != RadarMode.HERO }) {
                HomeMenu(quickEnabled = state.quickScanning || (!state.scanning && state.totalHosts > 0 && state.env != null),
                    quickScanning = state.quickScanning, onQuick = actions.onQuickScanAll, onStopQuick = actions.onStopQuickScan, onSettings = actions.onOpenSettings)
            }
            RadarBlock(mode, progress?.found ?: 0, ProgressLine.of(progress, elapsedS), summary, canScan = state.env != null,
                onScan = { actions.onDismissMessage(); actions.onScan() }, onStop = actions.onStop, modifier = Modifier.padding(top = 12.dp),
                heroSubnet = localSubnet, quickScanning = state.quickScanning, onStopQuick = actions.onStopQuickScan)
            state.message?.let { NoticeLine(it, actions.onDismissMessage) }
            if (HeaderRules.showFilters(mode, state.filter, state.query)) {
                FilterRow(state.filter, actions.onFilter)
                SearchField(state.query, actions.onQuery)
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                mode == RadarMode.HERO -> HeroPlaceholder()
                state.rows.isEmpty() -> EmptyState(emptyText(state))
                else -> DeviceList(HomeGroups.of(state.rows, localSubnet), state, actions, ownModel)
            }
        }
    }
}

@Composable
private fun DeviceList(groups: List<HostGroup>, state: HomeUiState, actions: HomeActions, ownModel: String) {
    val reduced = LocalReducedMotion.current
    // Rows present when the scan began never animate; nor do initial load, filter changes or returning to the screen (spec 3.6).
    var baseline by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(state.scanning) { baseline = if (state.scanning) state.rows.map { it.id }.toSet() else null }
    val risePx = with(LocalDensity.current) { Motion.ROW_RISE.toPx() }
    LazyColumn(Modifier.fillMaxSize().testTag("deviceList")) {
        groups.forEach { g ->
            stickyHeader(key = "header:${g.subnet}:${g.local}") { GroupHeader(GroupHeaders.text(g.subnet, g.local)) }
            items(g.rows, key = { it.id }) { r ->
                val arriving = !reduced && baseline?.let { r.id !in it } == true
                val rise = remember(r.id) { Animatable(if (arriving) 1f else 0f) }
                LaunchedEffect(r.id) { if (rise.value > 0f) rise.animateTo(0f, tween(Motion.ROW_ARRIVAL_MS, easing = Motion.rowEasing)) }
                val itemModifier = if (arriving) Modifier.animateItem(fadeInSpec = tween(Motion.ROW_ARRIVAL_MS, easing = Motion.rowEasing),
                    placementSpec = null, fadeOutSpec = null) else Modifier
                HostRowItem(r, own = RowRules.isOwn(r.ip, state.env), ownModel = ownModel, onClick = { actions.onOpenHost(r.id) },
                    modifier = itemModifier.graphicsLayer { translationY = rise.value * risePx })
            }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.ui.theme.Motion
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

private const val TICK_SCANNING_MS = 1_000L
private const val TICK_IDLE_MS = 30_000L

/** Thin wrapper: builds the view model, samples progress, keeps a clock, and hands everything to [HomeContent]. */
@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    // Re-read the environment after the answer: the SSID becomes visible without waiting for a link change.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.refreshEnv(granted) }
    // Progress reaches the header at most every 250 ms (spec 3.6). The view model nulls `status` in the same emission scanning
    // stops, so the last non-null value is latched for the settle (spec 3.3) and cleared when a new scan starts.
    val sampled by remember(vm) { vm.state.map { it.status }.filterNotNull().distinctUntilChanged().sample(Motion.PROGRESS_SAMPLE_MS) }
        .collectAsStateWithLifecycle(null)
    var latched by remember { mutableStateOf<DiscoveryStatus?>(null) }
    LaunchedEffect(s.scanning) { if (s.scanning) latched = null }
    LaunchedEffect(sampled) { latched = sampled }
    val now by produceState(System.currentTimeMillis(), s.scanning) {
        while (true) { value = System.currentTimeMillis(); delay(if (s.scanning) TICK_SCANNING_MS else TICK_IDLE_MS) }
    }
    HomeContent(
        state = s,
        actions = HomeActions(onScan = vm::startScan, onStop = vm::stopScan, onOpenHost = onOpenHost, onDismissMessage = vm::dismissMessage,
            onFilter = vm::setFilter, onQuery = vm::setQuery, onQuickScanAll = vm::quickScanAll, onStopQuickScan = vm::stopQuickScan,
            onOpenSettings = onOpenSettings, onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }),
        progress = latched, now = now,
    )
}
```

`RowRulesTest.kt`: delete the whole `secondary line appends the other subnet` test function (it calls the removed `rowSecondaryLine`); the other tests in the file are unchanged.

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.HomeContentTest --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.RadarStateTest --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.HomeScreenTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `HomeContentTest` 16 passed; `RadarStateTest` 2 passed; `HomeScreenTest` 2 passed and 1 skipped by assumption; `BUILD SUCCESSFUL`. If `stickyHeader` asks for `@OptIn(ExperimentalFoundationApi::class)`, add it to `DeviceList` only (the spec expects it to be stable in foundation 1.8.2; the compiler decides). `HomeContentTest` uses `DeviceConfigurationOverride.FontScale` and `ForcedSize`: if the compiler requires an opt-in for them (for example `@OptIn(ExperimentalTestApi::class)`), add it to the test class only.

- [ ] **Step 5: Install and capture**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s 28221FDH300AP9 shell am start -n com.northwalessolutions.netprobe/.MainActivity; sleep 2
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t06-home-settled.png
"$ADB" -s 28221FDH300AP9 shell input tap 200 470; sleep 3     # ring position on a 1080x2340 Pixel 7 Pro at 2.625 density; adjust from t06-home-settled.png
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t06-home-scanning.png
sleep 20; "$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t06-home-done.png
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|FATAL" | tail -5
```
Expected: settled ring with summary, scanning disc with Stop and progress, done summary "Scanned in N s"; grouped list with a sticky "Home subnet" header; no crash lines. Look at all three images before committing.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Split HomeContent and restyle Home with the radar, grouped rows and notice line"
```

---

### Task 7: DetailContent split, the Radar Detail screen, port and reasons sheets, vulnerability sheet

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt` (replace: thin wrapper), `ui/detail/DetailComponents.kt` (replace), `ui/vulns/VulnSheet.kt` (replace), `ui/Controls.kt` (add `ScanPill`, `PinnedSegmented`, `BackToHome`)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailFakes.kt`, `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`

**Interfaces:**
- Consumes: `DetailUiState`, `PortRow`, `VulnSheetState`, `DetailViewModel` public methods (`ui/detail/DetailViewModel.kt`); `actionWord`, `primaryAction`, `actionDescription`, `sheetActions`, `versionLine`, `identityWithType`, `detailProgress` (Task 3); `IdentityLine` (`ui/home/HomeViewModel.kt`), `label()` (`ui/home/HomeComponents.kt`); `npFieldColors`, `EmptyState` (Tasks 4, 6); tokens (Task 1); `ScanProfile`, `ScanProgress`, `VulnResult`, `Vuln`.
- Produces:
```kotlin
data class DetailActions(val onBack: () -> Unit = {}, val onRename: (String?) -> Unit = {}, val onStatus: (HostStatus) -> Unit = {}, val onNote: (String) -> Unit = {},
    val onProfile: (ScanProfile) -> Unit = {}, val onCustom: (String) -> Unit = {}, val onScan: () -> Unit = {}, val onStop: () -> Unit = {},
    val onAction: (DeviceAction) -> Unit = {}, val onPortVulns: (Int) -> Unit = {}, val onDeviceVulns: () -> Unit = {}, val onCloseVulns: () -> Unit = {},
    val onRerunVulns: (String) -> Unit = {}, val onOpenLink: (String) -> Unit = {}, val onDismissMessage: () -> Unit = {})
@Composable fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false)
@Composable fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)          // ui/Controls.kt
@Composable fun PinnedSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, activeFill: Color, activeText: Color,
                                modifier: Modifier = Modifier, enabled: Boolean = true, activeWeight: FontWeight = FontWeight.W400)   // ui/Controls.kt
@Composable fun BackToHome(onBack: () -> Unit)                                                                               // ui/Controls.kt
@Composable fun NoteField(hostId: Long, note: String, onSave: (String) -> Unit, modifier: Modifier = Modifier)   // restyled, same save rules
```
- Tags: `detailHeader` (the pinned column), `detailScanBar`, `detailScanButton` (Scan/Stop pill), `statusSegments`, `note` (on whichever of the box or the field is showing), `portList`, `portAction`.
- Detail keeps a Snackbar for its own transient messages ("No app installed for that link. IP copied instead."); D17 concerns Home.
- The vulnerability sheet drops the line "Keyword matches can be noisy. Edit the query to narrow it.": it is a disclaimer (global constraint). Failures read "<reason>, showing cached results from <time>" when a cache exists, which gives the spec's "Offline, showing cached results from <time>" for the offline case.

- [ ] **Step 1: Write the failing tests**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailFakes.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

object DetailFakes {
    const val IP = "10.0.3.59"
    fun host(note: String = "", status: HostStatus = HostStatus.UNMARKED) = HostEntity(id = 7, networkId = 1, ip = IP, mac = "00:11:32:9a:1f:e4",
        reverseDnsName = "chnas.lan", mdnsName = "CHNAS", netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.NAS, customName = null, identVendor = "Synology", identModel = "DS220+", identOs = null, identConfidence = 80,
        identReasons = listOf("mDNS model DS220+"), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = status, note = note,
        isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 1)

    /** A port whose primary action uses [scheme]; [has] false models "no app installed" (no primary). */
    fun port(n: Int, scheme: String?, has: Boolean = true, name: String = "HTTP", product: String? = null, version: String? = null): PortRow {
        val uri = scheme?.let { if (it == "smb") "smb://$IP/" else "$it://$IP:$n/" }
        val open = uri?.let { DeviceAction.OpenUri(if (has) "Open in browser" else "No app installed for $scheme://", it, has) }
        val actions = listOfNotNull(open, uri?.let { DeviceAction.OpenWith(it) }, DeviceAction.Copy("Copy IP", IP), DeviceAction.Copy("Copy IP:port", "$IP:$n"))
        return PortRow(n, ServiceInfo(name, product, version, null, Confidence.CONFIRMED), open?.takeIf { has }, actions)
    }
    fun ports(n: Int) = (1..n).map { port(8000 + it, "http") }

    fun state(ports: List<PortRow> = ports(30), note: String = "", profile: ScanProfile = ScanProfile.Quick, customText: String = "",
              customError: String? = null, scanning: Boolean = false) =
        DetailUiState(host = host(note), displayName = "CHNAS", ports = ports, scanning = scanning, profile = profile,
            customPortsText = customText, customError = customError)
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailContentTest {
    @get:Rule val rule = createComposeRule()
    private val actions = mutableListOf<DeviceAction>()
    private var scans = 0; private var stops = 0
    private val recording get() = DetailActions(onAction = { actions += it }, onScan = { scans++ }, onStop = { stops++ })
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))
    private val longNote = "Line one of the note\nLine two of the note\nLine three of the note\nLine four of the note"

    private fun show(state: DetailUiState, fontScale: Float = 1f, noteExpanded: Boolean = false) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then phone) {
            DetailContent(state, recording, startNoteExpanded = noteExpanded)
        } }
    }
    /** Port rows whose action word lies wholly inside the list viewport (pixels on both sides). */
    private fun fullyVisibleRows(): Int {
        val listBottom = rule.onNodeWithTag("portList").fetchSemanticsNode().boundsInRoot.bottom
        return rule.onAllNodesWithTag("portAction").fetchSemanticsNodes().count { it.boundsInRoot.bottom <= listBottom + 0.5f }
    }

    @Test fun pinnedBlockStaysWhileThePortsScroll() {   // spec 10.2 test 2
        show(DetailFakes.state())
        val header = rule.onNodeWithTag("detailHeader").getBoundsInRoot(); val bar = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        rule.onNodeWithTag("portList").performScrollToIndex(29)
        assertEquals(header, rule.onNodeWithTag("detailHeader").getBoundsInRoot()); assertEquals(bar, rule.onNodeWithTag("detailScanBar").getBoundsInRoot())
        rule.onNodeWithTag("detailScanButton").assertIsEnabled().performClick(); assertEquals(1, scans)
        rule.onNode(hasTestTag("detailScanButton") and hasAnyAncestor(hasTestTag("portList"))).assertDoesNotExist()
    }
    @Test fun identityBlockAsDrawn() {
        show(DetailFakes.state())
        rule.onNodeWithText("Synology DS220+  NAS").assertIsDisplayed()
        rule.onNodeWithText("10.0.3.59  00:11:32:9a:1f:e4").assertIsDisplayed()
        rule.onNodeWithText("Why this identity").assertIsDisplayed(); rule.onNodeWithText("Look up vulnerabilities").assertIsDisplayed()
        rule.onNodeWithTag("statusSegments").onChildren().filter(isSelected()).assertCountEquals(1)
        rule.onNodeWithText("Add a note", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun portActionWordsAndTheCopyFallback() {
        show(DetailFakes.state(listOf(DetailFakes.port(80, "http"), DetailFakes.port(22, "ssh", name = "SSH"), DetailFakes.port(445, "smb", name = "SMB"),
            DetailFakes.port(5357, "http", has = false))))
        val words = rule.onAllNodesWithTag("portAction")   // the action box is its own clickable, so it is not merged into the row
        words[0].assertTextEquals("Open"); words[1].assertTextEquals("Connect"); words[2].assertTextEquals("Open share"); words[3].assertTextEquals("Copy")
        rule.onNode(hasTestTag("portAction") and hasContentDescription("Copy 10.0.3.59:5357")).performClick()
        assertEquals(DeviceAction.Copy("Copy IP:port", "10.0.3.59:5357"), actions.last())
    }
    @Test fun longPressOpensTheSheetWithoutTheNoAppEntry() {
        show(DetailFakes.state(listOf(DetailFakes.port(5357, "http", has = false))))
        rule.onNodeWithText("5357").performTouchInput { longClick() }
        rule.onNodeWithText("Copy IP and port").assertIsDisplayed(); rule.onNodeWithText("Open with...").assertIsDisplayed()
        rule.onNodeWithText("Look up vulnerabilities for this service").assertIsDisplayed()
        rule.onAllNodesWithText("No app installed", substring = true).assertCountEquals(0)
    }
    @Test fun whyThisIdentityOpensASheet() {
        show(DetailFakes.state())
        rule.onNodeWithText("Why this identity").performClick()
        rule.onNodeWithText("mDNS model DS220+").assertIsDisplayed()
    }
    @Test fun collapsedNoteAtFontScale13ShowsSixPortRows() {   // spec 10.2 test 7, first acceptance
        show(DetailFakes.state(), fontScale = 1.3f)
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 6)
    }
    @Test fun worstCaseAtFontScale13LeavesThreePortRows() {   // Review Focus 2, spec 10.2 test 7 second acceptance
        show(DetailFakes.state(note = longNote, profile = ScanProfile.Custom(emptyList()), customText = "22,abc", customError = "Not a port or range: 'abc'"),
            fontScale = 1.3f, noteExpanded = true)
        rule.onNodeWithText("Not a port or range: 'abc'").assertIsDisplayed()
        rule.onNodeWithTag("detailScanButton").assertIsNotEnabled()
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 3)
    }
    @Test fun scanBarHeightDoesNotJumpWhenScanStarts() {   // Review Focus 2, spec 10.2 test 7 third acceptance
        var st by mutableStateOf(DetailFakes.state())
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f) then phone) { DetailContent(st, recording) } } }
        val before = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        st = st.copy(scanning = true, progress = ScanProgress(312, 65535, 4, 0))
        rule.waitForIdle()
        val after = rule.onNodeWithTag("detailScanBar").getBoundsInRoot()
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        rule.onNodeWithText("312 of 65535, found 4").assertIsDisplayed()
        rule.onNodeWithTag("detailScanButton").performClick(); assertEquals(1, stops)
    }
    @Test fun tappingTheListCollapsesTheExpandedNote() {   // spec 5.1 item 7: Compose does not clear focus on a tap elsewhere by itself
        show(DetailFakes.state(ports = DetailFakes.ports(2), note = longNote), noteExpanded = true)
        rule.onNode(hasTestTag("note") and hasSetTextAction()).assertIsDisplayed()
        rule.onNodeWithTag("portList").performTouchInput { click(Offset(width / 2f, height - 10f)) }   // blank space under the two rows
        rule.waitForIdle()
        rule.onNode(hasTestTag("note") and hasSetTextAction()).assertDoesNotExist()   // the collapsed box has no text-entry action
        rule.onNodeWithTag("note").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.detail.DetailContentTest --console=plain 2>&1 | tail -8
```
Expected: `compileDebugAndroidTestKotlin FAILED` with `Unresolved reference 'DetailContent'` and `'DetailActions'`.

- [ ] **Step 3: Implement**

Append to `app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt` (and add the imports `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.*`, `androidx.compose.foundation.selection.selectable`, `androidx.compose.foundation.selection.selectableGroup`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft`, `androidx.compose.material3.Icon`, `androidx.compose.ui.draw.alpha`, `androidx.compose.ui.draw.drawBehind`, `androidx.compose.ui.geometry.CornerRadius`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.graphics.RectangleShape`, `androidx.compose.ui.graphics.drawscope.Stroke`, `androidx.compose.ui.text.font.FontWeight`, `androidx.compose.ui.text.style.TextAlign`, `com.northwalessolutions.netprobe.ui.theme.DISABLED_ALPHA`):
```kotlin
private val CONTROL_VISUAL = 40.dp
private val CONTROL_RADIUS = 8.dp
private val CONTROL_INSET = 4.dp

/** Detail Scan pill: teal fill "Scan", or an outlined "Stop" while scanning; 40 dp visual in a 48 dp target (spec 5.1 item 8). */
@Composable
fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = enabled || scanning
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = active, role = Role.Button, onClick = onClick)
        .alpha(if (active) 1f else DISABLED_ALPHA).testTag("detailScanButton"), contentAlignment = Alignment.Center) {
        val pill = Modifier.heightIn(min = CONTROL_VISUAL).clip(CircleShape)
            .then(if (scanning) Modifier.border(PILL_STROKE, NetProbeColors.signal, CircleShape) else Modifier.background(NetProbeColors.signal))
            .padding(horizontal = 18.dp)
        Box(pill, contentAlignment = Alignment.Center) {
            Text(if (scanning) "Stop" else "Scan", style = NetProbeText.scanPill, color = if (scanning) NetProbeColors.signal else NetProbeColors.background)
        }
    }
}

/** Equal segments in an 8 dp outlined control; 40 dp visual inside a 48 dp target; labels wrap rather than truncate (spec 5.1, 7.5). */
@Composable
fun PinnedSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, activeFill: Color, activeText: Color,
                    modifier: Modifier = Modifier, enabled: Boolean = true, activeWeight: FontWeight = FontWeight.W400) {
    Row(modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA).selectableGroup().drawBehind {
        val inset = CONTROL_INSET.toPx()
        drawRoundRect(NetProbeColors.border, topLeft = Offset(0f, inset), size = Size(size.width, size.height - 2 * inset),
            cornerRadius = CornerRadius(CONTROL_RADIUS.toPx()), style = Stroke(1.dp.toPx()))
    }) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val shape = when (i) { 0 -> RoundedCornerShape(topStart = CONTROL_RADIUS, bottomStart = CONTROL_RADIUS)
                labels.lastIndex -> RoundedCornerShape(topEnd = CONTROL_RADIUS, bottomEnd = CONTROL_RADIUS); else -> RectangleShape }
            Box(Modifier.weight(1f).heightIn(min = Dimens.touch).selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                .padding(vertical = CONTROL_INSET), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().heightIn(min = CONTROL_VISUAL).clip(shape).background(if (active) activeFill else Color.Transparent)
                    .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = NetProbeText.chip.copy(fontWeight = if (active) activeWeight else FontWeight.W400),
                        color = if (active) activeText else NetProbeColors.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** "Back to Home" with a leading chevron, a 48 dp touch row (spec 5.1 item 1, section 6). */
@Composable
fun BackToHome(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onBack).padding(horizontal = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Back to Home", style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.ScanPill
import com.northwalessolutions.netprobe.ui.home.label
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.delay

private const val NOTE_DEBOUNCE_MS = 400L
private const val NOTE_MIN_LINES = 2
private const val NOTE_MAX_LINES = 4
private const val NOTE_COLLAPSED_LINES = 2
private val PORT_COLUMN = 52.dp
private val BOX_RADIUS = 8.dp
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val STATUSES = listOf(HostStatus.UNMARKED, HostStatus.INTERESTING, HostStatus.INVESTIGATED, HostStatus.IGNORED)
private val PROFILES = listOf<ScanProfile>(ScanProfile.Quick, ScanProfile.Full, ScanProfile.Custom(emptyList()))

/** Title; a tap opens the rename dialog (Save, Reset, Cancel), unchanged rules. */
@Composable
fun EditableName(name: String, customName: String?, onRename: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { editing = true }
        .semantics { contentDescription = "Rename, $name" }, contentAlignment = Alignment.CenterStart) {
        Text(name, style = NetProbeText.title, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (editing) {
        var text by remember { mutableStateOf(customName ?: name) }
        AlertDialog(
            onDismissRequest = { editing = false }, containerColor = NetProbeColors.surface,
            title = { Text("Rename device", style = NetProbeText.rowName, color = NetProbeColors.text) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS)) },
            confirmButton = {
                TextButton({
                    // Blank, or the discovered name left unchanged, is not a custom name: store null so "Reset" only appears for real renames.
                    val t = text.trim()
                    onRename(if (t.isEmpty() || (customName == null && t == name)) null else t); editing = false
                }) { Text("Save", color = NetProbeColors.signal) }
            },
            dismissButton = {
                Row {
                    if (customName != null) TextButton({ onRename(null); editing = false }) { Text("Reset", color = NetProbeColors.signal) }
                    TextButton({ editing = false }) { Text("Cancel", color = NetProbeColors.textSecondary) }
                }
            },
        )
    }
}

@Composable
fun DetailLinks(onWhy: () -> Unit, onVulns: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) { LinkText("Why this identity", onWhy); LinkText("Look up vulnerabilities", onVulns) }
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, style = NetProbeText.secondary.copy(fontWeight = FontWeight.W500), color = NetProbeColors.signal)
    }
}

/** Four equal segments; exactly one active, in mark yellow (spec 5.1 item 6). */
@Composable
fun StatusSegments(current: HostStatus, onSelect: (HostStatus) -> Unit) {
    PinnedSegmented(STATUSES.map { it.label() }, STATUSES.indexOf(current), { onSelect(STATUSES[it]) },
        activeFill = NetProbeColors.mark, activeText = NetProbeColors.background, activeWeight = FontWeight.W500,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).testTag("statusSegments"))
}

/** Saves 400 ms after the last keystroke, and on leaving the screen if an edit is still pending (MVP behaviour). */
@Composable
fun NoteField(hostId: Long, note: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(hostId) { mutableStateOf(note) }
    val latestText by rememberUpdatedState(text); val latestNote by rememberUpdatedState(note); val save by rememberUpdatedState(onSave)
    LaunchedEffect(text) { if (text != note) { delay(NOTE_DEBOUNCE_MS); onSave(text) } }
    DisposableEffect(hostId) { onDispose { if (latestText != latestNote) save(latestText) } }
    val focusManager = LocalFocusManager.current   // Done clears focus, which collapses the note (NoteLine); tapping the list does the same (DetailContent)
    OutlinedTextField(text, { text = it }, modifier.fillMaxWidth().testTag("note"), textStyle = NetProbeText.secondaryItalic,
        minLines = NOTE_MIN_LINES, maxLines = NOTE_MAX_LINES, colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
}

/** Collapsed: a two-line box ("Add a note" when empty). Tap swaps in the field; losing focus collapses it (spec 5.1 item 7). */
@Composable
fun NoteLine(hostId: Long, note: String, onSave: (String) -> Unit, startExpanded: Boolean = false) {
    var expanded by rememberSaveable(hostId) { mutableStateOf(startExpanded) }
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 4.dp)) {
        if (expanded) {
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            NoteField(hostId, note, onSave, Modifier.focusRequester(focus).onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) expanded = false
            })
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(BOX_RADIUS))
                .border(1.dp, NetProbeColors.border, RoundedCornerShape(BOX_RADIUS)).clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("note"), contentAlignment = Alignment.CenterStart) {
                if (note.isBlank()) Text("Add a note", style = NetProbeText.secondaryItalic, color = NetProbeColors.textMuted)
                else Text(note, style = NetProbeText.secondaryItalic, color = NetProbeColors.textSecondary, maxLines = NOTE_COLLAPSED_LINES, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Profile control, Scan pill, Custom ports field, progress text and the 2 dp progress line, all pinned (spec 5.1 items 8 and 9). */
@Composable
fun ScanBar(profile: ScanProfile, customText: String, customError: String?, scanning: Boolean, progress: ScanProgress?, enabled: Boolean,
            onProfile: (ScanProfile) -> Unit, onCustom: (String) -> Unit, onScan: () -> Unit) {
    // Reserved from the data style's line height in sp, so the bar never jumps when a scan starts (about 21 dp at 1.3).
    val progressHeight = with(LocalDensity.current) { NetProbeText.data.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().testTag("detailScanBar")) {
        Row(Modifier.padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            PinnedSegmented(PROFILES.map { it.label }, PROFILES.indexOfFirst { it.label == profile.label }, { onProfile(PROFILES[it]) },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, enabled = !scanning, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            ScanPill(scanning, enabled = enabled && !(profile is ScanProfile.Custom && customError != null), onClick = onScan)
        }
        if (profile is ScanProfile.Custom) {
            OutlinedTextField(customText, onCustom, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), label = { Text("Ports") },
                placeholder = { Text("22,80,8000-8100") }, singleLine = true, enabled = !scanning, isError = customError != null,
                supportingText = customError?.let { e -> { Text(e, color = NetProbeColors.error) } }, textStyle = NetProbeText.data,
                colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).heightIn(min = progressHeight), contentAlignment = Alignment.CenterEnd) {
            if (scanning) Text(detailProgress(progress), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (scanning) LinearProgressIndicator(progress = { progress?.let { p -> if (p.total == 0) 0f else p.done / p.total.toFloat() } ?: 0f },
                modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal, trackColor = NetProbeColors.surface)
        }
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
    }
}

/** Port row: number, service and version, one action word; long-press opens the sheet (spec 5.2). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortRowItem(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onLongPress: () -> Unit) {
    val word = actionWord(schemeOf(p))
    val run = { onAction(primaryAction(ip, p)) }
    val copy = word == "Copy"
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")
        .padding(horizontal = Dimens.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${p.port}", style = NetProbeText.port, color = NetProbeColors.text, modifier = Modifier.width(PORT_COLUMN))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.service.name, style = NetProbeText.secondary, color = NetProbeColors.text)
            versionLine(p.service)?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.sizeIn(minWidth = Dimens.touch, minHeight = Dimens.touch).clickable(role = Role.Button, onClick = run)
            .semantics { contentDescription = actionDescription(ip, p) }.testTag("portAction"), contentAlignment = Alignment.CenterEnd) {
            Text(word, style = if (copy) NetProbeText.action.copy(fontWeight = FontWeight.W400) else NetProbeText.action,
                color = if (copy) NetProbeColors.textMuted else NetProbeColors.signal, maxLines = 1)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortSheet(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onVulns: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp)) {
            Text("$ip:${p.port} ${p.service.name}", style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.padding(bottom = 8.dp))
            sheetActions(p).forEach { (label, a) -> SheetRow(label, if (a is DeviceAction.OpenWith) NetProbeColors.signal else NetProbeColors.text) { onAction(a); onClose() } }
            SheetRow("Look up vulnerabilities for this service", NetProbeColors.signal) { onVulns(); onClose() }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, style = NetProbeText.secondary, color = color)
    }
}

/** "Why this identity" is always a sheet, so the pinned block has no variable-height part (spec 5.1 item 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasonsSheet(h: HostEntity, onClose: () -> Unit) {
    val found = listOfNotNull(h.mdnsName?.let { "$it (mDNS)" }, h.netbiosName?.let { "$it (NetBIOS)" }, h.upnpName?.let { "$it (UPnP)" }, h.reverseDnsName?.let { "$it (DNS)" })
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Why this identity", style = NetProbeText.rowName, color = NetProbeColors.text)
            h.identReasons.forEach { Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) }
            if (found.isNotEmpty()) Text("Found as: ${found.joinToString(", ")}", style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.home.IdentityLine
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.ui.vulns.VulnSheet

data class DetailActions(
    val onBack: () -> Unit = {}, val onRename: (String?) -> Unit = {}, val onStatus: (HostStatus) -> Unit = {}, val onNote: (String) -> Unit = {},
    val onProfile: (ScanProfile) -> Unit = {}, val onCustom: (String) -> Unit = {}, val onScan: () -> Unit = {}, val onStop: () -> Unit = {},
    val onAction: (DeviceAction) -> Unit = {}, val onPortVulns: (Int) -> Unit = {}, val onDeviceVulns: () -> Unit = {}, val onCloseVulns: () -> Unit = {},
    val onRerunVulns: (String) -> Unit = {}, val onOpenLink: (String) -> Unit = {}, val onDismissMessage: () -> Unit = {},
)

/** All Detail layout (Radar spec section 5). PINNED: the topBar column; only `portList` scrolls. */
@Composable
fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    var why by rememberSaveable { mutableStateOf(false) }
    var sheetPort by rememberSaveable { mutableStateOf<Int?>(null) }
    val ip = state.host?.ip.orEmpty()
    val focusManager = LocalFocusManager.current
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).testTag("detailHeader")) {
            BackToHome(actions.onBack)
            state.host?.let { h ->
                Column(Modifier.padding(horizontal = Dimens.gutter)) {
                    EditableName(state.displayName, h.customName, actions.onRename)
                    identityWithType(IdentityLine.of(h), h.deviceType)?.let {
                        Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(listOfNotNull(h.ip, h.mac).joinToString("  "), style = NetProbeText.data, color = NetProbeColors.textMuted)
                    DetailLinks(onWhy = { why = true }, onVulns = actions.onDeviceVulns)
                }
                StatusSegments(h.status, actions.onStatus)
                NoteLine(h.id, h.note, actions.onNote, startExpanded = startNoteExpanded)
            }
            ScanBar(state.profile, state.customPortsText, state.customError, state.scanning, state.progress, enabled = state.host != null,
                onProfile = actions.onProfile, onCustom = actions.onCustom, onScan = { if (state.scanning) actions.onStop() else actions.onScan() })
        }
    }) { pad ->
        if (state.ports.isEmpty()) EmptyState(if (state.scanning) "Scanning ports" else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).imePadding().fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }   // a tap outside the note field collapses the note; rows consume their own taps
            .testTag("portList")) {
            items(state.ports, key = { it.port }) { p -> PortRowItem(ip, p, onAction = actions.onAction, onLongPress = { sheetPort = p.port }) }
        }
    }
    if (why) state.host?.let { ReasonsSheet(it) { why = false } }
    sheetPort?.let { port ->
        state.ports.firstOrNull { it.port == port }?.let { p ->
            PortSheet(ip, p, onAction = actions.onAction, onVulns = { actions.onPortVulns(p.port) }, onClose = { sheetPort = null })
        }
    }
    state.vuln?.let { VulnSheet(it, onClose = actions.onCloseVulns, onRerun = actions.onRerunVulns, onOpen = actions.onOpenLink) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer

/** Thin wrapper over [DetailContent]; the view model and its behaviour are unchanged. */
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "host$hostId", factory = viewModelFactory { initializer { DetailViewModel(container, hostId) } })
    val s by vm.state.collectAsStateWithLifecycle()
    DetailContent(s, DetailActions(onBack = onBack, onRename = vm::setCustomName, onStatus = vm::setStatus, onNote = vm::setNote,
        onProfile = vm::setProfile, onCustom = vm::setCustomPorts, onScan = vm::startScan, onStop = vm::stopScan, onAction = vm::runAction,
        onPortVulns = vm::lookupVulnsForService, onDeviceVulns = vm::lookupVulnsForDevice, onCloseVulns = vm::closeVulns,
        onRerunVulns = vm::rerunVulnQuery, onOpenLink = vm::openLink, onDismissMessage = vm::dismissMessage))
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/vulns/VulnSheet.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.vulns

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.vulns.Vuln
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import com.northwalessolutions.netprobe.ui.detail.VulnSheetState
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SEVERITIES = listOf("CRITICAL" to "Critical", "HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low")
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

private fun formatTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

/** Text plus count, never colour alone (spec 5.3). */
private fun severityColor(severity: String): Color = when (severity.uppercase(Locale.ROOT)) {
    "CRITICAL" -> NetProbeColors.error; "HIGH" -> NetProbeColors.severityHigh; "MEDIUM" -> NetProbeColors.mark; else -> NetProbeColors.textMuted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VulnSheet(state: VulnSheetState, onClose: () -> Unit, onRerun: (String) -> Unit, onOpen: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vulnerabilities", style = NetProbeText.rowName, color = NetProbeColors.text)
            when (state) {
                is VulnSheetState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = NetProbeColors.signal); Spacer(Modifier.width(12.dp))
                    Text("Searching NVD for ${state.query}", style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                }
                is VulnSheetState.Ready -> Ready(state, onRerun, onOpen)
            }
        }
    }
}

@Composable
private fun Ready(state: VulnSheetState.Ready, onRerun: (String) -> Unit, onOpen: (String) -> Unit) {
    var text by remember(state.query) { mutableStateOf(state.query) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Query") }, textStyle = NetProbeText.secondary,
            colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(8.dp))
        TextButton({ onRerun(text) }, enabled = text.isNotBlank()) { Text("Search again", style = NetProbeText.action, color = NetProbeColors.signal) }
    }
    when (val r = state.result) {
        is VulnResult.Ok -> OkResult(r, state.query, onOpen)
        is VulnResult.Failed -> {
            val cached = r.cached
            if (cached != null) {
                Text("${r.reason}, showing cached results from ${formatTime(cached.fetchedAt)}", style = NetProbeText.secondary, color = NetProbeColors.textMuted)
                OkResult(cached, state.query, onOpen)
            } else Text(r.reason, style = NetProbeText.secondary, color = NetProbeColors.textMuted)
        }
    }
}

@Composable
private fun OkResult(r: VulnResult.Ok, query: String, onOpen: (String) -> Unit) {
    if (r.vulns.isEmpty()) { Text("No CVEs found for $query", style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = 16.dp)); return }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SEVERITIES.forEach { (key, label) -> Text("$label ${r.vulns.count { it.severity.uppercase(Locale.ROOT) == key }}", style = NetProbeText.data, color = severityColor(key)) }
    }
    val shown = if (r.total > r.vulns.size) "Showing ${r.vulns.size} of ${r.total}" else "${r.total} found"
    Text(if (r.fromCache) "$shown, cached ${formatTime(r.fetchedAt)}" else shown, style = NetProbeText.data, color = NetProbeColors.textMuted)
    LazyColumn(Modifier.fillMaxWidth()) { items(r.vulns) { v -> VulnRow(v, onOpen) } }
}

@Composable
private fun VulnRow(v: Vuln, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable { onOpen(v.url) }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.cveId, style = NetProbeText.port, color = NetProbeColors.text)
            v.cvss?.let { score ->
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.ROOT, "%.1f", score), style = NetProbeText.caption, color = NetProbeColors.background,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(severityColor(v.severity)).padding(vertical = 1.dp, horizontal = 6.dp))
            }
        }
        Text(v.summary, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v.published, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.detail.DetailContentTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `DetailContentTest` 9 passed; `BUILD SUCCESSFUL`. If `worstCaseAtFontScale13LeavesThreePortRows` fails on row count, do not shrink the touch targets: report the measured pinned-block height to the Director (spec 7.5 budget) before changing any spacing. `DetailContentTest` also uses `DeviceConfigurationOverride`: if the compiler requires an opt-in for it (for example `@OptIn(ExperimentalTestApi::class)`), add it to the test class only.

- [ ] **Step 5: Install and capture**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s 28221FDH300AP9 shell am start -n com.northwalessolutions.netprobe/.MainActivity; sleep 2
```
Open the NAS (CHNAS) row by hand or with `input tap` at its row, then:
```bash
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t07-detail-top.png
"$ADB" -s 28221FDH300AP9 shell input swipe 540 1900 540 1200 300; "$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t07-detail-scrolled.png
```
Tap Scan in the scan bar and capture `t07-detail-scanning.png`; long-press a port row and capture `t07-detail-port-sheet.png`; open "Why this identity" and capture `t07-detail-why-sheet.png`; tap "Look up vulnerabilities" and capture `t07-vuln-sheet.png`. Check `logcat` for `AndroidRuntime` as in Task 6.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Split DetailContent and restyle Detail, port and reasons sheets and the vulnerability sheet"
```

---

### Task 8: Settings restyle

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (replace; order and content unchanged)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsRowsTest.kt`

**Interfaces:**
- Consumes: `BackToHome`, `PinnedSegmented`, `npFieldColors` (Tasks 4, 7); tokens (Task 1); `AppContainer.settings`, `AppContainer.vulnCache`, `SettingsState`.
- Produces (internal so the Compose test and Task 17 can use them):
```kotlin
@Composable internal fun Section(title: String)
@Composable internal fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit)
@Composable internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, onCommit: (Int) -> Unit)
@Composable internal fun npSwitchColors(): SwitchColors
```

- [ ] **Step 1: Write the failing test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsRowsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sectionIsAHeadingAndRowsMeetTheTouchFloor() {
        var on = false
        rule.setContent { NetProbeTheme { Column {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", checked = false, supporting = "Bonjour names and services") { on = it }
            StepSlider("Sweep concurrency", 128, 32, 512, 32, "") { }
        } } }
        rule.onNode(hasText("Discovery") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        rule.onNodeWithText("Bonjour names and services").assertIsDisplayed()
        val row = rule.onNode(hasText("mDNS (Bonjour)", substring = true) and hasClickAction()).getBoundsInRoot()
        assertTrue(row.bottom - row.top >= 56.dp)
        rule.onNode(hasText("mDNS (Bonjour)", substring = true) and hasClickAction()).performClick(); assertTrue(on)
        rule.onNodeWithText("128").assertIsDisplayed()
    }
    @Test fun disabledRowIgnoresTaps() {
        var calls = 0
        rule.setContent { NetProbeTheme { SwitchRow("Probe for gateways", checked = false, enabled = false) { calls++ } } }
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.settings.SettingsRowsTest --console=plain 2>&1 | tail -8
```
Expected: `compileDebugAndroidTestKotlin FAILED`: `Cannot access 'Section': it is private in file` (and the same for `SwitchRow`, `StepSlider`), plus `No parameter with name 'supporting'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONC_MIN = 32; private const val CONC_MAX = 512; private const val CONC_STEP = 32
private const val TIMEOUT_MIN = 200; private const val TIMEOUT_MAX = 2000; private const val TIMEOUT_STEP = 100
private val PREFIX_OPTIONS = listOf(20, 22, 24)
private val SWITCH_ROW_MIN = 56.dp

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val update: ((SettingsState) -> SettingsState) -> Unit = { container.settings.update(it) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { BackToHome(onBack) } }) { pad ->
        Column(Modifier.padding(pad).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", s.mdns) { v -> update { it.copy(mdns = v) } }
            SwitchRow("SSDP / UPnP", s.ssdp) { v -> update { it.copy(ssdp = v) } }
            SwitchRow("NetBIOS names", s.netbios) { v -> update { it.copy(netbios = v) } }
            SwitchRow("Reverse DNS", s.reverseDns) { v -> update { it.copy(reverseDns = v) } }
            SwitchRow("SNMP", s.snmp) { v -> update { it.copy(snmp = v) } }
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }

            Section("Speed")
            StepSlider("Sweep concurrency", s.sweepConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, "") { v -> update { it.copy(sweepConcurrency = v) } }
            StepSlider("Sweep connect timeout", s.connectTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, " ms") { v -> update { it.copy(connectTimeoutMs = v) } }
            StepSlider("Port scan concurrency", s.scanConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, "") { v -> update { it.copy(scanConcurrency = v) } }
            StepSlider("Port scan timeout", s.scanTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, " ms") { v -> update { it.copy(scanTimeoutMs = v) } }

            Section("Large networks")
            Text("Largest network to sweep. Bigger networks are cut down to your own /24 unless widened here.", style = NetProbeText.data, color = NetProbeColors.textMuted)
            PinnedSegmented(PREFIX_OPTIONS.map { "/$it" }, PREFIX_OPTIONS.indexOf(s.maxPrefix), { i -> update { it.copy(maxPrefix = PREFIX_OPTIONS[i]) } },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())

            Section("Permissions")
            LocationPermissionRow()

            Section("Vulnerabilities")
            var key by remember { mutableStateOf(s.nvdApiKey) }
            OutlinedTextField(key, { key = it; update { st -> st.copy(nvdApiKey = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("NVD API key (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
            TextButton(onClick = {
                scope.launch {
                    val msg = try { container.vulnCache.clear(); "Vulnerability cache cleared" } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "Could not clear cache: ${e.message}" }
                    snackbar.showSnackbar(msg)
                }
            }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text("Clear vulnerability cache", style = NetProbeText.action, color = NetProbeColors.signal) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Caption in textMuted over a 1 dp surface rule, the Home group-header pattern; never teal (spec section 6). */
@Composable
internal fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(title, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
internal fun npSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = NetProbeColors.signal, checkedThumbColor = NetProbeColors.background, checkedBorderColor = NetProbeColors.signal,
    uncheckedTrackColor = NetProbeColors.surface, uncheckedThumbColor = NetProbeColors.textMuted, uncheckedBorderColor = NetProbeColors.border,
)

/** Label, optional supporting text, a switch; the whole row toggles; 56 dp minimum (spec section 6). */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SWITCH_ROW_MIN).alpha(if (enabled) 1f else DISABLED_ALPHA)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            supporting?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = npSwitchColors())
    }
}

/** Moves locally while dragging and saves once on release, so preferences are not rewritten on every drag frame. */
@Composable
internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
    Column(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Row(Modifier.padding(top = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            Text("$snapped$unit", style = NetProbeText.data, color = NetProbeColors.signal)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1, enabled = enabled,
            onValueChangeFinished = { if (snapped != value) onCommit(snapped) },
            colors = SliderDefaults.colors(thumbColor = NetProbeColors.signal, activeTrackColor = NetProbeColors.signal, inactiveTrackColor = NetProbeColors.surface,
                activeTickColor = NetProbeColors.background, inactiveTickColor = NetProbeColors.border))
    }
}

@Composable
private fun LocationPermissionRow() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Text("Android only reveals the Wi-Fi name with location permission. It is shown for display only: NetProbe tells networks apart by their gateway.",
        style = NetProbeText.data, color = NetProbeColors.textMuted)
    if (granted) Text("Location permission granted", style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
    else TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.heightIn(min = Dimens.touch)) {
        Text("Allow location for Wi-Fi name", style = NetProbeText.action, color = NetProbeColors.signal)
    }
}
```

- [ ] **Step 4: Run the test to see it pass, then the full suite and build**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.settings.SettingsRowsTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 2 passed; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Install and capture**

Open Settings from the Home overflow menu, then:
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t08-settings-top.png
"$ADB" -s 28221FDH300AP9 shell input swipe 540 1800 540 900 300; "$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t08-settings-middle.png
"$ADB" -s 28221FDH300AP9 shell input swipe 540 1800 540 600 300; "$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t08-settings-bottom.png
```
(Install first with `./gradlew :app:installDebug`.)

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Restyle Settings with section rules, switch rows, sliders and Back to Home"
```

---

### Task 9: Radar on-device QA and the full instrumented suite

**Files:**
- Create: `docs/qa/phase2-radar-ui-on-device.md`, screenshots and recordings under `docs/qa/shots/phase2/`
- Modify: anything the on-device pass proves broken (each fix is its own commit, with a failing test first where one can capture it)

**Interfaces:** none. This task executes Radar spec 10.3 on the Pixel 7 Pro and records evidence. Spec 10.2 tests are already pinned: 1, 3, 6 and Review Focus 1 in Task 6; 2, 7 and Review Focus 2 in Task 7; 4 and 5 in Task 4; 8 in Task 6 (`RadarStateTest`); 9 in Task 1 (`TypographyTest`). Static review is not acceptable: Bugsy builds, installs and looks at every screenshot.

- [ ] **Step 1: Run the whole instrumented suite once**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain 2>&1 | tail -15
```
Expected: every class passes (`HomeScreenTest` has one test skipped by assumption).

- [ ] **Step 2: Capture the on-device checklist (Radar spec 10.3)**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices | grep 28221FDH300AP9 || echo "PHONE NOT CONNECTED: stop and ask the Director to plug it in"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
shot() { "$ADB" -s 28221FDH300AP9 exec-out screencap -p > "docs/qa/shots/phase2/$1.png"; }
fs() { "$ADB" -s 28221FDH300AP9 shell settings put system font_scale "$1"; sleep 1; }
```
For each font scale (`fs 1.0`, then `fs 1.3`; suffix `-100` or `-130` on every name), drive the app by hand and run `shot <name>` at each point:

| # | Name | State to reach |
|---|---|---|
| 1 | `a01-cold-start` | `am force-stop`, then `am start -W`, shot immediately: dark bars, no white flash |
| 2 | `a02-hero` | The Director clears app data only if he agrees (it deletes his marks): `pm clear com.northwalessolutions.netprobe`; otherwise capture HERO with Wi-Fi off and on a network never scanned |
| 3 | `a03-scanning-early`, `a03-scanning-late` | 2 s and 12 s into a scan: blips visible, rows arriving |
| 4 | `a04-scanning-filter`, `a04-quick-scan` | Interesting filter on, then Scan: chips stay; overflow, Quick scan all: ring arc with "Stop" |
| 5 | `a05-settled`, `a05-settled-after-detail`, `a05-after-restart` | Done summary; open a device and come back (the "Scanned in N s" summary must still show: this is the only persistence evidence, `RadarStateTest` covers the state-to-text mapping only); `am force-stop` and relaunch: idle text |
| 6 | `a06-search-none`, `a06-filter-none`, `a06-no-network` | Search "zzzz"; Ignored filter with nothing ignored; Wi-Fi off |
| 7 | `a07-notice` | Any notice showing (for example Wi-Fi toggled mid-scan: "Network changed, scan stopped") |
| 8 | `a08-other-subnet-sticky` | Hue Bridge group (10.0.4.0/24) scrolled to mid-list, header pinned |
| 10 | `a10-detail-top`, `a10-detail-scrolled`, `a10-detail-note`, `a10-detail-scanning`, `a10-port-sheet`, `a10-why-sheet`, `a10-custom-error-note` | As named; the last at 1.3 only, with Custom "22,abc" and the note expanded |
| 11 | `a11-vulns`, `a11-vulns-offline` | Vulnerability sheet with results; again with Wi-Fi off (cache) |
| 12 | `a12-settings-top`, `a12-settings-middle`, `a12-settings-bottom` | Settings scrolled through |

Item 9 (extended discovery progress) is captured in Task 20. Then:
```bash
fs 1.0
"$ADB" -s 28221FDH300AP9 shell screenrecord --time-limit 15 /sdcard/radar.mp4   # tap Scan within the 15 s, let it settle
"$ADB" -s 28221FDH300AP9 pull /sdcard/radar.mp4 docs/qa/shots/phase2/a13-radar-grow-settle.mp4
"$ADB" -s 28221FDH300AP9 shell dumpsys gfxinfo com.northwalessolutions.netprobe reset
# run one full scan, then:
"$ADB" -s 28221FDH300AP9 shell dumpsys gfxinfo com.northwalessolutions.netprobe | grep -E "Janky frames|Total frames" | tee docs/qa/shots/phase2/a14-gfxinfo.txt
"$ADB" -s 28221FDH300AP9 shell settings put global animator_duration_scale 0; shot a15-animator-off-scanning   # start a scan first: arm frozen at 40 degrees
"$ADB" -s 28221FDH300AP9 shell settings put global animator_duration_scale 1
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|FATAL" | tee docs/qa/shots/phase2/a16-logcat-errors.txt | tail -5
```

- [ ] **Step 3: Record the results**

Create `docs/qa/phase2-radar-ui-on-device.md`:
```markdown
# NetProbe Phase 2 Part A (Radar UI) on-device QA, Pixel 7 Pro (Android <version>), <date>

| # | Check (Radar spec 10.3) | Expected | 1.0 | 1.3 | Evidence |
|---|---|---|---|---|---|
| 1 | Cold start | Dark bars, no light flash | | | a01-cold-start-100.png, -130 |
| 2 | Home first launch | HERO disc, Scan in the middle, no chips | | | a02-hero |
| 3 | Scanning early and late | Disc 172 dp, blips, rows fade and rise in | | | a03-* |
| 4 | Filter while scanning; quick scan all | Chips stay; ring arc with Stop, disc never grows | | | a04-* |
| 5 | Settled; after Detail; after restart | Summary; summary kept; idle text | | | a05-* |
| 6 | Search none; filter none; no network | Spec section 8 wording; no-network string once | | | a06-* |
| 7 | Notice line | Under the radar, dismissable | | | a07-notice |
| 8 | Other-subnet sticky header | Header pinned while rows scroll beneath | | | a08-* |
| 10 | Detail states | Pinned block intact; at 1.3 worst case at least three port rows | | | a10-* |
| 11 | Vulnerability sheet | Results; offline with cache line | | | a11-* |
| 12 | Settings | Section rules, teal switches, Back to Home | | | a12-* |
| 13 | Grow and settle recording | One continuous size change, no jump; the dark centre circle never shows outside the ring while the disc grows | | | a13-radar-grow-settle.mp4 |

| Behaviour check | Result |
|---|---|
| Stop pill stops a scan and the disc settles | |
| Tapping the disc does nothing | |
| Rotating the phone does not rotate the app | |
| Backgrounding mid-scan stops the arm; resuming restarts it (open item 11.1) | |
| Animator scale 0 freezes the arm (a15) | |
| TalkBack reads the ring, Stop pill and rows as in spec 7.3 | |
| Janky frames under 5 percent during a scan (a14) | |
| No AndroidRuntime lines (a16) | |

Open items for the Director's eye (Radar spec 11): 11.1 arm in background, 11.2 chips collapse rule, 11.3 router teal, 11.4 four chosen edge colours and `border` strength, 11.5 teal switches, 11.6 section 12 items, plus the two deviations recorded in this plan (stale edge alpha 0.6, blip spiral).
```
Fill every cell from the screenshots. A cell may not say "pass" unless the screenshot was opened and looked at.

- [ ] **Step 4: Fix what fails**

For each failure: write a failing test where one can capture it (JVM helper or Compose test), fix, rerun `./gradlew :app:testDebugUnitTest :app:assembleDebug`, reinstall, recapture, and commit `fix: <what>`. Record the commit hash in the Evidence column.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Record Radar UI on-device QA"
```

---

# Part B: Extended discovery

### Task 10: Bind extended-phase sockets to the Wi-Fi network

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/SocketBinder.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/Probe.kt` (`connect` gains `binder`), `engine/discovery/Sweep.kt` (rethrow `BindFailedException`), `engine/discovery/NetBios.kt`, `engine/discovery/Snmp.kt`, `engine/discovery/HttpTitle.kt` (honour the bound network), `netenv/NetworkEnvMonitor.kt` (`binderFor`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/common/ProbeBindTest.kt`

**Interfaces:**
- Consumes: `Probe.connect`, `Sweep`, `HostProber`, `NetBios.query`, `Snmp.query`, `HttpTitleFetcher.fetch`, `NetworkEnvMonitor`, `NetworkEnv.interfaceIp`.
- Produces:
```kotlin
interface SocketBinder { fun bind(socket: Socket); fun bind(socket: DatagramSocket); fun openConnection(url: URL): URLConnection }
class NetworkBinder(network: android.net.Network) : SocketBinder           // Network.bindSocket and Network.openConnection(url, Proxy.NO_PROXY)
class BindFailedException(cause: Throwable) : IOException
class BoundNetwork(val binder: SocketBinder) : AbstractCoroutineContextElement { companion object Key : CoroutineContext.Key<BoundNetwork> }
suspend fun currentBinder(): SocketBinder?
fun SocketBinder.bindOrFail(socket: Socket); fun SocketBinder.bindOrFail(socket: DatagramSocket)   // wrap any failure in BindFailedException
suspend fun Probe.connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false, binder: SocketBinder? = null): Outcome
fun NetworkEnvMonitor.binderFor(env: NetworkEnv): SocketBinder?            // null when no non-VPN Wi-Fi or Ethernet Network carries env.interfaceIp
```
- Deviation from extended spec 5.5, recorded: the spec gives `Probe.connect` a `network: Network? = null` parameter. `android.net.Network` cannot be constructed in JVM tests, so the parameter is the `SocketBinder` interface with `NetworkBinder` as the production implementation. The extended phase also puts a `BoundNetwork` element in the coroutine context, so the sweep's probes and the HTTP title, NetBIOS and SNMP enrichment of extra-range hosts bind without changing every function-typed engine input. Reverse DNS is a query to the configured DNS server, not a probe of the range, and is left to the system resolver.
- Rule: a bind failure is never swallowed as a timeout. `Probe.connect`, `Sweep`, `NetBios.query`, `Snmp.query` and (Task 15) the engine's `softly` and `isolated` rethrow `BindFailedException`, which ends the extended phase with `SKIPPED_BIND_FAILED`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/ProbeBindTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import com.northwalessolutions.netprobe.engine.discovery.HostProber
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.discovery.NetBios
import com.northwalessolutions.netprobe.engine.discovery.Snmp
import com.northwalessolutions.netprobe.engine.discovery.Sweep
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.net.*

class ProbeBindTest {
    private class RecordingBinder(private val fail: Boolean = false) : SocketBinder {
        val bound = mutableListOf<String>()
        override fun bind(socket: Socket) { if (fail) throw SocketException("EPERM"); bound += "tcp connected=${socket.isConnected}" }
        override fun bind(socket: DatagramSocket) { if (fail) throw SocketException("EPERM"); bound += "udp" }
        override fun openConnection(url: URL): URLConnection { bound += "url ${url.host}"; return url.openConnection(Proxy.NO_PROXY) }
    }
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address

    @Test fun `binder is applied before connect`() = runBlocking {
        ServerSocket(0).use { server ->
            val b = RecordingBinder()
            assertTrue(Probe.connect(lo, server.localPort, 500, binder = b) is Probe.Outcome.Connected)
            assertEquals(listOf("tcp connected=false"), b.bound)
        }
    }
    @Test fun `the bound network in the coroutine context is used when no binder is passed`() = runBlocking {
        ServerSocket(0).use { server ->
            val b = RecordingBinder()
            withContext(BoundNetwork(b)) { Probe.connect(lo, server.localPort, 500) }
            assertEquals(1, b.bound.size)
        }
    }
    @Test fun `no binder leaves connect as it was`() = runBlocking {
        ServerSocket(0).use { server -> assertTrue(Probe.connect(lo, server.localPort, 500) is Probe.Outcome.Connected) }
    }
    @Test fun `binder failure surfaces as BindFailedException and never connects`() = runBlocking {   // Review Focus 4
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            try { Probe.connect(lo, server.localPort, 500, binder = RecordingBinder(fail = true)); fail("expected BindFailedException") }
            catch (_: BindFailedException) { }
            assertThrows(SocketTimeoutException::class.java) { server.accept() }   // nothing reached the server on default routing
        }
    }
    @Test fun `sweep propagates a bind failure instead of counting it as a dead host`() = runBlocking {
        val sweep = Sweep(HostProber(ports = listOf(80), timeoutMs = 200), concurrency = 4)
        try { withContext(BoundNetwork(RecordingBinder(fail = true))) { sweep.run(sequenceOf(lo)).toList() }; fail("expected BindFailedException") }
        catch (_: BindFailedException) { }
    }
    @Test fun `udp and http enrichment honour the bound network`() = runBlocking {
        val b = RecordingBinder()
        withContext(BoundNetwork(b)) { NetBios.query(lo, 100); Snmp.query(lo, 100); HttpTitleFetcher().fetch(lo, ServerSocket(0).use { it.localPort }, false) }
        assertEquals(listOf("udp", "udp", "url 127.0.0.1"), b.bound)
    }
    @Test fun `udp enrichment rethrows a bind failure`() = runBlocking {
        try { withContext(BoundNetwork(RecordingBinder(fail = true))) { NetBios.query(lo, 100) }; fail("expected BindFailedException") }
        catch (_: BindFailedException) { }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.ProbeBindTest" --console=plain 2>&1 | tail -8
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'SocketBinder'`, `'BoundNetwork'`, `'BindFailedException'` and `No parameter with name 'binder'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/SocketBinder.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import android.net.Network
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Routes a socket over one network (extended spec 5.5). Production: [NetworkBinder]; tests: a recording fake. */
interface SocketBinder {
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)
    fun openConnection(url: URL): URLConnection
}

class NetworkBinder(private val network: Network) : SocketBinder {
    override fun bind(socket: Socket) = network.bindSocket(socket)
    override fun bind(socket: DatagramSocket) = network.bindSocket(socket)
    /** NO_PROXY as for every LAN fetch: never hand a probe to a Wi-Fi proxy or PAC. */
    override fun openConnection(url: URL): URLConnection = network.openConnection(url, Proxy.NO_PROXY)
}

/** A bind that fails (for example a VPN that disallows bypass) ends the extended phase; it is never read as a dead host. */
class BindFailedException(cause: Throwable) : IOException("Could not bind to the Wi-Fi network", cause)

/** Put on the coroutine context for the extended phase so sweeps and enrichment bind without new parameters. */
class BoundNetwork(val binder: SocketBinder) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BoundNetwork>
}

suspend fun currentBinder(): SocketBinder? = currentCoroutineContext()[BoundNetwork]?.binder

fun SocketBinder.bindOrFail(socket: Socket) = try { bind(socket) } catch (e: Exception) { throw BindFailedException(e) }
fun SocketBinder.bindOrFail(socket: DatagramSocket) = try { bind(socket) } catch (e: Exception) { throw BindFailedException(e) }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/Probe.kt`: change the `connect` signature and the first lines of its body (everything after `val socket = Socket()` stays as it is):
```kotlin
    suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false, binder: SocketBinder? = null): Outcome = coroutineScope {
        val socket = Socket()
        // Bound before connect; a bind failure is thrown, never classified as Timeout (extended spec 5.5).
        val route = binder ?: currentBinder()
        if (route != null) try { route.bindOrFail(socket) } catch (e: BindFailedException) { socket.close(); throw e }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Sweep.kt`: in `run`, add a catch before the generic one so the per-host block reads:
```kotlin
                    try {
                        val l = prober.probe(ip)
                        if (l.alive) send(l)
                    } catch (e: CancellationException) { throw e
                    } catch (e: BindFailedException) { throw e      // the network cannot be used: end the sweep, do not count a dead host
                    } catch (_: Exception) { /* a single host failing never stops the sweep */
                    } finally {
```
and add `import com.northwalessolutions.netprobe.engine.common.BindFailedException`.

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/NetBios.kt`: replace `query` (add imports `com.northwalessolutions.netprobe.engine.common.BindFailedException`, `com.northwalessolutions.netprobe.engine.common.bindOrFail`, `com.northwalessolutions.netprobe.engine.common.currentBinder`):
```kotlin
    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): NetBiosInfo? {
        val binder = currentBinder()   // set only for extra-range hosts in the extended phase
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    binder?.bindOrFail(s)
                    s.soTimeout = timeoutMs
                    val req = buildNodeStatusRequest()
                    s.send(DatagramPacket(req, req.size, ip, NBNS_PORT))
                    val buf = ByteArray(RECV_BUFFER_SIZE); val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    parseNodeStatus(buf.copyOf(p.length))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: BindFailedException) { throw e } catch (_: Exception) { null }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Snmp.kt`: replace `query` the same way (same three imports):
```kotlin
    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): SnmpInfo? {
        val binder = currentBinder()
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    binder?.bindOrFail(s)
                    s.soTimeout = timeoutMs
                    val req = buildGet(); s.send(DatagramPacket(req, req.size, ip, 161))
                    val buf = ByteArray(4096); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    parseResponse(buf.copyOf(p.length))
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: BindFailedException) { throw e } catch (_: Exception) { null }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitle.kt`: in `HttpTitleFetcher` read the binder before leaving the caller's context and open connections through it (add imports `com.northwalessolutions.netprobe.engine.common.SocketBinder`, `com.northwalessolutions.netprobe.engine.common.currentBinder`):
```kotlin
    suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
        val binder = currentBinder()
        return try {
            runInterruptible(Dispatchers.IO) { fetchBlocking(ip, port, tls, binder) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private fun fetchBlocking(ip: Inet4Address, port: Int, tls: Boolean, binder: SocketBinder?): HttpPeek {
```
and inside `fetchBlocking` replace the `openConnection` line with:
```kotlin
            // NO_PROXY: a LAN fetch must never be handed to a Wi-Fi proxy or PAC outside the network; bound to Wi-Fi in the extended phase.
            val c = (binder?.openConnection(url) ?: url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
```

`app/src/main/java/com/northwalessolutions/netprobe/netenv/NetworkEnvMonitor.kt`: add (imports `com.northwalessolutions.netprobe.engine.common.NetworkBinder`, `com.northwalessolutions.netprobe.engine.common.SocketBinder`):
```kotlin
    /** The Wi-Fi or Ethernet Network that carries [env]; never a VPN. Null means the extended phase must be skipped. */
    @Suppress("DEPRECATION")
    fun binderFor(env: NetworkEnv): SocketBinder? = cm.allNetworks.firstOrNull { n ->
        val nc = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
        val lp = cm.getLinkProperties(n) ?: return@firstOrNull false
        !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            lp.linkAddresses.any { it.address == env.interfaceIp }
    }?.let { NetworkBinder(it) }
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.ProbeBindTest" --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 7 passed; every existing engine test (Sweep, HostProber, NetBios, Snmp, HttpTitle, PortScanEngine) still passes; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Bind extended-phase sockets to the Wi-Fi network and never swallow a bind failure"
```

---

### Task 11: RFC1918 and subnet operations, the manual CIDR parser, and per-network settings

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/netenv/Ipv4.kt`, `netenv/SubnetOps.kt`, `netenv/Rfc1918.kt`, `netenv/CidrParser.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt` (three extended keys, `ManualRangeStore`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/netenv/Rfc1918Test.kt`, `netenv/CidrParserTest.kt`, `app/src/test/java/com/northwalessolutions/netprobe/data/ManualRangeStoreTest.kt`

**Interfaces:**
- Consumes: `Subnet`, `SubnetMath` (`netenv/Subnet.kt`), `SettingsState`, `AppSettings` (`data/Settings.kt`).
- Produces:
```kotlin
object Ipv4 { fun parse(s: String): Inet4Address? }                       // literal only, never a name lookup
fun Subnet.start(): Long; fun Subnet.end(): Long; fun Subnet.overlaps(o: Subnet): Boolean; fun Subnet.containsSubnet(o: Subnet): Boolean
fun Subnet.slash24Units(): List<Subnet>                                     // /20 to /23 expand to /24s; /24 and narrower stay as given
object Rfc1918 { val BLOCKS: List<Subnet>; fun contains(ip: Inet4Address): Boolean; fun contains(s: Subnet): Boolean; fun enclosing16(ip: Inet4Address): Subnet? }
data class CidrEntry(val subnet: Subnet, val note: String?)
data class CidrResult(val entries: List<CidrEntry>, val error: String?) { val subnets: List<Subnet> }
object CidrParser {
    const val MIN_PREFIX = 20; const val MAX_PREFIX = 30
    const val ERR_FORMAT: String; ERR_NO_PREFIX; ERR_PREFIX; ERR_TOO_LARGE; ERR_TOO_SMALL; ERR_PRIVATE; ERR_OWN
    fun parse(text: String, local: Subnet?): CidrResult
    fun parseOne(token: String, local: Subnet?): Result<CidrEntry>
    fun subnetOf(text: String): Subnet?
    fun units(s: Subnet): List<Subnet>
    fun merge(existing: List<Subnet>, added: List<Subnet>): Pair<List<Subnet>, List<String>>   // exact repeats collapse with a note
}
// SettingsState gains: val extEnabled: Boolean = false, val extMax: Int = 4, val extGatewayProbe: Boolean = false
class ManualRangeStore(read: (String) -> String?, write: (String, String?) -> Unit) {
    val revision: StateFlow<Int>; fun get(networkId: Long): List<Subnet>; fun set(networkId: Long, ranges: List<Subnet>); fun forNetwork(networkId: Long?): List<Subnet>
    companion object { fun key(networkId: Long): String }                   // "extManual.<networkId>"
}
// AppSettings gains: val manual: ManualRangeStore; companion constants EXT_MAX_MIN = 1, EXT_MAX_MAX = 16, EXT_MAX_DEFAULT = 4
```
- Messages owned by the extended-discovery spec 4.4 are used verbatim. The spec lists rejections for bad octets, bad prefixes, non-numeric text, hostnames and IPv6 without wording; this plan supplies "Not an IPv4 range, use a.b.c.d/p" and "Prefix must be a number from 20 to 30", and the duplicate note "Duplicate removed: <cidr>" (spec: "collapsed on save with an inline note"). Flagged for the Director in the Self-Review Notes.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/netenv/Rfc1918Test.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

class Rfc1918Test {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!

    @Test fun `literal parser never resolves names`() {
        for (bad in listOf("nas.local", "10.0.3", "10.0.3.256", "1.2.3.4.5", "", "a.b.c.d", "10.0.3.1 ")) assertNull(bad, Ipv4.parse(bad))
        assertEquals("10.0.3.7", Ipv4.parse("10.0.3.7")!!.hostAddress)
    }
    @Test fun `private blocks only`() {
        for (s in listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.0.1")) assertTrue(s, Rfc1918.contains(ip(s)))
        for (s in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "169.254.1.1", "192.169.0.1")) assertFalse(s, Rfc1918.contains(ip(s)))
    }
    @Test fun `enclosing slash 16 for each block`() {
        assertEquals("10.0.0.0/16", Rfc1918.enclosing16(ip("10.0.3.242")).toString())
        assertEquals("172.20.0.0/16", Rfc1918.enclosing16(ip("172.20.9.9")).toString())
        assertEquals("192.168.0.0/16", Rfc1918.enclosing16(ip("192.168.1.50")).toString())
        assertNull(Rfc1918.enclosing16(ip("172.15.1.1"))); assertNull(Rfc1918.enclosing16(ip("172.32.1.1"))); assertNull(Rfc1918.enclosing16(ip("100.64.1.1")))
    }
    @Test fun `overlap, containment and slash 24 units`() {
        assertTrue(net("10.0.4.0/24").overlaps(net("10.0.4.16/28"))); assertFalse(net("10.0.4.0/24").overlaps(net("10.0.5.0/24")))
        assertTrue(net("10.0.4.0/22").containsSubnet(net("10.0.7.0/24"))); assertFalse(net("10.0.4.0/24").containsSubnet(net("10.0.4.0/22")))
        assertTrue(net("192.168.0.0/20").overlaps(net("192.168.15.0/24")))   // high-bit networks compare unsigned
        assertEquals(listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24", "10.0.7.0/24"), net("10.0.4.0/22").slash24Units().map { it.toString() })
        assertEquals(16, net("10.0.0.0/20").slash24Units().size)
        assertEquals(listOf("10.0.4.16/28"), net("10.0.4.16/28").slash24Units().map { it.toString() })
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/netenv/CidrParserTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

class CidrParserTest {
    private val local = CidrParser.subnetOf("10.0.3.0/24")!!
    private fun error(s: String) = CidrParser.parseOne(s, local).exceptionOrNull()?.message

    @Test fun `valid forms with whitespace, commas and newlines`() {
        assertEquals(listOf("10.0.4.0/24"), CidrParser.parse("10.0.4.0/24", local).subnets.map { it.toString() })
        assertEquals(listOf("10.0.4.0/24"), CidrParser.parse("  10.0.4.0 / 24 ", local).subnets.map { it.toString() })
        val many = CidrParser.parse("10.0.4.0/24, 10.0.5.0/24\n192.168.50.0/24\r\n", local)
        assertNull(many.error); assertEquals(listOf("10.0.4.0/24", "10.0.5.0/24", "192.168.50.0/24"), many.subnets.map { it.toString() })
    }
    @Test fun `host bits are normalised with a note`() {
        val e = CidrParser.parseOne("10.0.4.20/24", local).getOrThrow()
        assertEquals("10.0.4.0/24", e.subnet.toString()); assertEquals("Normalised to 10.0.4.0/24", e.note)
        assertNull(CidrParser.parseOne("10.0.4.0/24", local).getOrThrow().note)
    }
    @Test fun `every rejection message`() {
        assertEquals(CidrParser.ERR_FORMAT, error("10.0.4.256/24")); assertEquals(CidrParser.ERR_FORMAT, error("10.0.x.0/24"))
        assertEquals(CidrParser.ERR_FORMAT, error("nas.local/24")); assertEquals(CidrParser.ERR_FORMAT, error("fd00::/64"))
        assertEquals(CidrParser.ERR_FORMAT, error("10.0.4.0/24/1"))
        assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/2a")); assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/33")); assertEquals(CidrParser.ERR_PREFIX, error("10.0.4.0/"))
        assertEquals("Add a prefix, for example /24", error("10.0.4.0"))
        assertEquals("Too large, use /20 or smaller", error("10.0.0.0/19")); assertEquals("Too large, use /20 or smaller", error("10.0.0.0/8"))
        assertEquals("Too small to sweep, use /30 or larger", error("10.0.4.1/31")); assertEquals("Too small to sweep, use /30 or larger", error("10.0.4.1/32"))
        assertEquals("Only private ranges are allowed", error("8.8.8.0/24")); assertEquals("Only private ranges are allowed", error("169.254.1.0/24"))
        assertEquals("Only private ranges are allowed", error("100.64.0.0/24"))
        assertEquals("This is your own network", error("10.0.3.0/24")); assertEquals("This is your own network", error("10.0.3.16/28"))
    }
    @Test fun `a list stops at its first error`() {
        val r = CidrParser.parse("10.0.4.0/24, 8.8.8.0/24, 10.0.5.0/24", local)
        assertEquals("Only private ranges are allowed", r.error); assertEquals(listOf("10.0.4.0/24"), r.subnets.map { it.toString() })
    }
    @Test fun `expansion of slash 20 to 23 and narrower kept as given`() {
        assertEquals(4, CidrParser.units(CidrParser.subnetOf("10.0.4.0/22")!!).size)
        assertEquals(16, CidrParser.units(CidrParser.subnetOf("10.0.16.0/20")!!).size)
        assertEquals(listOf("10.0.4.16/28"), CidrParser.units(CidrParser.subnetOf("10.0.4.16/28")!!).map { it.toString() })
        assertEquals(listOf("10.0.4.0/30"), CidrParser.units(CidrParser.subnetOf("10.0.4.0/30")!!).map { it.toString() })
    }
    @Test fun `exact repeats collapse with a note`() {
        val a = CidrParser.subnetOf("10.0.4.0/24")!!; val b = CidrParser.subnetOf("10.0.5.0/24")!!
        val (list, notes) = CidrParser.merge(listOf(a), listOf(a, b, b))
        assertEquals(listOf(a, b), list); assertEquals(listOf("Duplicate removed: 10.0.4.0/24", "Duplicate removed: 10.0.5.0/24"), notes)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/ManualRangeStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRangeStoreTest {
    private val prefs = HashMap<String, String>()
    private val store = ManualRangeStore({ prefs[it] }, { k, v -> if (v == null) prefs.remove(k) else prefs[k] = v })
    private fun net(s: String) = CidrParser.subnetOf(s)!!

    @Test fun `lists for two networks stay separate`() {
        store.set(1, listOf(net("10.0.4.0/24"))); store.set(2, listOf(net("172.16.9.0/24")))
        assertEquals(listOf(net("10.0.4.0/24")), store.get(1)); assertEquals(listOf(net("172.16.9.0/24")), store.get(2))
        assertEquals("10.0.4.0/24", prefs["extManual.1"])
    }
    @Test fun `no current network gives an empty list`() { store.set(1, listOf(net("10.0.4.0/24"))); assertEquals(emptyList<Any>(), store.forNetwork(null)) }
    @Test fun `invalid strings on load are dropped and order is kept`() {
        prefs["extManual.3"] = "10.0.9.0/24\nnot a range\n8.8.8.0/24\n\n10.0.4.0/24"
        assertEquals(listOf(net("10.0.9.0/24"), net("10.0.4.0/24")), store.get(3))
    }
    @Test fun `an empty list removes the key and every write bumps the revision`() {
        val before = store.revision.value
        store.set(4, listOf(net("10.0.4.0/24"))); store.set(4, emptyList())
        assertEquals(null, prefs["extManual.4"]); assertEquals(before + 2, store.revision.value)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.netenv.*" --tests "com.northwalessolutions.netprobe.data.ManualRangeStoreTest" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'Ipv4'`, `'Rfc1918'`, `'CidrParser'`, `'ManualRangeStore'`, `'overlaps'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/netenv/Ipv4.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

/** Dotted-quad literal to an address without any name lookup; null for anything else. */
object Ipv4 {
    private const val OCTETS = 4
    private const val OCTET_MAX = 255
    private const val MAX_DIGITS = 3
    fun parse(s: String): Inet4Address? {
        val p = s.split('.')
        if (p.size != OCTETS || p.any { it.isEmpty() || it.length > MAX_DIGITS || !it.all(Char::isDigit) }) return null
        val n = p.map { it.toInt() }.takeIf { o -> o.all { it <= OCTET_MAX } } ?: return null
        return InetAddress.getByAddress(ByteArray(OCTETS) { n[it].toByte() }) as Inet4Address
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/netenv/SubnetOps.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

private const val UNSIGNED_MASK = 0xFFFFFFFFL
private const val BITS = 32
private const val UNIT_PREFIX = 24
private const val UNIT_SHIFT = 8

/** Unsigned bounds, so 192.168.x and 172.x (negative as Int) compare correctly. */
fun Subnet.start(): Long = network.toLong() and UNSIGNED_MASK
fun Subnet.end(): Long = start() + (1L shl (BITS - prefix)) - 1
fun Subnet.overlaps(o: Subnet): Boolean = start() <= o.end() && o.start() <= end()
fun Subnet.containsSubnet(o: Subnet): Boolean = start() <= o.start() && o.end() <= end()

/** A /20 to /23 becomes its /24 units (each one cap slot); a /24 or narrower is swept as given (extended spec 4.4). */
fun Subnet.slash24Units(): List<Subnet> =
    if (prefix >= UNIT_PREFIX) listOf(this) else (0 until (1 shl (UNIT_PREFIX - prefix))).map { Subnet(network + (it shl UNIT_SHIFT), UNIT_PREFIX) }
```

`app/src/main/java/com/northwalessolutions/netprobe/netenv/Rfc1918.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address

/** The only address space extended discovery ever touches (extended spec 4.1). CGNAT and link-local are excluded by construction. */
object Rfc1918 {
    private const val ENCLOSING_PREFIX = 16
    val BLOCKS: List<Subnet> = listOf(
        Subnet(0x0A000000, 8),             // 10.0.0.0/8
        Subnet(0xAC100000.toInt(), 12),    // 172.16.0.0/12
        Subnet(0xC0A80000.toInt(), 16),    // 192.168.0.0/16
    )
    fun contains(ip: Inet4Address): Boolean = BLOCKS.any { it.contains(ip) }
    fun contains(s: Subnet): Boolean = BLOCKS.any { it.containsSubnet(s) }
    /** 10.a.0.0/16, 172.b.0.0/16 or 192.168.0.0/16 around [ip]; never the whole 10/8 (extended spec 4.3 step 2). */
    fun enclosing16(ip: Inet4Address): Subnet? = if (contains(ip)) SubnetMath.of(ip, ENCLOSING_PREFIX) else null
}
```

`app/src/main/java/com/northwalessolutions/netprobe/netenv/CidrParser.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

data class CidrEntry(val subnet: Subnet, val note: String?)
data class CidrResult(val entries: List<CidrEntry>, val error: String?) { val subnets: List<Subnet> get() = entries.map { it.subnet } }

/** Validation for the per-network manual list (extended spec 4.4). Pure; the messages are the inline Settings errors. */
object CidrParser {
    const val MIN_PREFIX = 20
    const val MAX_PREFIX = 30
    private const val MAX_BITS = 32
    private const val MAX_PREFIX_DIGITS = 2
    const val ERR_FORMAT = "Not an IPv4 range, use a.b.c.d/p"
    const val ERR_NO_PREFIX = "Add a prefix, for example /24"
    const val ERR_PREFIX = "Prefix must be a number from 20 to 30"
    const val ERR_TOO_LARGE = "Too large, use /20 or smaller"
    const val ERR_TOO_SMALL = "Too small to sweep, use /30 or larger"
    const val ERR_PRIVATE = "Only private ranges are allowed"
    const val ERR_OWN = "This is your own network"
    private val SEPARATORS = Regex("[,\\r\\n]+")

    private fun fail(message: String): Result<CidrEntry> = Result.failure(IllegalArgumentException(message))

    fun parseOne(token: String, local: Subnet?): Result<CidrEntry> {
        val t = token.filterNot { it.isWhitespace() }
        if (t.contains(':')) return fail(ERR_FORMAT)                                      // IPv6
        val parts = t.split('/')
        if (parts.size == 1) return fail(if (Ipv4.parse(parts[0]) != null) ERR_NO_PREFIX else ERR_FORMAT)
        if (parts.size != 2) return fail(ERR_FORMAT)
        val ip = Ipv4.parse(parts[0]) ?: return fail(ERR_FORMAT)
        val p = parts[1].takeIf { it.isNotEmpty() && it.length <= MAX_PREFIX_DIGITS && it.all(Char::isDigit) }?.toInt() ?: return fail(ERR_PREFIX)
        if (p > MAX_BITS) return fail(ERR_PREFIX)
        if (p < MIN_PREFIX) return fail(ERR_TOO_LARGE)
        if (p > MAX_PREFIX) return fail(ERR_TOO_SMALL)
        val s = SubnetMath.of(ip, p)
        if (!Rfc1918.contains(s)) return fail(ERR_PRIVATE)
        if (local != null && local.containsSubnet(s)) return fail(ERR_OWN)
        return Result.success(CidrEntry(s, if (SubnetMath.toInt(ip) != s.network) "Normalised to $s" else null))
    }

    /** One entry per line or comma separated; stops at the first invalid entry so the field shows one error. */
    fun parse(text: String, local: Subnet?): CidrResult {
        val out = ArrayList<CidrEntry>()
        for (token in text.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = parseOne(token, local)
            r.exceptionOrNull()?.let { return CidrResult(out, it.message) }
            out += r.getOrThrow()
        }
        return CidrResult(out, null)
    }

    /** A stored line back to a subnet; invalid lines give null and are dropped (spec 4.4). The own-network check runs at derive time. */
    fun subnetOf(text: String): Subnet? = parseOne(text, null).getOrNull()?.subnet

    fun units(s: Subnet): List<Subnet> = s.slash24Units()

    fun merge(existing: List<Subnet>, added: List<Subnet>): Pair<List<Subnet>, List<String>> {
        val list = existing.toMutableList(); val notes = ArrayList<String>()
        for (s in added) if (s in list) notes += "Duplicate removed: $s" else list += s
        return list to notes
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt`: add the three fields to `SettingsState` (after `nvdApiKey`):
```kotlin
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
```
in `load()` add:
```kotlin
        extEnabled = prefs.getBoolean("extEnabled", false),
        extMax = prefs.getInt("extMax", EXT_MAX_DEFAULT).coerceIn(EXT_MAX_MIN, EXT_MAX_MAX),
        extGatewayProbe = prefs.getBoolean("extGatewayProbe", false),
```
in `update()` extend the editor chain with:
```kotlin
            .putBoolean("extEnabled", s.extEnabled).putInt("extMax", s.extMax.coerceIn(EXT_MAX_MIN, EXT_MAX_MAX)).putBoolean("extGatewayProbe", s.extGatewayProbe)
```
add to `AppSettings`:
```kotlin
    /** The current network's manual ranges, keyed per network row so a list typed at one site is never swept at another. */
    val manual = ManualRangeStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })

    companion object {
        const val EXT_MAX_MIN = 1
        const val EXT_MAX_MAX = 16
        const val EXT_MAX_DEFAULT = 4
    }
```
and add the store at the end of the file (imports `com.northwalessolutions.netprobe.netenv.CidrParser`, `com.northwalessolutions.netprobe.netenv.Subnet`, `kotlinx.coroutines.flow.update`):
```kotlin
/** `extManual.<networkId>`, newline-joined, in SharedPreferences (extended spec 4.4 and 8.3). No schema change. */
class ManualRangeStore(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    fun get(networkId: Long): List<Subnet> = read(key(networkId))?.lines()?.mapNotNull { CidrParser.subnetOf(it.trim()) }.orEmpty()
    fun set(networkId: Long, ranges: List<Subnet>) {
        write(key(networkId), ranges.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"))
        _revision.update { it + 1 }
    }
    fun forNetwork(networkId: Long?): List<Subnet> = networkId?.let(::get).orEmpty()
    companion object { fun key(networkId: Long) = "extManual.$networkId" }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.netenv.*" --tests "com.northwalessolutions.netprobe.data.ManualRangeStoreTest" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `Rfc1918Test` 4, `CidrParserTest` 6, `ManualRangeStoreTest` 4 pass alongside the existing `SubnetTest` and `NetworkEnvMapperTest`; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add RFC1918 and subnet operations, the manual CIDR parser and per-network extended settings"
```

---

### Task 12: Candidate ranges, DNS evidence and display-time grouping rules

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRanges.kt`, `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RangeRules.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRangesTest.kt`, `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RangeRulesTest.kt`

**Interfaces:**
- Consumes: `Subnet`, `SubnetMath`, `Rfc1918`, `CidrParser.units`, `overlaps`, `start` (Task 11), `NetworkEnv.dnsServers`, `RowRules.parseIpv4`.
- Produces:
```kotlin
enum class RangeSource { MANUAL, EVIDENCE, GATEWAY_PROBE }
data class CandidateRange(val subnet: Subnet, val source: RangeSource)
data class CandidateResult(val ranges: List<CandidateRange>, val truncated: List<CandidateRange>)   // truncated = dropped only by the cap
object CandidateRanges {
    const val MIN_CAP = 1; const val MAX_CAP = 16
    fun derive(scanned: Subnet, manual: List<Subnet>, evidence: List<Inet4Address>, gatewayHits: List<Subnet>, cap: Int): CandidateResult
}
object Evidence { fun dnsServers(env: NetworkEnv, scanned: Subnet): List<Inet4Address> }
object RangeRules { fun groupFor(ip: String, scannedRanges: List<Subnet>, manualRanges: List<Subnet>): Pair<String, Boolean> }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRangesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class CandidateRangesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val scanned = net("10.0.3.0/24")
    private fun derive(manual: List<String> = emptyList(), evidence: List<String> = emptyList(), hits: List<String> = emptyList(), cap: Int = 4) =
        CandidateRanges.derive(scanned, manual.map(::net), evidence.map(::ip), hits.map(::net), cap)
    private fun CandidateResult.names() = ranges.map { "${it.subnet}:${it.source}" }

    @Test fun `local range is excluded including an overlapping manual entry`() {
        assertEquals(emptyList<String>(), derive(manual = listOf("10.0.3.128/25"), evidence = listOf("10.0.3.9")).names())
    }
    @Test fun `non rfc1918 and link local evidence is dropped`() {
        assertEquals(emptyList<String>(), derive(evidence = listOf("8.8.8.8", "169.254.1.1", "100.64.0.5")).names())
    }
    @Test fun `evidence normalises to its slash 24 and orders by count then address`() {
        assertEquals(listOf("10.0.5.0/24:EVIDENCE", "10.0.7.0/24:EVIDENCE", "10.0.9.0/24:EVIDENCE"),
            derive(evidence = listOf("10.0.9.1", "10.0.5.1", "10.0.5.2", "10.0.7.1")).names())
        assertEquals(listOf("10.0.4.0/24:EVIDENCE"), derive(evidence = listOf("10.0.4.20")).names())
    }
    @Test fun `sources are ordered manual, evidence, probe and de-duplicated`() {
        val r = derive(manual = listOf("10.0.8.0/24", "10.0.4.0/24"), evidence = listOf("10.0.4.20", "10.0.6.1"), hits = listOf("10.0.4.0/24", "10.0.2.0/24"))
        assertEquals(listOf("10.0.8.0/24:MANUAL", "10.0.4.0/24:MANUAL", "10.0.6.0/24:EVIDENCE", "10.0.2.0/24:GATEWAY_PROBE"), r.names())
    }
    @Test fun `overlap resolves to the earlier item`() {
        assertEquals(listOf("10.0.4.16/28:MANUAL"), derive(manual = listOf("10.0.4.16/28"), evidence = listOf("10.0.4.20")).names())
    }
    @Test fun `gateway hits order by address`() {
        assertEquals(listOf("10.0.2.0/24:GATEWAY_PROBE", "10.0.10.0/24:GATEWAY_PROBE"), derive(hits = listOf("10.0.10.0/24", "10.0.2.0/24")).names())
    }
    @Test fun `cap boundaries and coercion`() {
        val many = listOf("10.0.16.0/20", "10.0.32.0/24", "10.0.33.0/24")   // 16 units plus 2
        assertEquals(1, derive(manual = many, cap = 1).ranges.size); assertEquals(4, derive(manual = many, cap = 4).ranges.size)
        assertEquals(16, derive(manual = many, cap = 16).ranges.size); assertEquals(16, derive(manual = many, cap = 40).ranges.size)
        assertEquals(1, derive(manual = many, cap = 0).ranges.size)
        assertEquals(listOf("10.0.32.0/24", "10.0.33.0/24"), derive(manual = many, cap = 16).truncated.map { it.subnet.toString() })
    }
    @Test fun `truncated lists only cap drops, never overlap drops`() {
        val r = derive(manual = listOf("10.0.4.0/24", "10.0.5.0/24"), evidence = listOf("10.0.4.9", "10.0.6.1"), cap = 1)
        assertEquals(listOf("10.0.4.0/24"), r.ranges.map { it.subnet.toString() })
        assertEquals(listOf("10.0.5.0/24", "10.0.6.0/24"), r.truncated.map { it.subnet.toString() })
    }
    @Test fun `empty inputs give empty output`() { assertEquals(CandidateResult(emptyList(), emptyList()), derive()) }
    @Test fun `clamped slash 16 leaves the other slash 24s eligible`() {
        // Phone on 10.0.0.0/16, maxPrefix 22: the swept range is 10.0.3.0/24 and exclusion is by the swept range only (spec 11.2).
        assertEquals(listOf("10.0.200.0/24:EVIDENCE"), derive(evidence = listOf("10.0.200.5")).names())
    }
    @Test fun `a manual entry that overlaps a changed local range is dropped at derive time`() {
        val r = CandidateRanges.derive(net("10.0.4.0/24"), listOf(net("10.0.4.0/24"), net("10.0.5.0/24")), emptyList(), emptyList(), 4)
        assertEquals(listOf("10.0.5.0/24"), r.ranges.map { it.subnet.toString() })
    }
    @Test fun `dns servers outside the swept range count as evidence`() {
        val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"),
            listOf(ip("10.0.9.53"), ip("10.0.3.1"), ip("1.1.1.1"), InetAddress.getByName("fd00::53")), null, null, Transport.WIFI)
        assertEquals(listOf("10.0.9.53"), Evidence.dnsServers(env, scanned).map { it.hostAddress })
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RangeRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeRulesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private val local = net("10.0.3.0/24")

    @Test fun `local range is not off subnet`() { assertEquals("10.0.3.0/24" to false, RangeRules.groupFor("10.0.3.9", listOf(local), emptyList())) }
    @Test fun `a swept extra range groups its hosts`() {
        assertEquals("10.0.4.0/24" to true, RangeRules.groupFor("10.0.4.20", listOf(local, net("10.0.4.0/24")), emptyList()))
    }
    @Test fun `a manual slash 28 inside a slash 24 groups only its own hosts`() {
        val manual = listOf(net("10.0.4.16/28"))
        assertEquals("10.0.4.16/28" to true, RangeRules.groupFor("10.0.4.20", listOf(local, net("10.0.4.16/28")), manual))
        assertEquals("10.0.4.0/24" to true, RangeRules.groupFor("10.0.4.50", listOf(local, net("10.0.4.16/28")), manual))
    }
    @Test fun `an unswept off subnet host falls back to its slash 24`() { assertEquals("10.0.7.0/24" to true, RangeRules.groupFor("10.0.7.3", listOf(local), emptyList())) }
    @Test fun `with no local range known the slash 24 is not off subnet`() { assertEquals("10.0.7.0/24" to false, RangeRules.groupFor("10.0.7.3", emptyList(), emptyList())) }
    @Test fun `garbage is never resolved`() { assertEquals("nas.local" to false, RangeRules.groupFor("nas.local", listOf(local), emptyList())) }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.CandidateRangesTest" --tests "com.northwalessolutions.netprobe.ui.home.RangeRulesTest" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'CandidateRanges'`, `'CandidateResult'`, `'Evidence'`, `'RangeRules'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRanges.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.*
import java.net.Inet4Address

enum class RangeSource { MANUAL, EVIDENCE, GATEWAY_PROBE }
data class CandidateRange(val subnet: Subnet, val source: RangeSource)
/** [truncated] holds valid items rejected only because the cap was full, so the engine can report them. */
data class CandidateResult(val ranges: List<CandidateRange>, val truncated: List<CandidateRange>)

object CandidateRanges {
    const val MIN_CAP = 1
    const val MAX_CAP = 16
    private const val UNIT_PREFIX = 24

    /**
     * Extended spec 4.5: manual units first (list order), then evidence /24s by distinct-address count then address, then gateway
     * hits by address. An item is accepted if it is RFC1918, does not overlap the swept local range and does not overlap an earlier
     * item; accepting stops at the cap and later valid items go to [CandidateResult.truncated].
     */
    fun derive(scanned: Subnet, manual: List<Subnet>, evidence: List<Inet4Address>, gatewayHits: List<Subnet>, cap: Int): CandidateResult {
        val limit = cap.coerceIn(MIN_CAP, MAX_CAP)
        val ordered = ArrayList<CandidateRange>()
        manual.flatMap { CidrParser.units(it) }.forEach { ordered += CandidateRange(it, RangeSource.MANUAL) }
        evidence.filter { Rfc1918.contains(it) && !scanned.contains(it) }.distinct()
            .groupBy { SubnetMath.of(it, UNIT_PREFIX) }.entries
            .sortedWith(compareByDescending<Map.Entry<Subnet, List<Inet4Address>>> { it.value.size }.thenBy { it.key.start() })
            .forEach { ordered += CandidateRange(it.key, RangeSource.EVIDENCE) }
        gatewayHits.sortedBy { it.start() }.forEach { ordered += CandidateRange(it, RangeSource.GATEWAY_PROBE) }

        val accepted = ArrayList<CandidateRange>(); val truncated = ArrayList<CandidateRange>()
        for (c in ordered) {
            if (!Rfc1918.contains(c.subnet) || c.subnet.overlaps(scanned)) continue
            if (accepted.any { it.subnet.overlaps(c.subnet) } || truncated.any { it.subnet.overlaps(c.subnet) }) continue
            if (accepted.size < limit) accepted += c else truncated += c
        }
        return CandidateResult(accepted, truncated)
    }
}

object Evidence {
    /** RFC1918 DNS servers outside the swept range: on client sites usually the server range (extended spec 4.2). */
    fun dnsServers(env: NetworkEnv, scanned: Subnet): List<Inet4Address> =
        env.dnsServers.filterIsInstance<Inet4Address>().filter { Rfc1918.contains(it) && !scanned.contains(it) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RangeRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath

/** Display-time grouping (extended spec 7): nothing is stored, so a changed maxPrefix or manual list regroups at once. */
object RangeRules {
    private const val FALLBACK_PREFIX = 24

    /** scannedRanges[0] is the local swept range; later items are ranges swept in the last run. First match wins. */
    fun groupFor(ip: String, scannedRanges: List<Subnet>, manualRanges: List<Subnet>): Pair<String, Boolean> {
        val a = RowRules.parseIpv4(ip) ?: return ip to false
        val local = scannedRanges.firstOrNull() ?: return SubnetMath.of(a, FALLBACK_PREFIX).toString() to false
        if (local.contains(a)) return local.toString() to false
        (scannedRanges.drop(1) + manualRanges).firstOrNull { it.contains(a) }?.let { return it.toString() to true }
        return SubnetMath.of(a, FALLBACK_PREFIX).toString() to true
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.CandidateRangesTest" --tests "com.northwalessolutions.netprobe.ui.home.RangeRulesTest" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `CandidateRangesTest` 12 and `RangeRulesTest` 6 pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add candidate range derivation, DNS evidence and display-time range grouping"
```

---

### Task 13: Gateway probing: targets, the TEST-NET pre-check rule, random high ports and the bounded probe

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbe.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbeTest.kt`

**Interfaces:**
- Consumes: `Probe.Outcome` (`engine/common/Probe.kt`), `HostProber.DEFAULT_PORTS`, `QuickPorts.list`, `Rfc1918`, `Ipv4`, `slash24Units`, `overlaps`, `start` (Task 11), `SubnetMath`, `NetworkEnv`.
- Produces:
```kotlin
data class GatewayTargets(val enclosing: Subnet?, val exclude: Subnet, val gatewayOffset: Int?, val seeds: List<Inet4Address>) {
    fun byUnit(): Map<Subnet, List<Inet4Address>>          // each /24 that would become a candidate, with the addresses to try in it
    companion object { const val LOW = 1; const val HIGH = 254; val SEEDS: List<String>; fun of(env: NetworkEnv, scanned: Subnet): GatewayTargets }
}
object AnswersForEverything { val TEST_NET: List<Inet4Address>; const val TEST_PORT = 80
    fun answered(o: Probe.Outcome): Boolean; fun of(outcomes: List<Probe.Outcome>): Boolean }
object RandomHighPort { const val MIN = 49152; const val MAX_EXCLUSIVE = 65536; fun pick(random: Random): Int }
object GatewayProbe {
    const val TIMEOUT_MS = 400; const val CONCURRENCY = 64; val PORTS: List<Int>   // 80 then 443
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
                    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)): List<Subnet>   // onProgress(unitsDone, units, connectAttempts)
}
```
- Production `connect` (wired in Task 15) is `Probe.connect(ip, port, GatewayProbe.TIMEOUT_MS, binder = b)`, so every attempt is cancellable through the MVP watcher and bound to Wi-Fi (extended spec 4.3 step 7). No payload is sent and no banner read.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.portscan.QuickPorts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class GatewayProbeTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun env(phone: String, gw: String) = NetworkEnv(ip(phone), 24, ip(gw), emptyList(), null, null, Transport.WIFI)

    @Test fun `walk covers the enclosing slash 16 except the swept range, with low and high offsets`() {
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        assertEquals("10.0.0.0/16", t.enclosing.toString())
        val units = t.byUnit()
        assertFalse(net("10.0.3.0/24") in units)
        assertEquals(listOf("10.0.4.1", "10.0.4.254"), units[net("10.0.4.0/24")]!!.map { it.hostAddress })
        assertEquals(255, units.keys.count { t.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
    }
    @Test fun `the phone's own gateway offset is probed in every slash 24 when it is not 1 or 254`() {
        val t = GatewayTargets.of(env("192.168.1.50", "192.168.1.250"), net("192.168.1.0/24"))
        assertEquals(250, t.gatewayOffset)
        assertEquals(listOf("192.168.7.1", "192.168.7.254", "192.168.7.250"), t.byUnit()[net("192.168.7.0/24")]!!.map { it.hostAddress })
        assertNull(GatewayTargets.of(env("10.0.3.242", "10.0.3.254"), net("10.0.3.0/24")).gatewayOffset)
    }
    @Test fun `seeds skip addresses inside the phone's own slash 16`() {
        val seeds = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24")).seeds.map { it.hostAddress }
        assertFalse("10.0.0.1" in seeds); assertFalse("10.0.1.1" in seeds); assertFalse("10.0.0.254" in seeds)
        assertTrue("10.1.1.1" in seeds); assertTrue("172.16.0.1" in seeds); assertTrue("192.168.1.1" in seeds)
        assertEquals(20, GatewayTargets.SEEDS.size)
    }
    @Test fun `a non rfc1918 phone address skips the walk but keeps the seed list`() {
        val t = GatewayTargets.of(env("100.64.1.5", "100.64.1.1"), net("10.0.3.0/24"))
        assertNull(t.enclosing); assertEquals(20, t.byUnit().values.sumOf { it.size })
    }
    @Test fun `a unit is a hit on connect or refused, ports are 80 then 443 only`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        val hits = GatewayProbe.run(t, connect = { a, port ->
            calls += "${a.hostAddress}:$port"
            when (a.hostAddress) { "10.0.4.1" -> Probe.Outcome.Refused; "10.0.9.254" -> if (port == 443) Probe.Outcome.Connected(3, null) else Probe.Outcome.Timeout
                "10.0.5.1" -> Probe.Outcome.Unreachable; else -> Probe.Outcome.Timeout }
        }, dispatcher = Dispatchers.Default)
        assertEquals(listOf("10.0.4.0/24", "10.0.9.0/24"), hits.map { it.toString() })
        assertTrue(calls.all { it.endsWith(":80") || it.endsWith(":443") })
        assertFalse("10.0.5.1:443" in calls)                  // unreachable is definitive: no second port
        assertFalse("10.0.4.254:80" in calls)                 // a hit ends the unit
        assertTrue("10.0.6.1:443" in calls)                   // a timeout tries the next port
    }
    @Test fun `progress reaches every unit, never steps back, counts attempts and concurrency stays at 64`() = runBlocking {
        val inFlight = AtomicInteger(); val peak = AtomicInteger()
        var last = Triple(0, 0, 0); var steppedBack = false
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        GatewayProbe.run(t, connect = { _, _ ->
            val n = inFlight.incrementAndGet(); peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }; delay(2); inFlight.decrementAndGet(); Probe.Outcome.Timeout
        }, onProgress = { d, total, attempts ->
            // Reports arrive from many threads; keep the maximum so the assertion never depends on which thread called last, and flag any step back.
            synchronized(this) { if (d < last.first) steppedBack = true; last = Triple(maxOf(last.first, d), total, maxOf(last.third, attempts)) }
        }, dispatcher = Dispatchers.Default)
        val units = t.byUnit()
        assertEquals(Triple(units.size, units.size, units.values.sumOf { it.size } * GatewayProbe.PORTS.size), last)   // every address timed out on both ports
        assertFalse("progress stepped backwards", steppedBack)
        assertTrue("peak ${peak.get()}", peak.get() <= GatewayProbe.CONCURRENCY)
    }
    @Test fun `test-net pre-check reads any answer as answers for everything`() {
        assertFalse(AnswersForEverything.of(listOf(Probe.Outcome.Timeout, Probe.Outcome.Unreachable, Probe.Outcome.Timeout, Probe.Outcome.Timeout)))
        assertTrue(AnswersForEverything.of(listOf(Probe.Outcome.Timeout, Probe.Outcome.Refused)))
        assertTrue(AnswersForEverything.of(listOf(Probe.Outcome.Connected(1, null))))
        assertEquals(listOf("192.0.2.1", "198.51.100.1"), AnswersForEverything.TEST_NET.map { it.hostAddress })
    }
    @Test fun `random high ports avoid every liveness and quick port`() {
        val r = Random(42); val excluded = (HostProber.DEFAULT_PORTS + QuickPorts.list).toSet()
        repeat(2_000) { val p = RandomHighPort.pick(r); assertTrue(p in 49152..65535 && p !in excluded) }
        assertEquals(RandomHighPort.pick(Random(7)), RandomHighPort.pick(Random(7)))
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.GatewayProbeTest" --console=plain 2>&1 | tail -8
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'GatewayTargets'`, `'GatewayProbe'`, `'AnswersForEverything'`, `'RandomHighPort'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbe.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.portscan.QuickPorts
import com.northwalessolutions.netprobe.netenv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** What the gateway probe walks (extended spec 4.3 steps 1 to 4). */
data class GatewayTargets(val enclosing: Subnet?, val exclude: Subnet, val gatewayOffset: Int?, val seeds: List<Inet4Address>) {
    fun byUnit(): Map<Subnet, List<Inet4Address>> {
        val out = LinkedHashMap<Subnet, MutableList<Inet4Address>>()
        val offsets = listOfNotNull(LOW, HIGH, gatewayOffset).distinct()
        enclosing?.slash24Units()?.filterNot { it.overlaps(exclude) }?.forEach { u -> out[u] = offsets.map { SubnetMath.toAddress(u.network + it) }.toMutableList() }
        seeds.forEach { s -> out.getOrPut(SubnetMath.of(s, UNIT_PREFIX)) { mutableListOf() }.add(s) }
        return out
    }

    companion object {
        const val LOW = 1
        const val HIGH = 254
        private const val UNIT_PREFIX = 24
        private const val LAST_OCTET = 0xFF
        /** Common gateways in the other RFC1918 blocks, so a guest Wi-Fi on 192.168.x can find server ranges on 10.x or 172.16.x. */
        val SEEDS = listOf("10.0.0.1", "10.0.1.1", "10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "10.0.0.254", "172.16.0.1", "172.16.1.1",
            "172.16.16.1", "172.17.0.1", "172.31.0.1", "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1", "192.168.10.1",
            "192.168.88.1", "192.168.100.1", "192.168.178.1")

        fun of(env: NetworkEnv, scanned: Subnet): GatewayTargets {
            val enclosing = Rfc1918.enclosing16(env.interfaceIp)
            val offset = env.gateway?.let { SubnetMath.toInt(it) and LAST_OCTET }?.takeUnless { it == LOW || it == HIGH || it == 0 }
            val seeds = SEEDS.mapNotNull { Ipv4.parse(it) }.filter { s -> (enclosing == null || !enclosing.contains(s)) && !scanned.contains(s) }
            return GatewayTargets(enclosing, scanned, offset, seeds)
        }
    }
}

/** TEST-NET pre-check (extended spec 4.3 step 0): RFC 5737 addresses are never assigned, so any answer means "answers for everything". */
object AnswersForEverything {
    val TEST_NET: List<Inet4Address> = listOf("192.0.2.1", "198.51.100.1").map { Ipv4.parse(it)!! }
    const val TEST_PORT = 80
    fun answered(o: Probe.Outcome): Boolean = o is Probe.Outcome.Connected || o == Probe.Outcome.Refused
    fun of(outcomes: List<Probe.Outcome>): Boolean = outcomes.any(::answered)
}

/** One random high port per run, never a liveness or Quick port, for the pre-check and the canaries (extended spec 4.7). */
object RandomHighPort {
    const val MIN = 49152
    const val MAX_EXCLUSIVE = 65536
    private val EXCLUDED: Set<Int> = (HostProber.DEFAULT_PORTS + QuickPorts.list).toSet()
    fun pick(random: Random): Int {
        while (true) { val p = random.nextInt(MIN, MAX_EXCLUSIVE); if (p !in EXCLUDED) return p }
    }
}

object GatewayProbe {
    const val TIMEOUT_MS = 400
    const val CONCURRENCY = 64
    val PORTS = listOf(80, 443)

    /** A /24 is a hit when any probed address in it connects or is refused on 80 or 443; hits come back ordered by address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int) -> Unit = { _, _ -> },
                    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)): List<Subnet> {
        val units = targets.byUnit()
        val sem = Semaphore(CONCURRENCY)
        val hits = ConcurrentHashMap.newKeySet<Subnet>()
        val attempts = AtomicInteger()
        val lock = Any()
        var done = 0
        onProgress(0, units.size, 0)
        coroutineScope {
            for ((unit, addresses) in units) launch(dispatcher) {
                for (a in addresses) if (sem.withPermit { answers(a, connect, attempts) }) { hits += unit; break }
                // Counter and callback under one lock: two units finishing together can never report 272 before 271, so the status never steps back.
                synchronized(lock) { done++; onProgress(done, units.size, attempts.get()) }
            }
        }
        return hits.sortedBy { it.start() }
    }

    /** Ports in order, stopping at the first definitive answer: a connect or refusal is yes, unreachable is no, a timeout tries the next. Each connect is one counted attempt. */
    private suspend fun answers(a: Inet4Address, connect: suspend (Inet4Address, Int) -> Probe.Outcome, attempts: AtomicInteger): Boolean {
        for (port in PORTS) {
            attempts.incrementAndGet()
            when (connect(a, port)) {
                is Probe.Outcome.Connected, Probe.Outcome.Refused -> return true
                Probe.Outcome.Unreachable -> return false
                Probe.Outcome.Timeout -> Unit
            }
        }
        return false
    }
}
```

- [ ] **Step 4: Run the test to see it pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.GatewayProbeTest" --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 8 passed; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add gateway probe targets, TEST-NET pre-check rule, random high ports and the bounded probe"
```

---

### Task 14: Extended options and status types, the phantom guard, and a full-result sweep

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/PhantomGuard.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt` (extended types), `engine/discovery/Sweep.kt` (`runAll`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/PhantomGuardTest.kt`, `engine/discovery/SweepTest.kt` (add one test)

**Interfaces:**
- Consumes: `RangeSource` (Task 12), `AnswersForEverything.answered` (Task 13), `Subnet`, `Sweep`, `Liveness`, `BindFailedException` (Task 10).
- Produces (extended spec 5.1, plus two recorded additions):
```kotlin
data class ExtendedOptions(val enabled: Boolean = false, val maxRanges: Int = 4, val gatewayProbe: Boolean = false,
                           val manual: List<Subnet> = emptyList(), val knownIps: Set<String> = emptySet())
data class DiscoveryOptions(/* existing fields */, val extended: ExtendedOptions = ExtendedOptions())
data class RangeProgress(val subnet: String, val source: RangeSource?, val state: RangeState, val probed: Int, val total: Int, val found: Int)   // source null = local
enum class RangeState { PENDING, RUNNING, DONE, CANCELLED, PHANTOM, TRUNCATED }
enum class DiscoveryPhase { LOCAL, GATEWAY_PROBE, EXTENDED, DONE }
enum class ExtendedNote { GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, GATEWAY_PROBE_FOUND_NONE, NO_EXTRA_RANGES, SKIPPED_BIND_FAILED }
data class GatewayProbeProgress(val label: String, val done: Int, val total: Int, val attempts: Int = 0)   // attempts = connects tried so far
data class DiscoveryStatus(/* existing fields */, val phase: DiscoveryPhase = DiscoveryPhase.LOCAL, val ranges: List<RangeProgress> = emptyList(),
                           val truncatedRanges: Int = 0, val notes: List<ExtendedNote> = emptyList(), val gatewayProbe: GatewayProbeProgress? = null)
object PhantomGuard { const val SAMPLE = 64; const val MIN_PROBES = 16; const val ALIVE_RATIO = 0.9; const val CANARY_MIN_UNKNOWN = 4
    enum class Verdict { HOLD, RELEASE, PHANTOM }
    fun decide(probed: Int, alive: Int, finished: Boolean): Verdict
    fun canaryPhantom(outcomes: List<Probe.Outcome>): Boolean }
open fun Sweep.runAll(hosts: Sequence<Inet4Address>): Flow<Liveness>     // every result, alive or dead; run() = runAll().filter { alive }
```
- Additions to extended spec 5.1, recorded: `ExtendedOptions.knownIps` (the canary needs "addresses not already known in Room for that network", and the engine has no Room access; the view model passes the network's stored IPs) and `DiscoveryStatus.gatewayProbe` (the Radar progress string "probing 10.0.0.0/16 for gateways, 112 of 256  9 s" needs the probe's own progress).
- `Sweep.runAll` exists so the sweep guard counts probes and answers from one ordered stream. Counting alive hosts from `run()` against `progress` races: progress can pass 64 before buffered alive results reach the collector, which would release a phantom range.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/PhantomGuardTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import org.junit.Assert.*
import org.junit.Test

class PhantomGuardTest {
    @Test fun `holds until 64 probes, then judges more than 90 percent alive as phantom`() {
        assertEquals(PhantomGuard.Verdict.HOLD, PhantomGuard.decide(probed = 63, alive = 63, finished = false))
        assertEquals(PhantomGuard.Verdict.PHANTOM, PhantomGuard.decide(64, 58, false))     // 90.6 percent
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(64, 57, false))     // 89.1 percent
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(64, 3, false))
    }
    @Test fun `small ranges are judged at the end and need 16 probes to judge at all`() {
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(14, 14, finished = true))   // a /28 that is all alive is believed
        assertEquals(PhantomGuard.Verdict.PHANTOM, PhantomGuard.decide(30, 30, finished = true))
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(30, 2, finished = true))
        assertEquals(PhantomGuard.Verdict.HOLD, PhantomGuard.decide(30, 30, finished = false))
    }
    @Test fun `canary is phantom only when both addresses answer`() {
        assertTrue(PhantomGuard.canaryPhantom(listOf(Probe.Outcome.Refused, Probe.Outcome.Connected(1, null))))
        assertFalse(PhantomGuard.canaryPhantom(listOf(Probe.Outcome.Refused, Probe.Outcome.Timeout)))
        assertFalse(PhantomGuard.canaryPhantom(emptyList()))
    }
    @Test fun `status defaults keep the MVP meaning`() {
        val s = DiscoveryStatus()
        assertEquals(DiscoveryPhase.LOCAL, s.phase); assertTrue(s.ranges.isEmpty()); assertEquals(0, s.truncatedRanges)
        assertTrue(s.notes.isEmpty()); assertNull(s.gatewayProbe)
        assertFalse(DiscoveryOptions().extended.enabled); assertEquals(4, ExtendedOptions().maxRanges)
    }
}
```

Add to `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SweepTest.kt`:
```kotlin
    @Test fun `runAll emits every result including dead hosts`() = runBlocking {
        val lo = InetAddress.getLoopbackAddress() as Inet4Address
        val dead = InetAddress.getByName("192.0.2.1") as Inet4Address
        ServerSocket(0).use { server ->
            val sweep = Sweep(HostProber(ports = listOf(server.localPort), timeoutMs = 300), concurrency = 8)
            val out = sweep.runAll(sequenceOf(lo, dead)).toList()
            assertEquals(setOf(lo to true, dead to false), out.map { it.ip to it.alive }.toSet())
            assertEquals(2 to 2, sweep.progress.value)
        }
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.PhantomGuardTest" --tests "com.northwalessolutions.netprobe.engine.discovery.SweepTest" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'PhantomGuard'`, `'DiscoveryPhase'`, `'ExtendedOptions'`, `'runAll'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Subnet

/** Extended discovery inputs (extended spec 5.1). [knownIps] are the network's stored IPs, used to pick canary addresses (spec 4.7). */
data class ExtendedOptions(
    val enabled: Boolean = false,
    val maxRanges: Int = 4,                  // coerced to 1..16 by CandidateRanges.derive
    val gatewayProbe: Boolean = false,
    val manual: List<Subnet> = emptyList(),  // the current network's list (spec 4.4)
    val knownIps: Set<String> = emptySet(),
)

data class DiscoveryOptions(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true,
    val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000,
    val extended: ExtendedOptions = ExtendedOptions(),
)

enum class RangeState { PENDING, RUNNING, DONE, CANCELLED, PHANTOM, TRUNCATED }
enum class DiscoveryPhase { LOCAL, GATEWAY_PROBE, EXTENDED, DONE }
enum class ExtendedNote { GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, GATEWAY_PROBE_FOUND_NONE, NO_EXTRA_RANGES, SKIPPED_BIND_FAILED }

/** Per-range progress; probed and total are this range's own and are not monotonic across ranges. [source] null is the local range. */
data class RangeProgress(val subnet: String, val source: RangeSource?, val state: RangeState, val probed: Int, val total: Int, val found: Int)
/** [label] is the walked /16 (for example "10.0.0.0/16"); done and total count /24 units; [attempts] counts connects tried, for the device check. */
data class GatewayProbeProgress(val label: String, val done: Int, val total: Int, val attempts: Int = 0)

/** Top-level probed, total and found keep their MVP meaning (local sweep, monotonic total). */
data class DiscoveryStatus(
    val probed: Int = 0, val total: Int = 0, val found: Int = 0, val clampedFrom: Int? = null, val neighbourAvailable: Boolean? = null,
    val phase: DiscoveryPhase = DiscoveryPhase.LOCAL, val ranges: List<RangeProgress> = emptyList(), val truncatedRanges: Int = 0,
    val notes: List<ExtendedNote> = emptyList(), val gatewayProbe: GatewayProbeProgress? = null,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/PhantomGuard.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe

/**
 * "Connect or refused means alive" is safe on-link but not through a router: a REJECT rule or a captive portal answers for every
 * address (extended spec 4.7). Layer 2 is the per-range canary, layer 3 the sweep guard; layer 1 is the TEST-NET pre-check.
 */
object PhantomGuard {
    const val SAMPLE = 64
    const val MIN_PROBES = 16
    const val ALIVE_RATIO = 0.9
    const val CANARY_MIN_UNKNOWN = 4

    enum class Verdict { HOLD, RELEASE, PHANTOM }

    /** Hold hosts until 64 probes (or the end of a smaller range); then more than 90 percent alive is a phantom range. */
    fun decide(probed: Int, alive: Int, finished: Boolean): Verdict {
        if (!finished && probed < SAMPLE) return Verdict.HOLD
        if (probed < MIN_PROBES) return Verdict.RELEASE
        return if (alive > ALIVE_RATIO * probed) Verdict.PHANTOM else Verdict.RELEASE
    }

    /** Both never-seen canary addresses answering on a random high port means the range answers for everything. */
    fun canaryPhantom(outcomes: List<Probe.Outcome>): Boolean = outcomes.isNotEmpty() && outcomes.all(AnswersForEverything::answered)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Sweep.kt` (replace the body of the class; constructor and `progress` unchanged):
```kotlin
open class Sweep(
    private val prober: HostProber,
    private val concurrency: Int = 128,
    private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) },
) {
    private val _progress = MutableStateFlow(0 to 0)
    open val progress: StateFlow<Pair<Int, Int>> get() = _progress

    /** Alive hosts only (the MVP contract, used for the local range). */
    open fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = runAll(hosts).filter { it.alive }

    /** Every probe result, alive or dead, so the sweep guard counts probes and answers from one ordered stream (extended spec 4.7). */
    open fun runAll(hosts: Sequence<Inet4Address>): Flow<Liveness> = channelFlow {
        val list = hosts.toList()
        var probed = 0
        _progress.value = 0 to list.size
        val dispatcher = dispatcherFactory(concurrency)
        val sem = Semaphore(concurrency)
        coroutineScope {
            for (ip in list) {
                sem.acquire()
                launch(dispatcher) {
                    try {
                        send(prober.probe(ip))
                    } catch (e: CancellationException) { throw e
                    } catch (e: BindFailedException) { throw e      // the network cannot be used: end the sweep, do not count a dead host
                    } catch (_: Exception) { /* a single host failing never stops the sweep */
                    } finally {
                        synchronized(this@Sweep) { probed++; _progress.value = probed to list.size }
                        sem.release()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `PhantomGuardTest` 4 and `SweepTest` 2 pass, `DiscoveryEngineTest` and `ProbeBindTest` still pass (their fakes override `run`); `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add extended discovery options and status types, the phantom guard and a full-result sweep"
```

---

### Task 15: The extended phase in DiscoveryEngine: bind check, gateway probe, candidates, guarded per-range sweeps

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt` (replace)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt` (the existing `DiscoveryEngineTest` stays unchanged and must still pass: it is the toggle-off regression guard)

**Interfaces:**
- Consumes: everything the MVP engine consumed, plus `SocketBinder`, `BoundNetwork`, `BindFailedException` (Task 10), `Evidence`, `CandidateRanges`, `CandidateRange`, `RangeSource` (Task 12), `GatewayTargets`, `GatewayProbe`, `AnswersForEverything`, `RandomHighPort` (Task 13), `ExtendedOptions`, `RangeProgress`, `RangeState`, `DiscoveryPhase`, `ExtendedNote`, `GatewayProbeProgress`, `PhantomGuard`, `Sweep.runAll` (Task 14).
- Produces:
```kotlin
class DiscoveryEngine(/* the eleven MVP parameters, unchanged */,
    val gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?) -> List<Subnet> = { _, _, _ -> emptyList() },
    val canaryProbe: suspend (Inet4Address, Int, SocketBinder?) -> Probe.Outcome = { _, _, _ -> Probe.Outcome.Timeout },
    val random: Random = Random.Default,
) {
    fun discover(env: NetworkEnv, options: DiscoveryOptions, binder: SocketBinder? = null): Flow<DiscoveredHost>
    val status: StateFlow<DiscoveryStatus>
    companion object { fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine; const val CANARY_TIMEOUT_MS = 600 }
}
```
- Deviation from extended spec 5.2, recorded: the spec writes `gatewayProbe: suspend (targets, timeoutMs, concurrency) -> List<Subnet>` and `canaryProbe: suspend (ip, port) -> Outcome`. Timeout and concurrency are `GatewayProbe` constants (Task 13); the probe instead receives a progress callback (for the Radar progress line) and both receive the run's binder, because the binder is per run and the engine is a singleton.
- Flow, exactly as extended spec 5.2: gateway emitted first; mDNS, SSDP and the local sweep with enrichment and the neighbour dump inside one `coroutineScope` (unchanged); only after it returns: if the toggle is off, done; bind check on a throwaway socket (null binder or a failing bind gives `SKIPPED_BIND_FAILED`); DNS evidence; optional gateway probe with the TEST-NET pre-check; `CandidateRanges.derive`; then each candidate in order inside `withContext(BoundNetwork(binder))`: canary, sweep with the guard, enrichment; a range is `DONE` only after its enrichment jobs finish. Every unfinished range becomes `CANCELLED` in a `finally`, and `phase` ends `DONE`.
- Cancellation: Stop cancels the collector; the range's `coroutineScope` cancels its sweep and enrichment; `Probe.connect`'s watcher closes blocking sockets. Hosts held by an undecided guard are never emitted.
- Sweep-guard sample: extended spec 4.7 says "the first 64 probed addresses", a fixed address sample. `sweepExtra` indexes the range's address list and counts only results whose index is below `PhantomGuard.SAMPLE` towards the verdict, so a dense real range (live hosts answer in milliseconds, dead routed ones time out) is not flagged by completion order. Every live host is still held until the verdict, sample or not.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.Collections
import kotlin.random.Random

class DiscoveryEngineExtendedTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)

    private object OkBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private object FailingBinder : SocketBinder {
        override fun bind(socket: Socket) = throw SocketException("EPERM: VPN does not allow bypass")
        override fun bind(socket: DatagramSocket) = throw SocketException("EPERM")
        override fun openConnection(url: URL): URLConnection = throw SocketException("EPERM")
    }

    /**
     * Answers in [completionOrder] (default: address order); logs "<first address> bound=<BoundNetwork present>" for every range it sweeps.
     * The real Sweep delivers results in completion order, so a test can put live hosts first the way a busy range does.
     */
    private class FakeSweep(private val alive: (Inet4Address) -> Boolean, private val log: MutableList<String>, private val delayFor: (Inet4Address) -> Long,
                            private val completionOrder: (List<Inet4Address>) -> List<Inet4Address> = { it }) :
        Sweep(HostProber(), 8) {
        private val p = MutableStateFlow(0 to 0)
        override val progress: StateFlow<Pair<Int, Int>> get() = p
        override fun runAll(hosts: Sequence<Inet4Address>): Flow<Liveness> = flow {
            val list = hosts.toList(); p.value = 0 to list.size
            log += "${list.first().hostAddress} bound=${currentCoroutineContext()[BoundNetwork] != null}"
            completionOrder(list).forEachIndexed { i, a ->
                delayFor(a).takeIf { it > 0 }?.let { delay(it) }
                p.value = i + 1 to list.size
                emit(if (alive(a)) Liveness(a, true, setOf(80), 2) else Liveness(a, false, emptySet(), null))
            }
        }
    }

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val netbiosCalls = Collections.synchronizedList(mutableListOf<String>())
    private val gatewayCalls = Collections.synchronizedList(mutableListOf<GatewayTargets>())
    private val canaryCalls = Collections.synchronizedList(mutableListOf<String>())

    private fun engine(alive: (Inet4Address) -> Boolean = { false }, mdns: List<MdnsRecord> = emptyList(), mdnsDelayMs: Long = 0,
                       gatewayHits: List<String> = emptyList(), gatewayThrows: Boolean = false,
                       canary: (Inet4Address, Int) -> Probe.Outcome = { _, _ -> Probe.Outcome.Timeout },
                       delayFor: (Inet4Address) -> Long = { 0 }, onNetbios: suspend (Inet4Address) -> Unit = {},
                       completionOrder: (List<Inet4Address>) -> List<Inet4Address> = { it }): DiscoveryEngine =
        DiscoveryEngine(
            sweepFactory = { _, _ -> FakeSweep(alive, log, delayFor, completionOrder) },
            mdns = { _, _ -> flow { if (mdnsDelayMs > 0) delay(mdnsDelayMs); mdns.forEach { emit(it) } } }, ssdp = { emptyFlow() },
            upnpFetch = { _, _ -> null }, netbios = { a -> netbiosCalls += a.hostAddress; onNetbios(a); null }, snmp = { null },
            httpTitle = { _, _, _ -> null }, reverseDns = { null }, neighbours = { Result.success(emptyList()) }, mdnsTypes = listOf("_http._tcp"),
            deviceInfo = { _, _ -> null },
            gatewayProbe = { t, onProgress, _ -> gatewayCalls += t; if (gatewayThrows) throw IllegalStateException("boom"); onProgress(1, 1, 2); gatewayHits.map(::net) },
            canaryProbe = { a, port, _ -> canaryCalls += "${a.hostAddress}:$port"; canary(a, port) },
            random = Random(1),
        )
    private fun opts(enabled: Boolean = true, manual: List<String> = emptyList(), cap: Int = 4, probe: Boolean = false, known: Set<String> = emptySet()) =
        DiscoveryOptions(extended = ExtendedOptions(enabled, cap, probe, manual.map(::net), known))
    private suspend fun DiscoveryEngine.hosts(o: DiscoveryOptions, b: SocketBinder? = OkBinder, e: NetworkEnv = env) =
        discover(e, o, b).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { x, y -> x.merge(y) } }
    private fun swept() = log.map { it.substringBefore(' ') }
    private fun DiscoveryEngine.states() = status.value.ranges.map { it.state }

    @Test fun `toggle off changes nothing`() = runBlocking {
        val e = engine(alive = { it.hostAddress == "10.0.3.5" })
        val h = e.hosts(opts(enabled = false, manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1"), swept()); assertTrue(gatewayCalls.isEmpty()); assertTrue(canaryCalls.isEmpty())
        assertEquals(setOf("10.0.3.1", "10.0.3.5"), h.keys)
        assertEquals(listOf(RangeState.DONE), e.states()); assertTrue(e.status.value.notes.isEmpty()); assertEquals(254, e.status.value.total)
    }
    @Test fun `manual range only is swept bound, enriched, never a gateway and without a mac`() = runBlocking {
        val e = engine(alive = { it.hostAddress in setOf("10.0.4.7", "10.0.4.9") })
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(listOf("10.0.3.1 bound=false", "10.0.4.1 bound=true"), log.toList())
        val host = h["10.0.4.7"]!!
        assertFalse(host.isGateway); assertNull(host.mac); assertTrue(Source.SWEEP in host.sources)
        assertTrue("10.0.4.7" in netbiosCalls && "10.0.4.9" in netbiosCalls)
        assertEquals(RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 2), e.status.value.ranges[1])
        assertEquals(DiscoveryPhase.DONE, e.status.value.phase)
    }
    @Test fun `an mdns sighting leads to its slash 24 and merges with the sweep result`() = runBlocking {
        val hue = MdnsRecord(ip("10.0.4.20"), "Hue Bridge - 317AA9", "_hue._tcp", 443, emptyMap(), emptyList())
        val h = engine(alive = { it.hostAddress == "10.0.4.20" }, mdns = listOf(hue)).hosts(opts())
        assertEquals(listOf("10.0.3.1", "10.0.4.1"), swept())
        assertEquals(setOf(Source.MDNS, Source.SWEEP), h["10.0.4.20"]!!.sources)
    }
    @Test fun `a dns server outside the range is evidence`() = runBlocking {
        engine().hosts(opts(), e = env.copy(dnsServers = listOf(ip("10.0.9.53"))))
        assertEquals(listOf("10.0.3.1", "10.0.9.1"), swept())
    }
    @Test fun `candidates are derived only after a slow mdns has finished`() = runBlocking {
        engine(mdns = listOf(MdnsRecord(ip("10.0.6.5"), "slow", "_http._tcp", 80, emptyMap(), emptyList())), mdnsDelayMs = 300).hosts(opts())
        assertEquals(listOf("10.0.3.1", "10.0.6.1"), swept())
    }
    @Test fun `gateway probe gets the right targets and its hits come after manual and evidence`() = runBlocking {
        val e = engine(mdns = listOf(MdnsRecord(ip("10.0.6.5"), "x", "_http._tcp", 80, emptyMap(), emptyList())), gatewayHits = listOf("10.0.10.0/24"))
        e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1", "10.0.4.1", "10.0.6.1", "10.0.10.1"), swept())
        assertEquals("10.0.0.0/16", gatewayCalls.single().enclosing.toString()); assertEquals("10.0.3.0/24", gatewayCalls.single().exclude.toString())
        assertEquals(GatewayProbeProgress("10.0.0.0/16", 1, 1, 2), e.status.value.gatewayProbe)
        canaryCalls.filter { it.startsWith("192.0.2.1") || it.startsWith("198.51.100.1") }.let { assertEquals(4, it.size) }   // pre-check: 2 addresses x (80 + high port)
    }
    @Test fun `gateway probe off is never called and an empty probe is noted`() = runBlocking {
        engine(gatewayHits = listOf("10.0.10.0/24")).hosts(opts(manual = listOf("10.0.4.0/24"), probe = false)); assertTrue(gatewayCalls.isEmpty())
        val e = engine(); e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertTrue(ExtendedNote.GATEWAY_PROBE_FOUND_NONE in e.status.value.notes)
    }
    @Test fun `refuse-everything network yields phantom ranges and no extra hosts`() = runBlocking {   // Review Focus 3
        val e = engine(alive = { true }, canary = { _, _ -> Probe.Outcome.Refused }, gatewayHits = listOf("10.0.10.0/24"))
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24"), probe = true))
        assertTrue(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL in e.status.value.notes); assertTrue(gatewayCalls.isEmpty())
        assertEquals(listOf(RangeState.DONE, RangeState.PHANTOM, RangeState.PHANTOM), e.states())
        assertTrue(h.keys.none { it.startsWith("10.0.4.") || it.startsWith("10.0.5.") }); assertEquals(listOf("10.0.3.1"), swept())
    }
    @Test fun `accept-everything range is discarded by the sweep guard without enrichment`() = runBlocking {   // Review Focus 3
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") })   // canary times out: the portal only answers port 80
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(RangeState.PHANTOM, e.status.value.ranges[1].state); assertEquals(0, e.status.value.ranges[1].found)
        assertTrue(h.keys.none { it.startsWith("10.0.4.") }); assertTrue(netbiosCalls.none { it.startsWith("10.0.4.") })
    }
    @Test fun `a busy real range whose live hosts answer first is not mistaken for a phantom`() = runBlocking {   // the guard judges the first 64 ADDRESSES, not the fastest 64 answers
        val busy = (100..159).map { "10.0.4.$it" }.toSet()   // 60 live hosts, none among the first 64 addresses
        val e = engine(alive = { it.hostAddress in busy }, delayFor = { if (it.hostAddress in busy) 0L else 1L },
            completionOrder = { all -> all.filter { it.hostAddress in busy } + all.filter { it.hostAddress !in busy } })   // live hosts finish first, dead ones time out later
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(RangeState.DONE, e.status.value.ranges[1].state)
        assertEquals(60, h.keys.count { it.startsWith("10.0.4.") }); assertEquals(60, e.status.value.ranges[1].found)
    }
    @Test fun `the phantom guard never discards an mdns sighting`() = runBlocking {
        val hue = MdnsRecord(ip("10.0.4.20"), "Hue Bridge", "_hue._tcp", 443, emptyMap(), emptyList())
        val h = engine(alive = { it.hostAddress.startsWith("10.0.4.") }, mdns = listOf(hue)).hosts(opts())
        assertEquals(setOf(Source.MDNS), h["10.0.4.20"]!!.sources)
    }
    @Test fun `a small all-alive manual range is believed`() = runBlocking {
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") })
        val h = e.hosts(opts(manual = listOf("10.0.4.16/28")))
        assertEquals(14, h.keys.count { it.startsWith("10.0.4.") }); assertEquals(RangeState.DONE, e.status.value.ranges[1].state)
        assertEquals(14, e.status.value.ranges[1].total); assertEquals(254, e.status.value.total)   // per-range total; top-level total stays the local one
    }
    @Test fun `cap sweeps exactly cap ranges in order and reports the rest as truncated`() = runBlocking {
        val e = engine()
        e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24", "10.0.7.0/24"), cap = 2))
        assertEquals(listOf("10.0.3.1", "10.0.4.1", "10.0.5.1"), swept())
        assertEquals(2, e.status.value.truncatedRanges)
        assertEquals(listOf(RangeState.DONE, RangeState.DONE, RangeState.DONE, RangeState.TRUNCATED, RangeState.TRUNCATED), e.states())
    }
    @Test fun `bind failure skips the extended phase and keeps the local result`() = runBlocking {   // Review Focus 4
        for (b in listOf<SocketBinder?>(FailingBinder, null)) {
            log.clear()
            val e = engine(alive = { it.hostAddress == "10.0.3.5" })
            val h = e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true), b = b)
            assertEquals(listOf(ExtendedNote.SKIPPED_BIND_FAILED), e.status.value.notes); assertEquals(listOf("10.0.3.1"), swept())
            assertTrue("10.0.3.5" in h.keys); assertTrue(gatewayCalls.isEmpty()); assertTrue(canaryCalls.isEmpty())
        }
    }
    @Test fun `ranges move pending, running, done with done only after enrichment`() = runBlocking {
        lateinit var e: DiscoveryEngine
        val seen = Collections.synchronizedList(mutableListOf<RangeState>())
        e = engine(alive = { it.hostAddress == "10.0.4.7" }, onNetbios = { a -> if (a.hostAddress == "10.0.4.7") { delay(100); seen += e.status.value.ranges[1].state } })
        e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24")))
        assertEquals(listOf(RangeState.RUNNING), seen.toList())   // still running while its enrichment ran
        assertEquals(listOf(RangeState.DONE, RangeState.DONE, RangeState.DONE), e.states())
    }
    @Test fun `stop mid range cancels within a second and drops held hosts`() = runBlocking {   // Review Focus 5
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") }, delayFor = { if (it.hostAddress.startsWith("10.0.4.")) 20L else 0L })
        val got = Collections.synchronizedList(mutableListOf<DiscoveredHost>())
        val job = launch(Dispatchers.Default) { e.discover(env, opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24")), OkBinder).collect { got += it } }
        withTimeout(5_000) { while (e.status.value.ranges.getOrNull(1)?.let { it.state == RangeState.RUNNING && it.probed >= 5 } != true) delay(10) }
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertEquals(listOf(RangeState.DONE, RangeState.CANCELLED, RangeState.CANCELLED), e.states())
        assertTrue(got.none { it.ip.hostAddress.startsWith("10.0.4.") })   // held by the undecided guard, never emitted
        assertEquals(DiscoveryPhase.DONE, e.status.value.phase)
    }
    @Test fun `a throwing gateway probe does not stop later ranges`() = runBlocking {
        val e = engine(gatewayThrows = true)
        e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1", "10.0.4.1"), swept()); assertFalse(ExtendedNote.GATEWAY_PROBE_FOUND_NONE in e.status.value.notes)
    }
    @Test fun `toggle on with nothing to sweep is noted`() = runBlocking {
        val e = engine(); e.hosts(opts())
        assertEquals(listOf(ExtendedNote.NO_EXTRA_RANGES), e.status.value.notes)
    }
    @Test fun `canaries use the lowest and highest addresses not already known`() = runBlocking {
        engine().hosts(opts(manual = listOf("10.0.4.0/24"), known = setOf("10.0.4.1", "10.0.4.254")))
        val probed = canaryCalls.map { it.substringBefore(':') }
        assertEquals(listOf("10.0.4.2", "10.0.4.253"), probed)
        assertTrue(canaryCalls.all { it.substringAfter(':').toInt() in 49152..65535 })
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngineExtendedTest" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `No parameter with name 'gatewayProbe'`, `'canaryProbe'`, `'random'` and `Too many arguments for public final fun discover`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Runs one enrichment step; a failure in a single host's lookup is swallowed (null). Cancellation and bind failures propagate. */
private suspend fun <T> softly(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: BindFailedException) {
    throw e
} catch (_: Exception) {
    null
}

/** Runs one whole strategy; any failure ends that strategy only. Cancellation and bind failures propagate. */
private suspend fun isolated(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BindFailedException) {
        throw e
    } catch (_: Exception) {
        // strategy died; the rest of discovery carries on
    }
}

/** Stops one range's sweep the moment the guard says PHANTOM; caught by that range only. */
private class PhantomStop : Exception()

class DiscoveryEngine(
    val sweepFactory: (HostProber, Int) -> Sweep,
    val mdns: (List<String>, Long) -> Flow<MdnsRecord>,
    val ssdp: () -> Flow<SsdpResponse>,
    val upnpFetch: suspend (String, Inet4Address) -> UpnpDevice?,
    val netbios: suspend (Inet4Address) -> NetBiosInfo?,
    val snmp: suspend (Inet4Address) -> SnmpInfo?,
    val httpTitle: suspend (Inet4Address, Int, Boolean) -> HttpPeek?,
    val reverseDns: suspend (Inet4Address) -> String?,
    val neighbours: () -> Result<List<NeighbourEntry>>,
    val mdnsTypes: List<String>,
    val deviceInfo: suspend (String, Inet4Address) -> Map<String, String>?,
    val gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?) -> List<Subnet> = { _, _, _ -> emptyList() },
    val canaryProbe: suspend (Inet4Address, Int, SocketBinder?) -> Probe.Outcome = { _, _, _ -> Probe.Outcome.Timeout },
    val random: Random = Random.Default,
) {
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status

    private fun updateRange(i: Int, f: (RangeProgress) -> RangeProgress) =
        _status.update { s -> s.copy(ranges = s.ranges.mapIndexed { j, r -> if (j == i) f(r) else r }) }
    private fun addNote(n: ExtendedNote) = _status.update { it.copy(notes = it.notes + n) }

    /** Step 7 of extended spec 5.2: one throwaway socket proves the Wi-Fi network can be bound. */
    private suspend fun bindWorks(b: SocketBinder): Boolean = withContext(Dispatchers.IO) {
        try { Socket().use { b.bind(it) }; true } catch (e: Exception) { false }
    }

    fun discover(env: NetworkEnv, options: DiscoveryOptions, binder: SocketBinder? = null): Flow<DiscoveredHost> = channelFlow {
        val range = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, options.maxPrefix)
        val local = range.subnet
        val found = HashSet<Inet4Address>()
        val evidence = LinkedHashSet<Inet4Address>()
        fun noteEvidence(ip: Inet4Address) { if (!local.contains(ip)) synchronized(evidence) { evidence.add(ip) } }
        _status.value = DiscoveryStatus(total = local.size, clampedFrom = if (range.clamped) range.originalPrefix else null,
            ranges = listOf(RangeProgress(local.toString(), null, RangeState.RUNNING, 0, local.size, 0)))
        suspend fun emit(h: DiscoveredHost) {
            val fresh = synchronized(found) { found.add(h.ip) }
            if (fresh) _status.update { it.copy(found = synchronized(found) { found.size }) }
            send(h.withDerivedMac())
        }

        val enrich = Semaphore(ENRICHMENT_CONCURRENCY)
        suspend fun enrichHost(l: Liveness) = enrich.withPermit {
            var h = DiscoveredHost(l.ip)
            if (options.netbios) softly { netbios(l.ip) }?.let { h = h.copy(netbiosName = it.computerName, netbiosWorkgroup = it.workgroup, mac = it.mac, macSource = it.mac?.let { MacSource.NETBIOS }, sources = h.sources + Source.NETBIOS) }
            if (options.reverseDns) softly { reverseDns(l.ip) }?.let { h = h.copy(reverseDnsName = it) }
            if (options.snmp) softly { snmp(l.ip) }?.let { h = h.copy(snmpSysDescr = it.sysDescr, snmpSysName = it.sysName, snmpSysObjectId = it.sysObjectId, sources = h.sources + Source.SNMP) }
            if (options.httpTitle) {
                val web = WEB_PORTS.firstOrNull { it in l.openPorts }
                if (web != null) softly { httpTitle(l.ip, web, web in TLS_PORTS) }?.let { h = h.copy(httpTitle = it.title, httpServer = it.server, httpAuthRealm = it.authRealm, sources = h.sources + Source.HTTP) }
            }
            if (h != DiscoveredHost(l.ip)) emit(h)
        }

        /** One extra range: canary, guarded sweep, enrichment; DONE only after enrichment (extended spec 4.7 and 5.2). */
        suspend fun sweepExtra(i: Int, c: CandidateRange, canaryPort: Int, b: SocketBinder) {
            updateRange(i) { it.copy(state = RangeState.RUNNING) }
            val known = options.extended.knownIps + synchronized(found) { found.map { it.hostAddress } }
            val fresh = c.subnet.hosts().filter { it != env.interfaceIp && it.hostAddress !in known }.toList()
            if (fresh.size >= PhantomGuard.CANARY_MIN_UNKNOWN) {
                val outcomes = listOf(fresh.first(), fresh.last()).map { softly { canaryProbe(it, canaryPort, b) } ?: Probe.Outcome.Timeout }
                if (PhantomGuard.canaryPhantom(outcomes)) { updateRange(i) { it.copy(state = RangeState.PHANTOM, found = 0) }; return }
            }
            val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
            val released = AtomicInteger()
            val phantom = try {
                coroutineScope {
                    val held = ArrayList<Liveness>()
                    var verdict = PhantomGuard.Verdict.HOLD
                    // The guard judges a fixed sample, the first SAMPLE addresses in address order, never "whichever 64 answered first":
                    // live hosts answer in milliseconds and dead routed ones time out, so completion order would flag any busy real range.
                    val addresses = c.subnet.hosts().filter { it != env.interfaceIp }.toList()
                    val sampleSize = minOf(PhantomGuard.SAMPLE, addresses.size)
                    val index = HashMap<Inet4Address, Int>(addresses.size).also { m -> addresses.forEachIndexed { n, a -> m[a] = n } }
                    var probed = 0; var sampleDone = 0; var sampleAlive = 0
                    suspend fun release(l: Liveness) {
                        emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP)))   // never isGateway (spec 5.3)
                        released.incrementAndGet()
                        launch { enrichHost(l) }
                    }
                    sweep.runAll(addresses.asSequence()).collect { l ->
                        probed++
                        if ((index[l.ip] ?: Int.MAX_VALUE) < sampleSize) { sampleDone++; if (l.alive) sampleAlive++ }
                        if (l.alive) { if (verdict == PhantomGuard.Verdict.RELEASE) release(l) else held += l }   // every live host is held until the verdict, sample or not
                        if (verdict == PhantomGuard.Verdict.HOLD) {
                            verdict = PhantomGuard.decide(sampleDone, sampleAlive, finished = sampleDone == sampleSize)
                            when (verdict) {
                                PhantomGuard.Verdict.RELEASE -> { held.forEach { release(it) }; held.clear() }
                                PhantomGuard.Verdict.PHANTOM -> throw PhantomStop()
                                PhantomGuard.Verdict.HOLD -> Unit
                            }
                        }
                        val p = probed
                        updateRange(i) { it.copy(probed = p) }
                    }
                    if (verdict == PhantomGuard.Verdict.HOLD) {   // a sample that never completed (a probe that threw): judge what there is
                        verdict = PhantomGuard.decide(sampleDone, sampleAlive, finished = true)
                        if (verdict == PhantomGuard.Verdict.RELEASE) { held.forEach { release(it) }; held.clear() }
                    }
                    verdict == PhantomGuard.Verdict.PHANTOM
                }
            } catch (_: PhantomStop) { true }
            updateRange(i) { it.copy(state = if (phantom) RangeState.PHANTOM else RangeState.DONE, found = if (phantom) 0 else released.get()) }
        }

        suspend fun extendedPhase() {
            val ext = options.extended
            val b = binder
            if (b == null || !bindWorks(b)) { addNote(ExtendedNote.SKIPPED_BIND_FAILED); return }
            try {
                Evidence.dnsServers(env, local).forEach { noteEvidence(it) }
                var hits = emptyList<Subnet>()
                if (ext.gatewayProbe) {
                    _status.update { it.copy(phase = DiscoveryPhase.GATEWAY_PROBE) }
                    val testPort = RandomHighPort.pick(random)
                    val pre = AnswersForEverything.TEST_NET.flatMap { a ->
                        listOf(AnswersForEverything.TEST_PORT, testPort).map { p -> softly { canaryProbe(a, p, b) } ?: Probe.Outcome.Timeout }
                    }
                    if (AnswersForEverything.of(pre)) addNote(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL)
                    else {
                        val targets = GatewayTargets.of(env, local)
                        val label = targets.enclosing?.toString() ?: OTHER_RANGES_LABEL
                        var ran = false
                        isolated {
                            hits = gatewayProbe(targets, { d, t, n -> _status.update { it.copy(gatewayProbe = GatewayProbeProgress(label, d, t, n)) } }, b)
                            ran = true
                        }
                        if (ran && hits.isEmpty()) addNote(ExtendedNote.GATEWAY_PROBE_FOUND_NONE)
                    }
                }
                // Only now: every local strategy has joined, so the evidence is complete (spec 4.2). New sightings below add no ranges.
                val cand = CandidateRanges.derive(local, ext.manual, synchronized(evidence) { evidence.toList() }, hits, ext.maxRanges)
                if (cand.ranges.isEmpty()) { addNote(ExtendedNote.NO_EXTRA_RANGES); return }
                _status.update { s -> s.copy(phase = DiscoveryPhase.EXTENDED, truncatedRanges = cand.truncated.size,
                    ranges = s.ranges + cand.ranges.map { RangeProgress(it.subnet.toString(), it.source, RangeState.PENDING, 0, it.subnet.size, 0) } +
                        cand.truncated.map { RangeProgress(it.subnet.toString(), it.source, RangeState.TRUNCATED, 0, it.subnet.size, 0) }) }
                val canaryPort = RandomHighPort.pick(random)
                withContext(BoundNetwork(b)) { cand.ranges.forEachIndexed { k, c -> sweepExtra(1 + k, c, canaryPort, b) } }   // one range at a time
            } catch (e: BindFailedException) {
                addNote(ExtendedNote.SKIPPED_BIND_FAILED)
            }
        }

        try {
            env.gateway?.let { emit(DiscoveredHost(it, isGateway = true, sources = setOf(Source.GATEWAY))) }
            val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
            val statusJob = launch { sweep.progress.collect { (p, t) ->
                _status.update { it.copy(probed = p, total = maxOf(t, it.total)) }
                updateRange(0) { r -> r.copy(probed = p, total = maxOf(t, r.total)) }
            } }

            coroutineScope {
                if (options.mdns) launch { isolated {
                    val infoQueried = HashSet<String>()
                    mdns(mdnsTypes, options.mdnsDurationMs).collect { r ->
                        noteEvidence(r.ip)
                        emit(DiscoveredHost(r.ip, mdnsName = r.instanceName, mdnsServiceTypes = setOf(r.type), mdnsTxt = mapOf(r.type to r.txt),
                            mdnsInstanceNames = setOf(r.instanceName), ipv6LinkLocal = r.ipv6.toSet(), sources = setOf(Source.MDNS)))
                        // _device-info._tcp is not browsable, so ask for its TXT record by instance name (at most a few names per host).
                        val prefix = "${r.ip.hostAddress}|"
                        val ask = synchronized(infoQueried) { infoQueried.count { it.startsWith(prefix) } < MAX_DEVICE_INFO_QUERIES_PER_HOST && infoQueried.add(prefix + r.instanceName) }
                        if (ask) launch { enrich.withPermit {
                            softly { deviceInfo(r.instanceName, r.ip) }?.let { txt ->
                                emit(DiscoveredHost(r.ip, mdnsServiceTypes = setOf(DeviceInfoTxt.SERVICE), mdnsTxt = mapOf(DeviceInfoTxt.SERVICE to txt), sources = setOf(Source.MDNS)))
                            }
                        } }
                    }
                } }
                if (options.ssdp) launch { isolated {
                    val fetched = HashSet<String>()
                    ssdp().collect { r ->
                        noteEvidence(r.from)
                        val usnMac = r.usn?.let { MacExtract.fromText(it) }
                        var h = DiscoveredHost(r.from, upnpServer = r.server, mac = usnMac, macSource = usnMac?.let { MacSource.UPNP_UDN }, sources = setOf(Source.SSDP))
                        val loc = r.location
                        if (loc != null && synchronized(fetched) { fetched.add(loc) }) {
                            softly { upnpFetch(loc, r.from) }?.let { d ->
                                val udnMac = if (h.mac == null) d.udn?.let { MacExtract.fromText(it) } else null
                                h = h.copy(upnpFriendlyName = d.friendlyName, upnpManufacturer = d.manufacturer, upnpModel = d.modelName, upnpDeviceType = d.deviceType,
                                    mac = h.mac ?: udnMac, macSource = h.macSource ?: udnMac?.let { MacSource.UPNP_UDN })
                            }
                        }
                        emit(h)
                    }
                } }
                launch {
                    isolated { sweep.run(local.hosts()).collect { l ->
                        emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP), isGateway = l.ip == env.gateway))
                        launch { enrichHost(l) }
                    } }
                    // Sweep done: read the neighbour table once.
                    val neigh = withContext(Dispatchers.IO) {   // blocking JNI netlink round trip; discover may be collected on Main
                        try { neighbours() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                    }
                    _status.update { it.copy(neighbourAvailable = neigh.isSuccess) }
                    neigh.getOrNull()?.forEach { n ->
                        val ip = runCatching { java.net.InetAddress.getByName(n.ip) as Inet4Address }.getOrNull() ?: return@forEach
                        if (local.contains(ip)) emit(DiscoveredHost(ip, mac = n.mac, macSource = MacSource.NEIGH, sources = setOf(Source.NEIGH)))
                    }
                }
            }
            // The collector sees a conflated StateFlow and may miss the last update, so flush it before cancelling.
            val (probed, total) = sweep.progress.value
            _status.update { it.copy(probed = probed, total = maxOf(total, it.total)) }
            statusJob.cancel()
            val localFound = synchronized(found) { found.count { local.contains(it) } }
            updateRange(0) { it.copy(state = RangeState.DONE, probed = probed, total = maxOf(total, it.total), found = localFound) }

            if (options.extended.enabled) extendedPhase()
        } finally {
            // Stop, a network change or a failure: whatever had not finished is CANCELLED; partial results stay (spec 5.4).
            _status.update { s -> s.copy(phase = DiscoveryPhase.DONE, ranges = s.ranges.map { r ->
                if (r.state == RangeState.RUNNING || r.state == RangeState.PENDING) r.copy(state = RangeState.CANCELLED) else r }) }
        }
    }

    companion object {
        const val CANARY_TIMEOUT_MS = 600
        private const val OTHER_RANGES_LABEL = "other ranges"
        fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine {
            val nsd = AndroidNsdSource(context); val upnp = UpnpFetcher(); val titles = HttpTitleFetcher(); val rdns = ReverseDns()
            return DiscoveryEngine(
                sweepFactory = { prober, c -> Sweep(prober, c) },
                mdns = { types, ms -> MdnsResolveQueue(nsd).run(types, ms) }, ssdp = { SsdpSearch().search() },
                upnpFetch = { loc, host -> upnp.fetch(loc, host) }, netbios = { NetBios.query(it) }, snmp = { Snmp.query(it) },
                httpTitle = { ip, port, tls -> titles.fetch(ip, port, tls) }, reverseDns = { rdns.lookup(it) },
                neighbours = { NeighbourTable.dump() }, mdnsTypes = mdnsTypes,
                deviceInfo = { name, host -> DeviceInfoTxt.query(name, host) },
                gatewayProbe = { targets, onProgress, b ->
                    GatewayProbe.run(targets, connect = { a, port -> Probe.connect(a, port, GatewayProbe.TIMEOUT_MS, binder = b) }, onProgress = onProgress)
                },
                // Probe.connect blocks: never on the collector's (possibly Main) dispatcher.
                canaryProbe = { a, port, b -> withContext(Dispatchers.IO) { Probe.connect(a, port, CANARY_TIMEOUT_MS, binder = b) } },
            )
        }
        private const val MAX_DEVICE_INFO_QUERIES_PER_HOST = 3
        private const val ENRICHMENT_CONCURRENCY = 32
        private val WEB_PORTS = listOf(80, 8080, 443, 8443)
        private val TLS_PORTS = setOf(443, 8443)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngineExtendedTest" --tests "com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngineTest" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `DiscoveryEngineExtendedTest` 19 passed (the 18 cases that were already in the file plus `a busy real range whose live hosts answer first is not mistaken for a phantom`) and every `DiscoveryEngineTest` case still passes (toggle off); `BUILD SUCCESSFUL`. A failure of `stop mid range cancels within a second` means a blocking call outside `Probe.connect` or a missing cancellation check: fix the engine, never widen the 1 s bound.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Run the extended phase: bind check, gateway probe, candidate ranges and guarded per-range sweeps"
```

---

### Task 16: Extended status into the view model: range grouping, progress line, notices and options

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt` (`HostRow`, `HomeUiState.groups`, `RowRules.row`, the `state` combine, `init`, `discover`), `ui/home/RowText.kt` (`HomeGroups.of` keys on `HostRow.subnet`), `ui/home/HomeContent.kt` (list from `state.groups`; the range summary above the list), `ui/home/HomeComponents.kt` (`RangeSummary`), `ui/home/HomeCopy.kt` (extended strings), `ui/home/ProgressLine.kt` (extended phases), `AppContainer.kt` (`currentNetwork`)
- Modify tests: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowRulesTest.kt`, `ProgressLineTest.kt`, `CopyTableTest.kt`, `RowTextTest.kt`, `HomeStatsTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeFakes.kt`, `HomeContentTest.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt`

**Interfaces:**
- Consumes: `RangeRules` (Task 12), `CidrParser`, `ManualRangeStore`, `AppSettings.manual`, `SettingsState.extEnabled/extMax/extGatewayProbe` (Task 11), `ExtendedOptions`, `DiscoveryStatus` extended fields, `RangeState`, `DiscoveryPhase`, `ExtendedNote` (Task 14), `DiscoveryEngine.discover(env, options, binder)` (Task 15), `NetworkEnvMonitor.binderFor` (Task 10), `HostGroup`, `GroupHeaders` (Task 3).
- Produces:
```kotlin
data class HostRow(/* id .. mac unchanged */, val subnet: String = "", val offSubnet: Boolean = false)   // otherSubnet is removed (extended spec 7)
fun RowRules.row(h: HostEntity, env: NetworkEnv?, maxPrefix: Int, openPorts: Int, stale: Boolean,
                 extraRanges: List<Subnet> = emptyList(), manualRanges: List<Subnet> = emptyList()): HostRow   // RowRules.otherSubnet is removed
// HomeUiState gains: val groups: List<HostGroup> = emptyList()   (grouping after the filter, in the view model: extended spec 9 step 4)
//                    val lastRunRanges: List<RangeProgress> = emptyList()   (the last extended run's range list, memory only: extended spec 5.6 and 8.2)
object HomeGroups { fun of(rows: List<HostRow>): List<HostGroup> }        // the Task 3 localSubnet parameter is dropped
object ExtendedNotices { fun of(status: DiscoveryStatus): List<String> }
object LastRun { fun sweptRanges(status: DiscoveryStatus): List<Subnet> }
object RangeSummaryText { fun headline(otherRanges: Int): String; fun lines(ranges: List<RangeProgress>): List<String> }
@Composable fun RangeSummary(ranges: List<RangeProgress>)     // ui/home/HomeComponents.kt: "3 other ranges checked", expandable to one line per range
// AppContainer gains: val currentNetwork: MutableStateFlow<NetworkEntity?>   (set by HomeViewModel; read by Settings in Task 17)
```
- Notice wording is the Radar spec section 8 table, with two Controller rulings. `GATEWAY_PROBE_SKIPPED_ANSWERS_ALL` reads "Gateway probe skipped: every address answered" (the PHANTOM row's form) and is part of the copy-table test. Ranges dropped by the cap are reported once, by the cap row ("2 more ranges found, raise the cap in Settings"); the table's per-range TRUNCATED row ("cut short: scan limit reached") is deleted as unused, because its wording describes a range stopped mid-sweep, which this design never does.
- Per-range done summary (extended spec 8.2, kept as the spec requires): the view model keeps the last extended run's `ranges` in memory (`HomeUiState.lastRunRanges`, cleared at the start of every scan and on a network change, so it is never shown against a different network). `RangeSummary` sits above the device list while the radar is SETTLED: "N other ranges checked", tap to expand to one line per range, "10.0.4.0/24  evidence  swept, 3 found". It is the only place a swept-but-empty range or a PHANTOM, capped or stopped range stays visible after the notice is dismissed. The Radar spec fixes no wording for these lines (it defers the done summary's range list to this data); the strings below are this plan's own and are covered by `CopyTableTest`.
- Network change: "Scanned in N s" and the range list belong to one network, so `ScanFinish.afterNetworkChange` (Task 5) clears the finish time when the resolved network id changes.
- A Stop or a network change still produces the extended notices: they are computed in `discover`'s `finally`, and the engine has already marked the unfinished ranges `CANCELLED` in its own `finally`.

- [ ] **Step 1: Write the failing tests and update the helpers**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtendedNoticesTest {
    private val local = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)
    private fun r(s: String, state: RangeState, found: Int = 0, source: RangeSource = RangeSource.MANUAL) = RangeProgress(s, source, state, 254, 254, found)

    @Test fun `notes map to the radar copy`() {
        assertEquals(listOf("Extended discovery: no extra ranges to sweep"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.NO_EXTRA_RANGES), ranges = listOf(local))))
        assertEquals(listOf("Other ranges skipped: could not bind to Wi-Fi"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.SKIPPED_BIND_FAILED))))
        assertEquals(listOf("Gateway probe found no other ranges"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_PROBE_FOUND_NONE))))
        assertEquals(listOf("Gateway probe skipped: every address answered"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL))))
    }
    @Test fun `range states become notices and found ranges say nothing`() {
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, found = 3), r("10.0.5.0/24", RangeState.PHANTOM), r("10.0.8.0/24", RangeState.DONE)))
        assertEquals(listOf("10.0.5.0/24 skipped: every address answered", "10.0.8.0/24: nothing found"), ExtendedNotices.of(st))
    }
    @Test fun `cancelled range names the range that was running`() {   // Review Focus 5
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, found = 2), r("10.0.6.0/24", RangeState.CANCELLED), r("10.0.7.0/24", RangeState.CANCELLED)))
        assertEquals(listOf("Stopped at 10.0.6.0/24"), ExtendedNotices.of(st))
    }
    @Test fun `cap drops are one notice`() {
        val st = DiscoveryStatus(truncatedRanges = 2, ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 1), r("10.0.5.0/24", RangeState.TRUNCATED), r("10.0.6.0/24", RangeState.TRUNCATED)))
        assertEquals(listOf("2 more ranges found, raise the cap in Settings"), ExtendedNotices.of(st))
        assertEquals(listOf("1 more range found, raise the cap in Settings"), ExtendedNotices.of(st.copy(truncatedRanges = 1)))
    }
    @Test fun `range summary lines carry source, outcome and count`() {
        val ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 3, RangeSource.EVIDENCE), r("10.0.7.0/24", RangeState.DONE), r("10.0.5.0/24", RangeState.PHANTOM, source = RangeSource.GATEWAY_PROBE),
            r("10.0.6.0/24", RangeState.TRUNCATED), r("10.0.8.0/24", RangeState.CANCELLED), r("10.0.9.0/24", RangeState.PENDING))
        assertEquals(listOf("10.0.4.0/24  evidence  swept, 3 found", "10.0.7.0/24  manual  swept, nothing reachable",
            "10.0.5.0/24  gateway probe  skipped, every address answered", "10.0.6.0/24  manual  not swept, scan limit reached",
            "10.0.8.0/24  manual  stopped", "10.0.9.0/24  manual  not swept"), RangeSummaryText.lines(ranges))
        assertEquals("1 other range checked", RangeSummaryText.headline(1)); assertEquals("6 other ranges checked", RangeSummaryText.headline(6))
    }
    @Test fun `last run keeps only swept ranges`() {
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 2), r("10.0.5.0/24", RangeState.PHANTOM), r("10.0.4.16/28", RangeState.DONE)))
        assertEquals(listOf("10.0.4.0/24", "10.0.4.16/28"), LastRun.sweptRanges(st).map { it.toString() })
    }
}
```

Add to `app/src/test/java/com/northwalessolutions/netprobe/ui/home/ProgressLineTest.kt` (imports `com.northwalessolutions.netprobe.engine.discovery.*`):
```kotlin
    private val local = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)
    @Test fun `gateway probe phase`() {
        val st = DiscoveryStatus(probed = 254, total = 254, phase = DiscoveryPhase.GATEWAY_PROBE, gatewayProbe = GatewayProbeProgress("10.0.0.0/16", 112, 256))
        assertEquals("probing 10.0.0.0/16 for gateways, 112 of 256  9 s", ProgressLine.of(st, 9))
        assertEquals("preparing next range  31 s", ProgressLine.of(st.copy(gatewayProbe = null), 31))
    }
    @Test fun `extended range counts extra ranges only and uses the range's own numbers`() {
        val st = DiscoveryStatus(probed = 254, total = 254, phase = DiscoveryPhase.EXTENDED, ranges = listOf(local,
            RangeProgress("10.0.5.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 1),
            RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.RUNNING, 171, 254, 0),
            RangeProgress("10.0.6.0/24", RangeSource.EVIDENCE, RangeState.PENDING, 0, 254, 0),
            RangeProgress("10.0.9.0/24", RangeSource.GATEWAY_PROBE, RangeState.PENDING, 0, 254, 0),
            RangeProgress("10.0.10.0/24", RangeSource.GATEWAY_PROBE, RangeState.TRUNCATED, 0, 254, 0)))
        assertEquals("10.0.4.0/24 (2 of 4), probing 171 of 254", ProgressLine.of(st, 40))
        assertEquals("preparing next range  41 s", ProgressLine.of(st.copy(ranges = st.ranges.map { if (it.state == RangeState.RUNNING) it.copy(state = RangeState.DONE) else it }), 41))
    }
```

In `CopyTableTest.kt`, replace the import `com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus` with `com.northwalessolutions.netprobe.engine.discovery.*`, and append these templates to the `templates` list (before `).map { Regex(it) }`): the extended wording of Radar spec section 8, plus the two copy rulings and the range list recorded above.
```kotlin
        "probing \S+ for gateways, \d+ of \d+  \d+ s", "\S+ \(\d+ of \d+\), probing \d+ of \d+", "preparing next range  \d+ s",
        "\d+ more ranges? found, raise the cap in Settings", "Extended discovery: no extra ranges to sweep", "Gateway probe found no other ranges",
        "Gateway probe skipped: every address answered", "Other ranges skipped: could not bind to Wi-Fi", "\S+ skipped: every address answered",
        "Stopped at \S+", "\S+: nothing found", "\d+ other ranges? checked",
        "\S+  (manual|evidence|gateway probe|local)  (swept, nothing reachable|swept, \d+ found|skipped, every address answered|not swept, scan limit reached|stopped|not swept)",
```
and these tests (they enumerate every phase, range state, note and source, so a reachable state that produces wording outside the table fails here):
```kotlin
    private val localRange = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)

    @Test fun `every extended progress state matches the table`() {
        val probe = GatewayProbeProgress("10.0.0.0/16", 112, 256, 224)
        for (phase in DiscoveryPhase.entries) for (gateway in listOf<GatewayProbeProgress?>(null, probe)) for (state in RangeState.entries) for (elapsed in listOf(0L, 31L)) {
            val st = DiscoveryStatus(probed = 254, total = 254, found = 9, phase = phase, gatewayProbe = gateway, ranges = listOf(localRange,
                RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, state, 171, 254, 0), RangeProgress("10.0.6.0/24", RangeSource.MANUAL, RangeState.PENDING, 0, 254, 0)))
            inTable(ProgressLine.of(st, elapsed))   // includes GATEWAY_PROBE with no progress yet ("preparing next range") and EXTENDED with nothing running
        }
    }
    @Test fun `every notice and range summary state matches the table`() {
        for (note in ExtendedNote.entries) inTable(ExtendedNotices.of(DiscoveryStatus(notes = listOf(note))).single())
        for (state in RangeState.entries) for (found in listOf(0, 2)) for (source in RangeSource.entries) for (dropped in listOf(0, 1, 2)) {
            val st = DiscoveryStatus(truncatedRanges = dropped, ranges = listOf(localRange, RangeProgress("10.0.5.0/24", source, state, 254, 254, found)))
            ExtendedNotices.of(st).forEach { inTable(it) }
            RangeSummaryText.lines(st.ranges).forEach { inTable(it) }
            inTable(RangeSummaryText.headline(st.ranges.size - 1))
        }
        inTable(HomeCopy.rangesChecked(5)); inTable(HomeCopy.capDropped(1)); inTable(HomeCopy.capDropped(2))
    }
```

In `RowRulesTest.kt`: delete the `scanned` field and the three tests `host inside the scanned subnet is not flagged`, `host outside the scanned subnet reports its own slash 24` and `garbage ip is not flagged and never resolved`, and add (import `com.northwalessolutions.netprobe.netenv.CidrParser`, `com.northwalessolutions.netprobe.netenv.NetworkEnv`, `com.northwalessolutions.netprobe.netenv.Transport`):
```kotlin
    private val env3 = NetworkEnv(RowRules.parseIpv4("10.0.3.242")!!, 24, RowRules.parseIpv4("10.0.3.1")!!, emptyList(), null, null, Transport.WIFI)
    @Test fun `host inside the swept range is local`() {
        val r = RowRules.row(host("10.0.3.7"), env3, 22, 0, false); assertEquals("10.0.3.0/24", r.subnet); assertFalse(r.offSubnet)
    }
    @Test fun `host outside the swept range reports its own slash 24`() {
        val r = RowRules.row(host("10.0.4.20"), env3, 22, 0, false); assertEquals("10.0.4.0/24", r.subnet); assertTrue(r.offSubnet)
    }
    @Test fun `a swept extra or manual range groups its hosts`() {
        assertEquals("10.0.4.16/28", RowRules.row(host("10.0.4.20"), env3, 22, 0, false, extraRanges = listOf(CidrParser.subnetOf("10.0.4.16/28")!!)).subnet)
        assertEquals("10.0.4.16/28", RowRules.row(host("10.0.4.20"), env3, 22, 0, false, manualRanges = listOf(CidrParser.subnetOf("10.0.4.16/28")!!)).subnet)
    }
    @Test fun `garbage ip is not flagged and never resolved`() {
        for (bad in listOf("not-an-ip", "10.0.3", "10.0.3.256", "1.2.3.4.5", "", "a.b.c.d")) { assertNull(bad, RowRules.parseIpv4(bad)); assertFalse(RowRules.row(host(bad), env3, 22, 0, false).offSubnet) }
        assertEquals("10.0.3.7", RowRules.parseIpv4("10.0.3.7")!!.hostAddress)
    }
```

Add to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (add the import `com.northwalessolutions.netprobe.engine.discovery.*`):
```kotlin
    private val lastRun = listOf(RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12),
        RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.DONE, 254, 254, 3),
        RangeProgress("10.0.7.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 0))

    @Test fun rangeSummaryExpandsToOneLinePerRange() {   // extended spec 8.2: a swept but empty range is a finding and must not vanish
        show(HomeFakes.settled().copy(lastRunRanges = lastRun))
        rule.onNodeWithText("2 other ranges checked").assertIsDisplayed()
        rule.onNodeWithTag("rangeLines").assertDoesNotExist()
        rule.onNodeWithText("2 other ranges checked").performClick()
        rule.onNodeWithText("10.0.4.0/24  evidence  swept, 3 found").assertIsDisplayed()
        rule.onNodeWithText("10.0.7.0/24  manual  swept, nothing reachable").assertIsDisplayed()
        rule.onNodeWithTag("deviceList").assertIsDisplayed()
    }
    @Test fun rangeSummaryIsAbsentWhileScanningAndWithoutAnExtendedRun() {
        rule.mainClock.autoAdvance = false
        show(HomeFakes.scanning().copy(lastRunRanges = lastRun)); rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("rangeSummary").assertDoesNotExist()
    }
    @Test fun rangeSummaryIsAbsentWithoutAnExtendedRun() {
        show(HomeFakes.settled())
        rule.onNodeWithTag("rangeSummary").assertDoesNotExist()
    }
```

Helper updates for the new `HostRow` shape (each file's own `row` helper only):
- `RowTextTest.kt`: the helper's constructor call becomes `HostRow(id, ip, name, identity, DeviceType.NAS, open, status, gateway, stale, mac, other ?: "10.0.3.0/24", other != null)`, and every `HomeGroups.of(rows, "10.0.3.0/24")` call becomes `HomeGroups.of(rows)` (likewise `HomeGroups.of(listOf(row(ip = "10.0.3.9")))` and `HomeGroups.of(emptyList())`).
- `HomeStatsTest.kt`: `HostRow(id, "10.0.4.$id", "h$id", "x", DeviceType.UNKNOWN, 0, HostStatus.UNMARKED, false, stale, null, other ?: "10.0.3.0/24", other != null)`.
- `HomeFakes.kt` (androidTest): `row` builds `HostRow(id, ip, name, identity, type, open, status, gateway, stale, mac, other ?: "10.0.3.0/24", other != null)`, and `settled` passes `groups = HomeGroups.of(rows)` to `HomeUiState`.

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'ExtendedNotices'`, `'LastRun'`, `'gatewayProbing'`, `No parameter with name 'extraRanges'`, and `Too many arguments` for the twelve-argument `HostRow` calls.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: add inside `object HomeCopy`:
```kotlin
    const val NO_EXTRA_RANGES = "Extended discovery: no extra ranges to sweep"
    const val GATEWAY_FOUND_NONE = "Gateway probe found no other ranges"
    const val BIND_FAILED = "Other ranges skipped: could not bind to Wi-Fi"
    /** Controller ruling: the Radar copy table has no row for GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, so it takes the PHANTOM row's form. */
    const val GATEWAY_ANSWERS_ALL = "Gateway probe skipped: every address answered"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_EVIDENCE = "evidence"
    const val SOURCE_GATEWAY = "gateway probe"
    const val SOURCE_LOCAL = "local"
    const val OUTCOME_PHANTOM = "skipped, every address answered"
    const val OUTCOME_CAPPED = "not swept, scan limit reached"
    const val OUTCOME_STOPPED = "stopped"
    const val OUTCOME_NOT_SWEPT = "not swept"
    fun rangesChecked(n: Int) = if (n == 1) "1 other range checked" else "$n other ranges checked"
    fun outcomeSwept(found: Int) = if (found == 0) "swept, nothing reachable" else "swept, $found found"
    fun rangeLine(subnet: String, source: String, outcome: String) = "$subnet  $source  $outcome"
    fun gatewayProbing(label: String, done: Int, total: Int, elapsedS: Long) = "probing $label for gateways, $done of $total  $elapsedS s"
    fun extendedRange(subnet: String, index: Int, count: Int, probed: Int, total: Int) = "$subnet ($index of $count), probing $probed of $total"
    fun preparing(elapsedS: Long) = "preparing next range  $elapsedS s"
    fun capDropped(n: Int) = if (n == 1) "1 more range found, raise the cap in Settings" else "$n more ranges found, raise the cap in Settings"
    fun phantom(subnet: String) = "$subnet skipped: every address answered"
    fun cancelled(subnet: String) = "Stopped at $subnet"
    fun nothingFound(subnet: String) = "$subnet: nothing found"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ProgressLine.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryPhase
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.discovery.RangeState

/** The radar's progress line (Radar spec 3.3 and section 8). "2 of 4" counts extra ranges only, never the local sweep. */
object ProgressLine {
    fun of(status: DiscoveryStatus?, elapsedS: Long): String {
        if (status == null) return HomeCopy.STARTING
        when (status.phase) {
            DiscoveryPhase.GATEWAY_PROBE ->
                return status.gatewayProbe?.let { HomeCopy.gatewayProbing(it.label, it.done, it.total, elapsedS) } ?: HomeCopy.preparing(elapsedS)
            DiscoveryPhase.EXTENDED -> {
                val extras = status.ranges.drop(1).filter { it.state != RangeState.TRUNCATED }
                val i = extras.indexOfFirst { it.state == RangeState.RUNNING }
                return if (i < 0) HomeCopy.preparing(elapsedS)
                else extras[i].let { HomeCopy.extendedRange(it.subnet, i + 1, extras.size, it.probed, it.total) }
            }
            DiscoveryPhase.LOCAL, DiscoveryPhase.DONE -> Unit
        }
        if (status.total == 0) return HomeCopy.STARTING
        return if (status.probed < status.total) HomeCopy.probing(status.probed, status.total, elapsedS)
        else HomeCopy.identifying(status.found, elapsedS)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.discovery.ExtendedNote
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.engine.discovery.RangeSource
import com.northwalessolutions.netprobe.engine.discovery.RangeState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet

/** End-of-run notices for the notice line (Radar spec 4.4 and section 8). Ranges with hosts need no notice: their group says it. */
object ExtendedNotices {
    fun of(status: DiscoveryStatus): List<String> {
        val out = ArrayList<String>()
        status.notes.forEach { n ->
            out += when (n) {
                ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL -> HomeCopy.GATEWAY_ANSWERS_ALL
                ExtendedNote.GATEWAY_PROBE_FOUND_NONE -> HomeCopy.GATEWAY_FOUND_NONE
                ExtendedNote.NO_EXTRA_RANGES -> HomeCopy.NO_EXTRA_RANGES
                ExtendedNote.SKIPPED_BIND_FAILED -> HomeCopy.BIND_FAILED
            }
        }
        val extras = status.ranges.drop(1)
        extras.forEach { r ->
            when {
                r.state == RangeState.PHANTOM -> out += HomeCopy.phantom(r.subnet)
                r.state == RangeState.DONE && r.found == 0 -> out += HomeCopy.nothingFound(r.subnet)
            }
        }
        extras.firstOrNull { it.state == RangeState.CANCELLED }?.let { out += HomeCopy.cancelled(it.subnet) }   // the one that was running
        if (status.truncatedRanges > 0) out += HomeCopy.capDropped(status.truncatedRanges)
        return out
    }
}

/** The done summary's range list (extended spec 8.2): a headline count and one line per extra range with source, outcome and found count. */
object RangeSummaryText {
    fun headline(otherRanges: Int): String = HomeCopy.rangesChecked(otherRanges)

    /** [ranges] is the status list with the local range first; the local range is not listed. */
    fun lines(ranges: List<RangeProgress>): List<String> = ranges.drop(1).map { r ->
        val source = when (r.source) {
            RangeSource.MANUAL -> HomeCopy.SOURCE_MANUAL
            RangeSource.EVIDENCE -> HomeCopy.SOURCE_EVIDENCE
            RangeSource.GATEWAY_PROBE -> HomeCopy.SOURCE_GATEWAY
            null -> HomeCopy.SOURCE_LOCAL
        }
        val outcome = when (r.state) {
            RangeState.DONE -> HomeCopy.outcomeSwept(r.found)
            RangeState.PHANTOM -> HomeCopy.OUTCOME_PHANTOM
            RangeState.TRUNCATED -> HomeCopy.OUTCOME_CAPPED
            RangeState.CANCELLED -> HomeCopy.OUTCOME_STOPPED
            RangeState.PENDING, RangeState.RUNNING -> HomeCopy.OUTCOME_NOT_SWEPT
        }
        HomeCopy.rangeLine(r.subnet, source, outcome)
    }
}

/** The last run's swept extra ranges, kept in view-model memory only (extended spec 5.6), for display-time grouping. */
object LastRun {
    fun sweptRanges(status: DiscoveryStatus): List<Subnet> =
        status.ranges.drop(1).filter { it.state == RangeState.DONE }.mapNotNull { CidrParser.subnetOf(it.subnet) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RowText.kt`: replace `HomeGroups` with:
```kotlin
object HomeGroups {
    private const val UNSIGNED_MASK = 0xFFFFFFFFL
    private fun networkKey(cidr: String): Long =
        RowRules.parseIpv4(cidr.substringBefore('/'))?.let { SubnetMath.toInt(it).toLong() and UNSIGNED_MASK } ?: Long.MAX_VALUE

    /** Local group first, then other ranges by network address ascending; rows keep their input order (Radar spec 4.2, extended spec 8.1). */
    fun of(rows: List<HostRow>): List<HostGroup> {
        val local = rows.filter { !it.offSubnet }
        val others = rows.filter { it.offSubnet }.groupBy { it.subnet }.entries.sortedBy { networkKey(it.key) }
        return buildList {
            if (local.isNotEmpty()) add(HostGroup(local.first().subnet, true, local))
            others.forEach { (subnet, rs) -> add(HostGroup(subnet, false, rs)) }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`: replace the Scaffold content lambda (`{ pad -> Box(...) { when { ... } } }`) with the following; the list now comes from `state.groups` (`localSubnet` stays for `heroSubnet`), and the range summary sits above it while the radar is SETTLED:
```kotlin
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (mode == RadarMode.SETTLED) RangeSummary(state.lastRunRanges)   // nothing when the last run had no extra ranges
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mode == RadarMode.HERO -> HeroPlaceholder()
                    state.rows.isEmpty() -> EmptyState(emptyText(state))
                    else -> DeviceList(state.groups, state, actions, ownModel)
                }
            }
        }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt`: add the imports `androidx.compose.foundation.verticalScroll`, `androidx.compose.material.icons.filled.KeyboardArrowDown`, `androidx.compose.material.icons.filled.KeyboardArrowUp`, `androidx.compose.runtime.saveable.rememberSaveable`, `com.northwalessolutions.netprobe.engine.discovery.RangeProgress`, and append:
```kotlin
private val RANGE_LIST_MAX = 160.dp

/** The last extended run's ranges (extended spec 8.2): "3 other ranges checked", tap for one line per range. Scrolls inside its own cap, so a long list never pushes the devices away. */
@Composable
fun RangeSummary(ranges: List<RangeProgress>) {
    val others = ranges.size - 1
    if (others < 1) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("rangeSummary")) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClickLabel = if (open) "Hide ranges" else "Show ranges", role = Role.Button) { open = !open }
            .padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Text(RangeSummaryText.headline(others), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textMuted)
        }
        if (open) Column(Modifier.heightIn(max = RANGE_LIST_MAX).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).testTag("rangeLines")) {
            RangeSummaryText.lines(ranges).forEach { Text(it, style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add (import `kotlinx.coroutines.flow.MutableStateFlow`):
```kotlin
    /** The network row Home resolved for the current env; Settings edits that network's manual ranges (extended spec 8.3 item 4). */
    val currentNetwork = MutableStateFlow<NetworkEntity?>(null)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:

Replace `HostRow` and `HomeUiState`:
```kotlin
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?,
                   val subnet: String = "", val offSubnet: Boolean = false)
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false,
                       val lastScanAt: Long? = null, val totalHosts: Int = 0, val otherSubnetHosts: Int = 0, val scanStartedAt: Long? = null, val scanFinishedAt: Long? = null,
                       val groups: List<HostGroup> = emptyList(), val lastRunRanges: List<RangeProgress> = emptyList())
```

In `RowRules`, delete `OTHER_SUBNET_PREFIX` and `otherSubnet`, and replace `row`:
```kotlin
    fun row(h: HostEntity, env: NetworkEnv?, maxPrefix: Int, openPorts: Int, stale: Boolean,
            extraRanges: List<Subnet> = emptyList(), manualRanges: List<Subnet> = emptyList()): HostRow {
        val own = isOwn(h.ip, env)
        val name = if (own && h.customName.isNullOrBlank()) THIS_PHONE else DisplayName.of(h)
        val scanned = env?.let { listOf(SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, maxPrefix).subnet) + extraRanges }.orEmpty()
        val (subnet, off) = RangeRules.groupFor(h.ip, scanned, manualRanges)
        return HostRow(h.id, h.ip, name, if (own) OWN_LINE else IdentityLine.of(h), if (own) DeviceType.PHONE else h.deviceType, openPorts, h.status, h.isGateway, stale, h.mac, subnet, off)
    }
```

In `HomeViewModel`, add the field after `finishedAt`:
```kotlin
    /** The last extended run's swept ranges; memory only, replaced at the next extended run (extended spec 5.6). */
    private val lastRunRanges = MutableStateFlow<List<Subnet>>(emptyList())
    /** The same run's full range list for the done summary (extended spec 8.2); cleared at every scan start and on a network change. */
    private val lastRunProgress = MutableStateFlow<List<RangeProgress>>(emptyList())
```

Replace the `state` property:
```kotlin
    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts,
        finishedAt, lastRunRanges, c.settings.manual.revision, lastRunProgress) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        @Suppress("UNCHECKED_CAST")
        val extra = arr[11] as List<Subnet>
        @Suppress("UNCHECKED_CAST")
        val lastRun = arr[13] as List<RangeProgress>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as ScanController.State; val msg = arr[8] as String?
        val done = arr[10] as Long?
        val maxPrefix = c.settings.state.value.maxPrefix
        val manual = c.settings.manual.forNetwork(networkId.value)
        val all = hs.map { h -> RowRules.row(h, e, maxPrefix, pc[h.id] ?: 0, stale = run > 0 && h.lastDiscoveryRun < run, extraRanges = extra, manualRanges = manual) }
        val stats = HomeStats.of(hs, all)
        val shown = HomeFilter.apply(all, f, q)
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, shown, f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning,
            lastScanAt = stats.lastScanAt, totalHosts = stats.totalHosts, otherSubnetHosts = stats.otherSubnetHosts,
            scanStartedAt = run.takeIf { it > 0 }, scanFinishedAt = done, groups = HomeGroups.of(shown),
            lastRunRanges = lastRun)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())
```

In `init`, replace the `env.collectLatest` collector:
```kotlin
        viewModelScope.launch {
            env.collectLatest { e ->
                val net = try { e?.let { c.networks.ensure(it, GatewayMac.lookup(it.gateway)) } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
                // "Scanned in N s" and the range list describe one network: a different network id clears both (latestRun can stay, the summary needs the finish time too).
                finishedAt.value = ScanFinish.afterNetworkChange(networkId.value, net?.id, finishedAt.value)
                if (net?.id != networkId.value) { lastRunRanges.value = emptyList(); lastRunProgress.value = emptyList() }
                networkId.value = net?.id; c.currentNetwork.value = net
            }
        }
```

Replace `discover`:
```kotlin
    private suspend fun discover(e: NetworkEnv) {
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)); networkId.value = net.id; c.currentNetwork.value = net
        extraMessage.value = null   // a notice stays until dismissed or the next scan starts (Radar spec 4.4)
        val runId = System.currentTimeMillis(); latestRun.value = runId
        val s = c.settings.state.value
        val ext = if (!s.extEnabled) ExtendedOptions() else ExtendedOptions(enabled = true, maxRanges = s.extMax, gatewayProbe = s.extGatewayProbe,
            manual = c.settings.manual.get(net.id), knownIps = c.hosts.hostsForNetwork(net.id).first().map { it.ip }.toSet())
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, s.maxPrefix, extended = ext)
        val binder = if (ext.enabled) c.networkEnvMonitor.binderFor(e) else null   // null skips the extended phase with its notice
        if (ext.enabled) lastRunRanges.value = emptyList()
        lastRunProgress.value = emptyList()   // the range list always describes the latest run only
        val openPorts = HashMap<String, MutableSet<Int>>()   // ports seen open per ip during this run: later emissions for the host carry none
        c.locks.acquire()
        try {
            c.discoveryEngine.discover(e, opts, binder).collect { h ->
                val id = c.hosts.upsertDiscovered(net.id, h, runId)
                val merged = c.hosts.byIdOnce(id) ?: return@collect
                val ports = openPorts.getOrPut(h.ip.hostAddress) { HashSet() }.also { it += h.probedOpenPorts }.toSet()
                // Stored port findings are part of the evidence, so a rediscovery never downgrades an identity a port scan established.
                val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(merged), c.ports.servicesFor(id), ports, emptyMap(), merged.mac?.let(c.ouiLookup))
                c.hosts.setIdentity(id, c.deviceIdentifier.identify(ev))
            }
        } finally {
            c.locks.release()
            // Also on Stop or a network change: the engine has already marked unfinished ranges CANCELLED.
            val st = c.discoveryEngine.status.value
            if (ext.enabled) { lastRunRanges.value = LastRun.sweptRanges(st); lastRunProgress.value = st.ranges }
            st.gatewayProbe?.let { Log.i(EXT_LOG_TAG, "gateway probe: ${it.done} of ${it.total} units, ${it.attempts} connect attempts") }   // read by Task 20 check 3
            val notices = listOfNotNull(st.clampedFrom?.let { HomeCopy.largeNetwork(it) }) + if (ext.enabled) ExtendedNotices.of(st) else emptyList()
            if (notices.isNotEmpty()) extraMessage.value = notices.joinToString("\n")
        }
    }
```
Add imports `android.util.Log`, `com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions` and `com.northwalessolutions.netprobe.engine.discovery.RangeProgress`, and the top-level constant `private const val EXT_LOG_TAG = "NetProbeExt"`. `Subnet`, `SubnetMath` and `kotlinx.coroutines.flow.*` are already imported.

- [ ] **Step 4: Run the tests to see them pass, then the full suite, the Compose classes and the build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.home.HomeContentTest --console=plain 2>&1 | tail -8
```
Expected: `ExtendedNoticesTest` 6 and the updated `ProgressLineTest`, `CopyTableTest`, `RowRulesTest`, `RowTextTest`, `HomeStatsTest` pass; `BUILD SUCCESSFUL`; `HomeContentTest` 19 passed (the 16 from Task 6, `otherSubnetGetsItsOwnStickyHeader` now running on `state.groups`, plus the 3 range-summary cases).

- [ ] **Step 5: Install and capture**

With extended discovery still off (its switch arrives in Task 17), scan once and capture the grouped list:
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s 28221FDH300AP9 shell am start -n com.northwalessolutions.netprobe/.MainActivity
```
Tap Scan, wait for the settle, scroll to the Hue Bridge, then `"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t16-home-grouped-toggle-off.png`. Expected: "Other subnet 10.0.4.0/24" header with the Hue Bridge under it, no sweep of that range (extended spec 12.4 check 1).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Group Home by range and show extended progress and end-of-run notices"
```

---

### Task 17: Settings section "Extended discovery"

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSection.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (insert the section between "Large networks" and "Permissions"; the scroll column keeps the `.imePadding()` Task 8 gave it, which is what lets the Add range field and its inline messages sit above the keyboard now that Task 1 made the window edge-to-edge)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/settings/ExtendedCopyTest.kt`, `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSectionTest.kt`

**Interfaces:**
- Consumes: `Section`, `SwitchRow`, `npSwitchColors` (Task 8); `npFieldColors` (Task 4); `CidrParser`, `ManualRangeStore`, `AppSettings.EXT_MAX_MIN/EXT_MAX_MAX`, `SettingsState.extEnabled/extMax/extGatewayProbe` (Task 11); `AppContainer.currentNetwork` (Task 16); `NetworkEnvMonitor.current()`, `SubnetMath.sweepRange`; tokens.
- Produces:
```kotlin
object ExtendedCopy { const val TITLE: String; SUPPORT; MAX_LABEL; PROBE_LABEL; PROBE_SUPPORT; NO_NETWORK; BEYOND_CAP; ADD
    const val SECONDS_PER_RANGE = 25; fun capLine(n: Int): String; fun manualTitle(networkName: String): String }
object ManualCap { fun beyondCap(entries: List<Subnet>, cap: Int): List<Boolean> }
@Composable fun ExtendedSection(state: SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, networkName: String?,
                                manual: List<Subnet>, local: Subnet?, onManualChange: (List<Subnet>) -> Unit)
```
- Wording is extended spec 8.3 verbatim. Rows under the master switch are drawn at alpha 0.5 and disabled while it is off, never hidden. No confirmation dialog anywhere.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/settings/ExtendedCopyTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtendedCopyTest {
    @Test fun `live cap line uses 25 s per range`() {
        assertEquals("At most 1 extra /24 network (about 1 minute)", ExtendedCopy.capLine(1))
        assertEquals("At most 4 extra /24 networks (about 2 minutes)", ExtendedCopy.capLine(4))
        assertEquals("At most 16 extra /24 networks (about 7 minutes)", ExtendedCopy.capLine(16))
    }
    @Test fun `entries beyond the cap are marked by their slash 24 units`() {
        val list = listOf("10.0.4.0/24", "10.0.8.0/23", "10.0.20.0/24").map { CidrParser.subnetOf(it)!! }
        assertEquals(listOf(false, false, true), ManualCap.beyondCap(list, 3))
        assertEquals(listOf(false, true, true), ManualCap.beyondCap(list, 2))
        assertEquals(listOf(false, false, false), ManualCap.beyondCap(list, 16))
    }
    @Test fun `title names the network`() { assertEquals("Manual ranges for Home", ExtendedCopy.manualTitle("Home")) }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSectionTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.ManualRangeStore
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtendedSectionTest {
    @get:Rule val rule = createComposeRule()
    private val prefs = HashMap<String, String>()
    private val store = ManualRangeStore({ prefs[it] }, { k, v -> if (v == null) prefs.remove(k) else prefs[k] = v })
    private val local = CidrParser.subnetOf("10.0.3.0/24")!!

    private fun show(enabled: Boolean = true, networkId: Long? = 1, name: String? = "Home", cap: Int = 4) = rule.setContent {
        var s by remember { mutableStateOf(SettingsState(extEnabled = enabled, extMax = cap)) }
        val rev by store.revision.collectAsState()
        val manual = remember(rev) { store.forNetwork(networkId) }
        NetProbeTheme { Column {
            ExtendedSection(s, { f -> s = f(s) }, name, manual, local) { list -> networkId?.let { store.set(it, list) } }
        } }
    }

    @Test fun rejectsABadCidrInlineAndKeepsAddDisabled() {
        show()
        rule.onNodeWithTag("addRange").performTextInput("8.8.8.0/24")
        rule.onNodeWithText("Only private ranges are allowed").assertIsDisplayed()
        rule.onNodeWithTag("addRangeButton").assertIsNotEnabled()
    }
    @Test fun persistsAGoodRangeForTheCurrentNetworkOnly() {
        store.set(2, listOf(CidrParser.subnetOf("172.16.9.0/24")!!))
        show(networkId = 1)
        rule.onNodeWithTag("addRange").performTextInput("10.0.4.20/24")
        rule.onNodeWithText("Normalised to 10.0.4.0/24").assertIsDisplayed()
        rule.onNodeWithTag("addRangeButton").performClick()
        assertEquals(listOf(CidrParser.subnetOf("10.0.4.0/24")!!), store.get(1))
        assertEquals(listOf(CidrParser.subnetOf("172.16.9.0/24")!!), store.get(2))
        rule.onNode(hasTestTag("manualRange") and hasText("10.0.4.0/24")).assertIsDisplayed()   // the list row; the field's placeholder shows the same text
        rule.onNodeWithContentDescription("Remove 10.0.4.0/24").performClick()
        assertTrue(store.get(1).isEmpty())
    }
    @Test fun ownNetworkIsRejected() {
        show(); rule.onNodeWithTag("addRange").performTextInput("10.0.3.16/28")
        rule.onNodeWithText("This is your own network").assertIsDisplayed()
    }
    @Test fun noNetworkDisablesTheEditor() {
        show(networkId = null, name = null)
        rule.onNodeWithText("Connect to a network to edit its ranges").assertIsDisplayed()
        rule.onNodeWithTag("addRange").assertIsNotEnabled()
    }
    @Test fun masterSwitchOffDisablesButShowsTheRows() {
        show(enabled = false)
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("At most 4 extra /24 networks (about 2 minutes)").assertIsDisplayed()
        rule.onNodeWithTag("addRange").assertIsNotEnabled()
        rule.onNode(hasText("Extended discovery", substring = true) and hasClickAction()).assertIsEnabled().performClick()
        rule.onNode(hasText("Probe for gateways", substring = true) and hasClickAction()).assertIsEnabled()
    }
    @Test fun stepperChangesTheCapAndMarksEntriesBeyondIt() {
        store.set(1, listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24").map { CidrParser.subnetOf(it)!! })
        show(cap = 2)
        rule.onAllNodesWithText("Beyond cap, will be skipped").assertCountEquals(1)
        rule.onNodeWithContentDescription("More ranges").performClick()
        rule.onNodeWithText("At most 3 extra /24 networks (about 2 minutes)").assertIsDisplayed()
        rule.onAllNodesWithText("Beyond cap, will be skipped").assertCountEquals(0)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.settings.ExtendedCopyTest" --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.settings.ExtendedSectionTest --console=plain 2>&1 | tail -8
```
Expected: `Unresolved reference 'ExtendedCopy'` and `'ManualCap'` (unit), `Unresolved reference 'ExtendedSection'` (androidTest).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSection.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlin.math.ceil

/** Settings copy owned by the extended-discovery spec 8.3. */
object ExtendedCopy {
    const val TITLE = "Extended discovery"
    const val SUPPORT = "Sweep other ranges your phone can reach."
    const val MAX_LABEL = "Maximum extra ranges"
    const val PROBE_LABEL = "Probe for gateways"
    const val PROBE_SUPPORT = "Checks common gateway addresses on every /24 in your /16 (about 1,600 connection attempts, about 13 s). Noisy on networks you do not own."
    const val NO_NETWORK = "Connect to a network to edit its ranges"
    const val BEYOND_CAP = "Beyond cap, will be skipped"
    const val ADD = "Add range"
    const val SECONDS_PER_RANGE = 25
    private const val SECONDS_PER_MINUTE = 60.0
    fun capLine(n: Int): String {
        val minutes = ceil(n * SECONDS_PER_RANGE / SECONDS_PER_MINUTE).toInt()
        val nets = if (n == 1) "1 extra /24 network" else "$n extra /24 networks"
        return "At most $nets (about ${if (minutes == 1) "1 minute" else "$minutes minutes"})"
    }
    fun manualTitle(networkName: String) = "Manual ranges for $networkName"
}

object ManualCap {
    /** An entry is beyond the cap when its /24 units, counted in list order, run past [cap] (manual entries come first under the cap). */
    fun beyondCap(entries: List<Subnet>, cap: Int): List<Boolean> {
        var used = 0
        return entries.map { used += CidrParser.units(it).size; used > cap }
    }
}

@Composable
fun ExtendedSection(state: SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, networkName: String?,
                    manual: List<Subnet>, local: Subnet?, onManualChange: (List<Subnet>) -> Unit) {
    val on = state.extEnabled
    SwitchRow(ExtendedCopy.TITLE, state.extEnabled, supporting = ExtendedCopy.SUPPORT) { v -> update { it.copy(extEnabled = v) } }
    Stepper(ExtendedCopy.MAX_LABEL, state.extMax, AppSettings.EXT_MAX_MIN, AppSettings.EXT_MAX_MAX, ExtendedCopy.capLine(state.extMax), on) { v -> update { it.copy(extMax = v) } }
    SwitchRow(ExtendedCopy.PROBE_LABEL, state.extGatewayProbe, supporting = ExtendedCopy.PROBE_SUPPORT, enabled = on) { v -> update { it.copy(extGatewayProbe = v) } }
    ManualEditor(networkName, manual, local, state.extMax, enabled = on && networkName != null, onManualChange)
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, line: String, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else DISABLED_ALPHA), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        StepButton(Icons.Default.Remove, "Fewer ranges", enabled && value > min) { onChange(value - 1) }
        Text("$value", style = NetProbeText.data, color = NetProbeColors.signal, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
        StepButton(Icons.Default.Add, "More ranges", enabled && value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description, tint = if (enabled) NetProbeColors.signal else NetProbeColors.border) }
}

@Composable
private fun ManualEditor(networkName: String?, manual: List<Subnet>, local: Subnet?, cap: Int, enabled: Boolean, onChange: (List<Subnet>) -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Text(networkName?.let { ExtendedCopy.manualTitle(it) } ?: ExtendedCopy.NO_NETWORK, style = NetProbeText.secondary, color = NetProbeColors.text,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        val beyond = ManualCap.beyondCap(manual, cap)
        manual.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                Text(s.toString(), style = NetProbeText.data, color = NetProbeColors.text, modifier = Modifier.weight(1f).testTag("manualRange"))
                if (beyond[i]) Text(ExtendedCopy.BEYOND_CAP, style = NetProbeText.caption, color = NetProbeColors.mark, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = { onChange(manual - s) }, enabled = enabled) { Icon(Icons.Default.Close, "Remove $s", tint = NetProbeColors.textMuted) }
            }
        }
        var text by rememberSaveable(networkName) { mutableStateOf("") }
        var notes by remember { mutableStateOf(emptyList<String>()) }
        val result = if (text.isBlank()) null else CidrParser.parse(text, local)
        val error = result?.error
        val helper = (result?.entries?.mapNotNull { it.note }.orEmpty() + notes).joinToString("\n").ifEmpty { null }
        OutlinedTextField(text, { text = it; notes = emptyList() }, Modifier.fillMaxWidth().testTag("addRange"), enabled = enabled,
            label = { Text(ExtendedCopy.ADD) }, placeholder = { Text("10.0.4.0/24") }, isError = error != null, textStyle = NetProbeText.data,
            supportingText = when {
                error != null -> { { Text(error, color = NetProbeColors.error) } }
                helper != null -> { { Text(helper, color = NetProbeColors.textMuted) } }
                else -> null
            },
            colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        TextButton(onClick = {
            val (merged, dupNotes) = CidrParser.merge(manual, result?.subnets.orEmpty())
            onChange(merged); notes = dupNotes; text = ""
        }, enabled = enabled && result != null && error == null && result.entries.isNotEmpty(), modifier = Modifier.heightIn(min = Dimens.touch).testTag("addRangeButton")) {
            Text(ExtendedCopy.ADD, style = NetProbeText.action, color = if (enabled) NetProbeColors.signal else NetProbeColors.border)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt`: between the "Large networks" block and `Section("Permissions")` insert (imports `com.northwalessolutions.netprobe.netenv.SubnetMath`, `androidx.compose.runtime.getValue`):
```kotlin
            Section("Extended discovery")
            val network by container.currentNetwork.collectAsStateWithLifecycle()
            val revision by container.settings.manual.revision.collectAsStateWithLifecycle()
            val manual = remember(network?.id, revision) { container.settings.manual.forNetwork(network?.id) }
            val local = remember(s.maxPrefix) { container.networkEnvMonitor.current()?.let { SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, s.maxPrefix).subnet } }
            ExtendedSection(s, update, networkName = network?.displayName, manual = manual, local = local) { list ->
                network?.id?.let { container.settings.manual.set(it, list) }
            }
```

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.settings.ExtendedCopyTest" --console=plain 2>&1 | tail -8
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.settings.ExtendedSectionTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 3 and 6 passed; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Install and capture**

Open Settings and scroll to the new section:
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
```
Capture `t17-settings-extended-off.png` (rows dimmed), switch it on and capture `t17-settings-extended-on.png`, type `8.8.8.0/24` and capture `t17-settings-bad-cidr.png`, then replace it with `10.0.4.20/24`, capture `t17-settings-normalised.png`, tap Add range and capture `t17-settings-manual-list.png`. Finally tap the Add range field so the keyboard opens, type `8.8.8.0/24`, and capture `t17-settings-keyboard.png`: the field, its label and the inline message "Only private ranges are allowed" must all be visible above the keyboard (this proves the `imePadding()` fix for the edge-to-edge window; if any of them is hidden, stop and tell the Director). Each with `"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/<name>.png`. Leave the list empty again afterwards (Task 20 adds ranges deliberately).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add the Extended discovery settings section with the per-network manual range editor"
```

---

### Task 18: Neighbour-table rules: on-link MACs only, proxy-ARP filter, evidence from the true subnet

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/NeighbourRules.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt` (the neighbour block and `sweepExtra`'s `release`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/NeighbourRulesTest.kt`; `DiscoveryEngineExtendedTest.kt` (one helper parameter and two tests)

**Interfaces:**
- Consumes: `NeighbourEntry` (`engine/neigh/NeighbourParser.kt`), `Ipv4`, `Subnet`, `SubnetMath`, the Task 15 engine (`noteEvidence`, `emit`, `sweepExtra`).
- Produces:
```kotlin
object NeighbourRules {
    const val PROXY_ARP_MIN_IPS = 3
    data class Outcome(val macs: Map<String, String>, val evidence: List<Inet4Address>, val onLinkIps: List<Inet4Address>)
    fun apply(entries: List<NeighbourEntry>, trueSubnet: Subnet, swept: Subnet, gateway: Inet4Address?): Outcome
}
```
- Rules (extended spec 6.2 and 4.2): only entries inside the interface's true subnet count (`SubnetMath.of(env.interfaceIp, env.prefixLength)`), which drops hotspot and Wi-Fi Direct interfaces; the gateway's MAC is never given to any other IP; a MAC on three or more IPs is proxy ARP and contributes neither MACs nor evidence, but the IPs themselves stay in [Outcome.onLinkIps]: a host known only from the neighbour table keeps its NEIGH row and loses only the MAC claim (extended spec 6.2 says "no MAC assigned", not "no host"; a Wi-Fi repeater that NATs several clients behind one MAC must not make those phones vanish); entries outside the swept range are evidence, never host rows. Every on-link entry inside the swept range still creates a NEIGH row as in the MVP. On-link hosts later swept in an extra range (the unswept part of a clamped /16) take their MAC from the same dump.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/NeighbourRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighbourRulesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun n(ip: String, mac: String) = NeighbourEntry(ip, mac, "REACHABLE")
    private val gw = Ipv4.parse("10.0.3.1")
    // Phone on 10.0.0.0/16 with maxPrefix 22: the true subnet is the /16, the swept range the /24 (extended spec 11.2).
    private val trueSubnet = SubnetMath.of(Ipv4.parse("10.0.3.242")!!, 16)
    private val swept = net("10.0.3.0/24")

    @Test fun `an entry outside the true subnet is neither evidence nor a mac`() {
        val r = NeighbourRules.apply(listOf(n("192.168.49.5", "02:00:00:00:00:49")), trueSubnet, swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs); assertEquals(emptyList<Any>(), r.evidence); assertEquals(emptyList<Any>(), r.onLinkIps)
    }
    @Test fun `an entry inside the true subnet and outside the swept range is evidence and keeps its mac`() {
        val r = NeighbourRules.apply(listOf(n("10.0.7.9", "de:ad:be:ef:00:07")), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.7.9" to "de:ad:be:ef:00:07"), r.macs); assertEquals(listOf("10.0.7.9"), r.evidence.map { it.hostAddress })
    }
    @Test fun `the gateway's mac is never assigned to another ip`() {
        val r = NeighbourRules.apply(listOf(n("10.0.3.1", "aa:bb:cc:00:00:01"), n("10.0.3.50", "aa:bb:cc:00:00:01")), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.3.1" to "aa:bb:cc:00:00:01"), r.macs)
        assertEquals(listOf("10.0.3.1", "10.0.3.50"), r.onLinkIps.map { it.hostAddress })   // 10.0.3.50 keeps its row, only the MAC claim is refused
    }
    @Test fun `a mac on more than two ips is proxy arp and ignored`() {
        val shared = listOf(n("10.0.8.1", "aa:aa:aa:aa:aa:aa"), n("10.0.8.2", "aa:aa:aa:aa:aa:aa"), n("10.0.8.3", "aa:aa:aa:aa:aa:aa"))
        val r = NeighbourRules.apply(shared + n("10.0.3.9", "bb:bb:bb:bb:bb:09"), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.3.9" to "bb:bb:bb:bb:bb:09"), r.macs); assertEquals(emptyList<Any>(), r.evidence)
        val two = NeighbourRules.apply(shared.take(2), trueSubnet, swept, gw)
        assertEquals(2, two.macs.size)   // two IPs on one MAC is allowed (a dual-homed host)
    }
    @Test fun `a proxy arp ip keeps its row but loses the mac claim`() {
        val shared = listOf(n("10.0.3.20", "aa:aa:aa:aa:aa:aa"), n("10.0.3.21", "aa:aa:aa:aa:aa:aa"), n("10.0.3.22", "aa:aa:aa:aa:aa:aa"))
        val r = NeighbourRules.apply(shared, trueSubnet, swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs); assertEquals(emptyList<Any>(), r.evidence)
        assertEquals(listOf("10.0.3.20", "10.0.3.21", "10.0.3.22"), r.onLinkIps.map { it.hostAddress })
    }
    @Test fun `off-link ips on a slash 24 phone get nothing from the dump`() {
        val r = NeighbourRules.apply(listOf(n("10.0.4.20", "de:ad:be:ef:04:20")), net("10.0.3.0/24"), swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs)
    }
}
```

In `DiscoveryEngineExtendedTest.kt`, add the parameter `neigh: List<com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry> = emptyList()` to `engine(...)` and pass `neighbours = { Result.success(neigh) }` instead of the empty list, then add:
```kotlin
    @Test fun `neighbour dump gives evidence and macs only inside the true subnet`() = runBlocking {
        val env16 = env.copy(prefixLength = 16)   // swept range clamps to 10.0.3.0/24 at maxPrefix 22
        val neigh = listOf(com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.7.9", "de:ad:be:ef:00:07", "REACHABLE"),
            com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("192.168.49.5", "02:00:00:00:00:49", "REACHABLE"))
        val h = engine(alive = { it.hostAddress == "10.0.7.9" }, neigh = neigh).hosts(opts(), e = env16)
        assertEquals(listOf("10.0.3.1", "10.0.7.1"), swept())                      // the hotspot range is never a candidate
        assertEquals("de:ad:be:ef:00:07", h["10.0.7.9"]!!.mac); assertEquals(MacSource.NEIGH, h["10.0.7.9"]!!.macSource)
        assertFalse("192.168.49.5" in h.keys)
    }
    @Test fun `a host known only from the neighbour table keeps its row when three ips share its mac`() = runBlocking {
        val shared = "aa:bb:cc:dd:ee:ff"
        val neigh = listOf(20, 21, 22).map { com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.3.$it", shared, "REACHABLE") }
        val h = engine(neigh = neigh).hosts(opts(enabled = false))
        assertEquals(setOf("10.0.3.1", "10.0.3.20", "10.0.3.21", "10.0.3.22"), h.keys)
        assertTrue(listOf(20, 21, 22).all { h["10.0.3.$it"]!!.mac == null })   // no MAC claim, but the hosts are still listed
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.NeighbourRulesTest" --tests "com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngineExtendedTest" --console=plain 2>&1 | tail -10
```
Expected: `compileDebugUnitTestKotlin FAILED` with `Unresolved reference 'NeighbourRules'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/NeighbourRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Subnet
import java.net.Inet4Address

/** Which neighbour entries may give MACs and evidence (extended spec 6.2). The JNI dump has no interface index; the subnet test is the filter. */
object NeighbourRules {
    const val PROXY_ARP_MIN_IPS = 3

    data class Outcome(val macs: Map<String, String>, val evidence: List<Inet4Address>)

    fun apply(entries: List<NeighbourEntry>, trueSubnet: Subnet, swept: Subnet, gateway: Inet4Address?): Outcome {
        val onLink = entries.mapNotNull { e -> Ipv4.parse(e.ip)?.let { it to e.mac.lowercase() } }.filter { (ip, _) -> trueSubnet.contains(ip) }
        val ipsPerMac = onLink.groupBy({ it.second }, { it.first }).mapValues { (_, ips) -> ips.distinct().size }
        val gatewayMac = gateway?.let { g -> onLink.firstOrNull { it.first == g }?.second }
        val usable = onLink.filter { (ip, mac) -> (ipsPerMac[mac] ?: 0) < PROXY_ARP_MIN_IPS && (ip == gateway || mac != gatewayMac) }
        return Outcome(usable.associate { (ip, mac) -> ip.hostAddress to mac }, usable.map { it.first }.filter { !swept.contains(it) }, onLink.map { it.first }.distinct())
    }
}
```

`DiscoveryEngine.kt`:

1. After `val evidence = LinkedHashSet<Inet4Address>()` add:
```kotlin
        val neighMacs = HashMap<String, String>()   // on-link MACs from the one dump, reused for on-link hosts in extra ranges
```

2. Replace the neighbour block inside the local `launch` (from `_status.update { it.copy(neighbourAvailable = neigh.isSuccess) }` to the end of that `launch`) with:
```kotlin
                    _status.update { it.copy(neighbourAvailable = neigh.isSuccess) }
                    neigh.getOrNull()?.let { entries ->
                        val r = NeighbourRules.apply(entries, SubnetMath.of(env.interfaceIp, env.prefixLength), local, env.gateway)
                        synchronized(neighMacs) { neighMacs.putAll(r.macs) }
                        r.evidence.forEach { noteEvidence(it) }   // evidence contributes ranges, never host rows
                        r.onLinkIps.filter { local.contains(it) }.forEach { ip ->   // outside the swept range an entry is evidence only
                            val mac = r.macs[ip.hostAddress]                        // null for the gateway's MAC on another IP and for proxy ARP: the row stays, the claim goes
                            emit(DiscoveredHost(ip, mac = mac, macSource = mac?.let { MacSource.NEIGH }, sources = setOf(Source.NEIGH)))
                        }
                    }
```

3. In `sweepExtra`, replace the first line of `release` with:
```kotlin
                        val mac = synchronized(neighMacs) { neighMacs[l.ip.hostAddress] }
                        emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, mac = mac, macSource = mac?.let { MacSource.NEIGH },
                            sources = setOfNotNull(Source.SWEEP, mac?.let { Source.NEIGH })))   // never isGateway (spec 5.3)
```

Add the import `com.northwalessolutions.netprobe.netenv.Ipv4`.

- [ ] **Step 4: Run the tests to see them pass, then the full suite and build**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -10
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: `NeighbourRulesTest` 6 passed, `DiscoveryEngineExtendedTest` 21 passed, `DiscoveryEngineTest` unchanged and passing (its neighbour cases use unique MACs inside the /24); `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Apply neighbour-table rules: on-link MACs only, proxy ARP filter, true-subnet evidence"
```

---

### Task 19: Keep the screen on while an extended run is active

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/KeepScreenOn.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt` (`extendedRunActive`), `ui/home/HomeViewModel.kt` (`discover` sets and clears it), `ui/Nav.kt` (applies it to the window)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/KeepScreenOnTest.kt`

**Interfaces:**
- Consumes: `HomeViewModel.discover` as left by Task 16, `AppContainer`, `NetProbeNav`.
- Produces:
```kotlin
// AppContainer gains: val extendedRunActive: MutableStateFlow<Boolean>
@Composable fun KeepScreenOn(active: Boolean)     // FLAG_KEEP_SCREEN_ON on the activity window while active; cleared on dispose
```
- The flag lives at the navigation host, not on Home, so opening a device mid-run does not let the screen sleep (extended spec 10 and 11.12). No permission and no new component: the scan stays in `viewModelScope` as in the MVP.

- [ ] **Step 1: Write the failing test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/KeepScreenOnTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun flagSet() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test fun flagFollowsTheExtendedRunAndIsClearedOnDispose() {
        var active by mutableStateOf(false)
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) KeepScreenOn(active) }
        rule.waitForIdle(); assertFalse(flagSet())
        active = true; rule.waitForIdle(); assertTrue(flagSet())
        active = false; rule.waitForIdle(); assertFalse(flagSet())
        active = true; rule.waitForIdle(); shown = false; rule.waitForIdle(); assertFalse(flagSet())
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.KeepScreenOnTest --console=plain 2>&1 | tail -8
```
Expected: `compileDebugAndroidTestKotlin FAILED` with `Unresolved reference 'KeepScreenOn'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/KeepScreenOn.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A default extended run (1.5 to 2 minutes) outlasts a typical screen timeout; the window stays on only while the run is active. */
@Composable
fun KeepScreenOn(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active, activity) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add
```kotlin
    /** True from the start of an extended run until its discover() returns, whatever screen is showing. */
    val extendedRunActive = MutableStateFlow(false)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen

@Composable
fun NetProbeNav(container: AppContainer) {
    val keepOn by container.extendedRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(keepOn)
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") }) }
        composable("host/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { DetailScreen(container, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`, in `discover`: immediately before `c.locks.acquire()` add
```kotlin
        if (ext.enabled) c.extendedRunActive.value = true
```
and make the first line of the `finally` block
```kotlin
            c.extendedRunActive.value = false
```

- [ ] **Step 4: Run the test to see it pass, then the full suite and build**

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.KeepScreenOnTest --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -5
```
Expected: 1 passed; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Install and verify on the device**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
```
In Settings switch Extended discovery on and add `10.0.4.0/24` for this network; on Home tap Scan, and while the progress line shows the range:
```bash
"$ADB" -s 28221FDH300AP9 shell dumpsys window windows | grep -E "netprobe|KEEP_SCREEN_ON" | tee docs/qa/shots/phase2/t19-keep-screen-on.txt
"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/phase2/t19-extended-running.png
```
Expected: the NetProbe window line lists `KEEP_SCREEN_ON`; after the run ends, the same `dumpsys` no longer lists it (append that output to the same file).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Keep the screen on while an extended discovery run is active"
```

---

### Task 20: Extended discovery on the Director's LAN

**Files:**
- Create: `docs/qa/phase2-extended-discovery-on-device.md`, evidence under `docs/qa/shots/phase2/`
- Modify: anything the run proves broken (each fix its own commit, with a failing JVM or fake-engine test first where one can capture it)

**Interfaces:** none. This task executes extended spec 12.4 checks 1 to 11 and Radar spec 10.3 item 9 on the Pixel 7 Pro (`28221FDH300AP9`) on the Director's LAN: phone in `10.0.3.0/24`, Hue Bridge at `10.0.4.20`, enclosing `10.0.0.0/16`. Bugsy runs it; static review is not acceptable, and a green suite is not evidence that the ranges found match the network.

- [ ] **Step 1: Preconditions**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices | grep 28221FDH300AP9 || echo "PHONE NOT CONNECTED: stop and ask the Director to plug it in"
"$ADB" -s 28221FDH300AP9 shell getprop ro.build.version.release
"$ADB" -s 28221FDH300AP9 shell ip -4 addr show wlan0 | grep inet      # expect 10.0.3.x/24
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -3
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
shot() { "$ADB" -s 28221FDH300AP9 exec-out screencap -p > "docs/qa/shots/phase2/$1.png"; }
"$ADB" -s 28221FDH300AP9 logcat -c
```
Checks 7 and 10 need the Director (another Wi-Fi network; a VPN that routes private ranges). Ask him before starting; record "not run, Director unavailable" rather than skipping silently.

- [ ] **Step 2: Run every check and record it**

Create `docs/qa/phase2-extended-discovery-on-device.md` and fill every row with the measured result, the time taken and the evidence file:
```markdown
# NetProbe Phase 2 Part B (Extended discovery) on-device QA, Pixel 7 Pro (Android <version>), <date>

LAN: phone 10.0.3.0/24, Hue Bridge 10.0.4.20, enclosing 10.0.0.0/16.

| # | Check (extended spec 12.4) | Expected | Result | Time | Evidence |
|---|---|---|---|---|---|
| 1 | Toggle off, scan | Result and time match the Phase A baseline; Hue Bridge under "Other subnet 10.0.4.0/24", no sweep of it | | | b01-toggle-off.png |
| 2 | Toggle on, probe off, no manual ranges | 10.0.4.0/24 found by evidence and swept; its other devices appear in that group; host count | | | b02-evidence.png, b02-progress-100.png, b02-progress-130.png |
| 3 | Toggle on, probe on | 10.0.4.0/24 found by the gateway probe; probe duration, hit list, and the logged attempt count between 527 and 1054 (see below) | | | b03-probe-progress.png, b03-hits.txt |
| 4 | Manual range | 10.0.4.0/24 added; bad inputs show the 4.4 messages inline; manual ranges sweep first; another network does not show or sweep the list | | | b04-*.png |
| 5 | Cap 1 with several candidates | Only the first (manual) range swept; the notice says "N more ranges found, raise the cap in Settings" | | | b05-cap.png |
| 6 | Stop mid extended sweep | Ends within about 1 s; partial hosts stay; notice "Stopped at <range>"; no Wi-Fi lock held | | | b06-stop.png, b06-wifi-locks.txt |
| 7 | Switch Wi-Fi mid-run (Director) | Run cancels with "Network changed, scan stopped"; no candidate state carries over | | | b07-*.png |
| 8 | Untouched phone, 4-range run | Screen stays on; the run completes | | | b08-done.png |
| 9 | Phantom check | A manual /24 the UniFi rejects (or a guest network): range PHANTOM, notice "<range> skipped: every address answered", no rows stored | | | b09-phantom.png |
| 10 | VPN routing private ranges (Director) | Extended phase skipped with "Other ranges skipped: could not bind to Wi-Fi", or probes stay on Wi-Fi; local result intact | | | b10-vpn.png |
| 11 | Timings vs spec 4.6 | Per-range sweep time, gateway probe duration; Settings estimate (25 s per range) within 50 percent | | | b11-timings.txt |
| S | Done summary range list | After a run with several extra ranges, "N other ranges checked" sits above the list and expands to one line per range (source, outcome, count), including a swept-but-empty range | | | b-range-summary.png |
| R9 | Radar 10.3 item 9 | Home with extended discovery on: range progress line "10.0.4.0/24 (1 of 1), probing N of 254" at 1.0 and 1.3 | | | b02-progress-100.png, b02-progress-130.png |
| L | logcat over checks 1 to 10 | No AndroidRuntime lines; no avc denials for netprobe | | | b-logcat-errors.txt |
```

Commands used by the rows:
```bash
# 2 and R9: capture while the range is sweeping, at both font scales
shot b02-progress-100
"$ADB" -s 28221FDH300AP9 shell settings put system font_scale 1.3; shot b02-progress-130; "$ADB" -s 28221FDH300AP9 shell settings put system font_scale 1.0
# 3: attempts come from the app, not from the kernel socket table (a snapshot of open sockets is not an attempt count, and may be unreadable from shell):
# when the run ends the view model logs the probe's final progress, e.g. "gateway probe: 271 of 271 units, 640 connect attempts"
"$ADB" -s 28221FDH300AP9 logcat -d -s NetProbeExt:I | tee docs/qa/shots/phase2/b03-hits.txt
# 6: no lock left after Stop
"$ADB" -s 28221FDH300AP9 shell dumpsys wifi | grep -iE "WifiLock|MulticastLock|netprobe" | tee docs/qa/shots/phase2/b06-wifi-locks.txt
# 8: confirm the window flag while the run is active
"$ADB" -s 28221FDH300AP9 shell dumpsys window windows | grep -E "netprobe|KEEP_SCREEN_ON"
# L
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|avc: denied.*netprobe|FATAL" | tee docs/qa/shots/phase2/b-logcat-errors.txt | tail -20
```
For check 3 the hit list and duration are read off the progress line and the resulting group headers; note the elapsed seconds the progress line showed when the probe phase ended. Expected attempts come from `GatewayTargets.byUnit()`: on this LAN (phone 10.0.3.242/24, gateway .1) that is 255 /24 units of the phone's /16 with offsets .1 and .254, which is 510 addresses, plus the 17 seed addresses outside the /16, which is 527 addresses. Each address costs one attempt, or two when port 80 times out and 443 is tried, so the logged count must lie between 527 and 1054 (a LAN that silently drops everything lands at 1054); a count outside that range, or a `done` that does not equal `total`, is a failure.

For check 2 the canary's addresses are the range's lowest and highest addresses not already stored for the network. On a first extended run the lowest unknown address is usually the range's own `.1` router, which refuses a high port, so the canary then rests on `.254` alone. That is spec behaviour, but record which addresses were already known before the run so the canary pair can be reconstructed.

For check S, tap the "N other ranges checked" line after the run settles and capture `b-range-summary.png` with the lines showing; then start a new scan and confirm the summary is gone while it runs.

- [ ] **Step 3: Fix what fails**

For each failure: reproduce it with a fake in `DiscoveryEngineExtendedTest` or a pure-helper test where possible, fix, rerun `./gradlew :app:testDebugUnitTest :app:assembleDebug`, reinstall, re-run the check, and commit `fix: <what>`. If check 11 shows the 25 s per range estimate off by more than 50 percent, change `ExtendedCopy.SECONDS_PER_RANGE` and its test together and say so in the QA record.

- [ ] **Step 4: Definition of done**

- `./gradlew :app:testDebugUnitTest` green, and `./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` green on the device.
- Every row of both QA records (`phase2-radar-ui-on-device.md`, `phase2-extended-discovery-on-device.md`) has a result and evidence, or "not run" with the reason.
- The Director has confirmed the grouped list and the ranges found match his network (ask explicitly; record his answer in the QA file).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Record extended discovery on-device QA on the Director's LAN"
```

---

## Plan Self-Review Notes

- **Spec coverage, Radar UI spec:**
  - Section 1 decisions: D1, D9, D10, D14, D15, D16 in Task 1; D2 to D6 and D11 in Task 4; D7 in Task 7; D8, D17 in Task 6; D12 in Tasks 2 and 6; D13 in Task 3.
  - Section 2 tokens: 2.1 palette, role table and edge colours in Task 1 (`ContrastTest`, `EdgeColorTest`); 2.2 Plex typography with `tnum` in Task 1 (`TypographyTest`); 2.3 spacing in `Dimens` (Task 1), applied in Tasks 4 to 8 and 17; 2.4 in Task 1; 2.5 theme, window background, `enableEdgeToEdge` and portrait lock in Task 1, insets in Tasks 6, 7 and 8 (including `imePadding()` on the Settings scroll column and the Detail port list, because the edge-to-edge window no longer resizes for the keyboard).
  - Section 3 radar: 3.1 `RadarModes` (Task 2); 3.2 to 3.5 `RadarBlock` (Task 4) placed in Home (Task 6); 3.3 status latch and 250 ms sampling in `HomeScreen` (Task 6); 3.4 summary text (Task 2), its inputs (Task 5) and persistence (Task 6, `RadarStateTest`); 3.6 one transition, cached disc, arm layer, resumed check (Task 4), row arrival (Task 6); 3.7 reduced motion (Tasks 1 and 4).
  - Section 4 Home: 4.1 pinned header, 4.3 row anatomy, 4.4 empty states and notice line in Task 6; 4.2 grouping in Task 3 (by `otherSubnet`) then Task 16 (by `HostRow.subnet`, in the view model).
  - Section 5 Detail: Task 7, with the pure helpers of 5.1 and 5.2 in Task 3.
  - Section 6 Settings: restyle in Task 8; the extended-discovery section styled in Task 17.
  - Section 7: 7.1 in Task 1; 7.2 touch floors in every component task; 7.3 strings in Task 2 and semantics in Tasks 4, 6 and 7; 7.4 in Task 4; 7.5 in Tasks 6 and 7 (font-scale tests).
  - Section 8 copy: `HomeCopy` and `CopyTableTest` (Tasks 2 and 16; regex templates over a generated matrix of every reachable state); Detail strings in Tasks 3 and 7.
  - Section 9 scope: the five read-only fields in Task 5; forbidden dependencies in Global Constraints. Section 10: 10.1 in Tasks 1 to 4; 10.2 tests 1, 3, 6 and 8 in Task 6, 2 and 7 in Task 7, 4 and 5 in Task 4, 9 in Task 1; 10.3 in Task 9 with item 9 in Task 20. Section 11 open items are listed for the Director in the Task 9 QA record. Section 12 is honoured.
- **Spec coverage, extended-discovery spec:**
  - Sections 1 to 3: toggles default off and persisted (Task 11); every decision row is implemented where its section is.
  - Section 4: 4.1 and 4.5 in Task 12; 4.2 DNS evidence (Task 12), mDNS and SSDP evidence and the "after all strategies joined" timing (Task 15), neighbour evidence (Task 18); 4.3 targets, seeds, ports, timeout, concurrency (Task 13) and the TEST-NET pre-check wiring (Task 15); 4.4 parser and per-network storage (Task 11), editor (Task 17); 4.6 measured in Task 20 check 11; 4.7 guard logic (Task 14) and its three layers in the engine (Task 15).
  - Section 5: 5.1 in Task 14; 5.2, 5.3 and 5.4 in Task 15; 5.5 in Tasks 10 and 15; 5.6 `LastRun` in view-model memory (Task 16).
  - Sections 6 and 7: Task 18; Tasks 12 and 16 (no schema change, `RangeRules.groupFor`, `HostRow.subnet`).
  - Section 8: 8.1 and 8.2 in Task 16 (strings owned by the Radar spec; the per-range done summary list is `RangeSummary`), 8.3 in Task 17. Sections 9 and 10: data flow in Task 16; binding (Task 10), cap (Task 12), screen on (Task 19), cellular unchanged.
  - Section 11: 11.1 Tasks 11 and 12; 11.2 Tasks 12 and 18; 11.3 Task 12 (`filterIsInstance<Inet4Address>`); 11.4 and 11.8 unchanged by design; 11.5, 11.6, 11.10, 11.13, 11.14 Task 15 tests; 11.7 Tasks 12 and 15; 11.9 Tasks 15 and 16 ("nothing found"); 11.11 Task 16; 11.12 Task 19.
  - Section 12: 12.1 in Tasks 11, 12, 13, 14, 16 and 18; 12.2 in Tasks 15 and 18; 12.3 in Task 6 (sticky grouped list) and Task 17 (Settings), with the unchanged `DaoTest` in the full instrumented runs of Tasks 9 and 20; 12.4 in Task 20. Section 13 open items keep their recommended defaults.
- **Deviations and additions, each flagged for the Director:**
  1. Stale edge alpha is 0.6, not 0.5: at 0.5 the NAS, MEDIA, PHONE and CAMERA edges fall below the spec's own 3:1 floor (Task 1).
  2. Blips use a hashed-jitter golden-angle spiral: the spec's plain hash puts two of the first 64 blips 0.99 dp apart against its 4 dp guard (Task 4).
  3. `radarRing` is asserted at 64 dp, its touch target (Task 4).
  4. The done summary is derived from the view model's `scanStartedAt` and `scanFinishedAt` instead of a `rememberSaveable` copy, which would survive a system-initiated process death the spec says must show the idle text (Task 6).
  5. `Probe.connect` takes a `SocketBinder` instead of `android.net.Network`, plus a `BoundNetwork` coroutine-context element so HTTP, NetBIOS and SNMP enrichment of extra-range hosts is bound too (Task 10). Reverse DNS stays on the system resolver.
  6. `gatewayProbe` and `canaryProbe` signatures carry the binder and a progress callback; timeout and concurrency are constants (Task 15). `ExtendedOptions.knownIps`, `DiscoveryStatus.gatewayProbe` and `Sweep.runAll` are additions (Task 14).
  7. Copy gaps, Controller rulings applied: "Gateway probe skipped: every address answered" is included for `GATEWAY_PROBE_SKIPPED_ANSWERS_ALL` and is part of the copy-table test; cap drops are reported once by the cap row and the table's TRUNCATED "cut short" row is deleted as unused (Task 16); CIDR messages for malformed text, bad prefixes and duplicates (Task 11).
  8. The per-range done summary that extended spec 8.2 asks for is built (Controller ruling): `RangeSummary` over `HomeUiState.lastRunRanges` in Task 16, "N other ranges checked" expanding to one line per range with source, outcome and count. The Radar spec fixes no wording for those lines, so the strings are this plan's own, listed in `HomeCopy` and covered by `CopyTableTest`. `lastRunRanges` is a second addition to `HomeUiState` beyond the Radar spec's five fields, alongside `groups` (deviation 9).
  9. `HomeUiState.groups` (Task 16) goes beyond the Radar spec's five fields; extended spec 9 step 4 requires grouping in the view model. Task 5 precedes the Home screen because `HomeContent` consumes its fields.
  10. Detail keeps a Snackbar for its own messages (D17 is about Home); the vulnerability sheet's "Keyword matches can be noisy" line is removed as a disclaimer (Task 7).
  11. Instrumented runs pass `leaveApksInstalledAfterRun=true` so the Director's stored data survives (Global Constraints).
- **Type consistency:**
  - `HostRow` has eleven fields with `otherSubnet` until Task 16 replaces it with `subnet` and `offSubnet`. Every constructor call is updated in the same task: `RowRules.row`, `RowTextTest`, `HomeStatsTest`, `HomeFakes`; `HomeFilterTest` passes ten arguments and is unaffected.
  - `HomeGroups.of(rows, localSubnet)` (Task 3) becomes `HomeGroups.of(rows)` in Task 16, with `HomeContent` switching to `state.groups` in the same task.
  - `RangeSource` (Task 12) is used by `RangeProgress` (Task 14). `AnswersForEverything.answered` (Task 13) is used by `PhantomGuard` (Task 14). `Sweep.runAll` (Task 14) is used by the engine (Task 15).
  - `SocketBinder`, `BoundNetwork`, `BindFailedException` (Task 10) are used in Tasks 14 and 15; `NetworkEnvMonitor.binderFor` (Task 10) is used in Task 16.
  - `CidrParser.subnetOf`, `Ipv4.parse`, `Rfc1918`, `slash24Units`, `overlaps`, `start` (Task 11) are used in Tasks 12, 13, 16, 17 and 18. `ManualRangeStore` and `AppSettings.manual` (Task 11) are used in Tasks 16 and 17.
  - `AppContainer.currentNetwork` (Task 16) is used in Task 17; `AppContainer.extendedRunActive` (Task 19) is set by the view model and read by `NetProbeNav`.
  - `HomeCopy` grows in Task 16 only by addition; `ProgressLine.of(status, elapsedS)` keeps its signature. `StopPill` and `npFieldColors` (Task 4), and `ScanPill`, `PinnedSegmented`, `BackToHome` (Task 7), are consumed only after they are defined. `Section`, `SwitchRow`, `StepSlider` become `internal` in Task 8 for Tasks 8 and 17.
- **Review Focus mapping:**
  1. Filter active while scanning: `HeaderRulesTest` (Task 2); `HomeContentTest.chipsStayWhileScanningWithAFilter` and `searchStaysWhileScanningWithAQuery` (Task 6); screenshot a04 (Task 9).
  2. Font scale 1.3 on Detail with the Custom error and the note expanded: `DetailContentTest.worstCaseAtFontScale13LeavesThreePortRows` and `scanBarHeightDoesNotJumpWhenScanStarts` (Task 7); screenshot a10-custom-error-note (Task 9).
  3. A firewall that refuses everything: `DiscoveryEngineExtendedTest` tests `refuse-everything network yields phantom ranges and no extra hosts`, `accept-everything range is discarded by the sweep guard without enrichment`, `the phantom guard never discards an mdns sighting` and, for the opposite error, `a busy real range whose live hosts answer first is not mistaken for a phantom` (Task 15; the guard judges the first 64 addresses, not the fastest 64 answers); `PhantomGuardTest` (Task 14); device check 9 (Task 20).
  4. A VPN active: `ProbeBindTest` tests `binder failure surfaces as BindFailedException and never connects`, `sweep propagates a bind failure instead of counting it as a dead host` and `udp enrichment rethrows a bind failure` (Task 10); `bind failure skips the extended phase and keeps the local result` (Task 15); the notice wording in `ExtendedNoticesTest` (Task 16); device check 10 (Task 20).
  5. Stop mid-range during an extended run: `stop mid range cancels within a second and drops held hosts` (Task 15); `ExtendedNoticesTest.cancelled range names the range that was running` (Task 16); device check 6 (Task 20).
