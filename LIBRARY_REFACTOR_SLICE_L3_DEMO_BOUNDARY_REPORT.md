# Library Refactor — Slice L3 — App Demo Boundary Cleanup

Date: 2026-08-18
Branch: dev (4bb515e + L3)
Scope: 收缩 app 为纯 Demo，清理遗留 Native 代码与空目录，保持 JNI/行为不变。

## Scope
清理 `app` 中迁移后残留的 Player 遗留代码与 Native 构建配置，使 `app` 仅保留 `MediaPlayerActivity` + Demo UI，`ffmpegplayer` 独占全部 Player 实现。

## L2 Baseline
L2（4bb515e）已将 `FFmpegNative.java`/`LiveAudioPcmSink.java` 搬至 `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/`，建立 `app → implementation project(':ffmpegplayer')`，删除 `ffmpegplayer` 反向 `sourceSets` 与 `app` 重复 Native 构建，三项构建 PASS，AAR 含 Java + `libnative-ffmpeg.so` + 7 FFmpeg .so。

## app Production Sources
`Get-ChildItem app/src/main/java -Recurse -File` 仅：
- `app/src/main/java/com/example/motro/MediaPlayerActivity.java`

L2 之前 `ffmpegplayer` 旧 `sourceSets` 曾排除的 `MediacodecPlayerActivity.java` / `ExoplayerPlayerActivity.java` / `PlayerLauncherActivity.java` 在当前 `app` 中已不存在（历史已清理），`app` 无其他生产 Java/Kotlin 文件。结论：Demo 主代码仅 `MediaPlayerActivity.java`，无需额外迁移或删除。

## Removed Legacy Sources
- `app/src/main/cpp/native-lib.cpp`（72 行）已 `git rm` 删除。删除后 `app/src/main/cpp` 目录为空且已不存在（`Test-Path app/src/main/cpp = False`），`app/src/main` 现仅 `java/`, `res/`, `AndroidManifest.xml`, `keepRules`。

## native-lib.cpp Audit
- **CMake 引用**：`app/src/main/cpp/CMakeLists.txt:13-24`（现 `ffmpegplayer/...`）的 `add_library(native-ffmpeg ...)` 未列 `native-lib.cpp`。
- **Java JNI 调用**：`grep Java_com_` 仅 `native-lib.cpp:36,43` 的 `Java_com_example_ffmpegdemo_FFmpegNative_*`（旧包 `ffmpegdemo`），当前 `com.example.motro.ffmpeg.FFmpegNative` 无此方法。
- **RegisterNatives / JNI_OnLoad**：`native-lib.cpp:19 JNI_OnLoad` 独立，与 `native-ffmpeg-jni.cpp` 的 `JNI_OnLoad` 不共享；`ffmpegplayer` 的 `native-ffmpeg` 通过 `native-ffmpeg-jni.cpp` 注册 `FFmpegNative`。
- **Build target**：无 `add_library`/`target_sources` 引用。
- **结论**：`LEGACY_UNUSED`（L0 已判），删除安全，未搬入 `ffmpegplayer`。

## app Native Configuration Cleanup
- **Before L3**：`app/build.gradle` 已在 L2 删除 `sourceSets.jniLibs` 与 `externalNativeBuild`（指向 `../ffmpegplayer/...` 的过渡配置）。
- **L3**：仅删除 `native-lib.cpp` 遗留文件；`app/src/main/jniLibs` 已在 L1 移走（`Test-Path False`），无需再删。
- **Result**：`app` 无 `src/main/cpp`、`src/main/jniLibs`、`externalNativeBuild`、`jniLibs.srcDirs` 的 Player Native 配置。

## Dependency Cleanup
- `app/build.gradle:87-104` 保留 `androidx.*`, `material`, `constraintlayout`, `navigation`, `viewpager2`, `coroutines`, `utilcode` 等 Demo UI 依赖；均被 `MediaPlayerActivity` 直接使用。
- `ffmpegplayer/build.gradle` 无 `dependencies` 块（Player 仅依赖 Android SDK 的 `AudioTrack`/`MediaCodec` 等）。
- 无明确仅由迁移前 Player 使用且 Demo 不再需要的独立依赖，故无版本升级或重排，仅保持 `implementation project(':ffmpegplayer')`。

## ABI Ownership
- **Library supported ABI**：`ffmpegplayer/build.gradle:15-17` `abiFilters "armeabi-v7a", "arm64-v8a"`。
- **Demo APK ABI policy**：`app/build.gradle:30-32` 保留 `abiFilters "armeabi-v7a", "arm64-v8a"`（用于限制最终 APK，非旧 app-native build；L2 已删 `externalNativeBuild`，此 ABI 仅为 APK 打包策略）。
- 未新增 ABI。

## Manifest Boundary
- `app/src/main/AndroidManifest.xml:18` 含 `.MediaPlayerActivity`（`LAUNCHER`）。
- `ffmpegplayer/src/main/AndroidManifest.xml` 仅 `INTERNET` 权限，无 Activity 声明。
- Merge 后 Demo 正常启动，无重复/错误声明。

## Reverse Dependency Audit
- `Select-String` 审计 `ffmpegplayer/src/main/java/**/*.java` 对 `../app`, `MediaPlayerActivity`, `ActivityMediaPlayerBinding`, `com.example.motro.R` 均 `NONE`。
- `grep ../app` 在 `ffmpegplayer/build.gradle` 中 `NONE`（L2 已删）。

## Duplicate Ownership Audit
- `FFmpegNative.java`：`app/... = False`, `ffmpegplayer/... = True`
- `LiveAudioPcmSink.java`：同上
- `NativePlayer.cpp`：`app/... = False`, `ffmpegplayer/... = True`
- `CMakeLists.txt`：仅 `ffmpegplayer/src/main/cpp/CMakeLists.txt`
- `FFmpeg headers` / `jniLibs`：仅 `ffmpegplayer`
- **结论**：无 duplicate Java/native ownership。

## APK / AAR Packaging
- **AAR**（`ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar` 20.4MB）：`jni/{arm64-v8a,armeabi-v7a}/` 各 8 个（`libnative-ffmpeg.so` + 7 FFmpeg .so），`classes.jar` 含 `FFmpegNative`/`LiveAudioPcmSink`。
- **APK**（`app:assembleDebug`）：`libnative-ffmpeg.so` 与 FFmpeg .so 来自 `implementation project(':ffmpegplayer')` 的 `mergeDebugNativeLibs`（`ffmpegplayer:configureCMakeDebug`/`buildCMakeDebug` 各 ABI，非 `app:configureCMakeDebug`），无 `app/src/main/jniLibs` 重复条目。

## Build
- `git diff --check`：`CHECK PASS`
- `:ffmpegplayer:assembleDebug`：`BUILD SUCCESSFUL`（`compileDebugJavaWithJavac` 来自 `ffmpegplayer/src/main/java`，`UP-TO-DATE` 表明增量正确）
- `:ffmpegplayer:assembleRelease`：`BUILD SUCCESSFUL`
- `:app:assembleDebug`：`BUILD SUCCESSFUL`（`ffmpegplayer:configureCMakeDebug`/`buildCMakeDebug` 各 ABI 通过 `app:mergeDebugNativeLibs` 合并，无 `app:configureCMakeDebug` 重复任务）
- 三项均 PASS；Java compile / JNI link / AAR packaging / Manifest merge 均通过；无 duplicate class/.so。

## Runtime Smoke
`NOT_EXECUTED`（无 adb/RTSP 环境；未伪造）

## Final Demo Boundary
```
app/
├── build.gradle（仅 application + implementation project(':ffmpegplayer')，无 Player Native 构建）
└── src/main/
    ├── AndroidManifest.xml（LAUNCHER）
    ├── java/.../MediaPlayerActivity.java
    └── res/...

ffmpegplayer/
├── build.gradle（library + externalNativeBuild + consumerProguard）
└── src/main/
    ├── java/.../ffmpeg/FFmpegNative.java, LiveAudioPcmSink.java
    ├── cpp/（CMakeLists.txt, native-ffmpeg-jni.cpp, native/*, ffmpeg/include）
    └── jniLibs/{arm64-v8a,armeabi-v7a}/（7 FFmpeg .so）
```

## Remaining Issues
- `:app:testDebugUnitTest` 的 KAPT/JUnit stub 历史问题仍存在（与本次无关）。
- 真机 Runtime 验证待 L6 统一执行。

## Slice L3 Freeze
**YES** — `MediaPlayerActivity` 留 app、Player Java/Native 归 library、`app` 无 Player C++/FFmpeg/jniLibs、`app` 不再构建 Player native、legacy 已清理、无 reverse/duplicate、package/JNI 未变、三项构建 PASS、行为未改。

---
**Answers:**
1. MediaPlayerActivity 是否仍属于 app？ **YES**
2. FFmpegNative 是否属于 ffmpegplayer？ **YES**
3. LiveAudioPcmSink 是否属于 ffmpegplayer？ **YES**
4. Player Native implementation 是否全部属于 ffmpegplayer？ **YES**
5. app 是否仍有有效 Player C++ source？ **NO**
6. app 是否仍有 Player jniLibs？ **NO**
7. app 是否仍 externalNativeBuild Player？ **NO**
8. native-lib.cpp 最终状态？ **REMOVED_UNUSED**
9. ffmpegplayer 是否引用 app source？ **NO**
10. 是否存在 duplicate Java/native ownership？ **NO**
11. app 是否只通过 ffmpegplayer 使用 Player implementation？ **YES**
12. package/JNI 是否改变？ **NO**
13. Player behavior 是否改变？ **NO**
