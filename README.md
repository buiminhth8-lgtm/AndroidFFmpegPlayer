# Motro Android FFmpeg Player

Motro 是一套以 FFmpeg Native 为核心的 Android 音视频播放/验证工程，不使用 ExoPlayer 作为播放主链路。

当前 Android module 为 `app`，Java 包名 `com.example.motro`，`applicationId` 为 `ccom.example.motro`。
FFmpeg 头文件在 `app/src/main/cpp/ffmpeg/include`，动态库在 `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}`。

---

## 1. 当前能力

- FFmpeg JNI 基础能力：版本、编译参数、decoder 可用性、MediaCodec 状态、URL probe。
- Native 播放器：RTSP / HLS / RTMP / HTTP / 本地文件。
- **硬解主链（Phase 2 Revised）**：

  ```
  RTSP/HEVC
  → hevc_mediacodec
  → CPU-visible AV_PIX_FMT_NV12
  → NativeNv12GlRenderer（OpenGL ES）
  → SurfaceView
  ```

  正常路径不经过 `sws_scale` / RGBA CPU 转换 / `ANativeWindow` RGBA 渲染。

- **Thermal 主链（NV12 GL）**：

  ```
  NV12 Y
  → Range Normalize（full/limited）
  → Manual / AGC Window
  → Gamma
  → White Hot / Ironbow
  → OpenGL ES → SurfaceView
  ```

- 播放时同路 remux 录制（不重开 RTSP、不转码、不重编码）与分片录制。
- 截图：RGBA 路径复用最近帧缓存；GL/direct-Surface 路径由 Demo 用 `PixelCopy`；NV12 GL native snapshot 暂不支持。
- 播放统计：stream / 帧数 / packet / 渲染 / 丢帧 / 录制 / Surface / 时钟 / 延迟 / 重连 / Thermal 状态。
- RTSP TCP/UDP 低延迟配置与三档 profile。
- Surface 安全处理：Surface 销毁后可 `clearPlayerSurface`，播放线程继续解码并跳过渲染。

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
- `software_yuv_gl` 与 `mediacodec_nv12_gl` 共用同一 `ThermalConfig` 与 `computeAgcWindow` 算法（Phase 1 语义不变）。

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

## 4. Fallback 语义（重要）

- **Decoder fallback**：`hevc_mediacodec → software hevc` 才置 `hardwareDecodeFallbackUsed=true`、`usingHardwareDecoder=false`、`decodeBackend="software"`。
- **Renderer fallback**：NV12 GL 渲染失败 → 回退现有 RGBA（sws）渲染器，`usingHardwareDecoder=true`、`hardwareDecodeFallbackUsed=false`、`renderFallbackUsed=true`、`renderer="rgba_nativewindow"`、`renderFallbackReason="nv12_gl render failed"`。

两者完全独立，绝不互相冒充。

---

## 5. 工程结构

```text
app/
  src/main/
    java/com/example/motro/
      MediaPlayerActivity.java
      ffmpeg/FFmpegNative.java
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
| `native-ffmpeg-jni.cpp` | JNI 注册、handle 管理、FFmpeg 基础能力入口。 |
| `NativePlayer.*` | 输入/解码/渲染线程、状态机、Thermal 调度、统计、重连、录制集成。 |
| `NativeNv12GlRenderer.*` | 硬解 NV12 → OpenGL ES（Original / White Hot / Ironbow / Gamma / Window），EGL 生命周期。 |
| `NativeYuvGlRenderer.*` | 软件 YUV420P → OpenGL ES（Phase 1 Thermal）。 |
| `NativeOesRenderer.*` | MediaCodec → SurfaceTexture → OES（experimental）。 |
| `VideoRenderer.*` | RGBA → `ANativeWindow`（软件 fallback）。 |
| `PlayerOptions.*` | RenderMode、RTSP transport、低延迟 profile。 |
| `ThermalConfig.*` / `ThermalPaletteLut.*` | Thermal 配置与共享 Ironbow LUT。 |
| `PlayerRemuxRecorder.*` / `SnapshotManager.*` | remux 录制与截图。 |

---

## 6. 构建

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

---

## 7. so 加载顺序

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

## 8. FFmpegNative API

所有返回 `String` 的 JNI 方法返回 JSON；成功 `{"success":true,...}`，失败 `{"success":false,"errorCode":..,"errorMessage":"reason"}`。

### 基础能力

| API | 说明 |
| --- | --- |
| `getFFmpegVersion()` / `getFFmpegBuildConfig()` | FFmpeg 版本 / 编译参数。 |
| `getAvailableDecoders()` | 关键 decoder 可用性（h264/hevc/h264_mediacodec/hevc_mediacodec）。 |
| `getMediaCodecInfo()` | MediaCodec 与 JNI 初始化状态。 |
| `probe(url, timeoutMs)` | 内置 ffprobe 探测。 |
| `runDebugCommand(String[])` | 伪命令入口（`-version`、`-probe <url>`、`ffprobe <url>`、`-sourceinfo` 等）。 |

### 播放器生命周期

| API | 说明 |
| --- | --- |
| `createPlayer()` | 创建播放器，返回 handle。 |
| `setPlayerSurface(handle, Surface)` | 绑定渲染 Surface。 |
| `preparePlayer(handle, url, timeoutMs)` | 打开输入、选 decoder、配置 renderer。 |
| `startPlayer / pausePlayer / stopPlayer / releasePlayer` | 播放控制。 |
| `clearPlayerSurface(handle)` | 清理 NativeWindow（Surface 销毁时）。 |

推荐顺序：`createPlayer → setRtspTransport/setPlayerLatencyMode/setPlayerOption → setPlayerSurface → preparePlayer → startPlayer`；销毁：`stopPlayer → clearPlayerSurface → releasePlayer`。

### 状态与统计

- `getPlayerState(handle)`：`state`、URL、codec、尺寸、fps、error、reconnect 等。
- `getPlayerStats(handle)`：详见下方字段。

播放器状态：`idle / preparing / prepared / playing / paused / reconnecting / stopping / stopped / error / released`。

### 录制 API

| API | 说明 |
| --- | --- |
| `startPlayerRecord(handle, outputPath)` | 同路 remux 录制（`.ts`/`.mp4`）。 |
| `startPlayerSegmentRecord(handle, pattern, segmentSec)` | 分片录制（pattern 含 `%03d`）。 |
| `startPlayerRecordWithConfig(handle, pathOrPattern, format, segmentSec)` | 配置型录制（mp4/mkv/ts/mov/webm/flv 等；`segmentSec>0` 分片）。 |
| `stopPlayerRecord(handle)` | 停止录制、写 trailer、返回统计。 |
| `getPlayerRecordState(handle)` | 录制状态 JSON。 |

MP4/MOV 默认 `movflags=frag_keyframe+empty_moov+default_base_moof`；停止录制必须写 trailer 才完整，异常退出推荐 `.ts`。

### 截图 API

- `takePlayerSnapshot(handle, outputPath)`：RGBA 路径原生保存（PNG 内置，JPG 依赖 MJPEG encoder）；GL/direct-Surface 路径返回不支持后由 Demo 用 `PixelCopy` 兜底；NV12 GL native snapshot 暂不支持。

### 断流重连 API

| API | 说明 |
| --- | --- |
| `setPlayerReconnectOptions(handle, enabled, maxRetry, retryDelayMs)` | `maxRetry=-1` 无限重连。 |
| `setPlayerEventListener(handle, listener)` | 事件：`reconnect_disconnected/reconnecting/waiting_source/reconnect_success/reconnect_exhausted`。 |
| `getPlayerReconnectState(handle)` | 重连状态 JSON。 |

### RTSP transport / 低延迟 API

| API | 说明 |
| --- | --- |
| `setRtspTransport(handle, transport)` | `tcp/udp/udp_multicast/auto`。 |
| `setPlayerLatencyMode(handle, mode)` | `low_latency/balanced/stable`。 |
| `setPlayerOption(handle, key, value)` | 覆盖单项参数（probesize、max_delay、buffer_size、stimeout、enable_frame_drop、fflags_nobuffer、skip_non_ref 等）。 |
| `getPlayerLatencyConfig(handle)` | 当前低延迟配置 JSON。 |
| `setHardwareDecode(handle, enabled)` / `setHardwareRenderMode(handle, mode)` | 硬解开关与渲染模式。 |
| `getPlayerRtspTransportState(handle)` | 旧 transport 状态（兼容）。 |

---

## 9. getPlayerStats 常用字段

| 字段 | 含义 |
| --- | --- |
| `renderMode` / `decodeBackend` / `frameOutputType` / `renderer` | 请求模式 / 实际解码后端 / 实际帧输出 / 实际渲染器。 |
| `requestedRenderer` / `renderFallbackUsed` / `renderFallbackReason` | 请求渲染器与渲染 fallback 状态。 |
| `usingHardwareDecoder` / `hardwareDecodeFallbackUsed` | 硬解使用与 decoder fallback。 |
| `hardwareDecodedFrameCount` / `softwareDecodedFrameCount` | 硬解 / 软件解码输出帧数（语义严格区分，与 CPU-visible 无关）。 |
| `nv12GlRenderedFrameCount` / `nv12GlFallbackFrameCount` | NV12 GL 渲染 / 回退帧数。 |
| `yuvGlRenderedFrameCount` / `yuvGlFallbackFrameCount` | 软件 YUV GL 渲染 / 回退帧数。 |
| `renderedFrameCount` / `droppedVideoFrameCount` | 总渲染帧 / 丢帧。 |
| `swsScaleEnabled` / `lastSwsScaleCostUs` | sws 是否实际启用（NV12 GL 正常为 false / -1）。 |
| `lastNv12GlRenderCostUs` / `avgNv12GlRenderCostUs` / `maxNv12GlRenderCostUs` | NV12 GL 渲染耗时（upload+draw+swap）。 |
| `thermalEnabled` / `thermalPalette` / `thermalGamma` / `thermalBlackPoint` / `thermalWhitePoint` | Thermal 配置。 |
| `thermalAgcEnabled` / `thermalAgcValid` / `thermalAgcBlackPoint` / `thermalAgcWhitePoint` | AGC 状态与 effective window。 |
| `thermalRenderMode` / `thermalInputType` | 实际 Thermal 模式（normal/white_hot/ironbow）与输入类型（`nv12_y`/`yuv_planes`/`oes_luminance`）。 |
| `thermalWindowApplied` | 当前是否应用 Window。 |
| `nv12AgcUpdateCount` / `nv12AgcInvalidCount` | NV12 AGC 更新/无效计数。 |

---

## 10. Demo 使用

入口 Activity：`MediaPlayerActivity`。界面为全屏视频 + `playbackInfoTextView` + 悬浮控制按钮 + 可显示/隐藏的控制面板（SOURCE / RTSP / LATENCY / PLAYER OPTIONS / THERMAL / CONTROL / DEBUG / RECORDING / SNAPSHOT）。

基本流程：

```text
1. 输入 RTSP/HTTP/HLS/RTMP/本地文件 URL。
2. 选择 TCP/UDP/Auto 与 Low/Balanced/Stable。
3. Create → Prepare → Start。
4. 需要 Thermal 时打开 THERMAL 面板（Palette/Gamma/Window/AGC）。
5. 需要录制时 Record / Segment。
6. 需要截图时 Snapshot。
7. 测试结束 Stop → Release。
```

Intent 参数（可选）：`EXTRA_URL`、`EXTRA_HARDWARE_DECODE`、`EXTRA_RTSP_TRANSPORT`、`EXTRA_LATENCY_MODE`、`EXTRA_RENDER_MODE`（如 `"mediacodec_nv12_gl"`）。

---

## 11. 重要限制

- 不使用 ExoPlayer 作为播放主链路（`build.gradle` 中历史 Media3/ExoPlayer 依赖已被清理）。
- Java 层暂无完整 AudioTrack 封装；Native 已有音频探测/解码状态/开关接口；remux 录制音频独立于 AudioTrack。
- RTSP UDP 低延迟可能出现花屏/丢帧（UDP 丢包正常风险）。
- MP4 remux 必须正常 stop 写 trailer 才完整；异常退出推荐 `.ts`。
- NV12 GL native snapshot 暂不支持（Demo 用 PixelCopy）；`mediacodec_surface`/`software_yuv_gl` 原生 snapshot 同样受限。
- `mediacodec_oes` 为 experimental / future zero-copy 路径（保留，不作为默认）。
- `softwareRenderedFrameCount` / `hardwareRenderedFrameCount` 为 legacy 计数（仅信息用途），最终 Stats freeze 可再收口。
- 真机验证项（HD 分辨率、padded stride、彩色流、reconnect 长时间运行等）见各发布记录，未在本仓库留存切分报告。

---

## 12. 常见 JSON 示例

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

`takePlayerSnapshot` 成功：

```json
{
  "success": true,
  "message": "snapshot saved",
  "outputPath": "/storage/emulated/0/Android/data/ccom.example.motro/files/snapshot.png",
  "width": 1280,
  "height": 720,
  "ptsUs": 1234567,
  "format": "png"
}
```

`stopPlayerRecord` 成功：

```json
{
  "success": true,
  "message": "player remux recording stopped",
  "outputPath": "/storage/emulated/0/Android/data/ccom.example.motro/files/record_av_test.ts",
  "videoPacketCount": 1000,
  "audioPacketCount": 800,
  "durationUs": 10000000
}
```

---

## 13. 排错提示

- 启动崩溃且路径含 `base.apk!/lib/...so`：确认 `useLegacyPackaging true` 生效，重装 App。
- UDP 低延迟花屏：优先 TCP low latency 或 UDP balanced。
- 延迟变大：查看 `latencyMode`、`effectiveRtspTransport`、`droppedVideoFrameCount`、`lastVideoDelayUs`。
- 截图失败：确认 `hasLastFrame=true` 且父目录存在。
- mp4 录制打不开：确认调用过 `stopPlayerRecord` / `stopPlayer` 写 trailer。
- prepare 后设置 latency mode 返回错误：属预期，stop/release 后重新 create/prepare。
- 硬解被 fallback：查看 `actualDecoderName` 与 `hardwareDecodeError`（decoder fallback）或 `renderFallbackReason`（renderer fallback），两者语义不同。
