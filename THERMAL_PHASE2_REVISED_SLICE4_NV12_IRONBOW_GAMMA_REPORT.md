# Thermal Phase 2 Revised Slice 4 — NV12 Ironbow + Gamma Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h` | `renderNv12(..., colorRange, colorspace, int thermalMode, float gamma)`; added `ironbowProgram_` + LUT locations/texture, `whiteHotGammaLocation_`, `lastAppliedThermalMode_` + getter |
| `app/src/main/cpp/native/NativeNv12GlRenderer.cpp` | White Hot shader adds `uGamma`; added `kNv12IronbowFragmentShader` (Y → range → gamma → 256×1 LUT); `compileProgramLocked` builds original / white hot / ironbow programs + shared `createIronbowLut()` texture (unit 3); `renderNv12` selects the program per mode + sets gamma/LUT; records `lastAppliedThermalMode_`; release deletes ironbow program/LUT |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderNv12GlFrame` computes thermal mode (0/1/2) + gamma from `ThermalConfig` snapshot; reports the renderer's actually-applied mode; `thermalRenderMode` for NV12 GL now includes `ironbow`; removed obsolete ironbow-fallback flag |
| `app/src/main/cpp/native/NativePlayer.h` | Removed `nv12IronbowFallbackLogged_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Gamma enabled for `mediacodec_nv12_gl` (palette + Gamma; Window/AGC still disabled); NV12 playbackInfo shows gamma |

No Manual Window / AGC / Histogram / FBO / glReadPixels / PBO / new palette / OES changes / sws reintroduction / MediaCodec configure changes / dependency upgrades.

## Fixed processing order

```
NV12 Y
→ Range Normalize (uYMin/uYScale, full=identity / limited=16~235)
→ clamp 0..1
→ Gamma pow(intensity, max(uGamma, 0.001))
→ Palette (White Hot gray / Ironbow LUT)
```

Range is applied BEFORE Gamma (never reversed).

## Gamma implementation

Reuses `ThermalConfig.gamma` / `setThermalGamma` (range 0.5 ~ 2.0). Applied as `uGamma` to White Hot and Ironbow programs only. Gamma=1.0 is identity (matches the Slice 3 White Hot baseline). Runtime gamma change takes effect next frame — no decoder/EGL/Surface/LUT/shader rebuild.

## Gamma semantics

`pow(intensity, max(uGamma, 0.001))` — identical semantics to Phase 1 (`gamma > 1` brightens mid-tones). Not inverted.

## White Hot Gamma

White Hot upgraded to `Y → Range → Gamma → Gray` (`vec4(intensity, intensity, intensity, 1.0)`); range logic unchanged.

## Ironbow LUT source

Reuses the shared `ThermalPaletteLut::createIronbowLut()` — the exact Phase 1 Ironbow color table (one source, no second palette).

## LUT size/format

256 × 1, `GL_RGB`, `GL_UNSIGNED_BYTE`, `GL_LINEAR`, `GL_CLAMP_TO_EDGE`.

## LUT GL lifecycle

Created once in `compileProgramLocked` (renderer's own EGL context, own texture id — not shared with other renderers); never created per frame; deleted in `releaseGlLocked` (context made current first); re-created on context rebuild; palette switching never re-creates the LUT.

## Ironbow shader

```glsl
float rawY = texture2D(uTextureY, vTexCoord).r;
float intensity = clamp((rawY - uYMin) * uYScale, 0.0, 1.0);
intensity = pow(intensity, max(uGamma, 0.001));
vec3 color = texture2D(uPaletteTexture, vec2(intensity, 0.5)).rgb;
gl_FragColor = vec4(color, 1.0);
```

## Runtime palette switching

Original / White Hot / Ironbow selected per frame from the `ThermalConfig` snapshot; only the active program/uniforms change. No reconnect / re-prepare / MediaCodec / EGL / Surface / texture / LUT / shader rebuild.

## Shader fallback

Ironbow unavailable → White Hot; White Hot unavailable → Original NV12 GL; Original GL failure → existing renderer fallback. `thermalRenderMode` reports the mode actually applied (via `getLastAppliedThermalMode`), never claims ironbow when falling back. Low-frequency logs, no crash/black screen.

## UI capability

- `mediacodec_nv12_gl`: White Hot YES, Ironbow YES, Gamma YES, Window NO, AGC NO (Window/AGC disabled).
- `software_yuv_gl`: Phase 1 unchanged (all five).
- `software_rgba` / `mediacodec_surface`: Thermal NO.

## NV12 GL performance

All Thermal effects are pure GPU. Hot path: NV12 texture upload (`glTexSubImage2D`, unchanged Slice 2) → range → gamma → palette → draw → swap. No CPU palette/RGB conversion, no sws, no glReadPixels/FBO/histogram, no per-frame LUT creation or shader compile, no glFinish.

## Phase 1 regression

NO regression — `software_yuv_gl` White Hot/Ironbow/Gamma/Window/AGC untouched; shared `createIronbowLut()` data unchanged (only reused).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 5 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 4 gate): Original 30s → White Hot 30s (Gamma 0.5/1.0/1.5/2.0) → Ironbow 30s (Gamma 0.5/1.0/1.5/2.0) → Original↔White Hot↔Ironbow ≥20 rounds; confirm `actualDecoderName=hevc_mediacodec`, `usingHardwareDecoder=true`, `renderMode=mediacodec_nv12_gl`, `renderer=nv12_gl`, `thermalInputType=nv12_y`, `thermalRenderMode=ironbow`, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`, no black/flash/LUT banding, Gamma live, no palette-switch stutter.

## Ready for Revised Slice 5

YES (static/build; on-device Ironbow/Gamma verification is the gate)
