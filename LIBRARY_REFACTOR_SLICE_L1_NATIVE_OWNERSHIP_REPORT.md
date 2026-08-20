# Library Refactor — Slice L1 — Native Ownership Move

Date: 2026-08-18
Branch: dev (cc5c684 + L1)
Scope: 仅搬 Native 实现的物理归属，不搬 Java，不改 package/JNI/行为。

## Scope
将播放器全部 Native 实现的物理 ownership 从 `app/src/main/` 迁移至 `ffmpegplayer/src/main/`。包括 C++、CMakeLists.txt、FFmpeg headers、FFmpeg runtime .so。不搬 `FFmpegNative.java`/`LiveAudioPcmSink.java`/`MediaPlayerActivity.java`，不改 package 与 JNI。

## L0 Baseline
`LIBRARY_REFACTOR_SLICE_L0_BASELINE_REPORT.md` (cc5c684) 已确认 `TEMPORARY_REVERSE_SOURCE_DEPENDENCY`：`ffmpegplayer` 通过 `../app/src/main/{java,cpp,jniLibs}` 借用 `app` 的 Java/Native/FFmpeg 实现。CMake 列 10 文件未含 `native-lib.cpp`（LEGACY）。

## Native Files Moved
以 `app/src/main/cpp/CMakeLists.txt:13-24` 的 `add_library(native-ffmpeg ...)` 为准，使用 `git mv` 保留历史（179 个文件，R 状态）：
- `app/src/main/cpp/CMakeLists.txt` → `ffmpegplayer/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/native-ffmpeg-jni.cpp` → `ffmpegplayer/src/main/cpp/native-ffmpeg-jni.cpp`
- `app/src/main/cpp/native/` 20 文件 → `ffmpegplayer/src/main/cpp/native/`（`NativePlayer.*`, `PlayerOptions.*`, `VideoRenderer.*`, `NativeNv12GlRenderer.*`, `NativeYuvGlRenderer.*`, `NativeOesRenderer.*`, `PlayerRemuxRecorder.*`, `SnapshotManager.*`, `ThermalConfig.*`, `ThermalPaletteLut.*`）
- `app/src/main/cpp/ffmpeg/` 整体 → `ffmpegplayer/src/main/cpp/ffmpeg/`（`include/libavcodec` 等 7 个 lib 目录，含 100+ 头文件）
- `app/src/main/jniLibs/` 整体 → `ffmpegplayer/src/main/jniLibs/`（`arm64-v8a`/`armeabi-v7a` 各 7 个 .so）

## Files Intentionally Not Moved
- `app/src/main/cpp/native-lib.cpp`（L0 已判 `LEGACY_UNUSED_CANDIDATE`）：独立 `JNI_OnLoad` + `Java_com_example_ffmpegdemo_FFmpegNative_*`（旧包 `ffmpegdemo`），CMake 未引用，Java 无调用，仅 `native-ffmpeg-jni.cpp` 注册 `com.example.motro.ffmpeg.FFmpegNative`。保留至 L3 清理。
- Java：`FFmpegNative.java`, `LiveAudioPcmSink.java`, `MediaPlayerActivity.java` 均未搬（L1 约束）。
- Demo UI 资源未动。

## CMake Ownership
- **迁移后**：`ffmpegplayer/src/main/cpp/CMakeLists.txt`（`project native_ffmpeg`, `CMAKE_CXX_STANDARD 17`, `FFMPEG_INCLUDE_DIR=${CMAKE_SOURCE_DIR}/ffmpeg/include`, `FFMPEG_LIB_DIR=${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}`）。
- 相对结构保持：`ffmpegplayer/src/main/cpp/ffmpeg/include` 与 `../jniLibs/${ANDROID_ABI}` 继续成立，无需重写 CMake 架构。
- 仅最小路径修改：`ffmpegplayer/build.gradle` 由 `path file('../app/src/main/cpp/CMakeLists.txt')` → `path file('src/main/cpp/CMakeLists.txt')`。

## FFmpeg Headers Ownership
- **Before**：`app/src/main/cpp/ffmpeg/include/`
- **After**：`ffmpegplayer/src/main/cpp/ffmpeg/include/`（7 个 lib 目录）
- 通过 `git mv` 搬迁，无复制残留。

## jniLibs Ownership
- **Before**：`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` 各 7 个 .so（`libavcodec.so 12.9MB` 等）
- **After**：`ffmpegplayer/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` 各 7 个 .so
- `app/src/main/jniLibs` 已不存在（`Test-Path False`），无 duplicate。

## ABI
`app/build.gradle:30-32` 与 `ffmpegplayer/build.gradle:15-17` 均为 `abiFilters "armeabi-v7a", "arm64-v8a"`，未新增。

## Gradle Native Configuration
**ffmpegplayer/build.gradle**
- `jniLibs.srcDirs = ['../app/src/main/jniLibs']` → `['src/main/jniLibs']`
- `cmake path file('../app/src/main/cpp/CMakeLists.txt')` → `file('src/main/cpp/CMakeLists.txt')`

**app/build.gradle**（L1 过渡，保證 Build PASS 且避免长期重复编译在 L2/L3 清理）
- `jniLibs.srcDirs = ['src/main/jniLibs']` → `['../ffmpegplayer/src/main/jniLibs']`
- `cmake path file('src/main/cpp/CMakeLists.txt')` → `file('../ffmpegplayer/src/main/cpp/CMakeLists.txt')`

此过渡使 `app` 的 `externalNativeBuild` 与 `jniLibs` 指向新位置，`app:assembleDebug` 仍可产出 `libnative-ffmpeg.so`；`ffmpegplayer` 亦独立产出同名 .so。L1 允许此短暂重复编译，L2/L3 将彻底清理 `app` 的 native config。

## JNI Contract
**未改变。**
- `native-ffmpeg-jni.cpp:1176 FindClass "com/example/motro/ffmpeg/FFmpegNative"`, `1249 OesFrameListener`
- `NativePlayer.cpp:1172 onAudioPcm`, `1173 onAudioControl`, `1174 getPlaybackHeadFrames`, `4004 onPlayerEvent`
- `NativeOesRenderer.cpp` 的 `SurfaceTexture` 相关 FindClass/GetMethodID 保持不变
- `Java_com_example_ffmpegdemo_*` 仅在 `native-lib.cpp`（未参与 build）
- `package com.example.motro.ffmpeg` 冻结，物理移动 ≠ package 重命名。

## Duplicate Packaging Audit
- **Ownership**：`app/src/main/cpp` 现仅 `native-lib.cpp`；`ffmpegplayer/src/main/cpp` 持有全部 Player Native；`app/src/main/jniLibs` 已不存在。
- **Packaging**：`app:assembleDebug` 与 `ffmpegplayer:assembleDebug/Release` 各自产出 `libnative-ffmpeg.so`，但分属不同产物（APK vs AAR），同一 APK/AAR 内无重复 `libnative-ffmpeg.so`。`git status` 无 `app/src/main/cpp/native` 残留，`git diff --cached` 显示 179 个 R（重命名）+ 2 个 build.gradle M，无复制残留。
- **结论**：`NONE`（无同一 artifact 内 duplicate .so 冲突；L1 重复编译为过渡态）

## AAR Native Contents
`:ffmpegplayer:assembleRelease` 生成 `ffmpegplayer-release.aar (20.4MB)` / `ffmpegplayer-debug.aar (20.5MB)`：
```
jni/arm64-v8a/libavcodec.so, libavdevice.so, libavfilter.so, libavformat.so, libavutil.so, libnative-ffmpeg.so, libswresample.so, libswscale.so
jni/armeabi-v7a/ 同上 8 个
```
`libnative-ffmpeg.so` 与 7 个 FFmpeg runtime libs 均在正确 ABI 目录。

## Build
- `git diff --check`：`CHECK PASS`
- `:ffmpegplayer:assembleDebug`：`BUILD SUCCESSFUL in 12s`（28 tasks, 24 executed）
- `:ffmpegplayer:assembleRelease`：`BUILD SUCCESSFUL in 13s`（31 tasks, 27 executed）
- `:app:assembleDebug`：`BUILD SUCCESSFUL in 20s`（44 tasks, 44 executed；含 `configureCMakeDebug`/`buildCMakeDebug` 各 ABI，`mergeDebugNativeLibs` 无 duplicate 告警）
- 三项均 PASS；CMake configure / native-ffmpeg compile / FFmpeg link 均通过。

## Runtime Smoke
`NOT_EXECUTED`（无 adb/RTSP 环境；未伪造）

## Remaining Issues
- `app/build.gradle` 仍保留过渡 `externalNativeBuild` 指向 `../ffmpegplayer`（L1 允许，L2/L3 清理为 `app → ffmpegplayer` 单一 native 产出）。
- `native-lib.cpp` 仍在 `app/src/main/cpp`（L3 删除）。
- Java 仍在 `app`（L2 搬迁后形成 `app → ffmpegplayer`）。

## Slice L1 Freeze
**YES** — Native 已迁移、CMake/Headers/jniLibs 已归属 ffmpegplayer、app 无残留 Player Native、无 duplicate packaging、JNI 未变、Java 未搬、行为未改、三项构建 PASS。

---
**Answers:**
1. Native implementation 当前是否全部属于 ffmpegplayer？ **YES**
2. CMakeLists.txt 当前属于哪个 module？ **ffmpegplayer/src/main/cpp/CMakeLists.txt**
3. FFmpeg headers 当前属于哪个 module？ **ffmpegplayer/src/main/cpp/ffmpeg/include**
4. FFmpeg runtime .so 当前属于哪个 module？ **ffmpegplayer/src/main/jniLibs**
5. app 是否仍保留 Player Native source？ **NO**（仅 legacy `native-lib.cpp`）
6. Java implementation 是否已搬？ **NO**
7. JNI package/signature 是否改变？ **NO**
8. native library name 是否改变？ **NO**（`libnative-ffmpeg.so`）
9. 是否出现 duplicate native packaging？ **NONE**
10. native-lib.cpp 是否搬迁？ **NO**（legacy candidate，按计划留 L3）
