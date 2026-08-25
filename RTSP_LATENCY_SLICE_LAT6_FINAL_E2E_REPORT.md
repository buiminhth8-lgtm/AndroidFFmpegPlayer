# RTSP Latency — LAT6 FINAL — E2E Timebase Activation

Date: 2026-08-25

Baseline: `1b46e3d` plus the pre-existing uncommitted LAT6 SSRC/session fix

Scope: route B only — RTCP Sender Report / RTP timebase diagnostics. No playback tuning and no LAT7 work.

# Scope

This slice audits why the receiver reported:

```text
mode=none
srCount=0
srValid=0
sendToT0Ms=--
valid=0
```

It corrects the receiver-side route-B architecture so that:

- RTCP SR establishes only the RTP-clock to sender-wall-clock anchor.
- FFmpeg `AV_PKT_DATA_PRFT` on the same `AVPacket` supplies the per-packet RTP-mapped sender wall time.
- The packet's PRFT is compared only with receiver wall time captured at that packet's T0.
- A separate clock-synchronization gate prevents an RTP/NTP mapping from being promoted to a cross-device latency percentile when clock error is unknown.
- reconnect, source switch, SSRC change, and same-SSRC sender restart cannot reuse an old mapping.
- diagnostics remain bounded and do not log per frame.

No RTSP default, MediaCodec setting, frame-drop rule, sync/pacing path, renderer, Audio, Recording, or Thermal behavior was changed.

# Why E2E Was mode=none

The direct mode-selection condition was never satisfied because the observed runtime had `rtcpSrReceivedCount=0`. No `AV_PKT_DATA_RTCP_SR` side data reached the receiver's video packet path, so there was no RTP/NTP anchor and the code correctly stayed at `mode=none`, `srValid=0`, and `valid=0`.

The bundled headers and binaries are not the missing capability:

- bundled libavformat version: `Lavf/62.3.100`, FFmpeg `8.0.1`;
- `packet.h` exposes `AV_PKT_DATA_RTCP_SR`;
- `defs.h` exposes `AVRTCPSenderReport`;
- the arm64 `libavformat.so` contains the RTCP-SR side-data and PRFT paths.

Therefore the bounded root cause is:

```text
The current RTSP sender/transport session did not provide an RTCP SR that
FFmpeg exported on a video AVPacket.
```

With neither sender/server source nor packet capture available, this audit cannot distinguish “sender did not transmit SR” from “SR was not delivered on the negotiated RTCP transport.” That narrower distinction remains external.

Two additional correctness defects were found in the previous route-B code:

1. It subtracted an SR control-packet NTP time from the T0 of the next video packet. That is not same-frame sender/RTP-to-T0 correlation.
2. The compact logger could report `valid=1` from an SR mapping and a sample even though `clockSyncEstimatedErrorUs` was still unknown.

Both are corrected. Mode is not forced.

# Available Timebase Sources

| Source | Status | Evidence / consequence |
|---|---|---|
| Sender source | NOT_AVAILABLE | No capture/encoder/sender project is present in this workspace. |
| RTSP server source | NOT_AVAILABLE | The camera/server implementation is external. |
| FFmpeg RTCP SR API | AVAILABLE | Public FFmpeg 8.0.1 packet side data; no private-struct access. |
| RTCP SR in current runtime | UNAVAILABLE | Supplied runtime evidence is `srCount=0`; no post-change device run was possible. |
| RTP clock rate | AVAILABLE after open | Derived from video stream `time_base`; not hardcoded. |
| Receiver T0 wall + monotonic | AVAILABLE | Existing LAT6/LAT3 bridge reused. |
| Sender/receiver clock sync | UNKNOWN | Receiver auto-time is observable only as configuration; sender clock state and error bound are absent. |
| External visual measurement | NOT_SELECTED | Existing helper is untouched; route C was not implemented in this slice. |

# Chosen Measurement Mode

Chosen architecture: `rtcp_sr` (route B only).

Runtime measurement mode at freeze: `none`.

`rtcp_sr` is selected at runtime only after a real SR anchor and a same-packet PRFT mapping have both been observed. `e2eValid` additionally requires a known cross-device clock-sync error and at least one accepted steady-state sample.

# Clock Domains

| Domain | Representation | Legal use |
|---|---|---|
| Sender monotonic | not available | Sender-local durations only; never compared with receiver monotonic. |
| Sender wall | RTCP SR NTP / PRFT Unix wall | Cross-device comparison only after sync is verified. |
| RTP media clock | 32-bit RTP timestamp, stream clock rate | Mapped to sender wall by SR; wrap-aware. |
| Receiver wall | `system_clock` Unix ns at T0 | Compared with synchronized sender wall only. |
| Receiver monotonic | `steady_clock` us at T0–T4 | Existing LAT3 local stage durations only. |

The implementation never subtracts sender monotonic from receiver monotonic and never subtracts RTP PTS directly from Unix wall time.

# Clock Synchronization

Sender clock method: `not_available`.

Receiver clock method: `system_auto_time_unverified` when Android auto-time is enabled, otherwise disabled/unknown.

Clock sync method: `UNKNOWN`.

Clock sync estimated error: `UNKNOWN` (`-1` internally, `--` in the compact log).

Android auto-time does not prove that the external camera/server clock is synchronized or provide an error bound. Consequently `kE2EClockSyncValid` is false and candidate PRFT deltas cannot enter the valid percentile window.

# RTP / RTCP Mapping

FFmpeg public APIs used:

- `AV_PKT_DATA_RTCP_SR` / `AVRTCPSenderReport`: SSRC, NTP timestamp, RTP timestamp, packet count, and byte count for a received SR;
- `AV_PKT_DATA_PRFT` / `AVProducerReferenceTime`: the current packet's raw RTP timestamp mapped through the most recent SR to Unix wall time.

FFmpeg exports an SR once on the next packet after receiving it. The previous assumption that the last SR repeats on every packet was removed.

The nominal mapping is:

```text
packetSenderWall = srNtpWall
                 + signedWrapAware(packetRtp - srRtp) / rtpClockRate
```

For production packet correlation, FFmpeg performs this calculation before returning the packet and exposes the result as PRFT. Host-testable helpers still cover NTP-to-Unix conversion, RTP 32-bit wrap, RTP-clock conversion, drift, and invalid mapping.

Reset rules:

- receiver reconnect/source switch/generation reset clears SR anchor, distribution, expected SSRC, and same-frame counters;
- SSRC change drops the old anchor and re-anchors on the new SR;
- a same-SSRC RTP/NTP discontinuity is treated as sender restart and re-anchors;
- old SR state never maps a new session.

Diagnostic fields include `rtcpSrReceivedCount`, `lastSrRtpTimestamp`, `lastSrNtpNs`, `videoSsrc`, `srMappingValid`, `rtpClockGeneration`, `srMappingResetCount`, `srDriftPpm`, and invalid/mismatch counters.

Semantic limit: the SR/PRFT value is the RTP media timestamp expressed on the sender wall clock. It does not prove camera capture time or the exact socket send instant. The requested `senderSendToReceiverT0Us` field is therefore not published as valid in this environment.

# Same-frame Correlation

Correlation key in route B is the `AVPacket` itself:

```text
AVPacket(PRFT for this packet's RTP timestamp)
        +
Receiver wall/monotonic captured at this packet's av_read_frame return (T0)
```

No “latest sender timestamp + latest receiver latency” pairing exists. `sameFrameMappedCount` counts packets with a usable PRFT after an SR anchor; `sameFrameUnmatchedCount` counts packets that have an anchor but no usable same-packet PRFT. Both reset with the E2E generation.

# Runtime Evidence

Pre-change receiver evidence supplied for this task:

```text
mode=none
srCount=0
srValid=0
sendToT0Ms=--
valid=0
```

Post-change runtime gate: NOT_EXECUTED. `adb devices -l` returned an empty device list on 2026-08-25. There was therefore no authorized way to install the APK, reach the RTSP source, run for 5–10 minutes, or capture at least 100 valid samples.

No post-change runtime value is inferred from unit tests or build success.

# E2E Distribution

Measurement mode: `none`

RTCP SR count: `0` (supplied pre-change runtime evidence)

SR mapping valid: `NO`

Clock sync: `UNKNOWN`

Clock sync error: `UNKNOWN`

Sender send -> Receiver T0:

```text
last=NOT_MEASURED
p50=NOT_MEASURED
p95=NOT_MEASURED
p99=NOT_MEASURED
samples=0
```

Candidate values with unknown clock error are counted invalid and never enter the bounded percentile distribution. Empty distributions expose `-1` to the demo and render as `--`, not `0`.

Capture -> Receiver T0: `NOT_MEASURED`

Capture -> Render Submit (T4): `NOT_MEASURED`

Physical glass-to-glass: `NOT_MEASURED`

# Receiver T0-to-T4 Baseline

Frozen LAT5/LAT3 evidence, not a new runtime run:

```text
decodeBackend=mediacodec
frameOutputType=nv12_cpu
renderer=nv12_gl
decode/render ~= 25 fps
T0 -> T4 p50=45.049 ms
T0 -> T4 p95=55.980 ms
T0 -> T4 p99=64.084 ms
```

This remains ONE_FRAME_CLASS. The LAT6 code is diagnostic side-channel work at T0 and does not modify decode, render, sync, pacing, or frame-drop behavior. Runtime regression confirmation is blocked by the missing device.

# Tests

C++ host test (`E2ETimebaseTest.cpp`, MSVC C++17):

```text
ALL_E2E_TIMEBASE_TESTS_PASSED
```

Coverage includes:

- RTP 32-bit wrap;
- RTP clock-to-time conversion;
- checked NTP-to-Unix conversion;
- generation reset;
- SSRC/session reset and same-SSRC sender restart;
- same-frame mapping semantics;
- invalid clock mapping and negative clock anomaly;
- explicit clock-sync validity gate;
- bounded percentile storage and empty-distribution `-1` values.

JVM test:

```text
:app:testDebugUnitTest --tests com.example.motro.LatencyStatsFormatterTest
BUILD SUCCESSFUL
```

It verifies that an SR mapping with unknown clock sync remains `valid=0` and renders `sendToT0Ms=--/--/--`.

# Build

```text
git diff --check                     PASS
:ffmpegplayer:assembleDebug          PASS
:ffmpegplayer:assembleRelease        PASS
:app:assembleDebug                   PASS
```

The non-fatal Android SDK XML/deprecation warnings are unrelated to LAT6.

# Remaining Unknowns

- Whether the external RTSP sender emits video RTCP Sender Reports.
- Whether negotiated UDP/TCP/interleaved transport delivers those reports.
- Sender/server clock synchronization method and measured error bound.
- Whether a reachable source yields stable SR/PRFT mapping over multiple SRs.
- Real `sameFrameUnmatchedCount`, anomalies, resets, and sample distribution.
- Camera capture, encoder residence, exact RTP socket-send time, and physical display/glass-to-glass latency.

# LAT6 Freeze

Measurement mode: `none`

RTCP SR count: `0`

SR mapping valid: `NO`

Clock sync: `UNKNOWN`

Clock sync error: `UNKNOWN`

Sender send -> Receiver T0: `NOT_MEASURED / NOT_MEASURED / NOT_MEASURED`

Receiver T0 -> T4: `45.049 / 55.980 / 64.084 ms`

Capture -> T4: `NOT_MEASURED`

Physical glass-to-glass: `NOT_MEASURED`

LAT6 Architecture Freeze: `YES`.

LAT6 Runtime Freeze:

```text
BLOCKED_BY_EXTERNAL_DEPENDENCY
```

Blocking dependency:

```text
No ADB device is connected, the external RTSP sender/server cannot be audited
or modified here, the current runtime provides no RTCP SR, and no verified
sender/receiver wall-clock synchronization error bound is available.
```

Required next action:

1. Connect the target Android device and make the RTSP source reachable.
2. Configure/verify video RTCP SR emission and capture multiple SR mappings.
3. Provide sender/server and receiver NTP/PTP status with a bounded sync error.
4. Install this build, run 5–10 minutes, collect at least 100 valid samples, and verify anomalies/unmatched/resets do not grow abnormally.

Until all three timebase dependencies are present, E2E latency remains invalid and LAT7 must not start.
