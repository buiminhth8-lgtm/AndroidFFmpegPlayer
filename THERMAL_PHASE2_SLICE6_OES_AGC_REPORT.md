# Thermal Phase 2 Slice 6 OES AGC Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeOesRenderer.h` | AGC resources (FBO/texture/program), readback buffer, `agcValid_`/`agcBlackPoint_`/`agcWhitePoint_`/counters; `renderOesFrame(..., agcEnabled, runAgc)`; `resetAgc`/getters |
| `app/src/main/cpp/native/NativeOesRenderer.cpp` | `kAgcDownsampleFragmentShader` (OES RGB → luminance); `ensureAgcGlLocked` (64×64 RGBA FBO+texture, `GL_FRAMEBUFFER_COMPLETE` check, downsample program); `runAgcAnalysis` (bind FBO → 64×64 viewport → luminance downsample → `glReadPixels` → restore framebuffer); `updateAgcFromReadback` (256-bin histogram → P2/P98 → clamp → validate → smoothing); effective window feeds White Hot/Ironbow uniforms; release deletes AGC resources |
| `app/src/main/cpp/native/NativePlayer.cpp` | `kOesAgcUpdateIntervalFrames=5`; `renderOesPendingFrameIfReady` counts thermal frames and triggers AGC every 5th; `setThermalAgcEnabled` resets OES AGC; `getStats` reports OES-aware `thermalAgc*` + `oesAgcUpdateCount`/`oesAgcReadbackErrorCount`; `resetStats` clears OES AGC counter/state |
| `app/src/main/cpp/native/NativePlayer.h` | Added `oesAgcFrameCounter_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | AGC re-enabled for `mediacodec_oes`; OES window line shows effective AGC window + `AGC ON/OFF`; removed dead `isOesMode()` |

No PBO / compute shader / full-resolution readback / new AGC algorithm / new palette / GLSurfaceView / pseudo-color recording / P010.

## AGC input path

OES RGB (SurfaceTexture) → luminance (dot 0.299/0.587/0.114) → downsample analysis. Analysis runs on the pre-window/pre-gamma/pre-palette luminance (never post-Ironbow/Gamma color).

## Downsample resolution

64 × 64 RGBA FBO texture (`GL_RGBA`, `GL_UNSIGNED_BYTE`, `GL_LINEAR`, `GL_CLAMP_TO_EDGE`); `glCheckFramebufferStatus == GL_FRAMEBUFFER_COMPLETE` verified; failure → OES AGC disabled (manual window), playback continues, no black screen.

## Update interval

`AGC_UPDATE_INTERVAL_FRAMES = 5` — only on valid OES thermal frames (thermal enabled + AGC enabled + WHITE_HOT/IRONBOW + mediacodec_oes). Other frames reuse the last valid AGC window. Thermal OFF / ORIGINAL never run AGC.

## Readback format

`glReadPixels(0,0,64,64, GL_RGBA, GL_UNSIGNED_BYTE, reusableBuffer)` (16384 bytes, allocated once). R channel read as luminance. FBO + viewport restored after readback so the final frame is unaffected. No `glFinish`.

## Histogram bins

256 (`uint32_t histogram[256]`), 4096 samples.

## Percentiles

P2 / P98 (`0.02` / `0.98`) from the cumulative histogram; `black = lowBin/255`, `white = highBin/255` in luminance 0..1. No 16~235 / AVColorRange conversion (OES input is already RGB luminance).

## Smoothing alpha

`0.15`; first valid result initializes directly, subsequent results blend (`old*0.85 + detected*0.15`).

## Invalid-range protection

Rejected (previous valid AGC window kept) when: no valid samples, percentile invalid, near-pure color, `white-black < 0.05`, or FBO/readback failure. Before any valid AGC result → manual window fallback. No NaN / Inf / division by zero / permanent bad window.

## Manual Window preservation

Manual `blackPoint`/`whitePoint` are never overwritten. Effective window = `agcEnabled && agcValid ? AGC : manual`. AGC OFF → immediately manual; re-enable resets OES AGC state for quick re-initialization.

## GL resource lifecycle

AGC FBO/texture/program created in the valid EGL context (`ensureAgcGlLocked` from `ensureGlLocked`, best-effort — failure only disables AGC); deleted in `releaseGlLocked` (no double delete); rebuilt on context recreate. Surface/EGL recreate never leaks the AGC FBO.

## Phase 1 AGC regression

NO regression — software CPU Y-plane AGC untouched (separate path/state).

## mediacodec_surface regression

NO regression — direct-Surface path untouched.

## Performance observation

NOT_TESTED (no device run). Design: 64×64 readback, at most every 5 frames, fixed buffer, no glFinish, no per-frame FBO/texture/program creation, no per-frame logs. If on-device validation shows periodic stutter, it will be recorded and reported (PBO/compute-shader changes are out of scope).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 5 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance (Slice 6 gate): OES White Hot + AGC and Ironbow + AGC ≥60s each; dark→bright→dark scene; AGC ON/OFF ≥20 times; confirm manual window preserved, no AGC flicker / periodic stutter, OES counters + `oesAgcUpdateCount` growing, no readback errors, then `mediacodec_surface` + `software_yuv_gl` Phase 1 regression.

## Ready for Slice 7

YES (static/build; on-device AGC readback performance verification is the gate)
