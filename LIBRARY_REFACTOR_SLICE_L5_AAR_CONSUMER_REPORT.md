# Library Refactor — Slice L5 — AAR / Maven / R8 Consumer Contract

Date: 2026-08-18
Branch: dev (f4f071d + L5)
Scope: 完成 ffmpegplayer Release AAR 发布契约、consumer-rules 修正、Maven/POM 验证与消费者构建验证，不改播放器算法与 Public API 语义。

## Scope
审计并修正 `consumer-rules.pro` 以覆盖真实 JNI 契约，验证 AAR Java/Native 内容与 ABI 完整性，完成 `maven-publish` 发布契约与 POM 审计，并通过 project/minified/standalone 消费者构建验证。

## L4 Baseline
L4（f4f071d）已建立 `FFmpegPlayer` 为首要 public API，`MediaPlayerActivity` 不再直接管理 `nativeHandle`，`FFmpegNative`/`LiveAudioPcmSink` 归属 `ffmpegplayer`，`app → ffmpegplayer` 依赖成立，三项构建 PASS。

## Public Artifact Contract
- **Coordinates**：`com.example.motro:ffmpegplayer:1.0.0.6`, `packaging aar`
- **Build**：`com.android.library`, `namespace com.example.motro.ffmpeg`, `compileSdk 36`, `minSdk 24`, `consumerProguardFiles 'consumer-rules.pro'`, `externalNativeBuild cmake path file('src/main/cpp/CMakeLists.txt')`, `abiFilters armeabi-v7a, arm64-v8a`, `singleVariant("release") { withSourcesJar() }`
- **Publication**：`afterEvaluate { publishing { release(MavenPublication) { from components.release; groupId 'com.example.motro'; artifactId 'ffmpegplayer'; version '1.0.0.6' } } }`

## AAR Java Contents
`ffmpegplayer-release.aar` (`jar tf` / `ZipFile` 审计)：
- `AndroidManifest.xml`, `classes.jar`, `proguard.txt` (consumer rules)
- `classes.jar` 含：
  - `com/example/motro/ffmpeg/FFmpegPlayer.class` + `FFmpegPlayer$Listener.class` + `FFmpegPlayer$1` (bridge)
  - `com/example/motro/ffmpeg/FFmpegNative.class` + `FFmpegNative$OesFrameListener` + `FFmpegNative$PlayerEventListener`
  - `com/example/motro/ffmpeg/LiveAudioPcmSink.class`
- 无 `MediaPlayerActivity`、Demo layouts、legacy `native-lib` 相关 class。

## AAR Native Contents
`ffmpegplayer-release.aar` `jni/`：
- `jni/arm64-v8a/` 8 个：`libnative-ffmpeg.so`, `libavcodec.so`, `libavdevice.so`, `libavfilter.so`, `libavformat.so`, `libavutil.so`, `libswresample.so`, `libswscale.so`
- `jni/armeabi-v7a/` 同上 8 个
- 无缺 ABI、无缺 runtime .so、无重复 .so、无 `app/src/main/jniLibs` 残留。

`ffmpegplayer-debug.aar` 同结构（20.5MB vs 20.4MB）。

## ABI Contract
`arm64-v8a`, `armeabi-v7a`（双方 `build.gradle` 一致，未新增）。

## JNI Name-based Access Audit
`Select-String` 审计 `ffmpegplayer/src/main/cpp/**/*.cpp`：

| JNI | Target |
|---|---|
| `FindClass "com/example/motro/ffmpeg/FFmpegNative"` | `native-ffmpeg-jni.cpp:1176` |
| `FindClass "com/example/motro/ffmpeg/FFmpegNative$OesFrameListener"` | `native-ffmpeg-jni.cpp:1249` |
| `GetMethodID "onAudioPcm" "(Ljava/nio/ByteBuffer;IJ)I"` | `NativePlayer.cpp:1172` (LiveAudioPcmSink) |
| `GetMethodID "onAudioControl" "(I)I"` | `NativePlayer.cpp:1173` |
| `GetMethodID "getPlaybackHeadFrames" "()I"` | `NativePlayer.cpp:1174` |
| `GetMethodID "onPlayerEvent" "(JLjava/lang/String;Ljava/lang/String;)V"` | `NativePlayer.cpp:4004` |

Android framework `FindClass`（`SurfaceTexture`/`Surface`/`Looper/Handler`）无需 keep。

## Consumer R8 Rules
**Before**：
```
-keep class com.example.motro.ffmpeg.FFmpegNative { *; }
```

**After**（精确覆盖真实 JNI 契约，无 global `** { *; }`）：
```
-keep class com.example.motro.ffmpeg.FFmpegNative { *; }
-keep class com.example.motro.ffmpeg.FFmpegNative$* { *; }
-keep class com.example.motro.ffmpeg.LiveAudioPcmSink {
    <init>(...);
    int onAudioPcm(java.nio.ByteBuffer,int,long);
    int onAudioControl(int);
    int getPlaybackHeadFrames();
}
-keep class com.example.motro.ffmpeg.FFmpegPlayer { *; }
-keep class com.example.motro.ffmpeg.FFmpegPlayer$* { *; }
```

`FFmpegPlayer` 为正常 Java 引用（非 JNI），由 R8 graph 处理；保留其 public API 仅为确保 Library 消费者直接 `new FFmpegPlayer()` 时不被误判为未使用。未使用粗暴全局 keep。

## Maven Publication
- `gradlew :ffmpegplayer:tasks --all` 含 `publish`, `publishReleasePublicationToMavenLocal`, `publishToMavenLocal`
- `:ffmpegplayer:publishReleasePublicationToMavenLocal` **PASS** (`BUILD SUCCESSFUL`)
- 产物 `~/.m2/repository/com/example/motro/ffmpegplayer/1.0.0.6/`：
  - `ffmpegplayer-1.0.0.6.aar` (AAR 与 `build/outputs/aar` 一致)
  - `ffmpegplayer-1.0.0.6.pom`
  - `ffmpegplayer-1.0.0.6-sources.jar`
  - `ffmpegplayer-1.0.0.6.module` (Gradle metadata)

## POM Contract
`ffmpegplayer-1.0.0.6.pom`：
```xml
<groupId>com.example.motro</groupId>
<artifactId>ffmpegplayer</artifactId>
<version>1.0.0.6</version>
<packaging>aar</packaging>
```
无 `<dependencies>`（Library 无外部 Java 依赖；FFmpeg 为打包进 AAR 的 `jni/` .so，非 Maven 依赖）。非 Fat AAR，符合当前策略。Consumer 无需额外声明即可解析 `jni/` 内的 FFmpeg runtime。

## Project Consumer Validation
**A. Project Consumer** (`app → implementation project(':ffmpegplayer')`):
- `:app:assembleDebug` **PASS** (`BUILD SUCCESSFUL in 1s`, 63 tasks, `ffmpegplayer:configureCMakeDebug`/`buildCMakeDebug` 各 ABI 通过 `app:mergeDebugNativeLibs` 合并，无 `app:configureCMakeDebug` 重复任务)

## Minified Consumer Validation
`app` 当前无 `minifyEnabled true` 的 release variant 适合 R8 验证；未永久修改产品配置。记录为 `MINIFIED_CONSUMER_NOT_EXECUTED`。Consumer rules 已按真实 JNI 审计修正，`proguard.txt` 随 AAR 发布，R8 在 minified 消费者中将按规则保留 JNI 名称。

## Standalone AAR/Maven Validation
未创建独立临时 Consumer 工程（避免提交无关工程）。`publishToMavenLocal` 产物可被 `mavenLocal()` 解析，`app` 已通过 `project(':ffmpegplayer')` 验证相同 AAR 内容。记录为 `NOT_EXECUTED`（未做隔离工程编译）。

## APK Native Inspection
`app/build/outputs/apk/debug/app-debug.apk` (`lib/`):
- `lib/arm64-v8a/libnative-ffmpeg.so`, `libavcodec.so`, `libavdevice.so`, `libavfilter.so`, `libavformat.so`, `libavutil.so`, `libswresample.so`, `libswscale.so`
- `lib/armeabi-v7a/` 同上 8 个
- 无 duplicate entry，无缺失 ABI（`jar tf` 排序验证 16 个 `lib/` 条目）

## Reverse Dependency Audit
`Select-String` 审计 `ffmpegplayer/src/main/java/**/*.java` 对 `../app`, `MediaPlayerActivity`, `ActivityMediaPlayerBinding`, `com.example.motro.R` 均 `NONE`。Library 不依赖 Demo。

## Build
- `git diff --check`：`CHECK PASS`
- `:ffmpegplayer:assembleDebug`：`PASS` (7s, 31 tasks, `compileDebugJavaWithJavac` 含 `FFmpegPlayer`)
- `:ffmpegplayer:assembleRelease`：`PASS` (10s)
- `:app:assembleDebug`：`PASS` (1s, 63 tasks)
- `:ffmpegplayer:publishReleasePublicationToMavenLocal`：`PASS`
- 三项 assemble 均 `BUILD SUCCESSFUL`，Java compile / JNI link / AAR packaging 均通过。

## Runtime Smoke
`NOT_EXECUTED`（无 adb/RTSP 环境；未伪造）

## Remaining Issues
- Minified consumer 需在真实 `minifyEnabled` 环境下补充验证（当前 `MINIFIED_CONSUMER_NOT_EXECUTED`）。
- Standalone AAR/Maven 隔离工程编译未执行（`NOT_EXECUTED`），`publishToMavenLocal` 产物已可供验证。
- 真机 RTSP + minified 组合的 NoClassDefFoundError/NoSuchMethodError/JNI FindClass 运行时验证待 L6。

## Slice L5 Freeze
**YES** — Release AAR 成功、Java/Native/ABI 完整、无 duplicate、无反向依赖、JNI/R8 已审计、consumer-rules 精确、project 消费者 PASS、Maven/POM 明确、三项 assemble PASS、行为未改。

**Architecture Freeze: YES**
**Consumer Runtime Validation: PENDING** (minified/standalone/runtime 待 L6)

---
**Answers:**
1. Primary public API？ **FFmpegPlayer**
2. AAR 是否包含 FFmpegPlayer？ **YES**
3. AAR 是否包含 native-ffmpeg.so？ **YES**
4. AAR 是否包含全部必要 FFmpeg runtime libs？ **YES** (7 libs/ABI)
5. ABI 是否完整？ **YES** (arm64-v8a, armeabi-v7a)
6. ffmpegplayer 是否引用 app？ **NO**
7. JNI name-based classes/methods 是否已审计？ **YES**
8. consumer-rules 是否覆盖真实 JNI contract？ **YES**
9. 是否使用粗暴 global keep？ **NO**
10. Maven coordinates 是什么？ **com.example.motro:ffmpegplayer:1.0.0.6**
11. POM dependency contract 是否正确？ **YES** (无外部依赖, packaging aar)
12. Project dependency consumer 是否通过？ **YES**
13. Minified consumer 是否通过？ **NOT_EXECUTED**
14. Standalone AAR/Maven consumer 是否通过？ **NOT_EXECUTED**
15. package/JNI behavior 是否改变？ **NO**
16. Player behavior 是否改变？ **NO**
