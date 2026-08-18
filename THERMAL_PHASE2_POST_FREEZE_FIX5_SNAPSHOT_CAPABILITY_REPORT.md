# Problem

Phase 2 moved the normal hardware path to MediaCodec CPU NV12 plus OpenGL ES. That path intentionally does not maintain `lastRgbaFrame_`. Before this fix, `mediacodec_nv12_gl` snapshot requests fell through to the native RGBA-frame check and returned an unstructured numeric error for “no video frame available”. The Java compatibility layer only attempted PixelCopy when the English error message contained `Snapshot is not supported`, so NV12 GL never reached PixelCopy.

The defect was a Snapshot capability/routing defect, not an NV12 renderer defect. The fix does not add GPU readback, an RGBA hot-path cache, or an NV12-to-RGBA snapshot conversion.

# Snapshot Architecture Before Fix

- Native RGBA rendering maintained `lastRgbaFrame_` and `SnapshotManager` could encode it as PNG/JPEG.
- `software_yuv_gl`, `mediacodec_surface`, and OES returned mode-specific English unsupported messages.
- `mediacodec_nv12_gl` did not have an equivalent capability result; it reached the empty RGBA cache and returned `errorCode:-1` / `no video frame available`.
- Java treated a response as successful using substring matching and selected PixelCopy by searching the human-readable message.
- Native stats equated `snapshotSupported` with `swsScaleEnabled`, which described native RGBA availability rather than end-user screenshot capability.

# Root Cause

There were two coupled causes:

1. Native snapshot capability was not modeled explicitly for every render mode. NV12 GL had no CPU RGBA frame by design, but that meant “surface capture required”, not “no decoded frame”.
2. Java routed on mutable English message text instead of a stable structured error code.

The pre-fix NV12 GL result was therefore `success=false`, numeric `errorCode=-1`, message `no video frame available`; Java did not invoke PixelCopy.

# Snapshot Capability Model

The implemented capability matrix is:

| Requested mode | Capture mode | Runtime status |
|---|---|---|
| `software_rgba` | `native_rgba` | Code-audited; current Demo UI normalizes Hardware OFF to `software_yuv_gl`, so this mode was not directly selectable for this run |
| `software_yuv_gl` | `surface_pixelcopy` | PASS on device |
| `mediacodec_nv12_gl` | `surface_pixelcopy` | PASS on device |
| `mediacodec_surface` | `surface_pixelcopy` | PASS on device, including when its current actual renderer reports `rgba_nativewindow` |
| `mediacodec_oes` | `surface_pixelcopy` when its final Surface is available | NOT_EXECUTED: existing OES Prepare crash occurred before playback/snapshot |

GL and direct-Surface requested modes keep the final Surface as their screenshot truth source. `software_rgba` keeps native CPU-frame capture. Availability of the native frame is checked separately, returning `SNAPSHOT_NO_FRAME` when absent.

# Error Code Model

Snapshot responses now preserve `success`, `message`, and `errorMessage`, and use stable string `errorCode` values:

| Code | Meaning |
|---|---|
| `SNAPSHOT_REQUIRES_SURFACE_CAPTURE` | Native does not own the final display image; Java may use PixelCopy |
| `SNAPSHOT_NO_FRAME` | No native frame or PixelCopy source data is available |
| `SNAPSHOT_NO_SURFACE` | Surface is missing, invalid, zero-sized, or changed during capture |
| `SNAPSHOT_IO_ERROR` | Path, open, write, encode, or save failure |
| `SNAPSHOT_UNSUPPORTED` | Mode/format/encoder is unsupported |
| `SNAPSHOT_PLAYER_RELEASED` | Handle is stale/released or becomes inactive during capture |
| `SNAPSHOT_PIXELCOPY_ERROR` | Other PixelCopy failure or timeout |
| `SNAPSHOT_PROTOCOL_ERROR` | Native returned malformed JSON to Java |

Backward-compatible human-readable messages remain present. Java does not interpret those messages for routing.

# Native RGBA Snapshot

`software_rgba` remains the only normal requested mode using the native RGBA frame snapshot. A successful result now includes `source:"native_rgba"` and `snapshotCaptureMode:"native_rgba"`. Empty frame state returns `SNAPSHOT_NO_FRAME`; path/encode/write failures return `SNAPSHOT_IO_ERROR`; unsupported suffixes return `SNAPSHOT_UNSUPPORTED`.

The existing PNG/JPEG implementation and current path/quality behavior remain intact. JPEG encoding may convert the already-existing RGBA snapshot frame to the encoder pixel format; this is not an NV12 GL snapshot conversion.

# Surface PixelCopy Snapshot

Java parses the complete native JSON using `JSONObject`. It invokes PixelCopy only when `success=false` and `errorCode` is exactly `SNAPSHOT_REQUIRES_SURFACE_CAPTURE`. Native success returns directly, and every other native failure returns directly. No English message substring controls routing.

PixelCopy targets the video `Surface` from the existing `SurfaceView` holder, creates one on-demand `ARGB_8888` bitmap, waits with the existing bounded operation, and saves to the existing app-specific output path using the existing extension and compression settings. A success result reports `source:"pixelcopy"` and `snapshotCaptureMode:"surface_pixelcopy"`.

Because the source is the video Surface rather than the Activity window, the pulled screenshots contain the final video only; the control panel and playback statistics overlay were absent.

# NV12 GL Snapshot

`mediacodec_nv12_gl` now returns:

`SNAPSHOT_REQUIRES_SURFACE_CAPTURE` -> Java PixelCopy -> saved image.

Final-binary device evidence:

- `actualDecoderName=hevc_mediacodec`
- `renderMode=mediacodec_nv12_gl`
- `renderer=nv12_gl`
- `snapshotCaptureMode=surface_pixelcopy`
- `nativeSnapshotSupported=false`
- `swsScaleEnabled=false`
- `renderFallbackUsed=false`
- `nv12GlFallbackFrameCount=0`
- `nv12EglContextCreateCount=1`
- `nv12EglSurfaceCreateCount=1`
- PixelCopy result: success, PNG, 1798x1019

No decoder/input/EGL restart is performed by a snapshot.

# Software YUV GL Snapshot

Hardware OFF + `software_yuv_gl` returns `SNAPSHOT_REQUIRES_SURFACE_CAPTURE` and uses PixelCopy. Device validation with Thermal Ironbow succeeded with:

- requested/actual renderer `yuv_gl`
- route `surface_pixelcopy`
- output 1798x1019 PNG
- final Ironbow colors present
- no Activity controls/statistics in the image
- no crash or EGL error in the capture run

# mediacodec_surface Snapshot

The requested `mediacodec_surface` mode is explicitly classified as `surface_pixelcopy`. On the current implementation/device, stats reported `requestedRenderer=direct_surface` and `renderer=rgba_nativewindow`; this does not change the screenshot contract. The final-binary request logged:

`snapshot route=surface_pixelcopy requestedRenderer=direct_surface actualRenderer=rgba_nativewindow`

PixelCopy saved a correct 1798x1019 Surface image. This mode already used its existing RGBA/sws playback path before the snapshot; the snapshot itself did not add a conversion or renderer fallback.

# OES Snapshot

The capability model returns `surface_pixelcopy` for `mediacodec_oes` because its successful architecture displays the final OES result on the same SurfaceView. No OES GPU readback was added.

Runtime screenshot validation could not run. On this device, OES Prepare failed with `SurfaceTexture creation failed`, attempted its pre-existing software fallback, then aborted in JNI because a pending Java exception reached `NewStringUTF`. This occurred before Start or Snapshot and is outside the Fix 5 screenshot routing. It is recorded as an existing remaining OES lifecycle/JNI issue, not hidden as a PixelCopy result.

# Thermal Snapshot

PixelCopy captures after the GL shader output reaches the final Surface, so the screenshot naturally contains the final palette/gamma/window result. It does not re-run the thermal algorithm on CPU or reconstruct color from NV12.

Device results:

- Hardware NV12 GL Thermal OFF: Original color output captured.
- Hardware NV12 GL White Hot: final grayscale/white-hot output captured.
- Hardware NV12 GL Ironbow: final pseudo-color output captured.
- Software YUV GL Ironbow configured before Create: first rendered path and screenshot were Ironbow.

# Snapshot Resolution Semantics

PixelCopy output dimensions are the current Surface callback dimensions. On the test device every validated PixelCopy image was 1798x1019. The decoded stream dynamically changed between 1280x720 and 192x256, but the screenshot retained Surface resolution and the renderer's current scaling/letterboxing.

Native RGBA snapshots retain source/native-frame dimensions because they encode `lastRgbaFrame_`; they are not resized to Surface dimensions.

# Surface Validity Handling

Before allocation/request, Java validates:

- Activity/player handle is still active;
- holder Surface exists and `isValid()`;
- `surfaceReady` is true;
- width and height are positive.

Every create/change/destroy/clear-reference transition increments `surfaceGeneration`. After PixelCopy completes, Java rechecks the active player handle, generation, Surface identity, readiness, and validity before writing the file. A detached or replaced Surface returns `SNAPSHOT_NO_SURFACE`; it does not reattach a Surface, recreate EGL, restart a decoder, or select another renderer.

# PixelCopy Error Handling

- `ERROR_SOURCE_NO_DATA` -> `SNAPSHOT_NO_FRAME`
- `ERROR_SOURCE_INVALID` or request exception -> `SNAPSHOT_NO_SURFACE`
- timeout/other PixelCopy result -> `SNAPSHOT_PIXELCOPY_ERROR`
- compress/open/write exception -> `SNAPSHOT_IO_ERROR`

A timed-out bitmap is deliberately not recycled while PixelCopy may still own it asynchronously. A completed non-timeout bitmap is recycled after result handling. PixelCopy success is not reported as save success unless bitmap compression and file output also succeed.

# Snapshot / Release Race

Native capability lookup/save remains protected by the frozen Fix 4 `PlayerOperationGuard`. Java passes the handle used for the native query into PixelCopy validation and checks it before request and after callback. Once Java invalidates/releases the handle, completion returns `SNAPSHOT_PLAYER_RELEASED` and never dereferences a `NativePlayer` from the PixelCopy callback.

The UI native worker is single-threaded, so Snapshot and the explicit Release button serialize. Background/surface stress was also exercised while screenshot work was in flight: ten foreground/background attempts produced no native crash, UAF, OOM, or EGL error. However, only eight touch requests were accepted and only one success was retained in the log before the Activity was backgrounded. A strict set of ten completed Snapshot+Release button pairs was not demonstrated; this contributes to the final runtime status NO.

# Snapshot / Surface Race

The captured Surface object and generation form a consistency token. If the callback observes a different generation/current Surface or an invalid Surface, the result is `SNAPSHOT_NO_SURFACE` and the file is not reported saved.

Ten rapid foreground/background Surface detach/reattach attempts produced no fatal signal, Java fatal exception, OOM, `EGL_BAD_*`, or renderer fallback. Because backgrounding suppresses/rotates result UI logs, this is safety evidence rather than ten successful capture results.

# Stats Semantics

The minimal stats additions/changes are:

- `snapshotCaptureMode`: `native_rgba`, `surface_pixelcopy`, or `unsupported`
- `nativeSnapshotSupported`: whether the native RGBA path is the active capability
- `snapshotSupported`: end-user capability, including Surface PixelCopy modes

Thus NV12 GL reports `snapshotSupported=true`, `nativeSnapshotSupported=false`, and `snapshotCaptureMode=surface_pixelcopy`. Existing `lastSnapshotTimeMs` remains native-save-only; no misleading PixelCopy counter was added.

# sws_scale Audit

Fix 5 adds no `sws_scale` call and does not enable `lastRgbaFrame_` on NV12 GL or YUV GL. On final NV12 GL runtime, `swsScaleEnabled=false`, `lastSwsScaleCostUs=-1`, and the existing scale counters stayed zero around snapshot capture.

The existing `SnapshotManager` JPEG encoder uses sws only after a native RGBA frame already exists. The existing `mediacodec_surface` implementation also currently uses its pre-existing RGBA playback path. Neither is caused by PixelCopy.

# Renderer Fallback Audit

PixelCopy is a capture route, not a renderer fallback. Snapshot code does not mutate render mode, `renderFallbackUsed`, fallback reason, or GL fallback counters. NV12 GL runtime remained `renderer=nv12_gl`, `renderFallbackUsed=false`, and `nv12GlFallbackFrameCount=0` before/after screenshots.

Repository search found an existing OES AGC downsample `glReadPixels`; Fix 5 adds no `glReadPixels`, FBO/PBO/compute readback, and Snapshot does not call that OES AGC readback.

# Fix 1 Regression

Thermal algorithms, shader code, palettes, AGC, gamma, and windowing were not modified. Hardware Original/White Hot/Ironbow and software YUV GL Ironbow screenshots visually matched the final displayed shader result. Software YUV GL Ironbow was selected before Create and appeared on the first playback path, preserving thermal config replay. The full prior AGC matrix was not replayed.

# Fix 2 Regression

No Surface/EGL renderer implementation was modified. The final NV12 run handled repeated 1280x720 <-> 192x256 changes, retained one EGL context and one EGL surface, and did not increment fallback counters. Foreground/background Surface stress produced no EGL access error or permanent fallback. True RTSP source switching was not available in this run; the source was local HTTP finite HEVC/TS.

# Fix 3 Regression

No Prepare/Start input lifecycle code was modified. Hardware NV12 GL, software YUV GL, and `mediacodec_surface` runs all showed normal Prepare followed by Start reuse (`realtimeStartInputReuseCount=1` on the initial session). Snapshot did not open input or decoder sessions. EOF reconnect counts increased because the local HTTP test asset is finite, not because of screenshot capture.

# Fix 4 Regression

The player registry, operation guard, closing state, active-operation drain, and release sequence were not redesigned. Snapshot JNI acquisition uses the existing operation guard and maps failed/stale acquisition to `SNAPSHOT_PLAYER_RELEASED`. Java callback code never retains or accesses a native player pointer. No Fix 4 lifetime failure was observed in the snapshot/background stress, but the exact ten completed Snapshot+Release button matrix was not fully logged.

# Build

`gradlew.bat :app:assembleDebug` passed after implementation and passed again after the final `mediacodec_surface` route correction. CMake built arm64-v8a and armeabi-v7a, Java compilation passed, and no dependency/Gradle/NDK/FFmpeg upgrade was made. `git diff --check` is part of the final pre-commit verification.

The repository-wide `gradlew.bat test` task was also attempted but failed in the pre-existing `:app:kaptDebugUnitTestKotlin` configuration: the stock `ExampleUnitTest.kt` `@Test` annotation was emitted as `error.NonExistentClass`, and `app/build.gradle` has no unit-test/JUnit dependency declaration. Fix 5 does not change dependencies, so this unrelated test configuration was not expanded in scope. This failure is not reported as a passing test.

# Runtime Verification

Device: connected Android arm64 device (`Bengal_for_arm64`). Test source: local HTTP finite HEVC/TS containing dynamic 1280x720 and 192x256 sections.

| Test | Result |
|---|---|
| NV12 GL Original -> PixelCopy | PASS |
| NV12 GL White Hot -> PixelCopy | PASS |
| NV12 GL Ironbow -> PixelCopy | PASS |
| Software YUV GL Ironbow -> PixelCopy | PASS |
| `mediacodec_surface` -> PixelCopy | PASS |
| Software RGBA native snapshot | NOT_EXECUTED; not selectable through current Demo policy, code-audited |
| OES final-Surface snapshot | NOT_EXECUTED; existing Prepare/JNI abort before snapshot |
| PixelCopy output is Surface-only, 1798x1019 | PASS |
| 20 successful NV12 GL snapshots | PASS: 16 accepted/succeeded in the rapid burst plus 4 additional successful requests |
| Snapshot/background Surface stress x10 | SAFETY PASS, result-completion coverage partial |
| Snapshot+Release completed pairs x10 | NOT_EXECUTED in full |
| Fatal/OOM/EGL error during successful snapshot tests | NOT_OBSERVED |
| True RTSP validation | NOT_EXECUTED |

Answers required by the task:

1. Pre-fix NV12 GL failed as `no video frame available` because it incorrectly queried the absent CPU RGBA cache.
2. Native returned numeric/unstructured `errorCode:-1`; it did not request Surface capture.
3. Java triggered PixelCopy only by matching an English unsupported-message substring.
4. Yes, that was string parsing; it has been removed from routing.
5. The stable handoff code is `SNAPSHOT_REQUIRES_SURFACE_CAPTURE`.
6. NV12 GL, software YUV GL, `mediacodec_surface`, and successful final-Surface OES route to PixelCopy.
7. No Snapshot `glReadPixels` or GPU readback was added.
8. `software_rgba` routes to `native_rgba` and still requires a valid `lastRgbaFrame_`.
9. No per-frame RGBA cache was added for GL modes.
10. Hardware NV12 GL Snapshot does not invoke/enable sws_scale.
11. Ironbow Snapshot captures the final pseudo-color Surface output.
12. PixelCopy dimensions are the current Surface dimensions (1798x1019 on this device).
13. Surface detach/change returns `SNAPSHOT_NO_SURFACE`; it does not repair/recreate playback resources.
14. Snapshot callback/release handling is pointer-safe through Java handle checks and the frozen JNI guard model.
15. Snapshot save failure is not reported as success even when PixelCopy itself succeeded.
16. Fixes 1-4 code paths remain unchanged; exercised regressions passed as described, while full OES/true-RTSP/exact release matrices remain incomplete.

# Remaining Issues

- OES cannot reach Snapshot on this device: Prepare reports `SurfaceTexture creation failed` and then aborts because JNI creates a string while a Java exception is pending. Fixing this requires OES/JNI exception-lifecycle work outside Fix 5.
- The current Demo option policy normalizes Hardware OFF to `software_yuv_gl`; direct `software_rgba` runtime snapshot verification was therefore unavailable without changing unrelated playback selection behavior.
- A strict ten completed Snapshot+Release UI-pair run was not completed; the implemented path is statically safe and background/detach stress did not crash.
- No reachable true RTSP stream was available; runtime used local HTTP HEVC/TS with EOF reconnect.
- PixelCopy keeps the existing explicit filename behavior, so repeated captures overwrite the selected path by current product design.
- The existing OES AGC code contains a downsample `glReadPixels`; it is unrelated to Snapshot and was not changed.

# Fix Runtime Verified YES/NO

**NO**.

Core Fix 5 behavior is implemented and verified for NV12 GL, software YUV GL, thermal output, and `mediacodec_surface`, including 20 successful NV12 GL captures. The hard gate cannot honestly be marked YES because OES crashed before Snapshot, `software_rgba` was not directly runnable through the current Demo policy, the exact ten completed Snapshot+Release pairs were not obtained, and true RTSP was unavailable.
