# AUDIO PHASE 1 — SLICE A3 — BOUNDED PCM QUEUE + AUDIO OUTPUT WORKER

Date: 2026-08-18
Branch: `dev`
A2 baseline commit: `bf2e174 feat(player): convert live audio to PCM`
Slice result commit: see "Git commit" section.

## Scope

Slice A3 decouples the PCM produced in A2 from the playback thread by adding a
bounded low-latency PCM queue and an independent audio output worker that
consumes into a **Null / Discard sink** (no AudioTrack, no sound).

```
AAC -> Decode -> SWR -> PCM S16/48k/stereo -> Bounded PCM Queue -> Audio Output Worker -> Null/Discard Sink
```

After this slice:

```
PCM Queue:        IMPLEMENTED (bounded)
Audio Worker:     IMPLEMENTED (null/discard sink)
JNI PCM Sink:     NOT_IMPLEMENTED
AudioTrack:       NOT_IMPLEMENTED
Audible Audio:    NO
A/V Sync:         NO
```

The change set is limited to `NativePlayer.h`, `NativePlayer.cpp`, and one
directly-related test-only debug command in `native-ffmpeg-jni.cpp`. No video
pipeline, Thermal pipeline, RTSP policy, recorder algorithm, Java UI, or
dependency was changed.

## A2 Baseline

A2 (`bf2e174`) was confirmed in place: `convertAudioFrameToPcm()` produces
S16/48k/stereo/interleaved PCM into the reusable `audioPcmBuffer_` scratch
buffer, records stats, then discards. A2 core capability is complete, so A3 is
not masking an A2 gap.

## PCM Block Ownership

`AudioPcmQueue::Block` owns its own PCM data:

```cpp
struct Block {
    std::vector<uint8_t> data;   // owned PCM bytes (S16/48k/stereo interleaved)
    int64_t startPtsUs = 0;      // media start PTS
    int64_t sampleCount = 0;     // samples per channel
    int64_t generation = 0;      // discontinuity identity
};
```

The A2 scratch buffer (`audioPcmBuffer_`) is reused on the next frame, so the
enqueue path copies the produced PCM bytes into an owned `Block` before
enqueueing. This avoids use-after-free and data races; the queue fully owns the
block lifetime. Memory is strictly bounded by the small queue length.

## Queue Design

- `AudioPcmQueue` (defined in NativePlayer.h) uses `std::mutex` +
  `std::condition_variable` + `std::deque<Block>`.
- Bounded by **duration** (computed from `sampleCount` at 48 kHz).
- Configured bounds: **target ~150 ms**, **hard max ~250 ms** (within the
  100-200 / 200-300 ms spec).
- Producer (playback thread) never waits for queue space; overflow drops the
  oldest blocks (DROP_OLDEST) to keep the live edge.

## Queue Bounds

- `configure(150000, 250000)` sets target 150 ms, hard max 250 ms.
- Enqueue evicts oldest blocks while `bufferedDuration + newBlock > max`.
- A single oversized block is accepted when the queue is empty (forward
  progress at the live edge); blocks are ~20 ms so this is not a practical case.

## Overflow Policy

- On overflow: drop the **oldest** blocks (DROP_OLDEST), keep the newest audio.
- `audioQueueDropCount` / `audioQueueDroppedSampleCount` count evictions.
- Producer never blocks; the playback thread returns immediately after enqueue.
- Recording (original compressed AAC) is never affected by PCM queue overflow.

## Audio Worker

- One dedicated `std::thread` (`audioOutputWorkerThread_`) runs
  `audioOutputWorkerLoop()`.
- Loop: `waitAndDequeue` (blocking on the queue CV) -> Null/Discard sink
  (consume + stats + discard) -> loop; exits on stop.
- Started on Audio ON (`startAudioOutputWorker`) and on `start()` when audio is
  still enabled (prepare() stops it); stopped on Audio OFF, `stop()`, release,
  and destruction.
- The worker is **always joined** before release/destruction (never detached).
  `~NativePlayer()` -> `release()` -> `stop()` joins the worker, so no worker
  accesses `this` after destruction.
- Test-only hook: `-audio-backpressure-test <ms>` makes the null sink sleep per
  block (default 0 = production unchanged), to validate producer/consumer
  backpressure isolation on device.

## Thread / Lock Model

- Queue mutex protects only queue metadata/data. It is held during
  enqueue/dequeue/flush/stop but never while calling decoder, swr, recorder,
  renderer, or any sink output.
- Consumer: `waitAndDequeue` releases the queue lock while waiting on the CV;
  the worker dequeues a block under the lock, then releases it before the null
  sink runs.
- Producer: `enqueue` takes the queue lock only for the bounded push+evict
  critical section (no long hold, no blocking).
- Stop: `requestStop` notifies the CV to wake the consumer.
- `audioWorkerMutex_` protects the worker thread handle for start/stop and is
  released before `join()` (no lock held during join; no deadlock).
- No busy-spin, no polling sleep in the queue or worker.

## Audio Toggle

- **ON** (`enableAudio(true)`): request playback-thread flush of decoder+swr,
  `startAudioOutputWorker()` (which resets the queue and clears the stop flag),
  then set `audioEnabled=true`. The worker is ready before any PCM can be
  enqueued; a clean queue means no stale PCM is consumed.
- **OFF** (`enableAudio(false)`): set `audioEnabled=false`, flush the queue,
  `stopAudioOutputWorker()` (requestStop + join), and request the playback
  thread to flush the decoder+swr. Recording continues; no RTSP reopen; no
  MediaCodec recreate.

## Reconnect / Flush

- On reconnect and transport-switch success, `flushAudioPcmForDiscontinuity()`
  flushes the queue and advances `audioQueueGeneration`.
- Stale queued PCM from the old source generation is cleared so the new stream
  enqueues from its live edge; the new decoder/SWR are opened from the new
  codecpar as before.
- In A3 the null sink makes any residual consumption harmless; A4 will reject
  stale blocks by generation before output.

## Recording Independence

- The recorder receives the original compressed AAC packet before the decode
  branch (unchanged). The PCM queue/worker are fully downstream and independent.
- Audio OFF, queue overflow, and worker state never affect
  `recordAudioPacketCount` or the recorder stream mapping.

## Stats

New Stats keys (all real data):

| Key | Meaning |
|---|---|
| `audioQueueDurationUs` | current buffered PCM duration (µs) |
| `audioQueueBlockCount` | current queued block count |
| `audioQueueBytes` | current queued PCM bytes |
| `audioQueueHighWatermarkUs` | peak buffered duration observed |
| `audioQueueDropCount` | blocks dropped by overflow |
| `audioQueueDroppedSampleCount` | samples dropped by overflow |
| `audioQueueFlushCount` | number of queue flushes (OFF/reconnect) |
| `audioQueueGeneration` | discontinuity identity (advances on reconnect) |
| `audioWorkerRunning` | whether the audio output worker is running |
| `audioWorkerConsumedBlockCount` | blocks consumed by the null sink |
| `audioWorkerConsumedSampleCount` | samples consumed |
| `audioWorkerConsumedByteCount` | bytes consumed |
| `lastConsumedPcmPtsUs` | start PTS of the last consumed block (µs; NOT a playback clock) |

Queue-internal counters are guarded by the queue mutex; worker counters are
atomics. No raw PCM data is exposed in JSON.

## Backpressure Validation

- The producer's `enqueue` never waits on queue space (code-audited; bounded
  critical section with oldest-drop). A slow consumer cannot block the playback
  thread, `av_read_frame`, video decode/render, or the recorder.
- The test-only `-audio-backpressure-test <ms>` debug command injects per-block
  sleep into the null sink so a device run can observe the queue hitting its
  cap, drop counters growing, and video/RTSP/recording continuing. Runtime
  execution: NOT_EXECUTED (no device); the mechanism is wired and ready.

## Video / Thermal Regression

No video or Thermal code was changed. The queue/worker run only on the audio
path. Runtime validation not executed (no device); code path unchanged.

## Build

- `git diff --check`: PASSED (only Git's informational LF-to-CRLF warnings).
- `.\gradlew.bat :app:assembleDebug`: **PASSED** (`BUILD SUCCESSFUL`).
- CMake rebuilt `libnative-ffmpeg.so` for `arm64-v8a` and `armeabi-v7a`.
- Existing KAPT/gradle deprecation warnings remain; no unrelated build/test
  infrastructure was modified.

## Runtime Verification

Not executed. No adb device, RTSP source, or actual AAC stream was available.

```
Backpressure isolation (device): NOT_EXECUTED (hook wired; no device run)
Audio toggle runtime:             NOT_EXECUTED
Reconnect runtime:                NOT_EXECUTED
Recording runtime:                NOT_EXECUTED
```

No runtime PASS is claimed. The code/architecture freeze below is based on code
audit + build.

## Answers to the A3 Questionnaire

- Is the queue bounded? **YES** (by duration; hard max ~250 ms).
- Does the producer wait for queue space? **NO** (overflow drops oldest).
- Queue-full handling? **DROP_OLDEST** (keep the live edge).
- Does a PCM block own its data? **YES** (owned `std::vector` copy from scratch).
- Is the audio worker an independent thread? **YES** (dedicated `std::thread`).
- Does release join the worker? **YES** (release->stop joins; never detached).
- Does reconnect flush stale PCM? **YES** (`flushAudioPcmForDiscontinuity`).
- Does Audio OFF flush the queue? **YES** (flush + stop worker).
- Does queue overflow affect Recording? **NO**.
- Is AudioTrack implemented? **NO**.
- Is audioPlayable still false? **YES**.
- Is effectiveSyncMaster still video? **YES**.

## Slice A3 Freeze

Hard-gate checklist:

- Bounded PCM queue: **YES**.
- Audio worker: **YES**.
- Queue ownership safe: **YES** (owned blocks, no UAF, no data race).
- Producer not blocked by consumer backpressure: **YES** (non-blocking enqueue).
- Overflow keeps the live edge: **YES** (DROP_OLDEST).
- Old PCM flushed on OFF/reconnect: **YES**.
- Worker safely stopped/joined: **YES**.
- No busy-spin: **YES** (mutex + condition_variable).
- No UAF/deadlock: **YES** (join before destruction; lock released before join).
- Recording AAC remux unaffected: **YES**.
- Video/Thermal no regression: **YES** (no code changed).
- No AudioTrack: **YES**. No A/V sync: **YES**.
- Build: **PASSED**.

Runtime verification is `NOT_EXECUTED` (no device/source). `Runtime Verified` is
**NO / NOT_EXECUTED**; no runtime PASS is claimed.

**Slice A3 Freeze: YES**

## Git Commit

Commit message: `feat(player): add bounded live audio output queue`