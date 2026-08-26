# Production Hardening PH3 Library Gradle & Consumer Rules

# Scope

PH3 hardens only the `ffmpegplayer` Android library module. It removes Gradle
configuration proven unused by the Java-only library, wires the existing
consumer ProGuard contract into the AAR, narrows that contract to actual JNI
boundaries, and validates the rebuilt artifact and rules.

No app/Demo configuration, native library, CMake file, Java/C++ player source,
public API, JNI package, RTSP option, decoder, renderer, audio, recording,
thermal, reconnect, or player lifecycle implementation was changed.

PH3 started from a clean `dev` worktree at
`279d52f build(player): slim unused ffmpeg native libraries`. The PH0, PH1,
and PH2 production-hardening reports were read in full before modification.

# Gradle Audit

The audit covered `ffmpegplayer/build.gradle`, its plugins, dependencies,
`android {}`, build types, consumer rules, CMake configuration, and `jniLibs`
packaging.

| Item | Before | Evidence | PH3 result |
| --- | --- | --- | --- |
| `com.android.library` | Applied | Required to produce Android library variants/AAR | KEPT |
| `org.jetbrains.kotlin.android` | Applied | Zero `.kt` files under `ffmpegplayer`; all production sources are three Java files | REMOVED |
| Kotlin compilation tasks | Present, `NO-SOURCE` | Prior builds executed/configured `compile*Kotlin` despite no Kotlin source | REMOVED |
| `fileTree(..., dir: 'libs')` | Declared | `ffmpegplayer/libs` contains no JAR or other file | REMOVED |
| `androidx.lifecycle:lifecycle-common-jvm:2.8.7` | Declared | No lifecycle import/API reference in module source | REMOVED |
| `consumerProguardFiles` | Missing | PH2 AAR contained no consumer rules | CONFIGURED |
| ABI filters | arm64-v8a, armeabi-v7a | Required production ABIs | UNCHANGED |
| CMake / `jniLibs` packaging | PH2 frozen | Outside PH3 and already dependency-complete | UNCHANGED |
| Debug/release `minifyEnabled` | `false` | Internal library shrinking was not part of the requested consumer contract | UNCHANGED |

Before PH3, `releaseRuntimeClasspath` contained direct Kotlin stdlib and
`lifecycle-common-jvm`, plus lifecycle transitives including AndroidX
annotations, Kotlin stdlib variants, and kotlinx-coroutines. After the removal,
Gradle reports:

```text
releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
No dependencies
FFMPEGPLAYER_KOTLIN_TASK_MATCH_COUNT=0
```

# Removed Plugins / Dependencies

```text
Kotlin plugin: REMOVED
Lifecycle dependency: REMOVED
Empty libs fileTree dependency: REMOVED
```

The module has no Kotlin source or Kotlin-facing API and no lifecycle source
reference. The app module's Kotlin/KAPT/plugins and dependencies were not
audited or modified because they belong to PH5.

# Consumer ProGuard Contract

`defaultConfig` now explicitly declares:

```groovy
consumerProguardFiles 'consumer-rules.pro'
```

Status:

```text
consumerProguardFiles: CONFIGURED
JNI keep rules: PASS
```

The consumer rules were narrowed from whole-class/whole-nested-class keeps to
the actual native boundary:

- Keep the exact `FFmpegNative` class name and its native methods because
  `JNI_OnLoad` uses `FindClass` and `RegisterNatives` with fixed names and
  descriptors.
- Keep the exact `FFmpegNative$OesFrameListener` class name and `(long)`
  constructor because native code finds and constructs it by name/signature.
- Keep `FFmpegNative$PlayerEventListener` and its exact `onPlayerEvent` method,
  plus implementations of that callback, because both the registered native
  descriptor and runtime `GetMethodID` depend on them.
- Keep only `onAudioPcm`, `onAudioControl`, and `getPlaybackHeadFrames` member
  names on `LiveAudioPcmSink`; native code obtains its runtime class from the
  object and never looks up the class by binary name.

Obsolete broad consumer keeps for every `FFmpegNative$*` nested type and every
`FFmpegPlayer` member were removed. There is no `-keep class
com.example.motro.** { *; }` or equivalent package-wide keep.

The module-local `proguard-rules.pro` is not the consumer contract, is not
packaged into the AAR, and remains inactive while library minification is off;
PH3 does not modify that out-of-scope internal build input.

# JNI/R8 Mapping

Native lookup results were mapped to the consumer rules as follows:

| Native operation | Java target | Consumer protection |
| --- | --- | --- |
| `FindClass` + `RegisterNatives` | `com/example/motro/ffmpeg/FFmpegNative` | Exact class plus `native <methods>` keep |
| Registered native table | 41 Java native name/descriptor pairs | All 41 matched Java `javap -s -p`; zero missing/extra pairs |
| `FindClass` + `GetMethodID("<init>", "(J)V")` | `FFmpegNative$OesFrameListener` | Exact class and long constructor keep |
| Registered descriptor + `GetMethodID` | `PlayerEventListener.onPlayerEvent(long,String,String)` | Interface and implementation member keep |
| `GetObjectClass` + three `GetMethodID` calls | `LiveAudioPcmSink` callback methods | Three exact member keeps; class name may be obfuscated |
| Android framework lookups | `SurfaceTexture`, `Surface`, `Looper`, `Handler` and framework methods | Platform library types; no consumer keep required |

There is no owned `GetFieldID`/`GetStaticFieldID` target. The only owned
`GetStaticMethodID` target is the Android framework `Looper.getMainLooper`.
The Java/native package contract remains exactly `com/example/motro/ffmpeg`.

# Release AAR Verification

Rebuilt artifact:

```text
path: ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
size: 17,585,386 bytes (16.77 MiB)
SHA-256: 115B7CA8CFD66046D602FFD5FE25C5B893A9C3E130DBB39508EEAA0E11CD2FDC
```

Required root entries are present:

```text
AndroidManifest.xml
classes.jar
proguard.txt
R.txt
META-INF/com/android/build/gradle/aar-metadata.properties
```

`proguard.txt` is 1,205 bytes and is byte-identical to
`ffmpegplayer/consumer-rules.pro`; both have SHA-256
`F1CDBE693327A47C09BD537A28B6173BD01F4ED3C3153B96275CCB489C755C3A`.
The PH2 artifact had no consumer-rule entry.

Each ABI contains the unchanged PH2 six-library set:

```text
libavcodec.so
libavformat.so
libavutil.so
libnative-ffmpeg.so
libswresample.so
libswscale.so
```

Both `arm64-v8a` and `armeabi-v7a` have exactly six `.so` entries, with no
`libavfilter.so` or `libavdevice.so`. AAR structure: **PASS**.

The rebuilt `classes.jar` remains 10,505 bytes with SHA-256
`63DEEBC87F3902D6874098EB25CD7128E22D964D4A9C4EDB6DBF81D1A5EA6A74`,
byte-identical to PH2. This proves the unminified library bytecode and public API
did not change. The AAR grew by 557 compressed bytes solely because the
consumer-rule payload is now present.

# R8 Smoke Validation

```text
R8 smoke: PARTIAL
```

The repository has an app consumer, but its release build has
`minifyEnabled false`; changing Demo release configuration is outside PH3. PH3
therefore did not claim a complete minified-consumer or device smoke test.

A local classfile R8 8.6.2-dev smoke was run with Android 35 libraries, the
rebuilt Release `classes.jar`, the exact packaged consumer rules, and a minimal
simulated consumer entry that constructs `FFmpegPlayer`. R8 completed
successfully. Inspection of its shrunk/obfuscated output proved:

- all 41 `FFmpegNative` native method names/descriptors remain present;
- `FFmpegNative` and both required nested JNI contract names remain stable;
- the OES `(long)` constructor remains present;
- `onPlayerEvent(long,String,String)` remains present on the interface and its
  actual implementation even though the implementation class is obfuscated;
- all three native audio callback names/descriptors remain present while
  `LiveAudioPcmSink` itself is safely obfuscated to `a.a`.

This validates rule syntax and the static JNI/R8 contract without broad keeps.
A full install/run of a minified independent consumer remains a PH7 gate, so
the PH3 classification is intentionally PARTIAL rather than PASS.

# Build / Tests

| Gate | Result | Evidence |
| --- | --- | --- |
| `git diff --check` | PASS | No whitespace errors; Windows line-ending warnings only |
| Post-change Release runtime dependency graph | PASS | `No dependencies` |
| Post-change ffmpegplayer Kotlin task audit | PASS | Zero Kotlin task matches |
| `:app:testDebugUnitTest` | PASS | Existing Java/Kotlin Demo unit tests completed |
| `:ffmpegplayer:assembleDebug` | PASS | Debug AAR built for both ABIs |
| `:ffmpegplayer:assembleRelease` | PASS | Release AAR built for both ABIs |
| `:app:assembleDebug` | PASS | Demo consumed the hardened library configuration |
| Release AAR extraction/structure | PASS | Manifest, classes, rules, metadata, and native payload verified |
| Java/native registration pair audit | PASS | 41 versus 41, zero missing/extra |
| Local scoped R8 rule smoke | PASS within static scope | Required JNI names survived shrink/obfuscation |

Gradle completed 113 actionable tasks (36 executed, 77 up-to-date) with
`BUILD SUCCESSFUL`. Existing `LiveAudioPcmSink` deprecation and app-module KAPT
warnings did not fail the gates and are outside PH3.

# Functional Invariants

```text
Playback behavior: NO CHANGE
RTSP config: NO CHANGE
Decoder: NO CHANGE
Renderer: NO CHANGE
Audio: NO CHANGE
Recording: NO CHANGE
Thermal: NO CHANGE
Reconnect: NO CHANGE
Public API: NO CHANGE
JNI package: NO CHANGE
```

No player implementation source changed. The exact PH2 native payload and
`classes.jar` remain intact.

# Remaining Risks

1. A fully minified independent consumer APK has not yet been assembled,
   installed, and exercised on device; this remains the explicit PH7 gate.
2. The Demo's own plugins, dependencies, KAPT warnings, release debuggability,
   and minification policy belong to PH5 and were intentionally untouched.
3. The module-local internal `proguard-rules.pro` remains broad but inactive and
   is not exported to consumers; any future library self-minification must
   independently review it.
4. Debug/test hook isolation and API/README alignment remain PH4 and PH6 work;
   neither was started here.

# PH3 Freeze

- Kotlin plugin: **REMOVED**.
- Lifecycle dependency: **REMOVED**.
- Empty library fileTree: **REMOVED**.
- `consumerProguardFiles`: **CONFIGURED**.
- Release AAR consumer rules: **PRESENT** and byte-verified.
- JNI/R8 rules: **PASS**.
- Release AAR structure and both-ABI native set: **PASS**.
- R8 smoke: **PARTIAL**, with scoped R8 validation complete and PH7 consumer
  runtime still pending.
- Builds and unit tests: **PASS**.
- Functional, public API, and JNI package invariants: **NO CHANGE**.
- PH3 freeze: **YES**.

# PH4 Readiness

PH4 readiness: **READY**. PH4 may isolate debug/test hooks only under a separate
explicit task. PH3 does not begin PH4.
