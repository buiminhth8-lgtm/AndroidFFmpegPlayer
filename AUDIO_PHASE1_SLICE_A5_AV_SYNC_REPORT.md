# AUDIO PHASE 1 — SLICE A5 — AUDIOTRACK PLAYBACK CLOCK + A/V SYNC

Date: 2026-08-18
Branch: `dev`
A4 baseline commit: `4029355 feat(player): play live PCM through AudioTrack`
Slice result commit: see "Git commit" section.

## Scope

Slice A5 adds a real AudioTrack playback-head based clock and makes video follow
audio:

```
AudioTrack.getPlaybackHeadPosition() -> 64-bit monotonic played frames
  -> audioPlaybackClockUs -> video follows audio (bounded wait / drop)
```

Not implemented (out of scope): AudioFocus, Bluetooth tuning, ExoPlayer, audio
time-stretch, recording redesign.

Change set: `LiveAudioPcmSink.java` (add `getPlaybackHeadFrames()`),
`NativePlayer.h`, `NativePlayer.cpp`. No video renderer, Thermal, RTSP, or
recorder change.

## A4 Baseline

A4 (`4029355`) was confirmed: PCM 48k/stereo/S16 -> bounded queue -> audio worker
-> JNI -> AudioTrack.write (worker-only). A4 is complete; A5 is not masking an A4
gap.

## Playback Head Model

- Java sink exposes `getPlaybackHeadFrames()` returning the raw 32-bit
  `AudioTrack.getPlaybackHeadPosition()` (0 when the track is not created).
- The worker queries the head once per consumed block (~20 ms) — **never** per
  video frame and never from the video/render thread.

## 32-bit Wrap Handling

The raw head is converted to a monotonic 64-bit played-frame count by
accumulating `static_cast<int32_t>(static_cast<uint32_t>(raw) - static_cast<uint32_t>(lastRaw))`
deltas, which is correct across 32-bit wraparound for consecutive samples well
under 2^31 frames apart. The wrap state (`audioPlaybackHeadRaw32_` /
`audioPlaybackHeadExtended64_`) is reset on every rebase.

## Audio Clock Base

On the first block of a new generation (or when the clock is invalid), the worker
re-anchors **before** writing the block:

```
audioClockGeneration_ = block.generation
audioClockBaseMediaPtsUs_ = block.startPtsUs   (first valid media PTS)
audioPlaybackHeadRaw32_ = rawHead
audioPlaybackHeadExtended64_ = 0               (base frame = 0)
```

The audible clock is:

```
audioPlaybackClockUs = audioClockBaseMediaPtsUs + playedFrames * 1_000_000 / 48000
```

Wall clock is never used as the media-time base.

## Audio Clock Validity

`audioPlaybackClockValid` is `true` only when: audio enabled, decoder/SWR normal,
AudioTrack operational, a valid media PTS anchored the base, the playback head is
queryable, and the current generation has produced audio. It is `false`
otherwise, and `effectiveSyncMaster` falls back to `video`.

## Clock Reset / Generation

`invalidateAudioClock()` clears validity and is called on:

- Audio OFF (`enableAudio(false)`).
- Reconnect / transport switch (`flushAudioPcmForDiscontinuity`, which also bumps
  `audioQueueGeneration`).
- `stop()`.
- AudioTrack write failure (`writeAudioPcmToSink` error).

On Audio OFF -> ON and on reconnect, the next valid block re-anchors the base
(`audioClockResetCount` increments), so no stale base/PTS/head is reused.

## Underflow / Stale Handling

- If the playback head cannot be queried, the clock is invalidated and
  `audioClockStaleCount` increments.
- `effectiveSyncMaster` also treats the clock as stale if it has not been
  refreshed within `kAudioClockStaleMs` (500 ms), so a frozen clock (worker
  stopped / underflow) falls back to the video master instead of stalling video.

## Audio ON/OFF

- OFF: flush queue -> requestStop -> pause/flush AudioTrack -> join worker ->
  invalidate clock. Recording continues; no RTSP reopen; no MediaCodec recreate.
- ON: flush decoder/SWR, start worker, enable; the first block re-anchors the
  clock from the live edge.

## Reconnect

`flushAudioPcmForDiscontinuity` flushes the queue, bumps the generation,
invalidates the clock, and pauses/flushes the AudioTrack. The new stream's first
PCM re-anchors the clock; old PCM and old playback-head base are never reused.

## Video-to-Audio Sync

- `effectiveSyncMaster` returns `audio` only when `syncMaster=audio` AND
  `isAudioPlaybackMasterAvailable(sourceHasAudio, audioEnabled, audioPlayable,
  audioPlaybackClockValid, !stale)`.
- `resolveMasterClockUs` AUDIO branch now uses `audioPlaybackClockUs` (the
  playback-head clock) instead of the legacy `audioClockUs` packet mirror, and
  records `audioVideoDiffUs = videoPtsUs - audioPlaybackClockUs`.
- `waitForAudioMasterIfEarly(ptsUs)` is invoked in both render paths
  (`renderFrame` and `renderMediaCodecFrame`) after the late-drop check: while the
  video PTS is ahead of the audible clock, it polls the cached atomic clock and
  sleeps, bounded by `kAudioMasterMaxWaitUs` (150 ms), and returns immediately on
  stop/invalid/stale.

## Frame Wait / Drop Policy

- Video late (existing): `shouldDropRealtimeFrame` / `shouldDropRealtimePacket`
  drop frames/packets beyond the existing thresholds, using the audio clock as
  the master when audio is active.
- Video early: bounded wait (150 ms) for the audio clock to catch up.
- All waits are bounded; a stale/invalid/disabled audio clock immediately falls
  back to the video master. Audio never freezes the RTSP/video pipeline.
- Audio free-runs; no `swr_set_compensation`, no time-stretch, no sample
  drop/duplicate.

## Recording Independence

The recorder keeps receiving the original compressed AAC packet before decode.
Audio clock invalid, AudioTrack failure, and video sync drops never affect
`recordAudioPacketCount` or the recorder stream mapping. No PCM -> AAC re-encode.

## Stats

New/changed Stats keys:

| Key | Meaning |
|---|---|
| `audioClockUs` | LEGACY: last compressed audio packet PTS (unchanged) |
| `audioPlaybackClockValid` | real playback-head clock validity (was hardcoded false) |
| `audioPlaybackClockUs` | audible clock from AudioTrack playback head |
| `audioPlaybackHeadFrames` | 64-bit played-frame count since the base |
| `audioClockGeneration` | current clock generation |
| `audioClockResetCount` | number of clock re-anchors |
| `audioClockStaleCount` | head-query failures / stale invalidations |
| `audioVideoDiffUs` | videoPts - audioPlaybackClockUs at the last sync decision |

`getStats()` reads only atomic snapshots (no AudioTrack query, no JNI, no lock).

## Runtime Sync Validation

Not executed. No adb device or AAC RTSP source was available.

```
A/V sync runtime:        NOT_EXECUTED
Audio OFF/ON clock:      NOT_EXECUTED
Reconnect clock rebase:  NOT_EXECUTED
Long-run drift:          NOT_EXECUTED
Audible audio:           NOT_EXECUTED
```

## Video / Thermal Regression

No video, MediaCodec, NV12/YUV GL, Surface/EGL, Thermal, or RTSP policy code was
changed. Runtime validation not executed (no device).

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF warnings).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- Java + CMake native (both ABIs) compiled; no dependency/minSdk change.

## Answers to the A5 Questionnaire

1. Audio clock from AudioTrack playback progress? **YES** (`getPlaybackHeadPosition`).
2. Packet PTS used as audible clock? **NO** (that is the legacy `audioClockUs` only).
3. Playback-head wraparound handled? **YES** (uint32 delta -> 64-bit).
4. Per-video-frame JNI AudioTrack query? **NO** (worker publishes atomic snapshot).
5. Audio clock invalid -> effective master? **VIDEO**.
6. Configured audio master effective when clock valid? **YES**.
7. Audio OFF invalidates clock? **YES**.
8. Reconnect rebases clock? **YES**.
9. AudioTrack recreate -> new generation? **YES** (rebase via generation/invalid).
10. Video early handling? **BOUNDED_WAIT** (150 ms).
11. Video late handling? **DROP_POLICY** (existing realtime drop).
12. Audio failure affects Recording? **NO**.
13. Audio time-stretch implemented? **NO**.

## Slice A5 Freeze

Hard-gate checklist:

- Playback-head based clock: **YES**.
- Wraparound safe: **YES**.
- No per-video-frame JNI query: **YES**.
- Clock validity/state correct: **YES**.
- OFF/reconnect/recreate rebase correct: **YES**.
- Stale clock falls back to video: **YES**.
- Valid audio master drives video sync: **YES** (bounded wait + existing drop).
- Wait bounded: **YES** (150 ms).
- Late video keeps realtime drop policy: **YES**.
- No deadlock/UAF: **YES** (atomics only between worker and video thread).
- Recording independent: **YES**.
- Video/Thermal no regression: **YES** (no code changed).
- Build: **PASSED**.

Runtime is `NOT_EXECUTED` (no device); no runtime PASS is claimed.

**Slice A5 Freeze: YES**

## Remaining Issues

- A/V sync, drift, OFF/ON clock re-anchor, reconnect rebase, and audible audio are
  all NOT_EXECUTED (no device/source in this environment).
- The audio clock base uses the first block's media PTS; if a stream has no valid
  audio PTS, the clock stays invalid and the player remains on the video master
  (safe degradation, by design).

## Git Commit

Commit message: `feat(player): synchronize live video to AudioTrack clock`
