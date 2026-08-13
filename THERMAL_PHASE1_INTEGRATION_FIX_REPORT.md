# Thermal Phase 1 Integration Fix Report

## Problem

In `mediacodec_surface` (hardware decode) mode, the Thermal Phase 1 pipeline (White Hot / Ironbow / Gamma / Manual Window / AGC) is only implemented for `software_yuv_gl`. A user could enable Thermal in hardware mode and get "configuration success but no visual change" — with `state=PLAYING, renderMode=mediacodec_surface, decoder=hevc_mediacodec, yuv-gl rendered=0`.

## Root Cause

The UI did not guard Thermal against the hardware decode surface mode. `setThermalEnabled(true)` succeeded against the native player while the render path (`renderMediaCodecFrame`) ignores thermal config, producing a misleading "Thermal ON / render=IRONBOW" state with no thermal picture.

## Supported Thermal render mode

Phase 1 Thermal support:
```
software_yuv_gl = SUPPORTED
mediacodec_surface = NOT SUPPORTED
```

## Hardware mode behavior

- When Stats reports `renderMode=mediacodec_surface`, thermal is treated as unavailable (`isThermalSupported()` returns false; stats `renderMode` is the runtime source of truth).
- Before a player/stats exists, availability falls back to the pending `hardwareDecodeSwitch` choice (`software_yuv_gl` if off, `mediacodec_surface` if on).

## Thermal enable guard

When current/pending mode is `mediacodec_surface`, tapping Thermal ON:
1. Does NOT call `setThermalEnabled(true)`.
2. Restores the Thermal switch to OFF.
3. Leaves the picture unchanged.
4. Logs `Log.w` with the reason and current mode.
5. Shows Toast: `Thermal requires Software YUV GL. Disable hardware decoding and restart playback.`

No auto RTSP reconnect, no player recreate, no decoder switch, no stop/start.

## Hardware decode guard

While Thermal is ON (only possible in `software_yuv_gl`), toggling Hardware Decode ON:
1. Is blocked — the switch is restored to OFF.
2. Logs `Log.w`.
3. Shows Toast: `Disable Thermal before enabling hardware decoding.`

Thermal is not auto-disabled; the user decides. Thermal OFF allows hardware decode normally.

## playbackInfo behavior

- `renderMode=mediacodec_surface` → displays `Thermal: UNAVAILABLE | mediacodec_surface` (window/range line omitted, since irrelevant).
- `software_yuv_gl` → displays the existing Phase 1 summary (e.g. `Thermal: IRONBOW | AGC ON | Gamma 0.90`, effective AGC window when valid, manual otherwise).
- `renderMode`, decoder, yuv-gl rendered, fallback always visible. No debug console; no full JSON.

Controls state is consolidated in `updateThermalControlsEnabledState()`, combining `thermalSupported` + `thermalEnabled` + `agcEnabled` (single source, no scattered setEnabled logic):
- Thermal unsupported → palette/AGC/gamma/window disabled; main Thermal switch stays clickable to show the Toast.
- Thermal OFF (supported) → palette/AGC/gamma/window disabled.
- Thermal ON + AGC ON → manual Black/White disabled (per Slice 7).

`currentRenderMode` refresh points: Activity init, `applyDecodeModeOption` (create/prepare), every Stats update (authoritative), and player release (reset to unknown). Stats updates only sync availability/display — no repeated Toast, no repeated JNI setters, no config mutation.

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Added `currentRenderMode` tracking, `isThermalSupported()`, consolidated `updateThermalControlsEnabledState()`; Thermal-enable guard; Hardware-decode guard listener; playbackInfo thermal availability display |

No `app/src/main/cpp/**` changes. No new JNI/API. `software_yuv_gl` still selected for software decode (`applyDecodeModeOption` keeps order: `setHardwareDecode` → `setHardwareRenderMode`); `software_rgba` fallback untouched.

## Native thermal algorithm modified

NO

## Automatic player restart

NO

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 4s
```

`git diff --check` clean. Only `MediaPlayerActivity.java` changed; no binaries, no dependency/Gradle/AGP/SDK/NDK/FFmpeg upgrades, no OES/MediaCodec thermal implementation.

## Runtime verification

NOT_EXECUTED — device `34aff35a` attached but no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- A: Hardware Decode ON (`mediacodec_surface`, yuv-gl rendered=0) → Thermal unavailable; tapping Thermal ON blocked + Toast; playback unaffected.
- B: Hardware Decode OFF + player rebuilt → `software_yuv_gl`, yuv-gl rendered>0, thermal controls available.
- C: Software YUV GL + Thermal ON → White Hot/Ironbow/Gamma/AGC/Window work per Phase 1.
- D: Thermal ON + attempt Hardware Decode ON → blocked, switch restored, prompt.
- E: Thermal OFF → Hardware Decode selectable.
- F: Hardware-mode playback (MediaCodec/RTSP/Recording/Snapshot/Reconnect) unaffected.
