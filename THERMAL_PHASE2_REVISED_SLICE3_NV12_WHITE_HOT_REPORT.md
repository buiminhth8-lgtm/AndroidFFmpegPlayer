# Thermal Phase 2 Revised Slice 3 — NV12 White Hot Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h` | `renderNv12(..., colorRange, colorspace, bool whiteHot)`; added `whiteHotProgram_` + its range/attrib locations |
| `app/src/main/cpp/native/NativeNv12GlRenderer.cpp` | Added `kNv12WhiteHotFragmentShader` (Y → range normalize → gray); `compileProgramLocked` builds original + white hot programs (shared vertex shader, deleted only after both link); `renderNv12` selects the program per frame and sets range uniforms on the active program; white hot failure → original NV12; release deletes both programs |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderNv12GlFrame` reads a per-frame `ThermalConfig` snapshot and selects White Hot (IRONBOW → white-hot fallback with one-time log); tracks `nv12ThermalRenderedCount_`/`lastNv12ThermalRenderMode_`; `getStats` reports `thermalInputType="nv12_y"`, `thermalRenderMode=normal|white_hot`, `nv12ThermalRenderedFrameCount`; `resetStats` clears them |
| `app/src/main/cpp/native/NativePlayer.h` | Added `nv12ThermalRenderedCount_`, `lastNv12ThermalRenderMode_`, `nv12IronbowFallbackLogged_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `isThermalSupported` allows `mediacodec_nv12_gl` (thermal unavailable only for `mediacodec_surface`); NV12 GL capability: palette enabled, Gamma/Window/AGC disabled; playbackInfo shows NV12 thermal status + `Input: NV12 Y` |

No Gamma / Ironbow / Window / AGC / Histogram / FBO / glReadPixels / PBO / new palette / OES changes / sws reintroduction / MediaCodec configure changes / dependency upgrades.

## NV12 Thermal input

White Hot uses only the NV12 Y plane (`frame->data[0]`). No NV12→RGB→luminance, no UV-derived intensity. Original keeps Y+UV→RGB.

## White Hot shader

```glsl
float rawY = texture2D(uTextureY, vTexCoord).r;
float intensity = clamp((rawY - uYMin) * uYScale, 0.0, 1.0);
gl_FragColor = vec4(intensity, intensity, intensity, 1.0);
```

Separate program from the Original NV12 program (no shader unification this slice). Y texture still uploaded (UV upload kept unchanged — no premature UV-upload optimization).

## Full-range handling

`AVCOL_RANGE_JPEG`/full/unspecified → `uYMin=0`, `uYScale=1` (identity). Preserves the current full-range stream brightness.

## Limited-range handling

`AVCOL_RANGE_MPEG`/limited → `uYMin=16/255`, `uYScale=255/219` (16~235 normalize), then clamp 0..1. Unknown range → identity (project policy).

## Processing order

```
NV12 Y
→ Range Normalize (uYMin/uYScale)
→ clamp 0..1
→ White Hot (gray)
```

## Thermal mode switching

- Thermal disabled → Original NV12
- Thermal enabled + ORIGINAL → Original NV12
- Thermal enabled + WHITE_HOT → NV12 Y White Hot
- Thermal enabled + IRONBOW → safe fallback to White Hot (one-time log; stats report `white_hot`, never `ironbow`)

Runtime Original ↔ White Hot via the per-frame thread-safe `ThermalConfig` snapshot: only the active program changes — no reconnect / re-prepare / MediaCodec / EGL / Surface / texture / shader rebuild.

## Shader fallback

White Hot compile/link/uniform failure → Original NV12 GL program (not sws/RGBA); if Original NV12 also fails, the existing renderer fallback applies. Low-frequency log; no per-frame error spam.

## UI capability

- `mediacodec_nv12_gl`: Thermal enable YES, Original YES, White Hot YES; Ironbow/Gamma/Manual Window/AGC NO (controls disabled).
- `software_yuv_gl`: Phase 1 capabilities unchanged.
- `mediacodec_surface`/`software_rgba`: Thermal unsupported.

## NV12 GL performance

White Hot is pure GPU (Y texture upload → white hot shader → draw → swap). No CPU grayscale buffer, no sws, no RGBA conversion, no glReadPixels/FBO/histogram, no per-frame allocation, no per-frame shader compile/texture recreate. The Y/UV `glTexSubImage2D` upload architecture from Slice 2 is unchanged.

## Phase 1 regression

NO regression — `software_yuv_gl` White Hot/Ironbow/Gamma/Window/AGC untouched; shared `ThermalConfig`/helpers unchanged.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
```

`git diff --check` clean. Only the 5 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 3 gate): Original ≥30s → White Hot ≥30s → Original↔White Hot ≥20 switches; confirm `actualDecoderName=hevc_mediacodec`, `usingHardwareDecoder=true`, `renderMode=mediacodec_nv12_gl`, `renderer=nv12_gl`, `thermalInputType=nv12_y`, `thermalRenderMode=white_hot`, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`, Decode≈Render FPS, no black/flash, hot→white / cold→black.

## Limited-range runtime verification

NOT_EXECUTED (current stream is full range; no limited-range test source).

## Ready for Revised Slice 4

YES (static/build; on-device NV12 White Hot verification is the gate)
