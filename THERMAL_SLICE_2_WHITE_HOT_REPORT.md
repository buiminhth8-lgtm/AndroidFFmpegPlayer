# Thermal Slice 2 White Hot Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeYuvGlRenderer.h` | Renamed `program_` → `normalProgram_`; added `whiteHotProgram_`; `renderI420(...)` gains `bool whiteHot` parameter |
| `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` | Added `kWhiteHotFragmentShader`; added `linkProgram` helper; compiles both programs with shared vertex shader; `renderI420` selects program by mode; lifecycle (`releaseGlLocked`/`ensureGlLocked`) handles both programs |
| `app/src/main/cpp/native/NativePlayer.h` | Added `whiteHotRenderedFrameCount_`, `lastThermalRenderMode_`, `ironbowNotImplementedLogged_` |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderSoftwareYuvGlFrame` reads `ThermalConfig` snapshot, selects White Hot path, tracks counters; `getStats()` outputs `thermalRenderMode` / `whiteHotRenderedFrameCount` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `playbackInfoTextView` thermal line now includes effective render mode (`render NORMAL` / `render WHITE_HOT`) |

No Java/JNI API changes (Slice 1 API reused). No changes to RTSP / demux / decode / reconnect / recording / snapshot / MediaCodec / software_rgba fallback / surface lifecycle / state machine.

## White Hot shader location

`app/src/main/cpp/native/NativeYuvGlRenderer.cpp` — `kWhiteHotFragmentShader`:

```glsl
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
void main() {
    float y = texture2D(yTexture, vTexCoord).r;
    gl_FragColor = vec4(y, y, y, 1.0);
}
```

Original YUV → RGB `kFragmentShader` preserved unchanged.

## ThermalConfig → Renderer path

```
UI/JNI thread: only writes ThermalConfig (Slice 1 setters, thermalConfigMutex_)
Render thread : NativePlayer::renderSoftwareYuvGlFrame (per frame)
                 → getThermalConfig() snapshot
                 → useWhiteHot = enabled && palette == WHITE_HOT
                 → NativeYuvGlRenderer::renderI420(..., whiteHot)
                 → glUseProgram(whiteHotProgram_ | normalProgram_)
```

No cross-thread `glUseProgram()` — the render thread owns and executes all GL calls.

## Behavior matrix

| Thermal state | Result |
| --- | --- |
| `enabled=false` | Normal YUV → RGB |
| `enabled=true`, palette ORIGINAL | Normal YUV → RGB |
| `enabled=true`, palette WHITE_HOT | Grayscale from Y only |
| `enabled=true`, palette IRONBOW | Normal YUV → RGB (one-time log: "Ironbow rendering is not implemented in Slice 2; using normal rendering") |

WHITE_HOT reuses the existing Y texture; U/V textures are still uploaded (no single-Y upload optimization — deferred to a performance slice).

## Fallback behavior

- If White Hot shader compile/link fails → `whiteHotProgram_` stays 0, clear error logged, `renderI420` falls back to `normalProgram_`. No crash, no black screen; normal playback keeps working.
- If a frame format is unsupported by GL → existing fallback to `software_rgba` (unchanged).

## Normal rendering preserved

YES

## Ironbow implemented

NO

## Gamma processing implemented

NO

## AGC implemented

NO

## Stats

`getPlayerStats()` now outputs:

```json
"thermalRenderMode": "white_hot",   // "normal" | "white_hot"
"whiteHotRenderedFrameCount": 1234
```

`playbackInfoTextView` thermal line:
```
Thermal ON | white_hot | AGC OFF | gamma 1.00 | render WHITE_HOT
```

No new Thermal UI controls (formal UI is a later slice).

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. No Gradle/AGP/NDK/SDK/FFmpeg/dependency/OpenGL ES version changes.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- A: Thermal OFF → identical to Slice 0/1 baseline picture
- B: Thermal ON + WHITE_HOT → pure grayscale (dark Y → black, bright Y → white; U/V must not affect output)
- C: toggle OFF ↔ WHITE_HOT during playback → no re-prepare / no RTSP reconnect / no decoder rebuild / no crash
- D: Decode FPS / Render FPS / Dropped / `yuvGlFallbackFrameCount` show no abnormal fallback or regression

## Ready for Slice 3

YES
