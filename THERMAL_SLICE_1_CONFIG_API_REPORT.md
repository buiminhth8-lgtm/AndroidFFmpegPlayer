# Thermal Slice 1 Config/API Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/ThermalConfig.h` | ADDED — `ThermalPaletteMode` enum, `ThermalConfig` struct, helper declarations |
| `app/src/main/cpp/native/ThermalConfig.cpp` | ADDED — `thermalPaletteName`, `parseThermalPalette`, `isValidThermalGamma` |
| `app/src/main/cpp/CMakeLists.txt` | Added `native/ThermalConfig.cpp` to `native-ffmpeg` sources |
| `app/src/main/cpp/native/NativePlayer.h` | Include `ThermalConfig.h`; added 4 setters + `getThermalConfig()`; added `thermalConfigMutex_` / `thermalConfig_` |
| `app/src/main/cpp/native/NativePlayer.cpp` | Implemented thread-safe setters and getter; `getStats()` outputs thermal fields |
| `app/src/main/cpp/native-ffmpeg-jni.cpp` | Added 4 JNI methods + `RegisterNatives` entries |
| `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java` | Added 4 native method declarations + 3 palette constants |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | `playbackInfoTextView` thermal diagnostics line (no controls added) |

## ThermalConfig location

`app/src/main/cpp/native/ThermalConfig.h` / `ThermalConfig.cpp`.

Defaults (identical to Slice 0 behavior):

```cpp
enabled = false
palette = ORIGINAL
agcEnabled = false
gamma = 1.0f
blackPoint = 0.0f
whitePoint = 1.0f
```

## Java APIs

```java
public static native String setThermalEnabled(long handle, boolean enabled);
public static native String setThermalPalette(long handle, int palette);
public static native String setThermalAgcEnabled(long handle, boolean enabled);
public static native String setThermalGamma(long handle, float gamma);

public static final int THERMAL_PALETTE_ORIGINAL = 0;
public static final int THERMAL_PALETTE_WHITE_HOT = 1;
public static final int THERMAL_PALETTE_IRONBOW = 2;
```

## JNI APIs

Registered in `registerNativeMethods`:

```cpp
{"setThermalEnabled",   "(JZ)Ljava/lang/String;"}
{"setThermalPalette",   "(JI)Ljava/lang/String;"}
{"setThermalAgcEnabled","(JZ)Ljava/lang/String;"}
{"setThermalGamma",     "(JF)Ljava/lang/String;"}
```

Each validates the handle via `getPlayer`, forwards to the matching `NativePlayer` setter, and returns the project's unified JSON string (`{"success":true,...}` on success, `{"success":false,"errorCode":...,"errorMessage":"reason"}` on failure).

Note: NativePlayer C++ setters return `std::string` JSON (matching the existing convention of every public NativePlayer method) rather than the literal `bool` signatures in the slice spec — this is what lets the JNI return explicit per-call error reasons, e.g. invalid palette or invalid gamma.

## Thread safety

`NativePlayer` owns:

```cpp
mutable std::mutex thermalConfigMutex_;
ThermalConfig thermalConfig_;
```

Every read (`getThermalConfig`, `getStats`) and write (all 4 setters) is guarded by `thermalConfigMutex_`. Setters only mutate the struct — no `gl*`/`egl*` calls, no render path interaction. Safe to call from UI/JNI thread while a GL render thread may later read the config.

## Parameter validation

- Palette: only `0` (ORIGINAL), `1` (WHITE_HOT), `2` (IRONBOW). Illegal value → failure JSON, original value kept (no silent coercion).
- Gamma: `std::isfinite` and range `0.5 ~ 2.0`. NaN/Infinity/out-of-range → failure JSON, original value kept.

## Stats fields

`getPlayerStats()` now outputs:

```json
"thermalEnabled": false,
"thermalPalette": "original",
"thermalPaletteValue": 0,
"thermalAgcEnabled": false,
"thermalGamma": 1.0
```

Palette strings: `original` / `white_hot` / `ironbow`.

`playbackInfoTextView` appends one concise diagnostics line (no full JSON, no UI controls):

```
Thermal OFF | original | AGC OFF | gamma 1.00
```

## Shader modified

NO

## Visual output modified

NO — even with `thermalEnabled=true` / `thermalPalette=IRONBOW`, the rendered picture is unchanged (config is only stored; no render path consumes it yet). This is the core acceptance condition of Slice 1.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 8s
```

`git diff --check` clean. No Gradle/AGP/SDK/NDK/FFmpeg/dependency upgrades.

## Ready for Slice 2

YES
