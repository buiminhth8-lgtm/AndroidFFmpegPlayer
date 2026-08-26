# Production Hardening PH0 Baseline Audit & Freeze

# Scope

PH0 establishes a reproducible production-hardening baseline for the Android
demo and `ffmpegplayer` AAR. It is an audit-only slice: the only tracked change
is this report. No player implementation, build behavior, public API, package,
JNI name, RTSP option, decoder, renderer, audio, recording, thermal, reconnect,
surface-lifecycle, or `NativePlayer` architecture was changed.

Evidence was collected on Windows on 2026-08-25 with JDK 17, Android Gradle
Plugin 8.9.1, CMake 3.22.1, and the NDK 27.0.12077973 toolchain selected by AGP.
The three prerequisite freeze reports and `README.md` were read before the
audit.

# Git Baseline

- Branch: `dev`
- Audited HEAD before this report: `f728fd23c58cf0e20444736d510ce134b51964cc`
  (`f728fd2 refactor(player): clean up latency diagnostics`)
- Remote marker at audit start: `origin/dev` also pointed to `f728fd2`.
- Initial `git status --short`: clean.
- User changes found at audit start: none.
- Destructive Git operations, push, rebase, amend, reset, and broad staging were
  not used.

The ten commits visible at baseline were:

```text
f728fd2 refactor(player): clean up latency diagnostics
4432ec2 chore(latency): close rtsp latency investigation
3cf84e5 feat(latency): activate end to end latency measurement
da71348 feat(latency): activate end to end latency measurement
d473dc6 feat(latency): activate end to end latency measurement
1b46e3d 1.LAT 6.0.1  E2E code
0ab2704 feat(latency): add end to end timebase diagnostics
d309f64 test(latency): isolate rtsp pre-demux latency
5841bba 1.fix bug
4d701a1 test(latency): quantify client playback latency budget
```

# Build Baseline

| Check | PH0 result | Evidence |
| --- | --- | --- |
| `git diff --check` before report | PASS | Exit code 0, no output |
| `:ffmpegplayer:assembleDebug` | PASS | Both configured ABIs built; `BUILD SUCCESSFUL` |
| `:ffmpegplayer:assembleRelease` | PASS | Both configured ABIs built; release AAR regenerated |
| `:app:assembleDebug` | PASS | Debug APK packaged successfully |
| `:app:testDebugUnitTest` | PASS | Kotlin and Java unit-test compilation and execution succeeded |
| `DiagnosticsModeTest.cpp` (MSVC C++17) | PASS | `ALL_DIAGNOSTICS_MODE_TESTS_PASSED` |
| `PreT0TimingTrackerTest.cpp` (MSVC C++17) | PASS | `ALL_PRE_T0_TRACKER_TESTS_PASSED` |
| `E2ETimebaseTest.cpp` (MSVC C++17) | PASS | `ALL_E2E_TIMEBASE_TESTS_PASSED` |

The Gradle tasks were run in one invocation after the audit dependency reports;
113 actionable tasks completed, with 104 executed and 9 up-to-date. The build
emitted a deprecation notice for an API used by `LiveAudioPcmSink`, KAPT warnings
that no processor recognized `AROUTER_MODULE_NAME`, and general Gradle 9
deprecation warnings. None blocked PH0. No device or live-source test was newly
executed in PH0; the real-device/live-RTSP runtime evidence remains the evidence
recorded by the prerequisite closeout and cleanup reports.

# Module Boundary

The actual Gradle module direction is:

```text
app (Demo APK)
  -> implementation project(':ffmpegplayer')

ffmpegplayer (Android library/AAR)
  -> Java facade and JNI bridge
  -> CMake/native player and renderers
  -> src/main/jniLibs FFmpeg runtime libraries
```

- `app` is the Demo/validation application. Its production source contains the
  Activity and display formatting only; it consumes the library module.
- `ffmpegplayer` owns the three Java runtime classes, JNI registration, native
  implementation, CMake target, FFmpeg headers, and packaged FFmpeg libraries.
- No reverse dependency from `ffmpegplayer` to `app` was found.
- Namespace/package: `com.example.motro.ffmpeg`.
- JNI class contract: `com/example/motro/ffmpeg/FFmpegNative`.
- The Demo primarily owns an `FFmpegPlayer`, but it also directly calls the
  public legacy `FFmpegNative` bridge for its info dialog and lifetime-stress
  long-press action. That exposure is recorded, not changed.

Module boundary status: **PASS**.

# Final Player Pipeline

The frozen, runtime-validated explicit profile remains:

```text
RTSP UDP / BALANCED
  maxDelayUs=100000
  reorderQueueSize=4
  socketBufferSize=262144
    -> hevc_mediacodec
    -> nv12_cpu
    -> nv12_gl (NativeNv12GlRenderer / OpenGL ES)
    -> SurfaceView
```

This is corroborated by the final closeout/cleanup evidence and by the current
source mappings (`hevc_mediacodec`, output type `nv12_cpu`, renderer type
`nv12_gl`). PH0 did not retune or reinterpret the profile. Software and other
hardware render modes remain compiled compatibility/fallback paths, and
`mediacodec_oes` remains an experimental, non-default path.

# ABI Baseline

The library and Demo `abiFilters`, source `jniLibs`, generated native target,
and release AAR consistently contain exactly:

- `arm64-v8a`
- `armeabi-v7a`

No x86 or x86_64 payload is present in the release AAR. Each supported ABI has
the same eight-library set: the seven imported FFmpeg `.so` files plus the
generated `libnative-ffmpeg.so`.

# Native Dependency Matrix

`Packaged` and `DT_NEEDED` were verified against the regenerated release AAR.
`llvm-readelf -d` from NDK 27.0.12077973 produced identical project-library
relationships for both ABIs. `CMake Linked` means present in
`target_link_libraries(native-ffmpeg)`; imported-but-not-linked libraries are
shown explicitly.

| Library | Packaged | Java Loaded | CMake Linked | DT_NEEDED By | Status |
| --- | --- | --- | --- | --- | --- |
| `libnative-ffmpeg.so` | YES, both ABIs | YES, required | Built CMake target | Loaded explicitly; no project `.so` needs it | REQUIRED |
| `libavutil.so` | YES, both ABIs | YES, required | YES | `libnative-ffmpeg`, `libavcodec`, `libavformat`, `libswresample`, `libswscale`, `libavfilter`, `libavdevice` | REQUIRED |
| `libswresample.so` | YES, both ABIs | YES, attempted as optional | YES | `libnative-ffmpeg`, `libavcodec`, `libavfilter` | REQUIRED |
| `libswscale.so` | YES, both ABIs | YES, required | YES | `libnative-ffmpeg`, `libavfilter` | REQUIRED |
| `libavcodec.so` | YES, both ABIs | YES, required | YES | `libnative-ffmpeg`, `libavformat`, `libavfilter`, `libavdevice` | REQUIRED |
| `libavformat.so` | YES, both ABIs | YES, required | YES | `libnative-ffmpeg`, `libavfilter`, `libavdevice` | REQUIRED |
| `libavfilter.so` | YES, both ABIs | NO | NO; imported only | `libavdevice` only | POSSIBLY_UNUSED |
| `libavdevice.so` | YES, both ABIs | NO | NO; imported only | None | POSSIBLY_UNUSED |

The owned native sources contain no `avfilter`/`avdevice` API reference.
`libnative-ffmpeg.so` directly needs the five REQUIRED FFmpeg libraries and
does not need `libavfilter.so` or `libavdevice.so`. Because PH0 does not perform
a removal/device regression, the latter two remain `POSSIBLY_UNUSED`, not
declared safe to delete. A correctness issue also exists: Java labels
`swresample` optional, but the ELF graph makes it mandatory for both
`libnative-ffmpeg` and `libavcodec`.

# Gradle Dependency Matrix

Classification is based on current source/resource references and the resolved
runtime dependency reports. PH0 does not remove or update any item.

## ffmpegplayer plugins and direct dependencies

| Item | Kind | Status | Evidence |
| --- | --- | --- | --- |
| `com.android.library` | plugin | USED | Produces the AAR and Android variants |
| `org.jetbrains.kotlin.android` | plugin | UNUSED_CANDIDATE | No Kotlin file exists anywhere under `ffmpegplayer/src`; `compile*Kotlin` is `NO-SOURCE` |
| `fileTree(dir: 'libs', *.jar)` | dependency | UNUSED_CANDIDATE | No file exists under `ffmpegplayer/libs` |
| `androidx.lifecycle:lifecycle-common-jvm:2.8.7` | dependency | UNUSED_CANDIDATE | No lifecycle import or API use in the library sources |
| `kotlin-stdlib:2.1.20` | resolved plugin runtime | UNUSED_CANDIDATE | Added despite the Java-only library |
| lifecycle transitives (`annotation`, coroutines, Kotlin) | resolved transitives | UNUSED_CANDIDATE | The additional annotation/coroutines footprint comes through the unused lifecycle dependency; Kotlin is also added by the plugin |

## app plugins

| Item | Status | Evidence |
| --- | --- | --- |
| `com.android.application` | USED | Builds the Demo APK |
| `org.jetbrains.kotlin.android` | USED | Required by the two checked-in Kotlin test sources, although main is Java-only |
| `kotlin-android` | UNUSED_CANDIDATE | Duplicate alias/application of the Kotlin Android plugin |
| `kotlin-kapt` | UNUSED_CANDIDATE | No KAPT dependency/processor is declared; Gradle reports the options are unrecognized |
| `com.google.devtools.ksp` | UNUSED_CANDIDATE | No KSP processor dependency and no generated production use exists |

The root `com.google.dagger.hilt.android` plugin declaration is `apply false` and
never applied, so it is also an `UNUSED_CANDIDATE`. The Foojay resolver is
`USED` by Gradle toolchain resolution.

## app direct dependencies

| Dependency | Status | Evidence |
| --- | --- | --- |
| `fileTree(dir: 'libs', *.jar)` | UNUSED_CANDIDATE | `app/libs` contains no files |
| `androidx.multidex:multidex:2.0.1` | UNUSED_CANDIDATE | minSdk is 24; no MultiDex class/application use |
| `androidx.media:media:1.4.0` | UNUSED_CANDIDATE | No source/resource reference |
| `androidx.appcompat:appcompat:1.4.1` | USED | `AppCompatActivity` and `Theme.AppCompat.NoActionBar` |
| `com.google.android.material:material:1.10.0` | UNUSED_CANDIDATE | No Material widget or theme reference |
| `androidx.constraintlayout:constraintlayout:2.1.3` | USED | Root of `activity_media_player.xml` |
| `androidx.navigation:navigation-fragment-ktx:2.4.1` | UNUSED_CANDIDATE | No navigation resource/API reference |
| `androidx.recyclerview:recyclerview:1.3.2` | UNUSED_CANDIDATE | No source/resource reference |
| `androidx.viewpager2:viewpager2:1.0.0` | UNUSED_CANDIDATE | No source/resource reference |
| `androidx.core:core-ktx:1.10.1` | UNUSED_CANDIDATE | No KTX use in main or tests; core is also transitive |
| `kotlinx-coroutines-core:1.10.2` | UNUSED_CANDIDATE | No coroutine reference |
| `kotlinx-coroutines-android:1.10.2` | UNUSED_CANDIDATE | No coroutine reference |
| `project(':ffmpegplayer')` | USED | Demo imports/uses `FFmpegPlayer` and `FFmpegNative` |
| `com.blankj:utilcodex:1.31.1` | UNUSED_CANDIDATE | No source/resource reference |
| `junit:junit:4.13.2` | USED | Java and Kotlin local unit tests |

The resolved app graph also demonstrates substantial transitive version
alignment (for example declared AppCompat 1.4.1 resolves to 1.6.1 and declared
ViewPager2 1.0.0 resolves to 1.1.0-beta02). Cleanup and compatibility testing
belong to PH5, not PH0.

# Release Configuration

| Setting | `ffmpegplayer` release | `app` release |
| --- | --- | --- |
| compileSdk | 35 | 36 |
| minSdk | 24 | 24 |
| targetSdk | 35 | 31 |
| ABI filters | arm64-v8a, armeabi-v7a | arm64-v8a, armeabi-v7a |
| `minifyEnabled` | `false` | `false` |
| `debuggable` | Not declared; no debuggable flag in AAR manifest | **`true`** |
| ProGuard input | `proguard-rules.pro` configured for own release minification, currently inactive because minify is off | Default optimized rules + `app/proguard-rules.pro`, currently inactive because minify is off |
| consumer rules declaration | **No `consumerProguardFiles`** | N/A |

The Demo release being debuggable is a production-hardening issue. It does not
alter the AAR, but it leaves Demo release debugging and all compiled UI paths
available. No release variant was changed in PH0.

# AAR Contents and Size

- File: `ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar`
- Size: **20,554,393 bytes (19.60 MiB)**
- SHA-256:
  `3452EC697513B859CD5DAC6673EF711EF273E548ABF018AFB1A9A442522ACE2D`
- `classes.jar`: present, 10,735 uncompressed bytes.
- `AndroidManifest.xml`: present; minSdk 24 and INTERNET permission.
- `R.txt`: present and empty, as expected for a resource-free library.
- ABI directories: exactly `arm64-v8a`, `armeabi-v7a`.
- `proguard.txt`: **absent**.

Native entry sizes below are the uncompressed sizes recorded by the AAR ZIP:

| ABI | Native library | Bytes |
| --- | --- | ---: |
| arm64-v8a | `libavcodec.so` | 12,956,800 |
| arm64-v8a | `libavdevice.so` | 49,456 |
| arm64-v8a | `libavfilter.so` | 3,781,768 |
| arm64-v8a | `libavformat.so` | 2,612,400 |
| arm64-v8a | `libavutil.so` | 731,072 |
| arm64-v8a | `libnative-ffmpeg.so` | 1,455,848 |
| arm64-v8a | `libswresample.so` | 90,240 |
| arm64-v8a | `libswscale.so` | 719,792 |
| **arm64-v8a total** | **8 libraries** | **22,397,376** |
| armeabi-v7a | `libavcodec.so` | 12,596,904 |
| armeabi-v7a | `libavdevice.so` | 44,236 |
| armeabi-v7a | `libavfilter.so` | 3,125,304 |
| armeabi-v7a | `libavformat.so` | 2,500,428 |
| armeabi-v7a | `libavutil.so` | 659,256 |
| armeabi-v7a | `libnative-ffmpeg.so` | 1,011,652 |
| armeabi-v7a | `libswresample.so` | 83,216 |
| armeabi-v7a | `libswscale.so` | 566,228 |
| **armeabi-v7a total** | **8 libraries** | **20,587,224** |

The total uncompressed native payload in the AAR is 42,984,600 bytes. The
source `jniLibs` total is smaller because it excludes the two generated
`libnative-ffmpeg.so` files.

`classes.jar` contains these consumer-visible classes:

```text
FFmpegPlayer
FFmpegPlayer$Listener
FFmpegNative
FFmpegNative$PlayerEventListener
FFmpegNative$OesFrameListener
LiveAudioPcmSink
```

# Consumer ProGuard Status

Status: **ISSUE**.

`ffmpegplayer/consumer-rules.pro` contains appropriately scoped keep rules for
`FFmpegPlayer`, `FFmpegNative`, nested bridge types, and JNI-called
`LiveAudioPcmSink` methods. An identical `proguard-rules.pro` also exists.
However, `ffmpegplayer/build.gradle` does not declare
`consumerProguardFiles 'consumer-rules.pro'`, and the regenerated AAR contains
no `proguard.txt`. The file's existence therefore does not protect a minified
consumer.

This is a real consumer/JNI release risk: R8 in a downstream application can
rename or remove classes/methods that native registration or callbacks identify
by exact package/name/signature. PH0 records the issue without changing Gradle;
PH3 must wire and verify the rules, followed by a minified consumer smoke test
in PH7.

# Public API Baseline

The following baseline was read from the regenerated AAR using `javap -public`.
Inherited `Object` methods are omitted.

## FFmpegPlayer

- Class: `public final`, implements `AutoCloseable`.
- Constructors: `public FFmpegPlayer()`.
- Public constants: none.
- Protected API: none.
- Nested public types: `public interface Listener` with
  `void onPlayerEvent(String event, String eventJson)`.
- Public methods:

```text
void setListener(FFmpegPlayer.Listener)
String setSurface(android.view.Surface)
String clearSurface()
String prepare(String, int)
String start()
String pause()
String stop()
String setAudioEnabled(boolean)
String setHardwareDecodeEnabled(boolean)
String setHardwareRenderMode(String)
String setRtspTransport(String)
String setLatencyMode(String)
String setPlayerOption(String, String)
String setReconnectOptions(boolean, int, int)
String setThermalEnabled(boolean)
String setThermalPalette(int)
String setThermalAgcEnabled(boolean)
String setThermalGamma(float)
String setThermalWindow(float, float)
String startRecord(String)
String startSegmentRecord(String, int)
String startRecordWithConfig(String, String, int)
String stopRecord()
String takeSnapshot(String)
String getState()
String getStats()
String getReconnectState()
String getLatencyConfig()
String getRecordState()
boolean isReleased()
String release()
void close()
```

## FFmpegNative exposure

`FFmpegNative` is a public final class in `classes.jar`, with public constants,
`PlayerEventListener`, `OesFrameListener`, diagnostics/probe calls, and the full
raw-handle JNI API. `LiveAudioPcmSink` is also public. `FFmpegPlayer` depends on
both internally, so they are implementation dependencies; consumers are not
required to call them directly, but the types and methods are currently exposed
and the Demo itself calls `FFmpegNative` for info/stress operations. They remain
the documented `PUBLIC_LEGACY_BRIDGE` in PH0.

## README differences frozen for PH6

- README says the AAR contains `proguard.txt`; the regenerated AAR does not.
- README says `:app:testDebugUnitTest` fails in KAPT on
  `@error.NonExistentClass`; it passes in PH0.
- README says Maven coordinates are available and the AAR has no external
  dependencies, but the current library build file has no publishing plugin or
  publication block, and the release runtime graph contains lifecycle, Kotlin,
  annotations, and coroutines.
- README says the Demo main line does not use `FFmpegNative`; the info button and
  lifetime-stress long press directly use it.
- The FFmpegPlayer API row enumerates `low_latency/balanced/stable` but omits the
  implemented/documented-in-code `ultra_low_latency` mode.
- The sample low-latency JSON shows `socketBufferSize=102400`; the current
  low-latency profile uses 262144. The frozen explicit UDP/BALANCED baseline is
  instead 100000/4/262144 for delay/reorder/socket.

# Debug/Test Hooks

No build-type guard or source-set isolation prevents the following entries from
being compiled into release artifacts.

| Entry | Release reachability | Classification | PH0 evidence |
| --- | --- | --- | --- |
| `FFmpegNative.probe(url, timeoutMs)` | Public AAR API | DIAGNOSTIC | Opens/probes a URL and returns JSON |
| `FFmpegNative.runDebugCommand(args)` | Public AAR API | DIAGNOSTIC | General dispatcher compiled and JNI-registered in release |
| `runDebugCommand`: version/buildconf/decoders/config/source/help commands | Public dispatcher | DIAGNOSTIC | Read-only build/source/capability information |
| `runDebugCommand`: `-probe` / `ffprobe` | Public dispatcher | DIAGNOSTIC | Opens/probes a caller-provided source |
| `runDebugCommand`: `-player-lifetime-stress` | Public dispatcher and Demo info-button long press | TEST_ONLY | Runs create/release/concurrency stress loops in-process |
| `runDebugCommand`: `-audio-backpressure-test [ms]` | Public dispatcher | TEST_ONLY | Mutates a process-global test delay used by the audio worker; default is zero |
| `setAudioWorkerBackpressureTestDelayMs` | Reachable through the public dispatcher | TEST_ONLY | Native hook is compiled into release |
| `mediacodec_oes` experimental mode | Public render-mode option and Demo intent path | UNKNOWN | Explicitly labelled experimental/future and non-default |
| `ffplay` debug command token | Public dispatcher | DIAGNOSTIC | Always returns unsupported; no playback implementation behind it |

The production `diagnostics_mode` option and bounded BASIC/LATENCY metrics are
normal observability controls, not classified as test-only hooks. PH4 should
isolate or gate the two test-only paths while preserving intentionally supported
diagnostics.

# Source / Package Metrics

Metrics use tracked files to exclude generated build output:

| Metric | PH0 baseline |
| --- | ---: |
| Tracked files under `ffmpegplayer/src` | 191 |
| Tracked files under `ffmpegplayer/src/main` | 188 |
| Code files including vendored FFmpeg headers and native tests | 175 |
| Java files under `ffmpegplayer/src` | 3 |
| Kotlin files under `ffmpegplayer/src` | 0 |
| Owned production native C/C++ source/header files | 26 |
| Direct native C++ test files | 3 |
| Owned native production + test files | 29 |
| Native files including vendored FFmpeg headers | 172 |
| `NativePlayer.cpp` lines | 6,182 |
| `NativePlayer.h` lines | 618 |
| Source `jniLibs` files | 14 (7 per ABI) |
| Source `jniLibs` total | 40,517,100 bytes (38.64 MiB) |
| Release AAR | 20,554,393 bytes (19.60 MiB) |
| AAR uncompressed native total | 42,984,600 bytes |

“Owned native” excludes the checked-in FFmpeg SDK headers under
`cpp/ffmpeg/include`; the broader counts retain them so PH7 comparisons can use
either definition without ambiguity.

# PH1-PH7 Candidates

- **PH1 — Native dependency correctness:** validate the `swresample` required
  load contract, prove actual runtime reachability of every FFmpeg component,
  and establish safe dependency/load ordering before removal work.
- **PH2 — AAR native slimming:** after PH1 evidence and device regression,
  remove only proven-unused native payloads (current candidates:
  `libavfilter.so`, `libavdevice.so`) and compare per-ABI/AAR sizes.
- **PH3 — Library Gradle / consumer rules:** wire `consumerProguardFiles`, verify
  AAR `proguard.txt`, reduce Java-only library plugin/dependency footprint, pin
  reproducible native tooling as appropriate, and verify publication metadata.
- **PH4 — Debug/test hook isolation:** remove or release-gate lifetime stress and
  audio backpressure mutation while deciding the supported diagnostic API
  surface.
- **PH5 — Demo cleanup:** remove verified-unused plugins/dependencies, resolve
  duplicate Kotlin/KAPT/KSP configuration, and make release non-debuggable
  without changing player behavior.
- **PH6 — API / README alignment:** decide the legacy native bridge contract,
  freeze an API signature baseline, and correct the AAR, test, dependency,
  profile, JSON, publication, and Demo-use documentation mismatches.
- **PH7 — Consumer smoke test / final freeze:** test the release AAR from an
  independent minified consumer for both ABIs, JNI registration, load order,
  facade lifecycle, real RTSP playback, and package-size deltas; then issue the
  final production freeze.

# Risks

1. **High — consumer R8/JNI correctness:** consumer keep rules are not embedded
   in the AAR. A minified third-party app may break exact JNI class/method
   contracts.
2. **High — release test mutation:** public release code can activate the
   process-global audio backpressure test delay or run player lifetime stress.
3. **Medium — Demo release security posture:** `app` release is explicitly
   debuggable and unminified.
4. **Medium — native contract inconsistency:** `swresample` is loaded as
   optional even though required by the ELF dependency graph.
5. **Medium — native/package size:** two not-main-chain FFmpeg libraries consume
   7,000,764 bytes (about 6.68 MiB) across both ABIs before ZIP compression;
   removal still requires PH1 proof and PH2 runtime regression.
6. **Medium — dependency footprint:** the Java-only AAR exposes unnecessary
   Kotlin/lifecycle/coroutines runtime dependencies; the Demo has multiple
   likely-unused direct libraries and build plugins.
7. **Medium — public surface:** raw-handle JNI and diagnostic/test dispatch are
   public in `classes.jar`, increasing compatibility and misuse obligations.
8. **Low/Medium — reproducibility/config drift:** the NDK is selected by the
   current environment rather than pinned in module configuration, and Demo vs
   library compile/target SDK values differ.
9. **Pending validation:** PH0 did not perform a standalone, minified consumer
   AAR smoke test; that is an explicit PH7 gate.

# PH0 Freeze

- PH0 baseline audit: **COMPLETE**.
- Build baseline: **PASS**.
- Module boundary: **PASS**.
- Baseline audited commit: `f728fd23c58cf0e20444736d510ce134b51964cc`.
- `CODE_CHANGE = NO`; this report is the only tracked PH0 modification.
- Final player pipeline and explicit RTSP UDP/BALANCED profile: unchanged.
- ABI/package/JNI/public API behavior: unchanged.
- No `.so`, Gradle dependency, debug hook, API, or implementation was removed or
  modified.
- The issues above are candidates/gates for PH1-PH7, not implicitly accepted
  fixes.
- **PH1 readiness: READY** to begin only under a separate explicit task.

PH0 is frozen. Do not begin PH1 as part of this slice.
