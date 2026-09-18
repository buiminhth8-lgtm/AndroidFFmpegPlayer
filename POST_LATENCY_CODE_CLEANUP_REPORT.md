# POST-LATENCY Production Code Cleanup Report

# Scope

This cleanup closes LAT0-LAT7 production diagnostics work without retuning the
player. The audit used `RTSP_LATENCY_FINAL_CLOSURE_REPORT.md`,
`RTSP_LATENCY_FINAL_BASELINE.md`, the LAT5 PRE-T0 report, and the LAT6 final E2E
report. A LAT7 freeze report does not exist because LAT7 was explicitly waived;
the final closure and baseline reports record that decision.

The final invocation remains RTSP UDP / BALANCED with `maxDelayUs=100000`,
`reorderQueueSize=4`, and `socketBufferSize=262144`. The verified video path
remains `hevc_mediacodec -> nv12_cpu -> nv12_gl -> SurfaceView`.

Audit classification:

| Class | Classification | Result |
|---|---|---|
| A | PRODUCTION_CORE | Player/JNI, FFmpeg demux/decode, renderers, PlayerOptions, recorder, Audio, Thermal, snapshot and lifecycle APIs kept |
| B | CORRECTNESS_GUARD | Generation/reset, reconnect/source isolation, Surface generation, EGL owner thread, JNI lifetime, prepare/start reuse, queue bounds and stale-frame guards kept |
| C | BASIC_DIAGNOSTICS | State, backend/output/renderer, FPS, packet/frame/drop, read error/timeout, reconnect, media backlog, packet-to-render and last error retained |
| D | ADVANCED_LATENCY_DIAGNOSTICS | LAT1/LAT2/LAT3 distributions, PRET0 and LAT6 E2E/RTCP retained behind LATENCY mode and consolidated under `native/diagnostics/` |
| E | TEST_ONLY | Host C++ helpers/tests, JVM formatter tests and the latency test pattern retained |
| F | EXPERIMENTAL_DEAD_CODE | One-off Probe UI, its Activity handler, temporary per-stall Logcat and redundant BASIC log streams removed/consolidated |

# Removed Experimental Code

Removed:

- `app/src/main/res/layout/view_media_player_controls.xml`: one-off Probe button.
- `app/src/main/java/com/example/motro/MediaPlayerActivity.java`: Probe click handler.
- `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp`: per-read `read stall detected` Logcat emission.

Kept:

- The public/native Probe API, because removing an existing callable API would
  violate compatibility even though the one-off Demo entry point is gone.
- RTSP transport and latency profile controls, because they are production
  configuration rather than dead A/B switches.
- The historical Demo URL default, because history and the final closure report
  identify it as the normal Demo default rather than a LAT0-LAT7 override; the
  runtime test also passed the URL through the supported Intent extra.

Reason:

Only code confirmed as class F was deleted. No forced transport branch,
hard-coded latency override, dead decoder/renderer branch, large commented-out
block, or unused JNI wrapper was found that could be removed without changing
behavior or API compatibility.

# Preserved Correctness Code

Kept:

- player/source/video/audio/E2E generation changes and reset ordering;
- reconnect source isolation, WAITING_SOURCE/RECONNECTING/RECONNECTED behavior,
  fresh-keyframe wait and discontinuity reset;
- Surface generation and attach/detach behavior;
- EGL owner-thread and renderer-surface generation protection;
- JNI handle/lifetime/release guards;
- prepare/start input reuse and stop/restart state behavior;
- bounded audio, latency-distribution and stage-correlation storage;
- stale-frame, latest-frame and frame/packet-drop correctness guards;
- compressed-packet recorder independence from speaker monitoring;
- Thermal state replay and snapshot lifecycle.

Reason:

These paths protect production ownership, continuity, and state correctness.
Their diagnostic origin does not make them experimental.

# Basic Diagnostics

BASIC is the production default. It retains low-overhead online health:

- player state;
- decoder backend, frame output and renderer;
- measured decode/render FPS;
- video packet, decoded, rendered and dropped counts;
- read timeout/error and reconnect-success counts;
- current client media backlog;
- packet-ready to render-submit last value;
- last error.

PRET0 BASIC collection records only read-result classes. It does not add timing
samples or calculate percentiles. Stage capture records only the bounded T0/T4
pair needed for packet-to-render health. The Demo emits one compact BASIC health
line every five seconds.

# Advanced Latency Diagnostics

`ffmpegplayer/src/main/cpp/native/diagnostics/PlaybackDiagnostics.h` is the
single owner of mode policy, bounded percentile storage, PRET0 aggregation,
RTCP sender-report mapping and E2E sample state. Supporting math/helpers live in
the same directory:

- `DiagnosticsMode.h`
- `LatencyDistribution.h`
- `PreT0TimingTracker.h`
- `E2ETimebase.h`

NativePlayer keeps lightweight scalar timestamp/generation hooks next to the
events whose identity they establish. No JSON, Logcat string construction,
percentile sort or unbounded allocation occurs in decoder/render hot paths.
Complete STATE/MEDIA/STAGE/PRET0/E2E/HEALTH logging is enabled only in LATENCY.

# Diagnostics Mode

The existing generic `setPlayerOption` API accepts:

- `diagnostics_mode=off`: no latency logs and no advanced percentile/PRET0/E2E work;
- `diagnostics_mode=basic`: production health counters and one low-frequency health line;
- `diagnostics_mode=latency`: full LAT1/LAT2/LAT3/PRET0/E2E diagnostics and six compact lines.

No new public enum or incompatible JNI method was added. `getStats()` exposes the
additive `diagnosticsMode` field. The native and Demo defaults are both BASIC.

# Hot Path Cleanup

Removed:

- the per-stall Logcat call from the `av_read_frame` loop;
- BASIC/OFF wall-clock E2E capture and RTCP/PRFT processing;
- BASIC/OFF percentile sample insertion and snapshot sorting;
- BASIC/OFF intermediate T1/T2/T3 stage capture;
- BASIC full Stats JSON, full latency-line set and Audio lifecycle logging.

Kept:

- scalar counters used for production health;
- bounded T0/T4 correlation in BASIC;
- existing decode/render cost counters and playback correctness clocks;
- low-frequency logging cadence.

Reason:

The retained hooks are bounded scalar work. The deleted/constrained work did not
affect playback decisions and had no value in the production BASIC hot path.

# Stats API Compatibility

All existing Stats JSON field names, units, invalid values, validity flags and
p50/p95/p99 semantics remain present. Advanced distributions/E2E snapshots are
empty or invalid while their mode is disabled rather than being renamed or
fabricated; compatible online/basic fields retain their established lifecycle
semantics. The only schema change is the additive string field `diagnosticsMode`.

The public Probe API and all player APIs remain callable. JNI package ownership
remains `com/example/motro/ffmpeg`; `ffmpegplayer` does not depend on `app`.

# Demo Cleanup

The Demo retains Create, Prepare, Start, Pause, Stop, Release, reconnect,
transport/profile selection, Thermal, snapshot, recording, Info, State and Stats.
It removes only the one-off Probe UI and adds OFF/BASIC/LATENCY selection. BASIC
is checked by default and is applied on both Create and Prepare; runtime switching
is supported.

Before cleanup, every five-second BASIC tick emitted full Stats JSON, six complete
latency lines, and one Audio lifecycle line. After cleanup it emits one BASIC
health line: seven redundant scheduled emissions per tick are removed. Full
detail remains available in LATENCY.

# Tests Preserved

No regression test was deleted. Includes were updated for the diagnostics folder.
Added coverage verifies:

- BASIC default and OFF/BASIC/LATENCY parsing;
- BASIC outcome-only PRET0 behavior without distributions;
- LATENCY distribution hooks;
- BASIC compact health formatting and absence of p50/E2E content.

Executed tests:

| Test | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS |
| `DiagnosticsModeTest.cpp` (MSVC C++17) | PASS |
| `PreT0TimingTrackerTest.cpp` (MSVC C++17) | PASS |
| `E2ETimebaseTest.cpp` (MSVC C++17) | PASS |

# Final Player Architecture

```text
app Demo
  -> ffmpegplayer Java API
    -> JNI (com/example/motro/ffmpeg)
      -> NativePlayer correctness/lifecycle pipeline
        -> FFmpeg demux -> MediaCodec HEVC -> NV12 CPU -> NV12 GL -> SurfaceView
        -> compressed packets -> PlayerRemuxRecorder -> MP4/MOV/TS
        -> lightweight diagnostics hooks
          -> PlaybackDiagnostics (OFF / BASIC / LATENCY)
             -> bounded health/PRET0/stage distributions
             -> LATENCY-only RTCP/PRFT/E2E mapping
```

# Functional Regression

Real-device target: `34aff35a` (`Bengal_for_arm64`). Source:
`rtsp://192.168.1.101:556/main.mov`, UDP/BALANCED, hardware decode,
`mediacodec_nv12_gl`.

| Check | Result |
|---|---|
| Install / Activity launch | PASS |
| Create | PASS |
| Prepare | PASS; HEVC MediaCodec opened, video/audio streams found |
| Start / first frames / steady playback | PASS |
| Stop | PASS |
| Frozen pipeline | PASS; `hevc_mediacodec -> nv12_cpu -> nv12_gl`, no fallback |
| BASIC default | PASS; one health line, approximately 25 fps, zero drops/errors/timeouts/reconnects |
| LATENCY mode | PASS; six complete compact lines and detailed Stats/Audio logs |
| OFF mode | PASS; 11-second window had no latency/full-Stats/Audio diagnostics while playback continued |
| E2E invalid semantics | PASS; `mode=none`, `srCount=0`, `valid=0`, `--` latency |

The cleanup does not modify recorder, Audio, Thermal, snapshot, Surface or
reconnect implementation. Their existing closure regression tests and behavior
were preserved; targeted runtime exercised the changed Demo/player/diagnostics
surface only.

# Performance Regression

Comparable cleanup run observations:

| Metric | Frozen baseline | Cleanup runtime | Result |
|---|---:|---:|---|
| decode/render FPS | approximately 25 | 24.9-26.0 | PASS |
| dropped frames | zero steady-state | 0 | PASS |
| T0 -> T4 p50 | 45.144 ms | 45.765 ms | PASS; same one-frame class |
| PRET0 read p50 | 31.449 ms | 32.108 ms | PASS |
| PRET0 video gap p50 | 38.789 ms | 38.651 ms | PASS |
| timeout/error/reconnect | 0/0/0 | 0/0/0 | PASS |
| renderer fallback | false | false | PASS |

The LATENCY sample was intentionally short and is not a new baseline or SLA.
BASIC removes E2E, distribution, intermediate stage and multi-line log overhead;
OFF removes optional diagnostic collection/logging while preserving playback.

# Build

| Gate | Result |
|---|---|
| `git diff --check` | PASS |
| `:ffmpegplayer:assembleDebug` | PASS (arm64-v8a, armeabi-v7a) |
| `:ffmpegplayer:assembleRelease` | PASS (arm64-v8a, armeabi-v7a) |
| `:app:assembleDebug` | PASS |

# Remaining Technical Debt

- The production source emitted no usable RTCP SR/PRFT anchor, so external
  sender/capture-to-T0 latency remains NOT_MEASURED. The code is LATENCY-only and
  retains invalid semantics rather than fabricating a value.
- The Demo still requests the compatibility `getStats()` JSON every second to
  render its information panel. That work is off the decoder/render threads; a
  separate minimal health snapshot would require a future API design and was
  intentionally not introduced during compatibility cleanup.

# Cleanup Freeze

POST-LATENCY CLEANUP is frozen. Playback behavior, latency profile, decoder,
renderer, recording, Audio, Thermal, snapshot, reconnect and Surface lifecycle
are unchanged. Experimental class-F UI/log code is removed, advanced diagnostics
are isolated and opt-in, tests are preserved, all build gates pass, and no LAT8
or latency retuning work is authorized by this cleanup.
