# Thermal Slice 6 AGC Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativePlayer.cpp` | Added AGC constants, `AgcResult`, `computeAgcWindow()` (histogram + P2/P98 + range conversion + validation); `updateAgcState()`; AGC integration in `renderSoftwareYuvGlFrame`; AGC state reset in `setThermalAgcEnabled`; `getStats()` emits AGC diagnostics |
| `app/src/main/cpp/native/NativePlayer.h` | Added `updateAgcState()`; added `agcValid_`, `agcBlackPoint_`, `agcWhitePoint_`, `agcUpdateCount_`, `agcFrameCounter_` |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `playbackInfoTextView` shows the effective Window (AGC when valid, manual otherwise) |

No shader/renderer changes, no new Palette, no GPU histogram, no MediaCodec/OES, no binaries, no dependency/Gradle/AGP/NDK/SDK upgrades.

## Histogram sampling strategy

CPU histogram of the 8-bit Y plane (`frame->data[0]`, respecting `frame->linesize[0]`):
- `pixelStep = 4`, `rowStep = 4` (~1/16 pixel subsampling)
- Fixed `uint32_t histogram[256]` stack array — no per-frame large allocation, no Y-plane copy
- O(sampled pixels); no sorting, no vectors in the hot path

## Update interval

Every 5 valid software thermal frames (`kAgcUpdateIntervalFrames = 5`), ~6 updates/s at 30fps. Other frames reuse the last valid AGC window. AGC is only computed when `thermalMode` is WHITE_HOT (1) or IRONBOW (2); Thermal OFF / ORIGINAL never run the histogram.

## Percentiles

- `kAgcLowPercentile = 0.02` (P2), `kAgcHighPercentile = 0.98` (P98) from the cumulative histogram (not min/max).

## Range conversion

Raw percentile Y values are converted to the same 0.0 ~ 1.0 normalized thermal range used by the Slice 5 shader:
- `AVCOL_RANGE_MPEG` (limited): `(v - 16/255) / (219/255)` with `v = raw/255`
- full / unspecified / unknown: `raw / 255` (identity, matching Slice 5 conservative policy)

Results clamped to `0.0 ~ 1.0`.

## Smoothing alpha

`kAgcSmoothingAlpha = 0.15`:

```
newBlack = oldBlack * 0.85 + detectedBlack * 0.15
newWhite = oldWhite * 0.85 + detectedWhite * 0.15
```

First valid detection initializes directly from the detected values (no slow ramp from 0/1).

## Invalid-frame protection

AGC result rejected (previous valid AGC window kept) when: no samples, non-finite, `low >= high`, or span `< 0.05` (`kAgcMinSpan`) — covers near-pure-color frames. Before the first valid AGC result the render falls back to the current manual Window. No division by zero, no black screen.

## Manual Window preservation

- Manual `blackPoint`/`whitePoint` in `ThermalConfig` are never overwritten by AGC.
- AGC ON: render uses `agcBlackPoint_`/`agcWhitePoint_`.
- AGC OFF: render immediately uses the manual Window.
- Re-enabling AGC resets `agcValid_`/`agcFrameCounter_` so the new scene re-initializes quickly.

## AGC semantics

```
if (thermalMode is WHITE_HOT or IRONBOW):
    updateAgcState(frame, thermal)          # every 5 frames
    if agcEnabled && agcValid:
        window = agcBlackPoint / agcWhitePoint
    else:
        window = manual blackPoint / whitePoint
```

AGC never calls GL directly; it only feeds the existing Slice 5 `ThermalRenderParams` (Range → Window → Gamma → White Hot/Ironbow unchanged).

## White Hot preserved

YES (Slice 3/5 behavior; AGC window applied before gamma)

## Ironbow preserved

YES (Slice 4/5 LUT behavior; AGC window applied before gamma → LUT)

## Gamma preserved

YES

## Normal rendering preserved

YES (AGC never computed or applied for Thermal OFF / ORIGINAL)

## Stats

`getPlayerStats()` now outputs:

```json
"thermalAgcValid": true,
"thermalAgcBlackPoint": 0.12,
"thermalAgcWhitePoint": 0.87,
"thermalAgcUpdateCount": 123
```

(no 256-bin histogram in JSON). `playbackInfoTextView` shows the effective window, e.g.:

```
Thermal ON | ironbow | AGC ON | gamma 0.90 | render IRONBOW
Window 0.12 - 0.87 | Range limited
```

No new Thermal UI controls.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 3 intended files modified.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- A: AGC OFF → manual Window fully preserved
- B: AGC ON → P2/P98 produces effective Window
- C: enabling AGC does not overwrite manual Window
- D: disabling AGC restores manual Window
- E: White Hot / Ironbow share the same AGC
- F/G: Gamma, LUT, Normal YUV unaffected
- H: stride handled correctly
- I: abnormal/pure-color frames produce no invalid Window
- J: histogram runs every 5 frames, not every frame
- Dark↔bright scene / hot target entering/leaving: watch for full-frame flicker; Decode/Render FPS, Dropped, `yuvGlFallbackFrameCount`

## Ready for Slice 7

YES
