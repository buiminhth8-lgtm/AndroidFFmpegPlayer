# Thermal Phase 2 Revised Slice 5 — NV12 Manual Window Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h` | `renderNv12(..., thermalMode, gamma, blackPoint, whitePoint)`; added `uBlackPoint`/`uWindowInvRange` uniform locations for White Hot and Ironbow |
| `app/src/main/cpp/native/NativeNv12GlRenderer.cpp` | White Hot + Ironbow shaders apply `(intensity - uBlackPoint) * uWindowInvRange` before gamma; CPU computes `windowInvRange = 1 / max(white - black, 0.001)` (no shader divide-by-zero); `compileProgramLocked` fetches/validates the new uniforms; `renderNv12` sets them; release resets |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderNv12GlFrame` passes `thermal.blackPoint`/`thermal.whitePoint` from the `ThermalConfig` snapshot; `getStats` reports `thermalWindowApplied` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Black/White controls re-enabled for `mediacodec_nv12_gl`; AGC stays disabled; NV12 playbackInfo shows Window + `AGC: N/A` |

No NV12 AGC / Histogram / P2/P98 / automatic window / FBO / glReadPixels / PBO / new palette / OES changes / sws reintroduction / MediaCodec configure changes / dependency upgrades.

## Window input domain

`blackPoint`/`whitePoint` operate in the range-normalized intensity `[0,1]` domain (NOT raw 8-bit Y). Default `0.0 / 1.0` → identity (Slice 5 default visuals identical to Slice 4).

## Processing order

```
raw NV12 Y
→ Range Normalize (uYMin/uYScale: full=identity, limited=16~235)
→ clamp 0..1
→ Manual Window ((intensity - uBlackPoint) * uWindowInvRange, clamp 0..1)
→ Gamma pow(intensity, max(uGamma, 0.001))
→ Palette (White Hot gray / Ironbow LUT)
```

Window is applied AFTER Range and BEFORE Gamma — never reversed.

## Window formula

Shader: `intensity = clamp((intensity - uBlackPoint) * uWindowInvRange, 0.0, 1.0);`
CPU: `uWindowInvRange = 1.0 / max(whitePoint - blackPoint, 0.001f)` — avoids shader division by zero and handles degenerate ranges safely.

## Existing ThermalConfig reused

YES — `ThermalConfig.blackPoint`/`whitePoint` (no new fields).

## Existing setThermalWindow reused

YES — no new API; the renderer only consumes the shared configuration.

## Paired update / thread safety

`blackPoint`/`whitePoint` are read together from the same `ThermalConfig` snapshot each frame (guarded by `thermalConfigMutex_` via `getThermalConfig()`), so no transient `black >= white` is ever rendered. Existing validation in `setThermalWindow` (finite, `0 <= black < white <= 1`, min span) is reused — no second rule set.

## Identity 0..1 behavior

`blackPoint=0`, `whitePoint=1` → `windowInvRange=1` → window is identity (matches Slice 4 White Hot/Ironbow baseline).

## White Hot Window

White Hot: `Y → Range → Window → Gamma → Gray` (window uniforms active).

## Ironbow Window

Ironbow: `Y → Range → Window → Gamma → LUT` (window uniforms active).

## Gamma ordering

Window is applied BEFORE Gamma (spec-correct).

## UI capability

- `mediacodec_nv12_gl`: White Hot YES, Ironbow YES, Gamma YES, Window YES, AGC NO (AGC disabled).
- `software_yuv_gl`: Phase 1 unchanged (all five).
- `software_rgba` / `mediacodec_surface`: Thermal NO.

Window values are never reset when toggling Original/White Hot/Ironbow (they live in `ThermalConfig`).

## NV12 AGC implemented

NO — the NV12 GL path always uses the manual window; AGC is safely ignored (no crash, no wrong window, stats/UI never claim NV12 AGC).

## Performance

Manual Window is pure GPU (subtract/multiply/clamp). No CPU grayscale frame, no sws, no histogram/P2/P98, no glReadPixels/FBO/PBO, no per-frame allocation/LUT/shader/texture recreation. Hot path unchanged (glTexSubImage2D, stride-aware, reusable staging, existing EGL lifecycle).

## Phase 1 regression

NO regression — `software_yuv_gl` Window/White Hot/Ironbow/Gamma/AGC untouched; `setThermalWindow` semantics unchanged.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
```

`git diff --check` clean. Only the 4 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 5 gate): White Hot + Ironbow with Window 0.00/1.00, 0.10/0.90, 0.20/0.80, 0.40/0.60; Gamma 0.5/1.0/1.5 at Window 0.20/0.80; ≥20 rapid window changes (no decoder/EGL/Surface/LUT rebuild, no black/flash/stutter); identity check 0.0/1.0 + gamma 1.0 matches Slice 4; confirm `renderMode=mediacodec_nv12_gl`, `renderer=nv12_gl`, `thermalWindowApplied=true`, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`.

## Ready for Revised Slice 6

YES (static/build; on-device Manual Window verification is the gate)
