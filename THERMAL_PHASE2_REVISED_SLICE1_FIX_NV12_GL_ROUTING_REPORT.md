# Thermal Phase 2 Revised Slice 1 Fix — NV12 GL Routing Report

## Root cause

Hardware Decode configuration repeatedly selected `mediacodec_surface` instead of `mediacodec_nv12_gl`. The single routing helper `MediaPlayerActivity.applyDecodeModeOption(...)` mapped `hardwareDecode == true` to `"mediacodec_surface"`; Create / Prepare / Start(reprepare) all replayed that helper, so every configuration pass re-applied `mediacodec_surface`, keeping the actual `renderMode` at `mediacodec_surface` and the picture on the sws/RGBA/ANativeWindow path (`nv12GlRenderedFrameCount=0`).

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `applyDecodeModeOption` now maps Hardware Decode ON → `"mediacodec_nv12_gl"` (Revised Phase 2 main path); `"mediacodec_surface"` remains available only via explicit `EXTRA_RENDER_MODE`; thermal-blocked Toast pending-mode string updated; `isThermalSupported` pending branch never allows thermal for `mediacodec_nv12_gl` |
| `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java` | Javadoc hardware example updated to `setHardwareRenderMode("mediacodec_nv12_gl")` |

No native changes, no shader changes, no Thermal changes, no OES changes, no MediaCodec configure / Surface binding changes, no dependency upgrades.

## Previous hardware routing

```
Hardware Decode ON
→ applyDecodeModeOption → setHardwareDecode(true) + setHardwareRenderMode("mediacodec_surface")
→ actual renderMode = mediacodec_surface
→ NV12 → sws_scale → RGBA → ANativeWindow (nv12GlRenderedFrameCount=0)
```

## New hardware routing

```
Hardware Decode ON
→ applyDecodeModeOption → setHardwareDecode(true) + setHardwareRenderMode("mediacodec_nv12_gl")
→ actual renderMode = mediacodec_nv12_gl
→ NV12 → NativeNv12GlRenderer → OpenGL → SurfaceView (nv12GlRenderedFrameCount grows)
```

Call order preserved: `setHardwareDecode(true)` first, then `setHardwareRenderMode(...)` (because `setHardwareDecode(false)` resets the software render mode; for `true` it does not reset, and the explicit render mode is applied afterwards).

## Create / Prepare / Start mode persistence

All three (Create-new, Prepare, Start/reprepare) call the single `applyDecodeModeOption` helper, so the NV12 GL routing is applied consistently every time — no Create=NV12 GL / Prepare=mediacodec_surface or Prepare=NV12 GL / reconnect=mediacodec_surface overwrite.

## Native mode activation

`setHardwareRenderMode("mediacodec_nv12_gl")` is accepted and kept by `RenderMode::MEDIACODEC_NV12_GL` (Slice 1 implementation; no residual NOT_READY gate). No fake success, no auto-revert to `mediacodec_surface`/`software_rgba`. `hardwareModeRequested` includes `MEDIACODEC_NV12_GL`; the Surface pre-check includes it; `setDecoderState` preserves it.

## Frame dispatch result

With `renderMode=mediacodec_nv12_gl`, `usingHardwareDecoder=true`, `frame->format=AV_PIX_FMT_NV12`, and `nv12GlRenderer_.isReady()` true, `renderFrame` dispatches to `NativeNv12GlRenderer::renderNv12`. On success the frame ends the render path — no `sws_scale`, no RGBA conversion, no `ANativeWindow` render. On failure, the frame falls through to the existing sws/RGBA path with `nv12GlFallbackFrameCount++` and a low-frequency log (render fallback, not decoder fallback).

## nv12GlRenderedFrameCount / nv12GlFallbackFrameCount

NOT_TESTED (no device run). Expected: `nv12GlRenderedFrameCount > 0`, `nv12GlFallbackFrameCount = 0` in normal operation. If the renderer is actually invoked but fails, this fix is complete but `nv12GlFallbackFrameCount > 0` — then Revised Slice 2 should not start until the renderer issue is resolved.

## sws bypass / ANativeWindow bypass

On successful NV12 GL frames: NO `sws_scale`, NO RGBA CPU conversion, NO `ANativeWindow` RGBA render (static, enforced by the dispatch returning before the sws block). On device, logs should no longer show periodic `ANativeWindow buffer renderCount=...` for NV12 GL success frames, and `swsScaleEnabled=false`.

## Hardware decoder preserved

YES — `requestedDecoderName=hevc_mediacodec`, `actualDecoderName=hevc_mediacodec`, `usingHardwareDecoder=true`. ByteBuffer/NV12 output is exactly the Revised Phase 2 input. No decoder / MediaCodec configure / Surface injection change.

## Thermal unchanged

NO Thermal modifications. `Thermal: UNAVAILABLE` remains for `mediacodec_nv12_gl` (thermal is not unlocked; it belongs to a later Revised Slice).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 5s
```

`git diff --check` clean. Only the 2 intended Java files changed; no binaries, no dependency/toolchain upgrades.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (this fix's gate) must confirm: `renderMode=mediacodec_nv12_gl`, `decodeBackend=mediacodec`, `frameOutputType=nv12_cpu`, `renderer=nv12_gl`, `actualDecoderName=hevc_mediacodec`, `usingHardwareDecoder=true`, `nv12GlRenderedFrameCount > 0`, `nv12GlFallbackFrameCount = 0`, `swsScaleEnabled=false`, no black/green/purple/flip/crop, and no periodic ANativeWindow renderCount logs on NV12 GL success frames.

## Ready for Revised Slice 2

YES (static/build; on-device NV12 GL activation verification is the gate)
