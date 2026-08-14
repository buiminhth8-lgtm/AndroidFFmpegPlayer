# Thermal Phase 2 Slice 5 OES Window Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeOesRenderer.h` | `renderOesFrame(env, w, h, thermalMode, gamma, blackPoint, whitePoint)`; added `uBlackPoint`/`uWhitePoint` uniform locations for White Hot and Ironbow programs |
| `app/src/main/cpp/native/NativeOesRenderer.cpp` | White Hot + Ironbow shaders apply `uBlackPoint`/`uWhitePoint` window before gamma; `compileProgramLocked` fetches/validates the new locations; `renderOesFrame` sets window uniforms; release resets them |
| `app/src/main/cpp/native/NativePlayer.cpp` | Passes `thermal.blackPoint` / `thermal.whitePoint` (from `ThermalConfig` snapshot) to `renderOesFrame` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Black/White controls re-enabled for `mediacodec_oes`; AGC stays disabled for OES; OES playbackInfo shows Window (luminance domain) + `AGC: N/A` |

No OES AGC / Histogram / FBO / glReadPixels / compute shader / auto window / new palette / GLSurfaceView / pseudo-color recording / new Thermal API.

## OES processing order

```
OES RGB (SurfaceTexture)
→ luminance = dot(rgb, vec3(0.299, 0.587, 0.114))
→ clamp 0..1
→ manual window:
   clamp((intensity - uBlackPoint) / max(uWhitePoint - uBlackPoint, 0.001), 0.0, 1.0)
→ gamma: pow(intensity, max(uGamma, 0.001))
→ White Hot (gray) / Ironbow (LUT)
```

Window → Gamma order confirmed in both thermal shaders.

## Window coordinate domain

`blackPoint`/`whitePoint` live in the OES luminance normalized `[0,1]` domain. Default `0.0 / 1.0` → identity window.

## Y range normalization applied

NO — no `uYMin` / `uYScale` / `16~235` / `AVColorRange` on the OES path (input is already MediaCodec/SurfaceTexture RGB).

## Existing setThermalWindow reused

YES — reuses `ThermalConfig.blackPoint`/`whitePoint` and the existing `setThermalWindow(handle, blackPoint, whitePoint)` with its existing validation (finite, `0 <= black < white <= 1`, min span). No duplicate OES-specific API.

## White Hot Window

OES White Hot shader applies luminance → window → gamma → gray. Window changes visible in contrast.

## Ironbow Window

OES Ironbow shader applies luminance → window → gamma → LUT. Window changes visible in contrast/color distribution.

## Gamma order

Window is applied BEFORE Gamma (per spec), matching Phase 1 software semantics.

## OES AGC implemented

NO — the OES path never reads AGC effective values (no AVFrame Y plane). If `agcEnabled` is somehow true in OES mode, it is safely ignored (no crash, no wrong window, no claim that OES AGC works). UI keeps the AGC switch disabled for `mediacodec_oes`.

## UI capability behavior

- `software_yuv_gl`: White Hot / Ironbow / Gamma / Manual Window / AGC — all enabled.
- `mediacodec_oes`: White Hot / Ironbow / Gamma / Manual Window enabled; AGC disabled.
- `mediacodec_surface`: Thermal unavailable.

Manual Window is NOT locked by the generic "AGC ON → window disabled" rule for OES (OES AGC can't be turned on, so the window stays available).

## mediacodec_surface regression

NO regression — direct-Surface path untouched.

## Phase 1 regression

NO regression — `software_yuv_gl` Phase 1 Window/AGC/Gamma/LUT untouched.

## Build

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
```

`git diff --check` clean. Only the 4 intended files changed; no dependency/toolchain upgrades, no binaries.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no RTSP stream run. On-device acceptance (Slice 5 gate): OES White Hot + Ironbow with Window `0.00/1.00`, `0.10/0.90`, `0.20/0.80`; Gamma `0.5/1.0/1.5`; ≥20 rapid window adjustments; confirm processing order, no decoder/SurfaceTexture/EGL rebuild, OES counters growing, no GL errors / black / flash; then `mediacodec_surface` + `software_yuv_gl` Phase 1 regression.

## Ready for Slice 6

YES (static/build; on-device verification is the gate)
