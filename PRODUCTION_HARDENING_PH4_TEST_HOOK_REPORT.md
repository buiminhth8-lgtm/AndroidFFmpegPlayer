# Production Hardening PH4 — Debug / Test Hook Isolation Report

Date: 2026-08-26

Baseline: `7683d9b build(player): harden aar consumer configuration`

Scope: PH4 only; LAT7 remains waived and PH5 was not started.

## Result

**PASS — PH4 FROZEN.**

The two native test-only command paths are enabled only when the native target
is built with `CMAKE_BUILD_TYPE=Debug`. Release uses `RelWithDebInfo`, receives
`FFMPEGPLAYER_ENABLE_TEST_HOOKS=0`, rejects both commands with the stable
`unsupported_in_release` response, and does not contain either test
implementation.

The public `FFmpegNative.runDebugCommand(String[])` API and JNI registration are
unchanged. Read-only diagnostics, production `DiagnosticsMode` behavior,
latency statistics, player options, decoder/render/audio/recording behavior,
and the experimental non-default `mediacodec_oes` public mode remain intact.

## Hook Audit and Classification

| Surface | Classification | PH4 result |
|---|---|---|
| `FFmpegNative.getFFmpegVersion/getFFmpegBuildConfig/getAvailableDecoders/getMediaCodecInfo` | `DIAGNOSTIC` | Preserved in all variants |
| `FFmpegNative.probe` | `DIAGNOSTIC` | Preserved in all variants |
| `FFmpegNative.runDebugCommand` public API/JNI dispatcher | `DIAGNOSTIC` | Signature and registration preserved in all variants |
| `-version`, `-buildconf`, `-decoders`, `-latency-config`, `-sourceinfo`, help commands | `DIAGNOSTIC` | Existing branches unchanged |
| `-probe`, `ffprobe` | `DIAGNOSTIC` | Existing branches unchanged |
| `ffplay` unsupported token | `DIAGNOSTIC` | Existing stable unsupported behavior unchanged |
| `-player-lifetime-stress` | `TEST_ONLY` | Debug executable; Release rejects before dispatch |
| `-audio-backpressure-test [ms]` | `TEST_ONLY` | Debug executable; Release rejects before dispatch |
| `setAudioWorkerBackpressureTestDelayMs`, process-global delay, worker sleep | `TEST_ONLY` implementation | Compiled only in Debug |
| Demo Info-button long press | `TEST_ONLY` entry | Public Demo UI remains; Debug runs stress, Release receives the uniform rejection |
| `mediacodec_oes` | `EXPERIMENTAL_SUPPORTED` | Retained; not a test hook and not a PH4 removal candidate |
| `DiagnosticsMode.OFF/BASIC/LATENCY` and STATE/MEDIA/STAGE/PRET0/E2E/HEALTH | `PRODUCTION_DIAGNOSTIC` | Preserved and verified |

No additional callable debug/test mutation hook was found outside these two
commands. FFmpeg header occurrences of `test`, `debug`, `probe`, or
`experimental` are third-party API declarations and are not project hooks.

## Isolation Design

`ffmpegplayer/src/main/cpp/CMakeLists.txt` now owns the build-type decision:

```cmake
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(FFMPEGPLAYER_TEST_HOOKS_VALUE 1)
else()
    set(FFMPEGPLAYER_TEST_HOOKS_VALUE 0)
endif()
target_compile_definitions(native-ffmpeg PRIVATE
        FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE})
```

This is intentionally fail-closed: only the exact Debug build type gets `1`;
Release/RelWithDebInfo and any other build type get `0`.

`native/TestHookPolicy.h` is the single command policy used by the JNI
dispatcher and by host tests. It identifies only the two test commands. In
Release it returns `UnsupportedInRelease` and the dispatcher returns exactly:

```json
{"success":false,"errorCode":"unsupported_in_release","message":"test hook is unsupported in release builds"}
```

Unknown commands and production diagnostics return `NotTestHook`, so they fall
through to the pre-existing dispatcher without semantic changes.

The following implementation blocks are guarded with
`#if FFMPEGPLAYER_ENABLE_TEST_HOOKS`:

- lifetime-stress declaration, dispatch branch, and complete implementation;
- audio-backpressure dispatch branch;
- process-global audio-worker test delay;
- `setAudioWorkerBackpressureTestDelayMs` declaration and definition;
- audio-worker test sleep branch.

## Direct Policy Tests

`ffmpegplayer/src/test/cpp/TestHookPolicyTest.cpp` was compiled and run twice
from the same source:

| Variant macro | Required behavior | Result |
|---|---|---|
| `FFMPEGPLAYER_ENABLE_TEST_HOOKS=1` | Both known test commands are `EnabledInDebug` | PASS — `ALL_DEBUG_TEST_HOOK_POLICY_TESTS_PASSED` |
| `FFMPEGPLAYER_ENABLE_TEST_HOOKS=0` | Both known test commands are `UnsupportedInRelease` | PASS — `ALL_RELEASE_TEST_HOOK_POLICY_TESTS_PASSED` |

Both variants also assert that `-version`, `-latency-config`, and an unknown
command bypass the test gate. The Release rejection JSON is checked for exact
equality, not only for a substring.

## Regression Tests

The existing production diagnostics and latency helpers were rebuilt and run:

| Test | Result |
|---|---|
| `DiagnosticsModeTest.cpp` | PASS — `ALL_DIAGNOSTICS_MODE_TESTS_PASSED` |
| `PreT0TimingTrackerTest.cpp` | PASS — `ALL_PRE_T0_TRACKER_TESTS_PASSED` |
| `E2ETimebaseTest.cpp` | PASS — `ALL_E2E_TIMEBASE_TESTS_PASSED` |

## Gradle Build Matrix

| Command | Result |
|---|---|
| `gradlew.bat :ffmpegplayer:assembleDebug --console=plain` | PASS |
| `gradlew.bat :ffmpegplayer:assembleRelease --console=plain` | PASS |
| `gradlew.bat :app:assembleDebug --console=plain` | PASS |

The Debug build emitted a non-fatal Android SDK XML version warning. Gradle also
reported existing deprecation warnings. Neither warning changed the build
result or PH4 behavior.

## Compile-Definition Evidence

The generated compile databases for both `arm64-v8a` and `armeabi-v7a` show:

| Native variant | JNI translation unit | NativePlayer translation unit |
|---|---|---|
| Debug | `FFMPEGPLAYER_ENABLE_TEST_HOOKS=1` | `FFMPEGPLAYER_ENABLE_TEST_HOOKS=1` |
| RelWithDebInfo / Release | `FFMPEGPLAYER_ENABLE_TEST_HOOKS=0` | `FFMPEGPLAYER_ENABLE_TEST_HOOKS=0` |

## ELF Evidence

The unstripped arm64 Debug ELF contains:

- `setAudioWorkerBackpressureTestDelayMs(int)`;
- `(anonymous namespace)::runPlayerLifetimeStressTest()`;
- `(anonymous namespace)::g_audio_worker_test_delay_ms`.

The unstripped arm64 Release ELF contains none of these symbols. The packaged
Release ELF also lacks the implementation markers `player lifetime stress
result=` and `audioWorkerTestDelayMs`, while retaining `runDebugCommand` and
`unsupported_in_release`.

The optimizer eliminates the two known command names as contiguous Release
strings while preserving their compiled comparison logic. This is an additional
artifact reduction, not the enforcement boundary; the compile-time policy and
early Release rejection are the enforcement boundary.

## AAR and Public API Evidence

| Artifact | Size | SHA-256 |
|---|---:|---|
| `ffmpegplayer-debug.aar` | 17,715,642 bytes | `63AC334FDBDE522700C75B4E348F35382A79AA503D8E9DFCBF72C358D3ACFA06` |
| `ffmpegplayer-release.aar` | 17,575,128 bytes | `918ADB833B54CFE374A7D7DB84ABD503F6C83E0A3D5C695792223D6C9E42867B` |

The rebuilt Release `classes.jar` SHA-256 is
`63DEEBC87F3902D6874098EB25CD7128E22D964D4A9C4EDB6DBF81D1A5EA6A74`,
byte-identical to the PH3 baseline. Therefore no Java public API signature or
unminified Release bytecode changed.

The Release AAR still contains both required ABIs and exactly the six frozen
native libraries per ABI: `libavcodec.so`, `libavformat.so`, `libavutil.so`,
`libnative-ffmpeg.so`, `libswresample.so`, and `libswscale.so`.

## Device Verification

An attached arm64 Bengal device was used with the rebuilt Debug APK. Installation
used `adb install -r`, preserving application data.

| Check | Result |
|---|---|
| Normal Info diagnostic | PASS — version, build config, decoders, and MediaCodec results logged |
| Debug `-player-lifetime-stress` through Info long press | PASS |
| Lifetime create/release cycles | PASS — 100 |
| Lifetime concurrent-release cycles | PASS — 20 |
| Lifetime final registry | PASS — `activePlayerCount=0` |
| Saved real RTSP source prepare | PASS — native `prepare completed` |
| Saved real RTSP source start | PASS — native `startPlayer` and first-frame/T0 evidence observed |
| Player STATS query during playback | PASS |
| Switch to `DiagnosticsMode.LATENCY` | PASS |
| `STATE/MEDIA/STAGE/PRET0/E2E/HEALTH` lines | PASS — all six observed |
| Restore original `DiagnosticsMode.BASIC` | PASS |

The Debug audio-backpressure dispatcher was not invoked on the live playback
session because it intentionally distorts audio-worker timing. Its Debug
executability is covered by the same-source policy test plus the Debug symbol
and implementation evidence. Release rejection is covered by the same-source
Release policy test, exact rejection JSON assertion, compile macro, and absence
of the complete Release implementation.

## Invariants

- Public API signature changed: **NO**.
- JNI class/package/registration changed: **NO**.
- Normal diagnostic command behavior changed: **NO**.
- Unknown-command fallback changed: **NO**.
- Production diagnostics or stats removed: **NO**.
- `DiagnosticsMode.LATENCY` changed: **NO**.
- RTSP options, decode/render/audio/recording behavior changed: **NO**.
- Playback behavior changed: **NO**; real-source device smoke passed.
- Dead experimental code removed: **NONE**.

## PH4 Freeze and Next Gate

PH4 is frozen with Debug test hooks enabled and Release test hooks rejected and
compiled out. The project is **READY** for PH5, but PH5 was not started in this
task.
