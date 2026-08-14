# Thermal Phase 2 Slice 2 OES Lifecycle Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeOesRenderer.h` | `renderOesFrame(env, frameW, frameH)`; added `rebindEglSurfaceLocked` / `releaseEglSurfaceLocked` / `resetDiagnostics`; counters `surfaceRecreateCount_`, `contextRecreateCount_`, `updateTexImageErrorCount_`; cached `transformMatrixArrayGlobalRef_` |
| `app/src/main/cpp/native/NativeOesRenderer.cpp` | `setSurface` rebuilds only the EGL window surface when prepared; aspect-fit letterbox viewport with 90°-rotation detection; EGL_CONTEXT_LOST teardown; `updateTexImage` error counting + rate-limited logs; reusable transform matrix array; shared surface-destroy helper |
| `app/src/main/cpp/native/NativePlayer.cpp` | Passes `videoWidth_`/`videoHeight_` to `renderOesFrame`; rate-limited render-failure log via `oesRenderFailCount_`; stats output for the three new OES counters; `resetStats` clears OES counters |
| `app/src/main/cpp/native/NativePlayer.h` | Added `oesRenderFailCount_` |

No Thermal effects, no GLSurfaceView, no decoder refactor, no dependency upgrades.

## Transform matrix handling

Every successful `updateTexImage()` is followed by `getTransformMatrix()` into a reused fixed `float[16]` (no per-frame JNI allocation), applied via `glUniformMatrix4fv(uSTMatrix)`; the vertex shader computes `vTexCoord = (uSTMatrix * aTexCoord).xy` with vec4 texture attributes. No hardcoded flip/rotate is used to mask matrix issues.

## Aspect / viewport behavior

`renderOesFrame` computes an aspect-fit letterbox viewport from `videoWidth/videoHeight` and the current surface size, honoring a 90° rotation detected from the SurfaceTexture matrix (near-zero diagonal → transposed). The quad is drawn fullscreen in NDC; the letterboxed viewport prevents forced stretching; bars show black (`glClearColor`). `glViewport` is re-applied every frame and on EGL surface rebind, so orientation / Surface size changes / recreate are handled.

Note: this makes OES letterbox, while the legacy `software_yuv_gl` path stretches — a deliberate display-policy difference for OES (no forced stretch). Documented for on-device review.

## frameAvailable synchronization

The OnFrameAvailable callback (main-looper Handler thread) only sets an atomic pending flag and increments the available counter — no GL. Multiple callbacks coalesce into the latest frame (atomic flag, no unbounded queue). The render loop consumes `oesFramePending_` under atomics; no data race on a plain bool.

## EGL ownership

GL/EGL remains confined to the thread holding the context: EGL + OES texture + SurfaceTexture created on the prepare thread; `renderOesFrame` makes the context current on the playback thread under the renderer mutex; `setSurface`/release rebind/teardown under the same mutex.

## Surface recreate behavior

`setSurface` during prepared OES playback now rebuilds ONLY the EGL window surface (`rebindEglSurfaceLocked`: destroy old EGLSurface → create from new ANativeWindow → make current → viewport) and keeps the SurfaceTexture, OES texture, program, and decoder untouched (no MediaCodec / SurfaceTexture rebuild). On EGL surface rebind failure it falls back to a full teardown requiring re-prepare. `oesSurfaceRecreateCount` tracks rebinds.

## Context recreate behavior

If `eglMakeCurrent` reports `EGL_CONTEXT_LOST`, the renderer tears down GL resources (`releaseGlLocked`), sets `prepared_=false` so no stale GL ids are drawn, increments `oesContextRecreateCount`, and requires a stop/restart (decoder recreate) to resume. No use of old GL id + new context.

## Release order

stop/release (after joining the playback thread): `releaseFfmpegResources()` frees the MediaCodec (releases its Surface) → `oesRenderer_.release()` → Java `Surface.release()` + `SurfaceTexture.release()` + listener + cached matrix array global-ref delete → GL program/texture delete under a re-made current context → `releaseEglSurfaceLocked` → context destroy → display terminate. Idempotent; no double release / use-after-free / callback-after-release (the listener's native call is guarded by the release-safe `getPlayer`).

## Hot-path review

- No per-frame shader compile, texture create/delete, EGL surface create, or Surface creation.
- Transform matrix array reused (single global ref); no per-frame JNI object churn.
- No unbounded frame queue.
- Failure logs rate-limited (first + every 100); `updateTexImage` exception logs rate-limited.

## mediacodec_surface regression

NO regression — the direct-Surface hardware path never touches the OES renderer (only `setDecoderState`/openDecoder branches by render mode).

## Phase 1 regression

NO regression — `software_yuv_gl` thermal path untouched.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 5s
```

`git diff --check` clean. Only the 4 intended files modified.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance (Slice 2 gate) to confirm: ≥60s OES continuous playback (hardware decoder, OES counters growing, no black screen / orientation / cropping issues), ≥5 background/foreground cycles, ≥5 Surface/Activity recreates, ≥5 Stop→Start / Release→Create, and one `mediacodec_surface` playback for regression. Watch Decode/Render FPS, dropped frames, OES available/rendered, `oesUpdateTexImageErrorCount`, `oesSurfaceRecreateCount`, `oesContextRecreateCount`, GL/EGL errors.

## Issues found / fixed

- Found: OES rendered into the full surface (forced stretch) regardless of video aspect. Fixed: aspect-fit letterbox viewport with 90°-rotation handling.
- Found: `setSurface` during prepared playback tore down the whole OES pipeline (breaking the decoder's SurfaceTexture binding). Fixed: only the EGL window surface is rebuilt, preserving MediaCodec / SurfaceTexture / texture.
- Found: EGL context loss would leave stale GL resources in use. Fixed: `EGL_CONTEXT_LOST` detection → GL teardown + prepared=false (re-prepare required), counter exposed.
- Found: `updateTexImage`/render failures could log every frame. Fixed: error counters + rate-limited logging.
- Found: per-frame `jfloatArray(16)` allocation for the transform matrix. Fixed: cached global-ref array.

## Ready for Slice 3

YES (static/build; on-device OES original verification still the gate)
