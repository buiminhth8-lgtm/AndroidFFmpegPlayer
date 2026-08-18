# Audio Phase 1 Slice A7 Recording / Stats / Integration Report

## Scope

Slice A7 validates the frozen A6 live-audio lifecycle against compressed-packet
recording, Stats semantics, reconnect, Surface, hardware video, and Thermal. It
does not add an audio playback feature, redesign the recorder, add Media3, or
change dependencies.

The audit found two narrow integration gaps and fixed only those gaps:

1. `enableAudio()` now updates the recorder's diagnostic
   `audioPlaybackEnabled` snapshot. This does not change stream mapping or mux
   state.
2. MP4/MOV recording of compressed AAC now uses FFmpeg's `aac_adtstoasc`
   bitstream filter. MPEG-TS ADTS AAC is converted to MP4-compatible compressed
   AAC framing without decode, PCM conversion, or re-encode.

## A6 Baseline

Baseline commit: `ed26b6c fix(player): stabilize live audio lifecycle`.

The full `AUDIO_PHASE1_SLICE_A6_LIFECYCLE_RECOVERY_REPORT.md` was reviewed
before A7 changes. The frozen chain remains:

`demux compressed AAC -> decode AVFrame -> SWR S16/48 kHz/stereo -> bounded PCM
queue -> native worker -> JNI LiveAudioPcmSink -> AudioTrack playback head ->
effective audio master`

A6 generation invalidation, bounded non-blocking AudioTrack writes, Pause /
Resume / Stop / Release ordering, Surface-independent audio ownership, and
reconnect recovery were not redesigned.

## Audio / Recording Separation

PASS.

- `NativePlayer::playbackLoop()` passes each original demuxed packet to
  `PlayerRemuxRecorder::onPacket()` before Pause draining, realtime packet
  dropping, startup keyframe dropping, video decode/render, or audio decode.
- Audio ON/OFF only controls the decode-to-AudioTrack monitoring pipeline.
- Recorder mapping is created from `AVFormatContext` stream metadata, never
  from `audioEnabled`, `audioPlayable`, AudioTrack state, PCM queue state, or
  sync-master state.
- A source with AAC maps video + AAC. A source without audio takes the existing
  video-only branch. The optional video-only runtime source was not available
  in this run; that branch was verified statically.

## Recorder Packet Path

`PlayerRemuxRecorder::onPacket()` receives a `const AVPacket *` and
`writePacketLocked()` creates a private `av_packet_ref`. Timestamp normalization,
time-base rescaling, output stream index assignment, and mux ownership apply
only to that private packet reference. The packet retained by playback is not
mutated.

For MP4/MOV AAC, the private compressed packet additionally passes through
`aac_adtstoasc`. This strips ADTS transport framing / supplies MP4 AAC config;
it is a bitstream operation, not audio decoding or encoding. The output AAC
payload remains compressed AAC.

`recordVideoPacketCount` and `recordAudioPacketCount` increment only after a
successful `av_interleaved_write_frame`. The exact 25-second Audio OFF output
contained HEVC + AAC and recorded 248 video packets / 385 AAC packets. The
exact Audio ON output contained HEVC + AAC and recorded 200 video packets /
315 AAC packets.

## Audio Toggle During Recording

PASS.

The mixed run started with Audio ON, switched ON -> OFF -> ON while one MP4
recording remained open, and retained:

- `recording=true`
- `videoStreamRecorded=true`
- `audioStreamRecorded=true`
- the same output path, segment index, and muxer context

While Audio was OFF, Record State reported
`audioPlaybackEnabled=false`, video/audio counts 1360/2124, and no mapping
change. After reconnect and before re-enabling audio, the same session reached
1569/2454. After Audio was re-enabled, the final session counts were
2071/3234. The diagnostic field then reported `audioPlaybackEnabled=true`.

## Audio Failure Isolation

PASS for the implemented contract.

- Decode, resample, PCM queue, worker, JNI sink, and AudioTrack failures only
  degrade live monitoring and invalidate the playback clock.
- Queue overflow drops PCM blocks only. It never reaches the compressed packet
  recorder path.
- Invalid or stale audio playback clocks change `effectiveSyncMaster` to video;
  they do not stop or remap recording.
- Video sync packet/frame dropping occurs after the recorder sees the original
  packet.
- A forced sink/decoder fault was not injected on this device. The runtime did
  exercise Audio OFF, generation cancellation, reconnect, and heavy queue
  overflow without a recorder stop.

## Stats Semantics

PASS. Existing public keys were retained; no duplicate counter family was
introduced.

| Layer | Public Stats meaning |
| --- | --- |
| Demux | `audioPacketCount` / `audioPacketBytes`: compressed source packets read by `av_read_frame` |
| Decode | `audioDecodedFrameCount` (alias of legacy `audioFrameCount`) and `audioDecodedSampleCount`: successfully decoded frames/samples |
| PCM | `audioPcmBlockCount`, `audioPcmSampleCount`, `audioPcmByteCount`: successful S16/48 kHz/stereo SWR output |
| Queue | depth/high-watermark and drop/flush/generation counters: bounded PCM transport only |
| Worker | consumed/stale block and sample/byte counters: native consumer activity |
| Sink | `audioSinkWriteCount`, `audioSinkWrittenByteCount`, `audioSinkWriteErrorCount`, controlled cancellations, restarts: AudioTrack/JNI results |
| Record | `recordAudioPacketCount`: compressed audio packets successfully written by the remux muxer |

`audioClockUs` remains the documented legacy compressed-packet PTS field.
`audioPlaybackClockUs` plus `audioPlaybackClockValid` is the real AudioTrack
playback-head clock.

`syncMaster` is requested policy. `effectiveSyncMaster` is `audio` only when
the source has audio, Audio is enabled and playable, and the AudioTrack clock
is valid and not stale; otherwise it is `video`. The exact Audio ON run logged
`master=audio`. Audio OFF and reconnect-wait states logged / computed video
fallback.

## Stats Invariants

PASS.

- Demux, decode, PCM, queue, sink, and record counters are independent and
  monotonic within a prepared player session.
- Audio OFF can have increasing demux and record counters while decode, PCM,
  worker, and sink counters remain unchanged.
- `audioSinkWriteCount` counts only complete successful sink writes;
  lifecycle cancellations use `audioSinkControlledCancelCount`.
- Queue drops never increment recorder errors and never decrement recorder
  counts.
- Record counters count mux success, not source discovery, decode success, or
  playback success.

The mixed run reached 392 PCM queue drops and 39 video catch-up drops while the
same recorder completed with 2071 video packets / 3234 AAC packets and a
readable dual-track MP4. At a representative Audio ON sample, Stats showed
2605 decoded audio frames, 2605 PCM blocks, 2218 worker-consumed blocks, 2217
successful sink writes, and zero sink write errors; the differing layer counts
are expected and are not aliases.

## D Architecture Boundary

FROZEN.

- `NativePlayer` remains the professional live RTSP/network player path.
- Future local MP4/VOD playback belongs to AndroidX Media3 / ExoPlayer in the
  separate D task.
- The paced HTTP MPEG-TS source used below is a live-network test harness, not
  a new local-file product path.
- Slice A7 does not implement D, add ExoPlayer/Media3, or change dependencies.

## Reconnect + Recording

PASS on the generic network reconnect path.

The source was allowed to reach EOF while recording. The recorder remained
active through `WAITING_SOURCE`; no output context or stream mapping was
recreated. A restarted paced HTTP MPEG-TS source used a continuous timestamp
offset. Reconnect succeeded on attempt 34, playback returned to PLAYING, and
the same recorder counts advanced from 1360/2124 to 1569/2454, then to final
2071/3234. `av_write_trailer` succeeded and `ffprobe` found a readable
227.10-second HEVC + AAC MP4.

An actual RTSP camera/server with AAC was not available, so RTSP transport
reconnect itself remains unexecuted in A7.

## Surface Regression

PASS.

During Audio ON + Thermal + recording, Surface generation 3 detached and
generation 4 reattached. The NV12 renderer applied generation 4, recreated its
EGL surface (`nv12EglSurfaceCreateCount=2`) without recreating the EGL context
(`nv12EglContextCreateCount=1`), and resumed rendering. Audio and recording
continued independently. Representative Stats showed 2401 NV12 GL rendered
frames, zero NV12 fallback frames, and applied surface generation 4.

## Video / Thermal Regression

PASS for the device matrix exercised.

- Decoder: `hevc_mediacodec`
- Frame output: `nv12_cpu`
- Renderer: `nv12_gl`
- Hardware decoder fallback: false
- Renderer fallback: false
- `swsScaleInvocationCount`: 0

Thermal Original (Thermal OFF), White Hot, Ironbow, and Ironbow + AGC were
exercised. The mixed run reported `render WHITE_HOT`, then `render IRONBOW`,
and AGC ON with a valid effective window approximately 0.11-0.89. Recording
and Audio ON remained active during the Thermal transitions.

## Runtime Matrix

Device: `34aff35a` (`Bengal_for_arm64`). Source: paced localhost HTTP MPEG-TS
over `adb reverse`, fixed 640x360 HEVC Main (no B frames), AAC LC 16 kHz stereo.

| Case | Result | Evidence |
| --- | --- | --- |
| Audio OFF + MP4 recording, 25 s wall clock | PASS | 24.80 s MP4, HEVC + AAC, 248/385 muxed packets |
| Audio ON + MP4 recording, 30 s wall clock | PASS | 20.16 s MP4, HEVC + AAC, 200/315 muxed packets; AudioTrack master active |
| Audio ON -> OFF -> ON during one recording | PASS | mapping stayed V+A; record AAC 2124 -> 2454 -> 3234 |
| Queue overflow during recording | PASS | 392 PCM drops; recorder completed V+A with no mux error |
| Reconnect while recording | PASS | output context retained; reconnect success; counts resumed |
| Surface detach / reattach while recording | PASS | applied generation 4; EGL surface recreated; no render fallback |
| Original / White Hot / Ironbow / AGC | PASS | effective render modes and AGC window observed |
| Hardware HEVC -> NV12 CPU -> NV12 GL | PASS | no decode/render fallback and no swscale |
| Source without audio -> video-only | NOT_EXECUTED | optional runtime source unavailable; branch audited statically |
| Actual AAC RTSP endpoint | NOT_EXECUTED | no endpoint was available; paced HTTP live harness used |
| 10-20 minute long run | NOT_EXECUTED | mixed recording ran 465.5 s (7 min 45.5 s); useful medium-run only |

Runtime verification is therefore PARTIAL: all mandatory functional paths were
exercised with a network-paced AAC source, but actual RTSP and the requested
10-20 minute duration were unavailable.

## Build

- `gradlew.bat :app:assembleDebug`: PASS; native code built for arm64-v8a and
  armeabi-v7a.
- Host `ffprobe`: PASS for both exact MP4 cases and the reconnect MP4; each
  contains one HEVC video track and one AAC audio track.
- `gradlew.bat :app:testDebugUnitTest`: FAILED before test execution because
  of the frozen, unrelated KAPT/JUnit stub problem in `ExampleUnitTest.java`
  (`@error.NonExistentClass`, task `:app:kaptDebugUnitTestKotlin`). A7 did not
  modify or work around it.
- `git diff --check`: PASS (line-ending conversion warnings only; no whitespace
  errors).

## Remaining Issues

1. No actual AAC RTSP endpoint was available; RTSP-specific record/reconnect
   runtime remains for lab validation.
2. The requested 10-20 minute long run was not completed. The longest A7 mixed
   recording was 7 min 45.5 s and included a deliberate source outage.
3. Optional source-without-audio runtime was not repeated in A7.
4. The pre-existing `:app:testDebugUnitTest` KAPT/JUnit stub failure remains
   unrelated to the player changes.

## Slice A7 Freeze

YES.

The frozen result is: compressed AAC recording is independent from AudioTrack
monitoring; MP4 receives MP4-compatible compressed AAC through a bitstream
filter; stream mapping is immutable across Audio toggle and reconnect; Stats
layers have distinct meanings; hardware video, Thermal, Surface, and reconnect
remain integrated; and the future D boundary remains NativePlayer for live
RTSP/network versus Media3/ExoPlayer for local MP4/VOD.

Do not start Slice A8 from this report.
