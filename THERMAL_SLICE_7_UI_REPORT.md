# Thermal Slice 7 UI Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/res/layout/view_media_player_controls.xml` | Added `THERMAL` section to the floating control panel (Thermal switch, palette radios, AGC switch, Gamma / Black / White SeekBars + value texts) |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | Added thermal UI state, listeners, enable/disable + window sync logic, `callThermalApi` helper |

No `app/src/main/cpp/**` changes. No shader / AGC / LUT / range / window native algorithm changes.

## Added Thermal controls

```
THERMAL
────────────────
Thermal        [Switch] thermalEnabledSwitch
Palette        [White Hot | Ironbow] thermalPaletteRadioGroup / thermalWhiteHotRadio / thermalIronbowRadio
AGC            [Switch] thermalAgcSwitch
Gamma          thermalGammaSeekBar (0~150) + thermalGammaValueText
Black          thermalBlackPointSeekBar (0~100) + thermalBlackPointValueText
White          thermalWhitePointSeekBar (0~100) + thermalWhitePointValueText
```

Section sits inside the existing floating control panel (above CONTROL); scrollable as before. `playerPreviewView`, `playbackInfoTextView`, floating toggle button, and panel show/hide logic are untouched.

## API bindings (reused, no new JNI)

```java
FFmpegNative.setThermalEnabled(handle, bool)
FFmpegNative.setThermalPalette(handle, THERMAL_PALETTE_WHITE_HOT | THERMAL_PALETTE_IRONBOW)
FFmpegNative.setThermalAgcEnabled(handle, bool)
FFmpegNative.setThermalGamma(handle, gamma)
FFmpegNative.setThermalWindow(handle, black, white)
```

All calls use the current valid handle; when `handle == 0` the UI silently returns (no crash, no blocking). Palette switch during playback calls only `setThermalPalette` — no Prepare / reconnect / player / Surface recreation.

Thermal Enable flow applies current UI config first, then enables:

```java
1. setThermalPalette(palette)
2. setThermalGamma(gamma)
3. setThermalWindow(black, white)
4. setThermalAgcEnabled(agc)
5. setThermalEnabled(true)
```

## Gamma mapping

SeekBar `progress 0..150` → `gamma = 0.5f + progress / 100.0f` (range 0.5 ~ 2.0, default progress 50 → 1.00). Value text shows two decimals. `setThermalGamma` only called when the mapped value actually changed.

## Window validation

Black/White SeekBars map `progress 0..100` → `value = progress / 100.0f` (0.00 ~ 1.00). Always clamped so `0 <= black < white <= 1` with min span `0.01` (`MIN_WINDOW_SPAN`). Clamped values are pushed back to the SeekBars (guarded by `thermalUiUpdating` to avoid recursion) and invalid params are never sent to native.

## AGC / manual UI behavior

- AGC ON → Black/White SeekBars disabled (effective window comes from native AGC). User manual values are not overwritten.
- AGC OFF → Black/White SeekBars re-enabled, previous manual values retained (native Slice 6 restores the manual window; Java does not reimplement AGC).
- Thermal OFF → palette radios, AGC switch, Gamma/Black/White controls disabled/grayed. Re-enabling restores the user's current options.

## playbackInfo integration

`playbackInfoTextView` unchanged from Slice 6 — still shows the thermal summary (e.g. `Thermal: IRONBOW | AGC ON | Gamma 0.90` / `Window: 0.12 - 0.87`, effective AGC window when valid, manual otherwise). No full JSON, no new console. Statistics thread model untouched.

## Native algorithm modified

NO

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 5s
```

`git diff --check` clean. Only the 2 intended UI files modified; no binaries, no dependency/Gradle/AGP/SDK/NDK/UI-framework upgrades.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later: Thermal OFF → White Hot → Ironbow → Gamma adjust → AGC ON/OFF → Manual Window → Thermal OFF, plus Decode/Render FPS, Dropped, `yuvGlFallbackFrameCount`.

## Ready for Slice 8

YES
