# RTSP Latency — LAT3 Diagnostics Log-output Fix

Date: 2026-08-20
Branch: dev
Commit: (created by this fix)
Scope: DIAGNOSTICS_LOG_OUTPUT_VISIBILITY_BUG — make LAT1/LAT2/LAT3 latency
diagnostics Logcat-safe. Adds a compact, key=value latency log that reuses the
exact same getStats() snapshot as the full FFmpegPlayerStats JSON. No metric
semantics, playback behavior, RTSP/MediaCodec/renderer/Audio code, or Stats API
was changed. This is NOT LAT4/LAT5.

---

# Scope

- Diagnostics log output only.
- Necessary log format helper: `LatencyStatsFormatter` (pure Java).
- Directly related unit tests for the compact formatter.
- Fix Report.

Prohibited areas (all untouched): PTS calculation, percentile algorithm,
latency samples, MediaCodec, RTSP parameters, decoder, GL renderer, frame drop,
pacing/sync, reconnect, Surface lifecycle, Audio, Recording, Thermal, LAT4,
LAT5.

# Problem

`FFmpegPlayerStats` full JSON is very long (~6-7 KB). Logcat truncates a single
message at ~4100 chars, so every LAT1/LAT2/LAT3 field emitted after the
truncation point is invisible in real logs. Observed truncation points:

- `...\"decoderName\":\"hevc\"` (fresh capture, line length 4100).
- `...packetRead` (startup capture).
- `...\"avgPacketReadyToRenderSubmitUs\":1` (steady-state capture).

Consequently these LAT3 key fields could not be verified from runtime logs:

- `packetReadyToRenderSubmit*` (P50/P95/P99/DistCount)
- `decoderTimingUnmatchedCount`
- `renderTimingUnmatchedCount`
- `stageTimingForcedEvictionCount`
- `stageTimingResetCount`
- `stageTimingClockAnomalyCount`
- LAT1/LAT3 backlog distribution fields and all diagnostics health fields.

This is a log-output visibility bug, not a playback pipeline bug.

# Existing Logcat Truncation

Saved evidence (`FFmpegPlayerStats` lines, same run that produced the compact
lines):

```
D FFmpegPlayerStats: {"success":true,...,"videoCodec  <<< 4100 chars, cut mid-field
```

Every full-JSON line is truncated at 4100 characters; the truncation point
moves as new fields are added, so the missing LAT3 tail cannot be recovered by
grepping. The full JSON log itself is retained unchanged (see
`FULL_JSON_LOGCAT_TRUNCATION_EXPECTED` below).

# Stats API Compatibility

`getStats()` / `getPlayerStats()` / Stats JSON contract: **NO CHANGE**.

- No field added, removed, or renamed in the Stats JSON.
- No field semantics changed.
- Return structure unchanged.
- The full JSON is never split/chunked to make it Logcat-safe.

API contract and Logcat output are fully separated: the compact latency log is
derived from the already-parsed `JSONObject` of the same snapshot and does not
alter what `getStats()` returns.

# Compact Log Design

The minimal implementation layer is Java: `MediaPlayerActivity` is the only
place that prints Stats (full JSON via `Log.d(TAG_STATS, ...)` every 5th 1 s
tick). The compact formatter therefore lives in the app module (not in the
frozen `ffmpegplayer` public library):

- `app/src/main/java/com/example/motro/LatencyStatsFormatter.java` — pure Java
  (no android.* dependency), directly JVM-unit-testable.
- `MediaPlayerActivity.logCompactLatencyStats(JSONObject)` — called in the exact
  same block that prints the full JSON, reusing the same parsed snapshot.

No second native `getStats()` call; no extra native snapshot; no per-frame
logging; no logging in decoder/render hot paths. Formatting happens only at the
existing Stats log cadence (every 5 s).

# Log Tag

Single tag for all four lines:

```
FFmpegLatencyStats
```

Line type is distinguished by content prefix (`STATE` / `MEDIA` / `STAGE` /
`HEALTH`), so `adb logcat -s FFmpegLatencyStats` captures all of them and each
line remains grep-able.

# Snapshot Sequence

Every Stats tick emits four lines sharing a log-only monotonic sequence number:

```
seq=1 STATE  ...
seq=1 MEDIA  ...
seq=1 STAGE  ...
seq=1 HEALTH ...
```

- Implemented as a log-only counter (`latencyStatsSeq`) in `MediaPlayerActivity`.
- It is not a public Stats metric, not a media generation, not a frame index.
- It only correlates the four lines of one snapshot against interleaved output.

# STATE Line

Identity / state / generation / measured FPS / pipeline identity:

```
seq=2 STATE handle=1 state=playing videoGen=2 stageGen=2 steady=1 decodeFps=25.0 renderFps=25.0 backend=mediacodec output=nv12_cpu renderer=nv12_gl packets=213 frames=212 rendered=212
```

Fields map to Stats JSON: `handle`, `state`, `videoPtsGeneration`,
`stageTimingGeneration`, `steadyStateValid`, `measuredDecodeFps`,
`measuredRenderFps`, `decodeBackend`, `frameOutputType`, `renderer`,
`videoPacketCount`, `videoFrameCount`, `renderedFrameCount`.

# MEDIA Line

LAT1 media backlog, current values + steady-state p50/p95/p99 (ms):

```
seq=2 MEDIA mediaMs cur demux=0.00 decoder=40.10 render=0.00 total=40.10 valid=1 dist demux=0.000/0.000/0.000 decoder=40.100/80.500/80.500 render=0.000/0.000/0.000 total=40.100/80.500/80.500
```

- `cur` values: `demuxToDecoderBacklogUs`, `decoderBacklogUs`,
  `renderBacklogUs`, `clientMediaBacklogUs` (as `total`).
- `valid`: `clientMediaBacklogValid`.
- `dist` triples: `demuxToDecoderBacklogP50/P95/P99`,
  `decoderBacklogP50/P95/P99`, `renderBacklogP50/P95/P99`,
  `clientMediaBacklogP50/P95/P99`; shown only when the corresponding
  `*DistCount` > 0.

# STAGE Line

LAT2/LAT3 local stage distribution p50/p95/p99 (ms):

```
seq=2 STAGE stageMs p50/p95/p99 demux=0.022/0.066/0.133 decode=41.888/76.988/95.389 queue=0.010/0.018/0.026 render=2.598/5.263/9.341 total=45.287/79.593/100.159
```

- `demux`: `demuxReturnToDecoderSubmitP50/P95/P99`
- `decode`: `decoderSubmitToOutputP50/P95/P99`
- `queue`: `decodedOutputToRenderBeginP50/P95/P99`
- `render`: `renderBeginToSubmitP50/P95/P99`
- `total`: `packetReadyToRenderSubmitP50/P95/P99` (T0→T4; still
  `postDemuxLocal`, NOT end-to-end latency)

`packetReady -> render submit` is therefore fully visible on one line.

# HEALTH Line

Diagnostics health counters:

```
seq=2 HEALTH samples=211 dist=91 mediaDist=4 decoderUnmatched=1 renderUnmatched=1 forcedEvict=0 reset=1 clockAnomaly=0 ptsBackward=0/0/0/0
```

- `samples`: `stageTimingSampleCount`
- `dist`: `packetReadyToRenderSubmitDistCount` (packetReady distribution count)
- `mediaDist`: `clientMediaBacklogDistCount`
- `decoderUnmatched`: `decoderTimingUnmatchedCount`
- `renderUnmatched`: `renderTimingUnmatchedCount`
- `forcedEvict`: `stageTimingForcedEvictionCount` (real current name; there is
  no `stageTimingEvictionCount` anymore — LAT3 renamed it)
- `reset`: `stageTimingResetCount`
- `clockAnomaly`: `stageTimingClockAnomalyCount`
- `ptsBackward`: `videoPtsBackwardCount` / `decoderPtsBackwardCount` /
  `decodedPtsBackwardCount` / `renderedPtsBackwardCount`

# Units

All latency durations in the compact lines are **ms**; line headers make this
explicit (`mediaMs`, `stageMs`). FPS values are frames/second. The internal
Stats JSON keeps its original us units — the API is not changed.

# Invalid Value Handling

- `clientMediaBacklogValid=false` (or backlog = -1) → `total=--`, `valid=0`,
  and all related current/dist values render `--` / `--/--/--` (never 0 ms).
- Distribution not yet ready (`DistCount == 0`) or any percentile < 0 →
  `--/--/--` (never fabricated zeros).
- `steadyStateValid=false` is output as `steady=0` on the STATE line.

# Eviction Counter Audit

Audit result: **no bookkeeping fix needed in this slice.**

The LAT3 semantic fix is genuinely present in the code
(`NativePlayer.cpp`):

- On `RenderSubmit`, `finalizeStageTiming()` runs first, then the completed (or
  anomalous) record is retired with `stageTimingRecords_.erase(recordIt)`.
- `stageTimingForcedEvictionCount_` is incremented only when a new
  `PacketReady` record cannot be inserted because the deque is at
  `kStageTimingMaxRecords` (256) — i.e. only true unresolved forced eviction.

Therefore the counter already expresses real forced unresolved eviction, and
this fix only needs to output it reliably (HEALTH line). No semantic change was
made.

# Runtime Evidence

Device: `34aff35a` (real device), RTSP:
`rtsp://192.168.1.101:556/main.mov`, hardware decode path
`mediacodec → nv12_cpu → nv12_gl`, ~2 min steady-state playback plus ~50 s
startup run.

Saved evidence:

- `runtime-ffmpeglatencystats.txt` — 6 complete snapshots (seq=3..8)
- `runtime-ffmpeglatencystats-final.txt` — 6 complete snapshots (seq=25..30)

Every snapshot has all four lines (STATE/MEDIA/STAGE/HEALTH) with the same seq,
programmatically verified:

```
COMPLETE_SNAPSHOTS=6  (each seq has STATE,MEDIA,STAGE,HEALTH)
```

Final snapshot (19:16:56):

```
seq=30 STATE handle=1 state=playing videoGen=2 stageGen=2 steady=1 decodeFps=25.0 renderFps=25.0 backend=mediacodec output=nv12_cpu renderer=nv12_gl packets=3719 frames=3718 rendered=3718
seq=30 MEDIA mediaMs cur demux=0.00 decoder=40.10 render=0.00 total=40.10 valid=1 dist demux=0.000/39.700/40.000 decoder=40.000/41.800/79.800 render=0.000/0.000/0.000 total=40.100/79.800/80.500
seq=30 STAGE stageMs p50/p95/p99 demux=0.022/0.042/0.112 decode=41.855/80.514/102.716 queue=0.010/0.019/0.026 render=2.581/6.772/22.293 total=45.197/85.147/106.110
seq=30 HEALTH samples=3717 dist=1024 mediaDist=144 decoderUnmatched=1 renderUnmatched=1 forcedEvict=0 reset=1 clockAnomaly=0 ptsBackward=0/0/0/0
```

Confirmations:

- `packetReady -> render submit` p50/p95/p99 fully visible: YES
  (`total=45.197/85.147/106.110` ms at seq=30; decoder p50 ≈ 41.9 ms,
  render p50 ≈ 2.6 ms — real values, consistent with LAT1/LAT2 offline
  expectations, not hardcoded).
- Decoder unmatched visible: YES (`decoderUnmatched=1`, startup-only, stable).
- Render unmatched visible: YES (`renderUnmatched=1`, startup-only, stable).
- Eviction counter visible: YES (`forcedEvict=0`).
- Clock anomaly visible: YES (`clockAnomaly=0`).
- PTS backward counters visible: YES (`ptsBackward=0/0/0/0`).
- Startup invalid handling observed: seq=1 shows `steady=0`, `total=--`,
  `valid=0`, `--/--/--` distributions — no fabricated zeros.

Full JSON truncation was observed in the same run (line length 4100, cut at
`"videoCodec"`), confirming the compact log is the only complete source for the
LAT3 tail fields. Full JSON log is intentionally retained:
`FULL_JSON_LOGCAT_TRUNCATION_EXPECTED` (API JSON unaffected).

# Log Length Evidence

Device runtime: maximum compact message length = **193 chars** (MEDIA line,
seq=3).

Unit test: maximum formatted line length = **192 chars**.

```
MAX_COMPACT_LOG_LENGTH=193
```

Both are far below the 1500-char safe target and far below the logcat ~4000-char
per-entry limit. All lines are single-line (no embedded newline; formatter
sanitizes string fields defensively).

# Performance Regression

- No per-frame / per-packet / per-decoded-frame logging.
- Only 4 short lines per existing Stats cadence (every 5 s), formatted on the
  stats worker path — not in decoder/render hot paths.
- Measured decode/render FPS during steady state: 25.0 / 25.0 (metadata 25);
  no drop in FPS.
- Dropped frames: rendered == frames == packets−1 (3718/3718/3719 at seq=30),
  no new drops.
- Fallback: none (renderer stayed `nv12_gl`, requested `nv12_gl`).
- Reconnect: none during validation (state stayed `playing` across the whole
  window).
- LAT1/LAT2/LAT3 values consistent with offline expectations: client media
  backlog ≈ 40 ms, decoder residence ≈ 40-42 ms, render ≈ 2.6 ms,
  packetReady→submit ≈ 45 ms, occasional demux backlog spike ≈ 40 ms (seq=3).
- Playback behavior: **NO CHANGE**.

# Build

- `git diff --check`: PASS (LF→CRLF notices only, no whitespace errors)
- `:app:testDebugUnitTest`: PASS — 8/8 new formatter tests
  (`LatencyStatsFormatterTest`: valid/invalid values, ms conversion,
  generation, p50/p95/p99, health counters, seq correlation, line length /
  newline safety) + existing `ExampleUnitTest`.
- `:ffmpegplayer:assembleDebug`: PASS (UP-TO-DATE — module untouched by this
  fix; previously validated on the same tree)
- `:ffmpegplayer:assembleRelease`: PASS (UP-TO-DATE — module untouched)
- `:app:assembleDebug`: PASS (executed; BUILD SUCCESSFUL in 39 s)

# Remaining Unknowns

- PRE_T0 (socket receive, RTP assembly/reorder, RTSP demux internal buffering):
  NOT_MEASURED (unchanged).
- POST_T4 (BufferQueue, SurfaceFlinger, HWC, VSync, scanout, LCD):
  NOT_MEASURED (unchanged).
- Real p50/p95/p99 on other streams/devices: pending long-run multi-source
  validation (unchanged scope).

# LAT3 Runtime Freeze Impact

This fix closes the LAT3 log-output visibility gap on a real device
(decoder≈41.9 ms p50, render≈2.6 ms p50, packetReady→submit≈45.2 ms p50,
client media backlog≈40 ms, no eviction/clock/backward anomalies). It does not
begin LAT4 (Decoder Isolation) or LAT5 (RTSP / Pre-T0 Isolation), and it makes
no low-latency tuning changes.

---

## Answers

1. Existing Stats API changed: **NO**
2. Existing Stats metric semantics changed: **NO**
3. Dedicated Logcat tag: **FFmpegLatencyStats**
4. Compact log uses same snapshot: **YES**
5. Snapshot sequence implemented: **YES**
6. PacketReady->RenderSubmit p50/p95/p99 visible: **YES**
7. Decoder unmatched visible: **YES**
8. Render unmatched visible: **YES**
9. Eviction/forced eviction visible: **YES**
10. Clock anomaly visible: **YES**
11. PTS backward counters visible: **YES**
12. Compact lines truncated: **NO**
13. Maximum compact line length: **193 chars**
14. Playback behavior changed: **NO**
15. Latency algorithm changed: **NO**
16. LAT4 started: **NO**
17. LAT5 started: **NO**
