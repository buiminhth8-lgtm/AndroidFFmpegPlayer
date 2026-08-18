# AUDIO PHASE 1 — SLICE A0 — BASELINE & ARCHITECTURE FREEZE

Date: 2026-08-18
Branch: `dev`
Baseline commit: `0628656 fix(player): finalize runtime stats semantics`
Slice result commit: see "Git commit" section.

## Scope

Slice A0 is an **audit-only** slice. It performs:

- Current Audio implementation audit.
- Audio lifecycle audit.
- Audio Recording independence audit.
- Audio Stats semantics freeze.
- Audio Phase 1 architecture freeze.
- AAC -> PCM -> AudioTrack contract freeze.
- Minimal necessary baseline diagnostics / semantic cleanup.
- Report, Build, one independent Git commit.

This slice does **NOT** play sound. It does not implement AAC packet decode,
libswresample PCM, a PCM queue, an audio worker, a JNI PCM callback, AudioTrack,
or A/V sync. Those belong to A1..A6 and are explicitly out of scope here.

The A0 change set is limited to `app/src/main/cpp/native/NativePlayer.cpp` and
only touches audio state semantics plus two diagnostic Stats fields. No video
pipeline, Thermal pipeline, RTSP network policy, recorder algorithm, Java UI, or
dependency was changed.

## Current Audio Architecture

- RTSP / HEVC primary video chain is unchanged (hevc_mediacodec -> NV12 ->
  NativeNv12GlRenderer -> SurfaceView), plus software YUV fallback.
- Recording remains **pure remux**: original compressed packets (video + audio)
  are copied to the output muxer. There is no decode->encode path.
- Audio playback is **not implemented**. The native side opens the FFmpeg audio
  decoder and counts/records compressed audio packets, but never decodes them.

Components involved:

| Component | File | Audio role |
|---|---|---|
| `NativePlayer::openInput()` | NativePlayer.cpp | stream discovery + audio decoder open |
| `NativePlayer::playbackLoop()` | NativePlayer.cpp | audio packet counting / clock mirror / recorder delivery |
| `NativePlayer::setAudioCallback()` | NativePlayer.cpp | JNI audio callback **STUB** |
| `NativePlayer::enableAudio()` | NativePlayer.cpp | user monitoring request flag |
| `PlayerRemuxRecorder` | PlayerRemuxRecorder.cpp | compressed audio packet remux |
| `nativeSetAudioCallback` / `nativeEnableAudio` | native-ffmpeg-jni.cpp | guarded JNI entry points |
| `FFmpegNative.setAudioCallback` / `enableAudio` | FFmpegNative.java | Java native declarations |
| `MediaPlayerActivity` audio switch | MediaPlayerActivity.java | user ON/OFF request UI |

## Current RTSP Audio Discovery

`openInput()` iterates the format context streams and selects the **first**
`AVMEDIA_TYPE_AUDIO` stream. It records:

- codec name (`audioCodec_`),
- bit rate (`audioBitRate_`),
- sample rate (`audioSampleRate_`),
- channel count (`audioChannels_`, from `ch_layout.nb_channels`),
- sample format enum + name (`audioSampleFormat_`, `audioSampleFormatName_`).

Discovery results are committed under `mutex_`; `sourceHasAudio_` is set to
`selectedAudioStreamIndex >= 0`. Discovery runs on prepare and on every
reconnect, so a source transition has-audio <-> no-audio is re-evaluated.

## Current Audio Decoder State

- The audio decoder is opened **unconditionally** inside `openInput()` whenever
  `audioStreamIndex_ >= 0`, independent of `audioEnabled_`.
- Open sequence: `avcodec_find_decoder` -> `avcodec_alloc_context3` ->
  `avcodec_parameters_to_context` -> `avcodec_open2`. Success sets
  `audioDecodeOpened_ = true`; failure records `audioDecodeError_` /
  `audioPlayError_` and leaves `audioDecodeOpened_ = false`.
- Close: `releaseFfmpegResources()` calls `avcodec_free_context` on
  `audioCodecContext_` and resets `audioDecodeOpened_` / `audioPlayable_`.
  This runs on stop, error, reconnect (before re-open), and release.
- Reconnect: `releaseFfmpegResources()` then `openInput(..., resetStreamMetadata=true)`
  re-discovers the audio stream and re-opens the audio decoder. A source that
  changes from has-audio to no-audio therefore ends with `audioDecodeOpened_=false`;
  a no-audio to has-audio transition re-opens the decoder.
- `audioEnabled_` is a **pure user request** and is not reset by source state.

## Current Audio Packet Handling

In `playbackLoop()`, an audio packet (stream index == `audioStreamIndex_`):

- increments `audioPacketCount_` and `audioPacketBytes_`,
- updates `lastAudioFrameTimeMs_`,
- mirrors the packet PTS into `audioClockUs_`
  (`av_rescale_q(packet->pts, audioStream.time_base, AV_TIME_BASE_Q)`),
- is delivered to the recorder (see Recording section).

There is **no** `avcodec_send_packet(audioCodecContext_, ...)` and **no**
`avcodec_receive_frame(audioCodecContext_, ...)` for audio. Compressed audio
packets are counted and remuxed but never decoded into audio frames.

> Audio decoder is opened, but compressed audio packets are not yet decoded into audio frames.

`audioFrameCount_` therefore remains `0`.

## Current Audio Playback Stub

There is no PCM playback path at all. The only audio-related API is the
`setAudioCallback` JNI entry, which is a **STUB**:

- It receives a `jobject callback` but performs no `NewGlobalRef`, no
  `GetMethodID`, no `CallVoidMethod`, and retains no reference to the object.
- It only records `audioCallbackSet_ = (callback != nullptr)`.
- Java (`MediaPlayerActivity`) never calls `setAudioCallback`, so
  `audioCallbackSet_` is `false` in practice.

## Current JNI Audio Callback State

| API | Guarded | Behavior |
|---|---|---|
| `enableAudio(handle, enabled)` | `PlayerOperationGuard` (Fix 4) | sets user request flag only |
| `setAudioCallback(handle, obj)` | `PlayerOperationGuard` (Fix 4) | STUB: records boolean presence only |

Both are reached through the Fix 4 opaque-handle registry and per-operation
guard, so audio APIs are release-safe exactly like every other player API.
The real JNI `AudioPcmSink` (GlobalRef + method ID + DirectByteBuffer PCM
delivery) is Slice A4.

## Current audioClockUs Semantics

`audioClockUs` currently equals the PTS of the **last received compressed audio
packet**, rescaled to microseconds. It is **NOT** a speaker playback clock; there
is no AudioTrack and no playback head.

This field is frozen as **LEGACY / PRE-PLAYBACK CLOCK**. To make this explicit
without breaking existing JSON keys, a new diagnostic field is emitted:

- `audioPlaybackClockValid` : always `false` at A0 (no AudioTrack playback head).
  Slice A5 will drive it from the AudioTrack playback position.

Consumers must not interpret `audioClockUs` as audio playback time.

## Current Sync Master Semantics

- `PlayerOptions.syncMaster` is the **requested** master (default struct value
  `AUDIO`; the `ultra_low_latency` profile sets `VIDEO`).
- `effectiveSyncMaster()` returns `AUDIO` only when
  `isAudioPlaybackMasterAvailable(sourceHasAudio && audioEnabled && audioPlayable && audioClockUs>0)`.
- At A0 `audioPlayable` is always `false`, so `effectiveSyncMaster` is always
  `VIDEO` even when `syncMaster=audio` is requested. Stats report both:
  `syncMaster` (requested) and `effectiveSyncMaster` (actual).
- Stats never claim `effectiveSyncMaster=audio` while `audioPlayable=false`.

## Recording Audio Architecture

`PlayerRemuxRecorder::openOutputLocked()` maps **every** input video/audio stream
to an output stream:

- `avformat_new_stream` + `avcodec_parameters_copy` -> the output audio track
  carries the original compressed audio parameters (e.g. AAC). No audio decode
  or encode occurs.
- The output audio stream is created **only** when the input audio stream has
  valid parameters (`codec_id != NONE`, `sample_rate > 0`, channels > 0);
  otherwise the audio stream is skipped and recording continues video-only.
- A video-only source produces a legitimate video-only MP4.
- `onPacket()` is called by the playback loop for **every** packet, before any
  audio-enabled or drop logic. The recorder therefore receives all original
  compressed audio packets regardless of playback state.
- `audioPlaybackEnabled_` in the recorder is a **report-only** field set from
  `audioEnabled_`; it never gates whether audio packets are written.

Recording stays **remux**: `Original compressed AAC packet -> remux -> MP4`.
The audio playback decoder is never a recording dependency.

## Audio Recording Independence

Confirmed by code:

- `remuxRecorder_.onPacket(packet_, formatContext_)` runs before the audio
  packet branch, the realtime drop policy, and the key-frame gate. Recording is
  never affected by `audioEnabled_`, `audioPlayable_`, or `audioCallbackSet_`.
- Recorder JSON and `enableAudio` JSON both expose
  `audioRecordingIndependentOfPlayback: true`.

Invariants mapped to the spec:

| Spec case | Status |
|---|---|
| CASE 1 video+audio source -> video+audio recording | Supported (remux both) |
| CASE 2 video-only source -> video-only recording | Supported (`sourceHasAudio_=false`, video-only MP4) |
| CASE 3 declared audio stream with no audio packets | Records actual received packets only; no fabricated audio |
| CASE 4 `audioEnabled=false` stops recorder audio | **NO** — recorder is independent |
| CASE 5 future AudioTrack creation failure removes recording audio | **NO** — playback and recording are decoupled |
| CASE 6 future PCM queue overflow drops playback PCM only | **YES** — recorder receives packets before playback queueing |

## Source Has Audio Behavior

- Stream discovered: `sourceHasAudio=true`, codec/sample-rate/channels/format
  recorded; audio decoder opened during prepare/reconnect.
- If audio packets actually arrive, they are counted and delivered to the
  recorder.
- `sourceHasAudio=true` does **not** imply audio packets were received and does
  **not** imply playback is possible.

## Source Has No Audio Behavior

- `sourceHasAudio=false`; audio decoder not opened; `audioPlayable=false`.
- Video-only playback and video-only MP4 recording proceed normally.
- `audioEnabled_` (user request) is intentionally **not** reset by source state.

## Audio Enabled / Disabled Semantics

Frozen semantics:

| Field | Meaning |
|---|---|
| `audioEnabled` | user's request for live speaker monitoring (desired state) |
| `sourceHasAudio` | whether the input stream contains an audio stream |
| `audioDecodeOpened` | whether the FFmpeg audio decoder opened successfully |
| `audioCallbackSet` | whether a Java audio sink object was provided (STUB at A0) |
| `audioPlayable` | whether the full playback pipeline can actually output sound |

`audioEnabled` does **not** mean "source has audio", "decoder opened",
"record audio", or "AudioTrack exists".

With `audioEnabled=true` at A0 the player keeps the requested state but reports
`audioPlayable=false` and a clear `audioPlayError` of
`audio playback pipeline not implemented`. It does not crash, does not block
video playback, and does not block recording.

## Recorder Audio Packet Flow

Playback loop -> `onPacket(packet, formatCtx)` ->
`shouldWritePacketLocked` (stream mapped + key-frame gate) ->
`rotateSegmentIfNeededLocked` (segmented mode) ->
`writePacketLocked` (packet ref, PTS/DTS rebase, rescale, interleave write,
flush for fragmented MP4). Audio packets are written with the same
timestamp-rebase and monotonic-DTS rules as video.

## Final Audio Phase 1 Architecture

```
RTSP
 ├── Video packet
 │     -> current Video Pipeline (unchanged)
 ├── AAC packet
 │     -> FFmpeg AAC Decoder (opened; decoding lands in A1)
 │     -> AVFrame            (A1)
 │     -> libswresample      (A2)
 │     -> PCM S16/48k/stereo interleaved (A2)
 │     -> bounded PCM queue  (A3)
 │     -> audio output worker(A3)
 │     -> JNI AudioPcmSink   (A4)
 │     -> Android AudioTrack (A4)
 └── Original compressed Audio packet
       -> PlayerRemuxRecorder -> MP4 audio track (remux, unchanged)
```

Future slices: A1 decode, A2 swr/PCM, A3 queue+worker, A4 JNI sink+AudioTrack,
A5 playback clock + A/V sync, A6 toggle/pause/reconnect/lifecycle,
A7 recording+D integration+stats regression, A8 long-run validation.

## PCM Output Contract

Frozen for the whole Audio Phase 1:

| Property | Value |
|---|---|
| Format | S16_LE |
| Android encoding | `ENCODING_PCM_16BIT` |
| Interleave | interleaved |
| Sample rate | 48000 Hz |
| Channels | 2 (stereo) |

Every input variant (AAC FLTP 16 kHz stereo, or any other format/rate) is
converted by libswresample to 48 kHz stereo S16 interleaved. Rationale: fixed
AudioTrack configuration, no per-format output-layer rebuild, simpler PCM queue,
simpler playback clock, simpler A/V sync, uniform float/planar conversion, and
stability-first live monitoring.

## Thread Ownership Contract

Frozen for the whole Audio Phase 1:

- AAC decode + swresample: runs on the **playback/demux thread** (lightweight,
  avoids a compressed-audio packet queue and a second decoder ownership thread,
  simplifies reconnect flush).
- AudioTrack blocking output: runs on a dedicated **audio output worker**.
- AudioTrack.write(BLOCKING) must **never** run on the playback/demux thread,
  otherwise backpressure would block `av_read_frame`, video decode, MediaCodec,
  render, and recorder packet delivery.

## PCM Queue Contract

Frozen for the whole Audio Phase 1:

- Bounded; must never grow without limit.
- Target buffered PCM ~100-200 ms; hard max ~200-300 ms (final numbers frozen
  after A3 device validation).
- Queue full: producer must not block. Live low-latency policy: drop oldest /
  flush stale PCM to stay at the live edge.
- PCM queue overflow affects only audio monitoring; the recorder's original
  compressed packets are delivered before any queueing and are never dropped.

## LiveSession / D Boundary

Audio design must not bind AudioTrack deeply to `MediaPlayerActivity`. Future D
owns a `LiveSession` containing the NativePlayer, a live audio sink, and
recording state. No LiveSession refactor is performed in A0; the current Java
ownership is unchanged (no AudioTrack exists yet).

## Local Playback / ExoPlayer Boundary

NativePlayer handles **Live RTSP professional playback only**. Local MP4/VOD is
out of scope for the native player and will be handled by AndroidX Media3 /
ExoPlayer in the D task. Audio Phase 1 implements only live RTSP audio
monitoring. No local MP4 audio, local seek, local duration, VOD A/V sync, or
ExoPlayer integration is added.

## Lifecycle Audit

| Lifecycle point | Audio decoder | audioEnabled (user request) | sourceHasAudio |
|---|---|---|---|
| Create | not opened | kept | false |
| Prepare (`openInput` reset) | opened if audio stream | kept | discovered |
| Start / Playing | open | kept | current |
| Pause | open | kept | current |
| Stop (`releaseFfmpegResources`) | freed | kept | reset to false |
| Reconnect | freed then re-opened | kept | re-discovered |
| Release | freed | kept | reset to false |

`audioEnabled_` is intentionally the only audio field that is never reset by
source or lifecycle transitions.

## Reconnect Audit

- Reconnect path: `releaseFfmpegResources()` -> `openInput(url, timeout, reset=true)`.
- Audio stream is re-discovered and the audio decoder re-opened on each
  successful reconnect.
- The recorder keeps its output context and stream mapping across reconnect;
  packets resume flowing when they return. The recorder's stream mapping is
  fixed at recorder start.
- If a reconnect changes audio stream layout/parameters mid-recording, the
  recorder does not remap (recorded as a Remaining Issue below). This slice does
  not modify recording timestamp/remux behavior.

## Stats Baseline

All requested keys are present with the frozen meanings:

| Key | Source | A0 value |
|---|---|---|
| `sourceHasAudio` | state/stats | true/false by discovery |
| `audioStreamIndex` | state JSON | >=0 when audio stream |
| `audioCodec` | stats | e.g. `aac` |
| `audioSampleRate` | stats | e.g. `16000` |
| `audioChannels` | stats | e.g. `2` |
| `audioSampleFormat` | stats | e.g. `fltp` |
| `audioEnabled` | stats/enableAudio | user request |
| `audioPlayable` | stats/enableAudio/setAudioCallback | **false** at A0 |
| `audioDecodeOpened` | stats (new) / enableAudio | decoder open reality |
| `audioCallbackSet` | stats (new) / setAudioCallback | false at A0 |
| `audioPacketCount` | stats | received compressed audio packets |
| `audioPacketBytes` | stats | received compressed audio bytes |
| `audioFrameCount` | stats | **0** (no decode yet) |
| `audioClockUs` | stats | LEGACY packet-PTS mirror |
| `audioPlaybackClockValid` | stats (new) | **false** at A0 |
| `recordAudioPacketCount` | stats (recorder) | remuxed audio packets |
| `audioRecordingIndependentOfPlayback` | recorder/enableAudio JSON | true |
| `syncMaster` | stats | requested master |
| `effectiveSyncMaster` | stats | actual master (video at A0) |

Added baseline diagnostics (small, high value): `audioDecodeOpened`,
`audioCallbackSet`, `audioPlaybackClockValid`. No future A1..A8 counters
(`audioDecodedFrameCount`, `audioPcmSampleCount`, `audioQueueDropCount`,
`audioSinkWriteCount`, ...) were added.

## Recording Runtime Validation

Not executed. No device/RTSP source with audio packets was available in this
environment, and the audio-OFF recording regression (spec section 34) could not
be run.

```
Recording Runtime Verification: NOT_EXECUTED
Source with audio records audio:            NOT_EXECUTED
Source without audio records video-only:    NOT_EXECUTED
Audio OFF still records source audio:       NOT_EXECUTED
```

These are validation-coverage limits, not known failures. The recording
independence is established by code audit (recorder receives every packet before
any audio-enabled gating).

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF working-copy
  warning for the edited native file).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- CMake rebuilt `libnative-ffmpeg.so` for `arm64-v8a` and `armeabi-v7a`;
  Java layer unchanged. The only output of note is the pre-existing Gradle
  deprecation warning and the existing KAPT unrecognized-options warning.

## Answers to the A0 Questionnaire

1. Current RTSP source audio codec: `<actual>` — codec-agnostic discovery; historical evidence: `aac`. Not re-verified this session (no source).
2. Observed input sample rate: `<actual>` — historical: `16000`. Not re-verified this session.
3. Observed channels: `<actual>` — historical: `2`. Not re-verified this session.
4. Observed sample format: `<actual>` — historical: `fltp`. Not re-verified this session.
5. Is audio decoder currently opened? `<YES when source has an audio stream>` — opened unconditionally in `openInput()`; not opened in this session (no source).
6. Are AAC packets currently actually decoded? **NO** — no `avcodec_send_packet`/`receive_frame` for audio.
7. Is PCM currently produced? **NO**.
8. Is AudioTrack currently used? **NO**.
9. `audioEnabled` means: the user's request for live speaker monitoring. It does not mean source presence, decoder state, recorder state, or sink state.
10. `audioPlayable` means: whether the full audio playback pipeline can actually output sound. `false` at A0 (no PCM sink / AudioTrack).
11. `audioClockUs` represents: the PTS of the last received compressed audio packet (LEGACY / PRE-PLAYBACK clock). `audioPlaybackClockValid=false`.
12. Does `audioEnabled=false` disable recording audio? **NO**.
13. Does the recorder remux original compressed AAC? **YES** when a valid AAC audio stream/packets exist.
14. Video-only source: legitimate video-only MP4; `recordAudioPacketCount=0`; recording succeeds.
15. Audio stream exists but no audio packets: the recorder records only actual received packets (no fabricated audio); `recordAudioPacketCount=0`.
16. Future AAC decode thread: **PLAYBACK_THREAD**.
17. Future AudioTrack.write thread: **AUDIO_OUTPUT_WORKER**.
18. Final PCM contract: **S16_LE / 48000 Hz / stereo / interleaved** (`ENCODING_PCM_16BIT`).
19. Does Local MP4 use this Native Audio pipeline? **NO** — ExoPlayer/Media3 is out of scope.
20. Which future slice first produces audible sound? **A4**.

## Remaining Issues

- **Recording runtime verification not executed** (no device/source in this
  environment). Spec sections 32-35 are code-audited only.
- **Recorder stream mapping is fixed at recorder start.** A reconnect that
  changes the audio stream layout/parameters mid-recording is not remapped;
  recording timestamp/remux behavior was intentionally not modified in A0.
- **`setAudioCallback` is a STUB.** The callback object is not retained and no
  PCM delivery exists. Real JNI `AudioPcmSink` lands in A4.
- **Pre-existing concurrency note:** `audioPlayError_` / `audioDecodeError_`
  are plain `std::string` members written from the playback/reconnect thread
  (`openInput`) and from JNI threads (`enableAudio` / `setAudioCallback`)
  without a mutex. Pre-existing and out of A0 scope.
- **`syncMaster` requested default is `audio`** while `effectiveSyncMaster` is
  `video` until audio is playable. Consumers must read `effectiveSyncMaster`.
- **`audioFrameCount` stays 0** until A1 decodes frames — by design, not a bug.

## Slice A0 Freeze

Hard-gate checklist:

- Current Audio implementation fully audited: **YES**.
- Recording Audio path confirmed (compressed remux, stream mapping): **YES**.
- Audio Playback / Recording decoupled: **YES**.
- `audioEnabled` semantics frozen (pure user request): **YES**.
- `audioPlayable` semantics frozen (false until real sink): **YES**.
- `audioClockUs` semantics explicit (LEGACY packet-PTS, `audioPlaybackClockValid=false`): **YES**.
- `syncMaster` fallback semantics explicit (`effectiveSyncMaster=video` while not playable): **YES**.
- PCM S16/48k/stereo contract frozen: **YES**.
- Playback vs Audio Worker ownership frozen: **YES**.
- AudioTrack future thread ownership frozen (dedicated output worker): **YES**.
- PCM queue backpressure policy frozen (bounded, drop-oldest, live edge): **YES**.
- Local ExoPlayer clearly out-of-scope: **YES**.
- Video main chain unmodified: **YES** (native diff touches audio semantics only).
- Thermal unmodified: **YES**.
- Recorder algorithm unchanged: **YES**.
- Build: **PASSED**.

Recording runtime verification (device/source dependent) is
`NOT_EXECUTED`; per spec section 49 this does not block the architecture freeze
and `Recording Runtime Verified: YES` is not claimed.

**Slice A0 Architecture Freeze: YES**

## Git Commit

Commit message: `chore(player): establish live audio playback baseline`