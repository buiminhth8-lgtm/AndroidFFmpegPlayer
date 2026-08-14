# Thermal Phase 2 Revised Slice 2 — NV12 GL Stability & Upload Optimization Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeNv12GlRenderer.h` | `renderNv12(..., colorRange, colorspace)`; added `coeffsLocation_`, `rebindEglSurfaceLocked`/`releaseEglSurfaceLocked` |
| `app/src/main/cpp/native/NativeNv12GlRenderer.cpp` | Shader uses `uCoeffs` (BT.601/BT.709); `setSurface` preserves EGL context/program/textures and only rebinds the EGLSurface on Surface recreate; explicit chroma-width stride handling; `EGL_CONTEXT_LOST` rebuild; shared EGL-surface destroy helper |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderNv12GlFrame` passes `frame->colorspace`; records NV12 GL timing stats; `getStats`/`resetStats` extended |
| `app/src/main/cpp/native/NativePlayer.h` | NV12 GL timing stat atomics (`last/avg/max` render + upload) |

No Thermal new features, no OES/SurfaceTexture changes, no MediaCodec configure changes, no sws/RGBA/ANativeWindow reintroduced to the NV12 GL success path, no dependency upgrades, no glFinish / glReadPixels / PBO / compute shader.

## Texture allocation strategy

- Y texture `GL_LUMINANCE`, w×h; UV texture `GL_LUMINANCE_ALPHA`, chromaW×chromaH.
- `glTexImage2D` only on first frame / resolution change / context rebuild; normal frames use `glTexSubImage2D`.
- `glGenTextures` once per EGL context; no per-frame texture create/delete; no per-frame shader compile/link.
- Allocated dimensions tracked (`frameWidth_`/`frameHeight_`); unchanged resolution never re-allocates.

## glTexImage2D usage

First frame / resolution change / GL context recreate only.

## glTexSubImage2D usage

All normal consecutive frames (Y + UV sub-updates).

## Y stride handling

`linesize[0]` never assumed == width. Direct upload when `yStride == width`; otherwise rows of `width` bytes are copied into the reusable `yStaging_` buffer.

## UV stride handling

`chromaWidth = (width+1)/2`, `chromaHeight = (height+1)/2`, UV visible row bytes = `chromaWidth * 2` (NV12 interleaved U,V). Direct upload when `uvStride == uvVisibleRowBytes`; otherwise rows of `uvVisibleRowBytes` are copied into the reusable `uvStaging_` buffer.

## Reusable staging buffer

Renderer-owned `yStaging_`/`uvStaging_`, grow-only (resize only when capacity insufficient); no per-frame malloc/free/vector reallocation. Data/linesize/width/height are validated; invalid frames fall back safely (no out-of-bounds access).

## Resolution-change behavior

`sizeChanged` detected per frame → `glTexImage2D` re-allocates Y/UV textures, staging grows to the new size, viewport/aspect recomputed. No MediaCodec recreate, no full EGL context recreate. `clearSurface` resets allocation state.

## Full/Limited range

`uYMin`/`uYScale`: limited (`AVCOL_RANGE_MPEG`) → `16/255`, `255/219`; full/unspecified → identity. Preserves the current full-range visual result.

## BT.601/BT.709 handling

`uCoeffs = (cR_V, cG_U, cG_V, cB_U)`:
- BT.601: (1.402, 0.344136, 0.714136, 1.772)
- BT.709 (`AVCOL_SPC_BT709`): (1.5748, 0.1873, 0.4681, 1.8556)

Selected from `frame->colorspace`.

## Unknown colorspace fallback

BT.601 (matches Slice 1 behavior) — stable and consistent with the Phase 1 YUV GL convention.

## Surface / EGL lifecycle

- Surface create: window stored; EGL lazily created on the render thread.
- Surface resize/recreate: `setSurface` keeps EGL context/program/textures and only rebuilds the EGLSurface (`rebindEglSurfaceLocked`), then updates viewport; next render re-allocates textures only if resolution changed. No stale EGLSurface use.
- Surface destroy → recreate: playback resumes via the rebind path.
- Release: idempotent teardown (textures/program → EGLSurface → context → display), no double delete.

## Context recreation

`EGL_CONTEXT_LOST` on `eglMakeCurrent` → full GL teardown + `frameWidth_/frameHeight_` reset, so program/textures/uniform+attrib locations are rebuilt on the next render. No stale GL IDs are used.

## NV12 GL timing stats

- `lastNv12GlRenderCostUs`, `avgNv12GlRenderCostUs`, `maxNv12GlRenderCostUs` (upload+draw+swap).
- `lastNv12GlUploadCostUs`, `avgNv12GlUploadCostUs`, `maxNv12GlUploadCostUs`.

Measured with steady clocks; no `glFinish`. No per-frame logging.

## Render fallback behavior

NV12 GL failure → the frame falls through to the existing sws/RGBA path; `nv12GlFallbackFrameCount++` and a low-frequency log. `usingHardwareDecoder=true`, `hardwareDecodeFallbackUsed=false` (render fallback, not decoder fallback).

## Snapshot status

`snapshotSupported=false`, `hasLastFrame=false` for NV12 GL — unchanged. Marked as a known capability gap (no glReadPixels / RGBA CPU cache / sws re-introduction for snapshot).

## Successful hot path

```
hevc_mediacodec
→ NV12
→ glTexSubImage2D
→ NV12 shader
→ eglSwapBuffers
```

## sws_scale

NO (successful NV12 GL path)

## RGBA CPU conversion

NO (successful NV12 GL path)

## 192x256 runtime

NOT_EXECUTED — device `34aff35a` attached but no RTSP stream run / no APK installed. Expected on-device: Decode FPS ≈ Render FPS, dropped stable, `nv12GlRenderedFrameCount` grows, fallback=0, `swsScaleEnabled=false`, no GL/EGL errors, no memory growth.

## HD runtime

NOT_EXECUTED (no 1280x720 test source available).

## Padded-stride runtime

NOT_EXECUTED (current stream is contiguous 192-wide). Padded-stride path is code-reviewed for correctness (no out-of-bounds, reusable staging).

## Color validation

PARTIAL — no color (non-grayscale) source available; BT.601/709 matrix paths are implemented and code-reviewed but color correctness requires a color stream. UV order and full/limited validated only structurally.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 14s
```

`git diff --check` clean. Only the 4 intended files changed; no dependency/toolchain upgrades, no binaries.

## Ready for Revised Slice 3

YES (static/build; on-device stability verification is the gate)
