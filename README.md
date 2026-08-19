# Motro Android FFmpeg Player

Motro 是一套以 FFmpeg Native 为核心的 Android 专业 RTSP 音视频播放/验证工程，不使用 ExoPlayer 作为播放主链路。

当前 Android module 为 `app`，Java 包名 `com.example.motro`，`applicationId` 为 `ccom.example.motro`。
FFmpeg 头文件在 `app/src/main/cpp/ffmpeg/include`，动态库在 `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}`。

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
- Surface 安全处理：Surface 销毁后可 `clearPlayerSurface`，播放线程继续解码并跳过渲染；Surface 生命周期与 Audio 完全解耦。

---

## 2. RenderMode 与能力矩阵

`setHardwareRenderMode(handle, mode)` 支持：

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

**Hardware Decode ON 默认路由**（`MediaPlayerActivity.applyDecodeModeOption`）：

```java
FFmpegNative.setHardwareDecode(handle, true);
FFmpegNative.setHardwareRenderMode(handle, "mediacodec_nv12_gl");
```

`setHardwareDecode(true)` 之后再显式 `setHardwareRenderMode(...)`（顺序不可反，因为关闭硬解会重置软件 render mode）。Create / Prepare / Start / reconnect 全部复用同一路由，不会覆盖回 `mediacodec_surface`。Hardware Decode OFF 时显式回 `software_yuv_gl`。

---

## 3. Thermal 处理链

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

Thermal API（全部返回 JSON 字符串）：

```java
FFmpegNative.setThermalEnabled(long handle, boolean enabled);
FFmpegNative.setThermalPalette(long handle, int palette);          // ORIGINAL=0 / WHITE_HOT=1 / IRONBOW=2
FFmpegNative.setThermalAgcEnabled(long handle, boolean enabled);
FFmpegNative.setThermalGamma(long handle, float gamma);            // 0.5 ~ 2.0
FFmpegNative.setThermalWindow(long handle, float black, float white); // 0<=black<white<=1
```

常量：`FFmpegNative.THERMAL_PALETTE_ORIGINAL / WHITE_HOT / IRONBOW`。

---

## 4. Audio Phase 1（Live Audio Monitoring）

### 4.1 架构与固定契约

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

- 输入不固定：任何 AAC 输出格式（如 16 kHz / 2ch / FLTP）统一由 swr 转换为固定 48 kHz / Stereo / S16 / Interleaved。
- 输入格式变化（reconnect / source change）：swr 按 decoded frame identity（sample format / rate / channels / layout mask）重建，输出契约不变。
- `audioEnabled` = 用户实时监听请求（纯意图，不表示 source/decoder/recorder 状态）。
- `audioPlayable` = 完整监听链路可用：`audioEnabled && sourceHasAudio && audioDecodeOpened && audioSinkReady`。
- Recording 与监听完全独立：Recorder 在 decode 之前收到原始压缩 AAC packet，Audio OFF / PCM 丢弃 / AudioTrack 失败均不影响录制。

### 4.2 AudioTrack

- 由 `LiveAudioPcmSink` 独立持有（不依赖 Activity/View，未来可由 LiveSession 持有）。
- 固定配置：`48000 Hz / CHANNEL_OUT_STEREO / ENCODING_PCM_16BIT / MODE_STREAM`，buffer 取自 `AudioTrack.getMinBufferSize`（低延迟）。
- `AudioTrack.write` 使用 **WRITE_NON_BLOCKING** + 250 ms 最大 drain 窗口 + 2 ms 取消轮询；生命周期命令推进 Java epoch 并在 pause/flush/release 前禁用写入，受控取消返回独立码（`audioSinkControlledCancelCount`），不计入真实错误。
- 生命周期：ON 时懒创建+play；OFF/Pause/Reconnect 时 pause+flush；Stop/Release 时 release。AudioTrack 失败只降级监听，不影响 Video / Recording。

### 4.3 Audio Playback Clock 与 A/V Sync

- 时钟源：`AudioTrack.getPlaybackHeadPosition()`（worker 每 block ~20 ms 读取一次，**不在 video 线程做 JNI 查询**）。
- 32-bit playback head 经 uint32 delta 扩展为单调 64-bit played frames（wrap 安全）。
- 时钟模型：

  ```
  audioPlaybackClockUs = baseMediaPtsUs + playedFrames * 1_000_000 / 48000
  ```

  `baseMediaPtsUs` = 当前 generation 首批有效 PCM 的媒体 PTS；`playedFrames` = 自 base 起已播放帧数。不使用 wall clock 作为媒体时间基准。
- `audioPlaybackClockValid` = true 仅当：audio enabled、decoder/SWR 正常、AudioTrack operational、有有效媒体 PTS、playback head 可查、当前 generation 有输出。
- 时钟失效/陈旧（500 ms 未刷新、AudioTrack 失败、OFF/Pause/Reconnect/Stop）→ `effectiveSyncMaster=video` 回退。
- **A/V Sync（Video follows Audio）**：`effectiveSyncMaster=audio` 仅当 `syncMaster=audio` 且 clock valid；Video 早于音频 → 有界等待（≤150 ms）；Video 晚于音频 → 沿用现有 low-latency 丢帧策略。不实现 time-stretch / swr 补偿。
- 同代 PCM PTS 不连续（>20 ms 洞，如队列丢块导致）→ 重锚 playback-head 映射（`audioClockPtsDiscontinuityCount`），防止 drift 单向累积。
- 所有 OFF/ON、Pause、Stop、callback 替换、reconnect/transport 不连续、解码格式变化都会推进 `audioGeneration` 并 reset clock base，杜绝旧 PCM / 旧时钟复用。

### 4.4 生命周期语义（已冻结）

- **Audio OFF**：停 PCM 生产 → 推进 generation → flush 队列+AudioTrack → invalidate/reset clock → stop+join worker → 请求 playback 线程 reset decoder/SWR。RTSP 与 Video 继续，Recorder 继续录 AAC。
- **Audio ON**：推进新 generation → playback 线程边界 reset decoder/SWR → 新 sink epoch + 启动 worker（仅播放激活时）→ 从当前 live edge 恢复，不补播 OFF 期间音频；不 reopen RTSP、不重建 Video MediaCodec。
- **Pause**：推进 generation、flush 队列+AudioTrack、invalidate clock、stop+join worker；realtime 下 playback 线程继续按 live edge 排空压缩包并继续 Recorder，但不喂 decoder/renderer，避免 socket 积压与 Resume 追赶。Resume 从 fresh sink/worker epoch 开始，无残留 PCM。
- **Reconnect**：断流时先隔离旧 audio generation（flush 队列+AudioTrack、reset clock/head、清 validity），reconnect 架构重建 decoder/SWR，成功且 Audio ON 时恢复 sink/worker 并以首批有效 PCM 重建时钟。Recorder 的 reconnect/remux 语义不变。
- **Stop / Release**：确定性 shutdown——发布 Stop → 推进 generation + flush → 唤醒/关闭队列 → join worker（不持 queue/sink/player/registry 锁）→ join playback → 最终 flush → 停止 Recorder → 释放 decoder/SWR/input →（Release 时）sink Release + 删除 JNI GlobalRef。重复 Release / stale handle 安全（Fix 4）。

---

## 5. Fallback 语义（重要）

- **Decoder fallback**：`hevc_mediacodec → software hevc` 才置 `hardwareDecodeFallbackUsed=true`、`usingHardwareDecoder=false`、`decodeBackend="software"`。
- **Renderer fallback**：NV12 GL 渲染失败 → 回退现有 RGBA（sws）渲染器，`usingHardwareDecoder=true`、`hardwareDecodeFallbackUsed=false`、`renderFallbackUsed=true`、`renderer="rgba_nativewindow"`、`renderFallbackReason="nv12_gl render failed"`。
- **Audio fallback**：任何 Audio 失败（decoder/SWR/JNI/AudioTrack/时钟陈旧）只降级监听：`audioPlayable=false`、`audioPlaybackClockValid=false`、`effectiveSyncMaster=video`。绝不触发 Video decoder/renderer fallback、RTSP reopen 或 Recorder 中断。

两者/三者完全独立，绝不互相冒充。

---

## 6. 工程结构

```text
app/
  src/main/
    java/com/example/motro/
      MediaPlayerActivity.java
      ffmpeg/FFmpegNative.java
      ffmpeg/LiveAudioPcmSink.java        # AudioTrack 持有者（Audio Phase 1）
    cpp/
      CMakeLists.txt
      native-ffmpeg-jni.cpp
      ffmpeg/include/
      native/
        NativePlayer.h/.cpp
        NativeYuvGlRenderer.h/.cpp        # software_yuv_gl（Phase 1 GL + Thermal）
        NativeNv12GlRenderer.h/.cpp       # mediacodec_nv12_gl（硬解 NV12 GL + Thermal）
        NativeOesRenderer.h/.cpp          # mediacodec_oes（experimental）
        VideoRenderer.h/.cpp              # software_rgba（ANativeWindow）
        PlayerRemuxRecorder.h/.cpp
        SnapshotManager.h/.cpp
        PlayerOptions.h/.cpp              # RenderMode / 低延迟配置
        ThermalConfig.h/.cpp              # ThermalConfig / 校验
        ThermalPaletteLut.h/.cpp          # 共享 Ironbow LUT
    jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/
ffmpegplayer/                             # AAR library（复用 JNI/so，导出 FFmpegNative）
```

### Native 模块职责

| 文件 | 职责 |
| --- | --- |
| `native-ffmpeg-jni.cpp` | JNI 注册、opaque handle 管理（Fix 4）、FFmpeg 基础能力入口、调试命令（`-player-lifetime-stress`、`-audio-backpressure-test`）。 |
| `NativePlayer.*` | 输入/解码/渲染线程、状态机、Thermal 调度、Audio 全链路（decode→SWR→queue→worker→sink→clock→A/V sync）、统计、重连、录制集成。 |
| `NativeNv12GlRenderer.*` | 硬解 NV12 → OpenGL ES（Original / White Hot / Ironbow / Gamma / Window），EGL 生命周期。 |
| `NativeYuvGlRenderer.*` | 软件 YUV420P → OpenGL ES（Phase 1 Thermal）。 |
| `NativeOesRenderer.*` | MediaCodec → SurfaceTexture → OES（experimental）。 |
| `VideoRenderer.*` | RGBA → `ANativeWindow`（软件 fallback）。 |
| `PlayerOptions.*` | RenderMode、RTSP transport、低延迟 profile、syncMaster。 |
| `ThermalConfig.*` / `ThermalPaletteLut.*` | Thermal 配置与共享 Ironbow LUT。 |
| `PlayerRemuxRecorder.*` / `SnapshotManager.*` | remux 录制（含 AAC `aac_adtstoasc`）与截图。 |

---

## 7. 构建

Native 由 `app/src/main/cpp/CMakeLists.txt` 生成 `native-ffmpeg`；ABI 为 `armeabi-v7a / arm64-v8a / x86_64`。

```powershell
.\gradlew.bat :app:assembleDebug
```

### AAR 打包与三方引用

```bash
./gradlew :ffmpegplayer:assembleRelease       # 或别名任务 assembleFfmpegPlayerAar
./gradlew :ffmpegplayer:publishReleasePublicationToMavenLocal
```

产物：`ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar`（classes.jar + jni so + consumer-rules.pro）。AAR 只导出 `com.example.motro.ffmpeg.FFmpegNative`，不包含 Demo Activity。

构建需要 JDK 17。如默认 Java 不含 javac，可临时指定：

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk17 assembleFfmpegPlayerAar
```

已知：`:app:testDebugUnitTest` / `:app:connectedDebugAndroidTest` 在 KAPT 阶段因模板测试桩 `@error.NonExistentClass` 失败（历史遗留，与播放器代码无关）；生产 debug 构建通过。

---

## 8. so 加载顺序

`FFmpegNative` 静态块按序加载：

```java
avutil
swresample   // optional
swscale
avcodec
avformat
native-ffmpeg
```

---

## 9. FFmpegNative API

所有返回 `String` 的 JNI 方法返回 JSON；成功 `{"success":true,...}`，失败 `{"success":false,"errorCode":..,"errorMessage":"reason"}`。

### 基础能力

| API | 说明 |
| --- | --- |
| `getFFmpegVersion()` / `getFFmpegBuildConfig()` | FFmpeg 版本 / 编译参数。 |
| `getAvailableDecoders()` | 关键 decoder 可用性（h264/hevc/h264_mediacodec/hevc_mediacodec）。 |
| `getMediaCodecInfo()` | MediaCodec 与 JNI 初始化状态。 |
| `probe(url, timeoutMs)` | 内置 ffprobe 探测。 |
| `runDebugCommand(String[])` | 伪命令入口（`-version`、`-probe <url>`、`ffprobe <url>`、`-sourceinfo`、`-player-lifetime-stress`、`-audio-backpressure-test <ms>` 等）。 |

### 播放器生命周期

| API | 说明 |
| --- | --- |
| `createPlayer()` | 创建播放器，返回 opaque handle（Fix 4：逻辑 ID，非指针）。 |
| `setPlayerSurface(handle, Surface)` | 绑定渲染 Surface。 |
| `preparePlayer(handle, url, timeoutMs)` | 打开输入、选 decoder、配置 renderer、打开 audio decoder（如有）。 |
| `startPlayer / pausePlayer / stopPlayer / releasePlayer` | 播放控制（Pause 冻结画面+音频；Release 幂等、join worker 后释放资源）。 |
| `clearPlayerSurface(handle)` | 清理 NativeWindow（Surface 销毁时）。 |

推荐顺序：`createPlayer → setRtspTransport/setPlayerLatencyMode/setPlayerOption → setPlayerSurface → preparePlayer → startPlayer`；销毁：`stopPlayer → clearPlayerSurface → releasePlayer`。

### 状态与统计

- `getPlayerState(handle)`：`state`、URL、codec、尺寸、fps、error、reconnect 等。
- `getPlayerStats(handle)`：详见下方字段。

播放器状态：`idle / preparing / prepared / playing / paused / reconnecting / stopping / stopped / error / released`。

### Audio API（Audio Phase 1）

| API | 说明 |
| --- | --- |
| `setAudioCallback(handle, Object sink)` | 注册 Java Audio PCM sink（`LiveAudioPcmSink` 实例）。Native 缓存 GlobalRef + `onAudioPcm` / `onAudioControl` / `getPlaybackHeadFrames` 方法 ID。 |
| `enableAudio(handle, enabled)` | 用户实时监听开关。ON：新 generation + worker 从 live edge 恢复；OFF：flush 队列+AudioTrack、stop/join worker、invalidate clock。不 reopen RTSP、不重建 Video MediaCodec、不影响 Recorder。 |

Demo（`MediaPlayerActivity`）在 `ensurePlayer()` 时创建 `LiveAudioPcmSink` 并注册；CONTROL 面板 `Audio` 开关映射到 `enableAudio`。

### 录制 API

| API | 说明 |
| --- | --- |
| `startPlayerRecord(handle, outputPath)` | 同路 remux 录制（`.ts`/`.mp4`）。 |
| `startPlayerSegmentRecord(handle, pattern, segmentSec)` | 分片录制（pattern 含 `%03d`）。 |
| `startPlayerRecordWithConfig(handle, pathOrPattern, format, segmentSec)` | 配置型录制（mp4/mkv/ts/mov/webm/flv 等；`segmentSec>0` 分片）。 |
| `stopPlayerRecord(handle)` | 停止录制、写 trailer、返回统计。 |
| `getPlayerRecordState(handle)` | 录制状态 JSON。 |

- 视频与音频均为**原始压缩包 remux**（`av_interleaved_write_frame`），无解码/编码。
- MP4/MOV 默认 `movflags=frag_keyframe+empty_moov+default_base_moof`；MP4/MOV 压缩 AAC 经 `aac_adtstoasc` bitstream filter 转换 ADTS 帧（TS→MP4 兼容），不重编码。
- Audio ON/OFF、Pause、Reconnect、PCM 溢出均不改变 recorder stream mapping；`recordAudioPacketCount` 只在 mux 成功后递增。
- 停止录制必须写 trailer 才完整，异常退出推荐 `.ts`。

### 截图 API

- `takePlayerSnapshot(handle, outputPath)`：RGBA 路径原生保存（PNG 内置，JPG 依赖 MJPEG encoder）；GL/direct-Surface 路径返回不支持后由 Demo 用 `PixelCopy` 兜底；NV12 GL native snapshot 暂不支持。

### 断流重连 API

| API | 说明 |
| --- | --- |
| `setPlayerReconnectOptions(handle, enabled, maxRetry, retryDelayMs)` | `maxRetry=-1` 无限重连。 |
| `setPlayerEventListener(handle, listener)` | 事件：`reconnect_disconnected/reconnecting/waiting_source/reconnect_success/reconnect_exhausted`。 |
| `getPlayerReconnectState(handle)` | 重连状态 JSON。 |

Reconnect 时 Audio 独立处理：旧 generation 隔离（flush 队列+AudioTrack、clock invalidate），新流重新发现 audio stream、重建 decoder/SWR、恢复 sink/worker/clock；Recorder 保持打开。

### RTSP transport / 低延迟 API

| API | 说明 |
| --- | --- |
| `setRtspTransport(handle, transport)` | `tcp/udp/udp_multicast/auto`。 |
| `setPlayerLatencyMode(handle, mode)` | `low_latency/balanced/stable`。 |
| `setPlayerOption(handle, key, value)` | 覆盖单项参数（probesize、max_delay、buffer_size、stimeout、enable_frame_drop、fflags_nobuffer、skip_non_ref、sync_master 等）。 |
| `getPlayerLatencyConfig(handle)` | 当前低延迟配置 JSON。 |
| `setHardwareDecode(handle, enabled)` / `setHardwareRenderMode(handle, mode)` | 硬解开关与渲染模式。 |
| `getPlayerRtspTransportState(handle)` | 旧 transport 状态（兼容）。 |

---

## 10. getPlayerStats 常用字段

### 视频 / 渲染 / Thermal

| 字段 | 含义 |
| --- | --- |
| `renderMode` / `decodeBackend` / `frameOutputType` / `renderer` | 请求模式 / 实际解码后端 / 实际帧输出 / 实际渲染器。 |
| `requestedRenderer` / `renderFallbackUsed` / `renderFallbackReason` | 请求渲染器与渲染 fallback 状态。 |
| `usingHardwareDecoder` / `hardwareDecodeFallbackUsed` / `hardwareDecodeError` | 硬解使用与 decoder fallback。 |
| `hardwareDecodedFrameCount` / `softwareDecodedFrameCount` | 硬解 / 软件解码输出帧数（语义严格区分）。 |
| `nv12GlRenderedFrameCount` / `nv12GlFallbackFrameCount` / `nv12GlNoSurfaceFrameCount` | NV12 GL 渲染 / 回退 / 无 Surface 帧数。 |
| `yuvGlRenderedFrameCount` / `yuvGlFallbackFrameCount` | 软件 YUV GL 渲染 / 回退帧数。 |
| `renderedFrameCount` / `droppedVideoFrameCount` / `frameDropBeforeRenderCount` | 总渲染帧 / 丢帧 / 渲染前显式丢弃。 |
| `swsScaleEnabled` / `swsScaleInvocationCount` / `lastSwsScaleCostUs` | sws 是否实际启用（NV12 GL 正常为 false / 0 / -1）。 |
| `lastNv12GlRenderCostUs` / `avgNv12GlRenderCostUs` / `maxNv12GlRenderCostUs` | NV12 GL 渲染耗时（upload+draw+swap）。 |
| `thermalEnabled` / `thermalPalette` / `thermalGamma` / `thermalBlackPoint` / `thermalWhitePoint` | Thermal 配置。 |
| `thermalAgcEnabled` / `thermalAgcValid` / `thermalAgcBlackPoint` / `thermalAgcWhitePoint` | AGC 状态与 effective window。 |
| `thermalRenderMode` / `thermalInputType` / `thermalWindowApplied` | 实际 Thermal 模式与输入类型。 |
| `nv12AgcUpdateCount` / `nv12AgcInvalidCount` | NV12 AGC 更新/无效计数。 |

### Audio（Audio Phase 1）

| 字段 | 含义 |
| --- | --- |
| `sourceHasAudio` / `audioStreamIndex` / `audioCodec` / `audioSampleRate` / `audioChannels` / `audioSampleFormat` | 输入流元数据（stream 发现事实）。 |
| `audioEnabled` | 用户实时监听请求（纯意图）。 |
| `audioPlayable` | 完整监听链路可用（enabled && sourceHasAudio && decodeOpened && sinkReady）。 |
| `audioDecodeOpened` / `audioCallbackSet` / `audioSinkReady` | decoder 打开 / sink 已注册 / sink 方法就绪。 |
| `audioPacketCount` / `audioPacketBytes` | demux 到的压缩音频包（`audioPacketCount` 与 decoded 帧数不保证 1:1）。 |
| `audioDecodedFrameCount`（别名 `audioFrameCount`）/ `audioDecodedSampleCount` / `audioDecodeErrorCount` | 解码帧 / 样本 / 错误。 |
| `audioPcmBlockCount` / `audioPcmSampleCount` / `audioPcmByteCount` | SWR 输出块 / 每声道样本 / 字节（S16×2ch）。 |
| `audioOutputSampleRate` / `audioOutputChannels` / `audioOutputSampleFormat` / `audioOutputInterleaved` | 固定输出契约（48000 / 2 / s16 / true）。 |
| `audioSwrReconfigureCount` / `audioResampleErrorCount` | SWR 重建次数（稳定源接近 1）/ 重采样错误。 |
| `audioQueueDurationUs` / `audioQueueBlockCount` / `audioQueueBytes` / `audioQueueHighWatermarkUs` | PCM 队列当前深度 / 峰值。 |
| `audioQueueDropCount` / `audioQueueDroppedSampleCount` / `audioQueueFlushCount` / `audioQueueGeneration` | 队列溢出丢块 / 丢样本 / flush 次数 / generation。 |
| `audioWorkerRunning` / `audioWorkerStartCount` / `audioWorkerJoinCount` / `audioWorkerStaleBlockCount` | worker 运行态 / 启动 / join / 陈旧块。 |
| `audioWorkerConsumedBlockCount` / `audioWorkerConsumedSampleCount` / `audioWorkerConsumedByteCount` | worker 消费统计。 |
| `audioSinkWriteCount` / `audioSinkWrittenByteCount` / `audioSinkWriteErrorCount` / `audioSinkLastErrorCode` | AudioTrack 写入成功 / 字节 / 错误 / 最后错误码。 |
| `audioSinkControlledCancelCount` / `audioSinkRestartCount` | 受控取消（生命周期 epoch）/ sink 重启次数。 |
| `audioClockUs` | **LEGACY**：最后压缩音频包 PTS 镜像，非播放时钟。 |
| `audioPlaybackClockUs` / `audioPlaybackClockValid` / `audioPlaybackHeadFrames` | 真实 AudioTrack playback-head 时钟 / 有效性 / 64-bit 已播帧。 |
| `audioClockGeneration` / `audioClockResetCount` / `audioClockStaleCount` / `audioClockPtsDiscontinuityCount` | 时钟代 / 重锚 / 陈旧 / 同代 PTS 不连续重锚次数。 |
| `audioVideoDiffUs` | videoPts - audioPlaybackClockUs（A/V 同步偏差）。 |
| `audioGeneration` / `audioLifecycleState` / `audioReconnectRecoveryCount` | 生命周期代 / 状态 / 音频重连恢复次数。 |
| `audioRecordingIndependentOfPlayback` | 恒为 true：录音与监听完全独立。 |
| `recordAudioPacketCount` | Recorder 成功 mux 的压缩 AAC 包数（非监听统计）。 |
| `syncMaster` / `effectiveSyncMaster` | 请求同步主 / 实际同步主（audio 仅当 clock valid 且非 stale）。 |

各层计数相互独立、单调；Audio OFF 时 demux/record 计数继续增长而 decode/PCM/sink 不变。

---

## 11. Demo 使用

入口 Activity：`MediaPlayerActivity`。界面为全屏视频 + `playbackInfoTextView` + 悬浮控制按钮 + 可显示/隐藏的控制面板（SOURCE / RTSP TRANSPORT / LATENCY / PLAYER OPTIONS / THERMAL / CONTROL / DEBUG / RECORDING / SNAPSHOT）。

基本流程：

```text
1. 输入 RTSP/HTTP/HLS/RTMP/本地文件 URL。
2. 选择 TCP/UDP/Auto 与 Low/Balanced/Stable。
3. Create → Prepare → Start。
4. 需要 Thermal 时打开 THERMAL 面板（Palette/Gamma/Window/AGC）。
5. 需要声音时打开 CONTROL 面板的 Audio 开关（enableAudio；Create/Prepare/Start 时会同步应用）。
6. 需要录制时 Record / Segment。
7. 需要截图时 Snapshot。
8. 测试结束 Stop → Release。
```

Intent 参数（可选）：`EXTRA_URL`、`EXTRA_HARDWARE_DECODE`、`EXTRA_RTSP_TRANSPORT`、`EXTRA_LATENCY_MODE`、`EXTRA_RENDER_MODE`（如 `"mediacodec_nv12_gl"`）。

---

## 12. 重要限制

- 不使用 ExoPlayer 作为播放主链路；NativePlayer 只负责 Live RTSP/网络专业播放。Local MP4/VOD 属未来 D 任务（Media3/ExoPlayer），不加入 NativePlayer。
- 音频监听为 **AAC（FFmpeg 软件解码）**；Opus/G711/AC3 等不在 Audio Phase 1 范围。不使用 MediaCodec AAC 解码。
- Audio Phase 1 无 AudioFocus / 蓝牙 / 耳机路由调优；无 time-stretch / 变速 / swr drift 补偿。
- RTSP UDP 低延迟可能出现花屏/丢帧（UDP 丢包正常风险）。
- MP4 remux 必须正常 stop 写 trailer 才完整；异常退出推荐 `.ts`。
- NV12 GL native snapshot 暂不支持（Demo 用 PixelCopy）；`mediacodec_surface`/`software_yuv_gl` 原生 snapshot 同样受限。
- `mediacodec_oes` 为 experimental / future zero-copy 路径（保留，不作为默认）。
- `softwareRenderedFrameCount` / `hardwareRenderedFrameCount` 为 legacy 计数（仅信息用途）。
- 真机验证：AAC 源为 paced HTTP MPEG-TS（`adb reverse`）；**真实 AAC RTSP endpoint 断线重连矩阵尚未执行**（实验室待验证项），其余生命周期矩阵（toggle×30、Pause/Resume×20、Surface×20、30 分钟长跑、Release race）已通过。

---

## 13. 常见 JSON 示例

`getPlayerLatencyConfig`：

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

`enableAudio` 成功（Audio ON）：

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

`stopPlayerRecord` 成功（含 AAC 轨）：

```json
{
  "success": true,
  "message": "player remux recording stopped",
  "outputPath": "/storage/emulated/0/Android/data/ccom.example.motro/files/record_av_test.mp4",
  "videoPacketCount": 18360,
  "audioPacketCount": 28689,
  "durationUs": 1836096000,
  "sourceHasAudio": true,
  "audioStreamRecorded": true
}
```

---

## 14. 排错提示

- 启动崩溃且路径含 `base.apk!/lib/...so`：确认 `useLegacyPackaging true` 生效，重装 App。
- UDP 低延迟花屏：优先 TCP low latency 或 UDP balanced。
- 延迟变大：查看 `latencyMode`、`effectiveRtspTransport`、`droppedVideoFrameCount`、`lastVideoDelayUs`。
- 截图失败：确认 `hasLastFrame=true` 且父目录存在。
- mp4 录制打不开：确认调用过 `stopPlayerRecord` / `stopPlayer` 写 trailer。
- Audio 开关无声音：确认 `sourceHasAudio=true`、`audioDecodeOpened=true`、`audioSinkReady=true`、`audioPlayable=true`、`audioPlaybackClockValid=true`、`audioSinkWriteCount` 增长、`audioSinkWriteErrorCount` 不持续增长。
- Audio 有声音但视频卡顿/不同步：查看 `effectiveSyncMaster`（应为 audio）、`audioVideoDiffUs`（应围绕 0 抖动、无持续单方向增长）、`audioClockPtsDiscontinuityCount`。
- Audio OFF 后仍在响：确认 `audioWorkerRunning=false`、`audioLifecycleState` 为 off/stopped、`audioSinkWriteCount` 不再增长。
- 硬解被 fallback：查看 `actualDecoderName` 与 `hardwareDecodeError`（decoder fallback）或 `renderFallbackReason`（renderer fallback），两者语义不同。
