# Production Hardening PH1 Native Dependency Correctness

# Scope

PH1 audits and aligns the Android native runtime contract across the Java
loader, CMake imports/linkage, owned native symbol use, ELF `DT_NEEDED`, and AAR
packaging. The only production code change makes `libswresample.so` a required
Java load, matching its actual runtime role. No native library was deleted, no
FFmpeg binary was rebuilt or upgraded, and no Gradle dependency, consumer rule,
debug hook, Demo behavior, public API, RTSP option, decoder, renderer, audio
architecture, or player lifecycle was changed.

Audit environment: Windows, JDK 17, AGP 8.9.1, CMake 3.22.1, NDK
27.0.12077973, and connected arm64 device `34aff35a` (`Bengal_for_arm64`).

# PH0 Baseline

- PH1 started from clean branch `dev` at PH0 commit
  `a6c9b86 chore(player): baseline production hardening`.
- PH0 identified `swresample` as Java-optional but ELF-required.
- PH0 classified `libavfilter.so` and `libavdevice.so` as possibly unused and
  deferred any removal to PH2.
- Supported/package ABIs remain `arm64-v8a` and `armeabi-v7a`.
- Frozen player path remains
  `hevc_mediacodec -> nv12_cpu -> nv12_gl -> SurfaceView` for the validated
  explicit hardware profile.
- `PRODUCTION_HARDENING_PH0_BASELINE_REPORT.md` and
  `POST_LATENCY_CODE_CLEANUP_REPORT.md` were read before modification.

# Java Native Loader

Before PH1 the loader caught and suppressed an `UnsatisfiedLinkError` from
`System.loadLibrary("swresample")`, then continued loading libraries whose ELF
dependencies require `libswresample.so`. That could defer the failure to
`libavcodec` or `libnative-ffmpeg` loading and obscure the actual missing
required payload.

PH1 changes the call to the existing required-library path and removes the now
unused private optional helper. The regenerated release `classes.jar` bytecode
was inspected with `javap -c -p`; its static initializer invokes
`loadRequired(String)` for all six names in this exact order:

```text
avutil
swresample
swscale
avcodec
avformat
native-ffmpeg
```

`loadRequired` directly calls `System.loadLibrary`. If `swresample` is missing
or unloadable, its `UnsatisfiedLinkError` now escapes the static initializer at
the `swresample` step, preventing JNI use and retaining the loader's diagnostic
library/cause rather than continuing into a dependent `dlopen` failure.

# CMake Dependency

CMake already matched the owned native implementation and needed no PH1 edit:

| Library | CMake declaration | `target_link_libraries(native-ffmpeg)` | Verdict |
| --- | --- | --- | --- |
| `libavutil.so` | Imported shared | YES | REQUIRED |
| `libswresample.so` | Imported shared | YES | REQUIRED |
| `libswscale.so` | Imported shared | YES | REQUIRED |
| `libavcodec.so` | Imported shared | YES | REQUIRED |
| `libavformat.so` | Imported shared | YES | REQUIRED |
| `libavfilter.so` | Imported shared | NO | PH2_CANDIDATE |
| `libavdevice.so` | Imported shared | NO | PH2_CANDIDATE |
| `libnative-ffmpeg.so` | Built shared target | N/A | REQUIRED |

The owned audio path includes `libswresample/swresample.h` and directly calls:

```text
swr_alloc_set_opts2
swr_init
swr_get_delay
swr_convert
swr_free
```

Both generated `libnative-ffmpeg.so` ABIs have these five unresolved dynamic
symbols, version-bound to exports provided by `libswresample.so` as
`LIBSWRESAMPLE_6`. No owned source use of an `avfilter_*` or `avdevice_*` symbol
was found.

# ELF DT_NEEDED Matrix

The regenerated release AAR was extracted and inspected with NDK 27
`llvm-readelf -d`; `llvm-nm -D -u` and dynamic-symbol inspection were also used
for the SWR contract. The project-library relationships are identical for both
ABIs.

| ELF | arm64-v8a project `DT_NEEDED` | armeabi-v7a project `DT_NEEDED` | Other Android/system `DT_NEEDED` |
| --- | --- | --- | --- |
| `libavutil.so` | none | none | `libm`, `libandroid`, `libdl`, `libc` |
| `libswresample.so` | `libavutil.so` | `libavutil.so` | `libm`, `libc` |
| `libswscale.so` | `libavutil.so` | `libavutil.so` | `libm`, `libc` |
| `libavcodec.so` | `libswresample.so`, `libavutil.so` | `libswresample.so`, `libavutil.so` | `libm`, `libandroid`, `libmediandk`, `libz`, `libdl`, `libc` |
| `libavformat.so` | `libavcodec.so`, `libavutil.so` | `libavcodec.so`, `libavutil.so` | `libm`, `libz`, `libc` |
| `libavfilter.so` | `libswscale.so`, `libavformat.so`, `libavcodec.so`, `libswresample.so`, `libavutil.so` | same | `libm`, `libc` |
| `libavdevice.so` | `libavfilter.so`, `libavformat.so`, `libavcodec.so`, `libavutil.so` | same | `libc` |
| `libnative-ffmpeg.so` | `libavformat.so`, `libavcodec.so`, `libavutil.so`, `libswresample.so`, `libswscale.so` | same | `liblog`, `libandroid`, `libEGL`, `libGLESv2`, `libm`, `libdl`, `libc` |

An automated post-build closure check examined all 16 packaged ELF files. Every
project-library `DT_NEEDED` target exists in the same ABI directory:

```text
UNRESOLVED_PACKAGED_DEPENDENCY_COUNT=0
```

In particular, both `libnative-ffmpeg.so` and `libavcodec.so` directly declare
`DT_NEEDED => libswresample.so` for both ABIs.

# swresample Verdict

```text
swresample: REQUIRED
```

The verdict is established independently by all relevant layers:

1. The Live Audio implementation directly calls five `swr_*` APIs.
2. Both `libnative-ffmpeg.so` ABIs retain undefined references to those APIs.
3. Both `libnative-ffmpeg.so` ABIs directly need `libswresample.so`.
4. Both `libavcodec.so` ABIs also directly need `libswresample.so`.
5. CMake imports and directly links `swresample` into the native target.
6. Both source `jniLibs` and the rebuilt release AAR package it for both ABIs.
7. The connected-device load test loaded it successfully before its dependents
   and exercised AAC audio decode/output startup.

It is not correct to treat `swresample` as optional in this build.

# Loading Order

The library-name order did not change in PH1; only the required/optional
failure policy changed. The existing order is supported by the actual ELF graph:

```text
libavutil
  -> libswresample (needs avutil)
  -> libswscale    (needs avutil)
  -> libavcodec    (needs swresample + avutil)
  -> libavformat   (needs avcodec + avutil)
  -> libnative-ffmpeg (needs all five above)
```

`libavfilter.so` and `libavdevice.so` are not Java-loaded and are not reachable
from `libnative-ffmpeg.so`'s dependency graph. They remain packaged by explicit
PH1 rule and are not inserted into the production load sequence.

# AAR Native Contents

Rebuilt artifact:

```text
ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
size: 20,554,162 bytes (19.60 MiB)
SHA-256: 5E987C823670B49696FD2D9FAD0F592567DA2CEE259B0B7E356A1E1C6BE5F14A
```

Both ABI directories contain the same complete eight-library set:

| ABI | Library | Bytes |
| --- | --- | ---: |
| arm64-v8a | `libavcodec.so` | 12,956,800 |
| arm64-v8a | `libavdevice.so` | 49,456 |
| arm64-v8a | `libavfilter.so` | 3,781,768 |
| arm64-v8a | `libavformat.so` | 2,612,400 |
| arm64-v8a | `libavutil.so` | 731,072 |
| arm64-v8a | `libnative-ffmpeg.so` | 1,455,848 |
| arm64-v8a | `libswresample.so` | 90,240 |
| arm64-v8a | `libswscale.so` | 719,792 |
| armeabi-v7a | `libavcodec.so` | 12,596,904 |
| armeabi-v7a | `libavdevice.so` | 44,236 |
| armeabi-v7a | `libavfilter.so` | 3,125,304 |
| armeabi-v7a | `libavformat.so` | 2,500,428 |
| armeabi-v7a | `libavutil.so` | 659,256 |
| armeabi-v7a | `libnative-ffmpeg.so` | 1,011,652 |
| armeabi-v7a | `libswresample.so` | 83,216 |
| armeabi-v7a | `libswscale.so` | 566,228 |

No `.so` was removed, renamed, replaced, or rebuilt in PH1. The AAR size change
from PH0 is only the smaller `classes.jar` after removal of the unused private
optional-loader bytecode.

# Changes

- `FFmpegNative.java`: changed `loadOptional("swresample")` to
  `loadRequired("swresample")`.
- `FFmpegNative.java`: removed the now-unused private `loadOptional` helper that
  caught and suppressed `UnsatisfiedLinkError`.
- Added this PH1 evidence/freeze report.
- CMake, JNI/native C++, `jniLibs`, public API, and all playback configuration
  remain unchanged.

The resulting active runtime contract is aligned:

```text
Java Loader = CMake = ELF = AAR Packaging: YES
```

This equality applies to the required player chain. The two intentionally
extra packaged candidates are separately frozen for PH2 rather than silently
treated as runtime dependencies.

# Tests

| Test | Result | Evidence |
| --- | --- | --- |
| Release loader bytecode (`javap -c -p`) | PASS | `swresample` invokes `loadRequired`; no optional helper remains |
| Both-ABI packaged ELF closure | PASS | 16 ELF files inspected; zero unresolved packaged project dependencies |
| Connected arm64 native load | PASS | All six libraries logged as loaded in dependency-first order; JNI initialized |
| `FFmpegPlayer` create/release | PASS | Handle 1 created; listener/audio callback attached; release ended with active count 0 |
| Live Audio JNI/library path | PASS | AAC 16 kHz stereo stream opened, `audioPlayable=true`, audio worker started/ended, no SWR/link/JNI failure |
| Real-source prepare/start/stop | PASS for PH1 dependency scope | Input and HEVC/AAC decoders opened, first frame rendered, stop completed |
| armeabi-v7a device runtime | NOT_EXECUTED | Connected device is arm64; ABI ELF/package closure passed statically |
| `:app:testDebugUnitTest` | PASS | Gradle local unit tests completed |
| `DiagnosticsModeTest.cpp` | PASS | `ALL_DIAGNOSTICS_MODE_TESTS_PASSED` |
| `PreT0TimingTrackerTest.cpp` | PASS | `ALL_PRE_T0_TRACKER_TESTS_PASSED` |
| `E2ETimebaseTest.cpp` | PASS | `ALL_E2E_TIMEBASE_TESTS_PASSED` |

The live source emitted repeated EOF/reconnect events after successful startup;
this was external stream behavior and is not attributed to the loader change.
The dependency test had already proved load, create, audio initialization,
decoding, rendering, stop, and release. No latency/performance claim is made.

# Build

| Gate | Result |
| --- | --- |
| `git diff --check` | PASS (line-ending warning only) |
| `:ffmpegplayer:assembleDebug` | PASS (`arm64-v8a`, `armeabi-v7a`) |
| `:ffmpegplayer:assembleRelease` | PASS (`arm64-v8a`, `armeabi-v7a`) |
| `:app:assembleDebug` | PASS |
| Post-build release AAR inspection | PASS |

Gradle completed 113 actionable tasks (24 executed, 89 up-to-date). Existing
SDK XML version, KAPT processor-option, deprecated Gradle feature, and MSVC
source-code-page warnings did not fail the gates and are outside PH1 scope.

# PH2 Candidates

```text
avfilter: PH2_CANDIDATE
avdevice: PH2_CANDIDATE
```

- Neither library is loaded by Java.
- Both are imported but intentionally absent from
  `target_link_libraries(native-ffmpeg)`.
- Owned native source uses neither API family.
- `libnative-ffmpeg.so` needs neither library.
- `libavdevice.so` needs `libavfilter.so`; only this otherwise unreachable
  candidate-to-candidate edge retains that branch.
- Both libraries and their complete dependency closure remain packaged for both
  ABIs in PH1, as required.

PH2 must independently remove the candidates, regenerate the AAR, repeat ELF
closure and both-ABI/device regression, and measure the size delta. PH1 does not
assert removal safety beyond candidate status.

# PH1 Freeze

- `swresample: REQUIRED`.
- `avfilter: PH2_CANDIDATE`.
- `avdevice: PH2_CANDIDATE`.
- Native loading order changed: **NO**.
- Native loading failure policy corrected: **YES**.
- `Java Loader = CMake = ELF = AAR Packaging`: **YES** for the required runtime
  chain.
- Native load/create/audio/release device test: **PASS**.
- Build gates and existing unit/host tests: **PASS**.
- Playback behavior, RTSP config, decoder, renderer, audio architecture, ABI,
  public API, CMake, JNI names, and packaged library set: unchanged.
- No PH2 library removal or slimming was performed.
- PH2 readiness: **READY**, only under a separate explicit task.

PH1 is frozen. Do not begin PH2 as part of this slice.
