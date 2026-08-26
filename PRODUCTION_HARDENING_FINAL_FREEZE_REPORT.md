# Production Hardening — PH7 — AAR Consumer Smoke Test & Final Freeze

Date: 2026-08-26
Branch: dev (f719104 + PH7)
Scope: VERIFY + FREEZE. Rebuild and inspect the real Release AAR, run an
independent Consumer Smoke Test against the AAR (never the source module),
validate ABI/native dependency closure, validate JNI/R8 including a runtime
JNI load under R8 on a real device, run the build/test/lint matrix, and
establish the final Production Hardening freeze baseline. No player-pipeline
code change was made.

---

# Scope

- Release AAR rebuild + real content inspection (ZIP/JAR).
- Independent consumer project (temp, outside repo) that depends ONLY on the
  Release AAR and compiles/links against the public Java surface.
- Consumer Release build with R8 (`minifyEnabled true`) and runtime install +
  native JNI load validation on device `34aff35a`.
- Native `DT_NEEDED` closure + ABI validation via `llvm-readelf` / ZIP.
- PH0-PH7 build/test/lint matrix.
- PH6 (API/doc alignment) re-verification against source.
- Final freeze report.

Not performed: any change to `NativePlayer`, `FFmpegNative` JNI, CMake,
decoder, renderer, RTSP parameters, audio, recording, thermal, reconnect,
Surface lifecycle, or Maven publishing (still independent).

# PH0-PH6 Summary

| Slice | Result | Key outcome |
|---|---|---|
| PH0 Baseline | PASS / FREEZE | Reproducible baseline; AAR 20,554,393 B (19.60 MiB); 8 `.so`/ABI (7 FFmpeg + native); `DiagnosticsModeTest.cpp` PASS |
| PH1 Native Dependency | PASS / FREEZE | `libswresample.so` REQUIRED; mapped 7 FFmpeg libs; `libavfilter`/`libavdevice` kept as PH2 candidates |
| PH2 AAR Slimming | PASS / FREEZE | Removed `libavfilter`+`libavdevice`; AAR 16.77 MiB; 6 `.so`/ABI |
| PH3 Library Build | PASS / FREEZE | Gradle/CMake/consumer-rules audit; no internal minify; JNI keep rules PASS |
| PH4 Test Hook | PASS / FREEZE | Debug test commands EnabledInDebug; Release rejected `unsupported_in_release`; DiagnosticsMode contract intact |
| PH5 Demo Cleanup | PASS / FREEZE | Demo deps trimmed; app Release debuggable removed, targetSdk gate fixed; real-source RTSP playback PASS on device |
| PH6 API/Doc | PASS / FREEZE | README aligned (lib counts, thermal facade, DiagnosticsMode, Maven NOT_CONFIGURED); 3 thermal facade constants + equality test |

All PH0-PH6 are closed. No BLOCKED item was carried forward.

# Final Module Architecture

```
app (Demo)
  -> implementation project(':ffmpegplayer')
ffmpegplayer (public library)
  -> FFmpegPlayer (facade, AutoCloseable, owner of native handle)
  -> FFmpegNative (public legacy JNI bridge; JNI_OnLoad registration target)
  -> LiveAudioPcmSink (public Java AudioTrack/JNI callback implementation)
  -> JNI/C++ (NativePlayer, Renderers, Thermal, Recorder, Snapshot, PreT0/E2E)
  -> FFmpeg headers + 5 runtime .so
  -> CMake
```

Consumer path: consumer -> Release AAR -> FFmpegPlayer -> FFmpegNative -> JNI
-> native libraries. Ordinary consumers use only `FFmpegPlayer`.

# Final Release AAR

- Path: `ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar`
- Size: **17,575,233 bytes (16.76 MiB)**
- Supported ABIs: `armeabi-v7a`, `arm64-v8a` (no x86 / x86_64 payload)
- `classes.jar` top-level public classes: `FFmpegPlayer`, `FFmpegNative`,
  `LiveAudioPcmSink`
- `proguard.txt`: JNI keep rules for `FFmpegNative` native methods,
  `FFmpegNative$OesFrameListener <init>(long)`,
  `FFmpegNative$PlayerEventListener` interface + implementors,
  `LiveAudioPcmSink` (`onAudioPcm/onAudioControl/getPlaybackHeadFrames`)
- `AndroidManifest.xml`, `R.txt`, `aar-metadata.properties` (minCompileSdk=1)

# Native Dependency Closure

`libnative-ffmpeg.so` `DT_NEEDED` (both ABIs, via `llvm-readelf`):

- FFmpeg: `libavformat`, `libavcodec`, `libavutil`, `libswresample`,
  `libswscale` — all 5 present in the AAR. PASS.
- Platform: `liblog`, `libandroid`, `libEGL`, `libGLESv2`, `libm`, `libdl`,
  `libc` — provided by Android.
- No `libavfilter.so` / `libavdevice.so` needed (PH2 slimming correct; no
  dangling DT_NEEDED to removed libraries).

ABI_NATIVE_DEPENDENCY: **PASS**

# ABI Validation

- Libraries present per ABI: `libavcodec`, `libavformat`, `libavutil`,
  `libnative-ffmpeg`, `libswresample`, `libswscale` (6/ABI).
- No stale x86 payload.
- The consumer Release APK packaged all 6 `.so` per ABI under `lib/<abi>/`.

# Consumer Smoke Test

Standalone temp project `AarConsumerSmoke` (outside repo, reuses cached Gradle
wrapper 8.14.3 + AGP 8.9.1, SDK `D:\develop\android\sdk`):

- `consumer` is `com.android.application`; its only dependency is
  `implementation files('ffmpegplayer-release.aar')` — never
  `project(':ffmpegplayer')`.
- `MainActivity` exercises the public surface: `new FFmpegPlayer()`,
  `getState/getStats/getReconnectState`, `setListener(null)`,
  `release()`, `isReleased()`, facade thermal constants
  (`THERMAL_PALETTE_ORIGINAL/WHITE_HOT/IRONBOW`),
  `setPlayerOption("diagnostics_mode","basic")`, and legacy bridge
  `FFmpegNative.getFFmpegVersion()` / `getFFmpegBuildConfig()`.
- `consumer:assembleDebug`: **PASS**
- `consumer:assembleRelease` (minified): **PASS**

# R8 Validation

Release consumer APK ran under R8. The AAR's embedded consumer rules correctly
kept the JNI-critical classes (from `mapping.txt`):
`FFmpegNative -> FFmpegNative`, `FFmpegNative$OesFrameListener`,
`FFmpegNative$PlayerEventListener` (none renamed/inlined away).

Runtime (device `34aff35a`, signed release APK installed and launched):
`JNI_OnLoad success, jniInitialized=1`; all 5 FFmpeg libs + `libnative-ffmpeg`
loaded; `createPlayer` succeeded; `FFmpegPlayer.Listener` +
`LiveAudioPcmSink` callbacks registered; idempotent `releasePlayer`
("already released" on second call).

JNI/R8 validation: **PASS (runtime, not compile-only)**.

# Debug/Test Hook Policy

Unchanged (PH4). Debug-known test commands are `EnabledInDebug`; Release
rejects with the exact
`{"success":false,"errorCode":"unsupported_in_release",...}` response. No PH7
redefinition. DiagnosticsMode.TEST? No — DiagnosticsMode `off/basic/latency`
remains a formal contract, distinct from Debug test injection. Default is
BASIC (verified in `PlaybackDiagnostics.h`: `mode_{DiagnosticsMode::Basic}`).

# Public API Contract

Re-verified against source (PH6):
- `FFmpegPlayer` public signatures match README exactly
  (setListener/setSurface/prepare/start/pause/stop/.../setThermalWindow/
  takeSnapshot/startRecord/getState/getStats/isReleased/release/close).
- Thermal facade constants forward `FFmpegNative` 0/1/2; legacy constants
  preserved; no JNI change.
- `DiagnosticsMode` values confirmed in `DiagnosticsMode.h`
  (`Off/Basic/Latency` -> "off"/"basic"/"latency").
- `FFmpegNative` statics confirmed (`getFFmpegVersion`,
  `getFFmpegBuildConfig`, ...).
- Maven publishing: NOT_CONFIGURED (no `maven-publish`/publishing block in
  `ffmpegplayer/build.gradle`); deliverable is the local Release AAR.

Public API / README: **ALIGNED**.

# Functional Regression

- AAR consumer (JNI/native) runtime on device: PASS (see R8/Runtime).
- Main demo-app live RTSP replay against a real source: **NOT_EXECUTED** this
  session because the production camera (`rtsp://192.168.1.101:556`) is
  currently unreachable. Not a hardening regression: PH6 (facade constants +
  README + tests) and PH7 (report only) made no change to the playback/native
  path. Prior-phase live evidence (LAT0-PH6 on device `34aff35a`, PH5 real
  source playback PASS) stands; PH7 did not claim a fresh live run.
- Recording / Thermal / Snapshot / Reconnect / Surface lifecycle: not
  re-run live in PH7 (source offline); no hardening change affected them.
- Audio: SOURCE_NOT_VALIDATED (the local smoke fixture and current camera
  path provide no live AAC audio packets; nothing fabricated).

# Build/Test Matrix

| Gate | Result |
|---|---|
| `git diff --check` | PASS (no whitespace errors) |
| `:ffmpegplayer:assembleDebug` | PASS |
| `:ffmpegplayer:assembleRelease` | PASS (rebuilt, AAR regenerated) |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS |
| `:app:testDebugUnitTest` / `:app:testReleaseUnitTest` | PASS (FFmpegPlayerFacadeConstantsTest 1/1, LatencyStatsFormatterTest 14/14, both variants) |
| `:ffmpegplayer:lintDebug` / `:app:lintDebug` | PASS |
| consumer assembleDebug | PASS |
| consumer assembleRelease (R8) | PASS |
| consumer runtime (signed release on device) | PASS (JNI native load, create/release) |
| ABI / DT_NEEDED closure | PASS |
| ffmpegplayer no Java test source | informational: `NO-SOURCE` |

# PH0 vs PH7 Comparison

| Item | PH0 | PH7 |
|---|---|---|
| Release AAR size | 20,554,393 B (19.60 MiB) | 17,575,233 B (16.76 MiB) |
| jniLibs native library count | 8 `.so`/ABI (7 FFmpeg + native) | 6 `.so`/ABI (5 FFmpeg + native) |
| Native library count (per ABI) | 8 | 6 |
| ffmpegplayer dependencies | none external | none external |
| app dependencies | heavier (pre-PH5) | ViewBinding/AppCompat/ConstraintLayout + ffmpegplayer |
| Release debuggable | true (pre-PH5) | false (PH5 fixed) |
| Consumer rules | baseline consumer-rules.pro | embedded proguard.txt, PASS |
| Release test hooks | Debug commands in Release | Debug-only; Release rejected (unsupported_in_release) |

AAR size improvement: **2,979,160 bytes (2.841 MiB, 14.49%)**

# Remaining Technical Debt

(NOT implemented in PH7 — recorded only.)

- `NativePlayer.cpp` large refactor / monolithic class.
- Typed result API (PH6 confirmed String/JSON contract intentional for now).
- ClockSyncProvider / full cross-device E2E (sender timestamps / RTCP SR
  provider; the RTCP SR path is wired and validated but no durable sender
  source exists in this repo).
- Maven Central / private-repo publishing (README documents NOT_CONFIGURED).
- Kotlin facade.
- Package rename (`com.example.motro`).
- Uncommitted `MediaPlayerActivity.initDefaults` hardcoded URL override
  (see below) — should be removed in a follow-up demo cleanup so `EXTRA_URL`
  intent routing works again.

# Deferred Work

- Fresh live-source functional regression when the production camera is
  reachable (and after removing the demo debug URL override).
- Any further optimization is outside Production Hardening.

# Production Recommendation

The library boundary (AAR packaging, ABI, native dependency closure, public
Java surface, JNI/R8 keep rules) and the player core are stable and
production-safe. Deliver via the local Release AAR; consumers enable R8 and
the AAR's embedded consumer rules protect the JNI surface automatically.
Treat Maven publishing and the internal architecture refactor as separate
follow-ups.

# Final Freeze

Gate check:
- PH0-PH6 closed: YES
- Release AAR build PASS: YES
- ABI/native dependency PASS: YES
- Consumer Debug build PASS: YES
- Consumer Release + R8 PASS; JNI/R8 runtime validation PASS: YES
- Public API/docs aligned: YES
- Release test hooks safe: YES
- Diagnostics preserved (LATENCY/BASIC/OFF): YES
- Core regression: no known defect/blocker; live run NOT_EXECUTED (source
  offline) with prior-phase evidence; no hardening change to playback path.

**PRODUCTION_HARDENING: PASS**

**PLAYER_CORE: FROZEN**

**LIBRARY_BOUNDARY: FROZEN**

**AAR_PACKAGING: FROZEN**

---

## Answers / Summary

PH0-PH6: PASS
Release AAR: ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
Release AAR size: 17,575,233 bytes (16.76 MiB)
Supported ABIs: armeabi-v7a, arm64-v8a
Native dependency closure: PASS
Consumer Debug build: PASS
Consumer Release + R8: PASS (runtime validated)
JNI/R8 validation: PASS (runtime)
Release test hooks: SAFE
Diagnostics preserved: YES
Public API / README: ALIGNED
Core runtime regression: NOT_EXECUTED (live source offline; prior evidence)
Recording: NOT_EXECUTED (source offline; no hardening change)
Thermal: NOT_EXECUTED (source offline; no hardening change)
Snapshot: NOT_EXECUTED (source offline; no hardening change)
Audio: SOURCE_NOT_VALIDATED
Reconnect: NOT_EXECUTED (source offline; no hardening change)
Surface lifecycle: NOT_EXECUTED (source offline; no hardening change)
ffmpegplayer assembleDebug: PASS
ffmpegplayer assembleRelease: PASS
app assembleDebug: PASS
app assembleRelease: PASS
Tests: PASS
PH0 AAR size: 20,554,393 bytes (19.60 MiB)
PH7 AAR size: 17,575,233 bytes (16.76 MiB)
Total AAR size improvement: 2,979,160 bytes (2.841 MiB, 14.49%)
Final fixes: NONE (no playback-code change required)
Remaining technical debt: see "Remaining Technical Debt"
PRODUCTION_HARDENING: PASS
PLAYER_CORE: FROZEN
LIBRARY_BOUNDARY: FROZEN
AAR_PACKAGING: FROZEN
