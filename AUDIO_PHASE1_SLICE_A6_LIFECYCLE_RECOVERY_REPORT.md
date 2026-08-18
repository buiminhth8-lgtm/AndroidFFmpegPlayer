# Scope

Audio Phase 1 Slice A6 only: stabilize the existing live AAC playback pipeline across Audio ON/OFF, Pause/Resume, reconnect, Surface changes, Stop, Release, and audio-only failures. Changes are limited to `NativePlayer`, `LiveAudioPcmSink`, and minimal lifecycle telemetry in `MediaPlayerActivity`. No AudioFocus, routing, speed/time-stretch, codec expansion, dependency change, renderer/Thermal algorithm change, video decoder configuration change, RTSP policy change, or recording mux redesign was added.

# A5 Baseline

A5 commit `f85dc1e` was verified before implementation. The repository contains AAC decode, SWR to interleaved 48 kHz/stereo/S16 PCM, bounded PCM queue, joined audio worker, JNI/AudioTrack sink, playback-head clock, video-to-audio sync with video-master fallback, and original compressed AAC remux recording. A6 is not masking an incomplete A5 chain.

# Audio Toggle

- OFF first disables PCM production, advances the audio generation, flushes the PCM queue and AudioTrack, invalidates/reset the playback-head clock base, stops and joins the worker, and requests decoder/SWR reset on the playback thread.
- ON advances to a fresh generation, resets decoder/SWR state at the playback-thread boundary, opens a new AudioTrack write epoch, and starts the worker only while playback is active. It resumes at the current demux/live edge; OFF-period PCM is never decoded into a later queue.
- Repeated requests are no-ops. A toggle neither reopens input nor recreates the video decoder. During the paced-source toggle run, reconnect remained zero and the hardware decoder/render path remained active.
- Runtime: 20 OFF/ON pairs completed without crash, ANR, sink error, or stale-generation consumption. Final worker start/join counts were `41/40` while playing; the final Stop made them equal.

# Pause / Resume

Pause now establishes an audio discontinuity, flushes queue and AudioTrack, invalidates the clock, stops/joins the worker, and prevents further decoded PCM production. For realtime input, the playback thread continues draining compressed packets at the live edge and continues the existing recorder packet path, but does not feed paused packets to decoders/renderers. This avoids socket backlog and resume catch-up.

Resume starts a fresh sink/worker epoch, flushes audio decoder/SWR state on the playback thread, resets realtime timing, and for realtime video flushes decoder reference state and waits for a fresh keyframe. No paused PCM is retained or replayed.

Runtime: 20 Pause/Resume cycles passed. Generation advanced once per pause; final start/join was `21/20` while playing. Clock became valid again, effective master returned to audio, stale block count remained zero, and queue duration remained bounded.

# Reconnect

At disconnect—before retry delay or input reopen—the old audio generation is isolated: queue and AudioTrack are flushed, clock/head base is reset, and validity is cleared. Existing reconnect resource teardown/reopen recreates the audio decoder from newly discovered stream metadata; SWR remains lazy. On success with Audio ON, the sink epoch/worker are restored and the first valid PCM establishes a new clock base. Recorder reconnect/remux behavior is unchanged.

Generic HTTP EOF/reconnect was exercised repeatedly (one run reached 28 successful audio recoveries) with no stale worker block, sink error, crash, or video failure. A real AAC RTSP endpoint was unavailable, so RTSP disconnect x5–10 was not executed and is recorded as a runtime coverage gap rather than claimed.

# Audio Presence / Format Change

- `has audio -> no audio`: reconnect discovery clears source/decode capability; the generation is already flushed, clock stays invalid, `audioPlayable=false`, and effective master is video. Video/input operation does not depend on the audio stream.
- `no audio -> has audio`: reconnect discovery opens the new audio decoder; Audio ON restores the sink/worker, SWR configures from the first decoded frame, and clock anchors only after new PCM.
- Reconnect/source discontinuity always discards old PCM. A decoded mid-session identity change also flushes queue/AudioTrack, advances generation, reconfigures SWR, and opens a fresh sink epoch.
- Output contract remains 48 kHz, stereo, interleaved PCM16 regardless of valid AAC input layout/rate/format.

The implementation paths were audited and built, but actual audio-presence and audio-format transitions were not available for device execution. Device playback did verify AAC FLTP/16 kHz/stereo conversion to the fixed output contract.

# Surface Independence

Surface operations do not own or reset audio decode, queue, worker, sink, clock, input, or recorder state. Surface destroy/recreate x20 via background/foreground kept the same process/player, audio generation and worker counters unchanged, audio clock valid, effective master audio, reconnect count unchanged, and hardware NV12 GL playback active. Fix 2 Surface/EGL architecture is unchanged.

# Stop

Shutdown order is deterministic:

1. publish Stop and block new PCM production;
2. advance generation and flush queue/AudioTrack/clock;
3. close/wake the queue;
4. join the non-detached audio worker without holding queue, sink, player, or registry mutexes;
5. join the playback producer;
6. perform a final queue flush after no producer remains;
7. stop recorder according to existing semantics and release decoder/SWR/input resources.

The former IDLE early return was removed, so a worker cannot escape Stop/Release even on an unusual pre-playback path. Runtime Stop during active audio changed start/join from `41/40` to `41/41`, with worker false and clock invalid. Stop/Prepare/Start/Stop x10 also completed without crash or ANR.

# Release

Fix 4 still removes the handle into closing before draining active operations. A6 Stop then cancels AudioTrack writes, joins the worker, and only afterward sends sink Release, deletes the JNI GlobalRef, and frees SWR/audio frame/codec through existing FFmpeg cleanup. Callback replace/clear uses the same stop/join/release-before-GlobalRef-delete rule. Repeated Release remains idempotent.

Runtime race: while Audio ON and the hardware video path was active, a stats query and Surface detach were queued immediately around Release, followed by stale stats/Surface requests. The pre-release stats succeeded; Release stopped the worker and playback thread and removed the handle; later requests returned controlled handle-zero errors. The process remained alive with no crash, ANR, JNI error, UAF, or deadlock.

# JNI / Worker Shutdown

The audio worker obtains `JNIEnv*` with `JavaVM::AttachCurrentThread` for its own lifetime and detaches once on exit; no `JNIEnv*` crosses threads. Java calls use a local reference copied under `audioSinkMutex`, then release the mutex before calling Java. Stop/join holds no queue, sink, player lifetime, or global registry mutex. GlobalRef replacement and deletion occur only after the old worker is joined.

`AudioTrack.write` now uses `WRITE_NON_BLOCKING` with a 250 ms maximum drain window and 2 ms cancellation polling. Lifecycle commands advance a Java epoch and disable writes before pause/flush/release. Expected epoch cancellation has a distinct return code and does not become an audio error. No detached worker, forced thread termination, or long sleep is used.

# Audio Error Isolation

Decoder, SWR, JNI, partial/failed AudioTrack write, or stale clock makes live audio non-playable and invalidates the audio playback clock; effective sync falls back to video. A later valid conversion/full sink write can recover audio. Queue overflow retains the existing bounded drop-oldest policy. None of these paths reopens RTSP, restarts MediaCodec, selects renderer fallback, or changes the recorder packet path.

Telemetry distinguishes controlled lifecycle cancellation from real sink errors and exposes lifecycle state, generation, worker starts/joins/stale blocks, sink restarts/errors, and reconnect recovery. Logging is periodic with the existing five-stats-cycle cadence, not per frame or per PCM block.

# Clock Reset / Generation

Every OFF/ON transition, Pause, Stop, callback replacement, reconnect/transport discontinuity, or decoded audio format identity change advances `audioGeneration`. Reset clears clock validity, generation base PTS, raw/extended playback-head state, cached clock, last update time, and A/V diff. Only a new-generation PCM block with valid PTS and a queryable AudioTrack playback head revalidates/rebases the clock. Generation and Java epoch checks reject already-dequeued old work.

# Recording Independence

Recorder input remains the original compressed AAC `AVPacket` before decode; no PCM encoding or mux algorithm was introduced. Audio toggle and AudioTrack state do not gate recorder packets. Realtime Pause continues the established compressed packet path while suppressing playback decoding.

Device validation used paced HTTP MPEG-TS because no AAC RTSP endpoint was available. Recording began with Audio OFF, remained active across OFF -> ON -> OFF, and `recordAudioPacketCount` continued from 0 to 2473. Normal Stop Record produced a 16,906,088-byte MPEG-TS containing 1532 video and 2473 AAC packets. The first MP4 attempt encountered the existing test stream's dynamic-resolution/timestamp remux limitation; switching only the test output container to MPEG-TS verified the A6 recording invariant without changing recorder code.

# Runtime Stress

Device `34aff35a` and a paced HTTP MPEG-TS source were available. The source carried HEVC with dynamic 1280x720/192x256 changes and AAC LC 16 kHz/stereo/FLTP; FFmpeg was used only as a paced local source. Results:

| Case | Result |
|---|---|
| Baseline Audio ON | PASS: worker/sink/clock valid, effective master audio |
| Audio OFF/ON | PASS: 20 pairs, no stale generation or sink error |
| Pause/Resume | PASS: 20 cycles, no backlog replay, clock revalidated |
| Surface destroy/recreate | PASS: 20 cycles, audio state independent |
| Generic HTTP reconnect | PASS: repeated recovery; one run reached 28 audio recoveries |
| Actual RTSP disconnect x5–10 | NOT_EXECUTED: no AAC RTSP endpoint |
| Stop/Prepare/Start/Stop | PASS: 10 cycles |
| Release race | PASS: Audio ON + stats + Surface + stale operations |
| Recording + Audio OFF/ON/OFF | PASS: AAC count continued, normal TS finalization |
| Presence/format transition source | NOT_EXECUTED: no matching endpoints |

No app crash, ANR, deadlock, JNI fatal error, or stale-generation worker consumption was observed. Audible stale-audio perception was not instrumented; queue generation, Java write epoch, flushes, zero stale-block telemetry, and clock reset behavior provide the objective result.

# Long-run

NOT_EXECUTED. The required 20–30 minute Audio ON + Hardware Video + Recording run was not completed in this environment; shorter lifecycle/recording stress was executed instead.

# Video / Thermal Regression

Hardware HEVC MediaCodec plus `mediacodec_nv12_gl` remained active through audio toggle, Pause/Resume, recording, dynamic video format changes, and Surface stress. No hardware fallback or unexplained video decoder recreation was observed. Video render configuration, NV12/YUV GL, EGL/Surface architecture, snapshot, and OES code were not modified.

Thermal remained OFF during runtime. Thermal algorithm, Ironbow LUT, AGC, and controls were not modified; active Thermal runtime regression is therefore NOT_TESTED.

# Build

- `git diff --check`: PASSED (Git line-ending notices only).
- Direct audio/player unit or instrumentation tests: none exist in `app/src/test` or `app/src/androidTest`.
- Extra generic `:app:testDebugUnitTest`: FAILED before test execution in the repository's existing KAPT/JUnit annotation resolution (`ExampleUnitTest` stub generated `@error.NonExistentClass`). It does not compile or reference the A6 files; no direct A6 test failure exists.
- `:app:assembleDebug`: PASSED (`BUILD SUCCESSFUL`); Java and CMake compiled successfully for `arm64-v8a` and `armeabi-v7a`.
- No dependency or SDK configuration change.

# Remaining Issues

- Real AAC RTSP disconnect/reconnect x5–10 was not executable; generic HTTP reconnect was exercised instead.
- Actual `has audio <-> no audio` and AAC decoded-format transition endpoints were unavailable; those paths have build/static verification only.
- The required 20–30 minute combined long-run was not executed.
- Active Thermal rendering was not runtime-tested (Thermal code was untouched).
- The unrelated template `:app:testDebugUnitTest` KAPT/JUnit resolution issue remains pre-existing; the required app build passes.

# Slice A6 Freeze

The lifecycle implementation meets the A6 freeze gates: toggle and Pause/Resume isolate stale PCM, reconnect resets generation/clock, presence/format paths are safe, Surface is independent, Stop/Release deterministically join, AudioTrack writes are cancellable, JNI/GlobalRef lifetime is ordered, audio failure falls back to video, recording remains compressed-packet independent, hardware video is preserved, and the final build passes.

Runtime coverage is PARTIAL only because no AAC RTSP presence/format matrix and no 20–30 minute run were available; no unverified runtime result is presented as executed.

**Slice A6 Freeze: YES**

Commit message: `fix(player): stabilize live audio lifecycle`
