import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Short HEAD at configuration time; "unknown" when git is missing or this is not a checkout. */
fun gitShortSha(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
} catch (e: java.io.IOException) { "unknown" }

fun buildTimeStamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

android {
    namespace = "com.northwalessolutions.netprobe"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.northwalessolutions.netprobe"
        minSdk = 26
        targetSdk = 30   // deliberate: untrusted_app_30 domain (targetSdk <= 31) keeps netlink RTM_GETNEIGH on Android 13+, see spec section 4
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${buildTimeStamp()}\"")
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
    buildFeatures { compose = true; buildConfig = true }
    lint {
        // targetSdk 30 is a documented decision; these checks exist for Play Store policy only.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        getByName("test") { resources.srcDirs("src/main/res/raw") }
        // MigrationTestHelper reads the exported schemas from the test APK's assets (spec 6).
        getByName("androidTest") { assets.srcDirs("$projectDir/schemas") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

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
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
