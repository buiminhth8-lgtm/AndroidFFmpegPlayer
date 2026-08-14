# Thermal Phase 2 Revised Slice 0 — NV12 GL Baseline Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/PlayerOptions.h` | Added `RenderMode::MEDIACODEC_NV12_GL` |
| `app/src/main/cpp/native/PlayerOptions.cpp` | `renderModeName`/`parseRenderMode` support `mediacodec_nv12_gl`; `setPlayerOptionValue` rejects it as NOT_READY |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h/.cpp` | NEW — boundary class for the future NV12→OpenGL renderer (interface only, no GL): `setSurface`/`clearSurface`/`release`/`supportsFrameFormat`/`isReady`; `supportsFrameFormat` always false, `isReady` false |
| `app/src/main/cpp/CMakeLists.txt` | Added `native/NativeNv12GlRenderer.cpp` |
| `app/src/main/cpp/native/NativePlayer.h` | Member `nv12GlRenderer_`; tracking atomics `lastFrameOutputType_` (1 yuv420p_cpu / 2 nv12_cpu / 3 direct_surface / 4 external_oes) and `lastRendererType_` (1 rgba_nativewindow / 2 yuv_gl / 3 nv12_gl / 4 oes_gl) |
| `app/src/main/cpp/native/NativePlayer.cpp` | `setSurface`/`clearSurface`/`release` wire the NV12 renderer boundary; `setHardwareRenderMode` rejects `mediacodec_nv12_gl` as NOT_READY; `renderFrame` tracks actual frame output type and splits decoded counters by decoder backend; render paths record `lastRendererType_`; `getStats()` emits `decodeBackend` / `frameOutputType` / `renderer`, makes `renderInputType` reflect the actual frame output, and `swsScaleEnabled` reflect the actual renderer |

No NV12 GL rendering, no shader, no texture upload, no sws bypass, no Thermal / ThermalConfig / Thermal UI changes, no OES changes, no GLSurfaceView, no dependency upgrades.

## Current actual MediaCodec pipeline (baseline)

```
MediaCodec hardware decode (hevc_mediacodec, ByteBuffer output)
→ CPU-visible NV12 AVFrame
→ sws_scale → RGBA
→ ANativeWindow (VideoRenderer::renderRgba)
```

The decoder backend is `hevc_mediacodec` (hardware); frames are CPU NV12 (not `AV_PIX_FMT_MEDIACODEC`, so `renderMediaCodecFrame` is not called); the render path is the software sws_scale RGBA path.

## MEDIACODEC_NV12_GL mode status

Defined and parsed (`mediacodec_nv12_gl`) but **NOT_READY**: `setHardwareRenderMode("mediacodec_nv12_gl")` returns an explicit unsupported/not-ready error without changing the current effective render mode; `setPlayerOptionValue` rejects it the same way. No auto-selection.

## NV12 renderer boundary

`NativeNv12GlRenderer` establishes the interface and integration point (lifecycle wired into `NativePlayer`), with `supportsFrameFormat`/`isReady` returning false. No GL/EGL code.

## NV12 input contract (Slice 1)

- Only `AV_PIX_FMT_NV12`.
- `frame->data[0]` = Y plane (`linesize[0]`, width × height); `frame->data[1]` = interleaved UV (`linesize[1]`, width/2 × height/2).
- Preserve width/height/linesize[0]/linesize[1]/color_range/colorspace.
- Never assume `linesize == width`; never treat NV21 / YUV420P / P010 as NV12. No conversion in this slice.

## decodeBackend semantics

`decodeBackend = "mediacodec" | "software"` — derived from the actual decoder backend (`usingHardwareDecoder`), independent of frame output type. `hevc_mediacodec` producing CPU NV12 → `decodeBackend="mediacodec"`, `frameOutputType="nv12_cpu"` (hardware backend + CPU output are NOT contradictory).

## frameOutputType semantics

`frameOutputType = "yuv420p_cpu" | "nv12_cpu" | "direct_surface" | "external_oes" | "unknown"` — tracked at runtime from the actual decoded frame format:
- `AV_PIX_FMT_MEDIACODEC` → `direct_surface` (surface-mode presentation)
- `AV_PIX_FMT_NV12` → `nv12_cpu`
- `AV_PIX_FMT_YUV420P`/`YUVJ420P` → `yuv420p_cpu`
- OES path → `external_oes` (via OES renderer; frame output tracked as direct-surface/`oes` as applicable)

## renderer semantics

`renderer = "rgba_nativewindow" | "yuv_gl" | "nv12_gl" | "oes_gl" | "unknown"` — the renderer that actually drew the last frame (tracked in each render path). Current NV12 path → `rgba_nativewindow`. Future `mediacodec_nv12_gl` → `nv12_gl`.

## Current Stats inconsistencies (now corrected at low risk)

- `renderInputType` previously derived from the requested `renderMode` (reported `direct_surface` for `mediacodec_surface` even when frames were CPU NV12). Now derived from the actual `frameOutputType` (`nv12_cpu` for the real path). `renderMode` remains the requested-mode field (unchanged); the new `frameOutputType`/`renderer` are the runtime source of truth.
- `swsScaleEnabled` previously `false` for the ByteBuffer NV12 case (`mediacodec_surface` + `usingHardwareDecoder=true`), even though sws_scale IS used. Now reflects the actual renderer (`rgba_nativewindow` → true). `snapshotSupported` follows (RGBA cache is populated in the sws path).

## Decoder fallback vs render fallback

- Decoder fallback (`hardwareDecodeFallbackUsed=true`): `hevc_mediacodec → software hevc` (backend change).
- Render fallback (future): MediaCodec NV12 → NV12 GL unavailable → RGBA renderer. Never counted as decoder fallback.
- No fallback flow was changed; the distinction is documented and supported by the new `decodeBackend`/`frameOutputType`/`renderer` fields.

## OES status

`mediacodec_oes` + `NativeOesRenderer` fully preserved, marked experimental / future zero-copy path. Not deleted, not refactored, not auto-selected. The revised Phase 2 main path is NV12 GL.

## Current runtime behavior preserved

YES — visual output, decoder, render path, sws usage, fallback, Thermal, OES are unchanged. The only behavioral-adjacent change is the decode counter split (hardware-decoded NV12 now counted under `hardwareDecodedFrameCount`; the Java decode-FPS uses the sum, so it is unaffected) and the diagnostics fields.

## Counter semantics investigation (hardwareDecoded vs softwareDecoded)

Why `softwareDecodedFrameCount` grew while `hevc_mediacodec` ran: `renderFrame` unconditionally incremented `softwareDecodedFrameCount` for every non-`AV_PIX_FMT_MEDIACODEC` frame — including CPU NV12 frames produced by the hardware decoder (ByteBuffer mode never reaches `renderMediaCodecFrame`, which is the only place `hardwareDecodedFrameCount` was incremented). Minimal safe fix applied: the counter now splits by the actual decoder backend (`usingHardwareDecoder`), so hardware-decoded NV12 counts as hardware. The sum is unchanged. Rendered counters were left as-is (documented; a final Stats pass may revisit).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 2s
```

`git diff --check` clean. Only the intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no usable RTSP stream run / no APK installed. On-device pass should confirm: `decodeBackend="mediacodec"`, `frameOutputType="nv12_cpu"`, `renderer="rgba_nativewindow"`, `renderInputType="nv12_cpu"`, `swsScaleEnabled=true` while playing the hevc_mediacodec NV12 stream, and unchanged picture.

## Ready for Slice 1

YES
