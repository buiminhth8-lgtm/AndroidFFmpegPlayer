# Production Hardening PH2 AAR Native Slimming

# Scope

PH2 removes only the two native libraries proven unreachable by the production
player dependency graph: `libavfilter.so` and `libavdevice.so`. The removal is
applied consistently to `arm64-v8a` and `armeabi-v7a`, and their unused CMake
imported targets are removed. The Release AAR is rebuilt and inspected as a ZIP
and as ELF, followed by Gradle, host, and connected-device regression gates.

No FFmpeg library was recompiled or upgraded. No Java loader, JNI name, public
API, Gradle dependency, consumer rule, debug/test hook, Demo behavior, RTSP
option, decoder, renderer, audio, recording, thermal, reconnect, or lifecycle
implementation was changed.

Audit environment: Windows, JDK 17, AGP 8.9.1, CMake 3.22.1, NDK
27.0.12077973, and connected arm64 device `34aff35a` (`Bengal_for_arm64`).

# PH1 Dependency Baseline

- PH2 started from a clean `dev` worktree at
  `3be0f9c fix(player): align native runtime dependencies`.
- `PRODUCTION_HARDENING_PH0_BASELINE_REPORT.md` and
  `PRODUCTION_HARDENING_PH1_NATIVE_DEPENDENCY_REPORT.md` were read in full
  before modification.
- PH1 froze `libswresample.so` as REQUIRED and aligned it across the Java
  loader, CMake, ELF, and AAR packaging.
- PH1 retained `libavfilter.so` and `libavdevice.so` as PH2 candidates pending
  this independent removal audit.
- Supported and packaged ABIs are `arm64-v8a` and `armeabi-v7a`.
- The PH1 Release AAR baseline was regenerated immediately before PH2 and
  measured as 20,554,162 bytes with SHA-256
  `5E987C823670B49696FD2D9FAD0F592567DA2CEE259B0B7E356A1E1C6BE5F14A`.

# Removal Candidates

```text
libavfilter: REMOVED
libavdevice: REMOVED
```

The two libraries formed an isolated candidate branch:

```text
libavdevice.so -> libavfilter.so -> required FFmpeg libraries
```

No required library or `libnative-ffmpeg.so` pointed back into that branch.
Both candidates satisfy every required deletion condition for both ABIs.

# Evidence

| Required condition | `libavfilter.so` | `libavdevice.so` | Evidence |
| --- | --- | --- | --- |
| Java does not load the library | PASS | PASS | Release bytecode loads only `avutil`, `swresample`, `swscale`, `avcodec`, `avformat`, and `native-ffmpeg` |
| Owned native code has no symbol call | PASS | PASS | Scoped `rg` of owned Java/C++ found zero candidate API references |
| Native target does not link the library | PASS | PASS | Neither appeared in `target_link_libraries(native-ffmpeg)`; both were imported-only declarations |
| `libnative-ffmpeg.so` has no `DT_NEEDED` | PASS | PASS | NDK 27 `llvm-readelf -d`, both ABIs |
| Required FFmpeg runtime has no `DT_NEEDED` | PASS | PASS | `libavformat`, `libavcodec`, `libavutil`, `libswresample`, and `libswscale` inspected for both ABIs |
| No dynamic candidate loading | PASS | PASS | Owned production code has no `dlopen`/`dlsym`; required ELF strings contain no candidate `.so` name |
| No business feature depends on it | PASS | PASS | Playback, decode, render, audio, record, thermal, snapshot, and reconnect paths contain no candidate API use |

`llvm-nm -D -u` found zero `avfilter`/`avdevice` candidate symbols in both
generated `libnative-ffmpeg.so` files. Before removal, the only candidate
`DT_NEEDED` edge was from `libavdevice.so` to `libavfilter.so`; therefore the
two libraries had to be considered and removed together.

# Removed Libraries

The following four tracked binary payloads were removed:

| ABI | Library | Removed bytes |
| --- | --- | ---: |
| arm64-v8a | `libavfilter.so` | 3,781,768 |
| arm64-v8a | `libavdevice.so` | 49,456 |
| armeabi-v7a | `libavfilter.so` | 3,125,304 |
| armeabi-v7a | `libavdevice.so` | 44,236 |
| **Total** | **4 files** | **7,000,764** |

The unused `avfilter` and `avdevice` imported-target declarations were also
removed from `ffmpegplayer/src/main/cpp/CMakeLists.txt`. No loader entry existed
for either candidate, so no Java loader change was required.

# Kept Libraries

Each ABI retains exactly this required six-library set:

```text
libavutil.so
libswresample.so
libswscale.so
libavcodec.so
libavformat.so
libnative-ffmpeg.so
```

The Java name order remains exactly:

```text
avutil
swresample
swscale
avcodec
avformat
native-ffmpeg
```

Post-build `javap -c -p` confirms every name still invokes `loadRequired` and
that no optional loader exists. Vendored headers were retained; they are not AAR
native payload and removing the FFmpeg SDK surface is outside PH2.

# ELF Validation

The rebuilt Release AAR was extracted and all 12 packaged ELF files were
inspected with NDK 27 `llvm-readelf -d`. Project-library dependencies are
identical between ABIs:

| ELF | Project `DT_NEEDED` |
| --- | --- |
| `libavutil.so` | none |
| `libswresample.so` | `libavutil.so` |
| `libswscale.so` | `libavutil.so` |
| `libavcodec.so` | `libswresample.so`, `libavutil.so` |
| `libavformat.so` | `libavcodec.so`, `libavutil.so` |
| `libnative-ffmpeg.so` | `libavformat.so`, `libavcodec.so`, `libavutil.so`, `libswresample.so`, `libswscale.so` |

For each ELF, every project `DT_NEEDED` target exists in the same ABI directory:

```text
UNRESOLVED_PACKAGED_DEPENDENCY_COUNT=0
```

Neither removed library appears as `DT_NEEDED` or as a candidate library-name
string in the remaining ELF set. ELF dependency validation: **PASS**.

# AAR Contents

Rebuilt artifact:

```text
ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
size: 17,584,829 bytes (16.77 MiB)
SHA-256: 19045F5713C83385ED0259788DA52F1A8963FDAF0B226BBC662C6BE35479A88C
```

| ABI | Native library | Bytes |
| --- | --- | ---: |
| arm64-v8a | `libavcodec.so` | 12,956,800 |
| arm64-v8a | `libavformat.so` | 2,612,400 |
| arm64-v8a | `libavutil.so` | 731,072 |
| arm64-v8a | `libnative-ffmpeg.so` | 1,455,848 |
| arm64-v8a | `libswresample.so` | 90,240 |
| arm64-v8a | `libswscale.so` | 719,792 |
| **arm64-v8a total** | **6 libraries** | **18,566,152** |
| armeabi-v7a | `libavcodec.so` | 12,596,904 |
| armeabi-v7a | `libavformat.so` | 2,500,428 |
| armeabi-v7a | `libavutil.so` | 659,256 |
| armeabi-v7a | `libnative-ffmpeg.so` | 1,011,652 |
| armeabi-v7a | `libswresample.so` | 83,216 |
| armeabi-v7a | `libswscale.so` | 566,228 |
| **armeabi-v7a total** | **6 libraries** | **17,417,684** |

The AAR contains zero `libavfilter.so` or `libavdevice.so` entries. Required
library missing count is zero and unexpected native library count is zero for
both ABIs. AAR dependency validation and ABI consistency: **PASS**.

# Size Before / After

## Compressed Release AAR

| Metric | Before | After | Saved |
| --- | ---: | ---: | ---: |
| File bytes | 20,554,162 | 17,584,829 | 2,969,333 |
| MiB | 19.60 | 16.77 | 2.831777 |
| Percent | 100% | 85.553617% | 14.446383% |

## Uncompressed AAR native payload

| ABI | Before native bytes | After native bytes | Saved bytes |
| --- | ---: | ---: | ---: |
| arm64-v8a | 22,397,376 | 18,566,152 | 3,831,224 |
| armeabi-v7a | 20,587,224 | 17,417,684 | 3,169,540 |
| **Total** | **42,984,600** | **35,983,836** | **7,000,764** |

## Source `jniLibs`

| ABI | Before bytes | After bytes | Saved bytes |
| --- | ---: | ---: | ---: |
| arm64-v8a | 20,941,528 | 17,110,304 | 3,831,224 |
| armeabi-v7a | 19,575,572 | 16,406,032 | 3,169,540 |
| **Total** | **40,517,100** | **33,516,336** | **7,000,764** |

All values are actual file or ZIP-entry lengths. The compressed AAR reduction
is smaller than the uncompressed native reduction because `.so` payloads are
compressed in the archive.

# Build / Tests

| Gate | Result | Evidence |
| --- | --- | --- |
| `git diff --check` | PASS | No whitespace error; Windows LF/CRLF warning only |
| `:app:testDebugUnitTest` | PASS | Existing local unit tests completed |
| `:ffmpegplayer:assembleDebug` | PASS | Both configured ABIs rebuilt |
| `:ffmpegplayer:assembleRelease` | PASS | Release AAR regenerated |
| `:app:assembleDebug` | PASS | Slimmed library packaged into Demo APK |
| `DiagnosticsModeTest.cpp` | PASS | `ALL_DIAGNOSTICS_MODE_TESTS_PASSED` |
| `PreT0TimingTrackerTest.cpp` | PASS | `ALL_PRE_T0_TRACKER_TESTS_PASSED` |
| `E2ETimebaseTest.cpp` | PASS | `ALL_E2E_TIMEBASE_TESTS_PASSED` |
| Release AAR ZIP/ELF validation | PASS | 6 libraries per ABI; 0 unresolved project dependencies |
| Connected arm64 native load | PASS | Six required libraries and `JNI_OnLoad` succeeded in dependency-first order |
| `FFmpegPlayer` create/release | PASS | Handle 1 created; release completed with remaining player count 0 |
| armeabi-v7a device runtime | NOT_EXECUTED | Connected device is arm64; both-ABI build and static ELF/AAR validation passed |

Gradle completed 113 actionable tasks (22 executed, 91 up-to-date) with
`BUILD SUCCESSFUL`. The device log contained no `UnsatisfiedLinkError`,
`dlopen failed`, or fatal exception during load/create/release.

# Functional Invariants

```text
Playback behavior: NO CHANGE
RTSP config: NO CHANGE
Decoder: NO CHANGE
Renderer: NO CHANGE
Audio: NO CHANGE
Recording: NO CHANGE
Thermal: NO CHANGE
Reconnect: NO CHANGE
Public API: NO CHANGE
```

Only unused package payload and unused CMake imported declarations changed.
The active Java/CMake/ELF/AAR runtime chain remains aligned and complete.

# PH2 Freeze

- `libavfilter.so`: **REMOVED** from both ABIs.
- `libavdevice.so`: **REMOVED** from both ABIs.
- Required native libraries: **KEPT**, six per ABI.
- Java loader / CMake / ELF / AAR dependency alignment: **PASS**.
- AAR size saved: **2,969,333 bytes (2.831777 MiB, 14.446383%)**.
- ABI consistency: **PASS**.
- Build, host tests, and connected arm64 load/create/release: **PASS**.
- Functional and public API invariants: **NO CHANGE**.
- PH2 freeze: **YES**.

# PH3 Readiness

PH3 readiness: **READY**. PH3 may address library Gradle and consumer-rule
hardening only under a separate explicit task. PH2 does not begin PH3.
