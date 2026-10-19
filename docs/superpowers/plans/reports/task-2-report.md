# Task 2 report: Netlink neighbour dump (JNI)

Status: DONE_WITH_CONCERNS (device gate not run: phone not attached)
Commit: cf8bdbc Add netlink neighbour table dump (JNI) with device spike test

## Implemented
NeighbourParser.kt, NeighbourTable.kt, neigh.c, CMakeLists.txt, externalNativeBuild block, NeighbourParserTest, NeighbourSpikeTest, QA note (placeholder marked NOT RUN). All code verbatim from the brief.

## TDD
RED: `./gradlew :app:testDebugUnitTest --tests '*NeighbourParserTest*'` -> BUILD FAILED, `e: NeighbourParserTest.kt:8:17 Unresolved reference 'NeighbourParser'`.
GREEN: same command -> BUILD SUCCESSFUL, result XML tests="5" skipped="0" failures="0" (brief says 4, the test class has 5 methods).
Full `:app:testDebugUnitTest` and `:app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.
Note: first Kotlin write had regex backslashes halved by my tooling (`"\d"`), caught by the compiler, fixed to `"\d"` as in the brief.

## APK
`unzip -l ... | grep libneigh` -> `8368  1981-01-01 01:01   lib/arm64-v8a/libneigh.so`

## Device gate
NOT RUN: `adb devices` listed no devices. docs/qa/2026-10-19-neighbour-spike.md holds run instructions and a blank result section to fill in. Step 8 commands are in the brief.

## Self-review
No em-dashes, no attribution lines, targetSdk still 30. Only brief files committed. Observations on the brief's C code (left verbatim): `static char lines[2048][64]` is not thread-safe (concurrent dump() calls would race); recv buffer is 64KB on stack; table capped at 2048 entries silently. Fine for MVP, worth noting if dump() is ever called concurrently.

## Concerns
Gate on hardware still pending; controller to run Step 8 and fill in the QA note. Git warns LF->CRLF (autocrlf), harmless.

## Fix round 1
Changes: NeighbourTable.dump() now @Synchronized with KDoc noting the static native buffer; neigh.c deletes each jstring local ref after SetObjectArrayElement. Commit: 9fc413d Serialise neighbour dump and release JNI local refs.

Command: ./gradlew :app:testDebugUnitTest --tests '*NeighbourParserTest*' --console=plain | tail -15
```
> Task :app:generateDebugUnitTestConfig UP-TO-DATE
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:kspDebugUnitTestKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 4s
28 actionable tasks: 7 executed, 21 up-to-date
```

Command: ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain | tail -15
```
> Task :app:mergeProjectDexDebug
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:mergeDebugNativeLibs
> Task :app:stripDebugDebugSymbols
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 3s
47 actionable tasks: 8 executed, 39 up-to-date
```

unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libneigh:
```
     8304  1981-01-01 01:01   lib/arm64-v8a/libneigh.so
```
