# Thermal Phase 2 Slice 1 OES Original Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeOesRenderer.h/.cpp` | NEW — OES renderer: EGL output to SurfaceView, GL_TEXTURE_EXTERNAL_OES texture, SurfaceTexture/decoder-Surface/OnFrameAvailableListener bridge, original OES shader, render/release lifecycle |
| `app/src/main/cpp/CMakeLists.txt` | Added `native/NativeOesRenderer.cpp` |
| `app/src/main/cpp/native/NativePlayer.h/.cpp` | OES mode decoder-surface selection in `openDecoder`; `hardwareModeRequested` includes `MEDIACODEC_OES`; OES prepare in `openInput`; `renderOesPendingFrameIfReady()` in playback loop; `notifyOesFrameAvailable()`; OES renderer release on stop/clear/release; snapshot guard; removed Slice 0 NOT_READY guard; `setDecoderState` preserves `mediacodec_oes` |
| `app/src/main/cpp/native/PlayerOptions.cpp` | Removed the Slice 0 `mediacodec_oes` NOT_READY rejection in the option path |
| `app/src/main/cpp/native-ffmpeg-jni.cpp` | Registered `nativeNotifyOesFrameAvailable`; cached `OesFrameListener` class at JNI_OnLoad; set JavaVM for `NativeOesRenderer` |
| `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java` | Added `OesFrameListener` (SurfaceTexture frame-available → native pending flag) + `nativeNotifyOesFrameAvailable` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Intent `EXTRA_RENDER_MODE="mediacodec_oes"` test entry; playbackInfo shows `Thermal: UNAVAILABLE | mediacodec_oes` + `OES available=.. rendered=..` |

No Thermal shader / White Hot / Ironbow / Gamma / Window / AGC added to the OES path. No GLSurfaceView conversion.

## MediaCodec decoder Surface path

`mediacodec_oes` configure flow (prepare / worker thread):

```
MediaCodec output Surface = Surface(SurfaceTexture)   (NOT the SurfaceView Surface)
→ FFmpeg av_mediacodec_default_init(codec, mcc, decoderSurface)
→ still h264_mediacodec / hevc_mediacodec hardware decoder
```

`mediacodec_surface` path unchanged (decoder Surface = SurfaceView Surface).

## SurfaceTexture bridge

- Native creates the EGL context + OES texture id first (needed before `SurfaceTexture(textureId)`).
- JNI creates `SurfaceTexture(textureId, mainLooperHandler)` on the prepare thread, sets `OesFrameListener` (2-arg listener + handler, API 21+; minSdk 24 safe), creates `Surface(surfaceTexture)`.
- Decoder Surface (`getDecoderSurfaceGlobalRef`) is passed to FFmpeg; all refs are JNI global refs managed by `NativeOesRenderer`.

## OES texture ownership

- `glGenTextures` → `GL_TEXTURE_EXTERNAL_OES` with `GL_LINEAR` / `GL_CLAMP_TO_EDGE`, created only in `prepareForOesDecode` under the EGL owner context.
- Deleted in `releaseGlLocked` (context made current on the releasing thread first).

## EGL / render thread ownership

- EGL display/context/window-surface + OES texture created on the prepare thread (which is also the JNI worker that calls prepare); required because the decoder Surface must exist before `av_mediacodec_default_init`.
- The playback thread (`playbackLoop`) becomes the render owner: `renderOesFrame` calls `eglMakeCurrent` on the playback thread, then `updateTexImage()` + draw + `eglSwapBuffers()`.
- Only one thread holds the EGL context current at a time; `stop()`/`release()` join the playback thread before releasing GL/Java resources.
- The OnFrameAvailable callback (main-looper Handler) only sets an atomic pending flag — no GL.

## frameAvailable strategy

```
MediaCodec → releaseOutputBuffer(render=true) → queues to SurfaceTexture
→ OesFrameListener.onFrameAvailable (main thread) → nativeNotifyOesFrameAvailable
→ NativePlayer::notifyOesFrameAvailable → oesFramePending_=true, oesFrameAvailableCount_++
→ playback loop renderOesPendingFrameIfReady (per packet, cheap atomic early-exit)
→ NativeOesRenderer::renderOesFrame: updateTexImage + getTransformMatrix + draw + swap
→ oesFrameRenderedCount_++
```

## transform matrix handling

`SurfaceTexture.getTransformMatrix(float[16])` → `glUniformMatrix4fv(uSTMatrix)`; vertex shader computes `vTexCoord = (uSTMatrix * aTexCoord).xy` with vec4 texture attributes. No assumption of plain 0~1 texture coords.

## release order

stop/release (after joining the playback thread): `releaseFfmpegResources()` frees the decoder (releases its Surface) → `oesRenderer_.release()` → Java `Surface.release()` + `SurfaceTexture.release()` + listener global-ref delete → GL program/texture delete (context made current) → EGL destroy/terminate. Idempotent (all handles reset), no double delete.

## Stats

```json
"renderMode": "mediacodec_oes",
"renderInputType": "external_oes",
"oesFrameAvailableCount": N,
"oesFrameRenderedCount": N,
"decoderName": "hevc_mediacodec"
```

OES frames are NOT counted in `yuvGlRenderedFrameCount`. `oesFrameAvailableCount`/`oesFrameRenderedCount` continue growing while playing.

## mediacodec_surface regression

NO regression — the direct-Surface hardware path is untouched (only `setDecoderState` now preserves the actual requested renderMode instead of hardcoding `mediacodec_surface`; for `mediacodec_surface` the value is identical).

## Phase 1 regression

NO regression — `software_yuv_gl` (YUV→GL thermal) and `software_rgba` fallback untouched.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. No Gradle/AGP/SDK/NDK/FFmpeg/GLES upgrades, no GLSurfaceView, no binary changes.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance required (Slice 1 gate):

- `mediacodec_surface` still plays original video
- `mediacodec_oes` uses hardware decoder, OES available/rendered grow, no black screen / crash / flip / cropping, Phase 1 unaffected
- Watch Decode/Render FPS, dropped frames, GL errors over ≥30s

## Known issues / limitations

- EGL context/window-surface is created on the prepare (worker) thread and presented from the playback thread via `eglMakeCurrent` — this is the standard multi-thread EGL pattern but must be confirmed stable on the target device; if it fails, Slice 1 follow-up would move OES setup into the playback thread.
- Surface recreation during active OES playback requires re-prepare (documented; `setSurface` tears down OES cleanly to avoid a dangling texture).
- Native snapshot not supported in `mediacodec_oes` (explicit error; demo PixelCopy path is triggered by the "not supported" message).
- OnFrameAvailable fires on the main-looper Handler thread (lightweight atomic set only).
- OES test entry is Intent `com.example.motro.extra.RENDER_MODE=mediacodec_oes`; no UI toggle was added.

## Ready for Slice 2

YES (pipeline built and compiles; on-device verification pending, per the Slice 1 gate)
