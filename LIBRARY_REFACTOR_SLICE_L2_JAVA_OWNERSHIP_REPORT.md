# Library Refactor — Slice L2 — Java Ownership + Dependency Inversion

Date: 2026-08-18
Branch: dev (87813c6 + L2)
Scope: 将 Player Java 实现搬至 Library，建立 `app → ffmpegplayer` 单向依赖，删除反向 sourceSets，清理 app 重复 Native 构建。

## Scope
搬 `FFmpegNative.java` / `LiveAudioPcmSink.java` 从 `app` 到 `ffmpegplayer`，建立 `app → ffmpegplayer`，删除 `ffmpegplayer → ../app/src` 反向依赖与 `app` 重复 Native 构建。不改 package/JNI/行为。

## L1 Baseline
L1（87813c6）已将 `CMakeLists.txt`、`native-ffmpeg-jni.cpp`、`native/*` 20 文件、`ffmpeg/include`、`jniLibs` 迁移至 `ffmpegplayer/src/main/{cpp,jniLibs}`，`ffmpegplayer/build.gradle` 已指向 `src/main/cpp/CMakeLists.txt`，`app/build.gradle` 过渡指向 `../ffmpegplayer`。三项构建 PASS，AAR 含 `libnative-ffmpeg.so` + 7 FFmpeg .so。

## Java Files Moved
使用 `git mv` 保留历史（R 状态）：
- `app/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java` → `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java`
- `app/src/main/java/com/example/motro/ffmpeg/LiveAudioPcmSink.java` → `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/LiveAudioPcmSink.java`

## Java Files Kept In App
- `app/src/main/java/com/example/motro/MediaPlayerActivity.java`（Demo）
- Demo UI 资源（`layout/drawable/strings/themes` 等）未动。

## Package / JNI Contract
- **Package 冻结**：`package com.example.motro.ffmpeg;`（两文件首行未变）
- **JNI 未变**：
  - `native-ffmpeg-jni.cpp:1176 FindClass "com/example/motro/ffmpeg/FFmpegNative"`, `1249 OesFrameListener`
  - `NativePlayer.cpp:1172 onAudioPcm`, `1173 onAudioControl`, `1174 getPlaybackHeadFrames`, `4004 onPlayerEvent`
  - `package` 物理移动 ≠ 重命名；`import com.example.motro.ffmpeg.*` 在 `MediaPlayerActivity` 中继续有效。

## Dependency Inversion
**app/build.gradle**
- `dependencies { implementation project(':ffmpegplayer') }` 新增（`102-103`）。
- `settings.gradle:34-36` 已含 `include ':ffmpegplayer'`，无需修改。

**ffmpegplayer/build.gradle**
- 删除反向 `sourceSets { java { srcDirs = ['../app/src/main/java'] exclude ... } }`（4 个 exclude 含 MediaPlayerActivity 等 Demo 类）。
- 保留 `externalNativeBuild cmake path file('src/main/cpp/CMakeLists.txt')`（L1 已正确）。

最终：`MediaPlayerActivity → ffmpegplayer Java → JNI → NativePlayer`，禁止 `ffmpegplayer → app`。

## Reverse Dependency Removal
- `grep -r "../app"` 在 `ffmpegplayer/build.gradle` 中 `NONE`（`Select-String` 验证）。
- `ffmpegplayer` 现仅编译 `ffmpegplayer/src/main/java`（默认 `src/main/java`），`app` Java 不再被借用。

## Native Build Ownership
- **Before L2**：`app` 与 `ffmpegplayer` 均有 `externalNativeBuild`（过渡重复编译）。
- **After L2**：仅 `ffmpegplayer` 拥有 `externalNativeBuild`（`src/main/cpp/CMakeLists.txt`）；`app/build.gradle` 已删除 `sourceSets.jniLibs` 与 `externalNativeBuild`。`app` 通过 `implementation project(':ffmpegplayer')` 的 `mergeDebugNativeLibs` / `mergeDebugJniLibFolders` 获得 `libnative-ffmpeg.so` 与 FFmpeg .so，无重复编译。

## LiveAudioPcmSink Coupling Audit
- 审计 `LiveAudioPcmSink.java`：`import android.media.AudioTrack/AudioManager/AudioFormat` 与 `java.nio.ByteBuffer`，无 `Activity`/`View`/`R.*`/`ViewBinding` 依赖；`onAudioPcm`/`onAudioControl`/`getPlaybackHeadFrames` 均不持有 Activity 实例。
- 线程/生命周期语义（A0-A8）保持：`AudioTrack` 创建于 worker 首写，`WRITE_NON_BLOCKING` + epoch 取消，`onAudioControl` 生命周期由 `NativePlayer` 驱动。

## Duplicate Class / Native Audit
- **Java**：`Test-Path app/.../FFmpegNative.java = False`, `ffmpegplayer/... = True`；`LiveAudioPcmSink` 同。仅一份 production source，无 `duplicate class`。
- **Native**：`app/src/main/cpp/native/NativePlayer.cpp = False`, `ffmpegplayer/... = True`；`app/src/main/jniLibs` 不存在；`ffmpegplayer/src/main/jniLibs/{arm64-v8a,armeabi-v7a}` 各 7 个 .so。仅 `ffmpegplayer` 产出 `libnative-ffmpeg.so`。

## AAR Java Contents
`:ffmpegplayer:assembleRelease` 生成 `ffmpegplayer-release.aar`，`classes.jar` 含：
- `com/example/motro/ffmpeg/FFmpegNative.class`
- `com/example/motro/ffmpeg/LiveAudioPcmSink.class`
- `FFmpegNative$OesFrameListener`, `FFmpegNative$PlayerEventListener` nested classes
- Native：`jni/{arm64-v8a,armeabi-v7a}/libnative-ffmpeg.so` + 7 FFmpeg .so（AAR `jni/` 验证通过）

## Build
- `git diff --check`：`CHECK PASS`
- `:ffmpegplayer:assembleDebug`：`BUILD SUCCESSFUL in 7s`（`compileDebugJavaWithJavac` 来自 `ffmpegplayer/src/main/java`）
- `:ffmpegplayer:assembleRelease`：`BUILD SUCCESSFUL in 10s`
- `:app:assembleDebug`：`BUILD SUCCESSFUL in 8s`（`ffmpegplayer:configureCMakeDebug`/`buildCMakeDebug` 各 ABI 通过 `app:mergeDebugNativeLibs` 合并，无 `app:configureCMakeDebug` 重复任务）
- 三项均 PASS；Java compile / JNI link / AAR packaging 均通过。

## Runtime Smoke
`NOT_EXECUTED`（无 adb/RTSP 环境；未伪造）

## Remaining Issues
- `app/src/main/cpp/native-lib.cpp` 仍在 `app`（L3 清理）。
- `:app:testDebugUnitTest` 的 KAPT/JUnit stub 历史问题仍存在（与本次无关）。

## Slice L2 Freeze
**YES** — Java 已迁移、package/JNI 未变、`app → ffmpegplayer` 成立、反向依赖已删、app 重复 Native 构建已清理、无 duplicate、无行为修改、三项构建 PASS。

---
**Answers:**
1. FFmpegNative 当前属于哪个 module？ **ffmpegplayer/src/main/java/com/example/motro/ffmpeg/**
2. LiveAudioPcmSink 当前属于哪个 module？ **ffmpegplayer/src/main/java/com/example/motro/ffmpeg/**
3. MediaPlayerActivity 当前属于哪个 module？ **app/src/main/java/com/example/motro/**
4. package 是否改变？ **NO**
5. JNI signatures 是否改变？ **NO**
6. app 是否通过 project(':ffmpegplayer') 依赖 Library？ **YES**
7. ffmpegplayer 是否仍引用 ../app/src？ **NO**
8. app 是否仍自己构建 Player Native target？ **NO**
9. 是否存在 duplicate FFmpegNative class？ **NO**
10. 是否存在 duplicate native .so packaging？ **NO**
11. AAR 是否包含 FFmpegNative？ **YES**
12. AAR 是否包含 native-ffmpeg.so？ **YES**
13. Player behavior 是否改变？ **NO**
