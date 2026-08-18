# Scope

Audio Phase 1 Slice A8 performs the final audit, device matrix, long-run,
backpressure, recording, lifecycle, race, video/Thermal, and build validation
for the existing live-audio pipeline. Baseline was clean at commit `99899f1`
(`test(player): validate live audio integration`). No codec, AudioFocus,
Bluetooth, speed/time-stretch, noise reduction, microphone/talkback,
Media3/ExoPlayer, local playback, OES, audio encoder, dependency, recorder
architecture, or large `NativePlayer` change was added.

The long-run exposed one direct regression: bounded-queue/catch-up drops can
create a same-generation PCM PTS gap, but the A5 AudioTrack clock mapping only
rebased for a new generation or invalid clock. The resulting old mapping let
`audioVideoDiffUs` grow in one direction. The A8 fix is limited to detecting a
PCM PTS discontinuity greater than 20 ms, rebasing the existing playback-head
mapping, resetting that expectation at existing discontinuity boundaries, and
reporting a distinct counter. Decoder, SWR, queue, worker, JNI sink,
AudioTrack, recorder, MediaCodec, renderer, and thread ownership are unchanged.

# A7 Baseline

`AUDIO_PHASE1_SLICE_A7_RECORDING_STATS_INTEGRATION_REPORT.md` was reviewed in
full. The frozen live path was complete before A8:

`network AAC -> FFmpeg decode -> AVFrame -> SWR -> S16/48 kHz/stereo PCM ->
bounded queue -> joined native worker -> JNI LiveAudioPcmSink -> AudioTrack ->
playback-head clock -> A/V sync`

In parallel, each original compressed AAC packet reaches
`PlayerRemuxRecorder` before playback decode/drop decisions and is remuxed to
MP4 through `aac_adtstoasc`. Audio monitoring does not own recorder mapping.
`NativePlayer` remains the live RTSP/network player. Local MP4/VOD remains a
future, separate Media3/ExoPlayer task.

# Final Audio Architecture

The following final invariants are frozen:

| Invariant | Result |
| --- | --- |
| Audio OFF does not stop input, video, or compressed-packet recording | PASS |
| Audio ON/OFF does not reopen input or recreate video MediaCodec | PASS; 30 pairs retained one input/decoder session |
| PCM transport is bounded and producer-side overflow drops old PCM | PASS |
| Sink delay/failure cannot block the playback producer indefinitely | PASS; non-blocking sink and drop-oldest queue retained |
| Invalid/stale audio clock selects video master | PASS |
| Reconnect/toggle/pause advances generation and rejects old PCM | PASS |
| Stop/Release joins the worker before JNI GlobalRef/native destruction | PASS |
| Recorder consumes original compressed AAC, never playback PCM | PASS |
| Surface/EGL lifecycle does not own audio lifecycle | PASS |
| Audio does not select video/Thermal fallback | PASS |

The A8 clock correction changes only media-time mapping after an observed PCM
PTS hole. `audioClockPtsDiscontinuityCount` is separate from generation resets,
queue drops, sink errors, and recorder counters.

# Runtime Matrix

Device: `34aff35a` (`Bengal_for_arm64`). Because no AAC RTSP endpoint was
available, runtime used a paced localhost HTTP MPEG-TS source over `adb
reverse`; it carried HEVC Main 640x360 at 10 fps plus AAC LC 16 kHz stereo.

| Case | Result | A8 evidence |
| --- | --- | --- |
| A. Hardware Video + Audio ON | PASS | MediaCodec/NV12 GL, worker/sink/clock valid, master audio |
| B. Hardware Video + Audio OFF | PASS | worker/clock disabled, master video; input/decoder and recorder continued |
| C. Hardware + Ironbow + Audio ON | PASS | active UI `render IRONBOW`; Thermal frames 41 -> 446; no fallback |
| D. Software Video + Audio ON | PASS | software HEVC -> YUV420P -> YUV GL; audio worker/clock valid |
| E. Recording + Audio ON | PASS | 30-minute combined run and dual-track MP4 |
| F. Recording + Audio OFF | PASS | recorder AAC count continued while playback worker/clock were off |
| G. Audio OFF -> ON x30 | PASS | generation 60; worker start/join 31/30 while playing; decoder remained 1/1 |
| H. Pause -> Resume x20 | PASS | 20 valid cycles across one generic reconnect; final stale/sink errors zero |
| I. Surface detach -> reattach x20 | PASS | Surface generation +40; one EGL context; audio worker generation unchanged |
| J. Disconnect -> reconnect x10 | PARTIAL | one A8 generic HTTP recovery passed; actual RTSP x10 unavailable |
| K. Stop/recreate/Start x10 | NOT_EXECUTED | A6 passed x10; not repeated without an AAC RTSP endpoint |
| L. Release races | PASS | active Audio + Stats + Surface + Release, plus built-in lifetime stress |

Runtime status is **PARTIAL**. All principal audio paths were exercised on a
real device with network-paced AAC, but actual AAC RTSP reconnect x10 and the
complete RTSP-specific matrix were unavailable and are not claimed.

# Long-run Validation

**PASS.** Hardware Video + Audio ON + Recording ON ran continuously from
2026-08-18 15:55:10 to 16:25:47, approximately 30 minutes 37 seconds wall
clock. The 1,860-second paced source reached natural EOF only about 0.35 seconds
before Stop Record.

- No native crash, ANR, fatal JNI error, OOM, decoder fallback, renderer
  fallback, or unexpected reconnect occurred during the continuous interval.
- MediaCodec and input session counts remained 1/1. Audio worker remained one
  live worker, sink restart remained one, sink errors/stale blocks remained
  zero, and AudioFlinger reported the app's stereo PCM 48 kHz track active.
- Drift samples remained approximately -112 ms to +148 ms and repeatedly
  crossed/returned toward zero; the last pre-EOF sample was about +68 ms.
- Queue high watermark remained 192,000 us below the 250,000 us hard limit.
  Drops rose to 5,144 under real sink pressure without queue or memory growth.
- PSS moved from about 112,050 KiB near start to a noisy 123-127 MiB plateau in
  the latter run; after EOF/final sampling it was 123,764 KiB. No instance,
  worker, queue, EGL-context, decoder, or native-heap population grew without
  bound.
- Recorder completed 18,360 HEVC packets and 28,689 original AAC packets.
  The pulled 136,098,925-byte MP4 was 1,836.096 seconds and contained HEVC Main
  640x360/10 fps plus AAC LC 16 kHz stereo. Both first and last five seconds
  decoded with host FFmpeg without errors.

Human acoustic perception was not instrumented; continuous AudioTrack writes,
playback-head movement, valid audio master, and active AudioFlinger track are
the objective playback evidence.

# Queue / Backpressure

**PASS.** `AudioPcmQueue` retains its 250 ms hard duration limit and drop-oldest
producer policy. A representative stressed sample showed 6,270 decoded/PCM
blocks, 5,105 worker-consumed blocks, 5,104 complete sink writes, and 1,165
queue drops while video, compressed AAC recording, and Stats continued. The
30-minute run reached 5,144 drops but never exceeded a 192 ms measured high
watermark. The producer did not wait for AudioTrack; memory and queue depth did
not track the cumulative drop count.

# A/V Sync / Drift

The first long-run attempt reproduced a real clock regression: queue drops
rose from 46 to 90 to 177 while `audioVideoDiffUs` grew from about 5.3 seconds
to 11.2 seconds to 19.75 seconds. This was traced to same-generation PCM PTS
holes retaining an obsolete AudioTrack playback-head base.

After the narrow PTS-discontinuity rebase fix, queue drops still increased but
drift stayed bounded. Short validation ranged roughly -68 ms to +96 ms; the
formal long run ranged roughly -112 ms to +148 ms without sustained direction.
Normal Audio ON reported `audioPlaybackClockValid=true` and
`effectiveSyncMaster=audio`. Audio OFF and EOF/reconnect-wait invalidated the
clock and safely reported video master.

# Toggle / Pause / Surface

- Audio OFF -> ON x30 passed. Final generation was 60, worker start/join was
  31/30 while playing, sink restarts 31, stale blocks and sink errors zero.
  Input and video decoder open counts remained one.
- Pause -> Resume x20 passed. Seven cycles preceded a natural EOF; after the
  generic source recovery, playback resumed and thirteen additional valid
  cycles completed. Final worker start/join was 52/51 while playing, clock was
  valid, and effective master was audio.
- Surface clear -> reattach x20 passed. Surface generation advanced by 40 and
  EGL surface creation advanced by 20 while the EGL context stayed at one.
  Audio generation and worker counts did not change; recording AAC counts kept
  increasing; NV12 fallback and swscale counts remained zero.

# Reconnect

The executed generic network recovery passed: EOF advanced/flushed the old
audio generation, invalidated clock/head state, selected video master, and left
the recorder open. A restarted paced source with continuous timestamp offset
reconnected successfully, established a new decoder/audio generation, restored
the AudioTrack clock/master, and resumed recorder counters. No stale worker
block or sink error was observed. The video decoder count changed from 1/1 to
2/2 only for this real reconnect.

An actual AAC RTSP camera/server was unavailable, so disconnect/reconnect x10
on RTSP transport is **NOT_EXECUTED** and prevents runtime freeze.

# Release / Race Safety

**PASS** for the executed and inherited coverage.

- While Hardware + Ironbow + Audio ON was active, Stats, Surface clear, and
  Release were issued together. Release drained active operations, ended the
  audio worker, stopped playback, removed the handle, and left the process
  alive with zero registered players.
- The built-in lifetime stress passed 100 create/release cycles and 20
  concurrent release cycles. It confirmed unique handles, duplicate-release
  safety, stale-handle safety, old-handle isolation, release waiting for active
  operations, closing-state rejection, concurrent Stats/Thermal/release safety,
  and `activePlayerCount=0`.
- A6's existing active-Audio toggle/Stats/Surface/release race coverage was
  reviewed and remains unchanged. Worker threads are joined, not detached;
  JNI GlobalRef destruction remains after worker shutdown.

No UAF, double free, deadlock, JNI fatal, crash, or orphan worker was observed.

# Recording Independence

**PASS.** The formal MP4 retained original compressed AAC remux throughout
Audio playback. A separate mixed matrix recording remained open across Audio
ON/OFF, pause, generic reconnect, and Surface operations and finalized normally
with 3,380 HEVC packets and 5,283 AAC packets. Host `ffprobe` reported a
358.016-second dual-track HEVC + AAC MP4. Audio OFF changed only playback
decode/queue/worker/sink/clock state; recorder AAC counts continued. No PCM
encoder exists in this path and stream mapping was not recreated.

A video-only runtime source was unavailable. Its existing static mapping branch
was audited but is reported **NOT_EXECUTED** for A8 runtime.

# Stats Consistency

**PASS.** Stats continue to distinguish source presence, user enablement,
decoder availability, current playability, worker/sink readiness, playback
clock validity/time, requested/effective master, and independent demux, decode,
PCM, queue, sink, and recorder counters. Observed Audio OFF samples had growing
demux/record counts with unchanged playback counters; Audio ON samples had
independently growing decode/PCM/worker/sink counts. `recordAudioPacketCount`
never aliases PCM or sink writes.

The new `audioClockPtsDiscontinuityCount` identifies only same-generation PTS
rebases. Existing `audioClockResetCount`, queue drop/flush/generation, sink
error/cancel/restart, decoder open, and recorder meanings were not changed.

# Video / Thermal Regression

**PASS** for exercised paths.

- Hardware: `decodeBackend=mediacodec`, `frameOutputType=nv12_cpu`,
  `renderer=nv12_gl`, hardware/render fallback false, swscale disabled. The
  formal long run retained one decoder and one EGL context.
- Software: `decodeBackend=software`, `frameOutputType=yuv420p_cpu`,
  `renderer=yuv_gl`, with growing rendered frames and no fallback.
- Active Hardware + Ironbow + Audio ON showed `render IRONBOW` and Thermal
  rendered frames increasing from 41 to 446 while audio clock/master remained
  valid. A8 also exercised Thermal enable, palette, gamma, and AGC controls;
  the frozen A7 baseline already covers Original, White Hot, Ironbow, Gamma,
  Window, and AGC. No Thermal shader/LUT/control code changed in A8.

# Build / Tests

- `git diff --check`: PASS; line-ending conversion notices only, no whitespace
  error.
- `gradlew.bat :app:assembleDebug`: **PASS**; Java/CMake/package completed for
  arm64-v8a and armeabi-v7a.
- `gradlew.bat :app:testDebugUnitTest`: blocked before test execution at
  `:app:kaptDebugUnitTestKotlin`; generated `ExampleUnitTest.java` contains
  `@error.NonExistentClass()` and reports `NonExistentClass cannot be converted
  to Annotation`.
- `gradlew.bat :app:connectedDebugAndroidTest`: blocked before test execution
  at `:app:kaptDebugAndroidTestKotlin` by the same generated
  `@error.NonExistentClass()` issue in `ExampleInstrumentedTest.java`.
- Host FFmpeg/ffprobe validation of the formal long-run MP4 and mixed matrix
  MP4: PASS.

Host Build: **PASS**

Host tests: **PARTIAL** (existing test-infrastructure/KAPT failure)

Runtime: **PARTIAL**

Long-run: **PASS**

# Remaining Issues

1. No actual AAC RTSP endpoint was available. RTSP disconnect/reconnect x10 and
   the full RTSP-specific runtime matrix remain lab work; generic paced-network
   reconnect passed.
2. Stop/recreate/Start x10 was not repeated in A8; the unchanged A6 path has an
   existing x10 pass.
3. No video-only runtime source was available; video-only MP4 remains static
   audit coverage in A8.
4. Human audible perception was not measured. AudioTrack/AudioFlinger and clock
   telemetry provide objective playback proof.
5. The pre-existing KAPT test-stub annotation failure prevents both unit and
   instrumentation test execution; the production debug build passes.

# Audio Phase 1 Final Freeze

Audio Phase 1 Architecture Freeze: **YES**. The live AAC decode-to-AudioTrack
chain, bounded/non-blocking PCM transport, generation/lifetime rules,
AudioTrack clock with same-generation PTS rebase, video-master fallback,
compressed-AAC recording independence, Surface separation, Video/Thermal
integration, Stats meanings, and D-task boundary are complete and frozen.

Audio Phase 1 Runtime Freeze: **PENDING**. The required 30-minute combined run
passed on device, as did the available lifecycle/race/video/Thermal matrix, but
actual AAC RTSP x10 recovery and video-only runtime coverage were unavailable.
No unexecuted item is presented as a runtime pass.

No Audio Slice A9 is created. Audio Phase 1 ends here; D, Recording Phase B,
and OES Phase C remain separate future tasks.
