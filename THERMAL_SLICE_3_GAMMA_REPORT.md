# Thermal Slice 3 Gamma Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeYuvGlRenderer.h` | `renderI420(...)` gains `float gamma`; added `whiteHotGammaLocation_` (GLint, default -1) |
| `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` | White Hot fragment shader adds `uniform float uGamma` + `pow(gray, max(uGamma, 0.001))`; cached `uGamma` location at compile; sets `glUniform1f` before draw; release resets location |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderSoftwareYuvGlFrame` passes `thermal.gamma` (from the per-frame `ThermalConfig` snapshot) to `renderI420` |

No Java/JNI API changes (Slice 1 `FFmpegNative.setThermalGamma` reused; range 0.5 ~ 2.0 unchanged). No changes to RTSP / demux / decode / reconnect / recording / snapshot / MediaCodec / software_rgba fallback / surface lifecycle / state machine / OpenGL ES version / build tooling.

## Gamma shader implementation

`app/src/main/cpp/native/NativeYuvGlRenderer.cpp` — `kWhiteHotFragmentShader`:

```glsl
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
uniform float uGamma;
void main() {
    float gray = texture2D(yTexture, vTexCoord).r;
    gray = clamp(gray, 0.0, 1.0);
    gray = pow(gray, max(uGamma, 0.001));
    gl_FragColor = vec4(gray, gray, gray, 1.0);
}
```

Gamma `1.0` → `pow(gray, 1.0)` → identical to Slice 2 White Hot baseline. No other brightness processing added.

## Gamma source: ThermalConfig

```
UI/JNI thread : setThermalGamma(handle, ...) → ThermalConfig.gamma (mutex-protected)
Render thread : NativePlayer::renderSoftwareYuvGlFrame
                 → getThermalConfig() snapshot (includes gamma)
                 → renderI420(..., useWhiteHot, thermal.gamma)
                 → glUniform1f(whiteHotGammaLocation_, gamma)  (White Hot only)
                 → draw
```

No GL call from any setter; gamma updates only the uniform, taking effect on subsequent frames.

## Runtime update path

`setThermalGamma(handle, 0.7f / 1.0f / 1.4f / 2.0f)` during playback only changes the `uGamma` uniform. No re-Prepare, no RTSP reconnect, no decoder rebuild, no EGL context rebuild, no shader recompile.

## Behavior matrix

| Thermal state | Gamma effect |
| --- | --- |
| OFF | Normal YUV — Gamma inactive |
| ON + ORIGINAL | Normal YUV — Gamma inactive |
| ON + WHITE_HOT | Y → clamp → pow(gamma) → Gray |
| ON + IRONBOW | Slice 2 fallback/normal behavior (Ironbow not implemented) |

Gamma affects White Hot only; normal YUV rendering is never touched.

## Normal rendering preserved

YES

## Ironbow

NOT IMPLEMENTED

## AGC

NOT IMPLEMENTED

## Window/Range

NOT IMPLEMENTED

## Stats / playbackInfo

Existing `thermalGamma` stat reused (no new field). `playbackInfoTextView` already shows gamma (e.g. `Thermal ON | white_hot | AGC OFF | gamma 0.85 | render WHITE_HOT`). No new UI controls.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 3 intended files modified; no binaries, no dependency upgrades, no unrelated refactoring.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- Gamma 1.0 → identical to Slice 2 White Hot baseline
- Runtime gamma 0.6 / 1.0 / 1.4 / 2.0 → grayscale response changes; no crash / reconnect / playback stop
- Thermal OFF → Gamma (any value) must not affect normal YUV picture
- Decode FPS / Render FPS / Dropped / `yuvGlFallbackFrameCount` show no abnormal regression

## Ready for Slice 4

YES
