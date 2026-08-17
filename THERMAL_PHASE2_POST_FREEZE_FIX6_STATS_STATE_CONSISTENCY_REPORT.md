# Problem

The player exposed requested configuration, discovered decode state, frame output,
and renderer state through overlapping fields whose update times did not match.
The most visible failures were:

- a newly created player reported `decodeBackend=software` even though no decoder
  existed;
- PREPARED could be interpreted as a renderer fallback even though no frame had
  reached a renderer;
- hardware-decoded CPU NV12, direct Surface output, OES output, and GL output did
  not share one precise decoder/output/renderer model;
- GL failure attempts and missing Surface events could contaminate fallback
  counters and reasons before a fallback frame had actually reached the user;
- OES and direct-Surface successes were not represented by the final output
  counter model;
- `swsScaleEnabled` described historical renderer state rather than whether the
  current active path needed `sws_scale`;
- timings that had never occurred were emitted as zero, which was
  indistinguishable from a measured sub-microsecond operation;
- dynamic format fields and stream metadata were copied at different locking
  boundaries;
- Thermal requested state could be displayed as active state before a renderer
  had successfully applied it;
- the Java overlay always labeled a legacy YUV GL counter, irrespective of the
  actual renderer.

This fix is limited to telemetry, state commits, counters, timing sentinels, and
the minimal overlay. It does not change decoder configuration, FFmpeg/MediaCodec
architecture, Surface/EGL ownership, Thermal algorithms, Snapshot routing, or
JNI registry ownership.

# Stats Inventory

The audit traced each public group to its writer and reset/lifetime boundary.
Existing JSON keys, including the opaque `handle`, were preserved. The compatible
additions are `swsScaleInvocationCount`, `videoFormatGeneration`, and
`thermalActiveRenderMode`.

| Public fields | Writer / source | Reset / lifetime | Final meaning |
|---|---|---|---|
| `state`, `playerState` | lifecycle state under `mutex_` | player lifecycle | current player state |
| `handle` | immutable `logicalHandle_` | player lifetime | opaque Fix 4 ID, never a pointer |
| `renderMode`, `requestedRenderer` | `PlayerOptions.renderMode` | option/session policy | requested render path |
| `requestedDecoderName` | decoder selection | Prepare/open | requested decoder |
| `actualDecoderName`, `decoderName`, `usingHardwareDecoder`, `decodeBackend` | successful decoder open | Prepare/reconnect | actual opened decoder/backend; unknown before open |
| `hardwareDecodeFallbackUsed`, `hardwareDecodeError` | decoder fallback result | new successful decoder open/session reset | decoder fallback only |
| `frameOutputType`, `frameFormat`, width/height/stride/range | decoded-frame format commit | first frame and every real change | last coherent decoded output description |
| `decodedFormatGeneration`, `videoFormatGeneration` | decoded-frame format commit | monotonic player lifetime | number of committed format versions |
| `renderer`, `renderFallbackReason` | packed renderer success state | successful render; reason cleared on recovery/detach/stop/reconnect | last successful renderer plus active fallback reason |
| `renderFallbackUsed` | requested/actual renderer + packed reason + Surface state | derived per snapshot | successful active renderer fallback only |
| decode counters | receive-frame success | `resetStats()` | decoded frames by actual backend |
| renderer counters | final render success | `resetStats()` | successful user-visible output by path |
| no-Surface counters | GL render skip | `resetStats()` | frames not output because Surface was absent |
| drop counters | explicit drop/final render failure sites | `resetStats()` | dropped frames; not a renderer fallback alias |
| sws fields | RGBA conversion timing | `resetStats()` | current active need plus historical invocation count |
| timing `last/avg/max` | measured operation success | `resetStats()` | `last=-1` before first measurement; aggregate zero before samples |
| Surface fields | renderer desired Surface and generation state | Surface lifecycle | current attachment and renderer synchronization state |
| Thermal configured fields | `ThermalConfig` | explicit Thermal configuration | desired enable/palette/AGC/window/gamma |
| Thermal active fields/counters | successful actual renderer | `resetStats()` / next successful renderer | applied render mode/input and successful Thermal frames |
| Snapshot capability fields | existing Fix 5 capability matrix | derived per snapshot | native RGBA vs Surface PixelCopy capability |
| reconnect/error fields | reconnect controller and decoder open result | recovery/start/stop boundaries | current reconnect/error condition, without stale render reason |

Hot-path counters remain atomics. A Stats request therefore provides a
non-blocking monotonic observation; adjacent counters may differ by one while a
frame commits concurrently. The decoder/output/format/renderer semantic core is
captured at bounded locking/atomic commit points and never uses a playback-wide
Stats lock.

# Final Semantic Model

The public model has four separate layers:

1. Request: `renderMode`, `requestedRenderer`, `requestedDecoderName`.
2. Decoder reality: `actualDecoderName`, `decodeBackend`,
   `usingHardwareDecoder`, `hardwareDecodeFallbackUsed`.
3. Decoded output reality: `frameOutputType` plus format, dimensions, stride,
   color range, and generation.
4. Final output reality: `renderer`, renderer counters,
   `renderFallbackUsed`, and `renderFallbackReason`.

Final renderer matrix:

| Requested mode | Normal frame output | Successful actual renderer |
|---|---|---|
| `software_rgba` | decoded CPU format, normally `yuv420p_cpu` | `rgba_nativewindow` after sws conversion |
| `software_yuv_gl` | `yuv420p_cpu` | `yuv_gl` |
| `mediacodec_nv12_gl` | `nv12_cpu` | `nv12_gl` |
| `mediacodec_surface` | `direct_surface` when MediaCodec produces an opaque Surface frame | `direct_surface`; if the current decoder/device returns a CPU frame, actual output/renderer report that observed CPU/RGBA path |
| `mediacodec_oes` | `external_oes` | `oes_gl` after the OES frame reaches the final Surface |

Invariants:

- Invariant 1: MediaCodec CPU NV12 does **not** count as software decode.
- Invariant 2: Surface unavailable does **not** count as render fallback.
- Invariant 3: Renderer fallback does **not** count as hardware decoder fallback.
- Invariant 4: PREPARED renderer unknown does **not** mean fallback.
- Invariant 5: Thermal configured does **not** necessarily mean Thermal actually rendered.
- Invariant 6: Snapshot PixelCopy does **not** mean renderer fallback.

# Decoder Semantics

`decodeBackend` is `unknown` until a decoder has successfully opened. It is
`mediacodec` only when `actualDecoderName` is the opened hardware decoder and
`usingHardwareDecoder=true`; otherwise an opened software decoder reports
`software`.

Decoder fallback preserves the requested render mode. It changes the actual
decoder/backend and sets `hardwareDecodeFallbackUsed` plus
`hardwareDecodeError`; it does not rewrite `renderMode`, does not set a renderer
fallback reason, and does not fabricate an actual renderer before a frame is
successfully output.

# Frame Output Semantics

`frameOutputType` describes the data emitted by the actual decoder, not the
configured renderer:

- `yuv420p_cpu`: CPU YUV420P planes;
- `nv12_cpu`: CPU NV12 planes, including MediaCodec output copied to CPU;
- `direct_surface`: opaque MediaCodec frame released directly to its Surface;
- `external_oes`: MediaCodec/OES frame consumed as an external texture;
- `unknown`: no real decoded frame has established the output yet.

`frameFormat` is likewise `unknown` before the first real frame. It is not
populated with a synthetic `mediacodec` value.

# Renderer Semantics

The actual renderer is committed only after successful final user output.
Renderer type and fallback reason are packed into one atomic runtime word, so a
Stats request cannot pair a newly committed renderer with an unrelated reason.

- RGBA NativeWindow success commits `rgba_nativewindow`.
- YUV GL swap success commits `yuv_gl`.
- NV12 GL swap success commits `nv12_gl`.
- OES final-Surface render success commits `oes_gl`.
- direct MediaCodec buffer release-to-Surface success commits
  `direct_surface`.

CREATED and PREPARED therefore report `renderer=unknown` until a final render
actually succeeds.

# Fallback Semantics

Decoder fallback and renderer fallback are independent dimensions.

`hardwareDecodeFallbackUsed` means the requested hardware decoder failed and a
software decoder was opened. `renderFallbackUsed` means the requested YUV/NV12
GL renderer failed or could not accept the format and a later RGBA render
successfully reached the attached Surface. The renderer fallback counter and
reason are committed only after that RGBA success.

Surface absence, PixelCopy capture, PREPARED renderer unknown, an attempted GL
render with no successful RGBA output, and a decoder fallback do not count as
renderer fallback. OES is not silently classified as YUV/NV12 renderer fallback.

# Counter Semantics

Final counter matrix:

| Counter | Final definition |
|---|---|
| `hardwareDecodedFrameCount` | successfully received frames from the actual MediaCodec decoder, including CPU NV12, direct Surface, and OES outputs |
| `softwareDecodedFrameCount` | successfully received frames from the actual FFmpeg software decoder only |
| `hardwareRenderedFrameCount` | **LEGACY compatibility:** successful direct MediaCodec release-to-Surface frames only |
| `softwareRenderedFrameCount` | **LEGACY compatibility:** successful non-direct CPU-backed RGBA or YUV GL output; it is a renderer counter, not proof of software decoding |
| `renderedFrameCount` | all successful user-visible final output across RGBA, YUV GL, NV12 GL, OES GL, and direct Surface |
| `yuvGlRenderedFrameCount` | successful YUV GL final output |
| `nv12GlRenderedFrameCount` | successful NV12 GL final output |
| `oesFrameRenderedCount` | successful OES final-Surface output |
| `nv12GlFallbackFrameCount` | frames for which NV12 GL failed and the RGBA fallback then successfully reached the user |
| `yuvGlFallbackFrameCount` | frames for which YUV GL failed/was unsupported and RGBA fallback then successfully reached the user |
| `yuvGlNoSurfaceFrameCount`, `nv12GlNoSurfaceFrameCount` | frames skipped because no Surface was available; never fallback counts |
| `droppedVideoFrameCount` | frames deliberately dropped or unable to produce final output; separate from fallback success |
| `frameDropBeforeRenderCount` | frames dropped by explicit pre-render policy only; final render errors no longer double-count here |

All session counters reset in `resetStats()` at the established session boundary.
Reconnect does not call that reset, so counters never move backward during one
logical player session.

# Legacy Fields

No existing JSON key was removed or renamed.
`hardwareRenderedFrameCount` is retained strictly as the direct-Surface legacy
counter. `softwareRenderedFrameCount` is retained for compatibility and is not
used by the overlay as a generic decoder/backend label. Consumers should use
`renderedFrameCount` for total final output and the renderer-specific counter
selected by `renderer` for path attribution.

`decodedFormatGeneration` remains available; `videoFormatGeneration` is an
equivalent clearer alias. `thermalRenderMode` remains compatible;
`thermalActiveRenderMode` is the explicit actual-state alias.

# sws_scale Semantics

`swsScaleEnabled=true` only when the player is in an active playback state, a
Surface is attached, and the actual successful renderer is
`rgba_nativewindow`, whose current path requires FFmpeg scaling/conversion.

It is false for normal NV12 GL, YUV GL, OES, direct Surface, CREATED, PREPARED,
STOPPED, and Surface-detached states. Historical use is represented by the new
`swsScaleInvocationCount`, sourced from the existing sws timing sample count.
`lastSwsScaleCostUs=-1` means no current/prior measured invocation in the
session; average/max remain zero until samples exist.

# Timing Semantics

For operation timings, `last=-1` means the operation has not occurred or the
current path explicitly does not use it. A measured value may legitimately be
zero microseconds. `avg=0` and `max=0` remain the empty-aggregate values.

The rule is applied to Prepare-to-Start, packet read, decoder send/receive,
sws, render breakdown, frame processing, and NV12 GL render/upload timings.
NV12 GL render/upload samples are committed only on successful output; missing
Surface and failed GL attempts do not fabricate a sample. Normal NV12 GL keeps
the sws sentinel at `-1`.

# Dynamic Format State

Width, height, pixel format, frame output type, Y stride, and color range are
compared and committed as one decoded-format change. Stats copies these core
fields under `mutex_`. Stream indices, codec names, audio metadata, and FPS are
also discovered into locals and committed under the same bounded mutex rather
than being serialized while discovery mutates them.

Device validation used a HEVC/TS asset that transitions from 1280x720 to
192x256. Stats moved coherently to `192x256`, `nv12`, `frameYStride=192`,
`frameOutputType=nv12_cpu`, with the generation incrementing.

# Format Generation

`videoFormatGeneration` is implemented as a compatible alias of
`decodedFormatGeneration`. The generation is monotonic for the player lifetime;
it is not reset by `openInput()` or reconnect. First-frame detection is based on
the absence of a committed decoded format, not on generation being zero.

The HTTP finite-source reconnect run observed generation values increasing
`2 -> 6 -> 10 -> 14` while counters also continued increasing. The local paced
run observed the 1280x720 to 192x256 generation transition without an EGL
context recreation.

# Surface State

`surfaceAttached` reports the desired display Surface known to the native
renderer. Clearing/backgrounding the Surface clears the active renderer
fallback reason and sets `swsScaleEnabled=false`, but preserves the last
successful renderer as historical runtime state.

Device detach evidence for NV12 GL:

- `surfaceAttached=false`;
- `renderFallbackUsed=false` and empty reason;
- `nv12GlFallbackFrameCount=0`;
- `nv12GlNoSurfaceFrameCount=44`;
- `swsScaleEnabled=false`;
- one EGL context was retained while Surface generations/surfaces advanced.

# Thermal State

Configured state (`thermalEnabled`, requested palette, AGC, gamma, window) is
separate from applied state (`thermalActiveRenderMode`, `thermalInputType`,
actual renderer-specific counters).

Applied mode/input are derived from the last successful actual renderer:
`nv12_gl -> nv12_y`, `yuv_gl -> yuv_planes`, `oes_gl -> oes_luminance`.
RGBA/direct output with Thermal configured reports active mode `unavailable`
and input `none`; it does not claim that a palette was applied. Thermal mode is
written only after a successful render. AGC validity additionally requires
Thermal enabled, AGC configured, a Thermal mode actually active, and the
renderer-specific runtime validity.

On device, Hardware NV12 GL with Ironbow showed `nv12ThermalRenderedFrameCount`
tracking successful NV12 output. The updated overlay reported `Thermal ON`,
configured `ironbow`, actual render `IRONBOW`, and input `NV12_Y`.

# Snapshot State

Fix 5 capability semantics are retained:

- `snapshotSupported=true` for end-user-capable native/PixelCopy modes;
- `nativeSnapshotSupported=true` only for the native RGBA capture mode;
- NV12 GL reports `snapshotCaptureMode=surface_pixelcopy`,
  `nativeSnapshotSupported=false`;
- PixelCopy is a capture route and never changes renderer/fallback state.

Hardware NV12 GL device Stats showed `snapshotSupported=true`,
`nativeSnapshotSupported=false`, `snapshotCaptureMode=surface_pixelcopy`, and
`renderFallbackUsed=false`, matching Fix 5.

# Reconnect State

Reconnect start clears an active renderer fallback reason but preserves
last-known decoder/output/renderer fields for diagnostics. A new successful
decoder open replaces decoder fallback/error state, and a new successful render
replaces renderer/fallback state. Counters and format generation remain
monotonic across reconnect.

The finite HTTP source exercised repeated EOF reconnects. Player state changed
to `RECONNECTING`; rendered/decode counters grew across sessions, generation
grew monotonically, and no stale renderer fallback reason appeared. A true RTSP
endpoint was not available for this run.

# Error / Fallback Reason Lifetime

- Decoder error/fallback fields are cleared by a later successful decoder open.
- Renderer fallback reason is cleared by normal renderer success, Surface
  detach, source metadata reset, reconnect start, stop, and stats reset.
- A GL failure sets only a pending reason; the public fallback becomes true only
  if RGBA output succeeds while the Surface is attached.
- Stop retains last-known decoder/output/renderer identity for diagnostics but
  clears active fallback state.
- `lastError` and reconnect fields remain owned by their existing lifecycle
  controllers and are not reused as renderer fallback reasons.

The no-Surface/reconnect recovery path was exercised. A deliberate nonzero GL
failure reason followed by successful GL recovery was code-audited but not
runtime-injected because the product has no safe failure hook.

# Lifecycle State Matrix

Actual fields are unknown until the corresponding runtime event has happened.
“Last-known” below is diagnostic state from the current session, not a claim
that the path is currently producing output.

| Lifecycle | `decodeBackend` / actual decoder | `frameOutputType` | `renderer` | `surfaceAttached` | `renderFallbackUsed` | `hardwareDecodeFallbackUsed` | `swsScaleEnabled` |
|---|---|---|---|---|---|---|---|
| CREATED / IDLE | `unknown` / empty | `unknown` | `unknown` | actual Surface binding | false | false | false |
| PREPARED | opened backend / actual decoder may be known | `unknown` | `unknown` | actual Surface binding | false | decoder-open result | false |
| PLAYING | actual opened backend/decoder | actual decoded output | last successful actual renderer | true when attached | true only after successful renderer fallback | decoder-open result | true only for active attached RGBA |
| PAUSED | actual/last-known | last-known | last successful renderer | current binding | active successful fallback only | decoder-open result | true only for attached RGBA path |
| SURFACE_DETACHED | actual/last-known | last-known | last successful renderer | false | false | decoder-open result | false |
| RECONNECTING | last-known until new open succeeds | last-known until new frame | last-known until new output | current binding | false at reconnect start | last-known until new decoder result | false unless an attached active RGBA path is re-established |
| STOPPED | last-known | last-known | last successful renderer | current binding | false | last decoder result | false |

# UI Overlay

The overlay now parses compatible JSON with `opt*` accessors and displays:

- player state;
- actual decoder and backend;
- frame output type;
- actual and requested renderer;
- dynamic resolution/format/stride/range;
- the counter selected for the actual renderer;
- separated decoder/renderer fallback state;
- Thermal configured and actual mode/input.

The old unconditional `yuv-gl rendered` label was removed. On hardware NV12 GL
the overlay now selects `nv12GlRenderedFrameCount`; direct Surface, OES, RGBA,
and YUV GL select their corresponding compatibility/path counter.

# Runtime Validation

Device: connected Android arm64 device `Bengal_for_arm64` (`adb` serial
`34aff35a`). Sources: local paced HEVC/TS and localhost HTTP finite HEVC/TS with
1280x720 -> 192x256 dynamic format.

| Scenario | Result |
|---|---|
| CREATED requested Hardware NV12 GL | PASS: backend/output/renderer unknown; requested renderer NV12 GL; both fallbacks false; timing sentinels valid |
| PREPARED Hardware NV12 GL | PASS: MediaCodec actual decoder known; output/renderer unknown; render fallback false |
| PLAYING Hardware NV12 GL | PASS: `mediacodec / nv12_cpu / nv12_gl`; hardware decode and NV12 render counters grow; software decode remains zero; sws false |
| Software decode + YUV GL | PASS: `software / yuv420p_cpu / yuv_gl`; software/YUV counters grow; hardware decode remains zero |
| Surface detach during NV12 GL | PASS: no-Surface counter grows; fallback remains zero/false; sws false; no fatal/EGL error observed |
| Dynamic 1280x720 -> 192x256 | PASS: format/stride/output update coherently; generation increments |
| Finite-source reconnect | PASS for generic reconnect state/counter/generation recovery; true RTSP not executed |
| Thermal Ironbow on NV12 GL | PASS: configured and actual state separated; actual Ironbow/NV12_Y and Thermal counter observed |
| Snapshot capability | PASS: Fix 5 `surface_pixelcopy` capability remains consistent and is not fallback |
| Renderer failure -> RGBA -> GL recovery | NOT_EXECUTED: no safe runtime failure injection hook; code-audited |
| Hardware decoder failure -> software fallback | NOT_EXECUTED: no safe decoder failure injection hook; code-audited |
| OES runtime | NOT_EXECUTED: existing out-of-scope OES Prepare/JNI issue recorded by Fix 5 |

Atomic counters can advance during JSON construction, so an in-flight snapshot
occasionally showed a one-frame difference between total and renderer-specific
counters. Each counter is monotonic and exact at its own success site; no hot
path/global Stats lock was added.

# Fix 1 Regression

PASS for scope and exercised behavior. Thermal algorithms, shaders, palette,
gamma, window, and AGC math were not changed. Thermal configuration replay
remains intact, and NV12 GL Ironbow rendered on device. The full historical AGC
matrix was not replayed.

# Fix 2 Regression

PASS for exercised behavior. No Surface/EGL ownership implementation was
redesigned. Dynamic-resolution NV12 playback retained one EGL context, Surface
detach/reattach did not become fallback, and no fatal/EGL access error was
observed. True RTSP source switching was not available.

# Fix 3 Regression

PASS for scope and exercised Prepare/Start behavior. Prepare reported actual
decoder with unknown output/renderer; Start produced the expected hardware and
software paths. No Prepare/Start input-lifecycle architecture was changed.

# Fix 4 Regression

PASS by scope/code audit and ordinary runtime use. Opaque handle `1` was exposed
in Stats, and repeated create/force-stop/device operations produced no stale
pointer, UAF, or fatal signal. The registry, operation guard, closing/drain, and
release architecture were not modified. The complete dedicated Fix 4 stress
matrix was not rerun.

# Fix 5 Regression

PASS for Stats/capability compatibility. No Snapshot implementation or routing
was changed. Hardware NV12 GL continued to advertise Surface PixelCopy support
without enabling sws or renderer fallback. The complete Fix 5 screenshot/race
matrix was not rerun; its existing OES limitation remains.

# Build

- `gradlew.bat :app:assembleDebug`: PASSED after implementation and again in the
  final pre-commit run (`BUILD SUCCESSFUL in 4s`).
- Final `git diff --check`: PASSED (only Git's informational LF-to-CRLF working
  copy warnings were emitted).
- CMake/NDK native compilation and Java compilation passed for the app debug
  artifact.
- Repository search found no directly related Stats/NativePlayer unit or
  instrumentation test; the existing device matrix above is the direct test.
- No dependency, Gradle, FFmpeg, MediaCodec, renderer, or ABI upgrade was made.

# Remaining Issues

- A true RTSP reconnect endpoint was unavailable; finite HTTP EOF reconnect was
  used for generic recovery evidence.
- There is no safe product hook to inject a renderer failure/recovery or hardware
  decoder-open failure, so those nonzero-fallback transitions were code-audited
  rather than runtime-forced.
- The pre-existing OES Prepare/JNI issue documented by Fix 5 remains out of
  scope; OES runtime counters were not revalidated here.
- Monotonic atomic counters are intentionally non-blocking and may show a
  one-frame cross-counter skew when queried during a concurrent frame commit.
- The full prior Fix 4 lifetime-stress and Fix 5 snapshot/race matrices were not
  repeated in this telemetry-only fix.

# Fix Runtime Verified: NO

The implementation and exercised primary Hardware NV12 GL, Software YUV GL,
Surface, dynamic-format, Thermal, Snapshot-capability, and reconnect semantics
pass. The final flag remains **NO** because the safe renderer/decoder failure
injection cases, true RTSP validation, and complete prior Fix 4/Fix 5 runtime
matrices were not all executed in this run. This is a validation-coverage limit,
not a known failure of the implemented Stats semantics.
