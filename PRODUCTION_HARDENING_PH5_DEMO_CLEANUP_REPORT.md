# Production Hardening PH5 — Demo Production Cleanup Report

Date: 2026-08-26
Baseline commit: `d6a07be refactor(player): isolate debug test hooks`

# Scope

PH5 audited and cleaned only the Demo `app` module: its Gradle plugins and dependencies, release build configuration, manifest, resources, Demo source, and directly related template tests. The `ffmpegplayer` module, native sources, CMake, JNI, public API, playback pipeline, renderer, decoder, recording, audio, thermal processing, and latency diagnostics were not changed.

The work started from a clean Git worktree. PH0 and PH4 reports were reviewed before modification. Baseline Debug and Release APKs were built from `d6a07be` before cleanup so that size comparisons use the same checkout and toolchain.

# Plugin Audit

Plugin declarations: **5 before -> 1 after**.

| Item | Before | Verdict | Action | Evidence |
|---|---|---|---|---|
| `com.android.application` | Applied | KEEP | Kept | Required to build the Demo application. |
| `org.jetbrains.kotlin.android` | Applied | REMOVE | Removed | Main Demo source is Java; the only Kotlin sources were disposable Android Studio template tests. |
| `kotlin-android` | Applied in addition to `org.jetbrains.kotlin.android` | REMOVE | Removed | Duplicate Kotlin Android application; no retained Kotlin source. |
| `kotlin-kapt` | Applied | REMOVE | Removed | No `kapt` processor dependency and no business generated source. The ARouter argument had no matching processor. |
| `com.google.devtools.ksp` | Applied | REMOVE | Removed | No `ksp` processor dependency and no `app/build/generated/ksp` source output. |
| KSP generated source set | Configured | REMOVE | Removed | Referenced a non-existent generated directory and no processor produced source. |
| ARouter processor arguments | Configured for KSP and Java annotation processing | REMOVE | Removed | No ARouter runtime or processor dependency and no ARouter annotations in Demo source. |

The final app task graphs used no Kotlin, KAPT, or KSP compilation/processing tasks. Android Gradle Plugin's View Binding tasks remain expected and are unrelated to Data Binding expression processing.

# Dependency Audit

Direct dependency declarations: **15 before -> 4 after** (including the retained unit-test dependency).

| Item | Before | Verdict | Action | Evidence |
|---|---|---|---|---|
| `fileTree(dir: 'libs')` | Declared | REMOVE | Removed | `app/libs` does not exist and no local JAR is consumed. |
| `androidx.multidex:multidex` | Declared | REMOVE | Removed | `minSdk 24`; no `MultiDex` class or manifest application integration. |
| `androidx.media:media` | Declared | REMOVE | Removed | No source, manifest, or resource reference. Player audio is provided by `ffmpegplayer`. |
| `androidx.appcompat:appcompat` | Declared | KEEP | Kept | `MediaPlayerActivity` extends `AppCompatActivity`; the app theme inherits AppCompat. |
| `com.google.android.material:material` | Declared | REMOVE | Removed | No Material widget, theme parent, style, manifest, or source reference. |
| `androidx.constraintlayout:constraintlayout` | Declared | KEEP | Kept | Demo layouts use `ConstraintLayout`. |
| `androidx.navigation:navigation-fragment-ktx` | Declared | REMOVE | Removed | No navigation graph, `NavHost`, or Navigation source reference. |
| `androidx.recyclerview:recyclerview` | Declared | REMOVE | Removed | No RecyclerView widget, adapter, source, or resource reference. |
| `androidx.viewpager2:viewpager2` | Declared | REMOVE | Removed | No ViewPager2 widget, adapter, source, or resource reference. |
| `androidx.core:core-ktx` | Declared | REMOVE | Removed | No retained Kotlin source or KTX API use. Required AndroidX Core classes continue transitively through AppCompat. |
| `kotlinx-coroutines-core` | Declared | REMOVE | Removed | No coroutine source use. Demo background work uses Java executors. |
| `kotlinx-coroutines-android` | Declared | REMOVE | Removed | No coroutine source use. |
| `project(':ffmpegplayer')` | Declared | KEEP | Kept | Supplies the player, options, diagnostics, playback, recording, audio, thermal, and surface APIs used by the Demo. |
| `com.blankj:utilcodex` | Declared | REMOVE | Removed | No source, manifest, or resource reference. |
| `junit:junit` | Declared for unit tests | KEEP | Kept | Used by `LatencyStatsFormatterTest.java`. |

The resolved final `releaseRuntimeClasspath` has only View Binding, AppCompat, ConstraintLayout, and `project :ffmpegplayer` as first-level entries. AndroidX Core/Lifecycle/Fragment entries shown by Gradle are required transitive dependencies of AppCompat and were not independently declared by the Demo.

# Removed Items

- Four unused/duplicate app plugins: Kotlin Android (two declarations), KAPT, and KSP.
- Unused KSP generated-source configuration and ineffective ARouter processor arguments.
- Eleven unused dependency declarations: local JAR file tree, MultiDex, AndroidX Media, Material, Navigation, RecyclerView, ViewPager2, Core KTX, both coroutine artifacts, and UtilCodeX.
- Explicit Data Binding enablement; View Binding remains enabled and used.
- Unused instrumentation runner configuration associated with the removed template instrumented test.
- Android Studio's default `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt`; neither tested Demo/player behavior.
- The hardcoded private-network RTSP fallback and the word `test` in the URL hint. Intent-provided and user-entered RTSP/HTTP(S) URLs remain supported.
- Unused manifest `tools` namespace.

# Kept Items

- AppCompat and its theme support.
- ConstraintLayout and all production Demo layouts.
- View Binding, which is used throughout the Demo.
- The `ffmpegplayer` project dependency and every player feature entry.
- JUnit and the meaningful `LatencyStatsFormatterTest` unit test.
- Both `armeabi-v7a` and `arm64-v8a` ABI filters.
- Existing versioning, APK naming, Java 17 configuration, release `minifyEnabled false` strategy, and ProGuard file declarations.

# Release Configuration

| Item | Before | Verdict | Action | Evidence |
|---|---|---|---|---|
| Release `debuggable` | `true` | FIX | Set to `false` | Final APK manifest analysis reports `debuggable=false`. |
| Release `minifyEnabled` | `false` | KEEP | Unchanged | PH5 does not introduce an R8 policy. |
| Release signing | No explicit `signingConfig` in app build file | KEEP | Unchanged | PH5 did not add, remove, or redirect signing configuration. |
| App `minSdk` | 24 | KEEP | Unchanged | No SDK-floor change was needed. |
| App `targetSdk` | 31 | FIX | Set to 35 | With Release no longer debuggable, `lintVitalRelease` correctly rejected the expired target 31. Target 35 matches the frozen library target and allows the required production Release build without suppressing lint. |

release debuggable: **FALSE**

The first post-cleanup Release attempt exposed `ExpiredTargetSdkVersion`, previously masked by the debuggable Release configuration. The app-only target update fixed the production gate; no lint baseline or suppression was introduced. The Demo manifest requests only `INTERNET`, so no storage, notification, foreground-service, or other target-SDK permission migration was required. Final real-source RTSP playback passed on an attached Android device after this update.

# Demo Functional Invariants

| Item | Before | Verdict | Action | Evidence |
|---|---|---|---|---|
| Create / Prepare / Start / Pause / Stop / Release | Present | KEEP | Unchanged | Each control remains in the layout and retains its `MediaPlayerActivity` click binding. |
| RTSP and local/HTTP(S) URL input | Present | KEEP | Unchanged | URL input remains; `EXTRA_URL` and user-entered URL paths remain. Only the private hardcoded fallback was removed. |
| Stats / Diagnostics | Present | KEEP | Unchanged | State, stats, reconnect/latency state, and diagnostics mode controls remain bound. Stats returned successfully during device playback. |
| Thermal Original | Present as thermal OFF | KEEP | Unchanged | Thermal enable switch defaults OFF, preserving the original image path. |
| Thermal White Hot / Ironbow | Present | KEEP | Unchanged | Both palette radio controls and player API bindings remain. |
| Snapshot | Present | KEEP | Unchanged | Snapshot control and native/PixelCopy paths remain. |
| Recording / segmented recording | Present | KEEP | Unchanged | Start, segment-start, stop, state, paths, and format controls remain. |
| Audio monitoring | Present | KEEP | Unchanged | Audio switch and player option application remain. |
| Reconnect | Present | KEEP | Unchanged | Reconnect switch, reconnect options, and state reporting remain. |
| Surface attach/detach | Present | KEEP | Unchanged | `SurfaceHolder.Callback`, automatic `setSurface`, destroy-time `clearSurface`, and manual clear control remain. |

Static verification found every required control in the layout and its corresponding Activity reference. Dynamic verification installed the final Debug APK on an attached arm64 device, launched the Demo with the already-proven real RTSP source through `EXTRA_URL`, and observed Prepare invoked/completed, Start invoked/native, first frame or T0, and a successful Stats result. The RTSP URL itself is intentionally not recorded in this report.

# Build / Tests

| Gate | Result | Evidence |
|---|---|---|
| `git diff --check` | PASS | No whitespace errors; only Git informational LF-to-CRLF working-tree warnings. |
| `:app:assembleDebug` | PASS | Final source assembled successfully. |
| `:app:assembleRelease` | PASS | Final source assembled successfully; `lintVitalRelease` also passed. |
| `:ffmpegplayer:assembleDebug` | PASS | Both configured ABIs built. |
| `:ffmpegplayer:assembleRelease` | PASS | Both configured ABIs built. |
| `:app:testDebugUnitTest` | PASS | Retained Java unit test executed. |
| `:app:testReleaseUnitTest` | PASS | Retained Java unit test executed. |
| `:ffmpegplayer:testDebugUnitTest` | PASS (`NO-SOURCE`) | No Java unit-test source is configured for this module. |
| `:ffmpegplayer:testReleaseUnitTest` | PASS (`NO-SOURCE`) | No Java unit-test source is configured for this module. |
| Direct native host tests | PASS | Debug/release test-hook policy, diagnostics mode, pre-T0 timing tracker, and E2E timebase suites all passed. |
| `:app:lintDebug` | PASS | Stable lint task completed without a baseline or suppression. |
| Final device RTSP playback | PASS | Prepare, Start, first-frame/T0, and Stats checks succeeded using the final Debug APK. |

Gradle reported existing deprecated-feature warnings for future Gradle 9 compatibility; they did not fail any PH5 gate.

# Size Comparison

| Artifact | Before | After | Change |
|---|---:|---:|---:|
| Debug APK | 32,824,239 bytes | 28,430,792 bytes | -4,393,447 bytes (-13.38%) |
| Release APK | 25,141,422 bytes | 19,347,091 bytes | -5,794,331 bytes (-23.05%) |

SHA-256 evidence:

- Debug before: `8EB1B5912EEDB93A3FFDAA7190ECCABF02E1D267A03EB93319A790E29A2C3775`
- Debug after: `420248506CE285E98C17B21117444C1E36C2CF319CAF451C558FE97F142DAB4A`
- Release before: `BA4DBAB6E07B8470BFD6DA015C247BDBE0A50F2A2E41D4E6C34B2D1E711C6A16`
- Release after: `1DC44BE94AF51E9877D232E0E08D37A778DF657845EA14574AF98D2AB32F58F9`

Gradle configuration/build time: **NOT_MEASURED**. No performance claim is inferred from non-isolated build durations.

# Remaining Demo Debt

- Root build/plugin management still declares apply-false Kotlin/KSP/Hilt plugin coordinates and legacy Kotlin/Room version properties. They are outside this app-only PH5 slice and may be shared policy; removal requires a root-wide audit.
- Gradle reports deprecated features that will require investigation before Gradle 9 adoption.
- Release signing remains not explicitly configured in the app build file. PH5 intentionally preserved the existing signing policy.
- Release minification remains disabled. PH5 intentionally preserved the existing R8 strategy.
- Instrumented template coverage was removed because it did not exercise player behavior; meaningful device regression remains a release-process concern.

# PH5 Freeze

- App plugin/dependency cleanup: **PASS**.
- Demo production Release configuration: **PASS** (`debuggable=false`, target 35, final Release build and lint vital pass).
- Demo functional surface preserved: **YES**.
- Final real-source RTSP smoke test: **PASS**.
- `ffmpegplayer` core changed: **NO** (`git diff --name-only -- ffmpegplayer` is empty).
- Playback behavior changed: **NO**.
- Public API changed: **NO**.

PH5 Freeze: **YES**.

# PH6 Readiness

All PH5 build, test, lint, scope, size, runtime, and Git gates are satisfied. No `ffmpegplayer` core files are modified.

PH6 readiness: **READY**.
