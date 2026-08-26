# RTSP Latency — Slice LAT6 — Sender / Server / Network End-to-End Timebase Mapping

Date: 2026-08-21
Branch: dev (d309f64 + LAT6)
Scope: MEASURE ONLY — clock-domain audit, sender/server code availability
audit, RTCP SR access audit, receiver T0 wall/monotonic bridge, RTP media
clock evidence, tested E2E timebase tooling (NTP / RTP wrap / SR mapping /
drift / anomaly), compact E2E log line, Level 3 external visual test pattern
tool, Report. No low-latency tuning of any kind. No playback pipeline change.

---

# Scope

Allowed and implemented:

- clock domain audit (frozen definitions)
- sender timestamp capability audit (workspace scan)
- RTP/NTP mapping math + RTCP Sender Report mapping engine (tested tooling)
- RTCP SR accessibility audit against the bundled FFmpeg public API
- receiver-side correlation bridge (T0 wall + T0 monotonic at one event)
- end-to-end latency Stats fields (only genuinely measurable ones)
- test tools/scripts (host tests + Level 3 visual pattern)
- compact diagnostics log (`E2E` line)
- Report

Prohibited (all untouched): `max_delay` / `reorder_queue_size` / `buffer_size`
/ TCP-UDP default tuning, MediaCodec tuning, renderer tuning, frame drop,
sync/pacing, encoder/server/SurfaceFlinger optimization, Audio investigation.

# LAT5 Baseline

Frozen from `RTSP_LATENCY_SLICE_LAT5_PRE_T0_ISOLATION_REPORT.md`:

- UDP balanced steady window: read p50/p95/p99 = 31.955/40.985/48.371 ms;
  video return gap p50 = 38.928 ms ≈ PTS delta ≈ 40 ms; fast=0, maxBurst=0;
  stalls/errors = 0.
- Post-demux T0→T4 remains ONE_FRAME_CLASS: p50 ≈ 45 ms, p95 ≈ 54-56 ms,
  p99 ≈ 59-64 ms; decoder residence p50 ≈ 41-42 ms; renderer ≈ few ms.
- Receiver-only analysis cannot see a stable fixed offset from the live edge;
  that requires sender/server timestamps (this slice's territory).

# Measurement Mode

**NONE available for runtime measurement in this environment.**

- LEVEL 1 (Cooperative Sender Instrumentation): NOT_AVAILABLE — no sender
  source code exists in the workspace.
- LEVEL 2 (RTCP Sender Report Mapping): UNAVAILABLE at runtime — the bundled
  FFmpeg public API does not expose the RTCP SR NTP↔RTP mapping (see below).
  The full Level 2 mapping engine is nevertheless delivered as tested,
  self-contained tooling ready for a build/path that can feed SRs.
- LEVEL 3 (External Visual Ground Truth): TOOL PROVIDED, runtime NOT_EXECUTED
  (requires camera + second high-frame-rate camera; not run in this slice).

Report answer: measurement mode = **NONE (tooling-ready)**.

# Available Components

| Component | Status |
|---|---|
| Sender Code | NOT_AVAILABLE |
| Server Code | NOT_AVAILABLE |
| Receiver Code | AVAILABLE |

The workspace contains only the Android receiver app (`app`) and the player
library (`ffmpegplayer`). No camera/sender application and no RTSP server
source exist to instrument. Nothing was assumed or fabricated.

# Clock Domains

Frozen classification (enforced by construction; no illegal subtraction):

| Domain | Clock | Used for |
|---|---|---|
| A. Sender monotonic | sender steady clock | sender-internal durations only (no sender in this workspace) |
| B. Sender wall clock | NTP/PTP synchronized Unix time | cross-device comparison anchor (absent here) |
| C. RTP media clock | e.g. 90 kHz RTP timestamps | media timeline identity; wrap-safe extension required |
| D. Android monotonic | `steady_clock` (LAT2/LAT3) | ALL local stage durations T0→T4 (unchanged) |
| E. Android wall clock | `system_clock` via `wallClockNs()` | T0 bridge value only |

Forbidden subtractions remain forbidden: sender-monotonic − receiver-monotonic,
RTP PTS − Android monotonic, Unix timestamp − media PTS — unless an explicit
mapping exists. None exists at runtime in this build, so none is computed.

# Clock Synchronization

- `senderClockSyncMethod`: **not_available** (no sender component).
- `receiverClockSyncMethod`: native reports `system_auto_time_unverified`;
  the compact log line reports the actual device setting
  (`auto_time` / `manual` / `unknown`) read from `Settings.Global.AUTO_TIME`.
- Cross-device absolute latency requires synchronized clocks on both ends;
  with no sender present this condition cannot be satisfied.

# Clock Error Bound

`clockSyncEstimatedErrorUs = -1` → **UNKNOWN**. Never reported as 0 ms. Any
future cross-device number must carry its own uncertainty bound separately
(CLOCK_SYNC_ERROR_BOUND); the Stats field reserves UNKNOWN instead of a fake 0.

# Sender Timestamp Boundary

NOT_AVAILABLE. No capture/encoder/send timestamps can exist without sender
code. `senderTimestampMode = "none"`.

# Sender Frame Correlation

Design frozen for future use: stable frame identity = session/generation +
media PTS (+ extended RTP timestamp when available). Bare per-session frame
counters are rejected as cross-session identity. The delivered mapper keys all
mapping off extended RTP timestamps with explicit generation reset.

# RTP Clock

Read from the real stream, never hardcoded: after stream selection the video
stream `time_base` is captured and published as
`videoStreamTimeBase = "<num>/<den>"` plus derived integer
`videoRtpClockRate = den/num` (RTSP/RTP video streams carry 1/clock_rate).
For the known HEVC/RTSP source this yields 1/90000 → 90000 Hz; the code path
itself reads whatever the SDP/stream provides and reports 0 when unknown.

# RTCP Sender Report

Audit result: **RTCP_SR_ACCESS: UNAVAILABLE** in this build.

- FFmpeg's public API (the headers bundled in `ffmpegplayer/src/main/cpp/
  ffmpeg/include`) exposes no accessor for the RTCP SR NTP↔RTP association the
  RTP demuxer maintains internally.
- Per slice rules, undocumented private-struct hacks are prohibited, so no SR
  data path was implemented in the player.
- Fields emitted are honest state flags: `rtcpSrAccess="unavailable"`,
  `e2eMeasurementMode="none"`.

The complete Level 2 engine (`RtpToNtpMapper`) ships as tested tooling:
SR anchoring, SSRC validation, wrap-safe extended RTP math, drift audit, and
RTP→wall mapping — ready for any future path that can legally obtain SRs.

# RTP-to-NTP Mapping

Implemented and unit-tested (`E2ETimebase.h`, host tests):

```
mediaWallNs = srNtpNs + (extRtp - srExtRtp) * 1e9 / rtpClockRate
```

- NTP 64-bit (seconds + fraction) → Unix ns in pure int64 math
  (`ntpToUnixNs`; epoch offset 2208988800 s; fraction scaled by 1e9/2^32).
  Float is never used for absolute timestamps.
- Mapping requires a valid same-session SR anchor; without it the call fails
  and never fabricates a value.

# SR Drift Audit

Consecutive SRs are checked for rate consistency:
`driftPpm = ((ntpDelta − expectedNtpDelta) × 1e6) / expectedNtpDelta`.
Unit-tested: exact-rate SRs yield 0 ppm; a 0.5 s stretch over 10 s of RTP
yields exactly 50000 ppm. A future consumer must treat abnormal drift as
`SR_MAPPING_UNRELIABLE` and stop emitting precise-looking latencies.

# Receiver T0 Wall/Monotonic Bridge

At the SAME event as the existing LAT2/LAT5 T0 (`packetReadyMonoUs =
steadyNowUs()`), the receiver wall timestamp is now also captured:

```cpp
lastPacketReadyWallNs_.store(wallClockNs());   // system_clock, Unix ns
```

- Local stage durations continue to use monotonic only (LAT2/LAT3 untouched).
- The bridge enables future cross-device subtraction:
  `senderSendWallNs − lastPacketReadyWallNs` style comparisons against an
  independently synchronized sender wall clock.
- Reset on every generation change (reconnect/format discontinuity/stats
  reset) together with the E2E generation counters.

# Same-frame E2E Correlation

Rules enforced in design and tests:

- End-to-end values may only be composed per frame identity (generation +
  PTS/RTP), never by adding "current sender snapshot + current receiver
  snapshot".
- Unit test proves two adjacent frames map to distinct, individually correct
  wall times (same-frame correlation; neighbor confusion impossible).
- Presentation-order semantics preserved: mapping is keyed by RTP/media
  timestamp identity, not packet FIFO position (B-frame/reorder safe).

# Sender Measurements

NOT_AVAILABLE (no sender code). Nothing measured, nothing fabricated.

# Server Measurements

NOT_AVAILABLE (no server code). `SERVER_RESIDENCE: NOT_MEASURED`.

# External Pre-T0 Path

With only `receiverT0WallNs` available and no sender send timestamp, the
segment `senderSendToReceiverT0Us` is NOT_MEASURED. When it becomes measurable
it MUST be named `senderSendToReceiverT0Us` / `preT0ExternalPathUs` — it would
include uplink + server + downlink + receiver pre-T0 and must never be labeled
`NETWORK_LATENCY`.

# Receiver T0-to-T4

Unchanged from LAT3/LAT5 (reused, not reimplemented): monotonic
`packetReadyToRenderSubmitUs`, ONE_FRAME_CLASS, p50 ≈ 45 ms baseline.

# External Visual Validation

Level 3 tool provided: `tools/latency_test_pattern.html`
(standalone HTML+JS, no framework):

- large millisecond wall-clock readout (`unixMs % 100000`),
- live frame index + full unix ms line,
- 100 ms LED block toggle (one lit block advancing every 100 ms),
- renders real wall-clock values only; never fabricates results.

Procedure (documented; NOT_EXECUTED this slice): display the pattern fullscreen
in the camera's view → the RTSP stream carries it → photograph the pattern
display and the Android receiver screen simultaneously with a high-frame-rate
camera → glass-to-glass = frame delta between identical pattern events.

# End-to-End Delay Budget

Only real data; unavailable segments are NOT_MEASURED, not zeros:

| Segment | p50 | p95 | p99 | Confidence |
|---|---:|---:|---:|---|
| Capture boundary -> encoder input | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Encoder residence | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Encoder output -> sender send | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Sender send -> receiver T0 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Receiver T0 -> T4 | ≈45 ms | ≈54-56 ms | ≈59-64 ms | HIGH (same-clock monotonic, same-frame) |
| T4 -> physical display | UNKNOWN | UNKNOWN | UNKNOWN | NOT_MEASURED |
| Capture -> T4 | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED | NOT_MEASURED |
| Glass-to-glass | UNKNOWN | UNKNOWN | UNKNOWN | NOT_MEASURED |

# Confidence Levels

- HIGH: same-device monotonic + same-frame correlation (receiver T0→T4 only).
- MEDIUM: cross-device synchronized wall clock with known uncertainty —
  not achievable in this environment (no sender).
- LOW: RTCP mapping with incomplete capture semantics — engine provided, no
  runtime SRs.
- NOT_MEASURED: everything sender/server/network/display-side.

# What LAT6 Measures

- Receiver T0 wall-clock bridge (`lastPacketReadyWallNs`) at the exact T0 event.
- Video stream time_base / RTP clock rate evidence from the real stream.
- E2E generation/reset bookkeeping across reconnects and discontinuities.
- Receiver clock-sync method evidence (auto_time/manual) in the compact log.
- Full Level 2 mapping math (NTP conversion, RTP wrap, SR anchors, drift,
  anomaly gating) as unit-tested tooling for when SR/sender data appears.

# What LAT6 Does NOT Measure

- Camera capture latency, encoder latency, sender send timing (no sender).
- Server residence (no server code).
- Network absolute delay (single-ended wall clock cannot measure it).
- RTCP SR mapping at runtime (public API exposes none; no hacks allowed).
- Physical glass-to-glass (external visual procedure documented but not run).
- Any post-T4 display pipeline stage.

# Performance Regression

- Added per-video-packet work: one `system_clock::now()` (~tens of ns) and one
  atomic store at T0; ~25 events/s. No allocation, no logging, no lock on the
  hot path.
- Compact log grows by one short line per existing 5 s stats tick (formatted
  on the stats worker path, not decoder/render paths).
- decodeBackend/output/renderer unchanged; expected decode/render fps unchanged
  (runtime re-validation pending, consistent with LAT5 methodology).

# Build

- Host C++ tests (`E2ETimebaseTest.cpp`, MSVC C++17):
  `ALL_E2E_TIMEBASE_TESTS_PASSED` — NTP conversion, 90 kHz RTP↔wall mapping,
  wrap-around extension (0xfffffff0→0x100000020), no-anchor invalidity,
  generation reset + SSRC mismatch rejection, negative-latency anomaly
  invalidation, same-frame correlation, bounded E2E distribution, SR drift
  audit (0 ppm / 50000 ppm cases).
- LAT5 host tests still pass: `ALL_PRE_T0_TRACKER_TESTS_PASSED`.
- JVM formatter tests: `:app:testDebugUnitTest` PASS (12/12, incl. 2 new E2E
  line tests: no fabricated zeros, length/newline safety).
- `git diff --check`: PASS (LF->CRLF notices only).
- `:ffmpegplayer:assembleDebug`: PASS
- `:ffmpegplayer:assembleRelease`: PASS
- `:app:assembleDebug`: PASS

# Runtime Evidence

NOT_EXECUTED. No cooperative sender, no RTCP SR source, and no second
high-frame-rate camera were available. All cross-device numbers are therefore
NOT_MEASURED; nothing was inferred from code logic and reported as data.

# Recommended Optimization Domain

INCONCLUSIVE for optimization: the dominant unknown (sender/encoder/server/
network fixed offset) remains unmeasured because no sender/server component
exists to instrument. Next actionable step is TIMEBASE_FOLLOWUP: obtain SR
access (FFmpeg patch/public API path or a cooperative sender), or execute the
Level 3 visual procedure — before any optimization slice can be justified.

# LAT6 Freeze

- clock domains clearly separated: YES
- no illegal cross-clock subtraction anywhere: YES
- RTP clock semantics explicit (stream-derived, not hardcoded): YES
- SR mapping engine verified by tests (anchor/wrap/drift/SSRC/reset): YES
- generation/reset semantics correct (E2E counters reset with sessions): YES
- same-frame correlation enforced and tested: YES
- clock uncertainty explicit (UNKNOWN, never 0): YES
- receiver T0/T4 semantics preserved (monotonic, reused): YES
- invalid mappings never rendered as 0 ("--"/invalid in log and JSON): YES
- Delay Budget contains only real data: YES
- no playback behavior change: YES
- builds PASS: YES

**Slice LAT6 Architecture Freeze: YES**

**LAT6 Runtime Freeze: PENDING** (no runtime E2E mode executable in this
environment; nothing fabricated).

---

## Answers

1. Measurement mode: **NONE (Level 2 engine + Level 3 tooling delivered; no
   runtime mode available)**
2. Sender source available: **NO**
3. Server source available: **NO**
4. Clock synchronization: **NOT_AVAILABLE** (cross-device); receiver method
   evidence = system auto-time setting in compact log
5. Estimated sync error: **UNKNOWN**
6. RTP clock rate: **90000 Hz** (from stream time_base 1/90000; stream-derived,
   not hardcoded)
7. RTCP SR available: **NO** (public API exposes none; RTCP_SR_ACCESS:
   UNAVAILABLE)
8. SR mapping valid: **NOT_EXECUTED** (engine tested offline; no runtime SRs)
9. Capture timestamp available: **NO**
10. Encoder input/output timestamps available: **NO**
11. Sender send timestamp available: **NO**
12. Sender send -> Receiver T0: p50=NOT_MEASURED p95=NOT_MEASURED
    p99=NOT_MEASURED
13. Receiver T0 -> T4: p50≈45 ms, p95≈54-56 ms, p99≈59-64 ms (LAT3/LAT5
    baseline, unchanged)
14. Capture -> Receiver T0: NOT_MEASURED
15. Capture -> T4: NOT_MEASURED
16. Physical glass-to-glass: NOT_MEASURED
17. Server latency isolated: **NO**
18. Network latency isolated: **NO**
19. Cross-device monotonic clocks subtracted: **NO**
20. Media PTS directly subtracted from wall clock: **NO**
21. Playback behavior changed: **NO**
22. Low-latency tuning performed: **NO**
