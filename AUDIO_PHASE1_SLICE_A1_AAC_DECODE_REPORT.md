# AUDIO PHASE 1 — SLICE A1 — AAC PACKET → DECODED AUDIO AVFRAME

Date: 2026-08-18
Branch: `dev`
A0 baseline commit: `b0ac581 chore(player): establish live audio playback baseline`
Slice result commit: see "Git commit" section.

## Scope

Slice A1 implements exactly one step of the Audio Phase 1 architecture:

```
RTSP -> FFmpeg demux -> AAC AVPacket -> FFmpeg AAC Decoder -> decoded audio AVFrame -> count -> discard
```

Concretely:

- `avcodec_send_packet(audioCodecContext_, packet)`
- `avcodec_receive_frame(audioCodecContext_, audioDecodedFrame_)`
- decode statistics / validation
- discard (no PCM, no AudioTrack)

After this slice:

```
AAC packet decoding:  YES
PCM:                  NO
AudioTrack:           NO
Audible audio:        NO
A/V sync:             NO
```

The change set is limited to `app/src/main/cpp/native/NativePlayer.h` and
`NativePlayer.cpp`. No video pipeline, Thermal pipeline, RTSP policy, recorder
algorithm, Java UI, or dependency was changed. No PCM, swresample, PCM queue,
audio worker, JNI PCM callback, AudioTrack, or A/V sync is implemented.

## A0 Baseline

A0 froze (per `AUDIO_PHASE1_SLICE_A0_BASELINE_ARCHITECTURE_REPORT.md`):

- `audioEnabled` = user's live-monitoring request only.
- `audioPlayable` = full pipeline playable; false at A0 (no PCM sink).
- `audioClockUs` = LEGACY / PRE-PLAYBACK clock mirroring the last compressed
  audio packet PTS; `audioPlaybackClockValid=false`.
- `setAudioCallback` is a STUB (boolean presence only; no GlobalRef / method ID).
- Recording is independent of playback: the recorder receives every packet
  before any audio-enabled/drop gating, and remuxes the original compressed AAC.
- `effectiveSyncMaster` stays `video` while audio is not playable.

A1 keeps all of these frozen semantics and adds real AAC decoding on top.

## Audio Packet Flow Before Fix

Playback loop per packet (`playbackLoop()`):

1. `av_read_frame()`.
2. Packet counting: video or audio counters (`videoPacketCount` /
   `audioPacketCount`, bytes, `lastAudioFrameTimeMs`).
3. For audio: `audioClockUs` mirror = packet PTS rescaled to microseconds.
4. `remuxRecorder_.onPacket(packet, formatCtx)` for every packet (recording
   remux; independent of audio state).
5. Realtime drop policy and startup key-frame gate (non-video packets dropped
   while waiting for the first video key frame).
6. Video branch: `avcodec_send_packet` / `avcodec_receive_frame` for video.
7. `av_packet_unref(packet_)`.

Before A1, audio packets were counted and remuxed but **never decoded**: no
`avcodec_send_packet`/`avcodec_receive_frame` existed for `audioCodecContext_`,
and `audioFrameCount` was permanently `0`.

## Audio Packet Flow After Fix

Same loop, with one new branch after the video branch:

```
} else if (packet_->stream_index == audioStreamIndex_) {
    decodeAudioPacket(packet_);
}
```

`decodeAudioPacket()` (playback thread):

1. Returns early when there is no decoder, no opened audio decoder, or
   `audioEnabled=false` (A1 policy: OFF skips playback AAC decode).
2. Times `send_packet + receive loop`.
3. `avcodec_send_packet(audioCodecContext_, packet)`:
   - `EAGAIN` -> drain pending frames, retry send once.
   - still `EAGAIN` after retry -> drain again and drop this packet this cycle
     (no packet queue exists in A1; the next packet's drain keeps the decoder
     moving — a defined design, not a permanent loss).
   - `AVERROR_EOF` -> decoder flushed; receive loop ends on `EOF`.
   - other `<0` -> `audioDecodeErrorCount++`, rate-limited error log.
4. `drainAudioDecodedFrames()` loops `avcodec_receive_frame` until `EAGAIN` or
   `EOF` (a single packet may yield 0, 1, or many frames; output is fully
   drained). Each received frame: count frame + samples, record metadata and
   PTS, then `av_frame_unref`.
5. Records decode timing (`last/avg/max audio decode cost`).

The recorder still receives the original compressed packet earlier in the same
iteration, so playback decode is fully downstream of recording.

## Recorder Packet Independence

Preserved unchanged from A0:

- `remuxRecorder_.onPacket(...)` runs before the audio decode branch and before
  any audio-enabled gating.
- `audioEnabled=false` skips playback decode but does **not** stop the recorder
  from writing audio packets.
- `recordAudioPacketCount` grows whenever the source actually delivers audio
  packets and recording is active, independent of the audio switch.

## Recorder Packet Mutation Audit

Audited `PlayerRemuxRecorder::writePacketLocked()` (called from `onPacket`):

- It creates `recordPacket = av_packet_alloc()`, then
  `av_packet_ref(recordPacket, packet)`. `av_packet_ref` copies the packet
  metadata into a **separate** `AVPacket` struct and shares the underlying data
  buffer through a refcount (read-only for the recorder).
- All mutations (`pts`, `dts`, `stream_index`, `av_packet_rescale_ts`) are
  applied to `recordPacket` only. The input `packet_` metadata and data are
  never written.
- No `av_packet_rescale_ts` or PTS/DTS rewrite is ever applied to the input
  packet.

Conclusion: the AAC decoder receives the **original demux packet semantics**.
No recorder fix was required in A1. Answer: recorder packet mutation = **NONE**
(recorder refs its own packet copy; decoder packet untouched).

## Audio Decoder Lifecycle

- **Open**: inside `openInput()` whenever an audio stream is discovered,
  regardless of `audioEnabled` (so Audio ON never reopens RTSP). Sequence:
  `avcodec_find_decoder` -> `avcodec_alloc_context3` ->
  `avcodec_parameters_to_context` -> `avcodec_open2`. On success the reusable
  `audioDecodedFrame_` is allocated once (`av_frame_alloc`) and
  `audioDecodeOpened=true`.
- **Close**: `releaseFfmpegResources()` frees `audioDecodedFrame_`
  (`av_frame_free`) then `audioCodecContext_` (`avcodec_free_context`), and
  resets `audioDecodeOpened=false`. Runs on stop, error, reconnect (before
  re-open), and release.
- **Reconnect**: `releaseFfmpegResources()` then `openInput(..., reset=true)`
  re-discovers the audio stream and re-opens the decoder with a fresh context
  and frame. The old context is never used to decode a new stream's packets.
- **AVFrame allocation**: exactly once per decoder open. Frames are reused and
  `av_frame_unref`'d after each receive. No per-frame `av_frame_alloc/free`.
- **Toggle OFF**: `enableAudio(false)` sets an atomic `audioFlushRequested` flag;
  the playback thread consumes it at the top of the loop and calls
  `avcodec_flush_buffers(audioCodecContext_)`, so a later ON never outputs stale
  pre-toggle frames. The flush runs on the playback thread (no cross-thread codec
  access) and never reopens RTSP or touches the video decoder.

## Audio AVFrame Lifecycle

- Dedicated member `AVFrame *audioDecodedFrame_` — **not** the video
  `decodedFrame_`. Video (MediaCodec/software) and AAC never share an `AVFrame`.
- Allocated once when the audio decoder opens; freed in
  `releaseFfmpegResources()`.
- After every successful `avcodec_receive_frame`, the frame is consumed
  (metadata/stats) and `av_frame_unref`'d before the next receive.
- No leaks, no double free, no per-frame allocation.

## AAC Send / Receive Loop

Send/receive follows the FFmpeg contract:

- One packet may produce zero, one, or multiple frames; the receive loop drains
  until `AVERROR(EAGAIN)` or `AVERROR_EOF`.
- `EAGAIN` on send is handled by draining pending output and retrying once.
- `AVERROR_EOF` on send/receive terminates the drain cleanly.
- `audioDecodedFrameCount` (alias of the redefined `audioFrameCount`) therefore
  is allowed to differ from `audioPacketCount`; AAC packet/frame is not 1:1.

## EAGAIN Handling

`send_packet` returning `EAGAIN` means the decoder's output is not yet drained.
A1 behavior:

1. Drain pending frames (`drainAudioDecodedFrames`).
2. Retry `avcodec_send_packet` once.
3. If still `EAGAIN`, drain again and drop this packet for this cycle. There is
   no packet queue in A1 (out of scope); the design is explicit and does not
   permanently stall the decoder.

This is a defined design decision (documented in code and here), not an
accidental permanent packet loss.

## Decode Error Handling

- A corrupted AAC packet increments `audioDecodeErrorCount` and logs at most one
  line per 2 s (rate-limited, `logRateLimitedAudioDecodeError`).
- Errors never stop video, never trigger `stopPlayer`, never reconnect RTSP,
  never fall back the video decoder, and never touch MediaCodec.
- A single audio decode error simply continues demux/decode/render/record.
- No complex recovery (N-error reconnect, reopen) is implemented in A1.

## Audio Enabled Decode Policy

Frozen: `audioEnabled=false` **skips** playback AAC decode (no pointless CPU
cost when the user is not monitoring). `audioEnabled=true` decodes AAC frames
and discards them.

- `audioPacketCount` still grows on demux regardless of the switch (it is an
  input-packet counter).
- `audioDecodedFrameCount` / `audioDecodedSampleCount` grow only when audio is
  ON (real decode).
- The recorder's `recordAudioPacketCount` grows whenever recording is active
  and audio packets arrive — independent of the switch.

Audio ON never reopens RTSP, never recreates the video decoder/MediaCodec, and
never touches recording. It only activates the playback AAC decode path from the
current live position (no historical PCM queue exists, so there is nothing to
catch up on).

## Audio Disabled Behavior

- Playback decode skipped; decoder remains open (so ON is instant).
- The decoder is flushed on the OFF transition (via the playback-thread flag),
  so stale pre-toggle frames are never emitted on a later ON.
- Recording continues to remux audio packets.
- Video is unaffected.

## Decoded Audio Metadata

For every successfully decoded frame (project uses the new `AVChannelLayout`
API — `ch_layout.nb_channels` — and `AVSampleFormat`):

- `nb_samples` -> `lastDecodedAudioNbSamples`
- `sample_rate` -> `lastDecodedAudioSampleRate`
- `ch_layout.nb_channels` -> `lastDecodedAudioChannels`
- `format` -> `lastDecodedAudioSampleFormat` (emitted as format name)

The existing `audioSampleRate` / `audioChannels` / `audioSampleFormat` fields
remain the **input/decoder-configured** stream metadata (from codecpar). The new
`lastDecodedAudio*` fields are the **actual decoded frame** values; AAC may
report identical values, but the fields are semantically distinct.

## Decoded Audio PTS

- `frame->best_effort_timestamp` is preferred; fallback to `frame->pts`.
- Rescaled from the audio stream time_base to microseconds with
  `av_rescale_q` (same helper/pattern as the video PTS path).
- Stored in `lastDecodedAudioPtsUs` and updated on every decoded frame.

## audioClockUs Compatibility

- `audioClockUs` keeps its A0 **LEGACY / PRE-PLAYBACK** meaning: the last
  compressed audio packet PTS mirror. It is not redefined as a playback clock.
- `audioPlaybackClockValid` remains `false` (real AudioTrack playback head is
  Slice A5).
- `lastDecodedAudioPtsUs` is the new precise decoded-frame PTS; it is **not**
  an audible/speaker playback clock.

## Stats

New Stats keys (all real, none fabricated):

| Key | Meaning |
|---|---|
| `audioDecodedFrameCount` | decoded Audio AVFrame count (alias of `audioFrameCount`, which was never incremented before A1 — safe redefinition) |
| `audioFrameCount` | same value (compatibility field, now defined as decoded frames) |
| `audioDecodedSampleCount` | total decoded samples (sum of `nb_samples`) |
| `audioDecodeErrorCount` | decode send/receive errors |
| `lastDecodedAudioPtsUs` | last decoded frame media PTS in microseconds |
| `lastDecodedAudioNbSamples` | last decoded frame sample count |
| `lastDecodedAudioSampleRate` | last decoded frame sample rate |
| `lastDecodedAudioChannels` | last decoded frame channel count |
| `lastDecodedAudioSampleFormat` | last decoded frame sample format name |
| `lastAudioDecodeCostUs` | last send+receive decode cost (µs; `-1` before first) |
| `avgAudioDecodeCostUs` | average decode cost (µs) |
| `maxAudioDecodeCostUs` | max decode cost (µs) |

All counters follow the project style: hot-path atomics, no global stats lock,
no per-frame JSON, timing recorded with the existing `recordCost` helper
(send + receive loop per the task's timing definition). Lifetime counters are
cumulative across reconnect; they reset only on `resetStats()` (prepare). The
"last decoded" fields reset on source change (`openInput` resetStreamMetadata)
and on stats reset.

## Reconnect Behavior

- Old decoder context and audio frame are freed first
  (`releaseFfmpegResources`).
- New input re-discovers the audio stream and re-opens the decoder.
- The old context is never used to decode a new stream.
- Lifetime counters continue across reconnect; `lastDecodedAudio*` are reset at
  source change and updated by the new stream's frames.
- If the source changes format (e.g. 16 kHz stereo -> 48 kHz mono), the decoder
  is re-opened from the new codecpar. No swr/output reconfiguration is needed in
  A1 because no PCM output exists.

## Audio Toggle

`enableAudio(false)` sets `audioFlushRequested`; the playback thread flushes the
audio decoder. `enableAudio(true)` just sets the flag; the next audio packet
starts decoding from the current live position. No RTSP reopen, no MediaCodec
recreate, no recorder restart, no decoder re-open. Toggling is cheap and
thread-safe (codec mutation only ever happens on the playback thread).

## Video Regression

No video code was changed. The new audio branch is an `else if` on the audio
stream index and cannot alter the video decode/render path. `decodeAudioPacket`
is synchronous and lightweight; per the A0/Phase architecture, audio decode cost
is small relative to demux/decode/render. Runtime video validation was not
executed this session (no device/source); the code path is unchanged.

## Thermal Regression

No Thermal code was touched. A1 adds no shader, LUT, gamma, window, or AGC
change.

## Recording Regression

Recorder behavior is untouched. The recorder still receives every packet before
the audio decode branch and remuxes original compressed AAC. Playback decode
errors cannot affect `recordAudioPacketCount`, stop recording, or change the
recorder stream mapping.

## Performance

A1 records `lastAudioDecodeCostUs` / `avgAudioDecodeCostUs` /
`maxAudioDecodeCostUs` (send + receive loop). No device was available to measure
the impact on video FPS or to compare before/after decode. Baseline:
`NOT_AVAILABLE` for a measured before/after comparison. Code review shows the
decode runs synchronously on the playback thread and is expected to be light
(AAC software decode); this must be confirmed on device in the validation phase.

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF warnings).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- CMake rebuilt `libnative-ffmpeg.so` for `arm64-v8a` and `armeabi-v7a`.
- Existing KAPT unrecognized-options warning and Gradle deprecation warning
  remain; no unrelated test/build infrastructure was modified.

## Runtime Verification

Not executed. No adb device, no RTSP source, and no actual AAC packet stream
were available in this environment.

```
Audio Decode Runtime:        NOT_EXECUTED
Recording Audio Verification: NOT_EXECUTED
Audio Toggle runtime:         NOT_EXECUTED
Reconnect Audio Decoder:      NOT_EXECUTED
```

No runtime `PASS` is claimed; acceptance criteria (79-81) remain to be verified
on device. The code/architecture freeze below is based on code audit + build.

## Answers to the A1 Questionnaire

1. Current audio codec: codec-agnostic discovery; historical evidence `aac`. Not re-verified this session (no source).
2. Audio decoder opened: YES when an audio stream exists (opened in `openInput`). Not exercised this session.
3. Before A1, were AAC packets actually decoded? **NO** (counted/remuxed only).
4. After A1, are AAC packets passed to `avcodec_send_packet`? **YES** (when `audioEnabled=true`).
5. Is `avcodec_receive_frame` drained until EAGAIN/EOF? **YES**.
6. Is a dedicated reusable Audio AVFrame used? **YES** (`audioDecodedFrame_`, allocated once).
7. Is Video AVFrame reused for Audio? **NO** (dedicated frame; correct answer).
8. Does Recorder receive original compressed AAC? **YES** (before decode, remux only).
9. Does Recorder mutate the decoder packet? **NO** — recorder operates on an `av_packet_ref`'d copy; input packet metadata/data untouched.
10. Does `audioEnabled=false` disable recording audio? **NO**.
11. Does `audioEnabled=false` skip playback AAC decode? **YES** — frozen policy: OFF skips playback decode to avoid useless CPU; recorder remains independent.
12. Does `audioEnabled=true` make `audioPlayable=true`? **NO** (no PCM sink / AudioTrack).
13. Is PCM produced? **NO**.
14. Is libswresample used? **NO**.
15. Is AudioTrack used? **NO**.
16. Is audible audio available? **NO**.
17. `lastDecodedAudioPtsUs` represents: the last decoded audio frame's media PTS in microseconds (best-effort timestamp, fallback pts), from the input stream time base.
18. `audioClockUs` after A1: unchanged LEGACY / PRE-PLAYBACK field mirroring the last compressed audio packet PTS; `audioPlaybackClockValid=false`.
19. Is effectiveSyncMaster still video? **YES** (`audioPlayable=false`, `isAudioPlaybackMasterAvailable` false).
20. Does Audio Decode error stop Video? **NO** (count + rate-limited log + continue).
21. Does Audio toggle reopen RTSP? **NO** (flush flag only on the playback thread).
22. Does Audio toggle recreate MediaCodec Video decoder? **NO**.
23. Audio decoder on reconnect: old context+frame freed, new stream re-discovered, decoder re-opened from new codecpar.

## Slice A1 Freeze

Hard-gate checklist:

- AAC packets enter the FFmpeg audio decoder: **YES** (`avcodec_send_packet`).
- Send/receive contract correct (EAGAIN drain+retry, EOF, other errors): **YES**.
- Receive output fully drained until EAGAIN/EOF: **YES**.
- Reusable Audio AVFrame: **YES** (`audioDecodedFrame_`, once per open).
- No per-frame AVFrame allocation: **YES**.
- Decoded samples countable: **YES** (`audioDecodedSampleCount`).
- Decoded PTS countable: **YES** (`lastDecodedAudioPtsUs`).
- Audio decode error does not affect video: **YES**.
- Audio recording independent: **YES** (unchanged remux; recorder first).
- Audio OFF does not close recorder audio: **YES**.
- No PCM: **YES**. No swresample: **YES**. No PCM queue: **YES**.
- No AudioTrack: **YES**. No A/V sync: **YES**.
- Video main chain no regression: **YES** (no video code changed).
- Thermal no regression: **YES** (no Thermal code changed).
- Build: **PASSED**.

Runtime verification is `NOT_EXECUTED` (no device/source). Per the slice spec,
the architecture/code freeze is recorded from code audit + build, and
`Runtime Verified` is **NO / NOT_EXECUTED**.

**Slice A1 Freeze: YES**

## Git Commit

Commit message: `feat(player): decode live AAC audio frames`