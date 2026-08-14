# Thermal Phase 2 Slice 3 OES White Hot Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeOesRenderer.h` | `renderOesFrame(env, w, h, bool whiteHot)`; added `whiteHotProgram_` + its ST-matrix/attrib locations |
| `app/src/main/cpp/native/NativeOesRenderer.cpp` | Added `kOesWhiteHotFragmentShader` (RGB → luminance → gray); `compileProgramLocked` compiles original + white hot programs with shared vertex shader; `renderOesFrame` selects the program per frame; white hot failure falls back to original OES; release deletes both programs |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderOesPendingFrameIfReady` reads a `ThermalConfig` snapshot each frame and selects White Hot (IRONBOW → white-hot fallback with one-time log); tracks `oesThermalRenderedCount` / `lastOesThermalRenderMode`; `getStats()` emits OES-aware `thermalRenderMode`, `thermalInputType=oes_luminance`, `oesThermalRenderedCount`; `resetStats` clears OES thermal state |
| `app/src/main/cpp/native/NativePlayer.h` | Added `oesThermalRenderedCount_`, `lastOesThermalRenderMode_`, `oesIronbowFallbackLogged_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `isThermalSupported()` now allows `mediacodec_oes` (partially supported); playbackInfo shows OES thermal status + `Input: OES LUMINANCE` |

No Gamma / Ironbow / Window / AGC / Histogram / FBO / glReadPixels / compute shader / new palette / GLSurfaceView / pseudo-color recording.

## OES luminance formula

```glsl
vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
float intensity = dot(rgb, vec3(0.299, 0.587, 0.114));
intensity = clamp(intensity, 0.0, 1.0);
gl_FragColor = vec4(intensity, intensity, intensity, 1.0);
```

## thermalIntensity semantics

OES input is post-decode RGB from MediaCodec/Surface. `thermalIntensity ∈ [0,1]` is defined directly as RGB luminance. No CPU frame access, no AVFrame Y plane assumption.

## Y range normalization applied

NO — no `uYMin`/`uYScale` / limited↔full 16~235 normalization on the OES path (avoids double range conversion after MediaCodec/Surface color conversion).

## Original OES preserved

YES — `program_` (original OES shader) untouched; `whiteHotProgram_` is a separate program sharing the same vertex shader and `uSTMatrix` transform.

## White Hot implementation

Pure GPU fragment shader on the EGL owner thread. Mode selection per frame:

- Thermal OFF / ORIGINAL → OES Original
- Thermal ON + WHITE_HOT → OES White Hot (luminance)
- Thermal ON + IRONBOW → safe fallback to White Hot (one-time log: "OES Ironbow is not implemented in Slice 3; using white hot fallback")

White hot program compile/link/uniform failure → falls back to the original OES program (playback continues, low-frequency error log, no black screen).

## Runtime switching behavior

`renderOesPendingFrameIfReady` reads a thread-safe `ThermalConfig` snapshot per frame and only changes the selected program — no re-prepare, no MediaCodec recreate, no SurfaceTexture recreate, no EGL recreate, no shader recompile. OFF ↔ WHITE_HOT is instantaneous.

## Transform matrix preserved

Both programs use the same vertex shader (`vTexCoord = (uSTMatrix * aTexCoord).xy`) with the Slice 2 aspect-fit viewport and `getTransformMatrix` handling — identical orientation / cropping between Original and White Hot.

## Shader lifecycle

Both programs compiled once at context creation (`compileProgramLocked`); deleted in `releaseGlLocked` under a current context; re-created on context rebuild. No per-frame compile/link.

## mediacodec_surface regression

NO regression — direct-Surface path untouched.

## Phase 1 regression

NO regression — `software_yuv_gl` Phase 1 shaders/AGC/LUT untouched.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
```

`git diff --check` clean. Only the 5 intended files modified; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance (Slice 3 gate): OES Original 30s → White Hot 30s → Original 30s; ≥20 Original↔White Hot rapid switches; confirm grayscale White Hot, orientation/crop identical to Original, hardware decoder intact, OES counters growing, no black/flash, no GL errors; then confirm `mediacodec_surface` and `software_yuv_gl` Phase 1 unaffected.

## Ready for Slice 4

YES (static/build; on-device verification is the gate)
