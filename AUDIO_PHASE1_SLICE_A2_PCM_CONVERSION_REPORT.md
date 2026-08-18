# AUDIO PHASE 1 — SLICE A2 — DECODED AUDIO AVFrame → PCM S16 / 48k / STEREO / INTERLEAVED

Date: 2026-08-18
Branch: `dev`
A1 baseline commit: `9fb8eac feat(player): decode live AAC audio frames`
Slice result commit: see "Git commit" section.

## Scope

Slice A2 implements the second step of the Audio Phase 1 architecture:

```
RTSP -> AAC AVPacket -> FFmpeg AAC Decoder -> decoded Audio AVFrame
  -> libswresample -> PCM S16 / 48000 Hz / stereo / interleaved -> stats -> discard
```

After this slice:

```
AAC Decode:          YES
PCM Conversion:      YES
PCM Queue:           NO
Audio Worker:        NO
JNI PCM Sink:        NO
AudioTrack:          NO
Audible Audio:       NO
A/V Sync:            NO
```

The change set is limited to `app/src/main/cpp/native/NativePlayer.h` and
`NativePlayer.cpp`. No video pipeline, Thermal pipeline, RTSP policy, recorder
algorithm, Java UI, or dependency was changed. No PCM queue, audio worker, JNI
PCM callback, AudioTrack, or A/V sync is implemented.

## A1 Baseline

A1 (`9fb8eac`) was confirmed in place before A2:

- `decodeAudioPacket()` sends packets to `avcodec_send_packet(audioCodecContext_, ...)`
  and drains `avcodec_receive_frame` until EAGAIN/EOF.
- A dedicated reusable `audioDecodedFrame_` exists (allocated once per decoder
  open, freed in `releaseFfmpegResources`).
- `audioDecodedFrameCount` / `audioDecodedSampleCount` grow when Audio is ON.
- No PCM, SwrContext, or AudioTrack existed at A1 (verified by code audit).

A1 facts reported in the A1 report matched the current source; no discrepancies
were found. A1 core capability is complete, so A2 is not masking an A1 gap.

## Input Audio Format

- Input is the decoded Audio AVFrame produced by the FFmpeg audio decoder.
- The input format identity is taken from the **real decoded frame**:
  `frame->format` (sample format), `frame->sample_rate`, and
  `frame->ch_layout` (channel count + native layout mask).
- Fallback to the codec context fields only when the frame lacks them.
- Historical target: AAC / 16000 Hz / 2 ch / FLTP. A2 is codec-agnostic and
  format-agnostic (no `if FLTP then ... else unsupported`); any decoded audio
  format is converted through the generic swr path.
- FFmpeg API used is the modern `AVChannelLayout` API (project uses
  `ch_layout` everywhere; no FFmpeg upgrade was made).

## Output PCM Contract

Frozen (matches A0/A1 contract):

| Property | Value |
|---|---|
| Output sample format | `AV_SAMPLE_FMT_S16` (signed 16-bit little-endian) |
| Output sample rate | 48000 Hz |
| Output channels | 2 |
| Output channel layout | Stereo |
| Output layout | Interleaved |
| Bytes per sample | 2 |
| Bytes per PCM frame | 2 ch x 2 bytes = 4 bytes |

Constants in code: `kAudioPcmOutputSampleRate=48000`,
`kAudioPcmOutputChannels=2`, `kAudioPcmOutputFormat=AV_SAMPLE_FMT_S16`.

## libswresample Architecture

- `SwrContext` is owned by the NativePlayer live audio playback pipeline
  (`audioSwrContext_`).
- Conversion uses `swr_alloc_set_opts2` (modern AVChannelLayout API),
  `swr_init`, `swr_convert`, `swr_get_delay`, `swr_free`.
- No hand-written FLTP->S16, resampler, or channel mixing.
- Output channel layout is always `av_channel_layout_default(&out, 2)`
  (stereo), not just `channels=2`.

## SwrContext Ownership

- Created lazily on the first real decoded frame, reconfigured whenever the
  input format identity changes, freed on Audio OFF flush, reconnect, stop,
  release, and source change.
- Never created per frame.
- `releaseFfmpegResources()` frees `audioSwrContext_` (swr_free), resets the
  input identity fields, and clears the PCM scratch buffer.
- Thread ownership: all hot-path access (`swr_convert`) happens on the playback
  thread. Control-thread lifecycle (Audio OFF flush) is marshalled through the
  existing `audioFlushRequested` atomic flag consumed at the top of the playback
  loop, so the swr context is never freed while the playback thread is inside
  `swr_convert`. No new lock was introduced.

## SwrContext Initialization

Lazy configuration based on the **first real decoded AVFrame** (not just the
codec context metadata), because the actual decoder output is the source of
truth. `configureAudioSwrContext()`:

1. Frees any existing `audioSwrContext_`.
2. Builds the input `AVChannelLayout` from the frame's native mask, falling back
   to `av_channel_layout_default` when the mask is unknown or invalid.
3. Builds the output layout with `av_channel_layout_default(&out, 2)`.
4. Calls `swr_alloc_set_opts2(&audioSwrContext_, &out, S16, 48000, &in, fmt, rate, ...)`.
5. Calls `swr_init`.
6. Stores the input identity (sample format, sample rate, channel count, layout
   mask) for later change detection.

## Input Format Identity

Stored as primitive fields (keeps the header forward-declaration style):

- `audioSwrInputSampleFormat_`
- `audioSwrInputSampleRate_`
- `audioSwrInputChannels_`
- `audioSwrInputLayoutMask_` (native channel mask; 0 = unspecified)

## Input Format Change Handling

`convertAudioFrameToPcm()` compares the current frame identity against the
stored identity. If any of sample format, sample rate, channel count, or layout
mask differs, the SwrContext is **rebuilt** (`configureAudioSwrContext`) and the
new frame is processed. This covers reconnect, source change, and in-stream
audio format changes (e.g. 16000/stereo/FLTP -> 48000/mono/FLTP) without
crashing.

Format change only rebuilds the Audio SwrContext. It never calls `openInput`,
never reconnects RTSP, and never recreates the video decoder/MediaCodec.

## Channel Layout Handling

- Native layouts use the frame's mask directly (`frame->ch_layout.u.mask`).
- Unspecified layouts fall back to `av_channel_layout_default` by channel count
  (1 -> mono, 2 -> stereo, ...).
- Mono input is upmixed to stereo by libswresample's standard channel
  conversion (no manual L=R copy).
- >2 channel input is downmixed by libswresample when the layout is valid.
- Invalid/unsupported layouts increment `audioResampleErrorCount`, skip
  playback PCM for that frame, and continue video/recording. No crash.

## Output Sample Count Calculation

Per FFmpeg convention:

```
delaySamples = swr_get_delay(audioSwrContext_, inputSampleRate)
requestedOutSamples = av_rescale_rnd(delaySamples + nb_samples,
                                     outputSampleRate, inputSampleRate, AV_ROUND_UP)
```

`requestedOutSamples` is only a capacity; the real produced count is the
`swr_convert` return value.

## PCM Buffer Allocation Strategy

- Reusable scratch buffer `std::vector<uint8_t> audioPcmBuffer_`.
- Required capacity: `requestedOutSamples x 2 channels x sizeof(int16_t)`.
- Overflow/invalid checks: `requestedOutSamples <= 0` or `> INT_MAX` rejected;
  `requiredBytes <= 0` or `> size_t max` rejected (protects 32-bit ABIs).
- The buffer grows only when capacity is insufficient. In steady state there is
  **no per-frame heap allocation** (resize within existing capacity reallocates
  nothing; verified by code audit — `resize` only grows when `size() < needed`).

## PCM Buffer Reuse

- The same `audioPcmBuffer_` is reused across frames for the whole session.
- Cleared (size=0, capacity retained) on Audio OFF, reconnect, stop, and release
  so stale content is never treated as a valid block.
- No per-frame malloc/free, no per-frame `std::vector` construction.

## swr_convert Flow

```
uint8_t *outData[] = { audioPcmBuffer_.data() };   // S16 interleaved: one plane
inData = frame->extended_data (or frame->data fallback)
convertedSamples = swr_convert(swr, outData, requestedOutSamples, inData, nb_samples)
```

- Uses `frame->extended_data` (correct for planar FLTP stereo where
  `extended_data[0]`/`extended_data[1]` are the left/right planes). Never uses
  only `frame->data[0]` for multi-channel planar input.
- Return value: `<0` error, `0` legal no output, `>0` PCM produced.
- Actual PCM bytes = `convertedSamples x outputChannels x sizeof(int16_t)`;
  `requestedOutSamples` is never treated as actual data size.

## PCM Sample / Byte Semantics

- 1 PCM frame = all channels at one instant (stereo S16 = L + R = 4 bytes).
- `audioPcmSampleCount` = total **output samples per channel** produced by
  `swr_convert` (cumulative `convertedSamples`).
- `audioPcmByteCount` = total PCM bytes = `audioPcmSampleCount x 2 ch x 2 bytes`
  (holds in the long run, per the 4-byte PCM frame definition).
- `audioPcmBlockCount` = number of successful `swr_convert` calls that produced
  output (one PCM block per converted frame). Named "block" to avoid confusion
  with the per-sample "PCM frame" concept.
- `audioDecodedSampleCount` (A1) remains the raw decoded per-channel sample
  count and is intentionally separate from `audioPcmSampleCount`.

## PCM PTS Semantics

- `lastPcmPtsUs` = media start time of the last produced PCM block, sourced from
  the decoded AVFrame's `best_effort_timestamp` (fallback `pts`), rescaled from
  the audio stream time base to microseconds.
- Not wall-clock, not packet-arrival time.
- Resampling (16k -> 48k) does not move the media timeline; the PCM block start
  PTS equals the decoded frame media start time. swr internal delay is NOT
  treated as an audio-playback clock (that is Slice A5).
- This is the PTS association the future A3 queue will carry
  (block + startPtsUs + sampleCount).
- `audioClockUs` remains the A0/A1 LEGACY compressed-packet PTS mirror and is
  NOT redefined as a PCM/playback clock.

## Audio Disabled Behavior

- `audioEnabled=false` skips playback AAC decode (A1 policy), so no decoded
  frames and therefore no swr work. The recorder keeps receiving the original
  compressed packets.
- On the OFF transition, `audioFlushRequested` is set; the playback thread
  flushes the audio decoder and frees/resets the SwrContext so a later ON starts
  from the live edge with no stale decoder or resampler-delayed samples.

## Audio Enabled Behavior

- `audioEnabled=true`: AAC decode -> decoded AVFrame -> swr -> PCM -> stats ->
  discard. Runs synchronously on the playback thread and returns immediately
  (no sleep, no wait, no JNI, no IO).
- Audio ON never reopens RTSP, never recreates the video decoder/MediaCodec, and
  never restarts recording.
- `audioPlayable` stays `false` (no PCM queue / audio worker / JNI sink /
  AudioTrack). `effectiveSyncMaster` stays `video`.

## Reconnect Behavior

- Reconnect frees the old decoder context, audio frame, SwrContext, and PCM
  scratch (via `releaseFfmpegResources`), then re-opens the input and audio
  decoder.
- On the first frame of the new stream, `convertAudioFrameToPcm` detects the
  (possibly new) input identity and configures a fresh SwrContext.
- No old swr state is reused across source generations; no stale PCM is
  considered valid.

## Recorder Independence

- The recorder receives the original compressed AAC packet before the decode
  branch (unchanged from A0/A1). PCM conversion operates only on the decoded
  frame, never on the recorder packet or its timestamps.
- `audioEnabled=false` does not stop recorder audio. Recording continues to
  remux original compressed AAC (`recordAudioPacketCount` grows independently).
- No audio encoder exists; recording is never PCM -> AAC encode.

## Stats

New Stats keys (all real data, no fabricated fields):

| Key | Meaning |
|---|---|
| `audioOutputSampleRate` | 48000 (frozen contract) |
| `audioOutputChannels` | 2 (frozen contract) |
| `audioOutputSampleFormat` | "s16" (frozen contract) |
| `audioOutputInterleaved` | true (frozen contract) |
| `audioSwrReconfigureCount` | number of SwrContext builds/reconfigures (near 1 for a stable source) |
| `audioPcmBlockCount` | successful swr_convert outputs (one per converted frame) |
| `audioPcmSampleCount` | cumulative output samples per channel |
| `audioPcmByteCount` | cumulative output PCM bytes |
| `audioResampleErrorCount` | swr errors (invalid format, init failure, convert failure, overflow) |
| `lastPcmPtsUs` | media start PTS of the last PCM block (µs) |
| `lastAudioResampleCostUs` | last swr cost (µs; -1 before first) |
| `avgAudioResampleCostUs` | average swr cost (µs) |
| `maxAudioResampleCostUs` | max swr cost (µs) |

Timing covers only `swr_convert` (send/receive decode timing remains the
separate A1 `*AudioDecodeCostUs` set, so decode and resample cost can be
diagnosed independently). All hot-path counters are atomics; `getStats` never
reads the raw PCM buffer (no PCM bytes in JSON). No Base64/hex PCM is exposed.

## Performance

No device/source was available to measure runtime cost or video-FPS impact.
`avgAudioResampleCostUs` / `lastAudioResampleCostUs` / `maxAudioResampleCostUs`
are recorded for device validation. Code review: the swr path is pure CPU, runs
on the playback thread, and uses a reused scratch buffer with no per-frame
allocation in steady state. Measured before/after comparison:
`NOT_AVAILABLE` (no device baseline).

## Video Regression

No video code was changed. `convertAudioFrameToPcm` runs only inside the audio
decode branch and cannot alter video decode/render. Runtime video validation not
executed (no device); code path unchanged.

## Thermal Regression

No Thermal code was touched. A2 adds no shader, LUT, gamma, window, or AGC
change.

## Recording Regression

Recorder behavior is untouched. The recorder still receives every packet before
the decode branch and remuxes original compressed AAC. PCM conversion cannot
affect `recordAudioPacketCount`, stop recording, or change the recorder stream
mapping.

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF warnings).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- CMake rebuilt `libnative-ffmpeg.so` for `arm64-v8a` and `armeabi-v7a`.
- Existing KAPT/gradle deprecation warnings remain; no unrelated build/test
  infrastructure was modified.

## Runtime Verification

Not executed. No adb device, no RTSP source, and no actual AAC packet stream
were available in this environment.

```
PCM Conversion Runtime:      NOT_EXECUTED
Recording Audio Verification: NOT_EXECUTED
Audio Toggle runtime:         NOT_EXECUTED
Reconnect runtime:            NOT_EXECUTED
Dynamic audio format change:  NOT_EXECUTED
```

No runtime PASS is claimed; acceptance criteria (69-74) remain for device
validation. The code/architecture freeze below is based on code audit + build.

## Answers to the A2 Questionnaire

1. Input codec: codec-agnostic; historical evidence `aac`. Not re-verified this session.
2. Observed decoded sample format: NOT_TESTED (historical: fltp).
3. Observed decoded sample rate: NOT_TESTED (historical: 16000).
4. Observed decoded channels: NOT_TESTED (historical: 2).
5. Final PCM sample format: S16.
6. Final PCM sample rate: 48000.
7. Final PCM channels: 2.
8. Final PCM channel layout: STEREO.
9. Final PCM layout: INTERLEAVED.
10. libswresample used: YES.
11. SwrContext reused: YES (lazy-configured, reused across frames).
12. SwrContext rebuilt every frame: NO (rebuilt only on input identity change).
13. Input format change detection: compare frame sample format / sample rate /
    channel count / native layout mask against the stored identity.
14. On format change: rebuild the SwrContext, then process the new frame; no
    RTSP reopen, no MediaCodec recreation.
15. PCM scratch buffer reused: YES (`audioPcmBuffer_`).
16. Per-frame PCM heap allocation: NO after steady-state capacity is established
    (`resize` only grows when capacity is insufficient).
17. `swr_convert` uses `frame->extended_data`: YES (fallback to `frame->data`).
18. Output sample capacity: `av_rescale_rnd(swr_get_delay + nb_samples, 48000, inputRate, AV_ROUND_UP)`.
19. Actual converted samples: the `swr_convert` return value; bytes =
    `convertedSamples x 2 x sizeof(int16_t)`.
20. `audioPcmSampleCount`: cumulative output samples per channel.
21. `audioPcmByteCount`: cumulative output PCM bytes.
22. `lastPcmPtsUs`: media start PTS of the last produced PCM block (µs).
23. Is PCM Queue implemented? NO.
24. Is Audio Worker implemented? NO.
25. Is JNI PCM Sink implemented? NO.
26. Is AudioTrack implemented? NO.
27. Is audible audio available? NO.
28. Is effectiveSyncMaster still video? YES.
29. Does Audio OFF affect recorder AAC? NO.
30. Does Recording still use original compressed AAC? YES (remux unchanged).

## Slice A2 Freeze

Hard-gate checklist:

- A1 AAC decode preserved: **YES** (drain loop unchanged except added conversion).
- Audio AVFrame enters swr: **YES** (`convertAudioFrameToPcm` called per frame).
- Final PCM = S16: **YES**. Sample rate 48000: **YES**. Channels 2: **YES**.
- Stereo: **YES**. Interleaved: **YES**.
- SwrContext reusable: **YES** (lazy, reused, freed on lifecycle boundaries).
- Format change safe reconfigure: **YES** (identity compare + rebuild).
- PCM scratch reusable: **YES**.
- No steady-state per-frame PCM allocation: **YES** (code audited).
- Output sample sizing safe (delay + rescale + overflow checks): **YES**.
- `frame->extended_data` used: **YES**.
- PCM media PTS association: **YES** (`lastPcmPtsUs`).
- Recorder AAC remux unchanged: **YES**.
- Audio OFF does not affect recording: **YES**.
- No PCM Queue / Audio Worker / JNI PCM Sink / AudioTrack / A/V sync: **YES**.
- Video no regression: **YES** (no video code changed).
- Thermal no regression: **YES** (no Thermal code changed).
- Build: **PASSED**.

Runtime verification is `NOT_EXECUTED` (no device/source). `Runtime Verified`
is **NO / NOT_EXECUTED**; no runtime PASS is claimed.

**Slice A2 Freeze: YES**

## Git Commit

Commit message: `feat(player): convert live audio to PCM`