# Thermal Slice 0 Baseline Report

## Scope

Establish and confirm the software YUV GL render pipeline as the stable development baseline for future Thermal slices (White Hot / Gamma / Ironbow / Window / AGC).

No thermal processing was implemented. Video color algorithm and YUV → RGB shader were NOT modified.

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativePlayer.h` | Added diagnostics fields `lastFrameYStride_`, `lastFrameColorRange_` |
| `app/src/main/cpp/native/NativePlayer.cpp` | Added `colorRangeName()` helper; record Y stride / color range in `processDecodedVideoFrame`; low-frequency diagnostic log (frame 1 + every 300); `getStats()` outputs `frameColorRange` / `frameColorRangeValue` / `frameYStride` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Software decode render mode switched to `software_yuv_gl`; `playbackInfoTextView` extended with resolution / Y stride / color range / yuv-gl rendered / yuv-gl fallback |

No changes to `NativeYuvGlRenderer.*` shaders, `FFmpegNative.java`, `PlayerOptions.*`, JNI, recorder, snapshot, or surface lifecycle.

## Software Decode Render Mode

Before:
`software_rgba`

After:
`software_yuv_gl`

Call order preserved (required because `setHardwareDecode(false)` resets the software render mode to `software_rgba`):

```java
FFmpegNative.setHardwareDecode(handle, hardwareDecode);
FFmpegNative.setHardwareRenderMode(handle, renderMode);  // software_yuv_gl / mediacodec_surface
```

## Existing Pipeline Confirmed

```
FFmpeg software decode
→ AVFrame (YUV420P / YUVJ420P)
→ NativePlayer::renderSoftwareYuvGlFrame
→ NativeYuvGlRenderer::renderI420 (Y/U/V 3 planes)
→ EGL / OpenGL ES 2.0
→ Surface
```

Confirmed existing:
- `RenderMode::SOFTWARE_YUV_GL` exists (`PlayerOptions.h`).
- `NativePlayer::renderSoftwareYuvGlFrame` calls `NativeYuvGlRenderer::renderI420(...)`.
- `renderI420` uploads Y/U/V 3 planes with the original YUV → RGB fragment shader.
- Fallback to `software_rgba` preserved: unsupported frame format or GL render failure falls through to the RGBA/sws_scale path (`renderFrame`).
- OpenGL ES 2.0 / `EGL_CONTEXT_CLIENT_VERSION = 2` unchanged.

## Supported Pixel Formats

- `AV_PIX_FMT_YUV420P`
- `AV_PIX_FMT_YUVJ420P`

No new pixel formats added (NV12/NV21/GRAY8/P010/10bit are out of scope for Slice 0).

## Added Diagnostics

- `frameFormat` (existing)
- resolution (`videoWidth` / `videoHeight`, existing)
- Y stride (`frameYStride`, ADDED)
- color range (`frameColorRange`, `frameColorRangeValue`, ADDED; helper maps `AVCOL_RANGE_MPEG→limited`, `AVCOL_RANGE_JPEG→full`, `AVCOL_RANGE_UNSPECIFIED→unspecified`, else `unknown`)
- render mode (`renderMode`, existing)
- YUV GL rendered count (`yuvGlRenderedFrameCount`, existing)
- YUV GL fallback count (`yuvGlFallbackFrameCount`, existing)

`getPlayerStats()` now also returns:
```json
"frameColorRange":"limited",
"frameColorRangeValue":1,
"frameYStride":1920
```

Low-frequency native diagnostic log (frame 1, then every 300 frames, single line for adb grep):
```text
software frame diagnostic width=640 height=512 format=yuv420p yStride=640 colorRange=limited renderMode=software_yuv_gl
```

`playbackInfoTextView` now shows (1 s refresh, via `getPlayerStats`):
```text
state=PLAYING | software_yuv_gl | h264
640x512 | yuv420p | Y stride=640 | range=limited
decode 30.0 fps | render 29.9 fps | dropped 0
bitrate 820 kbps | transfer 840 KB/s
yuv-gl rendered=3521 fallback=0 | packets N | frames N
reconnect attempt=0 ...
```

## Shader Modified

NO

## Thermal Processing Added

NO

## Ironbow Added

NO

## White Hot Added

NO

## Gamma Added

NO

## AGC Added

NO

## Build Result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
```

(`arm64-v8a`, `armeabi-v7a`, `x86_64` CMake + javac passed; no Gradle/AGP/NDK/compileSdk/FFmpeg upgrades)

## Runtime Verification

Device `34aff35a` is attached but no APK was installed and no unknown test flow was started (per Slice 0 rules).

Status: NOT_EXECUTED (true device RTSP stream validation pending)

Runtime acceptance to check on device:
- `getPlayerStats().renderMode == "software_yuv_gl"`
- `yuvGlRenderedFrameCount` grows continuously
- `yuvGlFallbackFrameCount` stays 0 for normal yuv420p/yuvj420p streams
- `playbackInfoTextView` shows resolution / Y stride / color range

## Known Limitations

- `software_yuv_gl` does not support native RGBA snapshot (existing behavior; the demo uses Java PixelCopy fallback). Functional parity with `software_rgba` is intentionally NOT addressed in Slice 0.
- Only `YUV420P` / `YUVJ420P` are GL-rendered; other formats fall back to `software_rgba`.
- Color range is observed only; no range normalization (16~235 → 0~255) performed — deferred to a later Window slice.
- `FOLLOW_UP_REQUIRED`: none observed in code review; if `yuvGlFallbackFrameCount` grows on device for unsupported formats (NV12/NV21/P010/10-bit/hardware frame), those extensions are explicitly deferred to later slices.

## Ready For Slice 1

YES
