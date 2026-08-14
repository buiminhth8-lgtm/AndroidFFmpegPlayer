# MediaCodec HC Slice 0 Configure Trace Report

## 1. Current failure sequence

```
Hardware Decode ON
→ MediaPlayerActivity.applyDecodeModeOption → setHardwareDecode(true) + setHardwareRenderMode("mediacodec_surface")
→ NativePlayer::prepare → openInput → openDecoder(hevc_mediacodec, useHardware=true)
→ av_mediacodec_alloc_context()
→ av_mediacodec_default_init(videoCodecContext_, mediaCodecCtx, surfaceGlobalRef_)   // returns 0, logs "MediaCodec surface init success"
→ avcodec_open2(videoCodecContext_, hevc_mediacodec, NULL)
    → FFmpeg mediacodec decoder init → AMediaCodec_configure(...)
    → "Client requested ByteBuffer mode decoder w/o color format set"
    → "Bad parameter value" / "failed to configure c2 params" / "Codec reported BAD_VALUE"
    → avcodec_open2 failed
→ hardware decoder failed → software fallback
→ setDecoderState(..., RenderMode::SOFTWARE_RGBA)
→ actual renderMode = software_rgba
```

Confirmed by code order. `av_mediacodec_default_init` returns success (it only stores the Surface jobject); the actual `AMediaCodec_configure` call happens later inside `avcodec_open2`.

## 2. Current direct-Surface architecture

```
SurfaceView.getHolder().getSurface()   (Java Surface)
  → MediaPlayerActivity.setPlayerSurface(handle, surface)   (worker thread, runNative)
  → NativePlayer::setSurface(env, surface)
      → env->NewGlobalRef(surface) → surfaceGlobalRef_   (stored under surfaceMutex_)
      → renderer_.setSurface / yuvGlRenderer_.setSurface / oesRenderer_.setSurface (ANativeWindow-based, separate)
  → NativePlayer::prepare (worker thread)
      → openInput → openDecoder
      → av_mediacodec_default_init(videoCodecContext_, mediaCodecCtx, surfaceGlobalRef_)   // display Surface jobject
      → avcodec_open2(...)
```

Distinct concepts (never mixed in the FFmpeg path):
1. display Surface jobject = `surfaceGlobalRef_` (Java Surface, passed to FFmpeg MediaCodec).
2. `ANativeWindow*` = used only by the renderers (rgba/GL/OES), never passed to FFmpeg.
3. OES decoder Surface = `NativeOesRenderer::getDecoderSurfaceGlobalRef()` (SurfaceTexture-based), used only in `mediacodec_oes` mode.
4. `EGLSurface` = renderer-internal output, unrelated to FFmpeg.

## 3. Actual FFmpeg MediaCodec API

From the bundled headers (`app/src/main/cpp/ffmpeg/include/libavcodec/mediacodec.h`):

```
FFmpeg MediaCodec API used:
AVMediaCodecContext *av_mediacodec_alloc_context(void);
int av_mediacodec_default_init(AVCodecContext *avctx, AVMediaCodecContext *ctx, void *surface);
void av_mediacodec_default_free(AVCodecContext *avctx);
int av_mediacodec_release_buffer(AVMediaCodecBuffer *buffer, int render);
```

Surface injection point: `NativePlayer::openInput` → `openDecoder`, before `avcodec_open2`.

Injection before avcodec_open2: **YES** (code order confirmed).

## 4. Surface object ownership / lifetime

- Created: `setSurface` → `env->NewGlobalRef(surface)` → `surfaceGlobalRef_`.
- No local ref is kept across JNI calls.
- Deleted only via `deleteSurfaceGlobalRefLocked` (called from `setSurface` when replacing, `clearSurface`, and `release`).
- `surfaceGlobalRef_` is not reset between `setSurface` and `prepare`/`avcodec_open2`.
- The OES decoder Surface is a separate global ref owned by `NativeOesRenderer`; it is never written into `surfaceGlobalRef_` and does not affect the direct-Surface path.

## 5. Surface injection point

Inside `openDecoder`, `if (useHardware)`: OES branch passes `oesRenderer_.getDecoderSurfaceGlobalRef()`; direct branch passes `surfaceGlobalRef_` (under `surfaceMutex_`, null-checked). `av_mediacodec_default_init` is called, then `avcodec_open2` immediately after.

## 6. Injection before avcodec_open2

YES (static code order; the existing "MediaCodec surface init success" log and the new `[HWCFG]` trace confirm the return value and injection).

## 7. Why ByteBuffer mode is currently plausible

`av_mediacodec_default_init` stores the Surface jobject and returns 0 without validating that `AMediaCodec_configure` will accept it. The real `AMediaCodec_configure` runs inside `avcodec_open2` (FFmpeg `ff_mediacodec_dec_init`), where the wrapper must resolve a JNIEnv (`av_jni_get_env`) to use the jobject, and it must decide between Surface mode vs ByteBuffer mode. The Android log "Client requested ByteBuffer mode decoder w/o color format set" is the wrapper/MediaCodec falling into ByteBuffer mode, i.e. `configure()` was effectively given no usable Surface AND no color format. The most plausible reasons, to be resolved by the runtime `[HWCFG]` trace + FFmpeg source (bundled version):
- (a) the Surface jobject is non-null in `av_mediacodec_default_init` but not actually usable when `configure()` runs (JNI env / wrapper path), or
- (b) the wrapper treats the surface as unusable and requires a color format (`pix_fmt`), while `codecpar->format` is `AV_PIX_FMT_NONE` for this stream.

## 8. Relevant Git regression candidates

- `git log -S av_mediacodec_default_init`: introduced in `d38ff93` (original "FFMPEG Mediacodec support"); restructured in `47d5feb` (Phase 2 Slice 1, OES branch) — but the direct-Surface call is functionally identical (same `surfaceGlobalRef_`, same timing before `avcodec_open2`).
- `surfaceGlobalRef_` introduced and only ever modified in `d38ff93` (create/replace/delete).
- Phase 2 Slices 0–6 (OES/thermal) did NOT change the direct-Surface MediaCodec path, `surfaceGlobalRef_` ownership, init order, decoder surface selection for the direct branch, or context reset.
- Conclusion: the direct-Surface binding chain is unchanged since the original MediaCodec support commit. No "last known good" direct-Surface commit was found in this history. The ByteBuffer/BAD_VALUE behavior is a pre-existing FFmpeg-wrapper/Surface interaction issue (device/bundle/stream dependent), not a Phase 2 code regression.

## 9. Ranked root-cause hypotheses

P1 HIGH — FFmpeg mediacodec wrapper effectively configures with no usable Surface.
Evidence: `av_mediacodec_default_init` returns 0 (only stores the jobject), then `avcodec_open2` reports "ByteBuffer mode ... w/o color format set" → `configure()` saw no usable Surface. Confirmed by runtime `[HWCFG] surfaceInjected=yes` (if trace shows yes, the disconnect is inside FFmpeg's configure/JNI-env path).

P1 HIGH — `codecpar->format == AV_PIX_FMT_NONE` (common on RTSP/HEVC) interacts with the wrapper: when the Surface path is not taken, the decoder demands a color format and fails.
Evidence: `[HWCFG] pixFmt` in the trace (likely `-1`/AV_PIX_FMT_NONE). This is the classic FFmpeg mediacodec "Client requested ByteBuffer mode decoder w/o color format set" trigger.

P2 MEDIUM — JNI VM / thread-env availability for FFmpeg (`av_jni_set_java_vm` result, or thread where configure runs).
Evidence: `[HWCFG] jniEnv=ok` in the trace; `g_jni_initialized` from JNI_OnLoad logs. `av_jni_set_java_vm` is called in JNI_OnLoad.

P2 MEDIUM — the Surface jobject is replaced/invalidated between `setSurface` and `avcodec_open2` (SurfaceView recreate). 
Evidence: no code path found that replaces `surfaceGlobalRef_` between setSurface and prepare in a single session; low likelihood, but SurfaceView recreation timing is worth confirming on device.

P3 LOW — Phase 2 OES changes confused decoder surface selection.
Evidence AGAINST: direct branch uses `surfaceGlobalRef_` only; OES uses a separate renderer-owned decoder Surface; no mixing observed.

## 10. Diagnostic logs added

One-shot/rare, `[HWCFG]` prefixed, in `openDecoder` (no per-frame, no behavior change):
- `[HWCFG] begin decoder=... renderMode=... enableHardwareDecode=... jniEnv=ok/null displaySurface=valid/null pixFmt=... colorRange=... colorSpace=...`
- `[HWCFG] decoderSurfaceType=oes decoderSurface=valid/null`
- `[HWCFG] mediaCodecContext=created/null surfaceInjected=yes/no beforeAvcodecOpen2=yes`
- `[HWCFG] avcodecOpen2=error/success decoder=...`
- `[HWCFG] fallback=yes/no actualDecoder=... actualRenderMode=... usingHardware=...`

## 11. Runtime trace result

NOT_EXECUTED — device `34aff35a` attached but no usable RTSP stream URL was available and no unknown test flow was started. Collecting the `[HWCFG]` trace on one Hardware-Decode prepare is the next step (HC Slice 1 device pass).

## 12. Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 15s
```

`git diff --check` clean. Only `app/src/main/cpp/native/NativePlayer.cpp` changed (25 additive diagnostic lines). No behavior change, no configure fix, no Thermal/OES modification.

## 13. Recommended HC Slice 1 fix scope

- Run one Hardware-Decode prepare with a real stream and capture the full `[HWCFG]` trace to confirm: `displaySurface=valid`, `jniEnv=ok`, `surfaceInjected=yes`, `pixFmt` value, `avcodecOpen2=error`.
- Inspect the bundled FFmpeg mediacodec decoder source (`ff_mediacodec_dec_init` / `mediacodecdec_common.c`) to find the exact ByteBuffer-vs-Surface decision and the color-format requirement.
- Candidate minimal fixes (to be implemented only in HC Slice 1): ensure the Surface reaches `AMediaCodec_configure` from a usable thread/env; or set the codec color format appropriately when Surface mode is requested; or pass `mediacodec_surface` with correct avctx fields. No code changes made in this slice.
