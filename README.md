# Motro Android FFmpeg Player

Motro 是一套以 FFmpeg Native 为核心的 Android 专业 RTSP 音视频播放工程（Video + Thermal + Recording + Live Audio），不使用 ExoPlayer 作为播放主链路。

项目为 **双模块架构**（Library Refactor L0~L6 已冻结）：

```
app（Demo）
└── MediaPlayerActivity + Demo UI
        ↓  implementation project(':ffmpegplayer')
ffmpegplayer（public library）
├── FFmpegPlayer        # primary public API（AutoCloseable）
├── FFmpegNative        # public legacy JNI bridge（普通 Consumer 无需直接调用）
├── LiveAudioPcmSink    # public JNI callback implementation（普通 Consumer 无需实例化）
├── JNI / C++（NativePlayer、Renderers、Thermal、Recorder、Snapshot）
├── FFmpeg headers / runtime .so
└── CMake
```

- `app`：仅 Demo（`MediaPlayerActivity.java` + UI 资源），不拥有任何播放器实现。
- `ffmpegplayer`：`com.android.library`，拥有完整 Player implementation（Java + JNI + C++ + FFmpeg + Renderer + Thermal + Recording + AudioTrack）。
- 依赖方向：**app → ffmpegplayer**；`ffmpegplayer` 不引用 `../app`。
- `app` `applicationId = com.example.motro`；Java 包 `com.example.motro` / `com.example.motro.ffmpeg`。

---

## 1. 当前能力

- FFmpeg JNI 基础能力：版本、编译参数、decoder 可用性、MediaCodec 状态、URL probe。
- Native 播放器：RTSP / HLS / RTMP / HTTP / 本地文件（Live 主链为 RTSP/网络流；Local VOD 属未来 D 任务，由 Media3/ExoPlayer 承担）。
- **硬解主链（Phase 2 Revised）**：

  ```
  RTSP/HEVC
  → hevc_mediacodec
  → CPU-visible AV_PIX_FMT_NV12
  → NativeNv12GlRenderer（OpenGL ES）
  → SurfaceView
  ```

  正常路径不经过 `sws_scale` / RGBA CPU 转换 / `ANativeWindow` RGBA 渲染。

- **Thermal 主链（NV12 GL / YUV GL）**：

  ```
  NV12 Y（或 YUV420P Y）
  → Range Normalize（full/limited）
  → Manual / AGC Window
  → Gamma
  → White Hot / Ironbow
  → OpenGL ES → SurfaceView
  ```

- **Live Audio 主链（Audio Phase 1，A0~A8 已冻结）**：

  ```
  RTSP AAC packet
   ├──→ PlayerRemuxRecorder（原始压缩 AAC → MP4/TS，独立于监听）
   └──→ FFmpeg AAC Decoder → AVFrame
         → libswresample → PCM S16 / 48000 Hz / Stereo / Interleaved
         → Bounded PCM Queue（目标 150 ms / 上限 250 ms，drop-oldest）
         → Audio Output Worker（独立线程，join 管理）
         → JNI NewDirectByteBuffer → LiveAudioPcmSink → AudioTrack（MODE_STREAM）
         → playback-head 时钟 → A/V Sync（Video follows Audio）
  ```

  Audio Phase 1 已真机验证（Audio ON/OFF×30、Pause/Resume×20、Surface×20、30 分钟长跑、Recording 独立性）。

- 播放时同路 remux 录制（不重开 RTSP、不转码、不重编码）与分片录制；MP4/MOV 压缩 AAC 经 `aac_adtstoasc` bitstream filter 转帧（不解码不重编码）。
- 截图：RGBA 路径复用最近帧缓存；GL/direct-Surface 路径由 Demo 用 `PixelCopy`；NV12 GL native snapshot 暂不支持。
- 播放统计：stream / 帧数 / packet / 渲染 / 丢帧 / 录制 / Surface / 时钟 / 延迟 / 重连 / Thermal / Audio 全链路状态。
- RTSP TCP/UDP 低延迟配置与多档 profile。
- Surface 安全处理：Surface 销毁后可 `clearSurface`，播放线程继续解码并跳过渲染；Surface 生命周期与 Audio 完全解耦。

---

## 2. 工程结构（最终架构）

```text
app/                                    # Demo module
├── build.gradle                        # application; implementation project(':ffmpegplayer')
└── src/main/
    ├── AndroidManifest.xml             # MediaPlayerActivity (LAUNCHER)
    ├── java/com/example/motro/
    │   └── MediaPlayerActivity.java    # 唯一 Demo Activity（仅 UI + 用户操作）
    └── res/                            # Demo layout / drawable / strings / themes / launcher

ffmpegplayer/                           # Public library module
├── build.gradle                        # com.android.library + externalNativeBuild + consumer rules
├── consumer-rules.pro                  # JNI/R8 consumer keep rules
└── src/main/
    ├── AndroidManifest.xml             # 仅 INTERNET 权限
    ├── java/com/example/motro/ffmpeg/
    │   ├── FFmpegPlayer.java           # primary public API（facade）
    │   ├── FFmpegNative.java           # public legacy JNI bridge（PUBLIC_LEGACY_BRIDGE）
    │   └── LiveAudioPcmSink.java       # public JNI callback implementation
    ├── cpp/
    │   ├── CMakeLists.txt
    │   ├── native-ffmpeg-jni.cpp
    │   ├── ffmpeg/include/             # FFmpeg headers（7 lib）
    │   └── native/                     # 播放器 Native 实现（20 文件）
    └── jniLibs/{arm64-v8a,armeabi-v7a} # FFmpeg runtime .so（5/ABI）
```

### ffmpegplayer Native 模块职责

| 文件 | 职责 |
| --- | --- |
| `native-ffmpeg-jni.cpp` | JNI 注册、opaque handle 管理（Fix 4）、FFmpeg 基础诊断入口；两个 test-only 命令仅 Debug 可执行，Release 稳定拒绝。 |
| `NativePlayer.*` | 输入/解码/渲染线程、状态机、Thermal 调度、Audio 全链路（decode→SWR→queue→worker→sink→clock→A/V sync）、统计、重连、录制集成。 |
| `NativeNv12GlRenderer.*` | 硬解 NV12 → OpenGL ES（Original / White Hot / Ironbow / Gamma / Window），EGL 生命周期。 |
| `NativeYuvGlRenderer.*` | 软件 YUV420P → OpenGL ES（Phase 1 Thermal）。 |
| `NativeOesRenderer.*` | MediaCodec → SurfaceTexture → OES（experimental）。 |
| `VideoRenderer.*` | RGBA → `ANativeWindow`（软件 fallback）。 |
| `PlayerOptions.*` | RenderMode、RTSP transport、低延迟 profile、syncMaster。 |
| `ThermalConfig.*` / `ThermalPaletteLut.*` | Thermal 配置与共享 Ironbow LUT。 |
| `PlayerRemuxRecorder.*` / `SnapshotManager.*` | remux 录制（含 AAC `aac_adtstoasc`）与截图。 |

---

## 3. Public API：FFmpegPlayer

`com.example.motro.ffmpeg.FFmpegPlayer` 是 Library 首要公共 API（`implements AutoCloseable`），内部统一管理：

- native player handle（`FFmpegNative.createPlayer()` 唯一 Java owner）
- `LiveAudioPcmSink`（AudioTrack 生命周期内部化）
- native event callback bridge（`FFmpegPlayer.Listener`）
- player lifecycle（`release()` 幂等；release 后 String 播放操作返回 JSON error，不触 stale handle）

普通 Consumer 不需要直接接触 `long nativeHandle`、`FFmpegNative` 或 `LiveAudioPcmSink`；后两者因 JNI/兼容契约保持 public。

```java
FFmpegPlayer player = new FFmpegPlayer();
player.setListener((event, eventJson) -> { /* reconnect 等事件 */ });
player.setSurface(surface);
player.prepare(url, timeoutMs);
player.start();
player.setAudioEnabled(true);        // 实时监听开关
player.setHardwareDecodeEnabled(true);
player.setThermalEnabled(true); player.setThermalPalette(FFmpegPlayer.THERMAL_PALETTE_WHITE_HOT);
// ...
player.stop();
player.release();                     // 或 try-with-resources
```

| FFmpegPlayer API | 说明 |
| --- | --- |
| `setSurface(Surface)` / `clearSurface()` | 绑定/清理渲染 Surface（Surface 销毁 ≠ player release）。 |
| `prepare(url, timeoutMs)` / `start()` / `pause()` / `stop()` | 播放生命周期（Prepare→Start 不重开 RTSP）。 |
| `release()` / `close()` | 幂等 release（join worker → sink release → 删除 JNI GlobalRef）。 |
| `setAudioEnabled(boolean)` | Live Audio 监听开关（不 reopen RTSP、不重建 MediaCodec、不影响 Recorder）。 |
| `setHardwareDecodeEnabled` / `setHardwareRenderMode` | 硬解开关与渲染模式。 |
| `setRtspTransport` / `setLatencyMode` | RTSP transport 与播放延迟 profile；profile 为 `low_latency/ultra_low_latency/balanced/stable`。 |
| `setPlayerOption` | 单项参数入口；也用于设置 `diagnostics_mode=off/basic/latency`。 |
| `setReconnectOptions` | 重连策略。 |
| `setThermalEnabled(boolean)` / `setThermalPalette(int)` / `setThermalAgcEnabled(boolean)` / `setThermalGamma(float)` / `setThermalWindow(float,float)` | Thermal 控制。 |
| `startRecord` / `startSegmentRecord` / `startRecordWithConfig` / `stopRecord` / `getRecordState` | remux 录制（与 Live Audio 独立）。 |
| `takeSnapshot(path)` | 截图（Fixes 能力路由）。 |
| `getState` / `getStats` / `getReconnectState` / `getLatencyConfig` | 状态 / 统计 / 重连 / 延迟 JSON。 |
| `isReleased()` | release 状态查询。 |

### 生命周期与线程

- `FFmpegPlayer` 以实例锁串行化 facade 调用并拥有唯一 native handle；一个实例不要并发执行互相冲突的生命周期操作。
- `prepare/start/pause/stop/release` 可能等待 native 工作完成，不应在主线程执行耗时流程；Demo 使用后台 executor 调用。
- `FFmpegPlayer.Listener` 在 native 事件回调线程触发，不保证是 Android 主线程；更新 UI 前必须自行切换到主线程。
- `release()` 可重复调用；第一次释放资源，后续返回成功的 `player already released` JSON。`close()` 等价于调用 `release()`。
- release 后 `isReleased()` 为 `true`、`setListener()` 被忽略，其他 String 播放操作返回 `success=false` JSON。

`FFmpegNative` / `LiveAudioPcmSink` 保持 `public`（`PUBLIC_LEGACY_BRIDGE`，供 JNI `FindClass`、静态诊断工具和二进制兼容）；它们不是普通 Consumer 的首选入口。

---

## 4. RenderMode 与能力矩阵

`setHardwareRenderMode(mode)`（经 `FFmpegPlayer` / `FFmpegNative`）支持：

| RenderMode | 解码 | 输入 | Thermal |
| --- | --- | --- | --- |
| `mediacodec_nv12_gl` | MediaCodec 硬解（默认） | CPU NV12 AVFrame | **YES**（完整 Thermal） |
| `software_yuv_gl` | 软件解码（Phase 1） | YUV420P/YUVJ420P | **YES**（完整 Thermal） |
| `mediacodec_oes` | MediaCodec 硬解 | SurfaceTexture/OES | experimental（保留） |
| `software_rgba` | 软件解码 | RGBA（sws_scale） | NO（兼容/fallback） |
| `mediacodec_surface` | MediaCodec 硬解 | 直接 Surface | NO（legacy） |

Thermal 能力矩阵：

| RenderMode | Thermal | White Hot | Ironbow | Gamma | Manual Window | AGC |
| --- | --- | --- | --- | --- | --- | --- |
| `software_yuv_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_nv12_gl` | YES | YES | YES | YES | YES | YES |
| `mediacodec_oes` | YES（experimental） | — | — | — | — | — |
| `software_rgba` / `mediacodec_surface` | NO | — | — | — | — | — |

**Hardware Decode ON 默认路由**（`MediaPlayerActivity`）：

```java
player.setHardwareDecodeEnabled(true);
player.setHardwareRenderMode("mediacodec_nv12_gl");
```

顺序不可反（关闭硬解会重置软件 render mode）。Hardware Decode OFF 时回 `software_yuv_gl`。

---

## 5. Thermal 处理链

```
NV12 Y（或 YUV420P Y）
→ Range Normalize（limited: 16~235 → 0..1；full: identity）
→ Manual Window（blackPoint/whitePoint，intensity 0..1 域）
→ AGC Effective Window（可选，P2/P98，alpha=0.15，每 5 帧一次，4×4 采样，256-bin histogram，CPU）
→ Gamma（pow(intensity, max(uGamma, 0.001))）
→ White Hot（灰度） / Ironbow（256×1 LUT，与 Phase 1 共享 ThermalPaletteLut）
```

- 处理顺序固定：Range → Window → Gamma → Palette。
- 默认 `blackPoint=0 / whitePoint=1 / gamma=1` 时与基准画面一致（identity）。
- AGC ON 时使用 AGC Effective Window（manual 不被覆盖）；AGC OFF 立即恢复 manual Window。
- `software_yuv_gl` 与 `mediacodec_nv12_gl` 共用同一 `ThermalConfig` 与 `computeAgcWindow` 算法。

普通 Consumer 使用 `FFmpegPlayer.THERMAL_PALETTE_ORIGINAL`、`FFmpegPlayer.THERMAL_PALETTE_WHITE_HOT`、`FFmpegPlayer.THERMAL_PALETTE_IRONBOW`。它们分别转发 `FFmpegNative` 中数值为 `0/1/2` 的兼容常量；legacy 常量没有删除。

---

## 6. Audio Phase 1（Live Audio Monitoring）

### 6.1 架构与固定契约

```
RTSP AAC packet
 ├──→ PlayerRemuxRecorder（原始压缩 AAC，remux 独立）
 └──→ FFmpeg AAC Decoder
       → AVFrame（lazy swr 按首帧真实格式配置）
       → libswresample → PCM：
           格式 S16（AV_SAMPLE_FMT_S16，little-endian）
           采样率 48000 Hz
           声道 2（Stereo）
           布局 Interleaved
       → AudioPcmQueue（bounded：目标 150 ms / 上限 250 ms；满则 drop-oldest，producer 永不阻塞）
       → Audio Output Worker（独立 native 线程；JavaVM attach 一次/detach 一次；join 管理，绝不 detach）
       → JNI NewDirectByteBuffer（PCM block 在同步调用期间保持有效）
       → LiveAudioPcmSink（Java，持有 AudioTrack）
       → AudioTrack.write（仅运行在 Audio Output Worker）
       → Speaker
```

- 输入不固定：任何 AAC 输出格式统一由 swr 转换为固定 48 kHz / Stereo / S16 / Interleaved。
- 输入格式变化（reconnect / source change）：swr 按 decoded frame identity 重建，输出契约不变。
- `audioEnabled` = 用户实时监听请求（纯意图）；`audioPlayable` = 完整监听链路可用。
- Recording 与监听完全独立：Recorder 在 decode 之前收到原始压缩 AAC packet，Audio OFF / PCM 丢弃 / AudioTrack 失败均不影响录制。

### 6.2 AudioTrack

- 由 `LiveAudioPcmSink` 持有（`FFmpegPlayer` 内部创建/注册，不依赖 Activity/View）。
- 固定配置：`48000 Hz / CHANNEL_OUT_STEREO / ENCODING_PCM_16BIT / MODE_STREAM`，buffer 取自 `getMinBufferSize`。
- `AudioTrack.write` 使用 **WRITE_NON_BLOCKING** + 250 ms 最大 drain 窗口 + 2 ms 取消轮询；生命周期命令推进 Java epoch 并在 pause/flush/release 前禁用写入，受控取消返回独立码（`audioSinkControlledCancelCount`）。

### 6.3 Audio Playback Clock 与 A/V Sync

- 时钟源：`AudioTrack.getPlaybackHeadPosition()`（worker 每 block ~20 ms 读取一次，不在 video 线程做 JNI 查询）。
- 32-bit playback head 经 uint32 delta 扩展为单调 64-bit played frames（wrap 安全）。

  ```
  audioPlaybackClockUs = baseMediaPtsUs + playedFrames * 1_000_000 / 48000
  ```

- `audioPlaybackClockValid` = true 仅当：audio enabled、decoder/SWR 正常、AudioTrack operational、有有效媒体 PTS、playback head 可查、当前 generation 有输出。
- 时钟失效/陈旧（500 ms 未刷新、AudioTrack 失败、OFF/Pause/Reconnect/Stop）→ `effectiveSyncMaster=video` 回退。
- **A/V Sync**：`effectiveSyncMaster=audio` 仅当 `syncMaster=audio` 且 clock valid；Video 早于音频 → 有界等待（≤150 ms）；晚于 → 沿用 low-latency 丢帧策略。
- 同代 PCM PTS 不连续（>20 ms 洞）→ 重锚 playback-head 映射（`audioClockPtsDiscontinuityCount`）。
- 所有 OFF/ON、Pause、Stop、callback 替换、reconnect/transport 不连续、解码格式变化推进 `audioGeneration` 并 reset clock base。

### 6.4 生命周期语义（已冻结）

- **Audio OFF**：停 PCM 生产 → 推进 generation → flush 队列+AudioTrack → invalidate/reset clock → stop+join worker → 请求 playback 线程 reset decoder/SWR。RTSP 与 Video 继续，Recorder 继续录 AAC。
- **Audio ON**：新 generation → reset decoder/SWR → 新 sink epoch + 启动 worker → 从 live edge 恢复；不 reopen RTSP、不重建 MediaCodec。
- **Pause**：推进 generation、flush 队列+AudioTrack、invalidate clock、stop+join worker；realtime 下 playback 线程继续排空压缩包并继续 Recorder。Resume 从 fresh sink/worker epoch 开始。
- **Reconnect**：隔离旧 generation（flush 队列+AudioTrack、reset clock/head），重建 decoder/SWR，成功且 Audio ON 时恢复 sink/worker 并重建时钟。Recorder 语义不变。
- **Stop / Release**：确定性 shutdown（join worker 不持锁 → sink Release → 删除 JNI GlobalRef）。重复 Release / stale handle 安全（Fix 4）。

---

## 7. Fallback 语义（重要）

- **Decoder fallback**：`hevc_mediacodec → software hevc` 才置 `hardwareDecodeFallbackUsed=true`、`decodeBackend="software"`。
- **Renderer fallback**：NV12 GL 渲染失败 → 回退 RGBA（sws）渲染器，`renderFallbackUsed=true`、`renderer="rgba_nativewindow"`。
- **Audio fallback**：任何 Audio 失败只降级监听（`audioPlayable=false`、`audioPlaybackClockValid=false`、`effectiveSyncMaster=video`），绝不触发 Video fallback / RTSP reopen / Recorder 中断。

三者完全独立，绝不互相冒充。

---

## 8. 构建

### 8.1 Consumer 引用

仓库内 Demo 使用模块依赖：

```groovy
dependencies {
    implementation project(':ffmpegplayer')
}
```

独立工程可复制 Release AAR 到例如 `app/libs/ffmpegplayer-release.aar`，然后仅依赖该文件：

```groovy
dependencies {
    implementation files('libs/ffmpegplayer-release.aar')
}
```

AAR 已嵌入 `proguard.txt`（来自 `consumer-rules.pro`）；Consumer 开启 R8 时规则会随 AAR 合并，用于保护 `FFmpegNative` 注册、事件回调、OES listener 和 Audio JNI callback 的精确类名/方法签名。无需在 Consumer 添加 package-wide keep。支持 ABI 仅为 `armeabi-v7a` 与 `arm64-v8a`。

### 8.2 构建与测试命令

```powershell
# Demo APK（自动构建依赖的 ffmpegplayer，含 CMake native）
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease

# Library AAR（debug / release）
.\gradlew.bat :ffmpegplayer:assembleDebug
.\gradlew.bat :ffmpegplayer:assembleRelease

# 当前 JVM unit tests
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest
```

- Native 由 `ffmpegplayer/src/main/cpp/CMakeLists.txt` 生成 `libnative-ffmpeg.so`（链接 FFmpeg runtime），ABI：`armeabi-v7a, arm64-v8a`。
- AAR 产物：`ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar`，包含 `classes.jar`（3 个 public 顶层类型及 nested/anonymous class）、`jni/{arm64-v8a,armeabi-v7a}/`（每 ABI 为 `libnative-ffmpeg.so` + 5 个必需 FFmpeg `.so`）、`proguard.txt`、`AndroidManifest.xml` 与 AAR metadata。
- `ffmpegplayer` 的 Release runtime classpath 无外部 Maven 依赖；FFmpeg native runtime 直接打包在 AAR 中。
- 构建需要 JDK 17。
- 当前 app Debug/Release JVM unit tests 可执行；`ffmpegplayer` 没有配置 Java/Kotlin unit-test source，相关 Gradle test task 为 `NO-SOURCE`。Native diagnostics/test-hook helpers 使用独立 C++ host tests。

### 8.3 Maven publishing

**NOT_CONFIGURED**。当前 `ffmpegplayer/build.gradle` 没有应用 `maven-publish`、没有 `publishing/publication` 配置，也没有可承诺的 `groupId/artifactId/version` 或 `publishReleasePublicationToMavenLocal` 任务。当前正式交付物是上面的本地 Release AAR；Maven Central、私有仓库或 Maven Local 发布属于后续独立工作。

---

## 9. so 加载顺序

`FFmpegNative` 静态块按序加载（位于 `ffmpegplayer`）：

```java
avutil
swresample   // required：Audio SWR 与 ELF DT_NEEDED 均依赖
swscale
avcodec
avformat
native-ffmpeg
```

---

## 10. FFmpegPlayer API 明细

所有返回 `String` 的方法返回 JSON；成功 `{"success":true,...}`，失败 `{"success":false,"errorCode":..,"errorMessage":"reason"}`。

| FFmpegPlayer API | 说明 |
| --- | --- |
| `new FFmpegPlayer()` | 内部 `createPlayer()` + 创建 `LiveAudioPcmSink` + 注册 listener/audio callback。 |
| `setListener(Listener)` | 播放事件；回调签名为 `Listener.onPlayerEvent(String event, String eventJson)`，内部桥接 `FFmpegNative.PlayerEventListener`。事件含 `reconnect_disconnected/reconnecting/waiting_source/reconnect_success/reconnect_exhausted`。 |
| `setSurface(Surface)` / `clearSurface()` | 渲染 Surface 绑定 / 清理（Surface 销毁 ≠ player release）。 |
| `prepare(url, timeoutMs)` | 打开输入、选 decoder、配置 renderer、打开 audio decoder（如有）。 |
| `start()` / `pause()` / `stop()` | 播放控制（Pause 冻结画面+音频）。 |
| `release()` / `close()` | 幂等 release：置 released → handle 清零 → 清理 listener/audio callback → `releasePlayer`。 |
| `setAudioEnabled(boolean)` | Live Audio 监听开关（不 reopen RTSP、不影响 Recorder）。 |
| `setHardwareDecodeEnabled(boolean)` / `setHardwareRenderMode(String)` | 硬解开关与渲染模式。 |
| `setRtspTransport(String)` / `setLatencyMode(String)` / `setPlayerOption(k,v)` | RTSP transport（tcp/udp/udp_multicast/auto）、播放延迟 profile（low_latency/ultra_low_latency/balanced/stable）、单项参数。 |
| `setReconnectOptions(enabled, maxRetry, retryDelayMs)` | 重连策略（maxRetry=-1 无限）。 |
| `setThermalEnabled(boolean)` / `setThermalPalette(int)` / `setThermalAgcEnabled(boolean)` / `setThermalGamma(float)` / `setThermalWindow(float,float)` | Thermal 控制。 |
| `startRecord(path)` / `startSegmentRecord(pattern, sec)` / `startRecordWithConfig(pathOrPattern, format, sec)` / `stopRecord()` / `getRecordState()` | remux 录制（mp4/mkv/ts/mov/webm/flv；`segmentSec>0` 分片）。 |
| `takeSnapshot(path)` | 截图（native RGBA / Demo PixelCopy）。 |
| `getState()` / `getStats()` / `getReconnectState()` / `getLatencyConfig()` | 状态 / 统计 / 重连 / 延迟 JSON。 |
| `isReleased()` | release 状态查询。 |

播放器状态：`idle / preparing / prepared / playing / paused / reconnecting / stopping / stopped / error / released`。

### Legacy bridge（PUBLIC_LEGACY_BRIDGE）

`FFmpegNative` / `LiveAudioPcmSink` 保持 public（JNI `FindClass`、静态工具、兼容），因此仍属于 AAR 的 public binary surface，但不是推荐的普通 Consumer API。Demo 的播放生命周期和功能控制使用 `FFmpegPlayer`；直接 legacy bridge 使用仅限 FFmpeg 能力信息、PH4 Debug stress 入口和兼容常量，不持有 raw native handle，也不直接创建 `LiveAudioPcmSink`。

`PlayerOptions`、`DiagnosticsMode` 与 `ThermalConfig` 是 native C++ 内部类型，不在 `classes.jar` 中，也不是 Java Consumer API。Java 侧分别通过 `setLatencyMode/setPlayerOption`、`setPlayerOption("diagnostics_mode", value)` 和 Thermal facade 方法配置。

### DiagnosticsMode（生产诊断）

生产默认模式是 **BASIC**：

```java
player.setPlayerOption("diagnostics_mode", "off");
player.setPlayerOption("diagnostics_mode", "basic");   // production default
player.setPlayerOption("diagnostics_mode", "latency");
```

| 值 | 语义 |
| --- | --- |
| `off` | 关闭可选诊断日志与高级 latency 分布/PRET0/E2E 计算；不关闭播放。 |
| `basic` | 生产健康诊断：保留低开销状态、帧/包/错误/重连和 compact health 信息。 |
| `latency` | 详细延迟诊断：启用 LAT1/LAT2/LAT3、PRET0、RTCP/PRFT/E2E 与完整 latency 日志。 |

Diagnostics 是正式生产 contract。Test Hook 不是 Diagnostics：`-player-lifetime-stress` 和 `-audio-backpressure-test` 只在 Debug native build 中可执行；Release 会在 dispatch 前返回：

```json
{"success":false,"errorCode":"unsupported_in_release","message":"test hook is unsupported in release builds"}
```

`FFmpegNative.runDebugCommand` 的 read-only version/build/decoder/source/probe/help 诊断仍在所有变体可用；普通 Consumer 应优先使用 `FFmpegPlayer`，且不应把 stress command 当作生产诊断 API。

### Result / JSON 语义

- `FFmpegPlayer` 的 String-returning 播放操作返回 JSON。成功至少含 `"success":true`；失败含 `"success":false` 以及当前实现提供的 `errorCode/errorMessage` 或稳定的错误说明字段。
- `getStats()` 返回兼容的统计快照，`diagnosticsMode` 为 `off/basic/latency`。BASIC/OFF 下高级分布和 E2E 字段可能为空或无效，而不是伪造采样。
- 时间/计数字段不可用时可使用 `-1`、`0` 或配套 `Valid=false`；字符串枚举在无法确定时可为 `"unknown"`。Consumer 应优先检查 `success`、相关 validity/sample-count 字段，再解释 p50/p95/p99 或时钟值。
- PH6 保持现有 String/JSON API，不引入 typed result，也不改变既有字段或返回语义。

---

## 11. `FFmpegPlayer.getStats()` 常用字段

### 视频 / 渲染 / Thermal

| 字段 | 含义 |
| --- | --- |
| `renderMode` / `decodeBackend` / `frameOutputType` / `renderer` | 请求模式 / 实际解码后端 / 实际帧输出 / 实际渲染器。 |
| `requestedRenderer` / `renderFallbackUsed` / `renderFallbackReason` | 渲染 fallback 状态。 |
| `usingHardwareDecoder` / `hardwareDecodeFallbackUsed` / `hardwareDecodeError` | 硬解使用与 decoder fallback。 |
| `hardwareDecodedFrameCount` / `softwareDecodedFrameCount` | 硬解 / 软件解码输出帧数。 |
| `nv12GlRenderedFrameCount` / `nv12GlFallbackFrameCount` / `nv12GlNoSurfaceFrameCount` | NV12 GL 渲染 / 回退 / 无 Surface 帧数。 |
| `yuvGlRenderedFrameCount` / `yuvGlFallbackFrameCount` | 软件 YUV GL 渲染 / 回退帧数。 |
| `renderedFrameCount` / `droppedVideoFrameCount` / `frameDropBeforeRenderCount` | 总渲染帧 / 丢帧 / 渲染前显式丢弃。 |
| `swsScaleEnabled` / `swsScaleInvocationCount` / `lastSwsScaleCostUs` | sws 是否实际启用（NV12 GL 正常为 false / 0 / -1）。 |
| `lastNv12GlRenderCostUs` / `avgNv12GlRenderCostUs` / `maxNv12GlRenderCostUs` | NV12 GL 渲染耗时。 |
| `thermalEnabled` / `thermalPalette` / `thermalGamma` / `thermalBlackPoint` / `thermalWhitePoint` | Thermal 配置。 |
| `thermalAgcEnabled` / `thermalAgcValid` / `thermalAgcBlackPoint` / `thermalAgcWhitePoint` | AGC 配置与有效窗口。 |
| `thermalRenderMode` / `thermalInputType` / `thermalWindowApplied` | 实际 Thermal 模式与输入类型。 |
| `nv12AgcUpdateCount` / `nv12AgcInvalidCount` | NV12 AGC 更新/无效计数。 |

### Audio（Audio Phase 1）

| 字段 | 含义 |
| --- | --- |
| `sourceHasAudio` / `audioStreamIndex` / `audioCodec` / `audioSampleRate` / `audioChannels` / `audioSampleFormat` | 输入流元数据。 |
| `audioEnabled` / `audioPlayable` | 用户监听请求 / 完整链路可用。 |
| `audioDecodeOpened` / `audioCallbackSet` / `audioSinkReady` | decoder 打开 / sink 注册 / sink 就绪。 |
| `audioPacketCount` / `audioPacketBytes` | demux 到的压缩音频包。 |
| `audioDecodedFrameCount`（别名 `audioFrameCount`）/ `audioDecodedSampleCount` / `audioDecodeErrorCount` | 解码帧 / 样本 / 错误。 |
| `audioPcmBlockCount` / `audioPcmSampleCount` / `audioPcmByteCount` | SWR 输出块 / 样本 / 字节。 |
| `audioOutputSampleRate` / `audioOutputChannels` / `audioOutputSampleFormat` / `audioOutputInterleaved` | 固定输出契约（48000 / 2 / s16 / true）。 |
| `audioSwrReconfigureCount` / `audioResampleErrorCount` | SWR 重建 / 重采样错误。 |
| `audioQueueDurationUs` / `audioQueueBlockCount` / `audioQueueBytes` / `audioQueueHighWatermarkUs` | PCM 队列深度 / 峰值。 |
| `audioQueueDropCount` / `audioQueueDroppedSampleCount` / `audioQueueFlushCount` / `audioQueueGeneration` | 队列溢出丢块 / 丢样本 / flush / generation。 |
| `audioWorkerRunning` / `audioWorkerStartCount` / `audioWorkerJoinCount` / `audioWorkerStaleBlockCount` | worker 运行态 / 启动 / join / 陈旧块。 |
| `audioWorkerConsumedBlockCount` / `audioWorkerConsumedSampleCount` / `audioWorkerConsumedByteCount` | worker 消费统计。 |
| `audioSinkWriteCount` / `audioSinkWrittenByteCount` / `audioSinkWriteErrorCount` / `audioSinkLastErrorCode` | AudioTrack 写入成功 / 字节 / 错误 / 最后错误码。 |
| `audioSinkControlledCancelCount` / `audioSinkRestartCount` | 受控取消 / sink 重启次数。 |
| `audioClockUs` | **LEGACY**：最后压缩音频包 PTS 镜像，非播放时钟。 |
| `audioPlaybackClockUs` / `audioPlaybackClockValid` / `audioPlaybackHeadFrames` | 真实 AudioTrack playback-head 时钟。 |
| `audioClockGeneration` / `audioClockResetCount` / `audioClockStaleCount` / `audioClockPtsDiscontinuityCount` | 时钟代 / 重锚 / 陈旧 / 同代 PTS 不连续重锚。 |
| `audioVideoDiffUs` | videoPts - audioPlaybackClockUs。 |
| `audioGeneration` / `audioLifecycleState` / `audioReconnectRecoveryCount` | 生命周期代 / 状态 / 音频重连恢复。 |
| `recordAudioPacketCount` | Recorder 成功 mux 的压缩 AAC 包数。 |
| `syncMaster` / `effectiveSyncMaster` | 请求同步主 / 实际同步主。 |

各层计数相互独立、单调；Audio OFF 时 demux/record 计数继续增长而 decode/PCM/sink 不变。
`audioRecordingIndependentOfPlayback=true` 由 Audio option/recording result JSON 返回，不是 `getStats()` 字段；`getStats()` 使用 `recordAudioPacketCount` 反映实际录音包进度。

---

## 12. Demo 使用

入口 Activity：`MediaPlayerActivity`。界面为全屏视频 + `playbackInfoTextView` + 悬浮控制按钮 + 可显示/隐藏的控制面板（SOURCE / RTSP TRANSPORT / LATENCY / PLAYER OPTIONS / THERMAL / CONTROL / DEBUG / RECORDING / SNAPSHOT）。

基本流程：

```text
1. 输入 RTSP/HTTP/HLS/RTMP/本地文件 URL。
2. 选择 TCP/UDP/Auto 与 Low/Balanced/Stable。
3. Create → Prepare → Start。
4. 需要 Thermal 时打开 THERMAL 面板（Palette/Gamma/Window/AGC）。
5. 需要声音时打开 CONTROL 面板的 Audio 开关。
6. 需要录制时 Record / Segment。
7. 需要截图时 Snapshot。
8. 测试结束 Stop → Release。
```

Demo 通过 `FFmpegPlayer` 完成播放、Surface、Thermal、录制、音频、重连和 Stats 操作，不持有 native handle，也不直接创建 `LiveAudioPcmSink`。`FFmpegNative` 的直接引用仅用于 FFmpeg Info、Debug-only lifetime stress 入口和兼容 palette 常量；Release stress 调用按 PH4 policy 被拒绝。

Intent 参数（可选）：`EXTRA_URL`、`EXTRA_HARDWARE_DECODE`、`EXTRA_RTSP_TRANSPORT`、`EXTRA_LATENCY_MODE`、`EXTRA_RENDER_MODE`（如 `"mediacodec_nv12_gl"`）。

---

## 13. 重要限制

- 不使用 ExoPlayer 作为播放主链路；`ffmpegplayer` 只负责 Live RTSP/网络专业播放。Local MP4/VOD 属未来 D 任务（Media3/ExoPlayer），不加入 NativePlayer。
- 音频监听为 **AAC（FFmpeg 软件解码）**；Opus/G711/AC3 等不在 Audio Phase 1 范围。不使用 MediaCodec AAC 解码。
- Audio Phase 1 无 AudioFocus / 蓝牙 / 耳机路由调优；无 time-stretch / 变速 / swr drift 补偿。
- RTSP UDP 低延迟可能出现花屏/丢帧（UDP 丢包正常风险）。
- MP4 remux 必须正常 stop 写 trailer 才完整；异常退出推荐 `.ts`。
- NV12 GL native snapshot 暂不支持（Demo 用 PixelCopy）；`mediacodec_surface`/`software_yuv_gl` 原生 snapshot 同样受限。
- `mediacodec_oes` 为 experimental / future zero-copy 路径（保留，不作为默认）。
- `softwareRenderedFrameCount` / `hardwareRenderedFrameCount` 为 legacy 计数（仅信息用途）。
- 真机验证：AAC 源为 paced HTTP MPEG-TS（`adb reverse`）；**真实 AAC RTSP endpoint 断线重连矩阵尚未执行**（实验室待验证项），其余生命周期矩阵（toggle×30、Pause/Resume×20、Surface×20、30 分钟长跑、Release race）已通过。
- Library 状态：**Architecture Freeze = YES**（L0~L6），**Runtime Freeze = PENDING**（需真实设备 + RTSP 矩阵后置 YES）。

---

## 14. 常见 JSON 示例

`getLatencyConfig`：

```json
{
  "success": true,
  "sourceType": "RTSP",
  "effectiveRtspTransport": "udp",
  "rtspTransport": "udp",
  "latencyMode": "low_latency",
  "probesize": 32768,
  "analyzeduration": 0,
  "maxDelayUs": 0,
  "reorderQueueSize": 0,
  "socketBufferSize": 102400,
  "fflagsNoBuffer": true,
  "avioDirect": true,
  "decoderThreadCount": 1,
  "enableFrameDrop": true,
  "dropLateFrameThresholdUs": 150000
}
```

`setAudioEnabled(true)` 成功（Audio ON）：

```json
{
  "success": true,
  "message": "audio option updated",
  "sourceHasAudio": true,
  "audioEnabled": true,
  "audioPlayable": true,
  "audioDecodeOpened": true,
  "audioCallbackSet": true,
  "audioRecordingIndependentOfPlayback": true,
  "audioPlayError": ""
}
```

`stopRecord` 成功（含 AAC 轨）：

```json
{
  "success": true,
  "message": "player remux recording stopped",
  "outputPath": "/storage/emulated/0/Android/data/com.example.motro/files/record_av_test.mp4",
  "videoPacketCount": 18360,
  "audioPacketCount": 28689,
  "durationUs": 1836096000,
  "sourceHasAudio": true,
  "audioStreamRecorded": true
}
```

---

## 15. 排错提示

- 启动崩溃且路径含 `base.apk!/lib/...so`：确认 `useLegacyPackaging true` 生效，重装 App。
- UDP 低延迟花屏：优先 TCP low latency 或 UDP balanced。
- 延迟变大：查看 `latencyMode`、`effectiveRtspTransport`、`droppedVideoFrameCount`、`lastVideoDelayUs`。
- 截图失败：确认 `hasLastFrame=true` 且父目录存在。
- mp4 录制打不开：确认调用过 `stopRecord` / `stop` 写 trailer。
- Audio 开关无声音：确认 `sourceHasAudio=true`、`audioDecodeOpened=true`、`audioSinkReady=true`、`audioPlayable=true`、`audioPlaybackClockValid=true`、`audioSinkWriteCount` 增长、`audioSinkWriteErrorCount` 不持续增长。
- Audio 有声音但视频卡顿/不同步：查看 `effectiveSyncMaster`（应为 audio）、`audioVideoDiffUs`（应围绕 0 抖动）、`audioClockPtsDiscontinuityCount`。
- Audio OFF 后仍在响：确认 `audioWorkerRunning=false`、`audioLifecycleState` 为 off/stopped、`audioSinkWriteCount` 不再增长。
- 硬解被 fallback：查看 `actualDecoderName` 与 `hardwareDecodeError`（decoder fallback）或 `renderFallbackReason`（renderer fallback），两者语义不同。
