# Thermal Slice 5 Window/Range Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/ThermalConfig.h/.cpp` | Added `isValidThermalWindow(blackPoint, whitePoint)` |
| `app/src/main/cpp/native/NativeYuvGlRenderer.h` | Added `ThermalRenderParams` (yMin/yScale/blackPoint/whitePoint/gamma); replaced per-uniform GLint fields with `ThermalUniformSet` (yMin/yScale/blackPoint/whitePoint/gamma) for White Hot and Ironbow; `renderI420(...)` now takes `const ThermalRenderParams&` |
| `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` | White Hot + Ironbow shaders gain `uYMin`/`uYScale`/`uBlackPoint`/`uWhitePoint`/`uGamma`; added `fetchThermalUniformSet`/`setThermalUniforms` helpers; uniform validation + LUT binding in `compileProgramLocked`; release reset in `releaseGlLocked` |
| `app/src/main/cpp/native/NativePlayer.h` | Added `setThermalWindow(float, float)` |
| `app/src/main/cpp/native/NativePlayer.cpp` | Implemented `setThermalWindow` (mutex-protected, validated); `renderSoftwareYuvGlFrame` computes `ThermalRenderParams` from `frame->color_range` + `ThermalConfig` and passes to `renderI420`; `getStats()` outputs `thermalBlackPoint` / `thermalWhitePoint` |
| `app/src/main/cpp/native-ffmpeg-jni.cpp` | Added `nativeSetThermalWindow` + registration `(JFF)Ljava/lang/String;` |
| `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java` | Added `native setThermalWindow(long, float, float)` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `playbackInfoTextView` thermal diagnostics now include Window + Range |

No changes to RTSP / demux / decode / reconnect / recording / snapshot / MediaCodec / software_rgba fallback / surface lifecycle / state machine / GLES version / build tooling.

## Range normalization behavior

Computed per frame in `NativePlayer::renderSoftwareYuvGlFrame` from `AVFrame::color_range`:

- `AVCOL_RANGE_MPEG` (limited): `yMin = 16/255`, `yScale = 255/219` → shader `clamp((gray - yMin) * yScale, 0, 1)` expands 16~235 to 0~1
- `AVCOL_RANGE_JPEG` (full) / `AVCOL_RANGE_UNSPECIFIED` / unknown: `yMin = 0`, `yScale = 1` → identity

The renderer never depends on FFmpeg `AVColorRange`; it only receives `ThermalRenderParams`.

## unspecified range policy

UNSPECIFIED/UNKNOWN → identity (`yMin=0, yScale=1`). No guessing of limited; conservative compatibility preserved.

## Window API

```java
FFmpegNative.setThermalWindow(long handle, float blackPoint, float whitePoint)
```

Single atomic setter (no two partial setters → no invalid intermediate state). JNI → `NativePlayer::setThermalWindow` under `thermalConfigMutex_`.

Validation (`isValidThermalWindow`): finite, `0.0 <= blackPoint`, `whitePoint <= 1.0`, `blackPoint < whitePoint`, min span `>= 0.01`. Invalid → failure JSON, previous config kept, no crash. No GL calls in any setter.

## Shader processing order

Both White Hot and Ironbow use identical order:

```
raw Y
→ (gray - uYMin) * uYScale          (range normalize)
→ clamp((gray - uBlackPoint) / max(uWhitePoint - uBlackPoint, 0.001), 0, 1)  (window)
→ pow(gray, max(uGamma, 0.001))     (gamma)
→ vec3(gray)  [White Hot]  /  LUT  [Ironbow]
```

Default `blackPoint=0.0` / `whitePoint=1.0` → window is identity (no dynamic-range compression). Runtime `setThermalWindow` takes effect on the next frame — no re-Prepare / RTSP reconnect / decoder / EGL / shader / LUT rebuild.

## White Hot preserved

YES (Slice 3 Gamma + Slice 2 behavior kept; now with range + window before gamma)

## Ironbow preserved

YES (Slice 4 LUT kept; now with range + window before gamma → LUT)

## Gamma preserved

YES (same `uGamma`; range/window applied before gamma per required order)

## Normal rendering preserved

YES (Normal YUV shader untouched; Window/Gamma/Range never applied when Thermal OFF or palette ORIGINAL)

## AGC implemented

NO (`agcEnabled` remains a stored config flag only; no automatic window/black/white computation)

## Stats

`getPlayerStats()` now outputs:

```json
"thermalBlackPoint": 0.15,
"thermalWhitePoint": 0.85
```

Existing `frameColorRange` (limited/full/unspecified) retained — no duplicate `thermalRangeMode` field added. `playbackInfoTextView` shows e.g.:

```
Thermal ON | ironbow | AGC OFF | gamma 0.90 | render IRONBOW
Window 0.15 - 0.85 | Range limited
```

No new Thermal UI controls.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 9 intended files modified; no binaries, no dependency/Gradle/AGP/NDK/SDK upgrades, no unrelated refactoring.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- A: black 0.0 / white 1.0 → window identity
- B: limited range → thermal shader expands 16~235 to 0~1
- C: full range → keeps 0~1
- D: unspecified → identity, no guessing
- E: black 0.20 / white 0.80 → visible contrast change
- F: window affects both White Hot and Ironbow
- G: Normal YUV unaffected
- H/I: Gamma and Ironbow LUT keep working
- Runtime: `setThermalWindow(0.0/1.0)`, `(0.10/0.90)`, `(0.20/0.80)`; watch Decode/Render FPS, Dropped, `yuvGlFallbackFrameCount`, GL errors

## Ready for Slice 6

YES
