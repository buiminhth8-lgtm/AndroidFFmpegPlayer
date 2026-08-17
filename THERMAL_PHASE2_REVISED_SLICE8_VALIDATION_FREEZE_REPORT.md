# Thermal Phase 2 Revised Slice 8 — Validation & Phase 2 Freeze Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativePlayer.cpp` | Minimal stats fix on the NV12 GL success path: explicit sws disabled sentinel (`lastSwsScaleCostUs_ = -1`) and `lastRenderCostUs_` reflects the NV12 GL total render cost |

No new Thermal functionality, no algorithm changes, no OES changes, no MediaCodec configure changes, no dependency upgrades.

## Final Architecture

Normal hardware path:

```
RTSP HEVC
→ hevc_mediacodec
→ CPU NV12
→ NativeNv12GlRenderer
→ OpenGL
→ SurfaceView
```

Thermal hardware path:

```
RTSP HEVC
→ hevc_mediacodec
→ CPU NV12 Y
→ Range
→ Manual/AGC Window
→ Gamma
→ White Hot / Ironbow
→ OpenGL
→ SurfaceView
```

- `sws_scale` in normal NV12 GL path: NO
- RGBA CPU conversion in normal NV12 GL path: NO
- GPU readback for AGC: NO (CPU NV12 Y-plane analysis)

## Final capability matrix

| Render mode | Thermal | WhiteHot | Ironbow | Gamma | Window | AGC |
| --- | --- | --- | --- | --- | --- | --- |
| `software_yuv_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_nv12_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_oes` | YES (experimental) | — | — | — | — | — |
| `software_rgba` | NO | — | — | — | — | — |
| `mediacodec_surface` | NO | — | — | — | — | — |

## Final Stats semantics

- `decodeBackend=mediacodec`, `frameOutputType=nv12_cpu`, `renderer=nv12_gl`, `renderMode=mediacodec_nv12_gl`, `usingHardwareDecoder=true`, `hardwareDecodeFallbackUsed=false`, `renderFallbackUsed=false`, `swsScaleEnabled=false`.
- `requestedRenderer`/`renderFallbackReason` present; `thermalWindowApplied`, `thermalInputType=nv12_y`, NV12 AGC counters (`nv12AgcUpdateCount`/`nv12AgcInvalidCount`) present.

## Decoder counter semantics

`hardwareDecodedFrameCount` = frames output by the MediaCodec hardware decoder (CPU NV12 included); `softwareDecodedFrameCount` = frames output by a software FFmpeg decoder only. Normal `hevc_mediacodec → NV12 GL` → `hardwareDecodedFrameCount` grows, `softwareDecodedFrameCount = 0`. No CPU-visible-AVFrame-based misjudgment.

## Renderer counter semantics

`nv12GlRenderedFrameCount` grows in the NV12 GL path; the RGBA renderer counter is not incremented on NV12 GL success. Legacy `hardwareRenderedFrameCount` / `softwareRenderedFrameCount` are retained (informational; the software counter covers software-decoder GL/RGBA renders) — marked legacy; not restructured to avoid JSON compatibility risk.

## Decoder fallback semantics

`hardwareDecodeFallbackUsed=true`, `usingHardwareDecoder=false`, `decodeBackend="software"` only when the decoder backend actually falls back (`hevc_mediacodec → software hevc`). Independent of renderer.

## Renderer fallback semantics

NV12 GL → RGBA fallback: `usingHardwareDecoder=true`, `hardwareDecodeFallbackUsed=false`, `renderFallbackUsed=true`, `renderer=rgba_nativewindow`, `renderFallbackReason="nv12_gl render failed"`. Never reported as a decoder fallback.

## sws state

Normal NV12 GL: `swsScaleEnabled=false`, `lastSwsScaleCostUs=-1` (explicit disabled sentinel, now set), `avgSwsScaleCostUs=0`, `maxSwsScaleCostUs=0`. sws timing only accumulates on the real RGBA fallback path.

## NV12 GL timing

`lastNv12GlRenderCostUs` / `avgNv12GlRenderCostUs` / `maxNv12GlRenderCostUs` and upload variants cover texture upload → draw → `eglSwapBuffers`. Generic `lastRenderCostUs` now reflects the NV12 GL render cost. No `glFinish`, no per-frame logging.

## Baseline A/B/C comparison

- A (legacy: MediaCodec → NV12 → sws → RGBA → ANativeWindow): HISTORICAL_DATA_ONLY — no device-run dataset captured in this repo; not fabricated.
- B (NV12 GL Original): no device data (NOT_TESTED).
- C (NV12 GL Ironbow + AGC): no device data (NOT_TESTED).

## 192x256 long-run

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. Expected: `hevc_mediacodec`, `mediacodec_nv12_gl`, `renderer=nv12_gl`, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`, Decode≈Render FPS, no crash/deadlock/black screen/GL error/memory growth/dropped surge.

## Palette switch x50

NOT_EXECUTED (static: per-frame program selection, no decoder/EGL/Surface/LUT rebuild).

## Gamma / Window / AGC stress

NOT_EXECUTED (static: uniform/config-snapshot only, no rebuilds; AGC OFF restores manual window).

## Hardware ON/OFF/ON

NOT_EXECUTED (static: `applyDecodeModeOption` centralizes routing; ON → `mediacodec_nv12_gl`, OFF → `software_yuv_gl`; ThermalConfig preserved).

## Surface recreate

NOT_EXECUTED (static: EGLSurface rebind keeps context/program/textures; context-lost rebuild path present).

## Reconnect

NOT_EXECUTED (static: `openInput` reuses `playerOptions_.renderMode`, so reconnect preserves `mediacodec_nv12_gl`).

## HD runtime

NOT_EXECUTED (no 1280x720 test source).

## Padded-stride runtime

NOT_EXECUTED (no padded-stride source). Code-checked: row-by-row copy, reusable staging, no per-frame malloc, no out-of-bounds.

## Color validation

PARTIAL — no color (non-grayscale) source; BT.601/BT.709 matrix and full/limited paths are implemented and code-reviewed, but color correctness requires a color NV12 stream.

## AGC final

Input NV12 Y plane; 4x4 sampling; every 5 frames max; 256 bins; P2/P98; alpha 0.15; GPU readback NO. `nv12AgcUpdateCount` expected ≈ Thermal AGC frames / 5; histogram never per-frame.

## Phase 1 regression

PASS (static) — `software_yuv_gl` White Hot/Ironbow/Gamma/Window/AGC untouched; shared `computeAgcWindow`/constants unchanged; routing/capability changes are additive/explicit.

## OES status

PRESERVED / EXPERIMENTAL (future zero-copy path; not deleted, not enabled as the Phase 2 default, no OES Thermal/AGC continuation). Cleanup deferred to a separate decision after Phase 2 freeze.

## Snapshot status

UNSUPPORTED for `mediacodec_nv12_gl` (`snapshotSupported=false`, `hasLastFrame=false`) — known capability gap. No glReadPixels / GPU readback / RGBA CPU cache implemented.

## Remaining known issues

- `softwareRenderedFrameCount`/`hardwareRenderedFrameCount` legacy naming (informational only; a final Stats freeze could revisit).
- NV12 GL native snapshot unsupported (PixelCopy gap).
- BT.709/full/limited color matrix not device-validated (no color stream).
- EGL created on prepare thread and rendered on the playback thread (multi-thread EGL pattern, device-validated in prior slices).
- OES path remains experimental.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only `NativePlayer.cpp` changed (3 additive lines).

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed.

## Phase 2 Freeze

YES (static/build validation complete; the core pipeline, Thermal path, routing, capability matrix, and fallback semantics are code-frozen).

## Remaining validation items

HD runtime, Padded-stride runtime, Color stream validation, RTSP Reconnect runtime, Palette×50 / ON-OFF-ON / Surface-recreate / long-run device passes — all objectively unavailable in this environment (NOT_EXECUTED, listed as residual, not fabricated).
