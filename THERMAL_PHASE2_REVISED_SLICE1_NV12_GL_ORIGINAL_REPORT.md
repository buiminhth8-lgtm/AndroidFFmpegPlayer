# Thermal Phase 2 Revised Slice 1 — NV12 GL Original Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h` | Full renderer interface: `setSurface`/`clearSurface`/`release`/`hasSurface`/`isReady`/`supportsFrameFormat`, `renderNv12(yData,yStride,uvData,uvStride,width,height,colorRange)`, EGL/texture/program members, staging buffers |
| `app/src/main/cpp/native/NativeNv12GlRenderer.cpp` | EGL (display/context/window-surface on the calling render thread), Y (`GL_LUMINANCE`, w×h) + UV (`GL_LUMINANCE_ALPHA`, w/2×h/2) textures, BT.601 NV12 YUV→RGB shader, stride-aware staging (grow-only), `glTexImage2D` on size change / `glTexSubImage2D` normal frames, aspect-fit viewport, safe release |
| `app/src/main/cpp/native/NativePlayer.cpp` | `MEDIACODEC_NV12_GL` added to `hardwareModeRequested` and the Surface pre-check; removed the NOT_READY rejection; `renderNv12GlFrame` (NV12 GL render + counters); NV12 GL dispatch in `renderFrame` (bypasses sws/RGBA on success, falls back on failure); stats `nv12GlRenderedFrameCount`/`nv12GlFallbackFrameCount`; `resetStats` |
| `app/src/main/cpp/native/NativePlayer.h` | `renderNv12GlFrame`; counters `nv12GlRenderedFrameCount_`/`nv12GlFallbackFrameCount_` |
| `app/src/main/cpp/native/PlayerOptions.cpp` | `setPlayerOptionValue` no longer rejects `mediacodec_nv12_gl` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `applyDecodeModeOption` routes Intent `EXTRA_RENDER_MODE="mediacodec_nv12_gl"` → hardware decode + `setHardwareRenderMode("mediacodec_nv12_gl")`; display treats `mediacodec_nv12_gl` as thermal-unavailable |

No Thermal / White Hot / Ironbow / Gamma / Window / AGC / OES changes, no sws behavior change, no decoder/MediaCodec configure change, no FFmpeg/NDK/Gradle upgrades, no GLSurfaceView.

## Actual decode backend

`hevc_mediacodec` hardware decoder (unchanged). `usingHardwareDecoder=true`, `requestedDecoderName/actualDecoderName=hevc_mediacodec`. No decoder rebuild, no MediaCodec configure / Surface binding changes.

## NV12 frame contract

`AV_PIX_FMT_NV12`: `frame->data[0]` = Y plane, `frame->data[1]` = interleaved UV. width/height/linesize[0]/linesize[1]/color_range preserved. NV21/YUV420P/P010 are never treated as NV12 (`renderFrame` checks `sourceFormat == AV_PIX_FMT_NV12`).

## Y / UV texture format

- Y: `GL_TEXTURE_2D` / `GL_LUMINANCE`, width × height, `GL_UNSIGNED_BYTE`.
- UV: `GL_TEXTURE_2D` / `GL_LUMINANCE_ALPHA`, width/2 × height/2 — NV12 interleaved (U,V,U,V...) stays interleaved; the shader reads `.ra` = (U,V). No CPU deinterleave.
- `GL_LINEAR`, `GL_CLAMP_TO_EDGE`, no mipmaps.

## Stride handling

Renderer never assumes `linesize == width`. When stride differs, rows are compacted into renderer-owned reusable staging buffers (`yStaging_`/`uvStaging_`, grow-only, no per-frame allocation); Y copies `width` bytes per row, UV copies `width` bytes per chroma row. `data[0]/data[1]`, width/height, strides are null/range-checked; failures return a render error → RGBA fallback.

## NV12 shader

```
float y = texture2D(uTextureY, tc).r;  y = clamp((y - uYMin) * uYScale, 0, 1);
vec2 uv = texture2D(uTextureUV, tc).ra;  u = uv.x - 0.5;  v = uv.y - 0.5;
r = y + 1.402*v; g = y - 0.344136*u - 0.714136*v; b = y + 1.772*u;
```

Same BT.601 convention and range normalization (`uYMin/uYScale` from `frame->color_range`, limited 16~235 / else identity) as the Phase 1 YUV GL renderer. Full-range video correct for this slice; full BT.601/709 metadata adaptation is deferred to Slice 2.

## EGL / Surface integration

Current `SurfaceView` is the EGL output target (no GLSurfaceView, no MediaCodec decoder Surface, no SurfaceTexture/OES). EGL created lazily on the playback thread (`ensureGlLocked`), window surface from the setSurface ANativeWindow, aspect-fit viewport. Release tears down safely (no double delete).

## sws bypass

On NV12 GL success, `renderFrame` returns immediately — `sws_scale`, RGBA frame copy, `saveLastFrame`/`ANativeWindow_lock`/`renderRgba` are NOT executed for that frame.

## RGBA / ANativeWindow bypass

On NV12 GL success the software RGBA renderer is not reached.

## Render fallback behavior

NV12 GL init/render failure → the frame falls through to the existing sws/RGBA path; `nv12GlFallbackFrameCount` increments and a low-frequency `LOGE` logs the reason. `usingHardwareDecoder` stays true and `hardwareDecodeFallbackUsed` stays false (render fallback is NOT decoder fallback). No silent fake success.

## Stats

Normal NV12 GL path reports: `renderMode="mediacodec_nv12_gl"`, `decodeBackend="mediacodec"`, `frameOutputType="nv12_cpu"`, `renderer="nv12_gl"`, `renderInputType="nv12_cpu"`, `usingHardwareDecoder=true`, and `nv12GlRenderedFrameCount` grows (`nv12GlFallbackFrameCount` stays 0 in normal operation).

## Phase 1 regression

NO regression — `software_yuv_gl` (Phase 1 YUV GL + Thermal) and `software_rgba` untouched.

## OES preserved

`mediacodec_oes` + `NativeOesRenderer` preserved unchanged (experimental path).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 4s
```

`git diff --check` clean. Only the intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. On-device pass (Slice 1 gate) must confirm: `actualDecoderName=hevc_mediacodec`, `usingHardwareDecoder=true`, `renderMode=mediacodec_nv12_gl`, `frameOutputType=nv12_cpu`, `renderer=nv12_gl`, `nv12GlRenderedFrameCount > 0`, `nv12GlFallbackFrameCount = 0`, no green/purple/UV-inverted/stretched/flipped picture, no GL errors, and no `sws context ready` / ANativeWindow buffer logs on NV12 GL success frames.

## Successful path

```
hevc_mediacodec
→ NV12
→ OpenGL
→ SurfaceView
```

## sws_scale in successful NV12 GL path

NO

## Ready for Slice 2

YES (static/build; on-device NV12 GL original verification is the gate)
