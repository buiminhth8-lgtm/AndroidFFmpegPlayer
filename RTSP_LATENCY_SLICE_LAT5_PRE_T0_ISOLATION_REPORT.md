# RTSP Latency — Slice LAT5 — RTSP / RTP Pre-T0 Isolation

Date: 2026-08-20
Branch: dev (5841bba + LAT5)
Scope: PRE-T0 MEASUREMENT + ISOLATION — quantify `av_read_frame()` call
behavior, video packet return cadence, PTS-delta cross-check, burst/stall/
error classification, and audit RTSP/RTP buffering option application. NOT a
low-latency optimization slice. No default RTSP/MediaCodec/renderer/sync/
pacing/Audio changes. LAT6 (sender timestamp) is NOT started.

---

# Scope

Allowed and implemented:

- `av_read_frame` timing diagnostics (R0/R1, readCallDurationUs)
- video packet return timing diagnostics (VIDEO_PACKET_DEMUX_RETURN_GAP)
- video PTS delta cross-check
- read stall / timeout / error diagnostics
- fast-return burst diagnostics
- RTSP option audit (configured/accepted/applied/unused)
- Stats fields + compact `FFmpegLatencyStats` PRET0 line
- direct tests (C++ host tests for the timing helper, JVM formatter tests)
- Report

Prohibited (all untouched): default `max_delay`/`reorder_queue_size`/
`buffer_size`/`fflags` tuning, frame drop changes, MediaCodec tuning, renderer
tuning, A/V sync changes, pacing changes, reconnect redesign, sender timestamp
implementation, RTCP Sender Report mapping, SurfaceFlinger measurement, Audio
investigation, encoder modification.

# Why LAT4 Was Deferred

`LAT4_DEFERRED_BY_LAT3_EVIDENCE`: LAT3 runtime confirmed decoder submit ->
output ≈ one frame class (p50 ≈ 41-42 ms, p95 ≈ 51-52 ms), with no hidden
post-demux queue growth. Decoder isolation is therefore LOW / OPTIONAL and is
not performed in this slice (no HW/SW decoder comparison, no MediaCodec
adjustment).

# LAT3 Frozen Baseline

From LAT3 + LAT3 Diagnostics Log-output Fix (verified on the same device
during this slice):

- Pipeline: RTSP/HEVC -> hevc_mediacodec -> nv12_cpu -> nv12_gl -> SurfaceView
- measured decode/render ≈ 25 fps (24.9-25.8 observed)
- client media backlog ≈ 40 ms (typical), occasional ≈ 80 ms
- post-demux packetReady->renderSubmit p50 ≈ 45 ms, p95 ≈ 54-56 ms,
  p99 ≈ 59-64 ms
- decoder residence p50 ≈ 41-42 ms; render begin->submit p50 ≈ 2.6 ms
- diagnostics health: unmatched=0, forcedEvict=0, clockAnomaly=0,
  ptsBackward=0/0/0/0

# Pre-T0 Measurement Boundary

```
R0 = steadyNowUs() just before av_read_frame()
R1 = steadyNowUs() just after av_read_frame() returns
readCallDurationUs = R1 - R0        (avReadFrameDurationUs / readWaitAndDemux)
```

- Same `std::chrono::steady_clock` (CLOCK_MONOTONIC) as LAT2. No
  CLOCK_REALTIME / System.currentTimeMillis / media PTS used for durations.
- `readCallDurationUs != networkLatencyUs`. It may include waiting for socket
  data, FFmpeg protocol layer, RTP depacketization, reorder/jitter handling,
  RTSP demux processing, and internal buffered-packet availability. For a
  realtime 25 fps pull loop a read of ≈ 40 ms can simply be waiting for the
  next packet.
- Video return gap is `currentVideoReturnMonoUs - previousVideoReturnMonoUs`
  measured at T0 (packet-ready), i.e. VIDEO_PACKET_DEMUX_RETURN_GAP — not a
  socket arrival gap.

# av_read_frame Timing

All read calls are timed (video, audio, other); the bounded
`LatencyDistribution` (LAT3 helper, window 1024) provides steady-state
percentiles. Per-call values are aggregated inside `PreT0TimingTracker`; no
per-packet log output.

UDP balanced steady window (device evidence, seq=48, 20:24):

```
readMs = 31.955 / 40.985 / 48.371   (p50/p95/p99)
```

The all-call read distribution includes non-video reads; the video-only
cadence is captured by the return-gap distribution (below).

# Video Packet Return Cadence

UDP balanced steady window:

```
videoGapMs = 38.928 / 48.070 / 56.839   (p50/p95/p99)
```

Video packets return from `av_read_frame` at ≈ 39-48 ms spacing, matching a
25 fps source cadence (40 ms nominal).

# Video PTS Delta

Consecutive valid video packet PTS deltas (strictly increasing, reorder-safe):

```
avgVideoPacketPtsDeltaUs ≈ 40025 (40.0 ms)   (UDP steady window)
```

PTS delta ≈ return gap ≈ 40 ms -> the demux return cadence follows the source
media cadence; no evidence of the application draining a pre-buffered burst.

# Burst Detection

- `fastVideoReturnThresholdUs = 5000` (5 ms). A 25 fps stream returns a video
  packet every ≈ 40 ms; 5 ms is clearly below one frame (< 1/8 cadence) and
  above scheduling noise. Chosen in code, not from this prompt's example.
- Burst length = number of consecutive video packets returned with fast gaps
  (first fast gap starts a burst of 2; a slow gap resets the current burst).

UDP steady window: `fastReturnPacketCount = 0`,
`maxFastReturnBurstLength = 0` -> SMOOTH cadence, no burst delivery observed
at the demux return point.

# Stall / Timeout / Error Diagnostics

Buckets: >100 ms, >250 ms, >500 ms, >1000 ms; plus `maxReadStallUs` and
classified errors (EAGAIN / ETIMEDOUT / EOF / other).

UDP steady window: `stall = 0/0/0/0`, `maxReadStallUs = 0`, `eagain = 0`,
`timeout = 0`, `eof = 0`, `error = 0`. The existing 1 s "read stall detected"
LOGE is retained unchanged.

# RTSP Option Audit

Method: the player already logs every option left unconsumed in the
AVDictionary after `avformat_open_input` ("unused FFmpeg open option
key=value"). A key that is gone from the dictionary was consumed by FFmpeg
(`APPLIED_BY_OPTION_CONSUMPTION`); a key that remains is `UNUSED`.

IMPORTANT: only the SUCCESSFUL UDP open (20:23:15) is valid consumption
evidence. Failed opens (camera "No route to host") leave most options in the
dict because the demuxer aborts before consuming them — those unused lists are
explicitly NOT treated as evidence.

| Option | CONFIGURED (balanced) | UDP successful open | TCP failed open | Verdict |
|---|---|---|---|---|
| rtsp_transport | udp / tcp | consumed | consumed | APPLIED |
| rtsp_flags=prefer_tcp | tcp only | N/A | consumed | APPLIED (partial) |
| tcp_nodelay=1 | yes | N/A | NOT consumed | UNUSED (needs successful TCP to confirm) |
| fflags=nobuffer | udp only | consumed (+fmtCtx flag) | N/A | APPLIED (UDP) |
| avioflags=direct | no (avioDirect=0) | N/A | N/A | NOT_CONFIGURED |
| stimeout=5000000 | yes | NOT consumed | NOT consumed | UNUSED |
| timeout=5000000 | yes | consumed | NOT consumed (abort artifact) | APPLIED (UDP control conn) |
| rw_timeout=5000000 | yes | NOT consumed | NOT consumed | UNUSED |
| max_delay | 100000 (UDP) / 200000 (TCP) | consumed | NOT consumed (abort artifact) | APPLIED |
| buffer_size=262144 | yes | consumed | NOT consumed (abort artifact) | APPLIED |
| probesize=131072 | yes | consumed | NOT consumed (abort artifact) | APPLIED |
| analyzeduration | 100000 (UDP) / 200000 (TCP) | consumed | NOT consumed (abort artifact) | APPLIED |
| max_probe_packets=128 | yes | consumed | NOT consumed (abort artifact) | APPLIED |
| reorder_queue_size | 4 (UDP) / -1 (TCP: not set) | consumed | N/A (not configured) | APPLIED_BY_OPTION_CONSUMPTION (UDP) |

# Effective max_delay

- configuredMaxDelayUs: 100000 (UDP balanced) / 200000 (TCP balanced) — set by
  the BALANCED latency profile (transport-dependent by design; no default
  changed).
- effectiveFmtCtxMaxDelayUs: **100000** (read back from
  `AVFormatContext::max_delay` immediately after successful open, UDP).
- The value participates in the RTP/RTSP demuxer jitter/reorder buffering.

# reorder_queue_size Audit

- UDP balanced: configured 4; consumed by the RTSP demuxer on successful open
  -> `APPLIED_BY_OPTION_CONSUMPTION`. There is no reliable public readback for
  the RTP reorder queue depth in this FFmpeg build, so the *effective* depth is
  reported as UNKNOWN (not fabricated).
- TCP balanced: profile sets -1 -> option not passed (reorder handling is
  internal to TCP interleaving) -> N/A.

# Timeout Option Audit

The historical "unused stimeout/rw_timeout" observation is CONFIRMED on the
current build:

- `stimeout=5000000` and `rw_timeout=5000000` remain unconsumed after open
  (both UDP and failed TCP).
- `timeout=5000000` IS consumed on the successful UDP open (RTSP control
  connection).
- The actual read-blocking behavior during playback is governed by FFmpeg's
  RTSP receive loop plus `interrupt_callback` (which currently only aborts on
  stop/transport-switch, not on a hard read deadline); failures then flow to
  the existing reconnect logic. No timeout default was changed in this slice.

# UDP Baseline

Same device / URL / resolution / decoder / renderer / network; only transport
fixed to UDP, latency mode BALANCED, hardware decode, nv12_gl.

Steady window (20:24, seq=48):

```
STATE: state=playing videoGen=5 stageGen=5 steady=1 decodeFps=24.9 renderFps=24.9 backend=mediacodec output=nv12_cpu renderer=nv12_gl
MEDIA: total=39.90 ms valid=1
STAGE: total=45.049/55.980/64.084 ms
PRET0: readMs=31.955/40.985/48.371 videoGapMs=38.928/48.070/56.839 ptsDeltaMs=40.025 fast=0 maxBurst=0 stall=0/0/0/0 eagain=0 timeout=0 eof=0 error=0
HEALTH: samples=1737 dist=1024 mediaDist=65 decoderUnmatched=0 renderUnmatched=0 forcedEvict=0 reset=2 clockAnomaly=0 ptsBackward=0/0/0/0
```

The RTSP camera source became unreachable mid-session ("No route to host"),
triggering reconnect attempts (videoGen advanced, reset counter grew); the
steady window above is the clean playing period captured before the flap.

# TCP Comparison

TCP balanced open was attempted with identical device/URL/decoder/renderer/
network. `avformat_open_input` failed with `No route to host` because the
camera went offline; the RTSP TCP control connection could not be established.
No TCP steady-state data was obtainable during this session (source
unavailable). TCP A/B runtime: NOT_EXECUTED (environmental).

# Optional ffplay / VLC Comparison

NOT_EXECUTED (no ffplay/VLC on the device; not installed per slice scope).

# Optional Packet Capture

NOT_EXECUTED (no root/packet-mirror capability verified; optional per scope).
Note: even with captures, pcap wall time minus RTP PTS is not a latency metric
without a reliable RTP<->NTP mapping (LAT6).

# Direct vs Relay Comparison

NOT_EXECUTED (single direct camera source; no relay topology available; not
fabricated).

# Post-Demux Regression

No regression from the LAT5 diagnostics:

- decodeBackend=mediacodec, frameOutputType=nv12_cpu, renderer=nv12_gl
- measured decode/render ≈ 25 fps (24.9-25.8)
- dropped: rendered tracks decoded (3135-3137 vs 3140 packets); no new drops
- fallback: none (renderer stayed nv12_gl)
- LAT3 post-demux unchanged: total p50 ≈ 45.0-45.2 ms, decoder p50 ≈ 41.7 ms,
  render p50 ≈ 2.6 ms, client media backlog ≈ 40 ms
- diagnostics health: clockAnomaly=0, forcedEvict=0, unmatched=0,
  ptsBackward=0/0/0/0
- Playback behavior: NO CHANGE

# What LAT5 Measures

- `av_read_frame()` call duration distribution (R0/R1, monotonic)
- video packet return cadence (gap between consecutive video returns)
- video PTS delta vs return-gap cross-check
- fast-return burst behavior
- read stall buckets and error/timeout classification
- RTSP/RTP option application state (consumed vs unused)
- effective `AVFormatContext::max_delay`

# What LAT5 Does NOT Measure

- camera capture time, encoder output time, server receive time,
  RTP send wall time (LAT6 territory; no sender timestamps implemented)
- network absolute delay (single receiver monotonic clock cannot measure it)
- socket/RTP physical arrival (R0 is pre-call; no NIC timestamps)
- SurfaceFlinger / HWC / display path

# Remaining Latency Blind Spots

- Camera/Encoder/RTSP server fixed buffering: NOT_MEASURED
- Network absolute delay: NOT_MEASURED
- PRE_T0 socket/RTP arrival -> FFmpeg: PARTIAL/NOT_MEASURED (demux-return
  behavior measured; physical arrival not)
- POST_T4 display: NOT_MEASURED
- IMPORTANT: even with smooth 40 ms cadence and no burst/stall, the stream can
  be stably offset from the live edge (e.g. +300 ms) with no queue growth. A
  stable fixed offset is NOT visible to a receiver-only monotonic analysis and
  requires LAT6 sender/server timestamps.

# Recommended Next Slice

Current evidence: UDP pre-T0 cadence SMOOTH (gap ≈ PTS delta ≈ 40 ms, no fast
burst, no stalls), UDP vs TCP comparison unavailable this session, post-demux
stays ≈ 45 ms. Per the LAT5 decision rule:

```
Recommended next slice: LAT6 — Sender/Server/Network End-to-End Timestamp
Measurement (priority HIGH)
```

No PRE_T0 optimization slice is indicated by the available data; the dominant
unknown remains the sender/server/network fixed offset.

# Build

- `git diff --check`: PASS (LF->CRLF notices only)
- C++ host tests (`PreT0TimingTrackerTest.cpp`, MSVC C++17):
  `ALL_PRE_T0_TRACKER_TESTS_PASSED` (read duration, return gap, burst rules,
  generation/reset, invalid/error reads, bounded distributions, stall buckets)
- JVM formatter tests: `:app:testDebugUnitTest` PASS (10/10, incl. PRET0 line)
- `:ffmpegplayer:assembleDebug`: PASS
- `:ffmpegplayer:assembleRelease`: PASS
- `:app:assembleDebug`: PASS

# Runtime Validation

- Device: 34aff35a; RTSP `rtsp://192.168.1.101:556/main.mov`
- UDP balanced: PARTIAL — steady-state PRET0 evidence captured (seq=48,
  20:24) with 6 complete 5-line snapshots in the saved log
  (`runtime-udp-latencystats.txt`, seq=77..82; note the source flapped at the
  end, so the tail window shows reconnecting state). The source became
  unreachable ("No route to host") and did not recover within the retry
  window.
- TCP balanced: NOT_EXECUTED (source offline; open failed at connection stage)
- Packet capture: NOT_EXECUTED (optional)

# LAT5 Freeze

- read call timing correct (R0/R1 on the same monotonic clock as LAT2): YES
- video return gap correct (T0-based, reset on generation): YES
- PTS delta cross-check present: YES
- burst detection bounded and low-overhead: YES
- stall/error/timeout semantics explicit: YES
- RTSP option audit completed (with successful-open consumption evidence): YES
- PRET0 compact log complete (same seq snapshot, no per-packet logging): YES
- read duration never labeled network latency: YES
- UDP baseline evidence: PARTIAL (steady window captured; source flapped)
- TCP A/B: NOT_EXECUTED (source offline)
- LAT3 semantics unchanged: YES
- Player behavior unchanged: YES
- builds PASS: YES

**Slice LAT5 Architecture Freeze: YES**

**LAT5 Runtime Freeze: PENDING** (TCP A/B and a clean 3-5 min UDP run remain
blocked by the camera being unreachable; NOT_EXECUTED, not fabricated).

---

## A/B Table

| Metric | UDP Balanced | TCP Balanced |
|---|---:|---:|
| measured FPS | 24.9-25.8 | NOT_EXECUTED |
| read p50 | 31.955 ms | NOT_EXECUTED |
| read p95 | 40.985 ms | NOT_EXECUTED |
| read p99 | 48.371 ms | NOT_EXECUTED |
| video return gap p50 | 38.928 ms | NOT_EXECUTED |
| video return gap p95 | 48.070 ms | NOT_EXECUTED |
| video return gap p99 | 56.839 ms | NOT_EXECUTED |
| video PTS delta typical | ≈ 40.0 ms | NOT_EXECUTED |
| fast burst max | 0 | NOT_EXECUTED |
| stall >100ms | 0 | NOT_EXECUTED |
| stall >250ms | 0 | NOT_EXECUTED |
| stall >500ms | 0 | NOT_EXECUTED |
| stall >1000ms | 0 | NOT_EXECUTED |
| timeout | 0 | NOT_EXECUTED |
| read errors | 0 | NOT_EXECUTED |
| client media backlog | ≈ 40 ms | NOT_EXECUTED |
| post-demux total p50 | 45.049 ms | NOT_EXECUTED |
| post-demux total p95 | 55.980 ms | NOT_EXECUTED |
| post-demux total p99 | 64.084 ms | NOT_EXECUTED |

## Boundary Table

| Pipeline Segment | Status |
|---|---|
| Camera capture | NOT_MEASURED |
| Encoder | NOT_MEASURED |
| RTSP Server fixed buffering | NOT_MEASURED |
| Network absolute delay | NOT_MEASURED |
| socket/RTP physical arrival -> FFmpeg | PARTIAL / NOT_MEASURED |
| av_read_frame call behavior | MEASURED |
| T0 -> decoder submit | MEASURED (LAT2) |
| decoder residence | MEASURED (LAT2/LAT3) |
| render | MEASURED (LAT2/LAT3) |
| SurfaceFlinger / HWC / display | NOT_MEASURED |

---

## Answers

A. av_read_frame duration steady p50/p95/p99: **31.955 / 40.985 / 48.371 ms**
   (UDP balanced; TCP NOT_EXECUTED)
B. video packet return gap steady p50/p95/p99: **38.928 / 48.070 / 56.839 ms**
   (UDP balanced)
C. video packet PTS delta typical: **≈ 40.0 ms** (25 fps source)
D. packet cadence: **SMOOTH** (gap ≈ PTS delta; fast=0, maxBurst=0)
E. read stalls: **0** across all buckets (>100/>250/>500/>1000 ms)
F. UDP vs TCP pre-T0 behavior: **NOT_EXECUTED** (TCP run blocked by source
   offline)
G. post-demux latency: **ONE_FRAME_CLASS** (p50 ≈ 45 ms, p95 ≈ 56 ms at 25 fps)
H. evidence of FFmpeg buffering hundreds of ms before T0: **NO** (smooth
   cadence, no burst, no stalls; note: a stable fixed offset cannot be ruled
   out by receiver-only analysis)
I. camera/server/network absolute latency measured: **NO**
