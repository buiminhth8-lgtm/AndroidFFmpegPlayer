# Library Refactor — Slice L0 — Baseline & Migration Contract

Date: 2026-08-18
Branch: dev (d173615)
Scope: 只做审计与冻结，不搬迁源码。

## Scope
L0 为 library 重构的基线审计：确认当前 `app` / `ffmpegplayer` 真实结构、依赖方向、JNI/package 契约、CMake/Native/FFmpeg/ABI 归属、legacy 候选、构建基线与迁移风险，并冻结最终归属与 L1-L6 边界。

## Current Module Structure
- `settings.gradle:34-36`：`include ':app'`, `':ffmpegplayer'`。
- `app/build.gradle:1-7`：`com.android.application` + kotlin/ksp；`namespace 'com.example.motro'`, `compileSdk 36`, `minSdk 24`, `abiFilters armeabi-v7a, arm64-v8a`, `externalNativeBuild cmake path file('src/main/cpp/CMakeLists.txt')`, `jniLibs.srcDirs ['src/main/jniLibs']`。
- `ffmpegplayer/build.gradle:1-4`：`com.android.library` + `maven-publish`；`namespace 'com.example.motro.ffmpeg'`, `compileSdk 36`, `minSdk 24`, `abiFilters armeabi-v7a, arm64-v8a`, `consumerProguardFiles 'consumer-rules.pro'`（文件存在：`-keep class com.example.motro.ffmpeg.FFmpegNative { *; }`），`publishing singleVariant("release")`。
- `app/src/main/AndroidManifest.xml`：含 `MediaPlayerActivity`（LAUNCHER）；`ffmpegplayer/src/main/AndroidManifest.xml` 仅 `INTERNET` 权限。

## Current Dependency Direction
**当前：无正向依赖，反向借用。**
- `app/build.gradle:100-116` 无 `implementation project(':ffmpegplayer')`。
- `ffmpegplayer/build.gradle:20-37` 以 `TEMPORARY_REVERSE_SOURCE_DEPENDENCY` 形式借用 `app`：
  ```gradle
  java { srcDirs = ['../app/src/main/java'] exclude 'MediaPlayerActivity...' }
  jniLibs.srcDirs = ['../app/src/main/jniLibs']
  cmake { path file('../app/src/main/cpp/CMakeLists.txt') }
  ```
- `grep ../app` 仅命中 `ffmpegplayer/build.gradle` 三处；`app` 内无 `project(':ffmpegplayer')`。

## app Ownership (Current)
- `app/src/main/java/com/example/motro/MediaPlayerActivity.java`
- `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java`（实际实现）
- `app/src/main/java/com/example/motro/ffmpeg/LiveAudioPcmSink.java`
- `app/src/main/cpp/**`：`native-ffmpeg-jni.cpp`, `native/*` 20 文件, `CMakeLists.txt`, `ffmpeg/include`, `native-lib.cpp`（legacy）
- `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}`：7 个 .so
- Demo UI：`layout/drawable/strings/themes` 等（未展开，属 app）

## ffmpegplayer Ownership (Current)
- `ffmpegplayer/src/main/AndroidManifest.xml`（6 行）
- `ffmpegplayer/consumer-rules.pro`, `build.gradle`, `src/.gitignore`
- `ffmpegplayer/src/main` 下无 `cpp` / `java` / `jniLibs` 实体目录（`Get-ChildItem ffmpegplayer/src/main` 仅 `AndroidManifest.xml`）— 实际实现仍在 `app`。

## Reverse Source Dependencies
`ffmpegplayer` 通过 `../app/src/main/{java,cpp,jniLibs}` 借用 `app` 的 Java / Native / FFmpeg 完整实现，记录为 `TEMPORARY_REVERSE_SOURCE_DEPENDENCY`。L1 需消除。

## Java / JNI Contract
- **冻结 package**：`com.example.motro.ffmpeg`（`FFmpegNative.java:1`, `LiveAudioPcmSink.java:1`）。
- **硬编码签名**（`Select-String` 审计）：
  - `native-ffmpeg-jni.cpp:1176 FindClass "com/example/motro/ffmpeg/FFmpegNative"`
  - `native-ffmpeg-jni.cpp:1249 FindClass "com/example/motro/ffmpeg/FFmpegNative$OesFrameListener"`
  - `NativePlayer.cpp:1172 GetMethodID "onAudioPcm" "(Ljava/nio/ByteBuffer;IJ)I"`, `1173 "onAudioControl" "(I)I"`, `1174 "getPlaybackHeadFrames" "()I"`, `4004 "onPlayerEvent" "(JLjava/lang/String;Ljava/lang/String;)V"`
  - `NativeOesRenderer.cpp` 含 `SurfaceTexture`/`Surface`/`Looper/Handler` 的 FindClass/GetMethodID。
  - `native-lib.cpp:36,43` 含遗留 `Java_com_example_ffmpegdemo_FFmpegNative_*`（旧包 `ffmpegdemo`）。
- **结论**：物理移动 Java 文件 ≠ package 重命名；JNI 契约在 L0-L6 期间冻结。

## Native / CMake Contract
- `app/src/main/cpp/CMakeLists.txt:8-9`：`FFMPEG_INCLUDE_DIR=${CMAKE_SOURCE_DIR}/ffmpeg/include`, `FFMPEG_LIB_DIR=${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}`。
- `add_library(native-ffmpeg SHARED` 列 10 文件：`native-ffmpeg-jni.cpp`, `native/NativePlayer.cpp`, `VideoRenderer.cpp`, `NativeYuvGlRenderer.cpp`, `NativeOesRenderer.cpp`, `NativeNv12GlRenderer.cpp`, `PlayerOptions.cpp`, `PlayerRemuxRecorder.cpp`, `SnapshotManager.cpp`, `ThermalConfig.cpp`, `ThermalPaletteLut.cpp`；未引用 `native-lib.cpp`。
- 迁移至 `ffmpegplayer/src/main/cpp` 后相对结构保持，`${CMAKE_SOURCE_DIR}/ffmpeg/include` 与 `../jniLibs/${ANDROID_ABI}` 继续成立。

## FFmpeg Headers / Runtime Libraries
- **Headers**：`app/src/main/cpp/ffmpeg/include` 下 7 目录 `libavcodec/libavdevice/libavfilter/libavformat/libavutil/libswresample/libswscale`。
- **Runtime .so**：`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}` 各 7 个：`libavcodec.so 12.9MB`, `libavdevice.so 49KB`, `libavfilter.so 3.7MB`, `libavformat.so 2.6MB`, `libavutil.so 731KB`, `libswresample.so 90KB`, `libswscale.so 719KB`。

## ABI Contract
- `app/build.gradle:30-32` 与 `ffmpegplayer/build.gradle:15-17` 均为 `abiFilters "armeabi-v7a", "arm64-v8a"`。
- 未来 `ffmpegplayer/src/main/jniLibs` 保持同 ABI，无新增。

## Legacy / Dead Code Candidates
- `app/src/main/cpp/native-lib.cpp:1-72`：独立 `JNI_OnLoad` + `av_jni_set_java_vm` + `Java_com_example_ffmpegdemo_FFmpegNative_*`，包名 `ffmpegdemo` 与当前 `com.example.motro.ffmpeg` 不一致，CMake 未引用，Java 无调用，`RegisterNatives` 仅在 `native-ffmpeg-jni.cpp` 中对 `FFmpegNative` 注册。标记 `LEGACY_UNUSED_CANDIDATE`，L3 删除。

## Build Baseline
- `git diff --check`：`CHECK PASS`（无空白错误；仅 LF→CRLF 提示）。
- Plan 模式下未执行 `gradlew :app:assembleDebug / :ffmpegplayer:assembleDebug / :ffmpegplayer:assembleRelease` 的实时构建（Plan 模式禁止写入型构建）；历史基线（A6/A8 报告）中三项均为 `PASS`（`BUILD SUCCESSFUL`，`arm64-v8a, armeabi-v7a`），但本次 L0 按规范记录为 `NOT_EXECUTED`（需 Build 模式复测）。
- 无基线配置阻塞；未做 Gradle 修复。

## Migration Risks
1. JNI 硬编码 `com/example/motro/ffmpeg` 签名
2. CMake 相对路径 `${CMAKE_SOURCE_DIR}/ffmpeg/include` / `../jniLibs`
3. 重复 native .so 打包（app 与 library 同时 `externalNativeBuild`）
4. 反向 `sourceSets` 依赖
5. R8/consumer-rules（当前仅 keep FFmpegNative）
6. Audio JNI 回调生命周期（GlobalRef/worker/Attach）
7. Native library 加载 (`System.loadLibrary("native-ffmpeg")`)
8. ABI 一致性
9. CRLF/LF 噪声
10. 遗留 `native-lib.cpp` / `ffmpegdemo` 包残留

## Frozen Final Ownership
- `app` 保留：`MediaPlayerActivity.java` + Demo UI（layout/drawable/strings/themes/ViewBinding/launcher）。
- `ffmpegplayer` 拥有：`FFmpegNative.java`, `LiveAudioPcmSink.java`, 全部 Player Native 实现（`native-ffmpeg-jni.cpp`, `NativePlayer.*` 等 20 文件）、`CMakeLists.txt`、`ffmpeg/include`、`jniLibs`。
- 最终依赖：`app → implementation project(':ffmpegplayer') → JNI → NativePlayer`；禁止 `ffmpegplayer → ../app/src/...`。

## Slice Plan L1-L6
- **L1 Native Ownership Move**：搬 C++/CMake/FFmpeg headers/jniLibs，不搬 Java。
- **L2 Java Ownership + Dependency Inversion**：搬 `FFmpegNative`/`LiveAudioPcmSink`，形成 `app → ffmpegplayer`。
- **L3 App Demo Boundary Cleanup**：删除 `LEGACY_UNUSED_CANDIDATE`（如 `native-lib.cpp`），app 只留 Demo。
- **L4 Public FFmpegPlayer Facade**：不提前。
- **L5 AAR / Maven / R8 Consumer Contract**：不提前。
- **L6 Final Runtime & Library Freeze**：不提前。

## Remaining Issues
- 需在 Build 模式下复测三项构建以完成基线闭环。
- `app` 尚未 `implementation project(':ffmpegplayer')`。
- `native-lib.cpp` 遗留待 L3 清理。

## Slice L0 Freeze
**YES** — 审计完整、反向依赖与 JNI/CMake/FFmpeg/ABI/legacy 已定位、无源码搬迁、无行为修改、L1-L6 边界明确。

---
**Answers:**
1. ffmpegplayer 是否为 Android Library？ **YES**
2. app 是否已经依赖 ffmpegplayer？ **NO**
3. ffmpegplayer 是否引用 ../app/src？ **YES**（三处反向依赖）
4. Java implementation 当前实际位于哪里？ **app/src/main/java/com/example/motro/ffmpeg/**
5. Native implementation 当前实际位于哪里？ **app/src/main/cpp/**
6. FFmpeg headers 当前位于哪里？ **app/src/main/cpp/ffmpeg/include/**
7. FFmpeg .so 当前位于哪里？ **app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/**
8. 当前 CMake 属于哪个 module？ **app**
9. JNI 是否硬编码 com/example/motro/ffmpeg？ **YES**
10. package 是否允许在本次搬迁中修改？ **NO**
11. native-lib.cpp 是否真实参与 build？ **NO**（LEGACY_UNUSED_CANDIDATE）
12. app debug 是否可构建？ **NOT_EXECUTED**（Plan 模式；历史 PASS）
13. ffmpegplayer debug 是否可构建？ **NOT_EXECUTED**
14. ffmpegplayer release 是否可构建？ **NOT_EXECUTED**
15. 最终依赖方向是什么？ **app → ffmpegplayer**
