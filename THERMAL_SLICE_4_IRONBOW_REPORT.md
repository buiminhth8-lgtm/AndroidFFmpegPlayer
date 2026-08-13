# Thermal Slice 4 Ironbow Report

## Modified Files

| File | Change |
| --- | --- |
| `app/src/main/cpp/native/NativeYuvGlRenderer.h` | `renderI420(...)` now takes `int thermalMode` (0 normal / 1 white_hot / 2 ironbow); added `ironbowProgram_`, `ironbowGammaLocation_`, `ironbowPaletteLocation_`, `ironbowTexture_` |
| `app/src/main/cpp/native/NativeYuvGlRenderer.cpp` | Added `kIronbowFragmentShader`; added standalone `createIronbowLut()` (256×1×RGB, control points + linear interpolation); compile/link Ironbow program + LUT texture (unit 3) in `compileProgramLocked`; program selection + gamma + LUT binding in `renderI420`; release in `releaseGlLocked` |
| `app/src/main/cpp/native/NativePlayer.h` | Replaced `ironbowNotImplementedLogged_` with `ironbowRenderedFrameCount_` |
| `app/src/main/cpp/native/NativePlayer.cpp` | Computes per-frame `thermalMode` (0/1/2) from `ThermalConfig` snapshot; removed Slice 2 "Ironbow not implemented" log; passes mode + gamma to `renderI420`; tracks `ironbowRenderedFrameCount_`; `getStats()` emits `thermalRenderMode` incl. `ironbow` + `ironbowRenderedFrameCount` |

No Java/JNI API changes (Slice 1 `setThermalPalette`/`setThermalGamma` reused). No changes to RTSP / demux / decode / reconnect / recording / snapshot / MediaCodec / software_rgba fallback / surface lifecycle / state machine / OpenGL ES version / build tooling.

## LUT generation location

`app/src/main/cpp/native/NativeYuvGlRenderer.cpp` — `createIronbowLut()` in the anonymous namespace (pure function, no GL). Standalone and easy to re-tune.

Control points (piecewise-linear, 256 entries, monotonic with Y brightness):

```
0.00  (10, 0, 30)     near-black dark blue
0.15  (0, 0, 120)     dark blue
0.30  (120, 0, 200)   violet
0.45  (200, 0, 200)   magenta
0.60  (230, 40, 60)   red
0.75  (250, 140, 20)  orange
0.90  (250, 220, 60)  yellow
1.00  (255, 245, 235) white
```

## LUT texture lifecycle

- Created once inside `compileProgramLocked` (only runs under the GL context, on the render thread): `glGenTextures` → `glBindTexture(GL_TEXTURE_2D, ironbowTexture_)` → `glTexImage2D(256, 1, GL_RGB, GL_UNSIGNED_BYTE, lut.data())` → params `GL_LINEAR/GL_LINEAR/GL_CLAMP_TO_EDGE/GL_CLAMP_TO_EDGE`, bound to texture unit 3 (Y=0, U=1, V=2, LUT=3).
- Deleted in `releaseGlLocked` via `glDeleteTextures`; `ironbowTexture_` reset to 0. Recreated automatically on next context creation (`ensureGlLocked` → `compileProgramLocked`).
- Not created/re-created per frame.

## Ironbow shader location

`app/src/main/cpp/native/NativeYuvGlRenderer.cpp` — `kIronbowFragmentShader`:

```glsl
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D yTexture;
uniform sampler2D paletteTexture;
uniform float uGamma;
void main() {
    float gray = texture2D(yTexture, vTexCoord).r;
    gray = clamp(gray, 0.0, 1.0);
    gray = pow(gray, max(uGamma, 0.001));
    vec3 color = texture2D(paletteTexture, vec2(gray, 0.5)).rgb;
    gl_FragColor = vec4(color, 1.0);
}
```

Reads Y only (never U/V). `normalProgram_` and `whiteHotProgram_` untouched.

## Gamma reuse

Ironbow uses the same `ThermalConfig.gamma` as White Hot — no separate `ironbowGamma`/`paletteGamma` state. `gamma=1.0` leaves input brightness unchanged; runtime `setThermalGamma` affects both White Hot and Ironbow. Normal YUV is unaffected by Gamma.

## Runtime switching path

```
UI/JNI thread : setThermalPalette / setThermalGamma → ThermalConfig (mutex)
Render thread : renderSoftwareYuvGlFrame → getThermalConfig() snapshot
                 → thermalMode 0/1/2 → renderI420(mode, gamma)
                 → glUseProgram(normal|white_hot|ironbow) → uniforms → draw
```

ORIGINAL ↔ WHITE_HOT ↔ IRONBOW during playback changes only the next frame's shader/state. No re-Prepare, no RTSP reconnect, no decoder/EGL/shader/LUT rebuild.

## Fallback behavior

- Ironbow shader or LUT texture init failure → clear `LOGE`, `ironbowProgram_`/`ironbowTexture_` stay 0 → render path falls back to White Hot, then to Normal. No crash, no black screen, no per-frame error spam.

## Normal preserved

YES

## White Hot preserved

YES (Slice 3 behavior unchanged)

## AGC implemented

NO

## Window/Range implemented

NO (no blackPoint/whitePoint, no 16~235 normalization)

## Stats

`getPlayerStats()` now emits:

```json
"thermalRenderMode": "ironbow",     // normal | white_hot | ironbow
"ironbowRenderedFrameCount": 1234
```

Existing `thermalPalette` / `thermalGamma` / `whiteHotRenderedFrameCount` reused. `playbackInfoTextView` shows the effective mode automatically (e.g. `Thermal ON | ironbow | AGC OFF | gamma 1.00 | render IRONBOW`). No new UI controls.

## Build result

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 6s
```

`git diff --check` clean. Only the 4 intended files modified; no binaries, no dependency/Gradle/AGP/NDK/SDK upgrades, no unrelated refactoring.

## Runtime verification

NOT_EXECUTED — no APK installed / no unknown test flow started. Device-based acceptance to confirm later:

- Thermal OFF / ORIGINAL → normal path fully preserved
- WHITE_HOT → Slice 3 behavior preserved
- IRONBOW → Y + Gamma + LUT → RGB thermal image; shader does not read U/V
- Runtime ORIGINAL → WHITE_HOT → IRONBOW → ORIGINAL; watch Decode/Render FPS, Dropped, `yuvGlFallbackFrameCount`, crash / GL errors

## Ready for Slice 5

YES
