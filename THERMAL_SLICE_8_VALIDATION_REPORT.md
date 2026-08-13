# Thermal Slice 8 Validation Report

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 4s
```

`git diff --check` clean. Only `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` changed (one minimal defect fix). No new features, no dependency/Gradle/AGP/SDK/NDK/FFmpeg upgrades.

## Runtime Environment

- device: `34aff35a` attached (adb). No RTSP stream URL available and no unknown test flow started → runtime on-device verification **NOT_EXECUTED**. Static validation + build performed instead.
- Android version / GPU / GLES: not collected (no app installed, no run).
- stream/codec/resolution: N/A.

## Test Matrix

Static-only (no device execution). Verified via code reading + build:

| Area | Static result |
| --- | --- |
| Normal | YUV→RGB path intact; thermal never applied when disabled / ORIGINAL |
| White Hot | Y → range → manual/AGC window → gamma → gray; no U/V read |
| Ironbow | Y → range → window → gamma → 256×1 LUT → RGB; LUT created once at context init |
| Gamma | runtime uniform update only; affects White Hot/Ironbow, never Normal |
| Manual Window | validated setter; identity at 0.0/1.0; runtime update via uniform |
| AGC | P2/P98 every 5 frames, 4×4 subsample, alpha 0.15, invalid protection, manual window preserved |
| Palette switching | changes only per-frame shader selection; no Prepare/reconnect/decoder/EGL/LUT rebuild |
| Surface/EGL lifecycle | release + recreate paths checked; programs/texture freed and reset; re-init on context rebuild |
| Concurrency | ThermalConfig mutex; AGC atomics; no GL in setters; GL only on render thread; release joins thread before renderer release |
| Stats | all Slice 0–6 thermal fields present and mutex/atomic-guarded |

## Normal Baseline

Normal YUV fragment shader unmodified across Slice 0–7. `thermalMode == 0` never computes AGC nor sets thermal uniforms; `software_rgba` fallback path preserved. PASS (static).

## White Hot

`renderSoftwareYuvGlFrame` → `renderI420(thermalMode=1, params)` → `whiteHotProgram_` (Y only). Gamma/Window/Range uniforms set via `setThermalUniforms`. PASS (static).

## Ironbow

`renderI420(thermalMode=2, params)` → `ironbowProgram_` + LUT texture (unit 3). LUT generated once in `compileProgramLocked` via `createIronbowLut()`, never per frame. Re-bound per frame (not re-created). If `ironbowProgram_`/`ironbowTexture_` unavailable → falls back to white hot, then normal. PASS (static).

## Gamma

`uGamma` uniform; range 0.5–2.0 validated in `setThermalGamma`; gamma=1.0 identity. Runtime update only changes the uniform (next frame). Normal unaffected. PASS (static).

## Manual Window

`setThermalWindow` validates finite, 0≤black<white≤1, min span 0.01. Defaults 0.0/1.0 identity. Runtime update changes uniforms only. PASS (static).

## AGC

`computeAgcWindow` — stack `uint32_t histogram[256]`, 4×4 subsample, respects `linesize[0]`, P2/P98 cumulative, converted to normalized thermal range matching Slice 5, invalid results rejected (min span 0.05), alpha 0.15 smoothing, first detection initializes directly, update every 5 thermal frames. Manual Window never overwritten; AGC OFF restores manual. PASS (static).

## Palette Switching

White Hot ↔ Ironbow changes only the next frame's program. No re-Prepare, no RTSP reconnect, no decoder/EGL/shader/LUT rebuild. PASS (static).

## Surface/EGL Lifecycle

`releaseGlLocked` deletes programs/textures and resets to 0 (safe against double-delete), `eglDestroySurface/Context`, `eglTerminate`. `compileProgramLocked` regenerates on next context creation. `release()` joins the playback thread before releasing renderers → no use-after-free. PASS (static).

## Performance Comparison

Not measured (no device run). Static hot-path audit:

- No per-frame LUT creation, no per-frame shader compile, no per-frame large heap allocation for AGC (stack 256-bin array, no Y-plane copy, subsampled).
- AGC histogram every 5 frames, not every frame.
- No per-frame log spam — all render-path logs guarded by frame 1 + every 100/300.
- No Java Bitmap/OpenCV per-pixel thermal processing.
- No setter-triggered GL resource recreation.

## GL/Fallback Diagnostics

- `software_rgba` fallback intact; `yuvGlFallbackFrameCount` observable.
- Unsupported formats (non-YUV420P/YUVJ420P) fall back to RGBA, never treated as CPU Y planes (hardware frames use `renderMediaCodecFrame`).
- Thermal shaders read Y only (White Hot) / Y + LUT (Ironbow); never U/V.

## Issues Found

### Issue 1 — vertex shader deleted before Ironbow program linked (fixed)

- **symptom**: latent resource-ordering defect — `glDeleteShader(vertexShader)` ran before the Ironbow program linked with the same vertex shader; relied on GL's "delete-when-detached" semantics to keep the object alive. Fragile across drivers; could cause a failed Ironbow link on some implementations.
- **root cause**: `compileProgramLocked` deleted the shared vertex shader right after the White Hot link, but the Ironbow link still referenced it.
- **fix**: moved `glDeleteShader(vertexShader)` to after all three programs (normal / white hot / ironbow) are linked.
- **verification**: `BUILD SUCCESSFUL`; diff limited to this one fix.

## Remaining Limitations

- MediaCodec/OES hardware-decode thermal rendering: not implemented (software `software_yuv_gl` thermal path only).
- Pseudo-color (thermal) recording: not implemented — recording always remuxes original packets (unchanged behavior).
- Unsupported pixel formats (NV12/NV21/P010/10-bit/GRAY8): not supported by the GL thermal path; fall back to `software_rgba`. Out of scope for Phase 1.
- `thermalRenderMode` stat reflects the requested config; in the rare case Ironbow shader init fails (fallback to White Hot), the stat reports `ironbow` while actual rendering is `white_hot`. Cosmetic, error-path only; not fixed to avoid scope creep.
- Range normalization is limited/full only; no 10-bit range handling.
- On-device runtime verification not executed (no stream/device run).

## Final Result

READY
