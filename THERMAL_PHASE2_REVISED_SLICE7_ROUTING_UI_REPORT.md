# Thermal Phase 2 Revised Slice 7 — Routing & UI Integration Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `isThermalSupported()` now an explicit allowlist (`software_yuv_gl` / `mediacodec_nv12_gl` / `mediacodec_oes`); rejects `software_rgba` and `mediacodec_surface`; updated the Thermal-blocked Toast/Log message (no longer "requires Software YUV GL" only) |
| `app/src/main/cpp/native/NativePlayer.h` | Added `renderFallbackReasonCode_` (0 none, 1 nv12_gl_failed, 2 yuv_gl_failed) |
| `app/src/main/cpp/native/NativePlayer.cpp` | Added `rendererNameFromRenderMode` / `renderFallbackReasonName` helpers; set the fallback reason at the NV12 GL and YUV GL → RGBA fallback points; `getStats` reports `requestedRenderer`, `renderFallbackUsed`, `renderFallbackReason`; `resetStats` clears it |

No new Thermal algorithm, no LUT/Gamma/Window/AGC changes, no OES changes, no MediaCodec configure changes, no sws/RGBA reintroduction, no dependency upgrades.

## Final capability matrix

| Render mode | Thermal | WhiteHot | Ironbow | Gamma | Window | AGC |
| --- | --- | --- | --- | --- | --- | --- |
| `software_yuv_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_nv12_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_oes` | YES (experimental) | — | — | — | — | — |
| `software_rgba` | NO | — | — | — | — | — |
| `mediacodec_surface` | NO | — | — | — | — | — |

Capability is enforced in one Java place (`isThermalSupported`); native/render paths consume the same `ThermalConfig`.

## Hardware Decode ON routing

`setHardwareDecode(true)` → `setHardwareRenderMode("mediacodec_nv12_gl")` (order preserved; `setHardwareDecode(false)` resets the software render mode, so the explicit render mode is applied afterwards). `mediacodec_surface` is reachable only via explicit `EXTRA_RENDER_MODE` override — the CPU-visible NV12 output is the expected input for the new main path.

## Hardware Decode OFF routing

`setHardwareDecode(false)` resets to `software_rgba`, then `applyDecodeModeOption` explicitly re-applies `setHardwareRenderMode("software_yuv_gl")` when software decode is selected — so a Thermal session never stays stuck in `software_rgba`.

## Create / Prepare / Start / Reconnect mode persistence

All configuration points (Create-new, Prepare, Start/reprepare) route through the single `applyDecodeModeOption` helper → the NV12 GL mode is applied consistently. Reconnect reopens input via `openInput`, which reads the already-set `playerOptions_.renderMode` (`setDecoderState` preserves it) — reconnect never rewrites the mode back to `mediacodec_surface`.

## Thermal guard changes

Old guard (`thermalSupported = renderMode == software_yuv_gl`) replaced by the explicit allowlist. `software_rgba` and real `mediacodec_surface` remain rejected. Opening Thermal under Hardware NV12 GL proceeds directly (no "requires Software YUV GL" block); enabling/disabling Thermal only updates `ThermalConfig` — no decoder/EGL/Surface rebuild.

## UI capability / AGC-manual behavior

Hardware ON + `mediacodec_nv12_gl`: Thermal switch, Palette (Original/White Hot/Ironbow), Gamma, Manual Window (when AGC OFF), and AGC all enabled. AGC ON disables the manual Black/White controls; AGC OFF restores them with prior values preserved. Palette/Gamma/Window/AGC state is never reset across Original↔White Hot↔Ironbow or session refresh.

## Decoder fallback semantics

`hardwareDecodeFallbackUsed=true` only when the decoder backend actually falls back (`hevc_mediacodec → software hevc`). Decoder failures are unrelated to renderer failures.

## Renderer fallback semantics

`renderFallbackUsed=true` + `renderFallbackReason` when a GL renderer (NV12 GL / YUV GL / OES) fell back to the RGBA/ANativeWindow renderer. Example: NV12 GL init/render failure → `decodeBackend="mediacodec"`, `frameOutputType="nv12_cpu"`, `renderer="rgba_nativewindow"`, `usingHardwareDecoder=true`, `hardwareDecodeFallbackUsed=false`, `renderFallbackUsed=true`. Decoder stays hardware; user ThermalConfig is preserved for when NV12 GL recovers.

## Stats behavior

- Normal Hardware NV12 GL: `decodeBackend=mediacodec`, `frameOutputType=nv12_cpu`, `renderer=nv12_gl`, `renderMode=mediacodec_nv12_gl`, `usingHardwareDecoder=true`, `hardwareDecodeFallbackUsed=false`, `renderFallbackUsed=false`, `requestedRenderer=nv12_gl`.
- NV12 GL → RGBA fallback: `renderer=rgba_nativewindow`, `renderFallbackUsed=true`, `hardwareDecodeFallbackUsed=false`.
- MediaCodec → software: `decodeBackend=software`, `usingHardwareDecoder=false`, `hardwareDecodeFallbackUsed=true`.

No fabricated state; legacy counters left for the final Stats freeze (Slice 8).

## Normal NV12 GL sws / RGBA constraint

`swsScaleEnabled=false`; `sws_scale` / RGBA CPU conversion / ANativeWindow RGBA render only re-enter when a real renderer fallback to RGBA occurs (and stats reflect `renderer=rgba_nativewindow` / `renderFallbackUsed=true`).

## Phase 1 regression

NO regression — `software_yuv_gl` Phase 1 Thermal (all five features) unchanged; the capability change is additive/explicit.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 12s
```

`git diff --check` clean. Only the 3 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 7 gate): Hardware ON → NV12 GL Original/White Hot/Ironbow; Gamma/Window/AGC runtime; ≥20 palette rounds; Hardware Decode ON→OFF→ON ≥5 (recover `hevc_mediacodec` + `mediacodec_nv12_gl`); Surface/background ≥5; reconnect (if safely triggerable) keeps the mode; confirm `renderMode=mediacodec_nv12_gl`, `renderer=nv12_gl`, `frameOutputType=nv12_cpu`, `nv12GlRenderedFrameCount` grows, fallback=0, `renderFallbackUsed=false`, `hardwareDecodeFallbackUsed=false`, `swsScaleEnabled=false`.

## Render fallback runtime verification

NOT_EXECUTED — no safe test hook to force NV12 GL failure without a production-affecting hook; the fallback semantics are statically verified (render-fallback path never sets `hardwareDecodeFallbackUsed`).

## Ready for Revised Slice 8

YES (static/build; on-device routing verification is the gate)
