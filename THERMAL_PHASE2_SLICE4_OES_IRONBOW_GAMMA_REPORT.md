# Thermal Phase 2 Slice 4 OES Ironbow + Gamma Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/ThermalPaletteLut.h/.cpp` | NEW — shared Ironbow 256×1 RGB LUT definition + `createIronbowLut()` (byte-for-byte identical to the Phase 1 generator, moved verbatim) |
| `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` | Uses the shared `ThermalPaletteLut` (removed its local copy; no color change) |
| `app/src/main/cpp/CMakeLists.txt` | Added `native/ThermalPaletteLut.cpp` |
| `app/src/main/cpp/native/NativeOesRenderer.h` | `renderOesFrame(env, w, h, thermalMode, gamma)`; added Ironbow program + locations + LUT texture; White Hot `uGamma` location |
| `app/src/main/cpp/native/NativeOesRenderer.cpp` | White Hot shader now applies `uGamma`; added `kOesIronbowFragmentShader` (RGB → luminance → gamma → LUT); `compileProgramLocked` builds original / white hot / ironbow programs + LUT texture (unit 3); `renderOesFrame` selects program by mode + sets gamma + binds LUT; release deletes ironbow program/texture |
| `app/src/main/cpp/native/NativePlayer.cpp` | `renderOesPendingFrameIfReady` passes thermal mode (0/1/2) + gamma from `ThermalConfig`; OES `thermalRenderMode` now reports `normal` / `white_hot` / `ironbow`; removed obsolete ironbow-fallback log/flag |
| `app/src/main/cpp/native/NativePlayer.h` | Removed `oesIronbowFallbackLogged_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | OES capability: AGC + Manual Window disabled for `mediacodec_oes` (software-only); gamma enabled for OES; OES playbackInfo shows gamma |

No OES Window / OES AGC / Histogram / FBO / glReadPixels / compute shader / new palette / GLSurfaceView / pseudo-color recording.

## OES processing order

```
OES RGB (SurfaceTexture)
→ luminance = dot(rgb, vec3(0.299, 0.587, 0.114))
→ clamp 0..1
→ Gamma pow(intensity, max(uGamma, 0.001))
→ palette
    WHITE_HOT → gray (intensity,intensity,intensity)
    IRONBOW   → texture2D(uPaletteTexture, vec2(intensity, 0.5)).rgb
```

## Luminance formula

Unchanged from Slice 3: `dot(rgb, vec3(0.299, 0.587, 0.114))`, clamped to `[0,1]`.

## Gamma implementation

Reuses `ThermalConfig.gamma` / `setThermalGamma` (0.5 ~ 2.0). Applied as `uGamma` uniform to White Hot and Ironbow OES programs only (not Original, not Thermal OFF). Read per frame from the thread-safe `ThermalConfig` snapshot; runtime change takes effect next frame — no decoder / SurfaceTexture / EGL / shader rebuild. Gamma=1.0 is identity.

## Ironbow LUT reuse strategy

Phase 1's generator was extracted verbatim into `ThermalPaletteLut.h/.cpp` (`createIronbowLut()`, identical control points and piecewise-linear interpolation). Both `NativeYuvGlRenderer` (Phase 1) and `NativeOesRenderer` (Phase 2) call the same function — one Ironbow color table, byte-for-byte/visually identical to Phase 1.

OES LUT texture: 256×1, `GL_RGB`, `GL_UNSIGNED_BYTE`, `GL_LINEAR`, `GL_CLAMP_TO_EDGE`, created once in `compileProgramLocked` (valid GL context only), bound to texture unit 3, deleted in `releaseGlLocked`, re-created on context rebuild. Never created per frame.

## Phase 1 LUT preserved

YES — `createIronbowLut()` output is identical.

## Runtime palette switching

`renderOesFrame` selects the program per frame from the `ThermalConfig` snapshot: 0=Original, 1=White Hot, 2=Ironbow. Switching changes only the program/uniforms — no reconnect / re-prepare / MediaCodec / SurfaceTexture / EGL / LUT / shader rebuild.

## GL resource lifecycle

Original / White Hot / Ironbow programs compiled once at context creation (shared vertex shader deleted only after all three link); LUT texture created once; all deleted in `releaseGlLocked` under a current context (no double delete); re-created on context rebuild. Failure paths: Ironbow unavailable → White Hot fallback → Original fallback (logged at init, not per frame, no black screen).

## OES capability / UI behavior

- `software_yuv_gl`: White Hot / Ironbow / Gamma / Window / AGC — all enabled.
- `mediacodec_oes`: White Hot / Ironbow / Gamma enabled; Manual Window and AGC controls disabled (not in the OES pipeline; Slice 5/6).
- `mediacodec_surface`: Thermal unavailable (unchanged).

## mediacodec_surface regression

NO regression — direct-Surface path untouched.

## Phase 1 regression

NO regression — `software_yuv_gl` shaders/AGC/Window/LUT untouched (only the LUT generator location changed, output identical).

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance (Slice 4 gate): Original 30s → White Hot 30s → Ironbow 30s; Gamma 0.5/1.0/1.5/2.0 live on White Hot and Ironbow; ≥20 Original↔White Hot↔Ironbow switches (no decoder/EGL rebuild, no black/flash); confirm Ironbow LUT colors, transform/aspect unchanged, hardware decoder intact, OES counters growing, no GL errors / performance regression; then confirm `mediacodec_surface` and `software_yuv_gl` Phase 1.

## Ready for Slice 5

YES (static/build; on-device verification is the gate)
