# RTSP Latency — POST-LATENCY FINAL CLOSEOUT

Date: 2026-08-25

# Scope

This is the production-baseline and regression-guard closeout for LAT0-LAT6. It is not LAT8 and does not create another latency optimization slice.

LAT7 was blocked by the LAT6 hard gate and was subsequently explicitly waived by the user. The closeout therefore freezes the evidence-backed Android receiver architecture and diagnostics while preserving all external/E2E unknowns. It does not claim a validated E2E optimization, a proven external root cause, or minimum possible latency.

No playback code, RTSP parameter, MediaCodec option, renderer, frame-drop rule, synchronization/pacing rule, Audio behavior, Recording behavior, or Thermal behavior is changed in this closeout.

# LAT0-LAT7 Summary

| Phase | Frozen conclusion |
|---|---|
| LAT0 | FPS/timebase contract frozen; metadata FPS is not measured FPS; packet/frame gap and Codec2 `out=8` are not latency metrics. |
| LAT1 | Same media-timeline PTS/backlog diagnostics implemented; receiver media backlog is bounded and not E2E latency. |
| LAT2 | Same-frame, generation-safe T0-T4 monotonic timing implemented. |
| LAT3 | Bounded p50/p95/p99 receiver delay budget and health semantics frozen; compact Logcat visibility fixed. |
| LAT4 | Deferred: decoder already measured approximately one-frame class. |
| LAT5 | PRE-T0 read/return cadence isolated; no evidence of hundreds-ms sustained receiver accumulation. |
| LAT6 | Route-B architecture implemented, but real UDP/TCP runs received zero video SR and clock-sync error is unknown; `e2eValid=NO`. |
| LAT7 | `WAIVED_BY_USER`; no evidence-based optimization experiment or optimized E2E baseline. |

# Final Architecture

Validated explicit profile:

```text
RTSP / HEVC
    -> hevc_mediacodec
    -> nv12_cpu
    -> nv12_gl
    -> SurfaceView
```

Android receiver status: `ONE_FRAME_CLASS`.

Module ownership remains:

```text
app -> ffmpegplayer
```

- `ffmpegplayer` has no dependency on app source.
- Native ownership remains in `ffmpegplayer`.
- JNI registration package remains `com/example/motro/ffmpeg`.
- No package rename or API boundary change was performed.

# Final RTSP Configuration

The frozen production baseline is an explicit invocation profile, not a rewrite of the historical no-extra Demo defaults.

```text
transport=udp
latencyMode=balanced
maxDelayUs=100000
reorderQueueSize=4
bufferSize=262144
decoder=hevc_mediacodec
frameOutputType=nv12_cpu
renderer=nv12_gl
```

Additional balanced UDP values: `probesize=131072`, `analyzeduration=100000`, `maxProbePackets=128`, `fflagsNoBuffer=true`, `avioDirect=false`, `tcpNoDelay=true`, `decoderThreadCount=1`.

The Activity's existing no-extra Demo URL/transport/hardware defaults predate this closeout and remain compatible. Production callers must request the frozen explicit profile; this closeout does not silently change legacy launch behavior without LAT7 A/B evidence.

# Final Delay Budget

LAT6 UDP observation (`seq=73`, approximately 5 min 46 s):

| Segment | Final p50 | p95 | p99 |
|---|---:|---:|---:|
| PRE-T0 `av_read_frame` observable duration | 31.449 ms | 39.263 ms | 43.404 ms |
| PRE-T0 video return gap | 38.789 ms | 45.300 ms | 49.684 ms |
| Sender/External -> T0 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| T0 -> Decoder Output | 41.219 ms | 48.801 ms | 53.294 ms |
| Render Begin -> T4 | 2.943 ms | 5.055 ms | 7.103 ms |
| T0 -> T4 | 45.144 ms | 53.132 ms | 57.292 ms |
| Capture -> T4 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Physical Glass-to-Glass | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |

PRE-T0 receiver cadence is not treated as network latency. T4 is submit return, not display present.

Approximately 15-minute closeout observation (`seq=193` final rolling window):

| Segment | Final p50 | p95 | p99 |
|---|---:|---:|---:|
| PRE-T0 `av_read_frame` observable duration | 31.599 ms | 41.933 ms | 156.190 ms |
| PRE-T0 video return gap | 38.393 ms | 50.607 ms | 163.023 ms |
| Sender/External -> T0 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| T0 -> Decoder Output | 40.534 ms | 54.550 ms | 166.831 ms |
| Render Begin -> T4 | 2.365 ms | 8.116 ms | 17.284 ms |
| T0 -> T4 | 44.136 ms | 64.362 ms | 171.019 ms |
| Capture -> T4 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Physical Glass-to-Glass | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |

The final rolling-window p99 preserves a late tail burst. It is not a sustained-latency claim: p50 stayed stable, packet/frame cadence remained approximately 25 fps, media backlog stayed bounded, and timeout/error/reconnect remained zero.

# Root Cause

`PRIMARY_LATENCY_ROOT_CAUSE=NOT_ESTABLISHED`

`SECONDARY_LATENCY_ROOT_CAUSE=NONE`

Reason: LAT7 was waived, LAT6 E2E was invalid, Sender/Server code was unavailable, and physical glass-to-glass was not measured. The closeout does not convert an unknown external segment into a claimed encoder, server, network, or display root cause.

Proven receiver classification: decoder plus renderer remains one-frame class, with no evidence of sustained hundreds-ms accumulation after T0.

# Accepted Optimizations

`NONE`.

LAT0-LAT6 added diagnostics and correctness guards only. LAT7 performed no A/B optimization, so no parameter change qualifies as accepted.

# Rejected Optimizations

No runtime optimization experiment was executed. Aggressive `max_delay=0`, zero reorder depth, tiny buffers, extra frame dropping, MediaCodec retuning, and renderer replacement were not attempted because the E2E evidence gate was not satisfied.

# Diagnostics

Productionization status: `YES`, by audit; no new code was required.

- `FFmpegLatencyStats` retains `STATE`, `MEDIA`, `STAGE`, `PRET0`, `E2E`, and `HEALTH`.
- Compact logs reuse the exact `getStats()` snapshot.
- The Demo emits them at approximately 5 s cadence, not per frame.
- No latency compact logger exists in decode/render hot paths.
- Invalid distributions and E2E values remain `--` / invalid rather than fabricated zero.
- The full Stats API is retained without field deletion, rename, unit change, or semantic change.

# Regression Baseline

The regression contract is [RTSP_LATENCY_FINAL_BASELINE.md](RTSP_LATENCY_FINAL_BASELINE.md).

Guard mode: `OBSERVATION_BASELINE_ONLY`. No product SLA is invented from one device/source and an invalid E2E timebase.

# Health Contract

- `stageTimingForcedEvictionCount`: near zero; final observed `0`.
- `stageTimingClockAnomalyCount`: zero; final observed `0`.
- PTS backward: zero; final observed `0/0/0/0`.
- timing unmatched: startup-only bounded values are allowed; continuous growth is not.
- read stalls: isolated network/scheduler events may occur; sustained growth is a regression signal.
- timeout/reconnect: evaluated against real network state, not assumed zero in all environments.
- media backlog: must not grow across consecutive steady windows.
- primary invariant: `NO_SUSTAINED_LATENCY_ACCUMULATION`.

# Functional Regression

Evidence on device `34aff35a` with the real RTSP source:

| Function | Result | Evidence / limit |
|---|---|---|
| Create | PASS | real device created player handle |
| Prepare | PASS | RTSP open and HEVC MediaCodec decoder open succeeded |
| Start | PASS | first frame rendered; steady playback reached |
| Stop | PASS | UI Stop reached `STOPPED` and closed the input cleanly |
| restart | PASS | direct Start from `STOPPED` did not reopen input; the supported Stop -> Prepare -> Start sequence reopened the source and rendered the first frame in 60 ms |
| reconnect | NOT_EXECUTED | no safe controlled source/network failure was introduced; no organic reconnect occurred during the stable run |
| Surface initial attach | PASS | NV12 EGL context/surface created and rendered |
| Surface detach/attach | PASS | Surface detached with EGL context preserved, reattached at generation 5, and NV12 rendering resumed |
| Activity leave/return | PASS | existing task was backgrounded and brought forward; the same player remained `PLAYING` and rebound its Surface |
| Thermal Original | PASS | normal NV12 rendering used before and after thermal mode tests |
| Thermal White Hot | PASS | Thermal enabled successfully with White Hot selected; rendering remained approximately 25 fps |
| Thermal Ironbow | PASS | palette changed to Ironbow and playback UI reported `render IRONBOW`; Thermal was then disabled back to Original |
| Snapshot | PASS | PixelCopy produced a 1,656,117-byte PNG at 1798x1019 |
| Recording | PASS | recorder span 10.203 s; trailer finalized after 241 video packets; FFprobe parsed the 1,238,826-byte MP4 as 9.641 s with HEVC/AAC stream headers |
| Audio lifecycle | PARTIAL_SOURCE_LIMIT | enable created worker/sink with zero sink errors; source declared AAC but delivered zero audio packets, so audible payload output is not claimed |

# Recording Invariant

Code-path and runtime audit: `PASS`.

The architecture remains compressed source packet -> `PlayerRemuxRecorder` -> MP4/MOV/TS muxing. No decoder/re-encoder path was introduced. `audioEnabled=false` continues to control speaker monitoring only; recorder stream mapping remains independent. Runtime recording output validation is `PASS`.

Runtime evidence replaced the prior not-executed status: recording started with `audioPlaybackEnabled=0`, independently mapped the declared HEVC/AAC streams, waited for a keyframe, wrote 241 HEVC packets, and finalized the fragmented MP4 successfully. FFprobe parsed both HEVC and AAC stream headers and reported a 9.641 s container duration. The source emitted zero audio packets during the sample, so the architecture invariant passed but audio payload presence was not fabricated.

# Long-run Validation

Required 15-minute closeout run: `PASS_WITH_OBSERVED_TAIL_JITTER`.

The real final-profile run on device `34aff35a` completed approximately 15 minutes and showed:

- 24,055 packets / 24,047 decoded frames / 24,047 rendered frames;
- measured decode/render stayed approximately 25 fps (single final one-second sample was 24/23 fps);
- `hevc_mediacodec -> nv12_cpu -> nv12_gl` remained active with no renderer fallback;
- T0-to-T4 p50 stayed approximately 44-45 ms throughout;
- final rolling T0-to-T4 p50/p95/p99 was 44.136/64.362/171.019 ms after a late tail burst;
- final PRE-T0 read-duration p50/p95/p99 was 31.599/41.933/156.190 ms;
- read stalls were `103/0/0/0` for `>100/250/500/1000 ms`, with no timeout, EOF, or read error;
- attached-Surface steady health ended at forced eviction `0`, clock anomaly `0`, PTS backward `0/0/0/0`, timing unmatched `1/1` startup-only;
- generation remained `2`, reset count `2`, and no reconnect occurred;
- no valid E2E sample because `srCount=0`, `srValid=0`, and `e2eValid=NO`.

`LATENCY_ACCUMULATION=NO`. The tail warning is retained because the final p99 was elevated, but it did not become persistent queue/backlog growth. The later intentional Activity/Surface detach regression produced expected no-Surface/forced-eviction counts and is excluded from this steady attached-Surface baseline.

# Physical Glass-to-Glass

`NOT_MEASURED`.

No high-frame-rate external camera test was executed. `eglSwapBuffers` return is not used as a proxy for panel present time.

# Clean Debug Artifact Audit

- No LAT0-LAT7 per-frame compact Logcat path was found.
- No latency tuning parameter is forced by this closeout.
- No unused LAT7 branch or LAT8/LAT9 artifact exists.
- The existing Demo RTSP URL is a historical application default and is not a new closeout test hack; it is intentionally not rewritten without a product endpoint decision.
- The Level-3 visual pattern tool is retained as diagnostic tooling, not production runtime code.

# Remaining Unknowns

- whether the external sender emits video RTCP SR or the bundled FFmpeg path fails to export it;
- sender/server and receiver clock synchronization method and bounded error;
- sender, encoder, server, relay, and network absolute residence;
- post-T4 display present/scanout latency;
- physical glass-to-glass latency;
- controlled reconnect behavior under an injected transport/source failure;
- audible audio-payload behavior with a source that actually emits audio packets.

# Production Recommendation

Freeze and ship only the explicitly configured, already stable receiver profile when its loss tolerance matches the product environment. Keep the existing bounded diagnostics for regression observation. Do not advertise an E2E latency number or continue parameter tuning until a legal sender/RTP-to-wall mapping or external physical measurement exists.

Any future optimization must be a new independent request with its own baseline and authorization; it must not extend LAT0-LAT7.

# Build and Direct Tests

```text
PreT0TimingTrackerTest                         PASS
E2ETimebaseTest                               PASS
:app:testDebugUnitTest LatencyStatsFormatter  PASS
:ffmpegplayer:assembleDebug                    PASS
:ffmpegplayer:assembleRelease                  PASS
:app:assembleDebug                             PASS
```

The MSVC C4819 source-code-page warning for the E2E test is non-fatal and does not change the passing test result. Gradle deprecation notices are also non-fatal and unrelated to the closeout documents.

# Final Freeze

```text
LAT7=WAIVED_BY_USER
POST_LATENCY_CLOSEOUT=COMPLETE_WITH_EXTERNAL_DEPENDENCIES
RECEIVER_ARCHITECTURE_FREEZE=YES
RECEIVER_CONFIGURATION_FREEZE=YES
DIAGNOSTICS_FREEZE=YES
E2E_OPTIMIZATION_VALIDATED=NO
E2E_LATENCY=NOT_MEASURED
PHYSICAL_GLASS_TO_GLASS=NOT_MEASURED
RTSP_LATENCY_PROJECT=FROZEN
```

The `FROZEN` status means the diagnostic project and receiver production baseline are closed by explicit user waiver. It does not mean the external E2E latency or root cause was measured.
