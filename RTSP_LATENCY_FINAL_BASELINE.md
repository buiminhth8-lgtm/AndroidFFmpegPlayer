# RTSP Latency Final Production Baseline

Date: 2026-08-25

Status: `OBSERVATION_BASELINE_ONLY`

Closeout policy: LAT7 was explicitly waived by the user. This document freezes the evidence-backed Android receiver profile and its regression observations. It does not claim a validated end-to-end optimization, an E2E SLA, or a physical glass-to-glass SLA.

# Pipeline

Validated receiver profile:

```text
RTSP / HEVC
    -> hevc_mediacodec
    -> nv12_cpu
    -> nv12_gl
    -> SurfaceView / eglSwapBuffers return
```

Observed stream: 1280x720 HEVC, RTP clock 90000 Hz, metadata FPS 21, measured decode/render approximately 25 fps.

This is an explicit invocation profile. The Launcher Activity's historical no-extra Demo defaults are not changed by this closeout; production callers must select the validated profile explicitly.

# Final RTSP Configuration

| Field | Frozen value | Evidence |
|---|---:|---|
| transport | `udp` | LAT5/LAT6 real-device baseline |
| latencyMode | `balanced` | LAT5/LAT6 real-device baseline |
| openTimeoutUs | `5000000` | `PlayerOptions::applyLatencyModeProfile` |
| readTimeoutUs | `5000000` | configured value; historical audit notes that not every timeout dictionary key is consumed |
| probesize | `131072` | balanced UDP profile |
| analyzeduration | `100000` | balanced UDP profile |
| maxProbePackets | `128` | balanced UDP profile |
| maxDelayUs | `100000` | configured and read back from `AVFormatContext::max_delay` |
| reorderQueueSize | `4` | configured and consumed by the successful UDP RTSP open; public effective-depth readback unavailable |
| bufferSize | `262144` | balanced UDP socket buffer configuration |
| fflagsNoBuffer | `true` | balanced UDP profile |
| avioDirect | `false` | balanced UDP profile |
| tcpNoDelay | `true` | balanced profile |
| lowDelayDecode | `true` | balanced profile |
| decoderThreadCount | `1` | balanced profile |
| dropLateFrameThresholdUs | `300000` | existing balanced UDP profile; unchanged by LAT0-LAT7 closeout |

No RTSP option, decoder option, renderer option, frame-drop rule, sync/pacing rule, reconnect rule, Audio behavior, Recording behavior, or Thermal behavior is changed by this closeout.

# Decoder and Render Configuration

| Field | Frozen value |
|---|---|
| decoder | `hevc_mediacodec` |
| decode backend | `mediacodec` |
| frame output | `nv12_cpu` |
| renderer | `nv12_gl` |
| renderer fallback observed | `false` |
| measured decode FPS | approximately `25` |
| measured render FPS | approximately `25` |

# LAT1 Media Backlog Baseline

Final LAT6 UDP snapshot (`seq=73`, bounded observation window):

| Metric | p50 | p95 | p99 |
|---|---:|---:|---:|
| demux-to-decoder media backlog | 0.000 ms | 39.800 ms | 40.800 ms |
| decoder media backlog | 40.000 ms | 41.200 ms | 42.400 ms |
| render media backlog | 0.000 ms | 0.000 ms | 0.000 ms |
| client media backlog | 40.000 ms | 79.900 ms | 80.600 ms |

These are media-timeline backlog observations, not end-to-end latency and not values to add to T0-to-T4 residence.

# LAT3 Receiver T0-to-T4 Baseline

LAT6 UDP snapshot after approximately 5 min 46 s:

| Local monotonic stage | p50 | p95 | p99 |
|---|---:|---:|---:|
| T0 demux return -> decoder submit | 0.030 ms | 0.052 ms | 0.167 ms |
| decoder submit -> output | 41.219 ms | 48.801 ms | 53.294 ms |
| decoded output -> render begin | 0.012 ms | 0.019 ms | 0.073 ms |
| render begin -> T4 submit return | 2.943 ms | 5.055 ms | 7.103 ms |
| T0 -> T4 | 45.144 ms | 53.132 ms | 57.292 ms |

Receiver status: `ONE_FRAME_CLASS`.

T4 is render-submit / `eglSwapBuffers` return. It is not physical display present time.

The approximately 15-minute closeout run retained a stable T0-to-T4 p50 while exposing a late rolling-window tail burst:

| Local monotonic stage | p50 | p95 | p99 |
|---|---:|---:|---:|
| T0 demux return -> decoder submit | 0.027 ms | 0.045 ms | 0.157 ms |
| decoder submit -> output | 40.534 ms | 54.550 ms | 166.831 ms |
| decoded output -> render begin | 0.011 ms | 0.020 ms | 0.054 ms |
| render begin -> T4 submit return | 2.365 ms | 8.116 ms | 17.284 ms |
| T0 -> T4 | 44.136 ms | 64.362 ms | 171.019 ms |

This tail is retained as a regression observation, not hidden or promoted to an SLA. The stable p50, steady frame cadence, bounded media backlog, and zero timeout/error/reconnect counters show no sustained receiver accumulation.

# LAT5 PRE-T0 Observable Baseline

Final LAT6 UDP snapshot:

| Observable behavior | p50 | p95 | p99 |
|---|---:|---:|---:|
| `av_read_frame` call duration | 31.449 ms | 39.263 ms | 43.404 ms |
| video packet return gap | 38.789 ms | 45.300 ms | 49.684 ms |

Average RTP/video PTS delta was approximately 41.714 ms in the final bounded window. Read duration and return gap are receiver observations; neither is labeled network latency.

The final rolling window of the approximately 15-minute run observed `av_read_frame` duration 31.599/41.933/156.190 ms and video return gap 38.393/50.607/163.023 ms (p50/p95/p99). These late tail values correlate with the T0-to-T4 tail burst and remain receiver-side observables, not proof of network residence.

# LAT6 End-to-End Baseline

| Metric | p50 | p95 | p99 | Status |
|---|---:|---:|---:|---|
| Sender/external -> Receiver T0 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | `e2eValid=NO` |
| Capture -> T4 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | no capture timestamp |
| Physical glass-to-glass | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | external visual test not executed |

Runtime evidence:

- UDP: approximately 5 min 46 s, 8,751 video packets, `srCount=0`.
- TCP-interleaved diagnostic: approximately 107 s, 2,682 video packets, `srCount=0`.
- UDP closeout: approximately 15 min, 24,055 packets and 24,047 decoded/rendered frames, `srCount=0`.
- sender/server source: unavailable in this repository;
- sender/receiver clock synchronization error bound: unknown.

No sender-to-T0 value may be inferred from RTP PTS, receiver monotonic time, receiver auto-time, PRE-T0 cadence, or T0-to-T4 timing.

# LAT7 Baseline

`WAIVED_BY_USER`.

No LAT7 optimization A/B experiment was executed, no optimization was accepted, and no optimized E2E baseline exists.

# Expected Health Counters

The approximately 15-minute attached-Surface steady observation ended with:

| Counter | Observed / expected behavior |
|---|---|
| `stageTimingForcedEvictionCount` | `0`; should remain near zero |
| `stageTimingClockAnomalyCount` | `0`; expected zero |
| PTS backward counters | `0/0/0/0`; expected zero |
| decoder/render timing unmatched | `1/1`; startup-only and stable, must not grow continuously |
| E2E clock mapping anomaly | `0` |
| E2E same-frame unmatched | `0` while no SR anchor exists |
| E2E resets | `2`; generation lifecycle value, must only grow on legitimate reset events |
| read stalls | `103/0/0/0` for `>100/250/500/1000 ms`; the late `>100 ms` burst is retained as a warning, while zero `>250 ms`, timeout, error, and reconnect plus stable p50 show no accumulation |
| timeout / read error | `0 / 0` in the final UDP run |
| reconnect | no reconnect during the final UDP steady run |
| media backlog | bounded; must not show sustained growth |

Primary health invariant: `NO_SUSTAINED_LATENCY_ACCUMULATION`.

Lifecycle note: intentionally detaching the Surface while playback continued caused timing samples with no render consumer and raised forced-eviction/no-Surface counters. The counters returned to normal behavior after attach. Regression comparisons for the steady baseline must exclude deliberately detached-Surface intervals.

# Regression Guard

Mode: `OBSERVATION_BASELINE_ONLY`.

No product SLA or hard numeric failure threshold is introduced because there is no LAT7 E2E A/B dataset, no multi-device/source distribution, and no physical-present measurement. Regression review must compare like-for-like source, device, transport, latency profile, decoder, renderer, and network conditions.

Warning conditions:

- measured decode or render FPS persistently below the approximately 25 fps source cadence;
- T0-to-T4 no longer remains one-frame class under comparable conditions;
- forced eviction, clock anomaly, PTS backward, or timing-unmatched counters grow continuously;
- read stalls, timeouts, or reconnects increase persistently on an otherwise stable network;
- client media backlog grows across successive bounded windows;
- renderer falls back from `nv12_gl` or frame output changes from `nv12_cpu`;
- any invalid E2E field is rendered as zero rather than invalid / `--`.

# Diagnostics Contract

- `getStats()` and all LAT0-LAT6 fields, names, units, and semantics remain compatible.
- `FFmpegLatencyStats` retains `STATE`, `MEDIA`, `STAGE`, `PRET0`, `E2E`, and `HEALTH`.
- The Demo polls Stats every 1 s and emits compact diagnostics only every fifth poll (approximately every 5 s).
- Diagnostics run on the Stats path, not per frame and not in decoder/render hot paths.
- No additional production logging switch is introduced because the existing cadence already satisfies the closeout rule.

# Startup and Reconnect Contract

Startup freshness flush, first-keyframe wait, EGL/MediaCodec warm-up, and initial unmatched samples are not steady-state regressions. Reconnect, source switch, and format discontinuity must advance generations and reset PTS, stage timing, and E2E mappings so old-session samples cannot cross into a new session.

# Remaining Dependencies

- legal runtime access to video RTCP Sender Reports or cooperative sender instrumentation;
- bounded NTP/PTP synchronization evidence for sender/server and receiver;
- a controlled reconnect/failure-injection run; the stable closeout run had no organic reconnect event;
- an RTSP source that actually delivers audio packets; this source declared AAC but reported `audioPacketCount=0`;
- physical glass-to-glass validation.
