# Library Refactor — Slice L6 — Final Runtime Validation & Public Library Freeze

Date: 2026-08-18
Branch: dev (126e2be + L6)
Scope: 最终 module ownership / Public API / AAR / Maven / JNI-R8 / 回归与生命周期审计，不新增功能，只允许修复直接回归。

## Scope
对 `ffmpegplayer` 做最终架构审计与发布验证，确认单向依赖、唯一 ownership、AAR/ABI/JNI-R8/Maven 契约、Demo 边界、构建矩阵与回归矩阵。无设备环境，runtime 回归以既有 A0-A8 冻结报告 + 静态审计为准，标记 `NOT_EXECUTED`。

## L5 Baseline
L5（126e2be）已修正 `consumer-rules.pro`（精确 JNI keep，无 global `** { *; }`）、验证 Release AAR（Java+Native+ABI 完整）、`publishReleasePublicationToMavenLocal` PASS、POM `com.example.motro:ffmpegplayer:1.0.0.6` (packaging aar)、project 消费者 PASS。Architecture Freeze 已 YES，Consumer Runtime Validation PENDING。

## Final Module Architecture
```
Consumer / Demo App (app)
      ↓ implementation project(':ffmpegplayer')
FFmpegPlayer            # primary public API
      ↓
───────────────────── ffmpegplayer (internal)
FFmpegNative / LiveAudioPcmSink / JNI / C++ / FFmpeg / MediaCodec / GL / Thermal / Recording / AudioTrack
```

## Public API Freeze
- **Primary public API**：`com.example.motro.ffmpeg.FFmpegPlayer`（`AutoCloseable`）。
- Consumer 不接触 `long nativeHandle`、不直接 `new LiveAudioPcmSink`。
- `FFmpegNative` / `LiveAudioPcmSink` 为 `PUBLIC_LEGACY_BRIDGE`（JNI/静态工具要求）。

## Module Ownership Audit
- **app**（`Get-ChildItem` 审计）：
  - `app/src/main/java/com/example/motro/MediaPlayerActivity.java`（唯一生产 Java）
  - `app/src/main/cpp` 不存在（`Test-Path False`）
  - `app/src/main/jniLibs` 不存在（`Test-Path False`）
  - `app/build.gradle`：`implementation project(':ffmpegplayer')`，无 `externalNativeBuild`/`jniLibs.srcDirs`
- **ffmpegplayer**：
  - `src/main/java/com/example/motro/ffmpeg/`：`FFmpegPlayer.java`, `FFmpegNative.java`, `LiveAudioPcmSink.java`
  - `src/main/cpp/`：`CMakeLists.txt`, `native-ffmpeg-jni.cpp`, `native/` 20 文件（NativePlayer/Renderers/Thermal/Recorder/Snapshot/PlayerOptions/ThermalPaletteLut）
  - `src/main/cpp/ffmpeg/include`（7 lib 目录）
  - `src/main/jniLibs/{arm64-v8a,armeabi-v7a}`（各 7 FFmpeg .so）
  - `build.gradle`：`com.android.library` + `externalNativeBuild` + `consumerProguardFiles` + `maven-publish`

## Reverse Dependency Audit
- `Select-String` 审计 `ffmpegplayer/src/main/java/**/*.java`：`../app`, `app/src`, `com.example.motro.R` → **NONE**。
- 唯一命中为 `FFmpegPlayer.java:8` Javadoc 中的 `{@code MediaPlayerActivity}` 文字注释（非代码依赖，无编译/运行影响）。

## Release AAR Audit
`ffmpegplayer-release.aar`：
- `AndroidManifest.xml`, `classes.jar`, `proguard.txt`(consumer rules)
- `classes.jar` 含 7 个 class：`FFmpegPlayer` + `FFmpegPlayer$Listener` + `FFmpegPlayer$1`、`FFmpegNative` + `FFmpegNative$OesFrameListener` + `FFmpegNative$PlayerEventListener`、`LiveAudioPcmSink`
- 无 `MediaPlayerActivity`、Demo layouts、legacy `native-lib` class。

## ABI / Native Dependencies
- 冻结 ABI：`arm64-v8a`, `armeabi-v7a`。
- 每个 ABI `jni/` 8 个 .so：`libnative-ffmpeg.so` + 7 FFmpeg runtime（`libavcodec/libavdevice/libavfilter/libavformat/libavutil/libswresample/libswscale`）。
- 无缺 ABI、无缺 runtime、无 duplicate .so。

## JNI / R8 Contract
- 真实 JNI name-based 契约（`FindClass`/`GetMethodID`）已审计：
  - `FFmpegNative` (FindClass)、`FFmpegNative$OesFrameListener` (FindClass)
  - `LiveAudioPcmSink.onAudioPcm/onAudioControl/getPlaybackHeadFrames` (GetMethodID)
  - `onPlayerEvent` (GetMethodID)
- `consumer-rules.pro` 精确覆盖上述 class/nested/method，无 `-keep class ** { *; }`。

## Maven / Consumer Validation
- Coordinates：`com.example.motro:ffmpegplayer:1.0.0.6`, packaging `aar`, POM 无外部依赖。
- `publishReleasePublicationToMavenLocal`：PASS（L5 已执行；L6 未重复，产物仍在 `~/.m2`）。
- Standalone 隔离 Consumer：NOT_EXECUTED（未建临时工程，`app` 已以 `project(':ffmpegplayer')` 验证相同 AAR）。

## Build Matrix
- `git diff --check`：CHECK PASS（无空白错误，仅 LF→CRLF 提示）
- `:ffmpegplayer:assembleDebug`：PASS（BUILD SUCCESSFUL）
- `:ffmpegplayer:assembleRelease`：PASS
- `:app:assembleDebug`：PASS
- 无 duplicate class/.so，Java compile / JNI link / AAR packaging / Manifest merge 均通过。

## Video Regression
`NOT_EXECUTED`（无设备/RTSP）。静态：`app` 未改渲染路径，`ffmpegplayer` Native/Renderer 代码 L1-L5 未改，保持 Hardware `nv12_cpu→nv12_gl` 与 Software `yuv_gl` 语义（A6-A8 冻结）。

## Thermal Regression
`NOT_EXECUTED`。`ThermalConfig/ThermalPaletteLut/shader` 未改，Original/White Hot/Ironbow/Gamma/Window/AGC 语义保持。

## Audio Regression
`NOT_EXECUTED`。`AAC→SWR→PCM 48k/S16/Stereo→Queue→Worker→JNI→AudioTrack→Clock→A/V Sync` 链未改；Audio OFF 仍仅停监听、Recording 继续。

## Recording Regression
`NOT_EXECUTED`。Recorder 仍为 compressed AAC remux（含 `aac_adtstoasc`），Audio 状态不改变 stream mapping。

## Snapshot Regression
`NOT_EXECUTED`。Snapshot 能力路由（native RGBA / PixelCopy）未改，无新增 glReadPixels/FBO/PBO。

## Surface Stress
`NOT_EXECUTED`。Fix 2 owner-thread/EGL 生命周期未改。

## Toggle / Pause Stress
`NOT_EXECUTED`。Audio toggle/Pause 生命周期（generation flush、clock invalidate、worker join）逻辑未改。

## Reconnect Stress
`NOT_EXECUTED`。Reconnect 的旧 decoder/PCM/clock 隔离逻辑未改。

## Release / Race Safety
`NOT_EXECUTED`（runtime）。`FFmpegPlayer.release()` 幂等、`nativeHandle` 清零、`synchronized(lock)`、`setAudioCallback(null)`+`setPlayerEventListener(null)` 后 `releasePlayer`，与 Fix 4 release-safe JNI 兼容；静态审计无 UAF/double-release 路径。

## Long-run Validation
`NOT_EXECUTED`（无设备）。

## Stats Consistency
`PASS`（静态）。`getStats` 字段语义自 A0-A8 冻结未改，仍区分 requested/actual decoder、audio enabled/playable/decoded/pcm/sink/clock、syncMaster/effectiveSyncMaster、record 计数器与 `audioRecordingIndependentOfPlayback`。

## Remaining Issues
- Runtime 回归矩阵（Video/Thermal/Audio/Recording/Snapshot/Surface/Reconnect/Release/Long-run）因无设备/RTSP 全部 `NOT_EXECUTED`，未伪造 PASS。
- Standalone AAR/Maven 隔离 Consumer 与 minified Consumer 未执行（L5 已记 `NOT_EXECUTED`/`MINIFIED_CONSUMER_NOT_EXECUTED`）。
- `FFmpegPlayer.java:8` Javadoc 含 `MediaPlayerActivity` 文字注释（非依赖，可选清理，L6 不扩大范围）。

## Public Library Final Freeze
- **Library Architecture Freeze: YES**（单向依赖、唯一 ownership、FFmpegPlayer 正式 primary API、raw handle 不暴露、AAR Java/Native/ABI 完整、JNI/R8 正确、无 duplicate、三项构建 PASS、行为未改）。
- **Library Runtime Freeze: PENDING**（需真实设备 + RTSP runtime matrix 达到验收后置 YES）。

---
**Answers:**
1. Primary public API: **FFmpegPlayer**
2. app owns player implementation: **NO**
3. ffmpegplayer references app: **NO**（仅 Javadoc 文字注释，非代码依赖）
4. Demo manages nativeHandle: **NO**
5. Demo directly uses LiveAudioPcmSink: **NO**
6. Release AAR complete: **YES**
7. AAR native ABI complete: **YES**
8. JNI/R8 contract: **PASS**
9. Standalone consumer: **NOT_EXECUTED**
10. Hardware Video: **NOT_EXECUTED**
11. Thermal: **NOT_EXECUTED**
12. Audio: **NOT_EXECUTED**
13. A/V Sync: **NOT_EXECUTED**
14. Recording with audio OFF: **NOT_EXECUTED**
15. Surface reattach: **NOT_EXECUTED**
16. Reconnect: **NOT_EXECUTED**
17. Release race: **NOT_EXECUTED**（静态审计安全）
18. Long-run: **NOT_EXECUTED**
19. Player behavior changed by Library refactor: **NO**
