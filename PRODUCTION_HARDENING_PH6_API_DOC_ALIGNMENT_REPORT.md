# Production Hardening PH6 — Public API & Documentation Alignment Report

Date: 2026-08-26

Baseline commit: `77baea0f6426253f81be337fa86aca03ef44bd51 build(app): clean demo production dependencies`

# Scope

PH6 audited the consumer-visible Java surface of the actual `ffmpegplayer` source and rebuilt Release AAR, aligned `README.md` with current source/Gradle/artifact behavior, added only three safe Thermal facade constants, and added a direct equality test. No native, JNI, CMake, decoder, renderer, RTSP, audio, recording, thermal algorithm, reconnect, Surface lifecycle, test-hook policy, dependency, or publishing implementation was changed.

The worktree was clean at PH6 start. During the task an out-of-scope `MediaPlayerActivity.java` URL-initialization edit appeared independently; it was preserved, excluded from PH6 staging, and is not described as a PH6 change. PH3, PH5, L6, post-latency cleanup, and the prior README were read before modification.

# Public API Baseline

The rebuilt `classes.jar` contains exactly three public top-level Java types plus their nested/anonymous classes:

```text
com.example.motro.ffmpeg.FFmpegPlayer
com.example.motro.ffmpeg.FFmpegNative
com.example.motro.ffmpeg.LiveAudioPcmSink
```

`PlayerOptions`, `DiagnosticsMode`, and `ThermalConfig` are native C++ internal types. They have no Java declaration and are not present in `classes.jar`.

## Primary facade: FFmpegPlayer

`FFmpegPlayer` is `public final`, implements `AutoCloseable`, has one public no-argument constructor, and owns the native handle, audio sink, event bridge, and lifecycle lock.

Additive public constants after PH6:

```java
public static final int THERMAL_PALETTE_ORIGINAL = 0;
public static final int THERMAL_PALETTE_WHITE_HOT = 1;
public static final int THERMAL_PALETTE_IRONBOW = 2;
```

Nested callback contract:

```java
public interface Listener {
    void onPlayerEvent(String event, String eventJson);
}
```

Constructor and public method signatures:

```text
FFmpegPlayer()
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

All 32 pre-existing `FFmpegPlayer` method signatures and its constructor are unchanged. Including `Listener.onPlayerEvent`, the README verifier found all 33 method/callback names documented.

## Public legacy bridge: FFmpegNative

`FFmpegNative` remains `public final` for JNI registration and compatibility. It has no public constructor. It exposes five reconnect event String constants, three Thermal int constants, `PlayerEventListener`, `OesFrameListener(long)`, and these 40 public static native methods:

```text
String getFFmpegVersion()
String getFFmpegBuildConfig()
String getAvailableDecoders()
String getMediaCodecInfo()
String probe(String, int)
String runDebugCommand(String[])
long createPlayer()
String setPlayerSurface(long, Surface)
String preparePlayer(long, String, int)
String startPlayer(long)
String pausePlayer(long)
String stopPlayer(long)
String getPlayerState(long)
String releasePlayer(long)
String takePlayerSnapshot(long, String)
String getPlayerStats(long)
String clearPlayerSurface(long)
String setAudioCallback(long, Object)
String setPlayerEventListener(long, PlayerEventListener)
String enableAudio(long, boolean)
String setPlayerReconnectOptions(long, boolean, int, int)
String getPlayerReconnectState(long)
String setPlayerRtspTransport(long, String)
String getPlayerRtspTransportState(long)
String setRtspTransport(long, String)
String setPlayerLatencyMode(long, String)
String setPlayerOption(long, String, String)
String setHardwareDecode(long, boolean)
String setHardwareRenderMode(long, String)
String setThermalEnabled(long, boolean)
String setThermalPalette(long, int)
String setThermalAgcEnabled(long, boolean)
String setThermalGamma(long, float)
String setThermalWindow(long, float, float)
String getPlayerLatencyConfig(long)
String startPlayerRecord(long, String)
String startPlayerSegmentRecord(long, String, int)
String startPlayerRecordWithConfig(long, String, String, int)
String stopPlayerRecord(long)
String getPlayerRecordState(long)
```

The JNI table also registers one private native OES notification method, producing the PH3 total of 41 registered native name/descriptor pairs. PH6 does not change any of those 41 pairs.

## Public JNI callback implementation: LiveAudioPcmSink

`LiveAudioPcmSink` remains public for its existing JNI/callback compatibility contract. Ordinary consumers do not instantiate it. Its public surface is:

```text
CMD_START = 0
CMD_PAUSE_FLUSH = 1
CMD_RELEASE = 2
WRITE_CANCELLED = -10000
LiveAudioPcmSink()
int onAudioPcm(ByteBuffer, int, long)
int onAudioControl(int)
int getPlaybackHeadFrames()
```

## Return, lifecycle, and thread semantics

- `FFmpegPlayer` String operations retain their JSON contract: success responses contain `success=true`; failures contain `success=false` and the implementation's error fields.
- `release()` is idempotent. It clears ownership before native release; repeated release returns a success JSON. `close()` delegates to `release()`.
- After release, String player operations return an error without using a stale handle, `setListener` is ignored, and `isReleased()` returns true.
- Facade calls are serialized by the instance lock. Potentially blocking lifecycle work should not run on the Android main thread.
- Listener callbacks arrive on a native callback thread; consumers must marshal UI updates to the Android main thread.
- Stats preserve existing invalid/unknown semantics. Consumers must check `success`, validity/sample-count fields, and documented sentinel values before interpreting unavailable clocks or distributions.

# API / README Mismatches

| Issue | Before | Final | Action |
|---|---|---|---|
| Thermal facade example | README used `FFmpegPlayer.THERMAL_PALETTE_WHITE_HOT`, but the constant existed only on `FFmpegNative` | All three facade constants exist and equal the legacy values | Added safe aliases and equality test |
| Legacy type visibility | `FFmpegNative` and `LiveAudioPcmSink` were labelled internal despite being public in source/AAR | Documented as public legacy/JNI compatibility surface, not preferred consumer entry | Corrected README |
| Maven plugin | README described `maven-publish` as configured | Publishing is explicitly `NOT_CONFIGURED` | Removed stale module claim |
| Maven command/coordinates | README advertised a non-existent publish task and coordinates | No publish command or coordinates are promised | Removed stale command/claim and documented deferred status |
| Source native count | README claimed 7 FFmpeg `.so` files per ABI | Source `jniLibs` contains 5 required FFmpeg `.so` files per ABI | Corrected count |
| AAR native count | README claimed generated native library plus 7 FFmpeg libraries | Rebuilt AAR contains 6 total `.so` files per ABI: generated library plus 5 FFmpeg libraries | Corrected artifact description |
| `swresample` load policy | README called it optional | It is required by Java loader, audio SWR use, and ELF `DT_NEEDED` | Corrected load-order comment |
| JVM test status | README claimed stale KAPT/template-test failure | app Debug/Release unit tests pass; library Java tests are `NO-SOURCE` | Replaced stale test statement |
| Playback latency profiles | README omitted `ultra_low_latency` in API lists | Documents all four real profiles | Corrected lists |
| Demo legacy bridge boundary | README claimed no direct `FFmpegNative` use | Playback uses facade; Info, Debug-only stress, and compatibility constants use the legacy bridge | Corrected boundary statement |
| Stats field ownership | `audioRecordingIndependentOfPlayback` appeared under `getStats()` | Documented under Audio/record result JSON; `recordAudioPacketCount` remains the Stats field | Corrected field location |
| Stats shorthand | Several abbreviated names were not literal JSON keys | All 102 documented Stats keys use their exact emitted names | Expanded exact names and verified against source |
| Diagnostics contract | OFF/BASIC/LATENCY and test-hook separation were not clearly documented | Default BASIC and all three production modes are explicit; hook policy is separate | Added verified section |
| AAR consumption/R8 | Independent file-AAR use and rule propagation were incomplete | Module and file-AAR usage, ABIs, artifact path, and consumer-rule behavior are explicit | Added verified instructions |
| Lifecycle/thread requirements | Facade comments overgeneralized post-release behavior; README lacked callback/thread guidance | Exact release, locking, blocking-call, and callback-thread semantics documented | Corrected Javadoc and README |

Ten stale factual example/claim groups were removed or corrected; additional missing consumer guidance was added. Historical freeze reports remain immutable evidence snapshots and were not rewritten to impersonate current source state.

# Facade Changes

The only production source additions are three compile-time aliases:

```java
FFmpegPlayer.THERMAL_PALETTE_ORIGINAL
        == FFmpegNative.THERMAL_PALETTE_ORIGINAL; // 0
FFmpegPlayer.THERMAL_PALETTE_WHITE_HOT
        == FFmpegNative.THERMAL_PALETTE_WHITE_HOT; // 1
FFmpegPlayer.THERMAL_PALETTE_IRONBOW
        == FFmpegNative.THERMAL_PALETTE_IRONBOW; // 2
```

The aliases are source/binary-compatible additions. No original constant was deleted, no method was added/removed/renamed, and no native call or behavior changed. A new `FFmpegPlayerFacadeConstantsTest` checks all three equalities in both app unit-test variants.

# README Changes

- Added verified module and standalone local-AAR dependency examples.
- Corrected AAR structure, ABI/native library counts, required load order, and embedded R8 rules.
- Replaced the non-existent Maven publishing workflow with `NOT_CONFIGURED`.
- Documented the complete facade method/constant surface, lifecycle, threading, return semantics, and legacy bridge boundary.
- Corrected all four latency profiles and added the independent OFF/BASIC/LATENCY diagnostics contract.
- Documented Debug/Release test-hook separation without presenting stress commands as production diagnostics.
- Corrected exact Stats JSON keys and invalid/unknown interpretation.
- Updated current build and unit-test commands/status.

Automated post-edit verification:

```text
FACADE_METHOD_AND_CALLBACK_COUNT=33
README_MISSING_FACADE_METHOD_COUNT=0
FACADE_CONSTANT_COUNT=3
README_MISSING_FACADE_CONSTANT_COUNT=0
DOCUMENTED_STATS_KEY_COUNT=102
MISSING_STATS_KEY_COUNT=0
```

# Diagnostics Documentation

Actual native contract:

| Value | Production meaning |
|---|---|
| `off` | Disables optional diagnostics logging and advanced latency collection; playback remains active |
| `basic` | Default production health diagnostics with bounded, low-overhead counters and compact health output |
| `latency` | Detailed latency distributions, PRET0, RTCP/PRFT/E2E correlation, and full latency output |

Java has no public `DiagnosticsMode` enum. Consumers use:

```java
player.setPlayerOption("diagnostics_mode", "off|basic|latency");
```

The production default is **BASIC**, confirmed by `PlaybackDiagnostics::mode_{DiagnosticsMode::Basic}`. `Test Hook != Diagnostics`: the two PH4 stress/backpressure hooks remain Debug-only; Release rejects them with `unsupported_in_release`. Read-only legacy diagnostic commands remain available in both variants.

# Publishing Status

Maven publishing: **NOT_CONFIGURED**.

`ffmpegplayer/build.gradle` applies only `com.android.library`. It contains no `maven-publish`, `publishing`, `publication`, `groupId`, `artifactId`, or repository publication configuration. No Maven coordinates or `publishReleasePublicationToMavenLocal` command are promised. PH6 intentionally does not implement publishing.

Current delivery path:

```text
ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
```

# Compatibility

Final Release AAR:

```text
path: ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
size: 17,575,233 bytes
SHA-256: D66AA1D624924C0C224D1037E5604FFCCD6D6362E5C652D1939525353F959A41
classes.jar size: 10,609 bytes
classes.jar SHA-256: C75FD70389765AD015E4B3AA8F1BC50529D65F989F20276586E3BBF5099B467D
```

Artifact verification:

- `AndroidManifest.xml`, `classes.jar`, `proguard.txt`, `R.txt`, and AAR metadata: present.
- `arm64-v8a` and `armeabi-v7a`: present, exactly six native libraries per ABI.
- Required libraries: `libavcodec`, `libavformat`, `libavutil`, `libnative-ffmpeg`, `libswresample`, `libswscale`.
- Removed PH2 libraries `libavfilter` and `libavdevice`: absent.
- Packaged `proguard.txt` SHA-256 remains `F1CDBE693327A47C09BD537A28B6173BD01F4ED3C3153B96275CCB489C755C3A`, byte-identical to the PH3/PH5 baseline.
- `javap` comparison: 33 existing facade constructor/method signatures unchanged; all legacy bridge/sink signatures unchanged; only three additive facade fields appear.

Public API breaking change: **NO**

JNI change: **NO**

Player behavior change: **NO**

# Build / Tests

| Gate | Result | Evidence |
|---|---|---|
| `git diff --check` | PASS | No whitespace errors; informational LF-to-CRLF warnings only |
| `:ffmpegplayer:assembleDebug` | PASS | Both configured ABIs built |
| `:ffmpegplayer:assembleRelease` | PASS | Release AAR rebuilt and inspected |
| `:app:assembleDebug` | PASS | Demo compiled against the additive facade |
| `:app:assembleRelease` | PASS | Release compiled; `lintVitalRelease` passed |
| `:app:testDebugUnitTest` | PASS | Existing formatter test and new facade constant test passed |
| `:app:testReleaseUnitTest` | PASS | Existing formatter test and new facade constant test passed |
| `:ffmpegplayer:testDebugUnitTest` | PASS (`NO-SOURCE`) | No Java/Kotlin library unit-test source is configured |
| `:ffmpegplayer:testReleaseUnitTest` | PASS (`NO-SOURCE`) | No Java/Kotlin library unit-test source is configured |
| Debug test-hook policy host test | PASS | `ALL_DEBUG_TEST_HOOK_POLICY_TESTS_PASSED` |
| Release test-hook policy host test | PASS | `ALL_RELEASE_TEST_HOOK_POLICY_TESTS_PASSED` |
| Diagnostics mode host test | PASS | `ALL_DIAGNOSTICS_MODE_TESTS_PASSED` |
| PRE-T0 timing host test | PASS | `ALL_PRE_T0_TRACKER_TESTS_PASSED` |
| E2E timebase host test | PASS | `ALL_E2E_TIMEBASE_TESTS_PASSED` |
| `:ffmpegplayer:lintDebug` | PASS | Debug lint report generated |
| `:app:lintDebug` | PASS | Debug lint report generated |
| Release AAR public API comparison | PASS | No existing method/legacy signature diff; three expected constants only |

Gradle's existing future-Gradle-9 deprecation warning remains non-blocking and outside PH6.

# Remaining API Debt

- The String/JSON result surface is intentionally retained. A typed result API is a `FUTURE_API_CANDIDATE` and would require a separate compatibility design.
- `FFmpegNative` and `LiveAudioPcmSink` remain public legacy/JNI compatibility surface. Tightening visibility would be a breaking change.
- Java has no typed `PlayerOptions`, `DiagnosticsMode`, or `ThermalConfig` model. A future optional facade model must preserve current String APIs.
- The raw native bridge includes duplicate legacy RTSP transport entry points. Removing/renaming either is deferred as a breaking change.
- Maven publishing is not configured.
- Package rename, Kotlin facade, and publication/version policy remain deferred.
- Historical freeze reports contain time-specific prior states; README and current source/AAR are the live consumer contract.

# PH6 Freeze

- Public API audit: **PASS**.
- README/API alignment: **PASS**.
- Thermal facade constants: **ADDED** and value-verified.
- Diagnostics documentation: **ALIGNED**.
- Maven publishing documentation: **NOT_CONFIGURED**, accurately documented.
- Existing public method/binary surface removed or changed: **NO**.
- JNI/native/player implementation change: **NO**.
- Four required builds, direct unit tests, host diagnostics/hook tests, Lint, and AAR inspection: **PASS**.
- PH6 scope files: README, one facade source, one direct test, and this report only.
- Out-of-scope user app edit: preserved and excluded from PH6.

PH6 Freeze: **YES**.

# PH7 Readiness

PH0–PH6 production-hardening slices now have the required PH6 API/documentation alignment evidence. The Release AAR is ready for the separate PH7 independent/minified consumer and final-freeze validation.

PH7 readiness: **READY**.
