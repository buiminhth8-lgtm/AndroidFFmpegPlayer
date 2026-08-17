# Phase 2 Post-Freeze Fix 3 - Prepare/Start Lifecycle Report

# Problem

`NativePlayer::prepare()` already made a realtime source playback-ready, but the
normal realtime `PREPARED -> PLAYING` path discarded that work. In particular,
the pre-fix `start()` path could close and reopen the input and recreate the
video decoder, including an allocated `hevc_mediacodec` session. This added a
reconnect-like operation and decoder/Codec2 churn to ordinary startup.

# Root Cause

The pre-fix guard was effectively:

```text
isRealtimeInput_ && !remuxRecorder_.isRecording()
    -> refreshRealtimeInputForStart()
```

`refreshRealtimeInputForStart()` implemented freshness by calling
`releaseFfmpegResources()`, clearing the cached frame, and calling
`openInput()` again. The intent was to avoid starting from data probed long
before the user pressed Start, but freshness, clock reset, input recovery, and
resource creation were coupled into one destructive operation.

# Prepare Before Fix

The audited `prepare()` path stopped any previous playback, reset diagnostics,
cleared the cached frame, and called `openInput()`. `openInput()` performed:

- `avformat_open_input()` and `avformat_find_stream_info()`;
- stream selection and runtime stream metadata setup;
- video `AVCodecContext` allocation/configuration and `avcodec_open2()`;
- `hevc_mediacodec` allocation/open when hardware decode was enabled;
- audio decoder open when an audio stream was usable;
- packet/frame allocations required by playback.

On success it entered `PREPARED`. Therefore Prepare really opened RTSP and,
for Hardware Decode ON, really created the MediaCodec-backed decoder.

# Start Before Fix

For a realtime source with no active remux recording, `start()` entered an
intermediate preparing state and called `refreshRealtimeInputForStart()`. That
helper reset the clock, released all FFmpeg resources, cleared the last frame,
and called `openInput()` again. Consequently normal Prepare -> Start opened the
input twice and opened the video decoder twice. With hardware decode, the
second decoder open recreated the MediaCodec session. The recording path was
deliberately exempt from this refresh.

# Prepare After Fix

Prepare retains responsibility for opening and validating the input,
discovering streams, opening the audio/video decoders, and establishing the
playback-ready `PREPARED` state. Successful Prepare records its completion time
and logs minimal session counters:

- `inputOpenCount`;
- `videoDecoderOpenCount`;
- `hardwareDecoderOpenCount`;
- `lastPrepareCostUs`.

The prepared `AVFormatContext`, video/audio codec contexts, stream indices,
packet/frames, and MediaCodec session remain valid until a real teardown event
such as Stop, Release, explicit reprepare/source change, transport switch, or
reconnect recovery.

# Start After Fix

Normal `PREPARED -> PLAYING` now:

1. validates that the prepared input and decoder resources still exist;
2. applies the bounded realtime freshness policy described below;
3. resets the realtime clock and stale rendered-frame state;
4. enters the existing startup keyframe gate;
5. starts the existing playback thread.

It does not call `releaseFfmpegResources()`, `openInput()`, or `avcodec_open2()`.
Repeated Start while already playing remains guarded. `STOPPED -> Start`
remains invalid until another Prepare, preserving the public contract.

# RTSP Input Reuse

The ordinary Start path reuses the prepared `AVFormatContext` and RTSP
connection. Static inspection confirms that `NativePlayer::start()` contains
no input close/open call. The new counter/logging makes an accidental reopen
observable.

On-device network smoke tests used a local HTTP HEVC transport stream because
the configured RTSP endpoint was unreachable. Both the immediate hardware run
and delayed hardware/software runs logged `inputOpenCount=1` at Start. Later
increments were accompanied by the existing EOF reconnect flow and were not
caused by Start. This validates the shared network-input reuse path, but it is
not a substitute for a real RTSP reuse/freshness test.

# Decoder Reuse

Normal Start reuses `videoCodecContext_` and the audio codec context created by
Prepare. On-device hardware and software network tests both showed
`videoDecoderOpenCount=1` at Start. Decoder count increased only when the
finite network test source reached EOF and the existing reconnect path opened
a new session.

# MediaCodec Reuse

The hardware test selected `hevc_mediacodec` and rendered through
`mediacodec_nv12_gl`. At normal Start the counters remained:

```text
inputOpenCount=1
videoDecoderOpenCount=1
hardwareDecoderOpenCount=1
```

Thus Start did not perform another hardware `avcodec_open2()` or allocate a
second MediaCodec session. A genuine reconnect remains allowed to recreate it.

# Startup Clock Reset

Start still calls `resetRealtimeClock()` before the playback thread begins.
This clears `realtimeClockInitialized_`, `realtimeFirstPtsUs_`, and
`realtimeStartWallUs_`; Prepare time therefore does not become a playback clock
anchor or make the first frame appear late by the Prepare-to-Start delay.

The keyframe gate calls `resetRealtimeClock()` again when it finishes. The
first subsequent valid video PTS is paired with the current steady-clock time
by `resolveMasterClockUs()`, after which normal late-frame and packet/frame
drop decisions apply.

# Startup Keyframe Policy

`beginStartupKeyFrameWait("start")` is preserved. Non-video packets and
non-key video packets are discarded while the gate is active; the first key
packet finishes the gate and establishes a new playable GOP/clock baseline.
The existing four-second timeout behavior is unchanged. Reconnect and
transport-switch paths retain their own keyframe gates.

# Delayed Start Freshness Policy

`avformat_find_stream_info()` may read packets while probing and the demuxer
may retain packets for later `av_read_frame()` calls. There is no playback
thread between Prepare and Start, however, so this code does not continuously
build a project-level multi-second packet/frame queue during the wait.

For a non-recording RTSP source whose Prepare-to-Start delay exceeds the
already configured `dropLateFrameThresholdUs`, Start now performs a best-effort
read-side `avio_flush()` followed by `avformat_flush()`. It then clears the
cached rendered frame, resets the realtime clock, and enters the keyframe gate.
This keeps the RTSP connection, stream discovery, codec contexts, and
MediaCodec session intact. Non-RTSP network formats are reused without a
format flush because blindly resynchronizing formats such as HTTP/HLS is not
generally safe. Recording retains the previous exemption and is not flushed.

The FFmpeg/AVIO flush discards userspace demux/read buffers; it cannot prove
that every RTSP protocol, kernel socket buffer, or server has no older network
data queued. A real 5-second and 15-second RTSP test is therefore required
before claiming live-edge freshness. No such endpoint was reachable in this
run, so both delayed RTSP cases are `NOT_EXECUTED` and are not reported as
passing.

# Pause/Resume Audit

The existing `PAUSED -> Start` branch resumes the current playback state and
returns before the new prepared-input handling. It does not flush/reopen the
input, reset the stream, or recreate MediaCodec. No pause semantics were
redesigned.

Audited transitions:

```text
CREATED -> PREPARED                 unchanged
PREPARED -> PLAYING                optimized by this fix
PLAYING -> PAUSED                  unchanged
PAUSED -> PLAYING                  unchanged resume path
PLAYING -> STOPPED                 unchanged teardown path
DISCONNECTED -> RECONNECTING
             -> RECONNECTED/PLAYING unchanged recovery path
```

# Reconnect Audit

`reconnectInput()` still releases FFmpeg resources, calls `openInput()`, resets
the realtime clock, and invokes
`beginStartupKeyFrameWait("reconnect")`. Reconnect is a true recovery event and
may reopen RTSP and recreate either the software decoder or MediaCodec.

The finite HTTP test source exercised EOF recovery on-device: input and decoder
counts changed from 1 to 2 only after EOF, and playback re-entered the existing
keyframe recovery path. RTSP timeout recovery itself was not executable because
the RTSP endpoint was unavailable.

# Fix 1 Regression

No thermal option replay, palette, gamma/window, AGC, or shader code changed.
The dedicated fresh Hardware ON/OFF + Thermal ON + Ironbow runtime matrix was
not run, so Fix 1 regression status is `NOT_TESTED`.

# Fix 2 Regression

No Surface/EGL lifecycle code was changed by Fix 3. A hardware smoke run kept
`mediacodec_nv12_gl` active and rendered a 1280x720 -> 192x256 format change;
the fresh software run rendered the same change through `software_yuv_gl`.
The complete Fix 2 detach/reattach/source-switch stress matrix was not run, so
the aggregate Fix 2 regression status remains `NOT_TESTED`.

A same-process hardware-to-software reprepare experiment exposed an existing
cross-renderer EGL surface conflict (`eglCreateWindowSurface`, EGL `0x3003`). A
fresh-process software run passed, so this is not attributed to the new
Prepare/Start reuse path, but it remains a separate lifecycle issue.

# Software Decode Regression

A fresh app process with Hardware Decode OFF selected the FFmpeg `hevc`
software decoder and `software_yuv_gl`. After a 16,657 ms Prepare-to-Start wait,
Start logged input/decoder/hardware counts `1/1/0`, created one owner-thread YUV
EGL context, rendered the first frame in 86 ms, and handled the
1280x720 -> 192x256 format change. The fresh software path passed.

# Recording Regression

The old code skipped its destructive refresh while
`remuxRecorder_.isRecording()` was true. The replacement preserves that
behavior: it reuses the prepared input but skips the RTSP freshness flush and
cached-frame clear while recording. No recording packet or timestamp algorithm
changed. Recording was not exercised at runtime, so its regression status is
`NOT_TESTED`.

# Startup Latency

No reliable pre-fix timing baseline exists, so pre-fix latency is
`NOT_AVAILABLE` rather than inferred.

Observed post-fix device timings:

| Case | Prepare cost | Prepare -> Start | Start -> first frame | Counts at Start |
|---|---:|---:|---:|---|
| Hardware, immediate HTTP network smoke | about 142 ms | 446 ms | 89 ms | input 1, decoder 1, hardware 1 |
| Hardware, delayed HTTP network smoke | same prepared session | 48,130 ms | 133 ms | input 1, decoder 1, hardware 1 |
| Fresh software HTTP network smoke | 57.237 ms | 16,657 ms | 86 ms | input 1, decoder 1, hardware 0 |

`startToFirstFrameMs` is recorded only by the first successful renderer output
after Start, across the OES, NV12 GL, software YUV GL, MediaCodec surface, and
RGBA fallback render paths.

# Build

- `git diff --check`: passed; only Git's existing LF/CRLF conversion notices
  were emitted.
- `gradlew.bat :app:assembleDebug`: passed, including the native arm64-v8a and
  armeabi-v7a builds.
- No directly relevant project unit/instrumentation test existed beyond the
  template example tests.

# Runtime Verification

Device `34aff35a` (`Bengal_for_arm64`) was connected. The debug APK installed
and launched without a crash.

- Immediate Hardware ON network Start: `PASS` for lifecycle reuse;
  `hevc_mediacodec`, `mediacodec_nv12_gl`, counters `1/1/1`, first frame 89 ms.
- Long-delayed Hardware ON network Start: `PASS` for lifecycle reuse on HTTP;
  48,130 ms delay, counters still `1/1/1`, first frame 133 ms.
- Fresh Hardware OFF network Start: `PASS`; software `hevc` +
  `software_yuv_gl`, counters `1/1/0`, first frame 86 ms.
- EOF reconnect smoke: `PASS` on the finite HTTP test source; count increments
  were attributable to reconnect.
- Dynamic 1280x720 -> 192x256 decode/render smoke: `PASS` on hardware and
  software paths.
- True RTSP immediate/5-second/15-second freshness tests: `NOT_EXECUTED`.
  The configured `rtsp://192.168.1.101:554/main.mov` was unreachable from the
  device (`192.168.110.102`, destination host unreachable).
- Thermal replay, full Surface/EGL stress, and recording matrices:
  `NOT_TESTED`.

# Required Answers

1. **Does Prepare really open RTSP?** YES. `openInput()` calls
   `avformat_open_input()` and stream discovery during Prepare.
2. **Does Prepare create MediaCodec?** YES when Hardware Decode is enabled;
   the selected `hevc_mediacodec` is opened during Prepare.
3. **Why did Start reopen before the fix?** The blanket realtime/non-recording
   freshness branch implemented freshness by releasing all FFmpeg resources
   and calling `openInput()` again.
4. **Does normal Start open RTSP after the fix?** NO. It reuses the prepared
   format context/connection. Genuine recovery and explicit reprepare remain
   exceptions.
5. **Normal Prepare -> Start RTSP open count?** 1 by the implemented lifecycle;
   the analogous on-device network path observed 1. A true RTSP run was not
   available.
6. **Normal Prepare -> Start MediaCodec open count?** 1. The on-device hardware
   path observed `hardwareDecoderOpenCount=1` at Start.
7. **Does a five-second delayed RTSP Start avoid old buffered video?** UNKNOWN /
   `NOT_EXECUTED`; the RTSP endpoint was unreachable and userspace flush alone
   cannot prove this for all socket/server queues.
8. **Does a fifteen-second delayed RTSP Start avoid old buffered video?**
   UNKNOWN / `NOT_EXECUTED` for the same reason.
9. **How is the realtime clock re-established?** Start clears all realtime
   anchors; completion of the startup keyframe gate clears them again, and the
   first subsequent valid PTS is anchored to the current steady clock.
10. **Is the first-keyframe wait preserved?** YES.
11. **Can reconnect still rebuild the decoder?** YES. Its explicit
    release/open/keyframe recovery flow is unchanged.
12. **Does source switching still follow Fix 2?** YES by code audit; Fix 3 does
    not change format commit or Surface/EGL ownership, and dynamic format smoke
    passed. The full Fix 2 stress matrix was not repeated.

# Remaining Issues

- Run immediate, 5-second, and 15-second tests against a reachable, continuous
  RTSP HEVC source. Verify live-edge content, no late-frame avalanche, stable
  `inputOpenCount=1`/`hardwareDecoderOpenCount=1`, and the expected freshness
  flush/keyframe diagnostics.
- Run the full Fix 1 thermal replay, Fix 2 Surface/EGL stress, RTSP reconnect,
  and recording regression matrices.
- Investigate the separately observed same-process hardware-NV12 to
  software-YUV renderer switch EGL `0x3003` conflict; the fresh software path
  itself passed.

# Fix Runtime Verified: NO

The code, build, hardware/software network reuse, first-frame timing, dynamic
format handling, and generic reconnect behavior were exercised. The hard gate
cannot be marked YES because a reachable true RTSP source was unavailable and
the required 5-second/15-second RTSP freshness, thermal replay, complete
Surface/EGL, and recording matrices were not executed.
