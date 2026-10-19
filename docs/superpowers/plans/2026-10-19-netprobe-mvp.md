# NetProbe MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A non-rooted Android app that discovers every device on the current LAN, identifies what each one is, port-scans it fast with service names, launches the right app for a service, annotates and filters results, and looks up CVEs for identified software.

**Architecture:** Single Gradle module, Kotlin + Jetpack Compose, MVVM with coroutines and Flow. Pure-Kotlin engines (discovery, port scan, service id, identification, vuln lookup) behind small interfaces so they test on the JVM against loopback servers and fixtures; Android-only pieces (NsdManager, ConnectivityManager, Room, a tiny JNI netlink helper) sit behind those interfaces.

**Tech Stack:** Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00, Room 2.7.1 (KSP), kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, NDK 28.2.13676358 + CMake 3.22.1 (one C file), JUnit 4, Turbine, AndroidX test.

**Spec:** `docs/superpowers/specs/2026-10-19-netprobe-mvp-design.md` (read it first; this plan argues from it).

## Global Constraints

- `minSdk = 26`, `targetSdk = 30`, `compileSdk = 35`. targetSdk 30 is deliberate (spec section 3 and 4): it keeps the netlink neighbour dump on Android 13+. Never raise it.
- Package and `applicationId`: `com.northwalessolutions.netprobe`.
- Toolchain is already installed: SDK at `C:\Users\blastdav\AppData\Local\Android\Sdk`, `JAVA_HOME=C:\Program Files\Java\jdk-17.0.2`, Gradle 8.14 cached under `~/.gradle/wrapper/dists/gradle-8.14-all`, adb at `C:/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe`, device serial `28221FDH300AP9`.
- **No downloads or installs of anything** other than Gradle resolving the Maven dependencies declared in `gradle/libs.versions.toml` from `google()` and `mavenCentral()`. In particular: no OUI CSV, no fonts, no SDK components, no extra repositories. If a declared version does not resolve, change it to the nearest version that does; do not add repositories.
- No em-dashes anywhere: code comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- Commit messages are plain text with no `Co-Authored-By` or other attribution lines.
- Scan / Stop and the detail-screen scan bar are pinned outside any scrolling container (spec 5.7).
- Scanning traffic is LAN only. The only internet call is the NVD query, and only when the user taps "Look up vulnerabilities".
- All shell commands below are Git Bash syntax, run from `K:/claude/netprobe` unless stated. Use `./gradlew` (the wrapper), never a global `gradle`.
- A task is done when its tests pass, `./gradlew :app:testDebugUnitTest` is green, `./gradlew :app:assembleDebug` succeeds, and the commit is made.

## Review Focus

1. **Large subnets.** A /16 or /8 Wi-Fi network must scan only the /24 around the phone and show the notice, never iterate 65k hosts. Test pinned in Task 3.
2. **Network change mid-scan.** Wi-Fi drops or switches while scanning: scan cancels within a second, partial results stay, the UI says why, no crash. Tests pinned in Task 6 (cancel closes sockets) and Task 20 (ViewModel reacts to `null` env).
3. **Garbage from the LAN.** Truncated, oversized or random bytes in SSDP, UPnP XML, NetBIOS, SNMP, HTTP and banners must never throw out of a parser. Tests pinned in Tasks 8, 10, 11, 12, 13.
4. **No app for a scheme.** Tapping an action with no handler installed shows the fallback and the copy actions instead of crashing with `ActivityNotFoundException`. Test pinned in Task 18.
5. **NVD unavailable.** Offline, rate-limited (HTTP 403/429) or malformed NVD responses must produce a clear state and serve cache when present. Tests pinned in Task 19.

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties, local.properties (gitignored)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/cpp/CMakeLists.txt, neigh.c                 JNI netlink neighbour dump
app/src/main/res/raw/ports.json, identify_rules.json, cpe_map.json
app/src/main/java/com/northwalessolutions/netprobe/
  NetProbeApp.kt, MainActivity.kt, AppContainer.kt
  netenv/   NetworkEnv.kt, Subnet.kt, NetworkEnvMonitor.kt
  engine/
    common/   Probe.kt (socket helpers), Bytes.kt
    neigh/    NeighbourTable.kt (JNI wrapper), NeighbourParser.kt
    portscan/ ScanProfile.kt, PortScanEngine.kt, PortTable.kt
    serviceid/ Banner.kt, BannerParsers.kt, ServiceIdentifier.kt, TlsPeek.kt
    discovery/ DiscoveredHost.kt, HostProber.kt, Sweep.kt, MdnsDiscovery.kt, Ssdp.kt, UpnpDescription.kt,
               NetBios.kt, Snmp.kt, HttpTitle.kt, ReverseDns.kt, MacExtract.kt, DiscoveryEngine.kt
    identify/ DeviceIdentity.kt, IdentifyRules.kt, DeviceIdentifier.kt
    vulns/    Cpe.kt, NvdClient.kt, RateLimiter.kt, VulnLookup.kt
  data/     Entities.kt, Daos.kt, NetProbeDb.kt, Repositories.kt, NetworkSignature.kt, Settings.kt
  actions/  DeviceAction.kt, ActionProvider.kt
  ui/       theme/Theme.kt, home/HomeScreen.kt, home/HomeViewModel.kt, detail/DetailScreen.kt,
            detail/DetailViewModel.kt, settings/SettingsScreen.kt, vulns/VulnSheet.kt, Nav.kt
app/src/test/java/...            JVM tests mirror the packages above
app/src/test/resources/fixtures/ recorded packets and responses
app/src/androidTest/java/...      Room DAO, Compose, and the Task 2 device spike
docs/qa/                          on-device QA records
```

---

### Task 1: Project scaffold that builds and runs a unit test

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `local.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/NetProbeApp.kt`, `MainActivity.kt`, `AppContainer.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/SmokeTest.kt`

**Interfaces:**
- Produces: `class AppContainer(context: Context)` with no members yet; later tasks add `val` properties to it. `NetProbeApp.container: AppContainer`.

- [ ] **Step 1: Generate the Gradle wrapper from the cached distribution (no download)**

```bash
cd /k/claude/netprobe
GRADLE_BIN=$(ls -d ~/.gradle/wrapper/dists/gradle-8.14-all/*/gradle-8.14/bin | head -1)
"$GRADLE_BIN/gradle" wrapper --gradle-version 8.14 --distribution-type all --no-daemon
ls gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
```
Expected: the four wrapper files exist. If `gradle-wrapper.properties` lists `distributionUrl` with `gradle-8.14-all.zip`, the wrapper will reuse the cached distribution.

- [ ] **Step 2: Write the build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "NetProbe"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.10.1"
kotlin = "2.1.21"
ksp = "2.1.21-2.0.1"
composeBom = "2025.06.00"
activityCompose = "1.10.1"
lifecycle = "2.9.1"
navigation = "2.9.0"
coreKtx = "1.16.0"
room = "2.7.1"
coroutines = "1.10.2"
serialization = "1.8.1"
junit = "4.13.2"
androidxJunit = "1.2.1"
espresso = "3.6.1"
turbine = "1.2.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.northwalessolutions.netprobe"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.northwalessolutions.netprobe"
        minSdk = 26
        targetSdk = 30   // deliberate: keeps netlink RTM_GETNEIGH on Android 13+, see spec section 4
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        // targetSdk 30 is a documented decision; these checks exist for Play Store policy only.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
```

`local.properties` (gitignored):
```properties
sdk.dir=C\:\\Users\\blastdav\\AppData\\Local\\Android\\Sdk
```

`.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
.cxx/
/captures
```

- [ ] **Step 3: Manifest, resources, app classes**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <queries>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="http" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="https" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="smb" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="ssh" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="telnet" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="vnc" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="rdp" /></intent>
        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="ftp" /></intent>
    </queries>

    <application
        android:name=".NetProbeApp"
        android:label="@string/app_name"
        android:icon="@android:drawable/ic_menu_search"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.NetProbe">
        <activity android:name=".MainActivity" android:exported="true"
            android:configChanges="orientation|screenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
(The icon is a system placeholder on purpose. The Director approves the real icon before it goes on the app.)

`app/src/main/res/values/strings.xml`:
```xml
<resources><string name="app_name">NetProbe</string></resources>
```
`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.NetProbe" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`NetProbeApp.kt`:
```kotlin
package com.northwalessolutions.netprobe

import android.app.Application

class NetProbeApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```
`AppContainer.kt`:
```kotlin
package com.northwalessolutions.netprobe

import android.content.Context

/** Manual dependency container. Later tasks add lazily-created singletons here. */
class AppContainer(val context: Context)
```
`MainActivity.kt`:
```kotlin
package com.northwalessolutions.netprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Text("NetProbe") } } }
    }
}
```

- [ ] **Step 4: Write the smoke unit test**

`app/src/test/java/com/northwalessolutions/netprobe/SmokeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun `unit tests run`() { assertEquals(4, 2 + 2) }
}
```

- [ ] **Step 5: Build and test**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`. First run downloads declared Maven dependencies only. If a version fails to resolve, pick the nearest resolvable version in `libs.versions.toml` and note it in the commit message.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Scaffold Android project: Kotlin, Compose, Room, targetSdk 30"
```

---

### Task 2: Netlink neighbour dump (JNI) with the on-device feasibility gate

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/neigh.c`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/neigh/NeighbourTable.kt`, `NeighbourParser.kt`
- Modify: `app/build.gradle.kts` (externalNativeBuild block)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/neigh/NeighbourParserTest.kt`
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/NeighbourSpikeTest.kt`

**Interfaces:**
- Produces: `data class NeighbourEntry(val ip: String, val mac: String, val state: String)`; `object NeighbourTable { fun dump(): Result<List<NeighbourEntry>> }` (safe: never throws, `Result.failure(NeighbourUnavailable(reason))` when denied); `class NeighbourUnavailable(reason: String) : Exception(reason)`.
- The raw JNI contract: `external fun dumpRaw(): Array<String>`, each element `"<ipv4>|<mac lower, colon separated>|<STATE>"`, or a single element `"ERR|<errno>|<strerror>"`.

- [ ] **Step 1: Write the failing parser test**

```kotlin
package com.northwalessolutions.netprobe.engine.neigh

import org.junit.Assert.*
import org.junit.Test

class NeighbourParserTest {
    @Test fun `parses entries and lower-cases macs`() {
        val r = NeighbourParser.parse(arrayOf("192.168.1.1|AA:BB:CC:DD:EE:FF|REACHABLE", "192.168.1.20|00:11:22:33:44:55|STALE"))
        val list = r.getOrThrow()
        assertEquals(2, list.size)
        assertEquals(NeighbourEntry("192.168.1.1", "aa:bb:cc:dd:ee:ff", "REACHABLE"), list[0])
    }
    @Test fun `drops entries without a usable mac or in FAILED or INCOMPLETE state`() {
        val list = NeighbourParser.parse(arrayOf(
            "192.168.1.2||FAILED", "192.168.1.3|00:00:00:00:00:00|REACHABLE",
            "192.168.1.4|00:11:22:33:44:55|INCOMPLETE", "192.168.1.5|00:11:22:33:44:66|DELAY")).getOrThrow()
        assertEquals(listOf("192.168.1.5"), list.map { it.ip })
    }
    @Test fun `ERR line becomes NeighbourUnavailable`() {
        val r = NeighbourParser.parse(arrayOf("ERR|13|Permission denied"))
        assertTrue(r.exceptionOrNull() is NeighbourUnavailable)
        assertEquals("errno 13: Permission denied", r.exceptionOrNull()!!.message)
    }
    @Test fun `malformed lines are skipped not thrown`() {
        val list = NeighbourParser.parse(arrayOf("garbage", "", "1.2.3.4|zz|REACHABLE", "1.2.3.5|00:11:22:33:44:55|REACHABLE")).getOrThrow()
        assertEquals(1, list.size)
    }
}
```

- [ ] **Step 2: Run it, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*NeighbourParserTest*' --console=plain 2>&1 | tail -15
```
Expected: FAIL, unresolved reference `NeighbourParser`.

- [ ] **Step 3: Kotlin side**

`NeighbourParser.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.neigh

data class NeighbourEntry(val ip: String, val mac: String, val state: String)
class NeighbourUnavailable(reason: String) : Exception(reason)

object NeighbourParser {
    private val macRe = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    private val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val dropStates = setOf("FAILED", "INCOMPLETE", "NOARP", "NONE")

    fun parse(raw: Array<String>): Result<List<NeighbourEntry>> {
        if (raw.size == 1 && raw[0].startsWith("ERR|")) {
            val p = raw[0].split("|", limit = 3)
            return Result.failure(NeighbourUnavailable("errno ${p.getOrNull(1)}: ${p.getOrNull(2)}"))
        }
        val out = ArrayList<NeighbourEntry>()
        for (line in raw) {
            val p = line.split("|")
            if (p.size != 3) continue
            val ip = p[0]; val mac = p[1].lowercase(); val state = p[2]
            if (!ipv4Re.matches(ip) || !macRe.matches(mac)) continue
            if (mac == "00:00:00:00:00:00" || state in dropStates) continue
            out += NeighbourEntry(ip, mac, state)
        }
        return Result.success(out)
    }
}
```
`NeighbourTable.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.neigh

object NeighbourTable {
    private val loaded: Boolean = try { System.loadLibrary("neigh"); true } catch (_: Throwable) { false }
    private external fun dumpRaw(): Array<String>

    /** Never throws. Failure carries NeighbourUnavailable with the errno text. */
    fun dump(): Result<List<NeighbourEntry>> {
        if (!loaded) return Result.failure(NeighbourUnavailable("libneigh not loaded"))
        return try { NeighbourParser.parse(dumpRaw()) } catch (t: Throwable) { Result.failure(NeighbourUnavailable(t.toString())) }
    }
}
```

- [ ] **Step 4: Run parser tests, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*NeighbourParserTest*' --console=plain 2>&1 | tail -15
```
Expected: 4 tests PASS.

- [ ] **Step 5: Native side**

`app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(neigh C)
add_library(neigh SHARED neigh.c)
target_link_libraries(neigh log)
```

`app/src/main/cpp/neigh.c`:
```c
#include <jni.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/neighbour.h>

/* Dumps the IPv4 neighbour table over an UNBOUND netlink route socket.
 * bind() is denied to all third-party apps; RTM_GETNEIGH is allowed only to
 * apps with targetSdkVersion <= 30 on Android 13 and later (spec section 4). */

static const char *state_name(unsigned s) {
    if (s & NUD_REACHABLE) return "REACHABLE";
    if (s & NUD_STALE) return "STALE";
    if (s & NUD_DELAY) return "DELAY";
    if (s & NUD_PROBE) return "PROBE";
    if (s & NUD_PERMANENT) return "PERMANENT";
    if (s & NUD_FAILED) return "FAILED";
    if (s & NUD_INCOMPLETE) return "INCOMPLETE";
    if (s & NUD_NOARP) return "NOARP";
    return "NONE";
}

static jobjectArray err_array(JNIEnv *env, int e) {
    char buf[160];
    snprintf(buf, sizeof buf, "ERR|%d|%s", e, strerror(e));
    jobjectArray arr = (*env)->NewObjectArray(env, 1, (*env)->FindClass(env, "java/lang/String"), NULL);
    (*env)->SetObjectArrayElement(env, arr, 0, (*env)->NewStringUTF(env, buf));
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_northwalessolutions_netprobe_engine_neigh_NeighbourTable_dumpRaw(JNIEnv *env, jobject thiz) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) return err_array(env, errno);

    struct { struct nlmsghdr nlh; struct ndmsg ndm; } req;
    memset(&req, 0, sizeof req);
    req.nlh.nlmsg_len = NLMSG_LENGTH(sizeof(struct ndmsg));
    req.nlh.nlmsg_type = RTM_GETNEIGH;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    req.nlh.nlmsg_seq = 1;
    req.ndm.ndm_family = AF_INET;

    struct sockaddr_nl kernel = { .nl_family = AF_NETLINK };
    if (sendto(fd, &req, req.nlh.nlmsg_len, 0, (struct sockaddr *)&kernel, sizeof kernel) < 0) {
        int e = errno; close(fd); return err_array(env, e);
    }

    /* Collect lines first, then build the Java array. */
    enum { MAX = 2048, LINE = 64 };
    static char lines[MAX][LINE];
    int n = 0, done = 0;
    char buf[65536];
    while (!done) {
        ssize_t len = recv(fd, buf, sizeof buf, 0);
        if (len < 0) { if (errno == EINTR) continue; int e = errno; close(fd); return err_array(env, e); }
        for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, (size_t)len); h = NLMSG_NEXT(h, len)) {
            if (h->nlmsg_type == NLMSG_DONE) { done = 1; break; }
            if (h->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(h);
                close(fd); return err_array(env, -e->error);
            }
            if (h->nlmsg_type != RTM_NEWNEIGH) continue;
            struct ndmsg *ndm = (struct ndmsg *)NLMSG_DATA(h);
            if (ndm->ndm_family != AF_INET) continue;
            char ip[INET_ADDRSTRLEN] = ""; char mac[18] = "";
            int alen = h->nlmsg_len - NLMSG_LENGTH(sizeof *ndm);
            for (struct rtattr *a = (struct rtattr *)((char *)ndm + NLMSG_ALIGN(sizeof *ndm)); RTA_OK(a, alen); a = RTA_NEXT(a, alen)) {
                if (a->rta_type == NDA_DST && RTA_PAYLOAD(a) == 4) inet_ntop(AF_INET, RTA_DATA(a), ip, sizeof ip);
                if (a->rta_type == NDA_LLADDR && RTA_PAYLOAD(a) == 6) {
                    unsigned char *m = RTA_DATA(a);
                    snprintf(mac, sizeof mac, "%02x:%02x:%02x:%02x:%02x:%02x", m[0], m[1], m[2], m[3], m[4], m[5]);
                }
            }
            if (ip[0] && n < MAX) { snprintf(lines[n], LINE, "%s|%s|%s", ip, mac, state_name(ndm->ndm_state)); n++; }
        }
    }
    close(fd);
    jobjectArray arr = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < n; i++) (*env)->SetObjectArrayElement(env, arr, i, (*env)->NewStringUTF(env, lines[i]));
    return arr;
}
```

Add to `android { }` in `app/build.gradle.kts`:
```kotlin
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
```

- [ ] **Step 6: Build**

```bash
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -20
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libneigh
```
Expected: BUILD SUCCESSFUL and `lib/arm64-v8a/libneigh.so` listed.

- [ ] **Step 7: Write the device spike test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/NeighbourSpikeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Spec section 11 item 6. Pass: at least one MAC for a swept host, no ERR. Run on the Pixel 7 Pro. */
@RunWith(AndroidJUnit4::class)
class NeighbourSpikeTest {
    @Test fun neighbourDumpReturnsMacsAfterSweep() = runBlocking {
        val before = NeighbourTable.dump()
        Log.i("NeighbourSpike", "before sweep: $before")
        assertTrue("dump denied: ${before.exceptionOrNull()}", before.isSuccess)

        // Touch 254 hosts of the common home /24s so the kernel ARPs for them.
        val prefixes = listOf("192.168.1.", "192.168.0.", "10.0.0.")
        withContext(Dispatchers.IO.limitedParallelism(128)) {
            prefixes.flatMap { p -> (1..254).map { "$p$it" } }.map { ip ->
                async { runCatching { Socket().use { it.connect(InetSocketAddress(ip, 80), 300) } } }
            }.awaitAll()
        }
        delay(500)
        val after = NeighbourTable.dump().getOrThrow()
        after.forEach { Log.i("NeighbourSpike", "${it.ip} ${it.mac} ${it.state}") }
        Log.i("NeighbourSpike", "entries with mac: ${after.size}")
        assertTrue("expected at least one neighbour with a MAC, got ${after.size}", after.isNotEmpty())
    }
}
```

- [ ] **Step 8: Run the spike on the device (Bugsy, phone plugged in)**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices
./gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.NeighbourSpikeTest 2>&1 | tail -20
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "NeighbourSpike|avc:.*netlink" | tail -40
```
Expected (PASS): test passes, logcat shows `entries with mac: N` with N >= 1 and no `avc: denied ... netlink_route_socket` lines. Record N and the Android version in `docs/qa/2026-10-19-neighbour-spike.md`.

**Gate:** If the test fails with `errno 13` (EACCES) or an `avc: denied` line, STOP and report to the Director: the netlink source is dropped per spec section 11 item 6, and Task 15 keeps only the secondary MAC sources. Do not try to work around SELinux.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add netlink neighbour table dump (JNI) with device spike test"
```

---

### Task 3: Subnet maths

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/netenv/Subnet.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/netenv/SubnetTest.kt`

**Interfaces:**
- Produces: `data class Subnet(val network: Int, val prefix: Int)` with `fun hosts(): Sequence<Inet4Address>` (excludes network and broadcast), `val size: Int`, `fun contains(ip: Inet4Address): Boolean`, `override fun toString()` as `"192.168.1.0/24"`.
- `data class SweepRange(val subnet: Subnet, val clamped: Boolean, val originalPrefix: Int)`.
- `object SubnetMath { fun of(ip: Inet4Address, prefix: Int): Subnet; fun sweepRange(ip: Inet4Address, prefix: Int, maxPrefix: Int = 22): SweepRange; fun toInt(ip: Inet4Address): Int; fun toAddress(i: Int): Inet4Address }`.
- Clamp rule (spec 5.1): if `prefix >= maxPrefix` sweep the whole subnet; otherwise sweep the /24 containing `ip` and set `clamped = true`. Settings may lower `maxPrefix` to 20.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SubnetTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun `slash 24 yields 254 hosts excluding network and broadcast`() {
        val s = SubnetMath.of(ip("192.168.1.37"), 24)
        assertEquals("192.168.1.0/24", s.toString())
        val hosts = s.hosts().toList()
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first().hostAddress)
        assertEquals("192.168.1.254", hosts.last().hostAddress)
    }
    @Test fun `slash 22 is swept whole`() {
        val r = SubnetMath.sweepRange(ip("10.1.2.3"), 22)
        assertFalse(r.clamped)
        assertEquals("10.1.0.0/22", r.subnet.toString())
        assertEquals(1022, r.subnet.hosts().count())
    }
    @Test fun `slash 16 is clamped to the phone's slash 24`() {
        val r = SubnetMath.sweepRange(ip("10.20.30.40"), 16)
        assertTrue(r.clamped)
        assertEquals(16, r.originalPrefix)
        assertEquals("10.20.30.0/24", r.subnet.toString())
    }
    @Test fun `slash 8 is clamped too and never iterates millions`() {
        val r = SubnetMath.sweepRange(ip("10.0.0.5"), 8)
        assertTrue(r.clamped)
        assertEquals(254, r.subnet.hosts().count())
    }
    @Test fun `maxPrefix 20 allows a slash 20 sweep`() {
        val r = SubnetMath.sweepRange(ip("172.16.5.9"), 20, maxPrefix = 20)
        assertFalse(r.clamped)
        assertEquals(4094, r.subnet.hosts().count())
    }
    @Test fun `slash 30 and slash 31 edge cases`() {
        assertEquals(2, SubnetMath.of(ip("192.168.1.1"), 30).hosts().count())
        assertEquals(2, SubnetMath.of(ip("192.168.1.1"), 31).hosts().count()) // RFC 3021: both usable
        assertEquals(1, SubnetMath.of(ip("192.168.1.1"), 32).hosts().count())
    }
    @Test fun `contains`() {
        val s = SubnetMath.of(ip("192.168.1.0"), 24)
        assertTrue(s.contains(ip("192.168.1.200")))
        assertFalse(s.contains(ip("192.168.2.1")))
    }
    @Test fun `int round trip keeps high addresses unsigned`() {
        val a = ip("224.0.0.251")
        assertEquals(a, SubnetMath.toAddress(SubnetMath.toInt(a)))
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*SubnetTest*' --console=plain 2>&1 | tail -15
```
Expected: FAIL, unresolved `SubnetMath`.

- [ ] **Step 3: Implement**

```kotlin
package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

data class Subnet(val network: Int, val prefix: Int) {
    private val mask: Int get() = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val size: Int get() = if (prefix >= 31) (1 shl (32 - prefix)) else (1 shl (32 - prefix)) - 2
    fun contains(ip: Inet4Address): Boolean = (SubnetMath.toInt(ip) and mask) == network
    fun hosts(): Sequence<Inet4Address> {
        val total = 1L shl (32 - prefix)
        val first = if (prefix >= 31) 0L else 1L
        val last = if (prefix >= 31) total - 1 else total - 2
        return (first..last).asSequence().map { SubnetMath.toAddress(network + it.toInt()) }
    }
    override fun toString() = "${SubnetMath.toAddress(network).hostAddress}/$prefix"
}

data class SweepRange(val subnet: Subnet, val clamped: Boolean, val originalPrefix: Int)

object SubnetMath {
    fun toInt(ip: Inet4Address): Int {
        val b = ip.address
        return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
    }
    fun toAddress(i: Int): Inet4Address = InetAddress.getByAddress(
        byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())) as Inet4Address

    fun of(ip: Inet4Address, prefix: Int): Subnet {
        require(prefix in 0..32) { "prefix out of range: $prefix" }
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return Subnet(toInt(ip) and mask, prefix)
    }

    fun sweepRange(ip: Inet4Address, prefix: Int, maxPrefix: Int = 22): SweepRange =
        if (prefix >= maxPrefix) SweepRange(of(ip, prefix), clamped = false, originalPrefix = prefix)
        else SweepRange(of(ip, 24), clamped = true, originalPrefix = prefix)
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*SubnetTest*' --console=plain 2>&1 | tail -15
```
Expected: 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add subnet maths with large-network clamp"
```

---

### Task 4: Network environment monitor

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/netenv/NetworkEnv.kt`, `NetworkEnvMonitor.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/netenv/NetworkEnvMapperTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class Transport { WIFI, ETHERNET }
data class NetworkEnv(val interfaceIp: Inet4Address, val prefixLength: Int, val gateway: Inet4Address?,
                      val dnsServers: List<InetAddress>, val ssid: String?, val bssid: String?, val transport: Transport)
/** Pure, testable input snapshot built from LinkProperties / NetworkCapabilities / WifiInfo. */
data class LinkSnapshot(val addresses: List<Pair<InetAddress, Int>>, val routes: List<Pair<InetAddress?, Boolean>> /* gateway, isDefault */,
                        val dns: List<InetAddress>, val isWifi: Boolean, val isEthernet: Boolean, val isVpn: Boolean,
                        val ssid: String?, val bssid: String?)
object NetworkEnvMapper { fun map(s: LinkSnapshot): NetworkEnv? }
class NetworkEnvMonitor(context: Context) { val env: Flow<NetworkEnv?> }
```
- Consumes: nothing.

- [ ] **Step 1: Failing mapper tests**

```kotlin
package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkEnvMapperTest {
    private fun a(s: String) = InetAddress.getByName(s)
    private val wifi = LinkSnapshot(
        addresses = listOf(a("fe80::1") to 64, a("192.168.1.23") to 24),
        routes = listOf(a("192.168.1.1") to true, null to false),
        dns = listOf(a("192.168.1.1"), a("1.1.1.1")),
        isWifi = true, isEthernet = false, isVpn = false, ssid = "Home", bssid = "aa:bb:cc:dd:ee:ff")

    @Test fun `wifi snapshot maps to env with ipv4 and gateway`() {
        val env = NetworkEnvMapper.map(wifi)!!
        assertEquals("192.168.1.23", env.interfaceIp.hostAddress)
        assertEquals(24, env.prefixLength)
        assertEquals("192.168.1.1", env.gateway?.hostAddress)
        assertEquals(Transport.WIFI, env.transport)
        assertEquals("Home", env.ssid)
    }
    @Test fun `unknown ssid placeholder becomes null`() {
        val env = NetworkEnvMapper.map(wifi.copy(ssid = "<unknown ssid>", bssid = "02:00:00:00:00:00"))!!
        assertNull(env.ssid); assertNull(env.bssid)
    }
    @Test fun `ethernet maps`() {
        val env = NetworkEnvMapper.map(wifi.copy(isWifi = false, isEthernet = true, ssid = null, bssid = null))!!
        assertEquals(Transport.ETHERNET, env.transport)
    }
    @Test fun `no ipv4 address yields null`() {
        assertNull(NetworkEnvMapper.map(wifi.copy(addresses = listOf(a("fe80::1") to 64))))
    }
    @Test fun `vpn or cellular only yields null`() {
        assertNull(NetworkEnvMapper.map(wifi.copy(isVpn = true)))
        assertNull(NetworkEnvMapper.map(wifi.copy(isWifi = false, isEthernet = false)))
    }
    @Test fun `missing default route gives null gateway but still an env`() {
        val env = NetworkEnvMapper.map(wifi.copy(routes = emptyList()))!!
        assertNull(env.gateway)
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*NetworkEnvMapperTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`NetworkEnv.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

enum class Transport { WIFI, ETHERNET }

data class NetworkEnv(
    val interfaceIp: Inet4Address, val prefixLength: Int, val gateway: Inet4Address?,
    val dnsServers: List<InetAddress>, val ssid: String?, val bssid: String?, val transport: Transport,
)

data class LinkSnapshot(
    val addresses: List<Pair<InetAddress, Int>>, val routes: List<Pair<InetAddress?, Boolean>>,
    val dns: List<InetAddress>, val isWifi: Boolean, val isEthernet: Boolean, val isVpn: Boolean,
    val ssid: String?, val bssid: String?,
)

object NetworkEnvMapper {
    fun map(s: LinkSnapshot): NetworkEnv? {
        if (s.isVpn) return null
        val transport = when { s.isWifi -> Transport.WIFI; s.isEthernet -> Transport.ETHERNET; else -> return null }
        val (ip, prefix) = s.addresses.firstOrNull { it.first is Inet4Address && !it.first.isLoopbackAddress } ?: return null
        val gw = s.routes.firstOrNull { it.second && it.first is Inet4Address }?.first as Inet4Address?
        val ssid = s.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        val bssid = s.bssid?.takeUnless { it == "02:00:00:00:00:00" || it.isBlank() }
        return NetworkEnv(ip as Inet4Address, prefix, gw, s.dns, ssid, bssid, transport)
    }
}
```

`NetworkEnvMonitor.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkEnvMonitor(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Emits the current usable LAN environment, or null. Re-emits on any link change. */
    val env: Flow<NetworkEnv?> = callbackFlow {
        fun publish() { trySend(current()) }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = publish()
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = publish()
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        cm.registerNetworkCallback(req, cb)
        publish()
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    fun current(): NetworkEnv? {
        // Prefer a Wi-Fi or Ethernet network even when a VPN is the active default.
        val candidates = cm.allNetworks.mapNotNull { n ->
            val nc = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            snapshot(nc, lp)
        }
        return candidates.firstNotNullOfOrNull { NetworkEnvMapper.map(it) }
    }

    private fun snapshot(nc: NetworkCapabilities, lp: LinkProperties): LinkSnapshot {
        @Suppress("DEPRECATION") val info = runCatching { wifi.connectionInfo }.getOrNull()
        return LinkSnapshot(
            addresses = lp.linkAddresses.map { it.address to it.prefixLength },
            routes = lp.routes.map { it.gateway to it.isDefaultRoute },
            dns = lp.dnsServers,
            isWifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isEthernet = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            isVpn = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            ssid = info?.ssid, bssid = info?.bssid,
        )
    }
}
```
Note: `WifiManager.connectionInfo` returns placeholders without location permission; the mapper turns those into null. Task 21 wires the monitor into `AppContainer`.

- [ ] **Step 4: Run, expect pass; build**

```bash
./gradlew :app:testDebugUnitTest --tests '*NetworkEnvMapperTest*' :app:assembleDebug --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add network environment monitor and mapper"
```

---

### Task 5: Port table, scan profiles and custom profile parser

**Files:**
- Create: `app/src/main/res/raw/ports.json`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/ScanProfile.kt`, `PortTable.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/ScanProfileTest.kt`, `PortTableTest.kt`

**Interfaces:**
- Produces:
```kotlin
sealed class ScanProfile { abstract val ports: List<Int>; abstract val label: String
    object Quick : ScanProfile()   // Appendix A, 115 ports
    object Full : ScanProfile()    // 1..65535
    data class Custom(override val ports: List<Int>) : ScanProfile() }
object ProfileParser { fun parse(text: String): Result<ScanProfile.Custom> }  // "22,80,8000-8100"
object QuickPorts { val list: List<Int> }
class PortTable(entries: Map<Int, String>) { fun nameFor(port: Int): String  /* "Unknown" when absent */
    companion object { fun fromJson(json: String): PortTable } }
```
- The JSON shape: `[{"port":22,"proto":"tcp","name":"SSH"}, ...]`. The implementer authors every entry listed in spec Appendix C and a name for every port in Appendix A. Loading from `R.raw.ports` happens in Task 21; tests pass the JSON string.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import org.junit.Assert.*
import org.junit.Test

class ScanProfileTest {
    @Test fun `quick has 115 unique ports in range`() {
        val q = ScanProfile.Quick.ports
        assertEquals(115, q.size); assertEquals(q.size, q.toSet().size)
        assertTrue(q.all { it in 1..65535 })
        assertTrue(q.containsAll(listOf(22, 80, 443, 445, 3389, 5900, 8080, 62078, 8123, 32400)))
    }
    @Test fun `full is 1 to 65535`() {
        assertEquals(65535, ScanProfile.Full.ports.size); assertEquals(1, ScanProfile.Full.ports.first()); assertEquals(65535, ScanProfile.Full.ports.last())
    }
    @Test fun `parser accepts lists and ranges and sorts and dedupes`() {
        assertEquals(listOf(22, 80, 8000, 8001, 8002), ProfileParser.parse(" 80, 22,8000-8002,80 ").getOrThrow().ports)
    }
    @Test fun `parser rejects bad input with a message`() {
        for (bad in listOf("", "abc", "0", "65536", "80-22", "22,,80", "1-70000", "-5")) {
            val r = ProfileParser.parse(bad)
            assertTrue("should reject '$bad'", r.isFailure)
            assertFalse(r.exceptionOrNull()!!.message.isNullOrBlank())
        }
    }
    @Test fun `parser caps a range at 65535 entries not more`() {
        assertEquals(65535, ProfileParser.parse("1-65535").getOrThrow().ports.size)
    }
}

class PortTableTest {
    private val json = """[{"port":22,"proto":"tcp","name":"SSH"},{"port":80,"proto":"tcp","name":"HTTP"},{"port":5900,"proto":"tcp","name":"VNC"}]"""
    @Test fun `looks up names and falls back to Unknown`() {
        val t = PortTable.fromJson(json)
        assertEquals("SSH", t.nameFor(22)); assertEquals("VNC", t.nameFor(5900)); assertEquals("Unknown", t.nameFor(31337))
    }
    @Test fun `malformed json yields an empty table not a crash`() {
        assertEquals("Unknown", PortTable.fromJson("not json").nameFor(22))
    }
    @Test fun `bundled table names every quick port`() {
        val t = PortTable.fromJson(javaClass.getResource("/ports.json")!!.readText())
        val unnamed = ScanProfile.Quick.ports.filter { t.nameFor(it) == "Unknown" }
        assertTrue("unnamed quick ports: $unnamed", unnamed.isEmpty())
    }
}
```
For the last test, add to `app/build.gradle.kts` inside `android { sourceSets { getByName("test") { resources.srcDirs("src/main/res/raw") } } }` so `ports.json` is on the test classpath.

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*ScanProfileTest*' --tests '*PortTableTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`ScanProfile.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

object QuickPorts {
    val list: List<Int> = listOf(
        7, 9, 13, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143, 144, 179, 199,
        389, 427, 443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587, 631, 646, 873, 990, 993, 995,
        1025, 1026, 1027, 1028, 1029, 1110, 1433, 1720, 1723, 1755, 1900, 2000, 2001, 2049, 2121, 2717,
        3000, 3128, 3306, 3389, 3986, 4899, 5000, 5009, 5051, 5060, 5101, 5190, 5357, 5432, 5631, 5666, 5800, 5900,
        6000, 6001, 6646, 7070, 8000, 8008, 8009, 8080, 8081, 8443, 8888, 9100, 9999, 10000, 32768,
        49152, 49153, 49154, 49155, 49156, 49157,
        // LAN extras
        1883, 2222, 3001, 5001, 5555, 5985, 8006, 8096, 8123, 8291, 8880, 9000, 9090, 32400, 62078,
    )
}

sealed class ScanProfile {
    abstract val ports: List<Int>
    abstract val label: String
    object Quick : ScanProfile() { override val ports get() = QuickPorts.list; override val label = "Quick" }
    object Full : ScanProfile() { override val ports get() = (1..65535).toList(); override val label = "Full" }
    data class Custom(override val ports: List<Int>) : ScanProfile() { override val label = "Custom" }
}

object ProfileParser {
    fun parse(text: String): Result<ScanProfile.Custom> {
        val tokens = text.split(',').map { it.trim() }
        if (text.isBlank() || tokens.any { it.isEmpty() }) return Result.failure(IllegalArgumentException("Enter ports like 22,80,8000-8100"))
        val out = sortedSetOf<Int>()
        for (t in tokens) {
            val m = Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(t) ?: return Result.failure(IllegalArgumentException("Not a port or range: '$t'"))
            val a = m.groupValues[1].toIntOrNull() ?: return Result.failure(IllegalArgumentException("Not a number: '$t'"))
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Not a number: '$t'"))
            if (a !in 1..65535 || b !in 1..65535) return Result.failure(IllegalArgumentException("Ports must be 1 to 65535: '$t'"))
            if (b < a) return Result.failure(IllegalArgumentException("Range is backwards: '$t'"))
            out.addAll(a..b)
        }
        return Result.success(ScanProfile.Custom(out.toList()))
    }
}
```

`PortTable.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class PortEntry(val port: Int, val proto: String = "tcp", val name: String)

class PortTable(private val entries: Map<Int, String>) {
    fun nameFor(port: Int): String = entries[port] ?: "Unknown"
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): PortTable = runCatching {
            PortTable(json.decodeFromString<List<PortEntry>>(text).filter { it.proto == "tcp" }.associate { it.port to it.name })
        }.getOrElse { PortTable(emptyMap()) }
    }
}
```

`app/src/main/res/raw/ports.json`: author it by hand. Every port in `QuickPorts.list` gets a name (IANA well-known: 7 Echo, 9 Discard, 13 Daytime, 21 FTP, 22 SSH, 23 Telnet, 25 SMTP, 53 DNS, 80 HTTP, 110 POP3, 111 RPCbind, 135 MS-RPC, 139 NetBIOS-SSN, 143 IMAP, 389 LDAP, 443 HTTPS, 445 SMB, 548 AFP, 554 RTSP, 587 SMTP Submission, 631 IPP, 873 rsync, 993 IMAPS, 995 POP3S, 1433 MSSQL, 1723 PPTP, 1900 SSDP, 2049 NFS, 3000 Web (dev), 3128 Squid Proxy, 3306 MySQL, 3389 RDP, 5000 Web / Synology, 5001 Synology HTTPS, 5060 SIP, 5432 PostgreSQL, 5555 ADB, 5800 VNC HTTP, 5900 VNC, 5985 WinRM, 6000 X11, 8000 Web (alt), 8006 Proxmox, 8008 Cast / HTTP alt, 8009 Chromecast, 8080 HTTP Proxy, 8081 HTTP alt, 8096 Jellyfin, 8123 Home Assistant, 8291 MikroTik Winbox, 8443 HTTPS alt, 8888 HTTP alt, 9000 Web (alt), 9090 Web admin, 9100 JetDirect, 10000 Webmin, 32400 Plex, 32768 RPC, 49152 to 49157 Dynamic / UPnP, 62078 iOS Lockdown, 1883 MQTT, 2222 SSH alt, plus the rest named from IANA) and the spec Appendix C list (5672 AMQP, 6379 Redis, 27017 MongoDB, 9200 Elasticsearch, 1880 Node-RED, 4711 Pi-hole FTL, 5901 to 5910 VNC, 5986 WinRM HTTPS, 137 NetBIOS-NS, 138 NetBIOS-DGM, 636 LDAPS, 88 Kerberos). Use the format `{"port":22,"proto":"tcp","name":"SSH"}` one per line.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*ScanProfileTest*' --tests '*PortTableTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add scan profiles, custom profile parser and bundled port table"
```

---

### Task 6: TCP connect port-scan engine

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/Probe.kt`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngine.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngineTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class PortState { OPEN, CLOSED, FILTERED }
data class PortResult(val port: Int, val state: PortState, val rttMs: Int?)
data class ScanOptions(val concurrency: Int = 128, val connectTimeoutMs: Int = 600)
data class ScanProgress(val done: Int, val total: Int, val open: Int, val errors: Int)
class PortScanEngine(private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) }) {
    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions()): Flow<PortResult>  // emits OPEN, CLOSED and FILTERED; callers filter
    val progress: StateFlow<ScanProgress?> }
object Probe { sealed class Outcome { data class Connected(val rttMs: Int, val socket: Socket) : Outcome(); object Refused : Outcome(); object Timeout : Outcome(); object Unreachable : Outcome() }
    suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false): Outcome }
```
- `Probe.connect` is the single place sockets are opened for TCP probing; the sweep (Task 7) and banner reader (Task 8) reuse it. Classification: success = Connected; `ConnectException` whose message contains `ECONNREFUSED` or `refused` = Refused; `SocketTimeoutException` = Timeout; `NoRouteToHostException` or messages containing `EHOSTUNREACH`/`ENETUNREACH` = Unreachable; anything else = Timeout (treated as FILTERED).
- Cancellation: `suspendCancellableCoroutine` is not needed; wrap the blocking connect in `runInterruptible` and close the socket in `invokeOnCancellation`-equivalent `finally`.

- [ ] **Step 1: Failing tests (loopback servers)**

```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import app.cash.turbine.test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortScanEngineTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `open and closed ports are classified`() = runBlocking {
        val open = listen(); val closed = freePort()
        val results = PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open, closed))).toList()
        assertEquals(PortState.OPEN, results.first { it.port == open }.state)
        assertEquals(PortState.CLOSED, results.first { it.port == closed }.state)
        assertNotNull(results.first { it.port == open }.rttMs)
    }
    @Test fun `unroutable address times out as FILTERED within the timeout budget`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val results = PortScanEngine().scan(InetAddress.getByName("192.0.2.1"), ScanProfile.Custom(listOf(80, 443)),
            ScanOptions(concurrency = 2, connectTimeoutMs = 300)).toList()
        assertTrue(results.all { it.state == PortState.FILTERED })
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2500)
    }
    @Test fun `progress counts every port once and counts opens`() = runBlocking {
        val open = listen(); val engine = PortScanEngine()
        engine.scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open, freePort(), freePort()))).toList()
        assertEquals(ScanProgress(done = 3, total = 3, open = 1, errors = 0), engine.progress.value)
    }
    @Test fun `cancellation stops the flow quickly`() = runBlocking {
        val engine = PortScanEngine()
        val job = launch { engine.scan(InetAddress.getByName("192.0.2.1"), ScanProfile.Full, ScanOptions(concurrency = 64, connectTimeoutMs = 2000)).collect { } }
        delay(300)
        val t0 = System.currentTimeMillis(); job.cancelAndJoin()
        assertTrue("cancel took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1000)
    }
    @Test fun `results arrive progressively not in one batch`() = runBlocking {
        val open = listen()
        PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(listOf(open) + (1..40).map { freePort() })).test {
            awaitItem(); cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*PortScanEngineTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`engine/common/Probe.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.runInterruptible
import java.net.*

object Probe {
    sealed class Outcome {
        data class Connected(val rttMs: Int, val socket: Socket?) : Outcome()
        object Refused : Outcome(); object Timeout : Outcome(); object Unreachable : Outcome()
    }

    /** Blocking connect made cancellable: cancellation closes the socket, which unblocks connect(). */
    suspend fun connect(ip: InetAddress, port: Int, timeoutMs: Int, keepOpen: Boolean = false): Outcome {
        val socket = Socket()
        return try {
            runInterruptible {
                val t0 = System.nanoTime()
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val rtt = ((System.nanoTime() - t0) / 1_000_000).toInt()
                if (keepOpen) Outcome.Connected(rtt, socket) else { socket.close(); Outcome.Connected(rtt, null) }
            }
        } catch (e: SocketTimeoutException) { socket.close(); Outcome.Timeout
        } catch (e: NoRouteToHostException) { socket.close(); Outcome.Unreachable
        } catch (e: ConnectException) {
            socket.close()
            val m = e.message.orEmpty()
            when {
                m.contains("ECONNREFUSED", true) || m.contains("refused", true) -> Outcome.Refused
                m.contains("EHOSTUNREACH", true) || m.contains("ENETUNREACH", true) -> Outcome.Unreachable
                else -> Outcome.Timeout
            }
        } catch (e: InterruptedException) { socket.close(); throw kotlinx.coroutines.CancellationException("probe cancelled")
        } catch (e: Exception) { socket.close(); Outcome.Timeout }
    }
}
```

`PortScanEngine.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.engine.common.Probe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.InetAddress

enum class PortState { OPEN, CLOSED, FILTERED }
data class PortResult(val port: Int, val state: PortState, val rttMs: Int?)
data class ScanOptions(val concurrency: Int = 128, val connectTimeoutMs: Int = 600)
data class ScanProgress(val done: Int, val total: Int, val open: Int, val errors: Int)

class PortScanEngine(
    private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) },
) {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions()): Flow<PortResult> = channelFlow {
        val ports = profile.ports
        var done = 0; var open = 0; var errors = 0
        _progress.value = ScanProgress(0, ports.size, 0, 0)
        val dispatcher = dispatcherFactory(options.concurrency)
        val sem = Semaphore(options.concurrency)
        coroutineScope {
            for (port in ports) {
                sem.acquire()
                launch(dispatcher) {
                    try {
                        val r = when (val o = Probe.connect(ip, port, options.connectTimeoutMs)) {
                            is Probe.Outcome.Connected -> PortResult(port, PortState.OPEN, o.rttMs)
                            Probe.Outcome.Refused -> PortResult(port, PortState.CLOSED, null)
                            Probe.Outcome.Timeout, Probe.Outcome.Unreachable -> PortResult(port, PortState.FILTERED, null)
                        }
                        send(r)
                        synchronized(this@PortScanEngine) { done++; if (r.state == PortState.OPEN) open++; _progress.value = ScanProgress(done, ports.size, open, errors) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        synchronized(this@PortScanEngine) { done++; errors++; _progress.value = ScanProgress(done, ports.size, open, errors) }
                    } finally { sem.release() }
                }
            }
        }
    }
}
```
`Semaphore` is `kotlinx.coroutines.sync.Semaphore`; import it and `withPermit` is not used because acquisition happens in the producer loop to bound in-flight launches.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*PortScanEngineTest*' --console=plain 2>&1 | tail -15
```
Expected: 5 PASS. If the loopback `CLOSED` classification fails on the Windows JVM because the message differs, extend the `Refused` match to include `Connection refused` (already covered by `"refused"`), and if `192.0.2.1` returns `Unreachable` instead of `Timeout` the test still passes because both map to FILTERED.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add TCP connect port-scan engine with progress and cancellation"
```

---

### Task 7: Host liveness prober and sweep

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HostProber.kt`, `Sweep.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HostProberTest.kt`, `SweepTest.kt`

**Interfaces:**
- Consumes: `Probe.connect` (Task 6), `Subnet` (Task 3).
- Produces:
```kotlin
data class Liveness(val ip: Inet4Address, val alive: Boolean, val openPorts: Set<Int>, val rttMs: Int?)
class HostProber(val ports: List<Int> = DEFAULT_PORTS, val timeoutMs: Int = 600) {
    suspend fun probe(ip: Inet4Address): Liveness
    companion object { val DEFAULT_PORTS = listOf(80, 443, 22, 445, 139, 135, 3389, 8080, 62078, 7, 53, 548) } }
class Sweep(private val prober: HostProber, private val concurrency: Int = 128,
            private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) }) {
    fun run(hosts: Sequence<Inet4Address>): Flow<Liveness>   // emits only alive hosts
    val progress: StateFlow<Pair<Int, Int>>                   // probed, total }
```
- Rule (spec 5.2 item 1): probe ports in order and stop at the first `Connected` or `Refused` (both alive). All `Timeout`/`Unreachable` = not alive. A `Connected` port is recorded in `openPorts`; after one `Connected`, keep probing the remaining ports only if `collectAllOpen` is false (it is: stop early; the port scan does the rest).

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

class HostProberTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `open port means alive and is recorded`() = runBlocking {
        val open = listen()
        val l = HostProber(ports = listOf(freePort(), open), timeoutMs = 500).probe(lo)
        assertTrue(l.alive); assertEquals(setOf(open), l.openPorts); assertNotNull(l.rttMs)
    }
    @Test fun `refused port alone means alive with no open ports`() = runBlocking {
        val l = HostProber(ports = listOf(freePort()), timeoutMs = 500).probe(lo)
        assertTrue(l.alive); assertTrue(l.openPorts.isEmpty())
    }
    @Test fun `all timeouts means not alive`() = runBlocking {
        val l = HostProber(ports = listOf(80, 443), timeoutMs = 300).probe(InetAddress.getByName("192.0.2.1") as Inet4Address)
        assertFalse(l.alive)
    }
    @Test fun `stops at first definitive answer`() = runBlocking {
        // First port refused: must not go on to the slow unroutable host ports (none here, but the port list after the first is unused).
        val t0 = System.currentTimeMillis()
        HostProber(ports = listOf(freePort(), freePort(), freePort()), timeoutMs = 2000).probe(lo)
        assertTrue(System.currentTimeMillis() - t0 < 1500)
    }
}

class SweepTest {
    @Test fun `emits only alive hosts and tracks progress`() = runBlocking {
        val lo = InetAddress.getLoopbackAddress() as Inet4Address
        val dead = InetAddress.getByName("192.0.2.1") as Inet4Address
        val sweep = Sweep(HostProber(ports = listOf(ServerSocket(0).also { it.close() }.localPort), timeoutMs = 300), concurrency = 8)
        val out = sweep.run(sequenceOf(lo, dead, dead)).toList()
        assertEquals(listOf(lo), out.map { it.ip })
        assertEquals(3 to 3, sweep.progress.value)
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*HostProberTest*' --tests '*SweepTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`HostProber.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import java.net.Inet4Address

data class Liveness(val ip: Inet4Address, val alive: Boolean, val openPorts: Set<Int>, val rttMs: Int?)

class HostProber(val ports: List<Int> = DEFAULT_PORTS, val timeoutMs: Int = 600) {
    suspend fun probe(ip: Inet4Address): Liveness {
        for (port in ports) {
            when (val o = Probe.connect(ip, port, timeoutMs)) {
                is Probe.Outcome.Connected -> return Liveness(ip, true, setOf(port), o.rttMs)
                Probe.Outcome.Refused -> return Liveness(ip, true, emptySet(), null)
                Probe.Outcome.Unreachable -> return Liveness(ip, false, emptySet(), null)
                Probe.Outcome.Timeout -> Unit
            }
        }
        return Liveness(ip, false, emptySet(), null)
    }
    companion object { val DEFAULT_PORTS = listOf(80, 443, 22, 445, 139, 135, 3389, 8080, 62078, 7, 53, 548) }
}
```

`Sweep.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.net.Inet4Address

class Sweep(
    private val prober: HostProber,
    private val concurrency: Int = 128,
    private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) },
) {
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress

    fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = channelFlow {
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
                        val l = prober.probe(ip)
                        if (l.alive) send(l)
                    } catch (e: CancellationException) { throw e
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

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*HostProberTest*' --tests '*SweepTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add TCP liveness prober and subnet sweep"
```

---

### Task 8: Banner parsers and service identification

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceInfo.kt`, `BannerParsers.kt`, `BannerReader.kt`, `TlsPeek.kt`, `ServiceIdentifier.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/BannerParsersTest.kt`, `ServiceIdentifierTest.kt`

**Interfaces:**
- Consumes: `PortTable` (Task 5), `Probe.connect(keepOpen = true)` (Task 6).
- Produces:
```kotlin
enum class Confidence { PORT_TABLE, CONFIRMED }
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null, val detail: String? = null, val confidence: Confidence)
data class Evidence(val mdnsTypes: Set<String> = emptySet(), val upnpServer: String? = null)
object BannerParsers {
    fun ssh(line: String): ServiceInfo?            // "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13" -> SSH / OpenSSH / 9.6p1 / "Ubuntu-3ubuntu13"
    fun greeting(line: String, portName: String): ServiceInfo?  // FTP/SMTP/POP3/IMAP "220 ProFTPD 1.3.8 Server" -> product/version
    fun httpHead(response: String): ServiceInfo?   // status line + Server header -> HTTP / nginx / 1.18.0
    fun serverHeader(value: String): Pair<String?, String?>    // "nginx/1.18.0 (Ubuntu)" -> nginx, 1.18.0
    fun classifyUnknown(bytes: ByteArray, portName: String): ServiceInfo }
class BannerReader(val timeoutMs: Int = 1000, val maxBytes: Int = 1024) {
    suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? }
class ServiceIdentifier(private val table: PortTable, private val reader: BannerReader = BannerReader(), private val tls: TlsPeek = TlsPeek()) {
    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence()): ServiceInfo }
object TlsPeek { suspend fun certNames(ip: InetAddress, port: Int, timeoutMs: Int = 1500): List<String>? }
```
- Port sets (spec 5.4): server-speaks-first `{21, 22, 25, 110, 143, 587, 2222}`; HTTP `{80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400}`; TLS `{443, 8443, 7443, 8006, 5001}`.
- mDNS confirmation: `_rfb._tcp` confirms VNC on 5900 to 5910, `_ipp._tcp` confirms IPP on 631, `_ssh._tcp` confirms SSH, `_http._tcp` confirms HTTP, `_smb._tcp` confirms SMB on 445.

- [ ] **Step 1: Failing parser tests**

```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

class BannerParsersTest {
    @Test fun `ssh banner`() {
        val s = BannerParsers.ssh("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.4\r\n")!!
        assertEquals("SSH", s.name); assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version)
        assertEquals("Ubuntu-3ubuntu13.4", s.detail); assertEquals(Confidence.CONFIRMED, s.confidence)
        assertEquals("dropbear", BannerParsers.ssh("SSH-2.0-dropbear_2022.83")!!.product)
        assertNull(BannerParsers.ssh("220 hello"))
    }
    @Test fun `ftp and smtp greetings`() {
        val f = BannerParsers.greeting("220 ProFTPD 1.3.8 Server (Debian) [::ffff:10.0.0.5]", "FTP")!!
        assertEquals("FTP", f.name); assertEquals("ProFTPD", f.product); assertEquals("1.3.8", f.version)
        val s = BannerParsers.greeting("220 mail.example.com ESMTP Postfix (Ubuntu)", "SMTP")!!
        assertEquals("Postfix", s.product); assertNull(s.version)
        val v = BannerParsers.greeting("220 (vsFTPd 3.0.5)", "FTP")!!
        assertEquals("vsFTPd", v.product); assertEquals("3.0.5", v.version)
        assertNull(BannerParsers.greeting("", "FTP"))
    }
    @Test fun `http head response`() {
        val r = BannerParsers.httpHead("HTTP/1.1 401 Unauthorized\r\nServer: nginx/1.18.0 (Ubuntu)\r\nWWW-Authenticate: Basic realm=\"TP-LINK\"\r\n\r\n")!!
        assertEquals("HTTP", r.name); assertEquals("nginx", r.product); assertEquals("1.18.0", r.version)
        assertTrue(r.detail!!.contains("401")); assertTrue(r.detail!!.contains("TP-LINK"))
        assertEquals("Microsoft-IIS" to "10.0", BannerParsers.serverHeader("Microsoft-IIS/10.0"))
        assertEquals("lighttpd" to "1.4.59", BannerParsers.serverHeader("lighttpd/1.4.59"))
        assertEquals("Apache" to "2.4.52", BannerParsers.serverHeader("Apache/2.4.52 (Ubuntu)"))
        assertEquals("GoAhead-Webs" to null, BannerParsers.serverHeader("GoAhead-Webs"))
        assertNull(BannerParsers.httpHead("garbage"))
    }
    @Test fun `unknown bytes reclassify or keep printable excerpt`() {
        assertEquals("SSH", BannerParsers.classifyUnknown("SSH-2.0-OpenSSH_8.9\r\n".toByteArray(), "Unknown").name)
        assertEquals("HTTP", BannerParsers.classifyUnknown("HTTP/1.0 200 OK\r\n".toByteArray(), "Unknown").name)
        val bin = BannerParsers.classifyUnknown(byteArrayOf(0, 1, 2, 3, -1, -2), "Unknown")
        assertEquals("Unknown", bin.name); assertNull(bin.detail)
        val txt = BannerParsers.classifyUnknown("* OK Dovecot ready.\r\n".toByteArray(), "IMAP")
        assertEquals("IMAP", txt.name); assertEquals("* OK Dovecot ready.", txt.detail)
    }
    @Test fun `garbage never throws`() {
        for (junk in listOf("", "\u0000", "HTTP/", "SSH-", "220", "x".repeat(5000))) {
            BannerParsers.ssh(junk); BannerParsers.greeting(junk, "FTP"); BannerParsers.httpHead(junk); BannerParsers.serverHeader(junk)
            BannerParsers.classifyUnknown(junk.toByteArray(), "Unknown")
        }
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*BannerParsersTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement parsers**

`ServiceInfo.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

enum class Confidence { PORT_TABLE, CONFIRMED }
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null,
                       val detail: String? = null, val confidence: Confidence)
data class Evidence(val mdnsTypes: Set<String> = emptySet(), val upnpServer: String? = null)
```

`BannerParsers.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

object BannerParsers {
    private val versionRe = Regex("(\\d+(?:\\.\\d+)+[A-Za-z0-9]*)")

    fun ssh(line: String): ServiceInfo? {
        val l = line.trim()
        if (!l.startsWith("SSH-")) return null
        val rest = l.removePrefix("SSH-").substringAfter('-', "")  // after protocol version
        if (rest.isEmpty()) return ServiceInfo("SSH", confidence = Confidence.CONFIRMED)
        val soft = rest.substringBefore(' ')
        val comment = rest.substringAfter(' ', "").ifBlank { null }
        val product = soft.substringBefore('_').ifBlank { null }
        val version = soft.substringAfter('_', "").ifBlank { null }
        return ServiceInfo("SSH", product, version, comment, Confidence.CONFIRMED)
    }

    private val knownProducts = listOf("ProFTPD", "vsFTPd", "Pure-FTPd", "FileZilla", "Postfix", "Exim", "Sendmail", "Dovecot", "Courier", "Microsoft ESMTP", "Exchange", "Cyrus", "Synology")
    fun greeting(line: String, portName: String): ServiceInfo? {
        val l = line.trim()
        if (l.isEmpty() || !(l.startsWith("220") || l.startsWith("+OK") || l.startsWith("* OK") || l.startsWith("200"))) return null
        val product = knownProducts.firstOrNull { l.contains(it, ignoreCase = true) }
        val version = product?.let { Regex(Regex.escape(it) + "[ /_-]*v?" + versionRe.pattern, RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1) }
        return ServiceInfo(portName, product, version, l.take(120), Confidence.CONFIRMED)
    }

    fun serverHeader(value: String): Pair<String?, String?> {
        val v = value.trim()
        if (v.isEmpty()) return null to null
        val first = v.substringBefore(' ')
        val product = first.substringBefore('/').ifBlank { null }
        val version = first.substringAfter('/', "").ifBlank { null }
        return product to version
    }

    fun httpHead(response: String): ServiceInfo? {
        val lines = response.split("\r\n", "\n")
        val status = lines.firstOrNull()?.takeIf { it.startsWith("HTTP/") && it.length > 9 } ?: return null
        val headers = lines.drop(1).takeWhile { it.isNotBlank() }
            .mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim().lowercase() to h.substring(it + 1).trim() } }.toMap()
        val (product, version) = headers["server"]?.let { serverHeader(it) } ?: (null to null)
        val realm = headers["www-authenticate"]?.let { Regex("realm=\"?([^\"]+)\"?").find(it)?.groupValues?.get(1) }
        val detail = listOfNotNull(status.substringAfter(' ').take(40), realm?.let { "realm $it" }).joinToString(", ")
        return ServiceInfo("HTTP", product, version, detail, Confidence.CONFIRMED)
    }

    fun classifyUnknown(bytes: ByteArray, portName: String): ServiceInfo {
        val text = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrDefault("")
        if (text.startsWith("SSH-")) return ssh(text.lineSequence().first()) ?: ServiceInfo("SSH", confidence = Confidence.CONFIRMED)
        if (text.startsWith("HTTP/")) return httpHead(text) ?: ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)
        val printable = bytes.isNotEmpty() && bytes.all { b -> val c = b.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c in 32..126 }
        val excerpt = if (printable) text.lineSequence().firstOrNull()?.trim()?.take(120)?.ifBlank { null } else null
        return ServiceInfo(portName, detail = excerpt, confidence = if (excerpt != null) Confidence.CONFIRMED else Confidence.PORT_TABLE)
    }
}
```

- [ ] **Step 4: Run parser tests, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*BannerParsersTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Failing identifier tests (loopback fake servers)**

```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServiceIdentifierTest {
    private val servers = mutableListOf<ServerSocket>()
    private val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":80,"name":"HTTP"},{"port":5900,"name":"VNC"},{"port":21,"name":"FTP"}]""")
    private val lo = InetAddress.getLoopbackAddress()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    /** A one-shot server: optionally sends greeting, optionally reads a line then replies. */
    private fun serve(greeting: String? = null, reply: ((String) -> String)? = null): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) {
            runCatching {
                while (true) s.accept().use { c ->
                    greeting?.let { c.getOutputStream().write(it.toByteArray()); c.getOutputStream().flush() }
                    if (reply != null) {
                        val req = c.getInputStream().bufferedReader().readLine() ?: ""
                        c.getOutputStream().write(reply(req).toByteArray()); c.getOutputStream().flush()
                    }
                    Thread.sleep(50)
                }
            }
        }
        return s.localPort
    }
    private fun ident(port: Int, asPort: Int, ev: Evidence = Evidence()) = runBlocking {
        // identify() decides behaviour from the port number; tests remap by passing a reader bound to the real loopback port.
        ServiceIdentifier(table, BannerReader(timeoutMs = 800), portOverride = mapOf(asPort to port)).identify(lo, asPort, ev)
    }

    @Test fun `ssh server speaks first`() {
        val s = ident(serve(greeting = "SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n"), asPort = 22)
        assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version); assertEquals(Confidence.CONFIRMED, s.confidence)
    }
    @Test fun `http head returns server header`() {
        val p = serve(reply = { "HTTP/1.1 200 OK\r\nServer: lighttpd/1.4.59\r\nContent-Length: 0\r\n\r\n" })
        val s = ident(p, asPort = 80)
        assertEquals("HTTP", s.name); assertEquals("lighttpd", s.product); assertEquals("1.4.59", s.version)
    }
    @Test fun `silent port falls back to port table name`() {
        val s = ident(serve(), asPort = 5900)
        assertEquals("VNC", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `mdns type confirms the port table name`() {
        val s = ident(serve(), asPort = 5900, ev = Evidence(mdnsTypes = setOf("_rfb._tcp")))
        assertEquals("VNC", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("mDNS _rfb._tcp", s.detail)
    }
    @Test fun `ftp greeting parsed`() {
        val s = ident(serve(greeting = "220 (vsFTPd 3.0.5)\r\n"), asPort = 21)
        assertEquals("vsFTPd", s.product); assertEquals("3.0.5", s.version)
    }
}
```

- [ ] **Step 6: Implement reader, TLS peek and identifier**

`BannerReader.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.Probe
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress

class BannerReader(val timeoutMs: Int = 1000, val maxBytes: Int = 1024) {
    /** Connects, optionally sends [sendFirst], reads up to maxBytes or until timeout. Null when nothing readable. */
    suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? {
        val o = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return null
        val socket = o.socket ?: return null
        return try {
            runInterruptible {
                socket.soTimeout = timeoutMs
                sendFirst?.let { socket.getOutputStream().write(it); socket.getOutputStream().flush() }
                val buf = ByteArray(maxBytes); var n = 0
                val input = socket.getInputStream()
                val deadline = System.currentTimeMillis() + timeoutMs
                try {
                    while (n < maxBytes && System.currentTimeMillis() < deadline) {
                        val r = input.read(buf, n, maxBytes - n)
                        if (r < 0) break
                        n += r
                        if (sendFirst == null && n > 0 && buf.copyOf(n).contains('\n'.code.toByte())) break
                    }
                } catch (_: java.net.SocketTimeoutException) { }
                if (n == 0) null else buf.copyOf(n)
            }
        } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException()
        } catch (_: Exception) { null
        } finally { runCatching { socket.close() } }
    }
}
```

`TlsPeek.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Reads certificate names for identification only. Trust-all is never used for the browser launch. */
open class TlsPeek {
    open suspend fun certNames(ip: InetAddress, port: Int, timeoutMs: Int = 1500): List<String>? = try {
        runInterruptible {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(ip, port), timeoutMs); s.soTimeout = timeoutMs
            s.use {
                it.startHandshake()
                val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate ?: return@runInterruptible emptyList()
                val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)
                val sans = runCatching { cert.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
                (listOfNotNull(cn) + sans).distinct()
            }
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
```

`ServiceIdentifier.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import java.net.InetAddress

class ServiceIdentifier(
    private val table: PortTable,
    private val reader: BannerReader = BannerReader(),
    private val tls: TlsPeek = TlsPeek(),
    /** Test hook: logical port -> real port to connect to. Empty in production. */
    private val portOverride: Map<Int, Int> = emptyMap(),
) {
    companion object {
        val SPEAKS_FIRST = setOf(21, 22, 25, 110, 143, 587, 2222)
        val HTTP = setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400)
        val TLS = setOf(443, 8443, 7443, 8006, 5001)
        private val mdnsConfirms = mapOf("_rfb._tcp" to (5900..5910 to "VNC"), "_ipp._tcp" to (631..631 to "IPP"),
            "_ssh._tcp" to (22..22 to "SSH"), "_http._tcp" to (80..80 to "HTTP"), "_smb._tcp" to (445..445 to "SMB"))
    }

    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence()): ServiceInfo {
        val real = portOverride[port] ?: port
        val baseName = table.nameFor(port)
        val base = ServiceInfo(baseName, confidence = Confidence.PORT_TABLE)
        val fromBanner: ServiceInfo? = when {
            port in SPEAKS_FIRST -> reader.read(ip, real)?.let { b ->
                val line = String(b, Charsets.ISO_8859_1).lineSequence().first()
                BannerParsers.ssh(line) ?: BannerParsers.greeting(line, baseName) }
            port in HTTP -> reader.read(ip, real, "HEAD / HTTP/1.0\r\nHost: ${ip.hostAddress}\r\n\r\n".toByteArray())
                ?.let { BannerParsers.httpHead(String(it, Charsets.ISO_8859_1)) }
            port in TLS -> {
                val names = tls.certNames(ip, real)
                val http = reader.read(ip, real, null) // plain read yields nothing on TLS; keep for symmetry, ignored
                ServiceInfo("HTTPS", detail = names?.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
                    confidence = if (names != null) Confidence.CONFIRMED else Confidence.PORT_TABLE).also { http?.size }
            }
            else -> reader.read(ip, real)?.let { BannerParsers.classifyUnknown(it, baseName) }
        }
        val mdns = mdnsConfirms.entries.firstOrNull { (t, r) -> t in evidence.mdnsTypes && port in r.first }
        return when {
            fromBanner != null && fromBanner.confidence == Confidence.CONFIRMED -> fromBanner
            mdns != null -> ServiceInfo(mdns.value.second, detail = "mDNS ${mdns.key}", confidence = Confidence.CONFIRMED)
            fromBanner != null -> fromBanner
            evidence.upnpServer != null && port in HTTP -> base.copy(detail = evidence.upnpServer, confidence = Confidence.CONFIRMED)
            else -> base
        }
    }
}
```
Simplify the TLS branch while implementing: drop the unused plain `reader.read` call; the test suite does not cover TLS (no loopback TLS server), so keep the code path minimal and defensive.

- [ ] **Step 7: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*serviceid*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "Add banner parsers and service identification"
```

---

### Task 9: mDNS discovery via NsdManager with a resolve queue

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Mdns.kt`, `MdnsDiscovery.kt`
- Create: `app/src/main/res/raw/mdns_types.txt` (one service type per line, from spec Appendix B)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsResolveQueueTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class MdnsRecord(val ip: Inet4Address, val instanceName: String, val type: String, val port: Int, val txt: Map<String, String>, val ipv6: List<String>)
/** Abstracts NsdManager so the queue is unit-testable. */
interface NsdSource {
    fun discover(type: String, onFound: (serviceName: String, type: String) -> Unit, onLost: (String) -> Unit): AutoCloseable
    suspend fun resolve(serviceName: String, type: String): MdnsRecord?   // one at a time
}
class MdnsResolveQueue(private val source: NsdSource, private val perResolveTimeoutMs: Long = 3000) {
    fun run(types: List<String>, durationMs: Long): Flow<MdnsRecord> }
class AndroidNsdSource(context: Context) : NsdSource
```
- Behaviour: start `discover` for every type; each found (name, type) pair is enqueued once; resolves run strictly one at a time with a timeout each; `run` stops discovery after `durationMs` and completes once the queue drains or 2 s later, whichever first.

- [ ] **Step 1: Failing queue test with a fake source**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class MdnsResolveQueueTest {
    private class FakeSource(private val services: Map<String, List<String>>, private val resolveDelayMs: Long = 20) : NsdSource {
        var concurrentResolves = 0; var maxConcurrent = 0; val resolved = mutableListOf<String>()
        override fun discover(type: String, onFound: (String, String) -> Unit, onLost: (String) -> Unit): AutoCloseable {
            services[type].orEmpty().forEach { onFound(it, type) }
            services[type].orEmpty().firstOrNull()?.let { onFound(it, type) } // duplicate announce
            return AutoCloseable { }
        }
        override suspend fun resolve(serviceName: String, type: String): MdnsRecord? {
            synchronized(this) { concurrentResolves++; maxConcurrent = maxOf(maxConcurrent, concurrentResolves) }
            delay(resolveDelayMs)
            synchronized(this) { concurrentResolves--; resolved += serviceName }
            if (serviceName == "broken") return null
            return MdnsRecord(InetAddress.getByName("192.168.1.10") as Inet4Address, serviceName, type, 80, mapOf("model" to "X"), emptyList())
        }
    }

    @Test fun `resolves each found service once, serially`() = runBlocking {
        val src = FakeSource(mapOf("_http._tcp" to listOf("printer", "nas"), "_ssh._tcp" to listOf("nas-ssh", "broken")))
        val out = MdnsResolveQueue(src).run(listOf("_http._tcp", "_ssh._tcp"), durationMs = 200).toList()
        assertEquals(3, out.size)
        assertEquals(4, src.resolved.size)          // broken attempted once, no duplicate from the double announce
        assertEquals(1, src.maxConcurrent)
    }
    @Test fun `a hanging resolve is timed out and the queue continues`() = runBlocking {
        val src = object : NsdSource {
            override fun discover(type: String, onFound: (String, String) -> Unit, onLost: (String) -> Unit): AutoCloseable { onFound("hang", type); onFound("ok", type); return AutoCloseable { } }
            override suspend fun resolve(serviceName: String, type: String): MdnsRecord? {
                if (serviceName == "hang") { delay(60_000); return null }
                return MdnsRecord(InetAddress.getByName("192.168.1.11") as Inet4Address, serviceName, type, 22, emptyMap(), emptyList())
            }
        }
        val out = MdnsResolveQueue(src, perResolveTimeoutMs = 100).run(listOf("_ssh._tcp"), durationMs = 100).toList()
        assertEquals(listOf("ok"), out.map { it.instanceName })
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*MdnsResolveQueueTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`Mdns.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.net.Inet4Address

data class MdnsRecord(val ip: Inet4Address, val instanceName: String, val type: String, val port: Int,
                      val txt: Map<String, String>, val ipv6: List<String>)

interface NsdSource {
    fun discover(type: String, onFound: (serviceName: String, type: String) -> Unit, onLost: (String) -> Unit): AutoCloseable
    suspend fun resolve(serviceName: String, type: String): MdnsRecord?
}

class MdnsResolveQueue(private val source: NsdSource, private val perResolveTimeoutMs: Long = 3000) {
    fun run(types: List<String>, durationMs: Long): Flow<MdnsRecord> = channelFlow {
        val queue = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val seen = HashSet<Pair<String, String>>()
        val handles = types.map { t ->
            source.discover(t, onFound = { name, type -> val k = name to type; if (synchronized(seen) { seen.add(k) }) queue.trySend(k) }, onLost = { })
        }
        val resolver = launch {
            for ((name, type) in queue) {
                val rec = withTimeoutOrNull(perResolveTimeoutMs) { runCatching { source.resolve(name, type) }.getOrNull() }
                if (rec != null) send(rec)
            }
        }
        delay(durationMs)
        handles.forEach { runCatching { it.close() } }
        queue.close()
        withTimeoutOrNull(2000) { resolver.join() } ?: resolver.cancel()
    }
}
```

`MdnsDiscovery.kt` (Android side, not unit tested; exercised on device in Task 22):
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.coroutines.resume

class AndroidNsdSource(context: Context) : NsdSource {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun discover(type: String, onFound: (String, String) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { onFound(info.serviceName, info.serviceType.trimEnd('.')) }
            override fun onServiceLost(info: NsdServiceInfo) { onLost(info.serviceName) }
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        return AutoCloseable { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    override suspend fun resolve(serviceName: String, type: String): MdnsRecord? = suspendCancellableCoroutine { cont ->
        val info = NsdServiceInfo().apply { this.serviceName = serviceName; this.serviceType = type }
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val v4 = i.host as? Inet4Address
                val txt = i.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
                val v6 = listOfNotNull((i.host as? Inet6Address)?.hostAddress)
                if (cont.isActive) cont.resume(v4?.let { MdnsRecord(it, i.serviceName, i.serviceType.trimEnd('.'), i.port, txt, v6) })
            }
        })
    }
}
```
`mdns_types.txt`: the 30 types from spec Appendix B, one per line. Task 21 loads it.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*MdnsResolveQueueTest*' :app:assembleDebug --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add mDNS discovery with serial resolve queue"
```

---

### Task 10: SSDP search and UPnP description parsing

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Ssdp.kt`, `UpnpDescription.kt`
- Create fixtures: `app/src/test/resources/fixtures/ssdp_response_router.txt`, `upnp_desc_router.xml`, `upnp_desc_tv.xml`, `upnp_desc_garbage.xml`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SsdpTest.kt`, `UpnpDescriptionTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class SsdpResponse(val from: Inet4Address, val location: String?, val server: String?, val st: String?, val usn: String?)
object SsdpParser { fun parse(from: Inet4Address, text: String): SsdpResponse? }
class SsdpSearch(private val sendRepeats: Int = 3, private val listenMs: Long = 4000) { fun search(): Flow<SsdpResponse> }
data class UpnpDevice(val friendlyName: String?, val manufacturer: String?, val modelName: String?, val modelNumber: String?, val deviceType: String?, val udn: String?)
object UpnpDescriptionParser { fun parse(xml: String): UpnpDevice? }   // null on garbage, never throws
class UpnpFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) { suspend fun fetch(location: String, allowedHost: Inet4Address): UpnpDevice? }
object MacExtract { fun fromText(s: String): String? }   // first aa:bb:cc:dd:ee:ff or aabbccddeeff pattern as lower colon form
```
- `UpnpFetcher` refuses a `LOCATION` whose host is not `allowedHost` (the responding IP): a device must not redirect us elsewhere.

- [ ] **Step 1: Fixtures**

`ssdp_response_router.txt`:
```
HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
EXT:
LOCATION: http://192.168.1.1:1900/igd.xml
SERVER: Linux/3.10 UPnP/1.1 MiniUPnPd/2.1
ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1
USN: uuid:5c6f-1234-aa:bb:cc:dd:ee:ff::urn:schemas-upnp-org:device:InternetGatewayDevice:1

```
`upnp_desc_router.xml`:
```xml
<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
 <specVersion><major>1</major><minor>0</minor></specVersion>
 <device>
  <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
  <friendlyName>Archer AX55</friendlyName>
  <manufacturer>TP-Link</manufacturer>
  <modelName>Archer AX55</modelName>
  <modelNumber>1.0</modelNumber>
  <UDN>uuid:12345678-1234-1234-1234-aabbccddeeff</UDN>
  <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType><friendlyName>WANDevice</friendlyName></device></deviceList>
 </device>
</root>
```
`upnp_desc_tv.xml`: same shape with `deviceType` `urn:schemas-upnp-org:device:MediaRenderer:1`, `friendlyName` `[TV] Samsung Q80`, `manufacturer` `Samsung Electronics`, `modelName` `QE55Q80`.
`upnp_desc_garbage.xml`: 300 bytes of `<root><device><friendlyName>unterminated` followed by binary bytes.

- [ ] **Step 2: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SsdpTest {
    private fun fixture(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    private val from = InetAddress.getByName("192.168.1.1") as Inet4Address
    @Test fun `parses headers case-insensitively`() {
        val r = SsdpParser.parse(from, fixture("ssdp_response_router.txt"))!!
        assertEquals("http://192.168.1.1:1900/igd.xml", r.location)
        assertEquals("Linux/3.10 UPnP/1.1 MiniUPnPd/2.1", r.server)
        assertTrue(r.st!!.contains("InternetGatewayDevice")); assertTrue(r.usn!!.startsWith("uuid:"))
    }
    @Test fun `non 200 and garbage return null`() {
        assertNull(SsdpParser.parse(from, "NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(SsdpParser.parse(from, "")); assertNull(SsdpParser.parse(from, "\u0000\u0001garbage"))
    }
    @Test fun `mac extraction from usn and names`() {
        assertEquals("aa:bb:cc:dd:ee:ff", MacExtract.fromText("uuid:5c6f-1234-aa:bb:cc:dd:ee:ff::urn"))
        assertEquals("aa:bb:cc:dd:ee:ff", MacExtract.fromText("uuid:12345678-1234-1234-1234-aabbccddeeff"))
        assertEquals("00:11:22:33:44:55", MacExtract.fromText("raspberrypi [00:11:22:33:44:55]"))
        assertNull(MacExtract.fromText("Living Room TV")); assertNull(MacExtract.fromText("1234567890ab12")) // 14 hex: not a MAC
    }
}

class UpnpDescriptionTest {
    private fun fixture(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    @Test fun `router description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!
        assertEquals("TP-Link", d.manufacturer); assertEquals("Archer AX55", d.modelName); assertEquals("Archer AX55", d.friendlyName)
        assertTrue(d.deviceType!!.contains("InternetGatewayDevice")); assertEquals("uuid:12345678-1234-1234-1234-aabbccddeeff", d.udn)
    }
    @Test fun `takes the root device not the embedded one`() {
        assertEquals("Archer AX55", UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!.friendlyName)
    }
    @Test fun `tv description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_tv.xml"))!!
        assertEquals("Samsung Electronics", d.manufacturer); assertTrue(d.deviceType!!.contains("MediaRenderer"))
    }
    @Test fun `garbage yields null not an exception`() {
        assertNull(UpnpDescriptionParser.parse(fixture("upnp_desc_garbage.xml"))); assertNull(UpnpDescriptionParser.parse(""))
    }
}
```

- [ ] **Step 3: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*SsdpTest*' --tests '*UpnpDescriptionTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 4: Implement**

`Ssdp.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.net.*

data class SsdpResponse(val from: Inet4Address, val location: String?, val server: String?, val st: String?, val usn: String?)

object SsdpParser {
    fun parse(from: Inet4Address, text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().startsWith("HTTP/1.1 200")) return null
        val h = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        return SsdpResponse(from, h["location"], h["server"], h["st"], h["usn"])
    }
}

object MacExtract {
    private val colon = Regex("(?<![0-9a-fA-F:])([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})(?![0-9a-fA-F:])")
    private val bare = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{12})(?![0-9a-fA-F])")
    fun fromText(s: String): String? {
        colon.find(s)?.let { return it.groupValues[1].lowercase() }
        bare.find(s)?.let { m -> return m.groupValues[1].lowercase().chunked(2).joinToString(":") }
        return null
    }
}

class SsdpSearch(private val sendRepeats: Int = 3, private val listenMs: Long = 4000) {
    fun search(): Flow<SsdpResponse> = channelFlow {
        val group = InetAddress.getByName("239.255.255.250")
        val msearch = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
        val socket = MulticastSocket().apply { soTimeout = 500; broadcast = true }
        try {
            launch(Dispatchers.IO) {
                repeat(sendRepeats) { runCatching { socket.send(DatagramPacket(msearch, msearch.size, group, 1900)) }; delay(1000) }
            }
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + listenMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                val got = runInterruptible(Dispatchers.IO) { try { socket.receive(p); true } catch (_: SocketTimeoutException) { false } }
                if (!got) continue
                val from = p.address as? Inet4Address ?: continue
                val r = SsdpParser.parse(from, String(p.data, 0, p.length, Charsets.ISO_8859_1)) ?: continue
                if (seen.add("${from.hostAddress}|${r.usn}")) send(r)
            }
        } finally { runCatching { socket.close() } }
    }
}
```

`UpnpDescription.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

data class UpnpDevice(val friendlyName: String?, val manufacturer: String?, val modelName: String?,
                      val modelNumber: String?, val deviceType: String?, val udn: String?)

object UpnpDescriptionParser {
    fun parse(xml: String): UpnpDevice? = try {
        val p = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val f = HashMap<String, String>(); var depth = 0; var deviceDepth = -1; var inRootDevice = false; var done = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && !done) {
            when (ev) {
                XmlPullParser.START_TAG -> { depth++
                    if (p.name == "device" && deviceDepth == -1) { deviceDepth = depth; inRootDevice = true }
                    else if (p.name == "deviceList" && inRootDevice) { inRootDevice = false }
                    else if (inRootDevice && depth == deviceDepth + 1) { val n = p.name; val t = runCatching { p.nextText() }.getOrNull(); depth--; if (t != null) f.putIfAbsent(n, t.trim()) }
                }
                XmlPullParser.END_TAG -> { if (p.name == "device" && depth == deviceDepth) done = true; depth-- }
            }
            ev = p.next()
        }
        if (f.isEmpty()) null else UpnpDevice(f["friendlyName"], f["manufacturer"], f["modelName"], f["modelNumber"], f["deviceType"], f["UDN"])
    } catch (_: Exception) { null }
}

class UpnpFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    suspend fun fetch(location: String, allowedHost: Inet4Address): UpnpDevice? = try {
        val url = URL(location)
        if (url.host != allowedHost.hostAddress) return null
        runInterruptible(Dispatchers.IO) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
            c.inputStream.use { ins ->
                val bytes = ins.readNBytes(maxBytes)
                UpnpDescriptionParser.parse(String(bytes, Charsets.UTF_8))
            }
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
```
`readNBytes` needs API 33 on Android; on the JVM tests it exists. Replace with a manual bounded read loop (`ByteArrayOutputStream`, 8 KB buffer, stop at `maxBytes`) so it works on minSdk 26.

- [ ] **Step 5: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*SsdpTest*' --tests '*UpnpDescriptionTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add SSDP search, UPnP description parsing and MAC extraction"
```

---

### Task 11: NetBIOS node-status query

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/NetBios.kt`
- Create fixture: `app/src/test/resources/fixtures/nbstat_response.bin` (built by the test from a hex string, so no binary file in git)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/NetBiosTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class NetBiosInfo(val computerName: String?, val workgroup: String?, val mac: String?)
object NetBios {
    fun buildNodeStatusRequest(txId: Int = 0x1234): ByteArray
    fun parseNodeStatus(resp: ByteArray): NetBiosInfo?     // null on garbage
    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): NetBiosInfo? }
```
- Wire format (RFC 1002 NBSTAT): header 12 bytes (`txId`, flags 0x0000, QDCOUNT 1), question name = encoded `*` padded with 0x00 to 16 bytes (`0x20` then 32 bytes of first-level encoding: `CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`, then `0x00`), QTYPE 0x0021, QCLASS 0x0001. Response: skip header and question (echoed), then RR: name (0x20 + 32 + 0x00), type, class, TTL (4), RDLENGTH (2), NUM_NAMES (1), then NUM_NAMES × 18 bytes (15 name bytes, 1 suffix, 2 flags), then statistics starting with the 6-byte unit ID (MAC).
- Computer name = first entry with suffix `0x00` and group flag clear (flags bit 15 = 0x8000 off). Workgroup = first entry with suffix `0x00` and group flag set. Trim trailing spaces. Skip names starting with `\u0001\u0002__MSBROWSE__`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class NetBiosTest {
    private fun hex(s: String) = s.replace(Regex("\\s"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun name16(n: String, suffix: Int) = n.padEnd(15).toByteArray(Charsets.US_ASCII).map { "%02x".format(it) }.joinToString("") + "%02x".format(suffix)

    // Header: txid 1234, flags 8400 (response, AA), qd 0, an 1, ns 0, ar 0. Then RR for "*".
    private val response = hex(
        "1234 8400 0000 0001 0000 0000" +
        "20" + "43 4b" + "41".repeat(30) + "00" +   // name: CK + 30 A's
        "0021 0001 00000000" +
        "0053" +                                      // RDLENGTH: 1 + 3*18 + 46 = 101 -> but we only need the first bytes parsed
        "03" +
        name16("DESKTOP-ABC", 0x00) + "0400" +        // unique, active
        name16("WORKGROUP", 0x00) + "8400" +          // group
        name16("DESKTOP-ABC", 0x20) + "0400" +        // file server service
        "001122334455" + "00".repeat(40))             // unit id (MAC) + stats

    @Test fun `request has the right length and query type`() {
        val r = NetBios.buildNodeStatusRequest(0x1234)
        assertEquals(50, r.size)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x20, r[12].toInt()); assertEquals(0x43, r[13].toInt()); assertEquals(0x4b, r[14].toInt())
        assertEquals(0x00, r[46].toInt()); assertEquals(0x21, r[47].toInt()); assertEquals(0x01, r[49].toInt())
    }
    @Test fun `parses names and mac`() {
        val i = NetBios.parseNodeStatus(response)!!
        assertEquals("DESKTOP-ABC", i.computerName); assertEquals("WORKGROUP", i.workgroup); assertEquals("00:11:22:33:44:55", i.mac)
    }
    @Test fun `all zero mac is reported as null`() {
        val r = response.copyOf(); for (k in 0 until 6) r[r.size - 46 + k] = 0
        assertNull(NetBios.parseNodeStatus(r)!!.mac)
    }
    @Test fun `truncated and garbage input return null`() {
        assertNull(NetBios.parseNodeStatus(response.copyOf(60)))
        assertNull(NetBios.parseNodeStatus(ByteArray(0)))
        assertNull(NetBios.parseNodeStatus(ByteArray(200) { 0x7f }))
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*NetBiosTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.nio.ByteBuffer

data class NetBiosInfo(val computerName: String?, val workgroup: String?, val mac: String?)

object NetBios {
    fun buildNodeStatusRequest(txId: Int = 0x1234): ByteArray {
        val b = ByteBuffer.allocate(50)
        b.putShort(txId.toShort()); b.putShort(0); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0)
        b.put(0x20)
        // "*" followed by fifteen 0x00 bytes, first-level encoded: each nibble + 'A'
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (x in raw) { val v = x.toInt() and 0xff; b.put(('A'.code + (v shr 4)).toByte()); b.put(('A'.code + (v and 0x0f)).toByte()) }
        b.put(0); b.putShort(0x21); b.putShort(1)
        return b.array()
    }

    fun parseNodeStatus(resp: ByteArray): NetBiosInfo? = try {
        if (resp.size < 12) return null
        val b = ByteBuffer.wrap(resp)
        b.position(2); val flags = b.short.toInt() and 0xffff
        if (flags and 0x8000 == 0) return null                      // not a response
        b.position(12)
        // RR name: length-prefixed labels until 0
        while (true) { val l = b.get().toInt() and 0xff; if (l == 0) break; b.position(b.position() + l) }
        val type = b.short.toInt() and 0xffff; if (type != 0x21) return null
        b.short; b.int; b.short                                       // class, ttl, rdlength
        val n = b.get().toInt() and 0xff
        if (n == 0 || n > 64 || b.remaining() < n * 18 + 6) return null
        var computer: String? = null; var group: String? = null
        repeat(n) {
            val nameBytes = ByteArray(15); b.get(nameBytes); val suffix = b.get().toInt() and 0xff; val f = b.short.toInt() and 0xffff
            val name = String(nameBytes, Charsets.ISO_8859_1).trimEnd()
            if (name.startsWith("\u0001\u0002") || suffix != 0x00) return@repeat
            if (f and 0x8000 != 0) { if (group == null) group = name } else if (computer == null) computer = name
        }
        val mac = ByteArray(6); b.get(mac)
        val macStr = mac.joinToString(":") { "%02x".format(it) }.takeUnless { it == "00:00:00:00:00:00" }
        NetBiosInfo(computer, group, macStr)
    } catch (_: Exception) { null }

    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): NetBiosInfo? = try {
        runInterruptible(Dispatchers.IO) {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val req = buildNodeStatusRequest()
                s.send(DatagramPacket(req, req.size, ip, 137))
                val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                parseNodeStatus(buf.copyOf(p.length))
            }
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*NetBiosTest*' --console=plain 2>&1 | tail -15
```
If the `all zero mac` test's offset arithmetic fails, compute the MAC offset from the parsed position instead of from the end: the test's intent is only that a zero MAC becomes null.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add NetBIOS node status query and parser"
```

---

### Task 12: SNMP sysDescr probe (hand-written BER)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Snmp.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SnmpTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class SnmpInfo(val sysDescr: String?, val sysName: String?, val sysObjectId: String?)
object Snmp {
    fun buildGet(community: String = "public", requestId: Int = 0x5a5a): ByteArray  // v2c GET for 1.3.6.1.2.1.1.1.0, .5.0, .2.0
    fun parseResponse(bytes: ByteArray): SnmpInfo?                                    // null on garbage or SNMP error
    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): SnmpInfo? }
```
- BER needed: SEQUENCE 0x30, INTEGER 0x02, OCTET STRING 0x04, NULL 0x05, OID 0x06, GetRequest 0xa0, GetResponse 0xa2; lengths in short and long form (0x81, 0x82). Write a tiny `Ber` encoder/decoder inside the file.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class SnmpTest {
    private fun hex(s: String) = s.replace(Regex("\\s"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `get request encodes version community and three oids`() {
        val r = Snmp.buildGet("public", 0x5a5a)
        assertEquals(0x30, r[0].toInt())
        val text = String(r, Charsets.ISO_8859_1)
        assertTrue(text.contains("public"))
        // OID 1.3.6.1.2.1.1.1.0 encodes as 06 08 2b 06 01 02 01 01 01 00
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 1, 0) })
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 5, 0) })
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 2, 0) })
    }

    // A real-shaped GetResponse: sysDescr "RouterOS RB750Gr3", sysName "gw", sysObjectID 1.3.6.1.4.1.14988.1
    private val response = hex(
        "30 5d 02 01 01 04 06 70 75 62 6c 69 63 a2 50 02 02 5a 5a 02 01 00 02 01 00 30 44" +
        "30 15 06 08 2b 06 01 02 01 01 01 00 04 09 52 6f 75 74 65 72 4f 53 20" +      // (truncated descr for brevity)
        "30 0e 06 08 2b 06 01 02 01 01 05 00 04 02 67 77" +
        "30 1b 06 08 2b 06 01 02 01 01 02 00 06 0f 2b 06 01 04 01 81 f5 1c 01" )

    @Test fun `parses a get response`() {
        val built = Snmp.encodeResponseForTest(requestId = 0x5a5a, descr = "RouterOS RB750Gr3", name = "gw", oid = intArrayOf(1,3,6,1,4,1,14988,1))
        val i = Snmp.parseResponse(built)!!
        assertEquals("RouterOS RB750Gr3", i.sysDescr); assertEquals("gw", i.sysName); assertEquals("1.3.6.1.4.1.14988.1", i.sysObjectId)
    }
    @Test fun `long form lengths are handled`() {
        val long = "x".repeat(300)
        val i = Snmp.parseResponse(Snmp.encodeResponseForTest(1, long, "n", intArrayOf(1,3,6,1)))!!
        assertEquals(300, i.sysDescr!!.length)
    }
    @Test fun `error status yields null`() {
        val b = Snmp.encodeResponseForTest(1, "d", "n", intArrayOf(1,3,6,1), errorStatus = 2)
        assertNull(Snmp.parseResponse(b))
    }
    @Test fun `garbage and truncation yield null`() {
        val good = Snmp.encodeResponseForTest(1, "RouterOS", "gw", intArrayOf(1,3,6,1))
        assertNull(Snmp.parseResponse(good.copyOf(good.size / 2)))
        assertNull(Snmp.parseResponse(ByteArray(0))); assertNull(Snmp.parseResponse(ByteArray(64) { -1 }))
        assertNull(Snmp.parseResponse(response.copyOf(20)))
    }
}
```
`encodeResponseForTest` is a real encoder in the main source (it reuses the same `Ber` writer as `buildGet`), which is why the tests trust it: the request encoder and the test response encoder share one code path that the OID window assertions check byte-for-byte.

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*SnmpTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address

data class SnmpInfo(val sysDescr: String?, val sysName: String?, val sysObjectId: String?)

private object Ber {
    fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }
    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun int(v: Int): ByteArray { // minimal two's complement
        var bytes = ByteArray(4) { ((v shr (8 * (3 - it))) and 0xff).toByte() }
        while (bytes.size > 1 && ((bytes[0].toInt() == 0 && bytes[1].toInt() and 0x80 == 0) || (bytes[0].toInt() == -1 && bytes[1].toInt() and 0x80 != 0))) bytes = bytes.copyOfRange(1, bytes.size)
        return tlv(0x02, bytes)
    }
    fun str(s: String) = tlv(0x04, s.toByteArray(Charsets.UTF_8))
    fun nul() = byteArrayOf(0x05, 0x00)
    fun oid(parts: IntArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) { var v = parts[i]; val stack = ArrayList<Int>(); do { stack.add(v and 0x7f); v = v ushr 7 } while (v > 0)
            for (k in stack.indices.reversed()) out.write(stack[k] or (if (k > 0) 0x80 else 0)) }
        return tlv(0x06, out.toByteArray())
    }
    fun seq(vararg items: ByteArray) = tlv(0x30, items.fold(ByteArray(0)) { a, b -> a + b })

    class Reader(private val b: ByteArray, var pos: Int = 0) {
        fun tag(): Int = b[pos++].toInt() and 0xff
        fun length(): Int { val f = b[pos++].toInt() and 0xff; if (f < 0x80) return f
            val n = f and 0x7f; require(n in 1..3) ; var v = 0; repeat(n) { v = (v shl 8) or (b[pos++].toInt() and 0xff) }; return v }
        fun bytes(n: Int): ByteArray { require(pos + n <= b.size); val r = b.copyOfRange(pos, pos + n); pos += n; return r }
        fun int(): Int { require(tag() == 0x02); val n = length(); var v = 0; for (x in bytes(n)) v = (v shl 8) or (x.toInt() and 0xff); return v }
        fun oidString(content: ByteArray): String { val parts = ArrayList<Int>(); parts += (content[0].toInt() and 0xff) / 40; parts += (content[0].toInt() and 0xff) % 40
            var v = 0; for (i in 1 until content.size) { val c = content[i].toInt() and 0xff; v = (v shl 7) or (c and 0x7f); if (c and 0x80 == 0) { parts += v; v = 0 } }
            return parts.joinToString(".") }
    }
}

object Snmp {
    private val OID_DESCR = intArrayOf(1, 3, 6, 1, 2, 1, 1, 1, 0)
    private val OID_OBJID = intArrayOf(1, 3, 6, 1, 2, 1, 1, 2, 0)
    private val OID_NAME = intArrayOf(1, 3, 6, 1, 2, 1, 1, 5, 0)

    fun buildGet(community: String = "public", requestId: Int = 0x5a5a): ByteArray {
        val varbinds = Ber.seq(Ber.seq(Ber.oid(OID_DESCR), Ber.nul()), Ber.seq(Ber.oid(OID_NAME), Ber.nul()), Ber.seq(Ber.oid(OID_OBJID), Ber.nul()))
        val pdu = Ber.tlv(0xa0, Ber.int(requestId) + Ber.int(0) + Ber.int(0) + varbinds)
        return Ber.seq(Ber.int(1), Ber.str(community), pdu)
    }

    /** Test helper that shares the encoder; produces a v2c GetResponse. */
    fun encodeResponseForTest(requestId: Int, descr: String, name: String, oid: IntArray, errorStatus: Int = 0): ByteArray {
        val varbinds = Ber.seq(Ber.seq(Ber.oid(OID_DESCR), Ber.str(descr)), Ber.seq(Ber.oid(OID_NAME), Ber.str(name)), Ber.seq(Ber.oid(OID_OBJID), Ber.oid(oid)))
        val pdu = Ber.tlv(0xa2, Ber.int(requestId) + Ber.int(errorStatus) + Ber.int(0) + varbinds)
        return Ber.seq(Ber.int(1), Ber.str("public"), pdu)
    }

    fun parseResponse(bytes: ByteArray): SnmpInfo? = try {
        val r = Ber.Reader(bytes)
        require(r.tag() == 0x30); val total = r.length(); require(total <= bytes.size - r.pos)
        r.int()                                   // version
        require(r.tag() == 0x04); r.bytes(r.length())   // community
        require(r.tag() == 0xa2); r.length()
        r.int()                                   // request id
        val errorStatus = r.int(); r.int()        // error status, error index
        if (errorStatus != 0) return null
        require(r.tag() == 0x30); val vbEnd = r.pos + r.length().also { require(it <= bytes.size - r.pos) }
        var descr: String? = null; var name: String? = null; var objId: String? = null
        while (r.pos < vbEnd) {
            require(r.tag() == 0x30); r.length()
            require(r.tag() == 0x06); val oid = r.oidString(r.bytes(r.length()))
            val t = r.tag(); val n = r.length(); val content = r.bytes(n)
            val value = when (t) { 0x04 -> String(content, Charsets.UTF_8); 0x06 -> r.oidString(content); else -> null }
            when (oid) { "1.3.6.1.2.1.1.1.0" -> descr = value; "1.3.6.1.2.1.1.5.0" -> name = value; "1.3.6.1.2.1.1.2.0" -> objId = value }
        }
        if (descr == null && name == null && objId == null) null else SnmpInfo(descr, name, objId)
    } catch (_: Exception) { null }

    suspend fun query(ip: Inet4Address, timeoutMs: Int = 1000): SnmpInfo? = try {
        runInterruptible(Dispatchers.IO) {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val req = buildGet(); s.send(DatagramPacket(req, req.size, ip, 161))
                val buf = ByteArray(4096); val p = DatagramPacket(buf, buf.size); s.receive(p)
                parseResponse(buf.copyOf(p.length))
            }
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*SnmpTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add SNMP sysDescr probe with hand-written BER"
```

---

### Task 13: HTTP title fetcher

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitle.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitleTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class HttpPeek(val title: String?, val server: String?, val authRealm: String?, val status: Int)
object HttpTitleParser { fun title(html: String): String?; fun realm(wwwAuthenticate: String?): String? }
class HttpTitleFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? }   // follows one same-host redirect
```
- `title` decodes the common HTML entities (`&amp; &lt; &gt; &quot; &#39; &nbsp;`), collapses whitespace, trims, caps at 120 chars, returns null when blank. For TLS the fetcher uses the same trust-all context as `TlsPeek` and only for reading the page.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpTitleTest {
    @Test fun `title parsing`() {
        assertEquals("Archer AX55 Login", HttpTitleParser.title("<html><head><TITLE>\n  Archer   AX55 &amp; Login </TITLE></head>"))
        assertNull(HttpTitleParser.title("<html><head></head><body>no title</body>"))
        assertNull(HttpTitleParser.title("<title>   </title>"))
        assertEquals(120, HttpTitleParser.title("<title>" + "x".repeat(500) + "</title>")!!.length)
        assertEquals("TP-LINK", HttpTitleParser.realm("Basic realm=\"TP-LINK\""))
        assertEquals("router", HttpTitleParser.realm("Digest realm=router, qop=auth"))
        assertNull(HttpTitleParser.realm(null))
    }

    private val servers = mutableListOf<ServerSocket>()
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }
    private fun serve(body: (String) -> String): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { while (true) s.accept().use { c ->
            val first = c.getInputStream().bufferedReader().readLine() ?: ""
            c.getOutputStream().write(body(first).toByteArray(Charsets.ISO_8859_1)); c.getOutputStream().flush() } } }
        return s.localPort
    }
    @Test fun `fetches title server and realm`() = runBlocking {
        val p = serve { "HTTP/1.1 401 Unauthorized\r\nServer: Boa/0.94\r\nWWW-Authenticate: Basic realm=\"NETGEAR R7000\"\r\nContent-Length: 40\r\n\r\n<html><title>Netgear Router</title></html>" }
        val r = HttpTitleFetcher().fetch(lo, p, tls = false)!!
        assertEquals("Netgear Router", r.title); assertEquals("Boa/0.94", r.server); assertEquals("NETGEAR R7000", r.authRealm); assertEquals(401, r.status)
    }
    @Test fun `follows one same host redirect`() = runBlocking {
        lateinit var portHolder: IntArray
        val p = serve { first -> if (first.startsWith("GET / ")) "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${portHolder[0]}/login\r\nContent-Length: 0\r\n\r\n"
                                 else "HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\n<title>Login page here</title>" }
        portHolder = intArrayOf(p)
        assertEquals("Login page here", HttpTitleFetcher().fetch(lo, p, tls = false)!!.title)
    }
    @Test fun `oversized body is capped and non utf8 bytes do not throw`() = runBlocking {
        val p = serve { "HTTP/1.1 200 OK\r\n\r\n<title>ÿþ café</title>" + "y".repeat(200_000) }
        val r = HttpTitleFetcher(maxBytes = 4096).fetch(lo, p, tls = false)!!
        assertNotNull(r.title)
    }
    @Test fun `closed port returns null`() = runBlocking {
        val closed = ServerSocket(0).also { it.close() }.localPort
        assertNull(HttpTitleFetcher(timeoutMs = 500).fetch(lo, closed, tls = false))
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*HttpTitleTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.*

data class HttpPeek(val title: String?, val server: String?, val authRealm: String?, val status: Int)

object HttpTitleParser {
    private val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val entities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
    fun title(html: String): String? {
        var t = titleRe.find(html)?.groupValues?.get(1) ?: return null
        entities.forEach { (k, v) -> t = t.replace(k, v, ignoreCase = true) }
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.takeIf { it.isNotEmpty() }?.take(120)
    }
    fun realm(wwwAuthenticate: String?): String? = wwwAuthenticate?.let { Regex("realm=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
}

class HttpTitleFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
    private val trustAll: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>() }), null) }.socketFactory
    }

    suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? = try {
        runInterruptible(Dispatchers.IO) {
            var url = URL(if (tls) "https" else "http", ip.hostAddress, port, "/")
            var hops = 0
            while (true) {
                val c = url.openConnection() as HttpURLConnection
                if (c is HttpsURLConnection) { c.sslSocketFactory = trustAll; c.hostnameVerifier = HostnameVerifier { _, _ -> true } }
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "NetProbe/0.1"); c.setRequestProperty("Accept", "text/html")
                val status = c.responseCode
                val loc = c.getHeaderField("Location")
                if (status in 300..399 && loc != null && hops == 0) {
                    val next = runCatching { URL(url, loc) }.getOrNull()
                    if (next != null && next.host == url.host) { url = next; hops++; c.disconnect(); continue }
                }
                val stream = (if (status >= 400) c.errorStream else c.inputStream) ?: c.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { ins -> val buf = ByteArray(8192); var n: Int
                    while (bytes.size() < maxBytes && ins.read(buf).also { n = it } > 0) bytes.write(buf, 0, minOf(n, maxBytes - bytes.size())) }
                val html = String(bytes.toByteArray(), Charsets.ISO_8859_1)
                return@runInterruptible HttpPeek(HttpTitleParser.title(html), c.getHeaderField("Server"), HttpTitleParser.realm(c.getHeaderField("WWW-Authenticate")), status)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    } catch (e: InterruptedException) { throw kotlinx.coroutines.CancellationException() } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*HttpTitleTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add HTTP title fetcher"
```

---

### Task 14: Reverse DNS and IPv6 EUI-64 MAC derivation

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ReverseDns.kt`, `Eui64.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/Eui64Test.kt`, `ReverseDnsTest.kt`

**Interfaces:**
- Produces:
```kotlin
object Eui64 { fun macFromLinkLocal(ipv6: String): String? }   // "fe80::211:22ff:fe33:4455" -> "00:11:22:33:44:55"; null when not EUI-64
class ReverseDns(private val timeoutMs: Long = 1000) { suspend fun lookup(ip: Inet4Address): String? }  // null when name == ip string or timeout
```
- EUI-64 rule: interface identifier (last 64 bits) has `ff:fe` at bytes 3 and 4; MAC = bytes 0..2 with bit 1 of byte 0 flipped (universal/local), then bytes 5..7. Only `fe80::/10` addresses are considered.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class Eui64Test {
    @Test fun `derives mac from eui64 link local`() {
        assertEquals("00:11:22:33:44:55", Eui64.macFromLinkLocal("fe80::211:22ff:fe33:4455"))
        assertEquals("b8:27:eb:12:34:56", Eui64.macFromLinkLocal("fe80::ba27:ebff:fe12:3456%wlan0"))
    }
    @Test fun `privacy addresses and non link local return null`() {
        assertNull(Eui64.macFromLinkLocal("fe80::1c2d:3e4f:5a6b:7c8d"))
        assertNull(Eui64.macFromLinkLocal("2a00:1450:4009:81f::200e"))
        assertNull(Eui64.macFromLinkLocal("not an address")); assertNull(Eui64.macFromLinkLocal(""))
    }
}

class ReverseDnsTest {
    @Test fun `loopback resolves or returns null without throwing and respects timeout`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = ReverseDns(timeoutMs = 1500).lookup(InetAddress.getByName("192.0.2.123") as Inet4Address)
        assertTrue(System.currentTimeMillis() - t0 < 2500)
        if (r != null) assertNotEquals("192.0.2.123", r)
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*Eui64Test*' --tests '*ReverseDnsTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`Eui64.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet6Address
import java.net.InetAddress

object Eui64 {
    fun macFromLinkLocal(ipv6: String): String? = try {
        val a = InetAddress.getByName(ipv6.substringBefore('%')) as? Inet6Address ?: return null
        if (!a.isLinkLocalAddress) return null
        val b = a.address
        if ((b[11].toInt() and 0xff) != 0xff || (b[12].toInt() and 0xff) != 0xfe) return null
        val mac = byteArrayOf((b[8].toInt() xor 0x02).toByte(), b[9], b[10], b[13], b[14], b[15])
        mac.joinToString(":") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
```
`ReverseDns.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

class ReverseDns(private val timeoutMs: Long = 1000) {
    suspend fun lookup(ip: Inet4Address): String? = withTimeoutOrNull(timeoutMs) {
        runCatching { runInterruptible(Dispatchers.IO) { ip.canonicalHostName } }.getOrNull()
    }?.takeUnless { it == ip.hostAddress || it.isBlank() }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*Eui64Test*' --tests '*ReverseDnsTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add reverse DNS lookup and EUI-64 MAC derivation"
```

---

### Task 15: Discovery engine (orchestration and merge)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveredHost.kt`, `DiscoveryEngine.kt`, `DiscoveryOptions.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineTest.kt`

**Interfaces:**
- Consumes: `Sweep`, `HostProber` (Task 7), `MdnsResolveQueue`/`NsdSource` (Task 9), `SsdpSearch`, `UpnpFetcher`, `MacExtract` (Task 10), `NetBios` (11), `Snmp` (12), `HttpTitleFetcher` (13), `ReverseDns`, `Eui64` (14), `NeighbourTable` (2), `SubnetMath.sweepRange` (3), `NetworkEnv` (4).
- Produces:
```kotlin
enum class Source { SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP, GATEWAY }
data class DiscoveredHost(
    val ip: Inet4Address, val mac: String? = null, val reverseDnsName: String? = null, val mdnsName: String? = null,
    val netbiosName: String? = null, val netbiosWorkgroup: String? = null,
    val upnpFriendlyName: String? = null, val upnpManufacturer: String? = null, val upnpModel: String? = null, val upnpDeviceType: String? = null, val upnpServer: String? = null,
    val mdnsServiceTypes: Set<String> = emptySet(), val mdnsTxt: Map<String, Map<String, String>> = emptyMap(), val mdnsInstanceNames: Set<String> = emptySet(),
    val ipv6LinkLocal: Set<String> = emptySet(), val snmpSysDescr: String? = null, val snmpSysName: String? = null, val snmpSysObjectId: String? = null,
    val httpTitle: String? = null, val httpServer: String? = null, val httpAuthRealm: String? = null,
    val probedOpenPorts: Set<Int> = emptySet(), val sources: Set<Source> = emptySet(), val isGateway: Boolean = false,
) { fun merge(other: DiscoveredHost): DiscoveredHost }   // field-wise: non-null wins, sets union, maps union, mac: first non-null
data class DiscoveryOptions(val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
                            val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000)
data class DiscoveryStatus(val probed: Int, val total: Int, val found: Int, val clampedFrom: Int?, val neighbourAvailable: Boolean?)
/** Every external dependency is injectable so the engine is tested with fakes. */
class DiscoveryEngine(
    val sweepFactory: (HostProber, Int) -> Sweep, val mdns: (List<String>, Long) -> Flow<MdnsRecord>, val ssdp: () -> Flow<SsdpResponse>,
    val upnpFetch: suspend (String, Inet4Address) -> UpnpDevice?, val netbios: suspend (Inet4Address) -> NetBiosInfo?,
    val snmp: suspend (Inet4Address) -> SnmpInfo?, val httpTitle: suspend (Inet4Address, Int, Boolean) -> HttpPeek?,
    val reverseDns: suspend (Inet4Address) -> String?, val neighbours: () -> Result<List<NeighbourEntry>>, val mdnsTypes: List<String>,
) {
    fun discover(env: NetworkEnv, options: DiscoveryOptions): Flow<DiscoveredHost>   // re-emits merged hosts; consumers upsert by ip
    val status: StateFlow<DiscoveryStatus>
    companion object { fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine }  // wires real implementations (Task 21 calls it)
}
```
- Order inside `discover`: emit the gateway immediately with `Source.GATEWAY`; start mDNS and SSDP concurrently with the sweep; for every alive host launch enrichment (NetBIOS, reverse DNS, SNMP, HTTP title when 80/443/8080/8443 probed open) bounded by a `Semaphore(32)`; when the sweep ends, run the neighbour dump once and merge MACs; apply `MacExtract` to UPnP `USN`/UDN and mDNS instance names and `Eui64` to link-local addresses as secondary MAC sources (only when `mac` is still null). Complete when all strategies finish.

- [ ] **Step 1: Failing tests with fakes**

```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DiscoveryEngineTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env = NetworkEnv(ip("192.168.1.50"), 24, ip("192.168.1.1"), emptyList(), "Home", null, Transport.WIFI)

    /** Fake prober: hosts in [alive] answer; everything else is dead. */
    private fun engine(alive: Map<String, Set<Int>>, mdns: List<MdnsRecord> = emptyList(), ssdp: List<SsdpResponse> = emptyList(),
                       neigh: Result<List<NeighbourEntry>> = Result.success(emptyList()), netbios: Map<String, NetBiosInfo> = emptyMap()) = DiscoveryEngine(
        sweepFactory = { _, _ -> object : Sweep(HostProber(), 8) {
            override fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = hosts.toList().filter { it.hostAddress in alive }
                .map { Liveness(it, true, alive[it.hostAddress]!!, 3) }.asFlow() } },
        mdns = { _, _ -> mdns.asFlow() }, ssdp = { ssdp.asFlow() },
        upnpFetch = { loc, _ -> if (loc.contains("igd")) UpnpDevice("Archer AX55", "TP-Link", "Archer AX55", null, "urn:schemas-upnp-org:device:InternetGatewayDevice:1", "uuid:1-2-3-4-aabbccddeeff") else null },
        netbios = { netbios[it.hostAddress] }, snmp = { if (it.hostAddress == "192.168.1.1") SnmpInfo("RouterOS", "gw", null) else null },
        httpTitle = { ip, port, _ -> if (port == 80 && ip.hostAddress == "192.168.1.1") HttpPeek("Router Login", "Boa", null, 200) else null },
        reverseDns = { if (it.hostAddress == "192.168.1.20") "desktop.lan" else null }, neighbours = { neigh }, mdnsTypes = listOf("_http._tcp"),
    )

    @Test fun `gateway is emitted first even if it drops probes`() = runBlocking {
        val first = engine(alive = emptyMap()).discover(env, DiscoveryOptions()).first()
        assertEquals("192.168.1.1", first.ip.hostAddress); assertTrue(first.isGateway); assertTrue(Source.GATEWAY in first.sources)
    }
    @Test fun `hosts from different sources merge by ip`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.20"), "Desktop", "_http._tcp", 80, mapOf("model" to "MacBookPro18,3"), listOf("fe80::211:22ff:fe33:4455")))
        val ssdp = listOf(SsdpResponse(ip("192.168.1.1"), "http://192.168.1.1:1900/igd.xml", "MiniUPnPd/2.1", "urn:x", "uuid:1-2-3-4-aabbccddeeff::urn:x"))
        val all = engine(alive = mapOf("192.168.1.1" to setOf(80), "192.168.1.20" to setOf(445)), mdns = mdns, ssdp = ssdp,
            netbios = mapOf("192.168.1.20" to NetBiosInfo("DESKTOP-ABC", "WORKGROUP", "00:11:22:33:44:55"))).discover(env, DiscoveryOptions()).toList()
        val byIp = all.groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        val gw = byIp["192.168.1.1"]!!; val pc = byIp["192.168.1.20"]!!
        assertEquals("TP-Link", gw.upnpManufacturer); assertEquals("Archer AX55", gw.upnpModel); assertEquals("RouterOS", gw.snmpSysDescr)
        assertEquals("Router Login", gw.httpTitle); assertEquals("aa:bb:cc:dd:ee:ff", gw.mac)              // from UDN
        assertEquals(setOf(Source.GATEWAY, Source.SWEEP, Source.SSDP, Source.SNMP, Source.HTTP), gw.sources)
        assertEquals("Desktop", pc.mdnsName); assertEquals("MacBookPro18,3", pc.mdnsTxt["_http._tcp"]!!["model"])
        assertEquals("DESKTOP-ABC", pc.netbiosName); assertEquals("00:11:22:33:44:55", pc.mac)             // NetBIOS beats EUI-64
        assertEquals("desktop.lan", pc.reverseDnsName); assertEquals(setOf(445), pc.probedOpenPorts)
        assertEquals(2, byIp.size)
    }
    @Test fun `neighbour table supplies macs after the sweep and status records availability`() = runBlocking {
        val e = engine(alive = mapOf("192.168.1.30" to emptySet()), neigh = Result.success(listOf(NeighbourEntry("192.168.1.30", "de:ad:be:ef:00:01", "REACHABLE"))))
        val merged = e.discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        assertEquals("de:ad:be:ef:00:01", merged["192.168.1.30"]!!.mac); assertTrue(Source.NEIGH in merged["192.168.1.30"]!!.sources)
        assertEquals(true, e.status.value.neighbourAvailable)
    }
    @Test fun `neighbour denial is recorded not thrown`() = runBlocking {
        val e = engine(alive = emptyMap(), neigh = Result.failure(Exception("errno 13")))
        e.discover(env, DiscoveryOptions()).toList()
        assertEquals(false, e.status.value.neighbourAvailable)
    }
    @Test fun `eui64 is used when nothing else gives a mac`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.40"), "esp-sensor", "_http._tcp", 80, emptyMap(), listOf("fe80::211:22ff:fe33:4455")))
        val merged = engine(alive = emptyMap(), mdns = mdns).discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        assertEquals("00:11:22:33:44:55", merged["192.168.1.40"]!!.mac)
    }
    @Test fun `large network is clamped and status says so`() = runBlocking {
        val e = engine(alive = emptyMap())
        e.discover(env.copy(prefixLength = 16), DiscoveryOptions()).toList()
        assertEquals(16, e.status.value.clampedFrom); assertEquals(254, e.status.value.total)
    }
    @Test fun `disabled strategies are not called`() = runBlocking {
        var snmpCalls = 0
        val e = DiscoveryEngine(sweepFactory = { _, _ -> object : Sweep(HostProber(), 8) { override fun run(hosts: Sequence<Inet4Address>) = flowOf(Liveness(ip("192.168.1.9"), true, emptySet(), 1)) } },
            mdns = { _, _ -> emptyFlow() }, ssdp = { emptyFlow() }, upnpFetch = { _, _ -> null }, netbios = { null }, snmp = { snmpCalls++; null },
            httpTitle = { _, _, _ -> null }, reverseDns = { null }, neighbours = { Result.success(emptyList()) }, mdnsTypes = emptyList())
        e.discover(env, DiscoveryOptions(snmp = false)).toList()
        assertEquals(0, snmpCalls)
    }
}
```
`Sweep` must be declared `open class Sweep` with `open fun run` for these fakes; adjust Task 7's class accordingly.

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*DiscoveryEngineTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`DiscoveredHost.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address

enum class Source { SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP, GATEWAY }

data class DiscoveredHost(
    val ip: Inet4Address, val mac: String? = null, val reverseDnsName: String? = null, val mdnsName: String? = null,
    val netbiosName: String? = null, val netbiosWorkgroup: String? = null,
    val upnpFriendlyName: String? = null, val upnpManufacturer: String? = null, val upnpModel: String? = null, val upnpDeviceType: String? = null, val upnpServer: String? = null,
    val mdnsServiceTypes: Set<String> = emptySet(), val mdnsTxt: Map<String, Map<String, String>> = emptyMap(), val mdnsInstanceNames: Set<String> = emptySet(),
    val ipv6LinkLocal: Set<String> = emptySet(), val snmpSysDescr: String? = null, val snmpSysName: String? = null, val snmpSysObjectId: String? = null,
    val httpTitle: String? = null, val httpServer: String? = null, val httpAuthRealm: String? = null,
    val probedOpenPorts: Set<Int> = emptySet(), val sources: Set<Source> = emptySet(), val isGateway: Boolean = false,
) {
    fun merge(o: DiscoveredHost): DiscoveredHost = DiscoveredHost(
        ip = ip, mac = mac ?: o.mac, reverseDnsName = reverseDnsName ?: o.reverseDnsName, mdnsName = mdnsName ?: o.mdnsName,
        netbiosName = netbiosName ?: o.netbiosName, netbiosWorkgroup = netbiosWorkgroup ?: o.netbiosWorkgroup,
        upnpFriendlyName = upnpFriendlyName ?: o.upnpFriendlyName, upnpManufacturer = upnpManufacturer ?: o.upnpManufacturer, upnpModel = upnpModel ?: o.upnpModel,
        upnpDeviceType = upnpDeviceType ?: o.upnpDeviceType, upnpServer = upnpServer ?: o.upnpServer,
        mdnsServiceTypes = mdnsServiceTypes + o.mdnsServiceTypes, mdnsTxt = mdnsTxt + o.mdnsTxt, mdnsInstanceNames = mdnsInstanceNames + o.mdnsInstanceNames,
        ipv6LinkLocal = ipv6LinkLocal + o.ipv6LinkLocal, snmpSysDescr = snmpSysDescr ?: o.snmpSysDescr, snmpSysName = snmpSysName ?: o.snmpSysName, snmpSysObjectId = snmpSysObjectId ?: o.snmpSysObjectId,
        httpTitle = httpTitle ?: o.httpTitle, httpServer = httpServer ?: o.httpServer, httpAuthRealm = httpAuthRealm ?: o.httpAuthRealm,
        probedOpenPorts = probedOpenPorts + o.probedOpenPorts, sources = sources + o.sources, isGateway = isGateway || o.isGateway,
    )
    /** Secondary MAC sources, applied only when mac is null. */
    fun withDerivedMac(): DiscoveredHost {
        if (mac != null) return this
        val fromNames = (mdnsInstanceNames + listOfNotNull(upnpFriendlyName)).firstNotNullOfOrNull { MacExtract.fromText(it) }
        val fromV6 = ipv6LinkLocal.firstNotNullOfOrNull { Eui64.macFromLinkLocal(it) }
        return copy(mac = fromNames ?: fromV6)
    }
}
```

`DiscoveryOptions.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

data class DiscoveryOptions(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true,
    val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000,
)
data class DiscoveryStatus(val probed: Int = 0, val total: Int = 0, val found: Int = 0, val clampedFrom: Int? = null, val neighbourAvailable: Boolean? = null)
```

`DiscoveryEngine.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

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
) {
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status

    fun discover(env: NetworkEnv, options: DiscoveryOptions): Flow<DiscoveredHost> = channelFlow {
        val range = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, options.maxPrefix)
        val found = HashSet<Inet4Address>()
        _status.value = DiscoveryStatus(total = range.subnet.size, clampedFrom = if (range.clamped) range.originalPrefix else null)
        suspend fun emit(h: DiscoveredHost) { val fresh = synchronized(found) { found.add(h.ip) }; if (fresh) _status.update { it.copy(found = found.size) }; send(h.withDerivedMac()) }

        env.gateway?.let { emit(DiscoveredHost(it, isGateway = true, sources = setOf(Source.GATEWAY))) }

        val enrich = Semaphore(32)
        val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
        val statusJob = launch { sweep.progress.collect { (p, t) -> _status.update { it.copy(probed = p, total = maxOf(t, it.total)) } } }

        coroutineScope {
            if (options.mdns) launch {
                mdns(mdnsTypes, options.mdnsDurationMs).collect { r ->
                    emit(DiscoveredHost(r.ip, mdnsName = r.instanceName, mdnsServiceTypes = setOf(r.type), mdnsTxt = mapOf(r.type to r.txt),
                        mdnsInstanceNames = setOf(r.instanceName), ipv6LinkLocal = r.ipv6.toSet(), sources = setOf(Source.MDNS)))
                }
            }
            if (options.ssdp) launch {
                val fetched = HashSet<String>()
                ssdp().collect { r ->
                    var h = DiscoveredHost(r.from, upnpServer = r.server, mac = r.usn?.let { MacExtract.fromText(it) }, sources = setOf(Source.SSDP))
                    val loc = r.location
                    if (loc != null && synchronized(fetched) { fetched.add(loc) }) {
                        upnpFetch(loc, r.from)?.let { d -> h = h.copy(upnpFriendlyName = d.friendlyName, upnpManufacturer = d.manufacturer, upnpModel = d.modelName,
                            upnpDeviceType = d.deviceType, mac = h.mac ?: d.udn?.let { MacExtract.fromText(it) }) }
                    }
                    emit(h)
                }
            }
            launch {
                sweep.run(range.subnet.hosts()).collect { l ->
                    emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP), isGateway = l.ip == env.gateway))
                    launch { enrich.withPermit {
                        var h = DiscoveredHost(l.ip)
                        if (options.netbios) netbios(l.ip)?.let { h = h.copy(netbiosName = it.computerName, netbiosWorkgroup = it.workgroup, mac = it.mac, sources = h.sources + Source.NETBIOS) }
                        if (options.reverseDns) reverseDns(l.ip)?.let { h = h.copy(reverseDnsName = it) }
                        if (options.snmp) snmp(l.ip)?.let { h = h.copy(snmpSysDescr = it.sysDescr, snmpSysName = it.sysName, snmpSysObjectId = it.sysObjectId, sources = h.sources + Source.SNMP) }
                        if (options.httpTitle) {
                            val web = l.openPorts.firstOrNull { it in setOf(80, 8080, 443, 8443) }
                            if (web != null) httpTitle(l.ip, web, web == 443 || web == 8443)?.let { h = h.copy(httpTitle = it.title, httpServer = it.server, httpAuthRealm = it.authRealm, sources = h.sources + Source.HTTP) }
                        }
                        if (h != DiscoveredHost(l.ip)) emit(h)
                    } }
                }
                // Sweep done: read the neighbour table once.
                val neigh = neighbours()
                _status.update { it.copy(neighbourAvailable = neigh.isSuccess) }
                neigh.getOrNull()?.forEach { n ->
                    val ip = runCatching { java.net.InetAddress.getByName(n.ip) as Inet4Address }.getOrNull() ?: return@forEach
                    if (range.subnet.contains(ip)) emit(DiscoveredHost(ip, mac = n.mac, sources = setOf(Source.NEIGH)))
                }
            }
        }
        statusJob.cancel()
    }

    companion object {
        fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine {
            val nsd = AndroidNsdSource(context); val upnp = UpnpFetcher(); val titles = HttpTitleFetcher(); val rdns = ReverseDns()
            return DiscoveryEngine(
                sweepFactory = { prober, c -> Sweep(prober, c) },
                mdns = { types, ms -> MdnsResolveQueue(nsd).run(types, ms) }, ssdp = { SsdpSearch().search() },
                upnpFetch = { loc, host -> upnp.fetch(loc, host) }, netbios = { NetBios.query(it) }, snmp = { Snmp.query(it) },
                httpTitle = { ip, port, tls -> titles.fetch(ip, port, tls) }, reverseDns = { rdns.lookup(it) },
                neighbours = { NeighbourTable.dump() }, mdnsTypes = mdnsTypes,
            )
        }
    }
}
```

- [ ] **Step 4: Run, expect pass; also run the whole suite**

```bash
./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add discovery engine orchestrating sweep, mDNS, SSDP, NetBIOS, SNMP, HTTP and neighbour table"
```

---

### Task 16: Device identification rules engine

**Files:**
- Create: `app/src/main/res/raw/identify_rules.json`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt`, `IdentifyRules.kt`, `DeviceIdentifier.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierTest.kt`

**Interfaces:**
- Consumes: `DiscoveredHost` (Task 15), `ServiceInfo` (Task 8).
- Produces:
```kotlin
enum class DeviceType { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, IOT, NAS, CAMERA, SERVER, UNKNOWN }
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?)
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) } }
@Serializable data class Rule(val id: String, val field: String, val pattern: String? = null, val ports: List<Int>? = null, val anyPort: List<Int>? = null,
    val vendor: String? = null, val model: String? = null, val os: String? = null, val type: String? = null, val weight: Int)
object IdentifyRules { fun load(json: String): List<Rule> }
class DeviceIdentifier(private val rules: List<Rule>) { fun identify(e: HostEvidence): DeviceIdentity }
```
- Rule `field` values: `mdnsType` (pattern matched against each service type), `mdnsTxt` (pattern against `type:key=value` strings), `upnpDeviceType`, `snmpSysDescr`, `httpTitle`, `httpRealm`, `httpServer`, `hostname` (matched against mDNS, NetBIOS, UPnP friendly and reverse DNS names), `sshBanner` (against `services[22]?.detail` and product), `ports` (all of `ports` open), `anyPort` (any of them open), `gateway`, `oui`. A `vendor`/`model` value of `$1` takes the first regex group; `$txt` takes the TXT value of the matched key.
- Direct extraction (weight 100): UPnP `manufacturer` to vendor, `modelName` to model; SNMP `sysDescr` through the per-vendor regexes in the rules file (rules with `field: snmpSysDescr` and `model: "$1"`); mDNS `model=`/`md=`/`ty=`/`product=` TXT values to model with vendor from the rule.
- Per field the highest weight wins; ties keep the earlier rule; `confidence` = highest weight applied (0 when none); each applied rule contributes a reason `"<field>: <matched text>"`.

- [ ] **Step 1: Failing tests (table-driven over the rules file)**

```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DeviceIdentifierTest {
    private val rules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText())
    private val id = DeviceIdentifier(rules)
    private val ip = InetAddress.getByName("192.168.1.2") as Inet4Address
    private fun ev(h: DiscoveredHost = DiscoveredHost(ip), ports: Set<Int> = emptySet(), services: Map<Int, ServiceInfo> = emptyMap(), oui: String? = null) = HostEvidence(h, ports, services, oui)

    @Test fun `rules file loads with unique ids and valid fields`() {
        assertTrue(rules.size >= 45); assertEquals(rules.size, rules.map { it.id }.toSet().size)
        val fields = setOf("mdnsType", "mdnsTxt", "upnpDeviceType", "snmpSysDescr", "httpTitle", "httpRealm", "httpServer", "hostname", "sshBanner", "ports", "anyPort", "gateway", "oui")
        rules.forEach { assertTrue("bad field in ${it.id}", it.field in fields); assertTrue(it.weight in 1..100) }
    }
    @Test fun `apple device-info model`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_device-info._tcp"), mdnsTxt = mapOf("_device-info._tcp" to mapOf("model" to "MacBookPro18,3")))))
        assertEquals("Apple", r.vendor); assertEquals("MacBookPro18,3", r.model); assertEquals(DeviceType.COMPUTER, r.type); assertTrue(r.reasons.any { it.contains("MacBookPro18,3") })
    }
    @Test fun `chromecast md`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_googlecast._tcp"), mdnsTxt = mapOf("_googlecast._tcp" to mapOf("md" to "Chromecast Ultra", "fn" to "Living Room")))))
        assertEquals("Google", r.vendor); assertEquals("Chromecast Ultra", r.model); assertEquals(DeviceType.MEDIA, r.type)
    }
    @Test fun `upnp direct extraction wins over port hints`() {
        val r = id.identify(ev(DiscoveredHost(ip, upnpManufacturer = "TP-Link", upnpModel = "Archer AX55", upnpDeviceType = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"), ports = setOf(80, 445)))
        assertEquals("TP-Link", r.vendor); assertEquals("Archer AX55", r.model); assertEquals(DeviceType.ROUTER, r.type); assertEquals(100, r.confidence)
    }
    @Test fun `snmp routeros`() {
        val r = id.identify(ev(DiscoveredHost(ip, snmpSysDescr = "RouterOS RB750Gr3")))
        assertEquals("MikroTik", r.vendor); assertEquals("RouterOS", r.os); assertEquals(DeviceType.ROUTER, r.type); assertEquals("RB750Gr3", r.model)
    }
    @Test fun `synology snmp and title`() {
        assertEquals("Synology", id.identify(ev(DiscoveredHost(ip, snmpSysDescr = "Linux DiskStation 4.4.302+ #69057 SMP"))).vendor)
        val t = id.identify(ev(DiscoveredHost(ip, httpTitle = "DiskStation - Synology DiskStation"))); assertEquals(DeviceType.NAS, t.type)
    }
    @Test fun `windows by ports and netbios`() {
        val r = id.identify(ev(DiscoveredHost(ip, netbiosName = "DESKTOP-ABC"), ports = setOf(135, 445)))
        assertEquals("Windows", r.os); assertEquals(DeviceType.COMPUTER, r.type)
    }
    @Test fun `iphone by lockdown port and hostname`() {
        assertEquals(DeviceType.PHONE, id.identify(ev(ports = setOf(62078))).type)
        val r = id.identify(ev(DiscoveredHost(ip, reverseDnsName = "Davids-iPhone.lan"))); assertEquals("iOS", r.os); assertEquals("Apple", r.vendor)
    }
    @Test fun `android phone by hostname`() {
        val r = id.identify(ev(DiscoveredHost(ip, reverseDnsName = "Pixel-7-Pro.lan"))); assertEquals("Android", r.os); assertEquals(DeviceType.PHONE, r.type)
    }
    @Test fun `printer by ipp and ty txt`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_ipp._tcp"), mdnsTxt = mapOf("_ipp._tcp" to mapOf("ty" to "HP OfficeJet Pro 9010")))))
        assertEquals(DeviceType.PRINTER, r.type); assertEquals("HP OfficeJet Pro 9010", r.model)
    }
    @Test fun `home assistant and proxmox by title or port`() {
        assertEquals("Home Assistant", id.identify(ev(ports = setOf(8123))).model)
        assertEquals("Proxmox VE", id.identify(ev(DiscoveredHost(ip, httpTitle = "pve - Proxmox Virtual Environment"))).model)
    }
    @Test fun `ssh banner os`() {
        val r = id.identify(ev(services = mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED))))
        assertEquals("Linux (Ubuntu)", r.os)
    }
    @Test fun `camera by rtsp`() { assertEquals(DeviceType.CAMERA, id.identify(ev(ports = setOf(554, 80))).type) }
    @Test fun `gateway flag`() { assertEquals(DeviceType.ROUTER, id.identify(ev(DiscoveredHost(ip, isGateway = true))).type) }
    @Test fun `oui vendor alone is a weak vendor`() {
        val r = id.identify(ev(oui = "Espressif Inc.")); assertEquals("Espressif Inc.", r.vendor); assertEquals(50, r.confidence)
    }
    @Test fun `no evidence is unknown`() { assertEquals(DeviceIdentity.UNKNOWN, id.identify(ev())) }
    @Test fun `every rule in the file fires for a synthetic evidence set`() {
        // Protects against typos: each rule's pattern must match a string built from its own pattern (for regex rules) or its own ports.
        for (r in rules) {
            val e = SyntheticEvidence.forRule(r, ip)
            val out = id.identify(e)
            assertTrue("rule ${r.id} never fired", out.reasons.any { it.startsWith("${r.field}:") } || r.field == "oui")
        }
    }
}
```
`SyntheticEvidence.forRule` lives in the test sources: for `ports`/`anyPort` it opens those ports; for `gateway` sets `isGateway`; for regex fields it uses the rule's own `sample` value. Add an optional `sample: String? = null` to `Rule` and give every regex rule in the JSON a `sample` that it should match. The loader test asserts every regex rule has a sample.

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*DeviceIdentifierTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`DeviceIdentity.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

enum class DeviceType { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, IOT, NAS, CAMERA, SERVER, UNKNOWN }
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?)
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) }
}
```

`IdentifyRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Rule(
    val id: String, val field: String, val pattern: String? = null, val ports: List<Int>? = null, val anyPort: List<Int>? = null,
    val vendor: String? = null, val model: String? = null, val os: String? = null, val type: String? = null, val weight: Int, val sample: String? = null,
)
object IdentifyRules {
    fun load(json: String): List<Rule> = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<Rule>>(json) }.getOrElse { emptyList() }
}
```

`DeviceIdentifier.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

class DeviceIdentifier(private val rules: List<Rule>) {
    private class Slot(var value: String? = null, var weight: Int = -1)

    fun identify(e: HostEvidence): DeviceIdentity {
        val vendor = Slot(); val model = Slot(); val os = Slot(); val type = Slot()
        val reasons = ArrayList<String>(); var confidence = 0
        fun set(slot: Slot, v: String?, w: Int) { if (v != null && w > slot.weight) { slot.value = v; slot.weight = w } }
        fun apply(r: Rule, matched: String, group1: String?, txtValue: String?) {
            fun sub(s: String?) = s?.replace("\$1", group1 ?: "")?.replace("\$txt", txtValue ?: "")?.trim()?.takeIf { it.isNotEmpty() }
            set(vendor, sub(r.vendor), r.weight); set(model, sub(r.model), r.weight); set(os, sub(r.os), r.weight); set(type, r.type, r.weight)
            reasons += "${r.field}: ${matched.take(80)}"; confidence = maxOf(confidence, r.weight)
        }
        val h = e.host
        // Direct extraction, weight 100.
        if (h.upnpManufacturer != null || h.upnpModel != null) {
            set(vendor, h.upnpManufacturer, 100); set(model, h.upnpModel, 100); reasons += "upnp: ${listOfNotNull(h.upnpManufacturer, h.upnpModel).joinToString(" ")}"; confidence = 100
        }
        val names = listOfNotNull(h.mdnsName, h.netbiosName, h.upnpFriendlyName, h.reverseDnsName) + h.mdnsInstanceNames
        val txtStrings = h.mdnsTxt.flatMap { (t, kv) -> kv.map { (k, v) -> Triple("$t:$k=$v", k, v) } }
        for (r in rules) {
            val re = r.pattern?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            fun matchAny(cands: List<String>) = cands.firstNotNullOfOrNull { c -> re?.find(c)?.let { m -> c to m.groups[1]?.value } }
            when (r.field) {
                "mdnsType" -> matchAny(h.mdnsServiceTypes.toList())?.let { (c, g) -> apply(r, c, g, null) }
                "mdnsTxt" -> txtStrings.firstNotNullOfOrNull { (s, _, v) -> re?.find(s)?.let { m -> Triple(s, m.groups[1]?.value, v) } }?.let { (s, g, v) -> apply(r, s, g, v) }
                "upnpDeviceType" -> matchAny(listOfNotNull(h.upnpDeviceType))?.let { (c, g) -> apply(r, c, g, null) }
                "snmpSysDescr" -> matchAny(listOfNotNull(h.snmpSysDescr))?.let { (c, g) -> apply(r, c, g, null) }
                "httpTitle" -> matchAny(listOfNotNull(h.httpTitle))?.let { (c, g) -> apply(r, c, g, null) }
                "httpRealm" -> matchAny(listOfNotNull(h.httpAuthRealm))?.let { (c, g) -> apply(r, c, g, null) }
                "httpServer" -> matchAny(listOfNotNull(h.httpServer))?.let { (c, g) -> apply(r, c, g, null) }
                "hostname" -> matchAny(names)?.let { (c, g) -> apply(r, c, g, null) }
                "sshBanner" -> matchAny(e.services[22]?.let { listOfNotNull(it.product, it.detail) }.orEmpty())?.let { (c, g) -> apply(r, c, g, null) }
                "ports" -> if (r.ports != null && e.openPorts.containsAll(r.ports)) apply(r, r.ports.joinToString(","), null, null)
                "anyPort" -> r.anyPort?.firstOrNull { it in e.openPorts }?.let { apply(r, it.toString(), null, null) }
                "gateway" -> if (h.isGateway) apply(r, "default gateway", null, null)
                "oui" -> if (e.ouiVendor != null) apply(r.copy(vendor = e.ouiVendor), e.ouiVendor, null, null)
            }
        }
        if (reasons.isEmpty()) return DeviceIdentity.UNKNOWN
        val t = type.value?.let { runCatching { DeviceType.valueOf(it) }.getOrNull() } ?: DeviceType.UNKNOWN
        return DeviceIdentity(vendor.value, model.value, os.value, t, confidence, reasons.distinct())
    }
}
```

`identify_rules.json`: one object per row of spec Appendix D, with these exact shapes (author all of them; the examples show each field kind):
```json
[
 {"id":"apple-device-info","field":"mdnsTxt","pattern":"^_device-info\\._tcp:model=(.+)$","vendor":"Apple","model":"$1","type":"COMPUTER","weight":90,"sample":"_device-info._tcp:model=MacBookPro18,3"},
 {"id":"airplay-appletv","field":"mdnsTxt","pattern":"^_airplay\\._tcp:model=(AppleTV.+)$","vendor":"Apple","model":"$1","type":"MEDIA","weight":90,"sample":"_airplay._tcp:model=AppleTV6,2"},
 {"id":"googlecast-md","field":"mdnsTxt","pattern":"^_googlecast\\._tcp:md=(.+)$","vendor":"Google","model":"$1","type":"MEDIA","weight":90,"sample":"_googlecast._tcp:md=Chromecast Ultra"},
 {"id":"hap-matter","field":"mdnsType","pattern":"^_(hap|matter)\\._tcp$","type":"IOT","weight":70,"sample":"_hap._tcp"},
 {"id":"hap-md","field":"mdnsTxt","pattern":"^_hap\\._tcp:md=(.+)$","model":"$1","type":"IOT","weight":70,"sample":"_hap._tcp:md=Eve Energy"},
 {"id":"ios-companion","field":"mdnsType","pattern":"^_(companion-link|rdlink)\\._tcp$","vendor":"Apple","os":"iOS","type":"PHONE","weight":70,"sample":"_companion-link._tcp"},
 {"id":"sonos","field":"mdnsType","pattern":"^_sonos\\._tcp$","vendor":"Sonos","type":"MEDIA","weight":90,"sample":"_sonos._tcp"},
 {"id":"hue","field":"mdnsType","pattern":"^_hue\\._tcp$","vendor":"Philips Hue","type":"IOT","weight":90,"sample":"_hue._tcp"},
 {"id":"printer-mdns","field":"mdnsType","pattern":"^_(ipp|ipps|printer|pdl-datastream)\\._tcp$","type":"PRINTER","weight":90,"sample":"_ipp._tcp"},
 {"id":"printer-ty","field":"mdnsTxt","pattern":"^_(?:ipp|ipps|printer)\\._tcp:(?:ty|product)=\\(?([^)]+)\\)?$","model":"$1","type":"PRINTER","weight":90,"sample":"_ipp._tcp:ty=HP OfficeJet Pro 9010"},
 {"id":"androidtv","field":"mdnsType","pattern":"^_androidtvremote2\\._tcp$","os":"Android TV","type":"MEDIA","weight":90,"sample":"_androidtvremote2._tcp"},
 {"id":"workstation","field":"mdnsType","pattern":"^_workstation\\._tcp$","os":"Linux","type":"COMPUTER","weight":50,"sample":"_workstation._tcp"},
 {"id":"macos-afp","field":"mdnsType","pattern":"^_afpovertcp\\._tcp$","vendor":"Apple","os":"macOS","type":"COMPUTER","weight":70,"sample":"_afpovertcp._tcp"},
 {"id":"upnp-igd","field":"upnpDeviceType","pattern":"InternetGatewayDevice","type":"ROUTER","weight":90,"sample":"urn:schemas-upnp-org:device:InternetGatewayDevice:1"},
 {"id":"upnp-media","field":"upnpDeviceType","pattern":"Media(Renderer|Server)","type":"MEDIA","weight":70,"sample":"urn:schemas-upnp-org:device:MediaRenderer:1"},
 {"id":"upnp-printer","field":"upnpDeviceType","pattern":"Printer","type":"PRINTER","weight":90,"sample":"urn:schemas-upnp-org:device:Printer:1"},
 {"id":"snmp-routeros","field":"snmpSysDescr","pattern":"^RouterOS\\s*(\\S+)?","vendor":"MikroTik","os":"RouterOS","model":"$1","type":"ROUTER","weight":100,"sample":"RouterOS RB750Gr3"},
 {"id":"snmp-synology","field":"snmpSysDescr","pattern":"DiskStation|Synology","vendor":"Synology","type":"NAS","weight":100,"sample":"Linux DiskStation 4.4.302+"},
 {"id":"snmp-qnap","field":"snmpSysDescr","pattern":"QNAP","vendor":"QNAP","type":"NAS","weight":100,"sample":"Linux TS-453 QNAP"},
 {"id":"snmp-printers","field":"snmpSysDescr","pattern":"(HP|Hewlett-Packard|Brother|EPSON|Canon|Lexmark|Kyocera|Xerox)","vendor":"$1","type":"PRINTER","weight":100,"sample":"HP ETHERNET MULTI-ENVIRONMENT"},
 {"id":"snmp-cisco","field":"snmpSysDescr","pattern":"Cisco IOS","vendor":"Cisco","os":"IOS","type":"ROUTER","weight":100,"sample":"Cisco IOS Software"},
 {"id":"title-synology","field":"httpTitle","pattern":"Synology|DiskStation","vendor":"Synology","type":"NAS","weight":70,"sample":"DiskStation - Synology DiskStation"},
 {"id":"title-openwrt","field":"httpTitle","pattern":"OpenWrt|LuCI","os":"OpenWrt","type":"ROUTER","weight":90,"sample":"OpenWrt - LuCI"},
 {"id":"title-bsd-fw","field":"httpTitle","pattern":"(pfSense|OPNsense)","os":"$1","type":"ROUTER","weight":90,"sample":"pfSense - Login"},
 {"id":"title-hass","field":"httpTitle","pattern":"Home Assistant","model":"Home Assistant","type":"SERVER","weight":90,"sample":"Home Assistant"},
 {"id":"port-hass","field":"anyPort","anyPort":[8123],"model":"Home Assistant","type":"SERVER","weight":90},
 {"id":"title-proxmox","field":"httpTitle","pattern":"Proxmox","model":"Proxmox VE","type":"SERVER","weight":90,"sample":"pve - Proxmox Virtual Environment"},
 {"id":"port-proxmox","field":"anyPort","anyPort":[8006],"model":"Proxmox VE","type":"SERVER","weight":90},
 {"id":"title-plex","field":"httpTitle","pattern":"Plex","model":"Plex Media Server","type":"SERVER","weight":70,"sample":"Plex"},
 {"id":"port-plex","field":"anyPort","anyPort":[32400],"model":"Plex Media Server","type":"SERVER","weight":70},
 {"id":"title-pihole","field":"httpTitle","pattern":"Pi-hole","model":"Pi-hole","type":"SERVER","weight":90,"sample":"Pi-hole - Login"},
 {"id":"title-unifi","field":"httpTitle","pattern":"UniFi","vendor":"Ubiquiti","type":"ROUTER","weight":70,"sample":"UniFi Network"},
 {"id":"realm-routers","field":"httpRealm","pattern":"(TP-LINK|NETGEAR|D-Link|ASUS|Zyxel|Technicolor|Sagemcom|Huawei|ZTE)","vendor":"$1","type":"ROUTER","weight":70,"sample":"NETGEAR R7000"},
 {"id":"server-cameras","field":"httpServer","pattern":"(Hikvision|Dahua|Reolink|Axis|Amcrest)","vendor":"$1","type":"CAMERA","weight":70,"sample":"Hikvision-Webs"},
 {"id":"rtsp-camera","field":"ports","ports":[554,80],"type":"CAMERA","weight":70},
 {"id":"host-android","field":"hostname","pattern":"^(Pixel|Galaxy|OnePlus|Xiaomi|Redmi|POCO|android-)","os":"Android","type":"PHONE","weight":70,"sample":"Pixel-7-Pro.lan"},
 {"id":"host-ios","field":"hostname","pattern":"iPhone|iPad","vendor":"Apple","os":"iOS","type":"PHONE","weight":70,"sample":"Davids-iPhone.lan"},
 {"id":"host-mac","field":"hostname","pattern":"MacBook|iMac|Mac-mini|Mac-Studio|Macmini","vendor":"Apple","os":"macOS","type":"COMPUTER","weight":70,"sample":"Davids-MacBook-Pro.local"},
 {"id":"host-windows","field":"hostname","pattern":"^(DESKTOP-|LAPTOP-|WIN-)","os":"Windows","type":"COMPUTER","weight":70,"sample":"DESKTOP-ABC123"},
 {"id":"host-iot","field":"hostname","pattern":"^(ESP_|ESP-|Shelly|Tasmota|wled|Sonoff|esphome)","type":"IOT","weight":70,"sample":"Shelly-ABCDEF"},
 {"id":"host-rpi","field":"hostname","pattern":"raspberrypi|^rpi","vendor":"Raspberry Pi","os":"Linux","type":"COMPUTER","weight":70,"sample":"raspberrypi.lan"},
 {"id":"port-ios","field":"anyPort","anyPort":[62078],"vendor":"Apple","os":"iOS","type":"PHONE","weight":70},
 {"id":"port-adb","field":"anyPort","anyPort":[5555],"os":"Android","weight":50},
 {"id":"ports-windows","field":"ports","ports":[135,445],"os":"Windows","type":"COMPUTER","weight":70},
 {"id":"port-rdp","field":"anyPort","anyPort":[3389],"os":"Windows","type":"COMPUTER","weight":70},
 {"id":"ports-mac","field":"ports","ports":[548,5900],"vendor":"Apple","os":"macOS","type":"COMPUTER","weight":50},
 {"id":"port-printer","field":"anyPort","anyPort":[9100,631,515],"type":"PRINTER","weight":70},
 {"id":"ports-cast","field":"ports","ports":[8009,8008],"vendor":"Google","type":"MEDIA","weight":70},
 {"id":"port-sonos","field":"anyPort","anyPort":[1400],"vendor":"Sonos","type":"MEDIA","weight":70},
 {"id":"port-mikrotik","field":"anyPort","anyPort":[8291],"vendor":"MikroTik","type":"ROUTER","weight":90},
 {"id":"ports-synology","field":"ports","ports":[5000,5001],"vendor":"Synology","type":"NAS","weight":50},
 {"id":"port-mqtt","field":"anyPort","anyPort":[1883],"type":"IOT","weight":50},
 {"id":"ssh-distro","field":"sshBanner","pattern":"(Ubuntu|Debian|Raspbian)","os":"Linux ($1)","weight":70,"sample":"Ubuntu-3ubuntu13"},
 {"id":"ssh-windows","field":"sshBanner","pattern":"OpenSSH_for_Windows","os":"Windows","weight":90,"sample":"OpenSSH_for_Windows_8.1"},
 {"id":"ssh-dropbear","field":"sshBanner","pattern":"dropbear","os":"Linux (embedded)","type":"IOT","weight":50,"sample":"dropbear"},
 {"id":"gateway","field":"gateway","type":"ROUTER","weight":90},
 {"id":"oui","field":"oui","weight":50}
]
```
Note the SNMP direct-extraction rules carry weight 100 so they count as direct extraction per the spec. Add `src/main/res/raw` to the test resources as in Task 5.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*DeviceIdentifierTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add device identification rules engine and seed rules"
```

---

### Task 17: Room persistence and repositories

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`, `Daos.kt`, `NetProbeDb.kt`, `Converters.kt`, `NetworkSignature.kt`, `Repositories.kt`, `Settings.kt`
- Test (JVM): `app/src/test/java/com/northwalessolutions/netprobe/data/NetworkSignatureTest.kt`, `DisplayNameTest.kt`
- Test (device): `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`

**Interfaces:**
- Consumes: `NetworkEnv` (4), `DiscoveredHost` (15), `DeviceIdentity`, `DeviceType` (16), `ServiceInfo`, `Confidence` (8).
- Produces:
```kotlin
enum class HostStatus { UNMARKED, INTERESTING, INVESTIGATED, IGNORED }
@Entity(tableName = "networks", indices = [Index("signature", unique = true)]) data class NetworkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val signature: String, val displayName: String, val gatewayIp: String?, val prefixLength: Int, val ssid: String?, val lastSeen: Long)
@Entity(tableName = "hosts", indices = [Index("networkId", "ip", unique = true)]) data class HostEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val networkId: Long, val ip: String, val mac: String?,
    val reverseDnsName: String?, val mdnsName: String?, val netbiosName: String?, val upnpName: String?, val upnpManufacturer: String?, val upnpModel: String?, val vendor: String?,
    val deviceType: DeviceType, val customName: String?, val identVendor: String?, val identModel: String?, val identOs: String?, val identConfidence: Int, val identReasons: List<String>,
    val snmpSysDescr: String?, val httpTitle: String?, val mdnsTxtJson: String?, val status: HostStatus, val note: String, val isGateway: Boolean,
    val firstSeen: Long, val lastSeen: Long, val lastPortScan: Long?, val lastDiscoveryRun: Long)
@Entity(tableName = "ports", indices = [Index("hostId", "port", unique = true)]) data class PortEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val port: Int,
    val serviceName: String, val product: String?, val version: String?, val detail: String?, val confidence: Confidence, val lastSeen: Long)
@Entity(tableName = "vuln_cache") data class VulnCacheEntity(@PrimaryKey val query: String, val json: String, val fetchedAt: Long)
object NetworkSignature { fun of(env: NetworkEnv): String }   // sha256 of (bssid ?: ssid ?: "") + gatewayIp + "/" + prefix, hex
object DisplayName { fun of(h: HostEntity): String }           // customName, mdnsName, netbiosName, upnpName, reverseDnsName, ip
class HostRepository(db: NetProbeDb) {
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>>
    suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long): Long   // returns host id; never touches customName, status, note
    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity)
    suspend fun setStatus(hostId: Long, status: HostStatus); suspend fun setNote(hostId: Long, note: String); suspend fun setCustomName(hostId: Long, name: String?)
    fun host(hostId: Long): Flow<HostEntity?> }
class PortRepository(db: NetProbeDb) { fun portsForHost(hostId: Long): Flow<List<PortEntity>>; suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo); suspend fun markScanned(hostId: Long) }
class NetworkRepository(db: NetProbeDb) { suspend fun ensure(env: NetworkEnv): NetworkEntity }
class VulnCacheRepository(db: NetProbeDb) { suspend fun get(query: String, maxAgeMs: Long): VulnCacheEntity?; suspend fun put(query: String, json: String) }
class AppSettings(context: Context)   // SharedPreferences-backed: discovery toggles, sweepConcurrency, connectTimeoutMs, maxPrefix, scanConcurrency, scanTimeoutMs, nvdApiKey, exposed as StateFlow<SettingsState>
```
- Room: `@Database(entities = [...], version = 1, exportSchema = true)`; `TypeConverters` for `List<String>` (JSON) and enums (name). `room.schemaLocation` is already set in Task 1; commit `app/schemas/`.

- [ ] **Step 1: Failing JVM tests**

```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class NetworkSignatureTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val base = NetworkEnv(ip("192.168.1.5"), 24, ip("192.168.1.1"), emptyList(), null, null, Transport.WIFI)
    @Test fun `same gateway and prefix without ids share a signature`() { assertEquals(NetworkSignature.of(base), NetworkSignature.of(base.copy(interfaceIp = ip("192.168.1.99")))) }
    @Test fun `bssid separates otherwise identical networks`() { assertNotEquals(NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:01")), NetworkSignature.of(base.copy(bssid = "aa:bb:cc:dd:ee:02"))) }
    @Test fun `is 64 hex chars`() { assertTrue(Regex("^[0-9a-f]{64}$").matches(NetworkSignature.of(base))) }
}

class DisplayNameTest {
    private fun host(custom: String? = null, mdns: String? = null, nb: String? = null, upnp: String? = null, rdns: String? = null) = HostEntity(
        networkId = 1, ip = "192.168.1.9", mac = null, reverseDnsName = rdns, mdnsName = mdns, netbiosName = nb, upnpName = upnp, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.UNKNOWN, customName = custom, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
        snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    @Test fun `precedence`() {
        assertEquals("Mine", DisplayName.of(host(custom = "Mine", mdns = "m", nb = "n", upnp = "u", rdns = "r")))
        assertEquals("m", DisplayName.of(host(mdns = "m", nb = "n"))); assertEquals("n", DisplayName.of(host(nb = "n", upnp = "u")))
        assertEquals("u", DisplayName.of(host(upnp = "u", rdns = "r"))); assertEquals("r", DisplayName.of(host(rdns = "r"))); assertEquals("192.168.1.9", DisplayName.of(host()))
        assertEquals("192.168.1.9", DisplayName.of(host(custom = "  ")))
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*NetworkSignatureTest*' --tests '*DisplayNameTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement entities, DAOs, DB, repositories, settings**

`Entities.kt`: the entities exactly as in Interfaces, plus `enum class HostStatus`.

`Converters.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter fun listToJson(l: List<String>): String = Json.encodeToString(l)
    @TypeConverter fun jsonToList(s: String): List<String> = runCatching { Json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
}
```

`Daos.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface NetworkDao {
    @Query("SELECT * FROM networks WHERE signature = :sig LIMIT 1") suspend fun bySignature(sig: String): NetworkEntity?
    @Insert suspend fun insert(n: NetworkEntity): Long
    @Query("UPDATE networks SET lastSeen = :now, ssid = COALESCE(:ssid, ssid) WHERE id = :id") suspend fun touch(id: Long, now: Long, ssid: String?)
}
@Dao interface HostDao {
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    @Query("SELECT * FROM hosts WHERE id = :id") fun byId(id: Long): Flow<HostEntity?>
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND ip = :ip LIMIT 1") suspend fun find(networkId: Long, ip: String): HostEntity?
    @Insert suspend fun insert(h: HostEntity): Long
    @Update suspend fun update(h: HostEntity)
    @Query("UPDATE hosts SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: HostStatus)
    @Query("UPDATE hosts SET note = :note WHERE id = :id") suspend fun setNote(id: Long, note: String)
    @Query("UPDATE hosts SET customName = :name WHERE id = :id") suspend fun setCustomName(id: Long, name: String?)
    @Query("UPDATE hosts SET lastPortScan = :now WHERE id = :id") suspend fun markScanned(id: Long, now: Long)
}
@Dao interface PortDao {
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") fun forHost(hostId: Long): Flow<List<PortEntity>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId AND port = :port LIMIT 1") suspend fun find(hostId: Long, port: Int): PortEntity?
    @Insert suspend fun insert(p: PortEntity): Long
    @Update suspend fun update(p: PortEntity)
}
@Dao interface VulnCacheDao {
    @Query("SELECT * FROM vuln_cache WHERE `query` = :q LIMIT 1") suspend fun get(q: String): VulnCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: VulnCacheEntity)
    @Query("DELETE FROM vuln_cache") suspend fun clear()
}
```

`NetProbeDb.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    companion object { fun open(context: Context): NetProbeDb = Room.databaseBuilder(context, NetProbeDb::class.java, "netprobe.db").build() }
}
```

`NetworkSignature.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import java.security.MessageDigest

object NetworkSignature {
    fun of(env: NetworkEnv): String {
        val raw = (env.bssid ?: env.ssid ?: "") + (env.gateway?.hostAddress ?: "") + "/" + env.prefixLength
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
object DisplayName {
    fun of(h: HostEntity): String = listOf(h.customName, h.mdnsName, h.netbiosName, h.upnpName, h.reverseDnsName).firstOrNull { !it.isNullOrBlank() } ?: h.ip
}
```

`Repositories.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class NetworkRepository(private val db: NetProbeDb) {
    suspend fun ensure(env: NetworkEnv): NetworkEntity {
        val sig = NetworkSignature.of(env); val now = System.currentTimeMillis()
        db.networks().bySignature(sig)?.let { db.networks().touch(it.id, now, env.ssid); return it.copy(lastSeen = now) }
        val n = NetworkEntity(signature = sig, displayName = env.ssid ?: env.gateway?.hostAddress ?: "Network", gatewayIp = env.gateway?.hostAddress, prefixLength = env.prefixLength, ssid = env.ssid, lastSeen = now)
        return n.copy(id = db.networks().insert(n))
    }
}

class HostRepository(private val db: NetProbeDb) {
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().forNetwork(networkId)
    fun host(hostId: Long): Flow<HostEntity?> = db.hosts().byId(hostId)

    suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long): Long {
        val now = System.currentTimeMillis()
        val txt = h.mdnsTxt.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
        val existing = db.hosts().find(networkId, h.ip.hostAddress)
        if (existing == null) {
            return db.hosts().insert(HostEntity(networkId = networkId, ip = h.ip.hostAddress, mac = h.mac, reverseDnsName = h.reverseDnsName, mdnsName = h.mdnsName,
                netbiosName = h.netbiosName, upnpName = h.upnpFriendlyName, upnpManufacturer = h.upnpManufacturer, upnpModel = h.upnpModel, vendor = h.upnpManufacturer,
                deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
                snmpSysDescr = h.snmpSysDescr, httpTitle = h.httpTitle, mdnsTxtJson = txt, status = HostStatus.UNMARKED, note = "", isGateway = h.isGateway,
                firstSeen = now, lastSeen = now, lastPortScan = null, lastDiscoveryRun = runId))
        }
        // Scan data refreshes; annotations (customName, status, note) are never overwritten.
        db.hosts().update(existing.copy(mac = h.mac ?: existing.mac, reverseDnsName = h.reverseDnsName ?: existing.reverseDnsName, mdnsName = h.mdnsName ?: existing.mdnsName,
            netbiosName = h.netbiosName ?: existing.netbiosName, upnpName = h.upnpFriendlyName ?: existing.upnpName, upnpManufacturer = h.upnpManufacturer ?: existing.upnpManufacturer,
            upnpModel = h.upnpModel ?: existing.upnpModel, vendor = h.upnpManufacturer ?: existing.vendor, snmpSysDescr = h.snmpSysDescr ?: existing.snmpSysDescr,
            httpTitle = h.httpTitle ?: existing.httpTitle, mdnsTxtJson = txt ?: existing.mdnsTxtJson, isGateway = existing.isGateway || h.isGateway, lastSeen = now, lastDiscoveryRun = runId))
        return existing.id
    }
    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
        val h = db.hosts().byId(hostId).let { f -> var v: HostEntity? = null; f.collect { v = it; return@collect }; v } ?: return
        db.hosts().update(h.copy(identVendor = identity.vendor, identModel = identity.model, identOs = identity.os, deviceType = identity.type, identConfidence = identity.confidence, identReasons = identity.reasons))
    }
    suspend fun setStatus(hostId: Long, status: HostStatus) = db.hosts().setStatus(hostId, status)
    suspend fun setNote(hostId: Long, note: String) = db.hosts().setNote(hostId, note)
    suspend fun setCustomName(hostId: Long, name: String?) = db.hosts().setCustomName(hostId, name?.trim()?.takeIf { it.isNotEmpty() })
}
```
Replace the awkward `setIdentity` collect with a `suspend fun byIdOnce(id: Long): HostEntity?` query on `HostDao` (`SELECT * FROM hosts WHERE id = :id`) and use it.

```kotlin
class PortRepository(private val db: NetProbeDb) {
    fun portsForHost(hostId: Long): Flow<List<PortEntity>> = db.ports().forHost(hostId)
    suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo) {
        val now = System.currentTimeMillis()
        val e = db.ports().find(hostId, port)
        if (e == null) db.ports().insert(PortEntity(hostId = hostId, port = port, serviceName = info.name, product = info.product, version = info.version, detail = info.detail, confidence = info.confidence, lastSeen = now))
        else db.ports().update(e.copy(serviceName = info.name, product = info.product ?: e.product, version = info.version ?: e.version, detail = info.detail ?: e.detail, confidence = info.confidence, lastSeen = now))
    }
    suspend fun markScanned(hostId: Long) = db.hosts().markScanned(hostId, System.currentTimeMillis())
}
class VulnCacheRepository(private val db: NetProbeDb) {
    suspend fun get(query: String, maxAgeMs: Long): VulnCacheEntity? = db.vulnCache().get(query)?.takeIf { System.currentTimeMillis() - it.fetchedAt <= maxAgeMs }
    suspend fun getAny(query: String): VulnCacheEntity? = db.vulnCache().get(query)
    suspend fun put(query: String, json: String) = db.vulnCache().put(VulnCacheEntity(query, json, System.currentTimeMillis()))
    suspend fun clear() = db.vulnCache().clear()
}
```

`Settings.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsState(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22,
    val scanConcurrency: Int = 128, val scanTimeoutMs: Int = 600, val nvdApiKey: String = "",
)
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("netprobe", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    private fun load() = SettingsState(
        mdns = prefs.getBoolean("mdns", true), ssdp = prefs.getBoolean("ssdp", true), netbios = prefs.getBoolean("netbios", true), reverseDns = prefs.getBoolean("rdns", true),
        snmp = prefs.getBoolean("snmp", true), httpTitle = prefs.getBoolean("http", true), sweepConcurrency = prefs.getInt("sweepConc", 128).coerceIn(32, 512),
        connectTimeoutMs = prefs.getInt("connTimeout", 600).coerceIn(200, 2000), maxPrefix = prefs.getInt("maxPrefix", 22).coerceIn(20, 24),
        scanConcurrency = prefs.getInt("scanConc", 128).coerceIn(32, 512), scanTimeoutMs = prefs.getInt("scanTimeout", 600).coerceIn(200, 2000), nvdApiKey = prefs.getString("nvdKey", "") ?: "")
    fun update(f: (SettingsState) -> SettingsState) {
        val s = f(_state.value)
        prefs.edit().putBoolean("mdns", s.mdns).putBoolean("ssdp", s.ssdp).putBoolean("netbios", s.netbios).putBoolean("rdns", s.reverseDns).putBoolean("snmp", s.snmp).putBoolean("http", s.httpTitle)
            .putInt("sweepConc", s.sweepConcurrency).putInt("connTimeout", s.connectTimeoutMs).putInt("maxPrefix", s.maxPrefix).putInt("scanConc", s.scanConcurrency).putInt("scanTimeout", s.scanTimeoutMs)
            .putString("nvdKey", s.nvdApiKey).apply()
        _state.value = load()
    }
}
```

- [ ] **Step 4: Device DAO test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: NetProbeDb
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build() }
    @After fun tearDown() { db.close() }

    @Test fun upsertKeepsAnnotationsAndRefreshesScanData() = runBlocking {
        val hosts = HostRepository(db); val ip = InetAddress.getByName("192.168.1.20") as Inet4Address
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip, mdnsName = "Desk", sources = setOf(Source.MDNS)), runId = 1)
        hosts.setStatus(id, HostStatus.INTERESTING); hosts.setNote(id, "check me"); hosts.setCustomName(id, "My PC")
        val id2 = hosts.upsertDiscovered(1, DiscoveredHost(ip, mac = "00:11:22:33:44:55", netbiosName = "DESKTOP"), runId = 2)
        assertEquals(id, id2)
        val h = hosts.host(id).first()!!
        assertEquals(HostStatus.INTERESTING, h.status); assertEquals("check me", h.note); assertEquals("My PC", h.customName)
        assertEquals("00:11:22:33:44:55", h.mac); assertEquals("Desk", h.mdnsName); assertEquals("DESKTOP", h.netbiosName); assertEquals(2L, h.lastDiscoveryRun)
    }
    @Test fun uniqueIndexPerNetwork() = runBlocking {
        val hosts = HostRepository(db); val ip = InetAddress.getByName("192.168.1.1") as Inet4Address
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip), 1); val b = hosts.upsertDiscovered(2, DiscoveredHost(ip), 1)
        assertNotEquals(a, b); assertEquals(1, hosts.hostsForNetwork(1).first().size)
    }
}
```
Add `androidTestImplementation("androidx.test:core-ktx:1.6.1")` to the version catalog (`androidx-test-core`) for `ApplicationProvider`.

- [ ] **Step 5: Run JVM tests and build; run DAO test on device when available**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -15
ls app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/1.json
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.data.DaoTest --console=plain 2>&1 | tail -15
```
Expected: JVM green, schema file exported, device test green (skip the device step if no phone is attached and say so in the commit message).

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add Room persistence, repositories, network signature and settings"
```

---

### Task 18: Connect actions

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/actions/DeviceAction.kt`, `ActionProvider.kt`, `AndroidActionRunner.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/actions/ActionProviderTest.kt`

**Interfaces:**
- Consumes: `ServiceInfo` (Task 8).
- Produces:
```kotlin
sealed class DeviceAction(val label: String) {
    data class OpenUri(val uriLabel: String, val uri: String, val hasHandler: Boolean) : DeviceAction(uriLabel)   // "Open in browser", "Connect with SSH app"
    data class Copy(val copyLabel: String, val text: String) : DeviceAction(copyLabel)
    data class OpenWith(val uri: String) : DeviceAction("Open with...") }
fun interface HandlerResolver { fun hasHandler(uri: String): Boolean }   // PackageManager behind an interface
class ActionProvider(private val resolver: HandlerResolver) {
    fun actionsFor(ip: String, port: Int, service: ServiceInfo): List<DeviceAction>   // primary first; copy actions always last
    fun primary(ip: String, port: Int, service: ServiceInfo): DeviceAction? }           // the OpenUri when it has a handler, else null
class AndroidActionRunner(private val context: Context) : HandlerResolver { fun run(action: DeviceAction): Boolean }  // false when no activity; caller then shows the copy sheet
```
- Mapping (spec 5.5): name HTTP -> `http://ip:port/`, HTTPS/TLS -> `https://ip:port/`, SMB -> `smb://ip/`, SSH -> `ssh://ip:port`, Telnet -> `telnet://ip:port`, VNC -> `vnc://ip:port`, RDP -> `rdp://ip:port`, FTP -> `ftp://ip:port/`. Name matching is case-insensitive on `service.name`, with fallbacks by port when the name is "Unknown": 80/8080/8000/8008/8081/8888/3000/5000/9000 -> HTTP, 443/8443 -> HTTPS, 445/139 -> SMB, 22/2222 -> SSH, 23 -> Telnet, 5900..5910 -> VNC, 3389 -> RDP, 21 -> FTP.
- Labels: "Open in browser", "Open share", "Connect with SSH app", "Connect with Telnet app", "Connect with VNC app", "Connect with RDP app", "Open with FTP app". When `hasHandler` is false the label becomes "No app installed for smb://" style text and the UI greys it.

- [ ] **Step 1: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class ActionProviderTest {
    private fun svc(name: String) = ServiceInfo(name, confidence = Confidence.PORT_TABLE)
    private val all = ActionProvider { true }
    private val none = ActionProvider { false }
    private val onlyHttp = ActionProvider { it.startsWith("http") }

    @Test fun `http builds browser action with port`() {
        val a = all.primary("192.168.1.1", 8080, svc("HTTP")) as DeviceAction.OpenUri
        assertEquals("http://192.168.1.1:8080/", a.uri); assertEquals("Open in browser", a.label); assertTrue(a.hasHandler)
    }
    @Test fun `smb uses host only`() { assertEquals("smb://192.168.1.5/", (all.primary("192.168.1.5", 445, svc("SMB")) as DeviceAction.OpenUri).uri) }
    @Test fun `ssh vnc rdp telnet ftp schemes`() {
        assertEquals("ssh://10.0.0.2:22", (all.primary("10.0.0.2", 22, svc("SSH")) as DeviceAction.OpenUri).uri)
        assertEquals("vnc://10.0.0.2:5901", (all.primary("10.0.0.2", 5901, svc("VNC")) as DeviceAction.OpenUri).uri)
        assertEquals("rdp://10.0.0.2:3389", (all.primary("10.0.0.2", 3389, svc("RDP")) as DeviceAction.OpenUri).uri)
        assertEquals("telnet://10.0.0.2:23", (all.primary("10.0.0.2", 23, svc("Telnet")) as DeviceAction.OpenUri).uri)
        assertEquals("ftp://10.0.0.2:21/", (all.primary("10.0.0.2", 21, svc("FTP")) as DeviceAction.OpenUri).uri)
    }
    @Test fun `unknown name falls back to port and HTTPS from TLS name`() {
        assertEquals("https://10.0.0.2:8443/", (all.primary("10.0.0.2", 8443, svc("Unknown")) as DeviceAction.OpenUri).uri)
        assertEquals("https://10.0.0.2:443/", (all.primary("10.0.0.2", 443, svc("HTTPS")) as DeviceAction.OpenUri).uri)
        assertEquals("vnc://10.0.0.2:5900", (all.primary("10.0.0.2", 5900, svc("Unknown")) as DeviceAction.OpenUri).uri)
        assertNull(all.primary("10.0.0.2", 31337, svc("Unknown")))
    }
    @Test fun `no handler means no primary but the list still offers copy and open with`() {
        assertNull(none.primary("10.0.0.2", 445, svc("SMB")))
        val list = none.actionsFor("10.0.0.2", 445, svc("SMB"))
        val open = list.filterIsInstance<DeviceAction.OpenUri>().single()
        assertFalse(open.hasHandler); assertEquals("No app installed for smb://", open.label)
        assertTrue(list.any { it is DeviceAction.OpenWith }); assertEquals(listOf("Copy IP", "Copy IP:port"), list.filterIsInstance<DeviceAction.Copy>().map { it.label })
        assertEquals("10.0.0.2:445", list.filterIsInstance<DeviceAction.Copy>().last().text)
    }
    @Test fun `handler resolution is per scheme`() {
        assertNotNull(onlyHttp.primary("10.0.0.2", 80, svc("HTTP"))); assertNull(onlyHttp.primary("10.0.0.2", 22, svc("SSH")))
    }
    @Test fun `copy actions exist even for ports with no mapping`() {
        val list = all.actionsFor("10.0.0.2", 31337, svc("Unknown"))
        assertEquals(2, list.filterIsInstance<DeviceAction.Copy>().size); assertTrue(list.none { it is DeviceAction.OpenUri })
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*ActionProviderTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`DeviceAction.kt`:
```kotlin
package com.northwalessolutions.netprobe.actions

sealed class DeviceAction(val label: String) {
    data class OpenUri(val uriLabel: String, val uri: String, val hasHandler: Boolean) : DeviceAction(uriLabel)
    data class Copy(val copyLabel: String, val text: String) : DeviceAction(copyLabel)
    data class OpenWith(val uri: String) : DeviceAction("Open with...")
}
fun interface HandlerResolver { fun hasHandler(uri: String): Boolean }
```

`ActionProvider.kt`:
```kotlin
package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

class ActionProvider(private val resolver: HandlerResolver) {
    private enum class Kind(val scheme: String, val label: String) {
        HTTP("http", "Open in browser"), HTTPS("https", "Open in browser"), SMB("smb", "Open share"), SSH("ssh", "Connect with SSH app"),
        TELNET("telnet", "Connect with Telnet app"), VNC("vnc", "Connect with VNC app"), RDP("rdp", "Connect with RDP app"), FTP("ftp", "Open with FTP app")
    }
    private fun kindFor(port: Int, service: ServiceInfo): Kind? = when (service.name.uppercase()) {
        "HTTP" -> Kind.HTTP; "HTTPS", "TLS" -> Kind.HTTPS; "SMB" -> Kind.SMB; "SSH" -> Kind.SSH; "TELNET" -> Kind.TELNET
        "VNC" -> Kind.VNC; "RDP" -> Kind.RDP; "FTP" -> Kind.FTP
        else -> when (port) {
            80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000 -> Kind.HTTP; 443, 8443 -> Kind.HTTPS; 445, 139 -> Kind.SMB
            22, 2222 -> Kind.SSH; 23 -> Kind.TELNET; in 5900..5910 -> Kind.VNC; 3389 -> Kind.RDP; 21 -> Kind.FTP; else -> null
        }
    }
    private fun uri(k: Kind, ip: String, port: Int) = when (k) {
        Kind.HTTP, Kind.HTTPS, Kind.FTP -> "${k.scheme}://$ip:$port/"
        Kind.SMB -> "smb://$ip/"
        else -> "${k.scheme}://$ip:$port"
    }

    fun actionsFor(ip: String, port: Int, service: ServiceInfo): List<DeviceAction> {
        val out = ArrayList<DeviceAction>()
        kindFor(port, service)?.let { k ->
            val u = uri(k, ip, port); val has = resolver.hasHandler(u)
            out += DeviceAction.OpenUri(if (has) k.label else "No app installed for ${k.scheme}://", u, has)
            out += DeviceAction.OpenWith(u)
        }
        out += DeviceAction.Copy("Copy IP", ip); out += DeviceAction.Copy("Copy IP:port", "$ip:$port")
        return out
    }
    fun primary(ip: String, port: Int, service: ServiceInfo): DeviceAction? =
        actionsFor(ip, port, service).filterIsInstance<DeviceAction.OpenUri>().firstOrNull { it.hasHandler }
}
```

`AndroidActionRunner.kt`:
```kotlin
package com.northwalessolutions.netprobe.actions

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidActionRunner(private val context: Context) : HandlerResolver {
    override fun hasHandler(uri: String): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), 0).isNotEmpty()

    /** Returns false when nothing could handle the action; the caller shows the fallback. */
    fun run(action: DeviceAction): Boolean = try {
        when (action) {
            is DeviceAction.OpenUri -> { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            is DeviceAction.OpenWith -> { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            is DeviceAction.Copy -> { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NetProbe", action.text)); true }
        }
    } catch (_: ActivityNotFoundException) { false }
}
```

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*ActionProviderTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add connect actions with handler resolution and fallbacks"
```

---

### Task 19: Vulnerability lookup (NVD)

**Files:**
- Create: `app/src/main/res/raw/cpe_map.json`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/vulns/Cpe.kt`, `NvdClient.kt`, `RateLimiter.kt`, `VulnLookup.kt`
- Create fixture: `app/src/test/resources/fixtures/nvd_response.json`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/vulns/CpeTest.kt`, `NvdParserTest.kt`, `RateLimiterTest.kt`, `VulnLookupTest.kt`

**Interfaces:**
- Consumes: `ServiceInfo` (8), `DeviceIdentity` (16), `VulnCacheRepository` (17) through a small interface.
- Produces:
```kotlin
@Serializable data class CpeEntry(val product: String, val cpe: String)   // "nginx" -> "cpe:2.3:a:f5:nginx:{v}:*:*:*:*:*:*:*"
object Cpe { fun load(json: String): Map<String, String>; fun forService(map: Map<String, String>, s: ServiceInfo): String? }  // null when product or version unknown
sealed class VulnQuery(val text: String) { class ByCpe(cpe: String) : VulnQuery(cpe); class ByKeyword(kw: String) : VulnQuery(kw) }
data class Vuln(val cveId: String, val cvss: Double?, val severity: String, val published: String, val summary: String, val url: String)
sealed class VulnResult { data class Ok(val vulns: List<Vuln>, val total: Int, val fromCache: Boolean, val fetchedAt: Long) : VulnResult()
    data class Failed(val reason: String, val cached: Ok?) : VulnResult() }
fun interface HttpGet { suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> }   // status, body; throws IOException offline
object NvdParser { fun parse(json: String): Pair<List<Vuln>, Int>? }     // null on malformed
class RateLimiter(private val permits: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis) { suspend fun acquire() }
interface VulnCache { suspend fun get(query: String, maxAgeMs: Long): Pair<String, Long>?; suspend fun getAny(query: String): Pair<String, Long>?; suspend fun put(query: String, json: String) }
class VulnLookup(private val http: HttpGet, private val cache: VulnCache, private val apiKey: () -> String?, private val cpeMap: Map<String, String>,
                 private val limiter: RateLimiter = RateLimiter(5, 30_000), private val ttlMs: Long = 24 * 3600_000) {
    fun queryForService(s: ServiceInfo): VulnQuery?            // ByCpe when mappable, ByKeyword "product version" when product known, null otherwise
    fun queryForDevice(d: DeviceIdentity): VulnQuery?          // ByKeyword "vendor model", null when both missing
    suspend fun run(q: VulnQuery): VulnResult }
```
- URL: `https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=50&` + (`cpeName=<urlencoded>` or `keywordSearch=<urlencoded>`). Header `apiKey` only when non-blank. Limiter: 5 per 30 s without key, 50 with key (VulnLookup swaps the limiter when a key is present).
- Severity: from `metrics.cvssMetricV31[0].cvssData.baseScore`/`baseSeverity`, falling back to `cvssMetricV30`, then `cvssMetricV2`; "UNKNOWN" when none. Summary: first English `descriptions[].value`. URL: `https://nvd.nist.gov/vuln/detail/<id>`.

- [ ] **Step 1: Fixture**

`nvd_response.json` (trimmed real shape):
```json
{"resultsPerPage":2,"startIndex":0,"totalResults":2,"format":"NVD_CVE","version":"2.0","timestamp":"2026-10-19T10:00:00.000",
 "vulnerabilities":[
  {"cve":{"id":"CVE-2023-38408","published":"2023-07-20T03:15:00.000","descriptions":[{"lang":"es","value":"..."},{"lang":"en","value":"The PKCS#11 feature in ssh-agent in OpenSSH before 9.3p2 has an insufficiently trustworthy search path."}],
   "metrics":{"cvssMetricV31":[{"cvssData":{"baseScore":9.8,"baseSeverity":"CRITICAL"}}]}}},
  {"cve":{"id":"CVE-2021-41617","published":"2021-09-26T19:15:00.000","descriptions":[{"lang":"en","value":"sshd in OpenSSH 6.2 through 8.7 before 8.8 ... AuthorizedKeysCommand"}],
   "metrics":{"cvssMetricV2":[{"cvssData":{"baseScore":4.4},"baseSeverity":"MEDIUM"}]}}}
 ]}
```

- [ ] **Step 2: Failing tests**

```kotlin
package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CpeTest {
    private val map = Cpe.load(javaClass.getResource("/cpe_map.json")!!.readText())
    @Test fun `map loads required products`() {
        for (p in listOf("OpenSSH", "nginx", "Apache", "lighttpd", "dropbear", "ProFTPD", "vsFTPd", "Samba", "MiniUPnPd", "BusyBox", "Exim", "Postfix", "Dovecot", "Pure-FTPd", "Microsoft-IIS"))
            assertTrue("missing $p", map.keys.any { it.equals(p, ignoreCase = true) })
        map.values.forEach { assertTrue(it.startsWith("cpe:2.3:a:") && it.contains("{v}")) }
    }
    @Test fun `service to cpe`() {
        assertEquals("cpe:2.3:a:openbsd:openssh:9.6p1:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("SSH", "OpenSSH", "9.6p1", null, Confidence.CONFIRMED)))
        assertEquals("cpe:2.3:a:f5:nginx:1.18.0:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.18.0", null, Confidence.CONFIRMED)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", null, null, Confidence.CONFIRMED)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "GoAhead-Webs", "1.0", null, Confidence.CONFIRMED)))
    }
}

class NvdParserTest {
    private val json = javaClass.getResource("/fixtures/nvd_response.json")!!.readText()
    @Test fun `parses vulns sorted by score desc with severity fallbacks`() {
        val (v, total) = NvdParser.parse(json)!!
        assertEquals(2, total); assertEquals("CVE-2023-38408", v[0].cveId); assertEquals(9.8, v[0].cvss!!, 0.01); assertEquals("CRITICAL", v[0].severity)
        assertTrue(v[0].summary.startsWith("The PKCS#11")); assertEquals("https://nvd.nist.gov/vuln/detail/CVE-2023-38408", v[0].url)
        assertEquals(4.4, v[1].cvss!!, 0.01); assertEquals("MEDIUM", v[1].severity); assertEquals("2021-09-26", v[1].published.take(10))
    }
    @Test fun `malformed yields null`() { assertNull(NvdParser.parse("{")); assertNull(NvdParser.parse("")); assertNull(NvdParser.parse("""{"vulnerabilities": "nope"}""")) }
    @Test fun `empty result list is ok`() { assertEquals(0 to 0, NvdParser.parse("""{"totalResults":0,"vulnerabilities":[]}""")!!.let { it.first.size to it.second }) }
}

class RateLimiterTest {
    @Test fun `blocks the sixth call inside the window`() = runBlocking {
        var now = 0L; val sleeps = mutableListOf<Long>()
        val rl = RateLimiter(5, 30_000, clock = { now }, sleeper = { ms -> sleeps += ms; now += ms })
        repeat(5) { rl.acquire() }; assertTrue(sleeps.isEmpty())
        rl.acquire(); assertEquals(1, sleeps.size); assertTrue(sleeps[0] in 29_000..30_000)
    }
}

class VulnLookupTest {
    private class FakeCache : VulnCache {
        val store = HashMap<String, Pair<String, Long>>()
        override suspend fun get(query: String, maxAgeMs: Long) = store[query]?.takeIf { System.currentTimeMillis() - it.second <= maxAgeMs }
        override suspend fun getAny(query: String) = store[query]
        override suspend fun put(query: String, json: String) { store[query] = json to System.currentTimeMillis() }
    }
    private val json = javaClass.getResource("/fixtures/nvd_response.json")!!.readText()
    private val cpeMap = Cpe.load(javaClass.getResource("/cpe_map.json")!!.readText())
    private val ssh = ServiceInfo("SSH", "OpenSSH", "9.6p1", null, Confidence.CONFIRMED)

    @Test fun `builds queries`() {
        val l = VulnLookup({ _, _ -> 200 to json }, FakeCache(), { null }, cpeMap)
        assertTrue(l.queryForService(ssh) is VulnQuery.ByCpe)
        assertEquals("GoAhead-Webs 2.5", l.queryForService(ServiceInfo("HTTP", "GoAhead-Webs", "2.5", null, Confidence.CONFIRMED))!!.text)
        assertNull(l.queryForService(ServiceInfo("VNC", confidence = Confidence.PORT_TABLE)))
        assertEquals("TP-Link Archer AX55", l.queryForDevice(DeviceIdentity("TP-Link", "Archer AX55", null, DeviceType.ROUTER, 100, emptyList()))!!.text)
        assertNull(l.queryForDevice(DeviceIdentity.UNKNOWN))
    }
    @Test fun `fetches parses caches and sends api key header`() = runBlocking {
        var seenUrl = ""; var seenHeaders = emptyMap<String, String>(); var calls = 0
        val cache = FakeCache()
        val l = VulnLookup({ u, h -> calls++; seenUrl = u; seenHeaders = h; 200 to json }, cache, { "KEY123" }, cpeMap)
        val r = l.run(l.queryForService(ssh)!!) as VulnResult.Ok
        assertEquals(2, r.vulns.size); assertFalse(r.fromCache)
        assertTrue(seenUrl.startsWith("https://services.nvd.nist.gov/rest/json/cves/2.0?")); assertTrue(seenUrl.contains("cpeName=cpe%3A2.3%3Aa%3Aopenbsd%3Aopenssh%3A9.6p1"))
        assertEquals("KEY123", seenHeaders["apiKey"])
        val again = l.run(l.queryForService(ssh)!!) as VulnResult.Ok
        assertTrue(again.fromCache); assertEquals(1, calls)
    }
    @Test fun `keyword query is url encoded`() = runBlocking {
        var seenUrl = ""
        val l = VulnLookup({ u, _ -> seenUrl = u; 200 to json }, FakeCache(), { null }, cpeMap)
        l.run(VulnQuery.ByKeyword("TP-Link Archer AX55")); assertTrue(seenUrl.contains("keywordSearch=TP-Link+Archer+AX55") || seenUrl.contains("keywordSearch=TP-Link%20Archer%20AX55"))
    }
    @Test fun `offline with cache serves cache and says so`() = runBlocking {
        val cache = FakeCache(); cache.store["kw"] = json to (System.currentTimeMillis() - 48 * 3600_000)   // stale
        val l = VulnLookup({ _, _ -> throw IOException("no network") }, cache, { null }, cpeMap)
        val r = l.run(VulnQuery.ByKeyword("kw")) as VulnResult.Failed
        assertEquals("Offline", r.reason); assertEquals(2, r.cached!!.vulns.size); assertTrue(r.cached!!.fromCache)
    }
    @Test fun `offline without cache fails plainly; 403 and 429 are rate limit messages; malformed body is a parse failure`() = runBlocking {
        assertEquals("Offline", (VulnLookup({ _, _ -> throw IOException() }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason)
        assertTrue((VulnLookup({ _, _ -> 403 to "" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("rate limit", true))
        assertTrue((VulnLookup({ _, _ -> 429 to "" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("rate limit", true))
        assertTrue((VulnLookup({ _, _ -> 200 to "<html>" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("unexpected", true))
    }
}
```
`RateLimiter` gets an injectable `sleeper: suspend (Long) -> Unit = { delay(it) }` as the test shows.

- [ ] **Step 3: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*vulns*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 4: Implement**

`cpe_map.json`:
```json
[
 {"product":"OpenSSH","cpe":"cpe:2.3:a:openbsd:openssh:{v}:*:*:*:*:*:*:*"},
 {"product":"nginx","cpe":"cpe:2.3:a:f5:nginx:{v}:*:*:*:*:*:*:*"},
 {"product":"Apache","cpe":"cpe:2.3:a:apache:http_server:{v}:*:*:*:*:*:*:*"},
 {"product":"lighttpd","cpe":"cpe:2.3:a:lighttpd:lighttpd:{v}:*:*:*:*:*:*:*"},
 {"product":"dropbear","cpe":"cpe:2.3:a:dropbear_ssh_project:dropbear_ssh:{v}:*:*:*:*:*:*:*"},
 {"product":"ProFTPD","cpe":"cpe:2.3:a:proftpd:proftpd:{v}:*:*:*:*:*:*:*"},
 {"product":"vsFTPd","cpe":"cpe:2.3:a:vsftpd_project:vsftpd:{v}:*:*:*:*:*:*:*"},
 {"product":"Samba","cpe":"cpe:2.3:a:samba:samba:{v}:*:*:*:*:*:*:*"},
 {"product":"MiniUPnPd","cpe":"cpe:2.3:a:miniupnp_project:miniupnpd:{v}:*:*:*:*:*:*:*"},
 {"product":"BusyBox","cpe":"cpe:2.3:a:busybox:busybox:{v}:*:*:*:*:*:*:*"},
 {"product":"Exim","cpe":"cpe:2.3:a:exim:exim:{v}:*:*:*:*:*:*:*"},
 {"product":"Postfix","cpe":"cpe:2.3:a:postfix:postfix:{v}:*:*:*:*:*:*:*"},
 {"product":"Dovecot","cpe":"cpe:2.3:a:dovecot:dovecot:{v}:*:*:*:*:*:*:*"},
 {"product":"Pure-FTPd","cpe":"cpe:2.3:a:pureftpd:pure-ftpd:{v}:*:*:*:*:*:*:*"},
 {"product":"Microsoft-IIS","cpe":"cpe:2.3:a:microsoft:internet_information_services:{v}:*:*:*:*:*:*:*"}
]
```

`Cpe.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class CpeEntry(val product: String, val cpe: String)
object Cpe {
    fun load(json: String): Map<String, String> = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<CpeEntry>>(json).associate { it.product to it.cpe } }.getOrDefault(emptyMap())
    fun forService(map: Map<String, String>, s: ServiceInfo): String? {
        val product = s.product ?: return null; val version = s.version ?: return null
        val tpl = map.entries.firstOrNull { it.key.equals(product, ignoreCase = true) }?.value ?: return null
        return tpl.replace("{v}", version.lowercase())
    }
}
```

`RateLimiter.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.vulns

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RateLimiter(private val permits: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis,
                  private val sleeper: suspend (Long) -> Unit = { delay(it) }) {
    private val stamps = ArrayDeque<Long>(); private val mutex = Mutex()
    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = clock()
            while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
            if (stamps.size < permits) { stamps.addLast(now); return@withLock }
            sleeper(windowMs - (now - stamps.first()))
        }
    }
}
```

`NvdClient.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.vulns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

data class Vuln(val cveId: String, val cvss: Double?, val severity: String, val published: String, val summary: String, val url: String)
fun interface HttpGet { suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> }

object NvdParser {
    fun parse(json: String): Pair<List<Vuln>, Int>? = try {
        val root = Json.parseToJsonElement(json).jsonObject
        val total = root["totalResults"]?.jsonPrimitive?.intOrNull ?: 0
        val list = root["vulnerabilities"]?.jsonArray ?: return null
        val vulns = list.mapNotNull { item ->
            val cve = item.jsonObject["cve"]?.jsonObject ?: return@mapNotNull null
            val id = cve["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val metrics = cve["metrics"]?.jsonObject
            fun first(key: String) = metrics?.get(key)?.jsonArray?.firstOrNull()?.jsonObject
            val m31 = first("cvssMetricV31") ?: first("cvssMetricV30"); val m2 = first("cvssMetricV2")
            val score = (m31 ?: m2)?.get("cvssData")?.jsonObject?.get("baseScore")?.jsonPrimitive?.doubleOrNull
            val severity = m31?.get("cvssData")?.jsonObject?.get("baseSeverity")?.jsonPrimitive?.contentOrNull ?: m2?.get("baseSeverity")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            val summary = cve["descriptions"]?.jsonArray?.firstOrNull { it.jsonObject["lang"]?.jsonPrimitive?.content == "en" }?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
            Vuln(id, score, severity, cve["published"]?.jsonPrimitive?.contentOrNull ?: "", summary, "https://nvd.nist.gov/vuln/detail/$id")
        }.sortedByDescending { it.cvss ?: -1.0 }
        vulns to total
    } catch (_: Exception) { null }
}

class UrlHttpGet(private val timeoutMs: Int = 15_000) : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String> = runInterruptible(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("User-Agent", "NetProbe/0.1")
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        code to body
    }
}
```

`VulnLookup.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import java.io.IOException
import java.net.URLEncoder

sealed class VulnQuery(val text: String) { class ByCpe(cpe: String) : VulnQuery(cpe); class ByKeyword(kw: String) : VulnQuery(kw) }
sealed class VulnResult {
    data class Ok(val vulns: List<Vuln>, val total: Int, val fromCache: Boolean, val fetchedAt: Long) : VulnResult()
    data class Failed(val reason: String, val cached: Ok?) : VulnResult()
}
interface VulnCache {
    suspend fun get(query: String, maxAgeMs: Long): Pair<String, Long>?
    suspend fun getAny(query: String): Pair<String, Long>?
    suspend fun put(query: String, json: String)
}

class VulnLookup(
    private val http: HttpGet, private val cache: VulnCache, private val apiKey: () -> String?, private val cpeMap: Map<String, String>,
    private val limiterNoKey: RateLimiter = RateLimiter(5, 30_000), private val limiterKey: RateLimiter = RateLimiter(50, 30_000), private val ttlMs: Long = 24 * 3600_000,
) {
    companion object { const val BASE = "https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=50&" }

    fun queryForService(s: ServiceInfo): VulnQuery? {
        Cpe.forService(cpeMap, s)?.let { return VulnQuery.ByCpe(it) }
        val p = s.product ?: return null
        return VulnQuery.ByKeyword(listOfNotNull(p, s.version).joinToString(" "))
    }
    fun queryForDevice(d: DeviceIdentity): VulnQuery? {
        val kw = listOfNotNull(d.vendor, d.model).joinToString(" ").trim()
        return if (kw.isEmpty()) null else VulnQuery.ByKeyword(kw)
    }

    suspend fun run(q: VulnQuery): VulnResult {
        val key = "${q::class.simpleName}:${q.text}"
        cache.get(key, ttlMs)?.let { (json, at) -> NvdParser.parse(json)?.let { (v, t) -> return VulnResult.Ok(v, t, fromCache = true, fetchedAt = at) } }
        val stale = cache.getAny(key)?.let { (json, at) -> NvdParser.parse(json)?.let { (v, t) -> VulnResult.Ok(v, t, true, at) } }
        val param = when (q) { is VulnQuery.ByCpe -> "cpeName=" ; is VulnQuery.ByKeyword -> "keywordSearch=" } + URLEncoder.encode(q.text, "UTF-8")
        val k = apiKey()?.takeIf { it.isNotBlank() }
        (if (k != null) limiterKey else limiterNoKey).acquire()
        val (code, body) = try { http.get(BASE + param, if (k != null) mapOf("apiKey" to k) else emptyMap()) }
            catch (e: IOException) { return VulnResult.Failed("Offline", stale) }
            catch (e: Exception) { return VulnResult.Failed("Offline", stale) }
        return when {
            code == 403 || code == 429 -> VulnResult.Failed("NVD rate limit reached, try again in 30 seconds or add an API key in Settings", stale)
            code != 200 -> VulnResult.Failed("NVD returned HTTP $code", stale)
            else -> NvdParser.parse(body)?.let { (v, t) -> cache.put(key, body); VulnResult.Ok(v, t, false, System.currentTimeMillis()) }
                ?: VulnResult.Failed("NVD returned an unexpected response", stale)
        }
    }
}
```
Add `src/main/res/raw` to test resources (done in Task 5) so `/cpe_map.json` resolves.

- [ ] **Step 5: Run, expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*vulns*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add NVD vulnerability lookup with CPE mapping, rate limiting and cache"
```

---

### Task 20: ViewModels and Compose screens

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Theme.kt`, `ui/Nav.kt`
- Create: `ui/home/HomeViewModel.kt`, `ui/home/HomeScreen.kt`, `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/vulns/VulnSheet.kt`
- Modify: `MainActivity.kt`
- Test (JVM): `app/src/test/java/com/northwalessolutions/netprobe/ui/home/HomeFilterTest.kt`, `HomeViewModelTest.kt`
- Test (device): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 4, 6, 8, 15, 16, 17, 18, 19 through `AppContainer` (Task 21 adds the properties; this task declares what it needs on `AppContainer` and Task 21 provides them: `networkEnvMonitor`, `discoveryEngine`, `portScanEngine`, `serviceIdentifier`, `deviceIdentifier`, `networks`, `hosts`, `ports`, `actions`, `actionRunner`, `vulnLookup`, `settings`, `ouiLookup: (String) -> String?` which returns null in the MVP).
- Produces:
```kotlin
enum class StatusFilter { ALL, INTERESTING, INVESTIGATED, IGNORED, UNMARKED }
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?)
object HomeFilter { fun apply(rows: List<HostRow>, filter: StatusFilter, query: String): List<HostRow> }   // gateway first, then by numeric IP
data class HomeUiState(val env: NetworkEnv?, val scanning: Boolean, val status: DiscoveryStatus?, val rows: List<HostRow>, val filter: StatusFilter, val query: String, val message: String?, val networkName: String?)
class HomeViewModel(container: AppContainer) : ViewModel() { val state: StateFlow<HomeUiState>; fun startScan(); fun stopScan(); fun quickScanAll(); fun setFilter(f: StatusFilter); fun setQuery(q: String); fun dismissMessage() }
data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>)
data class DetailUiState(val host: HostEntity?, val displayName: String, val ports: List<PortRow>, val scanning: Boolean, val progress: ScanProgress?, val profile: ScanProfile, val customPortsText: String, val customError: String?, val message: String?, val vuln: VulnSheetState?)
sealed class VulnSheetState { data class Loading(val query: String) : VulnSheetState(); data class Ready(val query: String, val result: VulnResult) : VulnSheetState() }
class DetailViewModel(container: AppContainer, hostId: Long) : ViewModel() { val state: StateFlow<DetailUiState>; fun startScan(); fun stopScan(); fun setProfile(p: ScanProfile); fun setCustomPorts(t: String)
    fun setStatus(s: HostStatus); fun setNote(n: String); fun setCustomName(n: String?); fun runAction(a: DeviceAction); fun lookupVulnsForService(port: Int); fun lookupVulnsForDevice(); fun rerunVulnQuery(text: String); fun closeVulns() }
```
- Identity line (spec 5.4.1): `listOfNotNull(identVendor, identModel).joinToString(" ")`, else `listOfNotNull(identOs, type.label).joinToString(" ")`, else `"Unknown device"` plus `", found via mDNS"` when the host has only mDNS evidence. `type.label` is "Router", "Phone", "Computer", "Printer", "Media", "IoT", "NAS", "Camera", "Server", "".
- Scan lifecycle in `HomeViewModel`: `startScan` creates `runId = now`, ensures the network, collects `discoveryEngine.discover` and upserts each host, then for each upserted host runs `deviceIdentifier.identify` with `HostEvidence(host, probedOpenPorts, emptyMap(), ouiLookup(mac))` and stores it. When `env` becomes null or changes signature during a scan: cancel the scan job, set `message = "Network changed, scan stopped"`. Hosts whose `lastDiscoveryRun` is older than the latest run are `stale = true` (dimmed).
- `quickScanAll`: sequentially for each row, run `portScanEngine.scan(ip, Quick)`, upsert OPEN ports with `serviceIdentifier.identify`, then re-identify the device with the services map.
- `DetailViewModel.startScan`: same per-host flow with the selected profile; after the scan, `ports.markScanned`, re-identify.
- Pinned layout (spec 5.7, Director's standing rule): Home = `Scaffold` with a `topBar` column holding the network panel, Scan/Stop button and progress, then the filter row, then `LazyColumn` for the list. Detail = `Scaffold` whose `topBar` holds the header and the scan bar; only the ports list scrolls. No action button lives inside a `LazyColumn` except the per-row port actions, which belong to their rows.

- [ ] **Step 1: Failing JVM tests**

```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

class HomeFilterTest {
    private fun row(ip: String, name: String = ip, status: HostStatus = HostStatus.UNMARKED, gw: Boolean = false, identity: String = "Unknown device") =
        HostRow(ip.hashCode().toLong(), ip, name, identity, DeviceType.UNKNOWN, 0, status, gw, false, null)
    private val rows = listOf(row("192.168.1.20", "pc", HostStatus.INTERESTING), row("192.168.1.3", "tv", identity = "Samsung QE55"), row("192.168.1.1", "router", gw = true), row("192.168.1.100", "cam", HostStatus.IGNORED))

    @Test fun `gateway first then numeric ip order`() {
        assertEquals(listOf("192.168.1.1", "192.168.1.3", "192.168.1.20", "192.168.1.100"), HomeFilter.apply(rows, StatusFilter.ALL, "").map { it.ip })
    }
    @Test fun `status filters`() {
        assertEquals(listOf("192.168.1.20"), HomeFilter.apply(rows, StatusFilter.INTERESTING, "").map { it.ip })
        assertEquals(listOf("192.168.1.1", "192.168.1.3"), HomeFilter.apply(rows, StatusFilter.UNMARKED, "").map { it.ip })
        assertTrue(HomeFilter.apply(rows, StatusFilter.INVESTIGATED, "").isEmpty())
    }
    @Test fun `query matches name ip and identity case-insensitively`() {
        assertEquals(listOf("192.168.1.3"), HomeFilter.apply(rows, StatusFilter.ALL, "samsung").map { it.ip })
        assertEquals(listOf("192.168.1.20"), HomeFilter.apply(rows, StatusFilter.ALL, "PC").map { it.ip })
        assertEquals(listOf("192.168.1.1", "192.168.1.100"), HomeFilter.apply(rows, StatusFilter.ALL, ".1").map { it.ip }.filter { it.endsWith(".1") || it.endsWith(".100") })
    }
    @Test fun `identity line rules`() {
        assertEquals("Apple MacBookPro18,3", IdentityLine.of("Apple", "MacBookPro18,3", null, DeviceType.COMPUTER, onlyMdns = false))
        assertEquals("Android Phone", IdentityLine.of(null, null, "Android", DeviceType.PHONE, onlyMdns = false))
        assertEquals("Printer", IdentityLine.of(null, null, null, DeviceType.PRINTER, onlyMdns = false))
        assertEquals("Unknown device, found via mDNS", IdentityLine.of(null, null, null, DeviceType.UNKNOWN, onlyMdns = true))
        assertEquals("Unknown device", IdentityLine.of(null, null, null, DeviceType.UNKNOWN, onlyMdns = false))
    }
}
```

```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    /** The scan controller is the testable core of HomeViewModel: it owns the job and reacts to env changes. */
    @Test fun `network loss cancels an active scan and sets the message`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        var cancelled = false
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true } })
        c.start(); advanceUntilIdle(); assertTrue(c.state.value.scanning)
        env.value = null; advanceUntilIdle()
        assertTrue(cancelled); assertFalse(c.state.value.scanning); assertEquals("Network changed, scan stopped", c.state.value.message)
    }
    @Test fun `switching networks mid scan also cancels`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c.start(); advanceUntilIdle(); env.value = TestEnvs.office; advanceUntilIdle()
        assertFalse(c.state.value.scanning); assertNotNull(c.state.value.message)
    }
    @Test fun `stop clears scanning without a message and a completed scan clears scanning`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
        val c = ScanController(scope = backgroundScope, env = env, runDiscovery = { })
        c.start(); advanceUntilIdle(); assertFalse(c.state.value.scanning); assertNull(c.state.value.message)
        val c2 = ScanController(scope = backgroundScope, env = env, runDiscovery = { kotlinx.coroutines.awaitCancellation() })
        c2.start(); advanceUntilIdle(); c2.stop(); advanceUntilIdle(); assertFalse(c2.state.value.scanning); assertNull(c2.state.value.message)
    }
    @Test fun `start without a network sets a message and does not scan`() = runTest {
        val c = ScanController(scope = backgroundScope, env = MutableStateFlow(null), runDiscovery = { fail("must not run") })
        c.start(); advanceUntilIdle(); assertFalse(c.state.value.scanning); assertEquals("Connect to Wi-Fi or Ethernet to scan", c.state.value.message)
    }
}
```
`TestEnvs` is a test helper with two `NetworkEnv` values differing in gateway. `ScanController` is a small class in `ui/home/ScanController.kt`:
```kotlin
class ScanController(private val scope: CoroutineScope, private val env: StateFlow<NetworkEnv?>, private val runDiscovery: suspend (NetworkEnv) -> Unit) {
    data class State(val scanning: Boolean = false, val message: String? = null)
    val state: StateFlow<State>; fun start(); fun stop(); fun dismissMessage() }
```
It launches `runDiscovery` in `scope`, and a watcher that cancels the job when `env` becomes null or its `NetworkSignature` changes, setting the message. `HomeViewModel` composes it with the real discovery pipeline.

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*HomeFilterTest*' --tests '*HomeViewModelTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement the ViewModels**

`ui/home/ScanController.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScanController(private val scope: CoroutineScope, private val env: StateFlow<NetworkEnv?>, private val runDiscovery: suspend (NetworkEnv) -> Unit) {
    data class State(val scanning: Boolean = false, val message: String? = null)
    private val _state = MutableStateFlow(State()); val state: StateFlow<State> = _state
    private var job: Job? = null; private var watcher: Job? = null

    fun start() {
        val e = env.value ?: run { _state.value = State(false, "Connect to Wi-Fi or Ethernet to scan"); return }
        if (job?.isActive == true) return
        val sig = NetworkSignature.of(e)
        _state.value = State(scanning = true)
        job = scope.launch {
            try { runDiscovery(e) } finally { if (isActive || _state.value.scanning) _state.update { it.copy(scanning = false) } }
        }
        watcher = scope.launch {
            env.drop(1).first { it == null || NetworkSignature.of(it) != sig }
            job?.cancel(); _state.value = State(false, "Network changed, scan stopped")
        }
        job!!.invokeOnCompletion { watcher?.cancel() }
    }
    fun stop() { job?.cancel(); watcher?.cancel(); _state.value = State(false, null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
}
```

`ui/home/HomeViewModel.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryOptions
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.portscan.PortState
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.Inet4Address
import java.net.InetAddress

enum class StatusFilter(val label: String) { ALL("All"), INTERESTING("Interesting"), INVESTIGATED("Investigated"), IGNORED("Ignored"), UNMARKED("Unmarked") }
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?)
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false)

object IdentityLine {
    val DeviceType.label: String get() = when (this) { DeviceType.ROUTER -> "Router"; DeviceType.PHONE -> "Phone"; DeviceType.COMPUTER -> "Computer"; DeviceType.PRINTER -> "Printer"
        DeviceType.MEDIA -> "Media"; DeviceType.IOT -> "IoT"; DeviceType.NAS -> "NAS"; DeviceType.CAMERA -> "Camera"; DeviceType.SERVER -> "Server"; DeviceType.UNKNOWN -> "" }
    fun of(vendor: String?, model: String?, os: String?, type: DeviceType, onlyMdns: Boolean): String {
        val vm = listOfNotNull(vendor, model).joinToString(" ").trim(); if (vm.isNotEmpty()) return vm
        val ot = listOfNotNull(os, type.label.takeIf { it.isNotEmpty() }).joinToString(" ").trim(); if (ot.isNotEmpty()) return ot
        return if (onlyMdns) "Unknown device, found via mDNS" else "Unknown device"
    }
}

object HomeFilter {
    private fun ipKey(ip: String): Long = ip.split('.').fold(0L) { a, p -> a * 256 + (p.toLongOrNull() ?: 0) }
    fun apply(rows: List<HostRow>, filter: StatusFilter, query: String): List<HostRow> {
        val q = query.trim().lowercase()
        return rows.filter { r -> when (filter) { StatusFilter.ALL -> true; StatusFilter.INTERESTING -> r.status == HostStatus.INTERESTING
                StatusFilter.INVESTIGATED -> r.status == HostStatus.INVESTIGATED; StatusFilter.IGNORED -> r.status == HostStatus.IGNORED; StatusFilter.UNMARKED -> r.status == HostStatus.UNMARKED } }
            .filter { r -> q.isEmpty() || r.displayName.lowercase().contains(q) || r.ip.contains(q) || r.identityLine.lowercase().contains(q) || (r.mac?.contains(q) == true) }
            .sortedWith(compareByDescending<HostRow> { it.isGateway }.thenBy { ipKey(it.ip) })
    }
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val env = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    private val filter = MutableStateFlow(StatusFilter.ALL); private val query = MutableStateFlow("")
    private val networkId = MutableStateFlow<Long?>(null); private val latestRun = MutableStateFlow(0L)
    private val quickScanning = MutableStateFlow(false); private val extraMessage = MutableStateFlow<String?>(null)
    private val controller = ScanController(viewModelScope, env) { e -> discover(e) }
    private val portCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }

    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quickScanning, extraMessage, portCounts) { arr ->
        @Suppress("UNCHECKED_CAST")
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus; val hs = arr[3] as List<HostEntity>
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as Boolean; val msg = arr[8] as String?; val pc = arr[9] as Map<Long, Int>
        val rows = hs.map { h -> HostRow(h.id, h.ip, DisplayName.of(h), IdentityLine.of(h.identVendor, h.identModel, h.identOs, h.deviceType, onlyMdns = h.mdnsName != null && h.netbiosName == null && h.upnpName == null && h.mac == null),
            h.deviceType, pc[h.id] ?: 0, h.status, h.isGateway, stale = run > 0 && h.lastDiscoveryRun < run, mac = h.mac) }
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, HomeFilter.apply(rows, f, q), f, q, cs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    init { viewModelScope.launch { env.collect { e -> networkId.value = e?.let { c.networks.ensure(it).id } } } }

    fun startScan() = controller.start()
    fun stopScan() = controller.stop()
    fun setFilter(f: StatusFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun dismissMessage() { controller.dismissMessage(); extraMessage.value = null }

    private suspend fun discover(e: NetworkEnv) {
        val net = c.networks.ensure(e); networkId.value = net.id
        val runId = System.currentTimeMillis(); latestRun.value = runId
        val s = c.settings.state.value
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, s.maxPrefix)
        c.discoveryEngine.discover(e, opts).collect { h ->
            val id = c.hosts.upsertDiscovered(net.id, h, runId)
            val merged = c.hosts.byIdOnce(id) ?: return@collect
            c.hosts.setIdentity(id, c.deviceIdentifier.identify(HostEvidence(mergedToDiscovered(merged, h), h.probedOpenPorts, emptyMap(), merged.mac?.let(c.ouiLookup))))
        }
        val st = c.discoveryEngine.status.value
        if (st.clampedFrom != null) extraMessage.value = "Large network (/${st.clampedFrom}). Scanned your /24 only. Widen in Settings."
    }

    fun quickScanAll() { if (quickScanning.value) return
        viewModelScope.launch {
            quickScanning.value = true
            try {
                val net = networkId.value ?: return@launch
                for (h in c.hosts.hostsForNetwork(net).first()) {
                    val ip = InetAddress.getByName(h.ip) as Inet4Address
                    c.portScanEngine.scan(ip, ScanProfile.Quick, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                        c.ports.upsertOpen(h.id, r.port, c.serviceIdentifier.identify(ip, r.port, Evidence()))
                        portCounts.update { it + (h.id to (it[h.id] ?: 0) + 1) }
                    }
                    c.ports.markScanned(h.id)
                }
            } finally { quickScanning.value = false }
        }
    }
}
```
`mergedToDiscovered(entity, latest)` builds a `DiscoveredHost` from the stored entity's names and the latest evidence so identification sees the merged picture; implement it in `HomeViewModel.kt` as a private function copying each stored field into the `DiscoveredHost` constructor and parsing `mdnsTxtJson`. `c.settings.scanOptions()` is an extension in `data/Settings.kt`: `fun AppSettings.scanOptions() = ScanOptions(state.value.scanConcurrency, state.value.scanTimeoutMs)`. Port counts for rows come from a `PortDao` query `SELECT hostId, COUNT(*) AS n FROM ports GROUP BY hostId` exposed as `fun counts(): Flow<List<PortCount>>`; replace the `portCounts` state with that flow once the DAO exists (do it in this task).

`ui/detail/DetailViewModel.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.portscan.*
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.vulns.VulnQuery
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.Inet4Address
import java.net.InetAddress

data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>)
sealed class VulnSheetState { data class Loading(val query: String) : VulnSheetState(); data class Ready(val query: String, val result: VulnResult) : VulnSheetState() }
data class DetailUiState(val host: HostEntity? = null, val displayName: String = "", val ports: List<PortRow> = emptyList(), val scanning: Boolean = false, val progress: ScanProgress? = null,
                         val profile: ScanProfile = ScanProfile.Quick, val customPortsText: String = "", val customError: String? = null, val message: String? = null, val vuln: VulnSheetState? = null)

class DetailViewModel(private val c: AppContainer, private val hostId: Long) : ViewModel() {
    private val profile = MutableStateFlow<ScanProfile>(ScanProfile.Quick); private val customText = MutableStateFlow(""); private val customError = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false); private val message = MutableStateFlow<String?>(null); private val vuln = MutableStateFlow<VulnSheetState?>(null)
    private var scanJob: Job? = null

    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), scanning, c.portScanEngine.progress, profile, customText, customError, message, vuln) { arr ->
        @Suppress("UNCHECKED_CAST")
        val h = arr[0] as HostEntity?; val ps = arr[1] as List<PortEntity>; val sc = arr[2] as Boolean; val pr = arr[3] as ScanProgress?
        val rows = ps.map { p -> val info = ServiceInfo(p.serviceName, p.product, p.version, p.detail, p.confidence)
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList()) }
        DetailUiState(h, h?.let { DisplayName.of(it) } ?: "", rows, sc, if (sc) pr else null, arr[4] as ScanProfile, arr[5] as String, arr[6] as String?, arr[7] as String?, arr[8] as VulnSheetState?)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    fun setProfile(p: ScanProfile) { profile.value = p }
    fun setCustomPorts(t: String) { customText.value = t; customError.value = null }
    fun startScan() {
        if (scanJob?.isActive == true) return
        val h = state.value.host ?: return
        val prof = if (profile.value is ScanProfile.Custom || (profile.value == ScanProfile.Quick && customText.value.isNotBlank() && false)) profile.value else profile.value
        val resolved = if (prof is ScanProfile.Custom) ProfileParser.parse(customText.value).getOrElse { customError.value = it.message; return } else prof
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                val ip = InetAddress.getByName(h.ip) as Inet4Address
                val found = HashMap<Int, ServiceInfo>()
                c.portScanEngine.scan(ip, resolved, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                    val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = c.hosts.mdnsTypesFor(hostId)))
                    found[r.port] = info; c.ports.upsertOpen(hostId, r.port, info)
                }
                c.ports.markScanned(hostId)
                c.hosts.byIdOnce(hostId)?.let { e -> c.hosts.setIdentity(hostId, c.deviceIdentifier.identify(HostEvidence(c.hosts.toDiscovered(e), found.keys, found, e.mac?.let(c.ouiLookup)))) }
            } finally { scanning.value = false }
        }
    }
    fun stopScan() { scanJob?.cancel(); scanning.value = false }
    fun setStatus(s: HostStatus) { viewModelScope.launch { c.hosts.setStatus(hostId, s) } }
    fun setNote(n: String) { viewModelScope.launch { c.hosts.setNote(hostId, n) } }
    fun setCustomName(n: String?) { viewModelScope.launch { c.hosts.setCustomName(hostId, n) } }
    fun runAction(a: DeviceAction) { if (!c.actionRunner.run(a)) message.value = "No app installed for that link. IP copied instead." .also { c.actionRunner.run(DeviceAction.Copy("Copy IP", state.value.host?.ip ?: "")) } }
    fun dismissMessage() { message.value = null }

    fun lookupVulnsForService(port: Int) { val row = state.value.ports.firstOrNull { it.port == port } ?: return
        val q = c.vulnLookup.queryForService(row.service) ?: run { message.value = "No product or version known for port $port"; return }; runVuln(q) }
    fun lookupVulnsForDevice() { val h = state.value.host ?: return
        val q = c.vulnLookup.queryForDevice(com.northwalessolutions.netprobe.engine.identify.DeviceIdentity(h.identVendor, h.identModel, h.identOs, h.deviceType, h.identConfidence, h.identReasons))
            ?: run { message.value = "Device not identified well enough to search"; return }; runVuln(q) }
    fun rerunVulnQuery(text: String) { if (text.isNotBlank()) runVuln(VulnQuery.ByKeyword(text.trim())) }
    fun closeVulns() { vuln.value = null }
    private fun runVuln(q: VulnQuery) { vuln.value = VulnSheetState.Loading(q.text); viewModelScope.launch { vuln.value = VulnSheetState.Ready(q.text, c.vulnLookup.run(q)) } }
}
```
Tidy the `prof`/`resolved` lines while implementing: `resolved` is `ProfileParser.parse(customText)` when the selected profile is Custom, otherwise the selected profile. Add to `HostRepository`: `suspend fun byIdOnce(id: Long): HostEntity?`, `suspend fun mdnsTypesFor(id: Long): Set<String>` (keys of the parsed `mdnsTxtJson`), and `fun toDiscovered(e: HostEntity): DiscoveredHost`.

- [ ] **Step 4: Compose screens**

`ui/theme/Theme.kt`: Material 3 `NetProbeTheme` using `dynamicDarkColorScheme`/`dynamicLightColorScheme` on API 31+, else `darkColorScheme()`/`lightColorScheme()`, following `isSystemInDarkTheme()`.

`ui/Nav.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen

@Composable
fun NetProbeNav(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") }) }
        composable("host/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { DetailScreen(container, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
```

`ui/home/HomeScreen.kt` (structure; fill in Material 3 components):
```kotlin
@Composable
fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {   // PINNED: never inside the list
                NetworkPanel(s.env, s.networkName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (s.scanning) vm.stopScan() else vm.startScan() }, enabled = s.env != null || s.scanning, modifier = Modifier.testTag("scanButton")) { Text(if (s.scanning) "Stop" else "Scan") }
                    Spacer(Modifier.width(12.dp))
                    s.status?.let { Text("Found ${it.found}, probing ${it.probed}/${it.total}", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.weight(1f))
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (s.quickScanning) "Quick scanning all..." else "Quick scan all") }, enabled = !s.quickScanning && s.rows.isNotEmpty(), onClick = { menu = false; vm.quickScanAll() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                    }
                }
                if (s.scanning) LinearProgressIndicator(progress = { s.status?.let { st -> if (st.total == 0) 0f else st.probed / st.total.toFloat() } ?: 0f }, Modifier.fillMaxWidth())
                FilterRow(s.filter, vm::setFilter); OutlinedTextField(s.query, vm::setQuery, Modifier.fillMaxWidth().testTag("search"), placeholder = { Text("Search name, IP, vendor, service") }, singleLine = true)
            }
        },
    ) { pad ->
        when {
            s.env == null && !s.scanning -> EmptyState("Connect to Wi-Fi or Ethernet to scan", pad)
            s.rows.isEmpty() -> EmptyState(if (s.scanning) "Looking for devices..." else "No devices found yet, tap Scan", pad)
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("deviceList")) { items(s.rows, key = { it.id }) { r -> HostRowItem(r, onClick = { onOpenHost(r.id) }) } }
        }
    }
}
```
`HostRowItem`: `ListItem` with leading icon by `DeviceType` (Router: `Icons.Default.Router`, Phone: `Smartphone`, Computer: `Computer`, Printer: `Print`, Media: `Tv`, IoT: `Sensors`, NAS: `Storage`, Camera: `Videocam`, Server: `Dns`, Unknown: `DeviceUnknown`), headline = displayName, supporting = `"$ip  ·  $identityLine"` written with a middle dot character (not an em-dash), trailing = open-port badge (`"$openPorts open"` when > 0) and a status `AssistChip` when not UNMARKED. `alpha(0.5f)` when `stale`. Gateway row gets a small "Gateway" label.
`FilterRow`: `Row` of `FilterChip`s for each `StatusFilter`, horizontally scrollable.
`NetworkPanel`: two lines: `ssid ?: "Wi-Fi"` with transport, and `"$ip/$prefix  gateway $gw"`; shows "No network" when env is null.

`ui/detail/DetailScreen.kt` (structure):
```kotlin
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "host$hostId", factory = viewModelFactory { initializer { DetailViewModel(container, hostId) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {   // PINNED header + scan bar
            TopAppBar(title = { EditableName(s.displayName, onRename = vm::setCustomName) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
            s.host?.let { h -> IdentityBlock(h, onLookupVulns = vm::lookupVulnsForDevice) ; StatusSelector(h.status, vm::setStatus); NoteField(h.note, vm::setNote) }
            ScanBar(s.profile, s.customPortsText, s.customError, s.scanning, s.progress, onProfile = vm::setProfile, onCustom = vm::setCustomPorts, onScan = { if (s.scanning) vm.stopScan() else vm.startScan() })
        }
    }) { pad ->
        if (s.ports.isEmpty()) EmptyState(if (s.scanning) "Scanning ports..." else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).fillMaxSize()) { items(s.ports, key = { it.port }) { p -> PortRowItem(p, onAction = vm::runAction, onVulns = { vm.lookupVulnsForService(p.port) }) } }
    }
    s.vuln?.let { VulnSheet(it, onClose = vm::closeVulns, onRerun = vm::rerunVulnQuery, onOpen = { url -> vm.runAction(DeviceAction.OpenUri("Open", url, true)) }) }
}
```
- `IdentityBlock`: identity line, MAC and vendor when known, `"Found as: <mdns> (mDNS), <netbios> (NetBIOS), <upnp> (UPnP), <rdns> (DNS)"`, an expandable "Why?" showing `identReasons`, and a `TextButton("Look up vulnerabilities")`.
- `StatusSelector`: `SingleChoiceSegmentedButtonRow` with the four statuses labelled "Unmarked", "Interesting", "Investigated", "Ignored".
- `NoteField`: `OutlinedTextField`, 2 to 4 lines, saves on each change (debounce 400 ms in the composable with `LaunchedEffect`).
- `ScanBar`: `SegmentedButton`s Quick / Full / Custom; when Custom, an `OutlinedTextField` with the custom text and `isError = customError != null`, `supportingText = customError`; the Scan/Stop `Button`; `"${progress.done} / ${progress.total}"` with a `LinearProgressIndicator` while scanning.
- `PortRowItem`: `ListItem` headline `"$port  ${service.name}"`, supporting = `listOfNotNull(product, version, detail).joinToString(" ")`, trailing = primary action `Button` (or a disabled `OutlinedButton` with the "No app installed" label when the only OpenUri lacks a handler) plus an overflow `DropdownMenu` listing the remaining actions and "Look up vulnerabilities".
- `VulnSheet` (`ui/vulns/VulnSheet.kt`): `ModalBottomSheet`. Loading: `CircularProgressIndicator` with "Searching NVD for <query>". Ready/Ok: severity counts row (`CRITICAL n  HIGH n  MEDIUM n  LOW n`), an editable query field with a "Search again" button, then a `LazyColumn` of items: CVE id, `AssistChip` with the score, one-line summary (`maxLines = 2`), click opens `url`. Ready/Failed: the reason text, and when `cached != null` the cached list beneath "Showing cached results from <time>". Empty Ok: "No CVEs found for <query>". A line under the query field: "Keyword matches can be noisy. Edit the query to narrow it."

`ui/settings/SettingsScreen.kt`: `Scaffold` with `TopAppBar` and a scrolling `Column` of: Discovery switches (mDNS, SSDP/UPnP, NetBIOS, reverse DNS, SNMP, HTTP titles); sliders with value labels for sweep concurrency (32 to 512), connect timeout (200 to 2000 ms), port-scan concurrency and timeout, large-network limit (`/20`, `/22`, `/24` segmented); a Permissions row with a `Button("Allow location for Wi-Fi name")` that launches `ActivityResultContracts.RequestPermission()` for `ACCESS_FINE_LOCATION` and explains "Android only reveals the Wi-Fi name and BSSID with location permission. It also lets NetProbe tell two networks apart that share 192.168.1.1/24."; a Vulnerabilities section with the NVD API key `OutlinedTextField` (password-style visual transformation) and `Button("Clear vulnerability cache")`.

`MainActivity.kt`:
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NetProbeApp).container
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
    }
}
```

- [ ] **Step 5: Compose device test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun scanButtonAndFiltersArePresentAndPinned() {
        rule.onNodeWithTag("scanButton").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed(); rule.onNodeWithText("Interesting").assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun scanTogglesToStopWhenOnWifi() {
        // Only meaningful on a device connected to Wi-Fi; otherwise the button is disabled and the test verifies that state instead.
        val btn = rule.onNodeWithTag("scanButton")
        try { btn.assertIsEnabled(); btn.performClick(); rule.onNodeWithText("Stop").assertIsDisplayed(); rule.onNodeWithText("Stop").performClick(); rule.onNodeWithText("Scan").assertIsDisplayed() }
        catch (e: AssertionError) { btn.assertIsNotEnabled(); rule.onNodeWithText("Connect to Wi-Fi or Ethernet to scan").assertIsDisplayed() }
    }
}
```

- [ ] **Step 6: Run JVM tests, build, run device test when attached**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -20
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.HomeScreenTest --console=plain 2>&1 | tail -15
```

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Add Compose UI: home, detail, settings, vulnerability sheet and view models"
```

---

### Task 21: Application wiring

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt`, `data/RawResources.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/RawResourcesTest.kt`

**Interfaces:**
- Produces the `AppContainer` properties every ViewModel uses:
```kotlin
class AppContainer(val context: Context) {
    val settings: AppSettings; val db: NetProbeDb; val networks: NetworkRepository; val hosts: HostRepository; val ports: PortRepository; val vulnCache: VulnCacheRepository
    val networkEnvMonitor: NetworkEnvMonitor; val portTable: PortTable; val mdnsTypes: List<String>; val identifyRules: List<Rule>; val cpeMap: Map<String, String>
    val discoveryEngine: DiscoveryEngine; val portScanEngine: PortScanEngine; val serviceIdentifier: ServiceIdentifier; val deviceIdentifier: DeviceIdentifier
    val actionRunner: AndroidActionRunner; val actions: ActionProvider; val vulnLookup: VulnLookup; val ouiLookup: (String) -> String? = { null }   // OUI database is roadmap
    val locks: Locks }
class Locks(context: Context) { fun <T> withScanLocks(block: () -> T): T }   // acquires MulticastLock + WifiLock(WIFI_MODE_FULL_HIGH_PERF), releases in finally
object RawResources { fun lines(text: String): List<String> }   // trims, drops blanks and '#' comments
```
- `DiscoveryEngine.discover` is wrapped so the locks are held for the whole run: `AppContainer.discoveryEngine` is `DiscoveryEngine.production(context, mdnsTypes)` and `HomeViewModel.discover` calls `c.locks.acquire()` before collecting and `release()` in `finally` (make `Locks` expose `acquire()`/`release()` rather than a block form, since the collection is a suspend flow).
- `VulnCacheRepository` adapts to the `VulnCache` interface with a small adapter class in `AppContainer`.

- [ ] **Step 1: Failing test**

```kotlin
package com.northwalessolutions.netprobe

import com.northwalessolutions.netprobe.data.RawResources
import org.junit.Assert.assertEquals
import org.junit.Test

class RawResourcesTest {
    @Test fun `lines trims drops blanks and comments`() {
        assertEquals(listOf("_http._tcp", "_ssh._tcp"), RawResources.lines(" _http._tcp \n\n# comment\n_ssh._tcp\n"))
    }
    @Test fun `bundled mdns types file has the appendix B list`() {
        val types = RawResources.lines(javaClass.getResource("/mdns_types.txt")!!.readText())
        assertEquals(30, types.size); assert(types.contains("_device-info._tcp")); assert(types.all { it.matches(Regex("^_[a-z0-9-]+\\._(tcp|udp)$")) })
    }
}
```

- [ ] **Step 2: Run, expect failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*RawResourcesTest*' --console=plain 2>&1 | tail -15
```

- [ ] **Step 3: Implement**

`data/RawResources.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context

object RawResources {
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    fun read(context: Context, id: Int): String = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}
```
`engine/common/Locks.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import android.content.Context
import android.net.wifi.WifiManager

class Locks(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicast = wifi.createMulticastLock("netprobe-mdns").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION") private val perf = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "netprobe-scan").apply { setReferenceCounted(false) }
    fun acquire() { runCatching { multicast.acquire() }; runCatching { perf.acquire() } }
    fun release() { runCatching { if (multicast.isHeld) multicast.release() }; runCatching { if (perf.isHeld) perf.release() } }
}
```
`AppContainer.kt`:
```kotlin
package com.northwalessolutions.netprobe

import android.content.Context
import com.northwalessolutions.netprobe.actions.ActionProvider
import com.northwalessolutions.netprobe.actions.AndroidActionRunner
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.common.Locks
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngine
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.portscan.PortScanEngine
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.vulns.*
import com.northwalessolutions.netprobe.netenv.NetworkEnvMonitor

class AppContainer(val context: Context) {
    val settings by lazy { AppSettings(context) }
    val db by lazy { NetProbeDb.open(context) }
    val networks by lazy { NetworkRepository(db) }
    val hosts by lazy { HostRepository(db) }
    val ports by lazy { PortRepository(db) }
    val vulnCache by lazy { VulnCacheRepository(db) }
    val networkEnvMonitor by lazy { NetworkEnvMonitor(context) }
    val portTable by lazy { PortTable.fromJson(RawResources.read(context, R.raw.ports)) }
    val mdnsTypes by lazy { RawResources.lines(RawResources.read(context, R.raw.mdns_types)) }
    val identifyRules by lazy { IdentifyRules.load(RawResources.read(context, R.raw.identify_rules)) }
    val cpeMap by lazy { Cpe.load(RawResources.read(context, R.raw.cpe_map)) }
    val discoveryEngine by lazy { DiscoveryEngine.production(context, mdnsTypes) }
    val portScanEngine by lazy { PortScanEngine() }
    val serviceIdentifier by lazy { ServiceIdentifier(portTable) }
    val deviceIdentifier by lazy { DeviceIdentifier(identifyRules) }
    val actionRunner by lazy { AndroidActionRunner(context) }
    val actions by lazy { ActionProvider(actionRunner) }
    val locks by lazy { Locks(context) }
    val ouiLookup: (String) -> String? = { null }
    val vulnLookup by lazy {
        VulnLookup(UrlHttpGet(), object : VulnCache {
            override suspend fun get(query: String, maxAgeMs: Long) = vulnCache.get(query, maxAgeMs)?.let { it.json to it.fetchedAt }
            override suspend fun getAny(query: String) = vulnCache.getAny(query)?.let { it.json to it.fetchedAt }
            override suspend fun put(query: String, json: String) = vulnCache.put(query, json)
        }, { settings.state.value.nvdApiKey }, cpeMap)
    }
}
```
Then in `HomeViewModel.discover`, wrap the collection: `c.locks.acquire(); try { ... } finally { c.locks.release() }`.

- [ ] **Step 4: Full build, full unit suite, install on the device, launch**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -20
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s 28221FDH300AP9 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s 28221FDH300AP9 shell am start -n com.northwalessolutions.netprobe/.MainActivity
sleep 3; "$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|netprobe" | tail -20
```
Expected: app opens to Home with the network panel filled; no `AndroidRuntime` crash lines.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Wire application container, locks and bundled resources"
```

---

### Task 22: On-device QA (Bugsy) and QA record

**Files:**
- Create: `docs/qa/2026-10-19-mvp-on-device.md`
- Modify: anything the QA run proves broken (each fix is its own commit with a failing test first where a test can capture it)

**Interfaces:** none. This task executes spec section 10's on-device list on the Pixel 7 Pro and records evidence. Static review is not acceptable (QA process memory).

- [ ] **Step 1: Preconditions**

```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices | grep 28221FDH300AP9 || echo "PHONE NOT CONNECTED: stop and ask the Director to plug it in"
"$ADB" -s 28221FDH300AP9 shell getprop ro.build.version.release
./gradlew :app:installDebug --console=plain 2>&1 | tail -5
```

- [ ] **Step 2: Run every step and record it**

Create `docs/qa/2026-10-19-mvp-on-device.md` with this table and fill every row with the measured result, the time taken and a screenshot filename (`"$ADB" -s 28221FDH300AP9 exec-out screencap -p > docs/qa/shots/<step>.png`):

```markdown
# NetProbe MVP on-device QA, Pixel 7 Pro (Android <version>), <date>

| # | Step (spec section 10) | Expected | Result | Time | Evidence |
|---|---|---|---|---|---|
| 1 | Install debug APK, launch | Home shows network panel with IP/prefix and gateway | | | |
| 2 | Tap Scan on the home network | First devices within 2 s; run completes in 10 to 20 s; found count vs router client list | found N of M router-listed devices (list the misses) | | |
| 3 | Quick scan the Director's PC | Known listening ports with correct service names; Full scan completes; Stop works mid-run | | | |
| 4 | Router HTTP action | Browser opens the admin page | | | |
| 5 | SSH or VNC action with app installed; one without | Launches the app; fallback text and copy for the missing one | | | |
| 6 | Mark Interesting with note, force-stop, relaunch, rescan | Mark and note survive; filter shows only that host | | | |
| 7 | Join another Wi-Fi and back | Annotations reappear only on the first network | | | |
| 8 | Identification | Router, Windows PC, phone, media or IoT device each show a sensible identity line; list reasons and the Unknowns | | | |
| 9 | Look up vulnerabilities | SSH with banner: results with scores; router device query; second tap served from cache with Wi-Fi off | | | |
| 10 | Neighbour table spike (Task 2) | MAC count after sweep, no avc denial | | | |
| 11 | logcat during steps 2 to 9 | No AndroidRuntime exceptions; no `avc: denied` for netprobe | | | |
```

Commands for step 11:
```bash
"$ADB" -s 28221FDH300AP9 logcat -c
# ... perform the steps ...
"$ADB" -s 28221FDH300AP9 logcat -d | grep -E "AndroidRuntime|avc: denied.*netprobe|FATAL" | tee docs/qa/logcat-errors.txt | tail -20
```

- [ ] **Step 3: Fix what fails**

For each failure: write the failing unit test where the bug is testable on the JVM, fix, rerun the suite, re-install, re-verify the step, then commit `fix: <what>`. Record the fix commit hash in the table's Evidence column.

- [ ] **Step 4: Definition of done check**

- All unit tests green: `./gradlew :app:testDebugUnitTest`
- Instrumented tests green on the device: `./gradlew :app:connectedDebugAndroidTest`
- Every row in the QA table has a Result and Evidence
- The Director has confirmed the discovered device set matches the router's list (ask explicitly; record the answer in the QA file)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Record MVP on-device QA results"
```

---

## Plan Self-Review Notes

- **Spec coverage:** 2.1 items 1 to 9 map to Tasks 15/20 (discover), 6/20 (port scan + quick scan all), 8 (service id), 18 (actions), 17/20 (annotation, filter), 17 (persistence per network), 4/20 (network panel), 16/20 (identification), 19/20 (vuln lookup). Section 4 constraints: Task 1 (targetSdk 30, queries, cleartext), Task 2 (netlink), Task 21 (locks). Section 8 error handling: Tasks 6, 7, 10 to 13 (parsers never throw), 20 (network change), 19 (NVD states). Section 10 tests: each task, plus Task 22 on-device. Section 11 open items: OUI stays a null lookup (Task 21); NVD key in Settings (Task 20); spike gate (Task 2); SSID/BSSID (Task 4 mapper plus Task 22 step 1).
- **Not in this plan, by spec:** OUI database, snapshots/alerts, other-subnet probing, export, WOL, custom tags. The engine's concurrency-halving on `EMFILE` bursts (spec 8) is simplified to counting errors in `ScanProgress`; add the halving if Task 22 ever shows resource errors.
- **Type consistency:** `Probe.Outcome.Connected(rttMs, socket)` is used by Tasks 6, 7, 8; `DiscoveredHost` fields in Task 15 are the superset used by Tasks 16, 17, 20; `ServiceInfo` from Task 8 is used by 17, 18, 19, 20; `Sweep` is `open` for the Task 15 fakes.
- **Review Focus mapping:** 1 Task 3 and Task 15 clamp tests; 2 Task 6 cancel test and Task 20 `ScanController` tests; 3 Tasks 8, 10, 11, 12, 13 garbage tests; 4 Task 18 no-handler test and Task 20 `runAction` fallback; 5 Task 19 offline, 403/429 and malformed tests.
