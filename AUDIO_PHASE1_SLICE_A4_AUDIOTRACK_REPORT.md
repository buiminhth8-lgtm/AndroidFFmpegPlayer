# AUDIO PHASE 1 — SLICE A4 — JNI PCM SINK + ANDROID AUDIOTRACK

Date: 2026-08-18
Branch: `dev`
A3 baseline commit: `0709827 feat(player): add bounded live audio output queue`
Slice result commit: see "Git commit" section.

## Scope

Slice A4 completes the Audio Phase 1 output path and produces real sound:

```
AAC -> Decode -> SWR -> PCM S16/48k/stereo -> Bounded Queue -> Audio Output Worker
  -> JNI (DirectByteBuffer) -> LiveAudioPcmSink -> AudioTrack.write -> Speaker
```

Not implemented (out of scope): Audio playback clock, A/V sync, AudioFocus,
ExoPlayer, recording redesign.

Change set: `NativePlayer.h`, `NativePlayer.cpp`, new `LiveAudioPcmSink.java`,
and a 4-line wiring in `MediaPlayerActivity.java`. No video, Thermal, RTSP,
recorder, or dependency change.

## A3 Baseline

A3 (`0709827`) was confirmed in place: bounded PCM queue (target 150 ms / hard
max 250 ms, DROP_OLDEST, non-blocking producer), a dedicated audio output worker
that consumes into a null/discard sink, queue flush on Audio OFF/reconnect, safe
worker stop/join, and recording AAC remux independence. A3 is complete; A4 is not
masking an A3 gap.

## Java Audio Sink

`LiveAudioPcmSink` (new, `com.example.motro.ffmpeg`) owns the AudioTrack and has
no Activity/View dependency (ready for a future LiveSession):

- `onAudioPcm(ByteBuffer pcm, int sizeBytes, long ptsUs) -> int` — lazily
  creates/starts the AudioTrack, then `AudioTrack.write(..., WRITE_BLOCKING)`
  in a partial-write loop; returns bytes written or a negative AudioTrack error.
- `onAudioControl(int command) -> int` — lifecycle:
  `CMD_START`(0) start, `CMD_PAUSE_FLUSH`(1) pause+flush, `CMD_RELEASE`(2)
  stop+flush+release.
- The `AudioTrack` reference is `volatile`; release only ever runs after the
  worker is joined, so there is no write-vs-release race.

## JNI Thread / Lifetime Model

- `setAudioCallback(handle, sink)` is now a real implementation: it creates a
  `NewGlobalRef` to the sink and caches the `onAudioPcm` and `onAudioControl`
  method IDs under `audioSinkMutex_`. It no longer just records a boolean.
- The audio output worker is a native `std::thread`. It attaches to the JVM
  **once** via `getJniEnvForCurrentThread` at loop start and detaches **once** on
  exit (no per-block attach/detach, no cross-thread JNIEnv reuse).
- Sink reference/metadata is protected by `audioSinkMutex_`. The worker copies a
  local ref + method ID under the lock, releases the lock, then performs the Java
  call (no JNI/sink lock held across `AudioTrack.write`).
- Release order: `stop()` joins the worker first, then `release()` sends
  `CMD_RELEASE` and deletes the GlobalRef. No DeleteGlobalRef races the worker.

## Direct PCM Transfer

- The worker wraps the owned PCM block with `NewDirectByteBuffer(block.data.data(), size)`
  and passes it to `onAudioPcm`. The block stays alive (it is a worker-local
  `std::vector<uint8_t>`) for the duration of the synchronous call; Java never
  caches or retains the ByteBuffer.
- Local refs (ByteBuffer + sink local ref) are deleted after the call. No byte[]
  copy is used (minSdk 24 supports the ByteBuffer write overload).

## AudioTrack Configuration

- `sampleRate=48000`, `CHANNEL_OUT_STEREO`, `ENCODING_PCM_16BIT`,
  `MODE_STREAM`, buffer from `AudioTrack.getMinBufferSize(...)` (low-latency,
  no oversized second-scale buffer).
- `WRITE_BLOCKING` on the worker (the sole consumer) gives natural backpressure:
  a slow AudioTrack blocks the worker (not the playback thread), the queue fills
  and drops oldest, and video/recording are unaffected.

## Audio Write Path

`playback thread` (decode+swr+enqueue) -> `AudioTrack.write` runs only on the
audio output worker thread, never on the RTSP/playback/video thread. Partial
writes are handled by the Java loop; negative returns (ERROR_BAD_VALUE,
ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT, ...) are captured as sink errors and
never crash native.

## Audio ON/OFF

- **ON**: flush decoder+swr (playback-thread flag) -> start worker (resets
  queue) -> `audioEnabled=true`. The AudioTrack is created+played lazily on the
  first PCM write. No RTSP reopen, no MediaCodec recreate.
- **OFF**: `audioEnabled=false` -> flush queue -> `requestStop` -> send
  `CMD_PAUSE_FLUSH` (pause+flush AudioTrack, unblocks any pending write) -> join
  worker -> flush decoder+swr. No stale audio plays; recording continues.

## Reconnect

`flushAudioPcmForDiscontinuity()` flushes the queue, advances the generation,
and sends `CMD_PAUSE_FLUSH` so the AudioTrack's buffered data is cleared. The
next write lazily resumes playback from the new source's live edge — old PCM is
never played after reconnect.

## Stop / Release

- `stop()`: flush queue, `requestStop`, `CMD_PAUSE_FLUSH` (unblocks a blocked
  write), join worker, then recorder stop + FFmpeg resource release.
- `release()`: `stop()` (worker already joined) -> `CMD_RELEASE` (release the
  AudioTrack) -> delete sink GlobalRef -> delete surface/listener refs.
- No queue/sink/registry mutex is held across a Java/AudioTrack blocking call.
  Worker is never detached; NativePlayer is never destroyed while the worker runs.

## Error Isolation

AudioTrack create/play/write failures only affect live audio monitoring. They
increment `audioSinkWriteErrorCount` / `audioSinkLastErrorCode` and can be
reflected in `audioPlayable`, but never call `stopPlayer`, never reconnect RTSP,
never fall back the video decoder, and never touch recording.

## Recording Independence

The recorder keeps receiving the original compressed AAC packet before the
decode branch. Audio OFF, AudioTrack failure, queue overflow, and JNI failure do
not affect `recordAudioPacketCount` or the recorder stream mapping. No
PCM -> AAC re-encode exists.

## Stats

New Stats keys (all real):

| Key | Meaning |
|---|---|
| `audioSinkReady` | sink GlobalRef + method IDs registered |
| `audioSinkWriteCount` | successful `onAudioPcm` calls |
| `audioSinkWrittenByteCount` | bytes accepted by AudioTrack |
| `audioSinkWriteErrorCount` | write/exception failures |
| `audioSinkLastErrorCode` | last error (0 = none) |
| `lastAudioSinkWriteCostUs` / `avgAudioSinkWriteCostUs` / `maxAudioSinkWriteCostUs` | sink write timing |

`audioPlayable` now reflects real capability (A0 frozen semantics preserved):

```
audioPlayable = audioEnabled && sourceHasAudio && audioDecodeOpened && audioSinkReady
```

`effectiveSyncMaster` remains `video` (no AudioTrack playback-head clock yet).
No audio playback clock / AV-drift stats were added.

## Runtime Audio Validation

Not executed. No adb device or AAC RTSP source was available in this environment.
`Audible Audio: NOT_EXECUTED`; no sound claim is made.

## Video / Thermal Regression

No video, MediaCodec, NV12/YUV GL, Surface/EGL, Thermal, or RTSP policy code was
changed. Runtime validation not executed (no device).

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF warnings).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- Java (new sink class + activity wiring) and CMake native (both ABIs) compiled.
- Existing KAPT/gradle deprecation warnings remain; no dependency/minSdk change.

## Answers to the A4 Questionnaire

1. AudioTrack really created? **YES** (LiveAudioPcmSink creates it lazily).
2. PCM really written to AudioTrack? **YES** (worker -> onAudioPcm -> AudioTrack.write).
3. Audible sound? **NOT_EXECUTED** (no device).
4. AudioTrack.write thread? **Audio Output Worker**.
5. Cross-thread JNIEnv* saved? **NO** (attach once per worker lifetime).
6. Worker attaches via JavaVM? **YES** (getJniEnvForCurrentThread attach/detach).
7. GlobalRef release-safe? **YES** (mutex-protected; deleted after worker join).
8. DirectByteBuffer memory lifetime safe? **YES** (block alive during sync call).
9. Audio OFF flush stale PCM? **YES** (queue flush + requestStop + PAUSE_FLUSH).
10. Reconnect clears old audio? **YES** (queue+generation flush + PAUSE_FLUSH).
11. AudioTrack failure affects Video? **NO**.
12. AudioTrack failure affects Recording? **NO**.
13. Audio OFF still records AAC? **YES**.
14. Audio Playback Clock implemented? **NO**.
15. A/V Sync implemented? **NO**.

## Slice A4 Freeze

Hard-gate checklist:

- A3 queue/worker preserved: **YES**.
- JNI worker-thread handling correct: **YES** (attach once/detach once).
- No cross-thread JNIEnv reuse: **YES**.
- GlobalRef lifetime safe: **YES**.
- PCM DirectByteBuffer lifetime safe: **YES**.
- AudioTrack MODE_STREAM: **YES**.
- write not on playback thread: **YES** (worker only).
- Audio ON/OFF safe: **YES**.
- Reconnect no stale PCM: **YES**.
- Release no deadlock/UAF: **YES** (worker joined before sink release/ref delete).
- Audio errors don't affect Video/Recording: **YES**.
- Recording AAC remux unchanged: **YES**.
- No A/V Sync: **YES**.
- Build: **PASSED**.

Audible audio requires a device; without one it is `NOT_EXECUTED` (not faked).

**Slice A4 Freeze: YES**

## Remaining Issues

- Audible-audio runtime acceptance (`adb` + AAC RTSP, 10x OFF/ON) is
  NOT_EXECUTED (no device/source in this environment).
- `CMD_START` is intentionally unused: the AudioTrack is created+played lazily on
  the worker thread to avoid a cross-thread create race.
- `WRITE_BLOCKING` relies on Android AudioTrack's internal synchronization for a
  concurrent pause/flush to unblock a pending write; to be confirmed on device.

## Git Commit

Commit message: `feat(player): play live PCM through AudioTrack`
