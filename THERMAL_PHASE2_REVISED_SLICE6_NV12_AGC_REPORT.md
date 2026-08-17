# Thermal Phase 2 Revised Slice 6 — NV12 AGC Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativePlayer.h` | NV12 AGC state: `nv12AgcValid_`, `nv12AgcBlackPoint_`, `nv12AgcWhitePoint_`, `nv12AgcUpdateCount_`, `nv12AgcInvalidCount_`, `nv12AgcFrameCounter_`, `nv12AgcLastFrameWidth_/Height_` |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderNv12GlFrame` runs the shared Phase 1 `computeAgcWindow` on the NV12 Y plane (data[0]) every 5 thermal frames, applies smoothing/validation, and feeds the AGC effective window; `setThermalAgcEnabled` and `resetStats` reset NV12 AGC state; `getStats` reports NV12 AGC fields |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | AGC re-enabled for `mediacodec_nv12_gl`; manual Black/White disabled while AGC ON; NV12 window line shows `AGC ON/OFF` |

No OES AGC, no FBO, no glReadPixels, no PBO, no compute shader, no new AGC algorithm, no new palette, no OES changes, no sws regression, no MediaCodec configure changes, no dependency upgrades.

## AGC input

CPU-visible NV12 Y plane (`frame->data[0]`, `linesize[0]`, width, height, `color_range`). No NV12→RGB→luminance, no GPU readback, no analysis of post-Gamma/Ironbow color.

## Shared Phase 1 helper usage

Reuses the existing Phase 1 `computeAgcWindow(data, stride, width, height, colorRange)` helper directly (same signature) — 4×4 sampling (`kAgcPixelStep`/`kAgcRowStep`), 256-bin fixed histogram, P2/P98, range normalize to the thermal intensity domain, min span 0.05. No algorithm rewrite; the same code path serves `software_yuv_gl` and `mediacodec_nv12_gl` Y planes.

## Sampling step

4×4 (`sampleStepX = 4`, `sampleStepY = 4`), stride-aware (rows read via `data + y * stride`, never assumes `stride == width`), no full-plane copy, no out-of-bounds.

## Update interval

`AGC_UPDATE_INTERVAL_FRAMES = 5` (shared constant). Only when thermal enabled + AGC enabled + WHITE_HOT/IRONBOW + NV12 GL. Other frames reuse the last valid AGC window. Thermal OFF / ORIGINAL / AGC OFF never build a histogram.

## Histogram bins

256 (`uint32_t histogram[256]`), direct 8-bit Y counts. No per-update heap allocation / std::map / Bitmap/OpenCV / JNI pixel processing.

## P2/P98

Cumulative histogram percentiles `0.02` / `0.98` → `detectedBlack` / `detectedWhite` (same as Phase 1).

## Color-range normalization

Percentile raw bins converted to the shader-consistent normalized intensity domain: full/JPEG → `bin/255`; limited/MPEG → `((bin/255) - 16/255) / (219/255)`, clamped 0..1. Matches the `Y → Range → Window` shader semantics — no double range normalization.

## Min dynamic range

`white - black >= 0.05` (`kAgcMinSpan`, shared). On no samples / invalid percentile / NaN/Inf / pure-color / insufficient range: keep the previous valid AGC window; if none exists, fall back to the manual window. No permanent black/white lock.

## Smoothing alpha

`0.15` (`kAgcSmoothingAlpha`, shared). First valid result initializes directly; subsequent results blend (`old*0.85 + detected*0.15`). No adaptive alpha.

## Manual/Effective Window separation

Two independent states:
- Manual: `ThermalConfig.blackPoint` / `whitePoint` (never overwritten by AGC).
- AGC effective: `nv12AgcBlackPoint_` / `nv12AgcWhitePoint_` / `nv12AgcValid_`.

`AGC ON + valid → AGC window; AGC ON + invalid → manual; AGC OFF → manual`. AGC OFF instantly restores the manual window; re-enable resets AGC validity for a fresh scene.

## Reset behavior

NV12 AGC validity/counter reset on: new playback session / new stream (`resetStats`), `setThermalAgcEnabled` (OFF→ON), and resolution change (detected per frame in `renderNv12GlFrame`). Surface recreate does not clear user manual config.

## Final pipeline

- AGC OFF: `NV12 Y → Range → Manual Window → Gamma → Palette`
- AGC ON:  `NV12 Y → Range → AGC Effective Window → Gamma → Palette`
- Original: no Window/Gamma/AGC Thermal pipeline.

## UI capability

- `mediacodec_nv12_gl`: White Hot / Ironbow / Gamma / Window / AGC — all YES (AGC re-enabled this slice).
- `software_yuv_gl`: Phase 1 unchanged (all five).
- `software_rgba` / `mediacodec_surface`: Thermal NO.

AGC ON disables the manual Black/White controls; AGC OFF re-enables them with prior values preserved.

## Performance

AGC CPU cost is minimal: once per 5 frames, 4×4 sampling, fixed 256-bin histogram, no full-plane copy, no glReadPixels/FBO/PBO/compute shader, no per-frame heap allocation/logging. The `NV12 → glTexSubImage2D → GPU render` hot path is unchanged.

## Phase 1 regression

NO regression — `software_yuv_gl` CPU Y AGC and all Thermal effects unchanged; the shared `computeAgcWindow`/constants are untouched.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 11s
```

`git diff --check` clean. Only the 3 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 6 gate): White Hot + AGC ON ≥60s, Ironbow + AGC ON ≥60s, dark→bright→dark scene, AGC ON/OFF ≥20 times, manual 0.20/0.80 preserved across AGC toggle, `nv12AgcUpdateCount ≈ thermal frames / 5`, no GPU readback, no periodic stutter, `renderMode=mediacodec_nv12_gl`, `renderer=nv12_gl`, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`.

## Ready for Revised Slice 7

YES (static/build; on-device NV12 AGC verification is the gate)
