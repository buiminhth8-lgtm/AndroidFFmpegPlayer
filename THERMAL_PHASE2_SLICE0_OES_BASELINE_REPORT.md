# Thermal Phase 2 Slice 0 OES Baseline Report

## Current hardware pipeline

```
mediacodec_surface
→ FFmpeg MediaCodec (h264_mediacodec / hevc_mediacodec)
→ AV_PIX_FMT_MEDIACODEC frames → final display Surface (AVMediaCodecBuffer release)
→ renderMediaCodecFrame
```

Preserved unchanged. Thermal Phase 1 Integration Fix stays effective: `mediacodec_surface` → Thermal unavailable.

## Current software pipeline

```
software_yuv_gl
→ FFmpeg software decode
→ AVFrame YUV420P/YUVJ420P
→ NativeYuvGlRenderer::renderI420 → EGLSurface → SurfaceView
→ Phase 1 thermal: Y → Range → Manual/AGC Window → Gamma → White Hot/Ironbow
```

Preserved unchanged.

## Added MEDIACODEC_OES RenderMode

- `PlayerOptions.h`: `enum class RenderMode { SOFTWARE_RGBA, SOFTWARE_YUV_GL, MEDIACODEC_SURFACE, MEDIACODEC_OES }`
- Parser accepts `mediacodec_oes` (also `media_oes`, `oes`); stringifier returns `mediacodec_oes`.
- Reused the existing single RenderMode enum/parser/stringifier system — no second RenderMode system.

## Activation policy: NOT READY

Requesting `mediacodec_oes` is explicitly rejected in Phase 2 Slice 0:

- `NativePlayer::setHardwareRenderMode("mediacodec_oes")` → `{"success":false,...}` "mediacodec_oes is not ready in Phase 2 Slice 0; current render mode unchanged". The current effective render mode is NOT changed, playback is undisturbed.
- `setPlayerOptionValue` (`hardware_render_mode`/`render_mode` key) applies the same guard.
- No silent fallback, no auto-switch to `mediacodec_surface`, no fake success.

## Render input type model

`PlayerOptions.h`:

```cpp
enum class VideoRenderInputType {
    NONE,
    YUV_PLANES,
    DIRECT_SURFACE,
    EXTERNAL_OES
};
```

Mapping (does not influence any existing render behavior):
- `software_rgba` / `software_yuv_gl` → `YUV_PLANES`
- `mediacodec_surface` → `DIRECT_SURFACE`
- `mediacodec_oes` → `EXTERNAL_OES` (future)

`getPlayerStats()` now outputs:

```json
"renderInputType": "direct_surface",   // yuv_planes | direct_surface | external_oes
"oesFrameAvailableCount": 0,
"oesFrameRenderedCount": 0
```

Counters are real atomics initialized to 0 (never incremented yet — no OES frames exist; no fake data).

`playbackInfoTextView` now shows the input type on the mode line, e.g.:
```
state=PLAYING | mediacodec_surface | direct_surface | hevc_mediacodec
...
Thermal: UNAVAILABLE | mediacodec_surface
```

No OES toggle added; users cannot select the not-yet-working `mediacodec_oes`.

## Existing SurfaceView retained

YES

## Existing Native EGL retained

YES (EGL context/surface management stays the base for the future OES renderer)

## Phase 1 modified

NO (White Hot / Ironbow / Gamma / AGC / Window / UI behavior untouched)

## OES runtime implemented

NO

## SurfaceTexture implemented

NO

## NativeOesRenderer class

NOT created in Slice 0 — an unused empty class would be dead code. The renderer component boundary (`NativeOesRenderer.h/.cpp` owning external OES texture, SurfaceTexture, decoder surface, transform matrix, OES shader, EGL ownership) is documented for Slice 1, where it will be created together with the first working OES path.

## Slice 1 resource model (documented, not implemented)

Planned for Phase 2 Slice 1:
- `GL_TEXTURE_EXTERNAL_OES` texture id
- `SurfaceTexture` + decoder `Surface`
- `frameAvailable` flag/counter (MediaCodec → SurfaceTexture callback only sets pending; GL render thread performs `updateTexImage()` + draw)
- SurfaceTexture transform matrix
- OES shader program
- EGL Context / EGLSurface ownership (render thread only; UI/JNI thread never calls GL)
- release/recreate lifecycle

Thread/lifecycle rule fixed for Slice 1: all GL/OES resources belong to the thread owning the EGL Context. UI/JNI thread must not call `glGenTextures` / `updateTexImage` / `glDraw*`.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 26s
```

`git diff --check` clean. Modified files: `PlayerOptions.h/.cpp`, `NativePlayer.h/.cpp`, `MediaPlayerActivity.java`. No binaries, no dependency/Gradle/AGP/SDK/NDK/FFmpeg/GLES upgrades, no GLSurfaceView conversion.

## Static acceptance

- A: RenderMode recognizes `mediacodec_oes` — YES
- B: `mediacodec_oes` cannot be truly activated — YES (NOT READY)
- C: requesting unimplemented mode leaves current mode intact — YES
- D: `mediacodec_surface` behavior fully preserved — YES
- E: `software_yuv_gl` / Phase 1 fully preserved — YES
- F: SurfaceView retained — YES
- G: no half-baked SurfaceTexture/OES runtime logic — YES
- H: no Thermal shader modification — YES
