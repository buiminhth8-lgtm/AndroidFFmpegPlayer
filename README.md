# Motro Android FFmpeg Player

Motro 当前是一套以 FFmpeg Native 为核心的 Android 音视频能力验证工程。项目目标是用 FFmpeg 完成拉流、探测、播放、截图、播放统计、RTSP 低延迟调优和播放时同路 remux 录制，不使用 ExoPlayer 作为播放主链路。

当前 Android module 是 `app`，Java 包名是 `com.example.motro`，应用 `applicationId` 是 `ccom.example.motro`。FFmpeg 头文件位于 `app/src/main/cpp/ffmpeg/include`，FFmpeg 动态库位于 `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}`。

## 当前能力

- FFmpeg JNI 基础信息查询：版本、编译参数、解码器、MediaCodec 解码器可用性。
- URL 基础探测：通过 FFmpeg API 实现简化 ffprobe，不依赖外部 `ffprobe` 可执行文件。
- Native 播放器：支持 RTSP、HLS、RTMP、HTTP 和本地文件等 FFmpeg 可识别输入源。
- 视频播放：`av_read_frame -> avcodec_send_packet -> avcodec_receive_frame -> sws_scale -> ANativeWindow`，渲染到 Android `Surface`。
- 播放时 remux 录制：复用同一路播放器已经打开的输入流，不重新打开 RTSP，不转码，不重新编码。
- 分片录制：按输出 pattern 和分片时长进行 remux 分段。
- 播放中截图：复用播放器最近一帧 RGBA 缓存，支持 `.png`，`.jpg/.jpeg` 依赖 FFmpeg 是否启用 MJPEG encoder。
- 播放状态和统计：可查询 stream、帧数、packet 数、录制状态、Surface 状态、低延迟参数、断流重连状态等。
- RTSP TCP/UDP 低延迟配置：支持 `tcp`、`udp`、`udp_multicast`、`auto`，支持 `low_latency`、`balanced`、`stable` 三档。
- Surface 安全处理：支持 Surface 销毁后 `clearPlayerSurface`，播放线程可继续解码并跳过渲染。

## 重要限制

- 当前播放主链路由 FFmpeg 实现，不使用 ExoPlayer 播放。`build.gradle` 中仍可见 Media3/ExoPlayer 依赖，但当前 `MediaPlayerActivity` 和 `FFmpegNative` 未使用 ExoPlayer。
- 当前 Java 层未提供完整 AudioTrack 封装类。Native 层已有音频流探测、音频解码状态、音频开关和回调状态接口；播放时 remux 录制音频不依赖 AudioTrack 是否启用。
- RTSP UDP 低延迟可能出现花屏或丢帧，这是 UDP 丢包和低缓冲的正常风险。
- MP4 remux 录制必须正常 stop 写入 trailer 后才完整；异常退出时推荐测试 `.ts`。
- 截图必须等待至少一帧视频成功解码并渲染或缓存后才能成功。
- 低延迟丢帧只影响播放显示，不影响 remux 录制，录制仍写原始 packet。

## 工程结构

```text
app/
  build.gradle
  src/main/AndroidManifest.xml
  src/main/java/com/example/motro/
    MediaPlayerActivity.java
    ffmpeg/FFmpegNative.java
  src/main/cpp/
    CMakeLists.txt
    native-ffmpeg-jni.cpp
    ffmpeg/include/
    native/
      NativePlayer.h/.cpp
      VideoRenderer.h/.cpp
      PlayerRemuxRecorder.h/.cpp
      SnapshotManager.h/.cpp
      PlayerOptions.h/.cpp
  src/main/jniLibs/
    arm64-v8a/
    armeabi-v7a/
    x86_64/
```

### Native 模块职责

| 文件 | 职责 |
| --- | --- |
| `native-ffmpeg-jni.cpp` | JNI 注册、Java 参数转换、NativePlayer handle 管理、FFmpeg 基础能力入口。 |
| `NativePlayer.*` | 打开输入源、查找流、创建解码器、播放线程、状态管理、Surface 绑定、统计、截图缓存、录制集成、断流重连。 |
| `VideoRenderer.*` | `ANativeWindow_fromSurface`、`ANativeWindow_lock`、RGBA 拷贝和 `unlockAndPost`。 |
| `PlayerRemuxRecorder.*` | 基于播放器当前 `AVFormatContext` 创建输出容器，复制 codecpar，写 header、packet、trailer，支持 `.ts`、`.mp4` 和分片。 |
| `SnapshotManager.*` | 保存最近一帧 RGBA 到 PNG/JPG。PNG 为内置实现；JPG 依赖 FFmpeg MJPEG encoder。 |
| `PlayerOptions.*` | RTSP transport、低延迟模式、FFmpeg open options、decoder 低延迟参数、单项 option 覆盖。 |

## 构建配置

Native 构建由 `app/src/main/cpp/CMakeLists.txt` 生成 `native-ffmpeg`：

- include：`app/src/main/cpp/ffmpeg/include`
- imported so：`avutil`、`swresample`、`swscale`、`avcodec`、`avformat`
- Android NDK 库：`log`、`android`
- ABI：`armeabi-v7a`、`arm64-v8a`、`x86_64`

`app/build.gradle` 关键配置：

- `externalNativeBuild.cmake.path = app/src/main/cpp/CMakeLists.txt`
- `jniLibs.srcDirs = ['src/main/jniLibs']`
- `packagingOptions.jniLibs.useLegacyPackaging true`
- `abiFilters "armeabi-v7a", "arm64-v8a", "x86_64"`

构建命令：

```powershell
.\gradlew.bat :app:assembleDebug
```


## AAR 打包和三方引用

项目新增 `ffmpegplayer` Android Library module，用于生成可被三方项目引用的 AAR。该模块复用现有 JNI/CMake/FFmpeg so，但只导出 `com.example.motro.ffmpeg.FFmpegNative`，不包含 demo `MediaPlayerActivity`，避免宿主应用被合入 launcher Activity 和 UI 依赖。

构建 release AAR：

```bash
./gradlew :ffmpegplayer:assembleRelease
```

也可以使用根工程别名任务：

```bash
./gradlew assembleFfmpegPlayerAar
```

构建需要 JDK 17。如果本机默认 Java 指向不含 `javac` 的运行时，可临时指定：

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk17 assembleFfmpegPlayerAar
```

产物路径：

```text
ffmpegplayer/build/outputs/aar/ffmpegplayer-release.aar
```

AAR 内包含：

- `classes.jar`：`FFmpegNative` Java API。
- `jni/{armeabi-v7a,arm64-v8a,x86_64}`：`native-ffmpeg` 以及 FFmpeg 动态库。
- `consumer-rules.pro`：保留 JNI 注册所需的 `FFmpegNative` 类和方法名。
- `AndroidManifest.xml`：仅声明网络访问权限。

三方项目直接引用本地 AAR：

```groovy
dependencies {
    implementation files("libs/ffmpegplayer-release.aar")
}
```

如需发布到本机 Maven 仓库：

```bash
./gradlew :ffmpegplayer:publishReleasePublicationToMavenLocal
```

三方项目再引用：

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation "com.example.motro:ffmpegplayer:1.0.0.6"
}
```

宿主项目使用示例：

```java
import com.example.motro.ffmpeg.FFmpegNative;

long handle = FFmpegNative.createPlayer();
FFmpegNative.setRtspTransport(handle, "tcp");
FFmpegNative.setPlayerLatencyMode(handle, "balanced");
```

## so 加载顺序

`FFmpegNative` 静态代码块按以下顺序加载：

```java
avutil
swresample
swscale
avcodec
avformat
native-ffmpeg
```

其中 `swresample` 当前按 optional 加载，以避免部分包体缺失时直接崩溃。

## Demo 使用

入口 Activity 是 `MediaPlayerActivity`。界面提供：

- URL 输入框。
- timeoutMs 输入框。
- Audio 开关。
- Reconnect 开关。
- RTSP transport：TCP、UDP、Auto。
- Latency mode：Low、Balanced、Stable。
- Create、Info、Probe、Prepare、Start、Pause、Stop、Release。
- Record、Segment、Stop Rec、Rec State。
- Snapshot、State、Stats、Reconnect/Latency State。
- Surface 清理和日志清理。

推荐基本流程：

```text
1. 输入 RTSP/HTTP/HLS/RTMP/本地文件 URL。
2. 选择 TCP/UDP/Auto。
3. 选择 Low/Balanced/Stable。
4. 点击 Create。
5. 点击 Prepare。
6. 点击 Start。
7. 需要录制时点击 Record 或 Segment。
8. 需要截图时点击 Snapshot。
9. 测试结束点击 Stop，再点击 Release。
```

## FFmpegNative API

所有返回 `String` 的 JNI 方法都返回 JSON 字符串。成功通常包含：

```json
{"success":true,"message":"ok"}
```

失败通常包含：

```json
{"success":false,"errorCode":-1,"errorMessage":"reason"}
```

FFmpeg API 错误会尽量通过 `av_strerror` 转成可读文本。

### 基础能力

| API | 含义 | 备注 |
| --- | --- | --- |
| `getFFmpegVersion()` | 返回 FFmpeg 版本字符串。 | 对应 `av_version_info()`。 |
| `getFFmpegBuildConfig()` | 返回 FFmpeg 编译参数。 | 对应 `avcodec_configuration()`。 |
| `getAvailableDecoders()` | 查询关键解码器是否存在。 | 返回 `h264`、`hevc`、`h264_mediacodec`、`hevc_mediacodec`。 |
| `getMediaCodecInfo()` | 查询 MediaCodec 解码器和 JNI 初始化状态。 | 包含 `jniInitialized`。当前不强制使用硬解。 |
| `probe(String url, int timeoutMs)` | 使用 FFmpeg API 探测 URL。 | 支持 RTSP，默认设置 `rtsp_transport=tcp`、timeout。返回 format、duration、bitrate、streams。 |
| `runDebugCommand(String[] args)` | 运行伪命令调试入口。 | 不调用外部 ffmpeg/ffprobe/ffplay 可执行文件。 |

`runDebugCommand` 支持：

| 命令 | 含义 |
| --- | --- |
| `-version` | 返回 FFmpeg version。 |
| `-buildconf` | 返回 FFmpeg build config。 |
| `-decoders` | 返回关键 decoder 可用性。 |
| `-probe <url>` | 调用内置 `probe`。 |
| `ffprobe <url>` | 调用内置 `probe`。 |
| `ffplay` | 返回不支持说明。 |
| `-latency-config` | 返回三档低延迟 profile 说明。 |
| `-sourceinfo <url>` | 判断 source type 并给出 RTSP 推荐 transport/mode。 |
| `-rtsp-low-latency-help` | 返回 TCP/UDP 低延迟说明。 |

### 播放器生命周期

| API | 含义 | 调用时机 |
| --- | --- | --- |
| `createPlayer()` | 创建 NativePlayer，返回 native handle。 | 播放前第一步。返回 `0` 表示创建失败。 |
| `setPlayerSurface(long handle, Surface surface)` | 绑定渲染 Surface。 | `prepare` 前后均可调用；Surface 变化时可重复调用。 |
| `preparePlayer(long handle, String url, int timeoutMs)` | 打开输入源、探测 stream、打开 video decoder、记录 audio 信息。 | `setRtspTransport`、`setPlayerLatencyMode`、`setPlayerOption` 应在此之前调用。 |
| `startPlayer(long handle)` | 启动播放线程。 | 需要先 `preparePlayer`，并已绑定 Surface。实时流 start 前会刷新输入以降低启动延迟。 |
| `pausePlayer(long handle)` | 暂停播放线程解码/渲染循环。 | 不释放 FFmpeg 资源。 |
| `stopPlayer(long handle)` | 停止播放线程并释放 FFmpeg 播放资源。 | 如正在录制，会自动停止录制并写 trailer。 |
| `releasePlayer(long handle)` | 释放播放器对象。 | 内部先 stop，再释放 Surface、录制器、缓存等。release 后 handle 不可继续使用。 |
| `clearPlayerSurface(long handle)` | 清理当前 NativeWindow。 | Surface destroyed 时调用，避免播放线程继续 lock 已销毁 Surface。 |

推荐调用顺序：

```java
long handle = FFmpegNative.createPlayer();
FFmpegNative.setRtspTransport(handle, "udp");
FFmpegNative.setPlayerLatencyMode(handle, "balanced");
FFmpegNative.setPlayerSurface(handle, holder.getSurface());
FFmpegNative.preparePlayer(handle, rtspUrl, 5000);
FFmpegNative.startPlayer(handle);

// Activity/Fragment 销毁
FFmpegNative.stopPlayer(handle);
FFmpegNative.clearPlayerSurface(handle);
FFmpegNative.releasePlayer(handle);
```

### 状态和统计

| API | 含义 | 主要字段 |
| --- | --- | --- |
| `getPlayerState(long handle)` | 返回播放器基础状态。 | `state`、`url`、`sourceHasVideo`、`sourceHasAudio`、stream index、codec、size、fps、error、reconnect 信息。 |
| `getPlayerStats(long handle)` | 返回播放器详细统计。 | packet/frame/render/drop/record/surface/snapshot/audio clock/video clock/latency config/reconnect 等。 |

播放器状态可能值：

```text
idle
preparing
prepared
playing
paused
reconnecting
stopping
stopped
error
released
```

`getPlayerStats` 常用字段：

| 字段 | 含义 |
| --- | --- |
| `readPacketCount` | `av_read_frame` 成功读取的 packet 数。 |
| `videoPacketCount` / `audioPacketCount` | 播放线程识别到的视频/音频 packet 数。 |
| `videoFrameCount` / `audioFrameCount` | 解码出的帧数统计。当前主要用于视频帧。 |
| `renderedFrameCount` | 成功渲染到 Surface 的帧数。 |
| `droppedVideoFrameCount` | 低延迟丢帧或渲染失败累计数。 |
| `recordVideoPacketCount` / `recordAudioPacketCount` | remux 录制写入的视频/音频 packet 数。 |
| `surfaceAttached` | 当前是否绑定可渲染 Surface。 |
| `hasLastFrame` | 是否已有最近一帧截图缓存。 |
| `audioEnabled` / `audioPlayable` / `audioDecodeOpened` | 音频播放相关状态。 |
| `audioClockUs` / `videoClockUs` | 当前音频/视频时间戳。 |
| `latencyMode` / `rtspTransport` / `effectiveRtspTransport` | 当前低延迟配置。 |
| `lastVideoDelayUs` | 视频相对 master clock 的延迟估计。 |

### 音频相关

| API | 含义 | 备注 |
| --- | --- | --- |
| `setAudioCallback(long handle, Object audioCallback)` | 设置 Java 音频回调对象状态。 | 当前项目没有独立 Java AudioTrack 封装类；Native 侧主要记录回调是否设置。 |
| `enableAudio(long handle, boolean enabled)` | 开关音频播放状态。 | 不影响 remux 录制音频；录制是否包含音频由源是否存在 audio stream 决定。 |

注意：

- 源没有音频时，`enableAudio(true)` 不应导致崩溃。
- 音频播放关闭或不可播放时，视频低延迟丢帧使用 wall clock，不使用 audio packet clock。
- remux 录制音频独立于 AudioTrack 播放状态。

### 录制 API

录制必须复用当前 NativePlayer 已打开的 `AVFormatContext` 和播放线程读取到的 packet，不重新打开 RTSP，不转码，不重新编码。

| API | 含义 | 备注 |
| --- | --- | --- |
| `startPlayerRecord(long handle, String outputPath)` | 开始同路 remux 录制。 | `outputPath` 支持 `.ts`、`.mp4`，推荐优先测试 `.ts`。 |
| `startPlayerSegmentRecord(long handle, String outputPattern, int segmentDurationSec)` | 开始分片 remux 录制。 | `outputPattern` 建议包含 `%03d`，例如 `record_segment_%03d.ts`。 |
| `startPlayerRecordWithConfig(long handle, String outputPathOrPattern, String format, int segmentDurationSec)` | 按配置开始 remux 录制。 | `format` 支持 `mp4`、`mkv/matroska`、`ts/mpegts`、`mov`、`webm`、`flv` 等；`segmentDurationSec > 0` 时按时长分片。 |
| `stopPlayerRecord(long handle)` | 停止录制但不停止播放。 | 写 trailer、关闭输出、返回 packet 统计。 |
| `getPlayerRecordState(long handle)` | 查询录制状态。 | 包含 outputPath、format、video/audio packet count、waiting keyframe、分片统计等。 |

录制状态可能值：

```text
idle
starting
waiting_keyframe
recording
stopping
stopped
error
released
```

关键行为：

- 如果源有 video stream，录制开始后等待视频关键帧，再正式写入视频和音频 packet。
- 等关键帧期间不写音频，避免文件开头时间轴异常。
- 源无音频时可生成纯视频文件。
- 源有音频但音频播放关闭或失败时，仍尽量录入音频 packet。
- 播放停止时如果正在录制，会自动 stop recorder。
- MP4/MOV 默认启用 fragmented MP4：`movflags=frag_keyframe+empty_moov+default_base_moof`，并在写 packet 后 flush，异常退出时已写 fragment 更容易恢复播放。
- `segmentDurationSec=300` 表示每 5 分钟切一个文件；如果 pattern 包含 `%03d` 会按序号替换，否则自动在文件名后追加序号。

推荐路径：

```java
String tsPath = new File(context.getExternalFilesDir(null), "record_av_test.ts").getAbsolutePath();
String mp4Path = new File(context.getExternalFilesDir(null), "record_av_test.mp4").getAbsolutePath();
```

推荐配置型录制：

```java
// 单个 fragmented MP4 文件
String oneMp4 = new File(context.getExternalFilesDir(null), "record_av_test.mp4").getAbsolutePath();
FFmpegNative.startPlayerRecordWithConfig(handle, oneMp4, "mp4", 0);

// 每 5 分钟一个 Matroska 文件
String mkvPattern = new File(context.getExternalFilesDir(null), "record_%03d.mkv").getAbsolutePath();
FFmpegNative.startPlayerRecordWithConfig(handle, mkvPattern, "mkv", 300);
```

### 截图 API

| API | 含义 | 备注 |
| --- | --- | --- |
| `takePlayerSnapshot(long handle, String outputPath)` | 保存播放器最近一帧 RGBA。 | 支持 `.png`、`.jpg`、`.jpeg`。PNG 内置支持；JPG 依赖 FFmpeg MJPEG encoder。 |

行为说明：

- 截图不重新打开 RTSP。
- 截图不另起解码链路。
- 如果还没有视频帧，返回 `no video frame available`。
- 父目录不存在会返回错误。
- 推荐写入 `context.getExternalFilesDir(null)`，不需要额外存储权限。

示例：

```java
String snapshotPath = new File(context.getExternalFilesDir(null), "snapshot.png").getAbsolutePath();
String result = FFmpegNative.takePlayerSnapshot(handle, snapshotPath);
```

### 断流重连 API

| API | 含义 | 备注 |
| --- | --- | --- |
| `setPlayerReconnectOptions(long handle, boolean enabled, int maxRetryCount, int retryDelayMs)` | 设置断流重连策略。 | `maxRetryCount` 会限制在 0 到 100；`retryDelayMs` 会限制在 100 到 60000。 |
| `getPlayerReconnectState(long handle)` | 查询重连状态。 | 包含 enabled、reconnecting、attemptCount、successCount、lastReconnectError 等。 |

当前重连是基础策略：`av_read_frame` 出错后按配置重开输入，不做复杂网络质量评估。

### RTSP transport 和低延迟 API

| API | 含义 | 备注 |
| --- | --- | --- |
| `setPlayerRtspTransport(long handle, String transport)` | 旧接口，设置 RTSP transport。 | 保留兼容；建议新代码使用 `setRtspTransport`。 |
| `getPlayerRtspTransportState(long handle)` | 查询旧 transport 状态。 | 返回 mode、currentTransport、switchPending 等。 |
| `setRtspTransport(long handle, String transport)` | 设置 RTSP transport。 | 支持 `tcp`、`udp`、`udp_multicast`、`auto`。应在 `preparePlayer` 前调用；播放中可尝试切换，录制中禁止切换。 |
| `setPlayerLatencyMode(long handle, String mode)` | 设置延迟模式。 | 支持 `low_latency`、`balanced`、`stable`。必须在 `preparePlayer` 前调用。 |
| `setPlayerOption(long handle, String key, String value)` | 覆盖单个播放器参数。 | 必须在 `preparePlayer` 前调用；未知 key 返回错误。 |
| `getPlayerLatencyConfig(long handle)` | 返回当前低延迟配置 JSON。 | 包含 sourceType、transport、timeout、buffer、drop 等字段。 |

`setPlayerOption` 支持 key：

| key | value | 含义 |
| --- | --- | --- |
| `rtsp_transport` | `tcp` / `udp` / `udp_multicast` / `auto` | 等同设置 transport。 |
| `latency_mode` | `low_latency` / `balanced` / `stable` | 等同设置低延迟档位。 |
| `probesize` | long | FFmpeg probe size。 |
| `analyzeduration` | long | FFmpeg analyze duration，单位微秒。 |
| `max_probe_packets` | int | 最大 probe packet 数。 |
| `max_delay` | long | FFmpeg 最大延迟，单位微秒。 |
| `reorder_queue_size` | int | RTSP UDP 重排队列大小。 |
| `buffer_size` | int | socket buffer size。 |
| `stimeout` | long | 打开超时，单位微秒。 |
| `timeout` | long | 打开超时，单位微秒。 |
| `rw_timeout` | long | 读写超时，单位微秒。 |
| `decoder_thread_count` | int | 视频 decoder thread count；`0` 表示 FFmpeg 自动。 |
| `enable_frame_drop` | boolean | 是否启用播放端丢旧帧。 |
| `drop_late_frame_threshold_us` | long | 丢帧阈值，单位微秒。 |
| `fflags_nobuffer` | boolean | 是否设置 `fflags=nobuffer` 和 `AVFMT_FLAG_NOBUFFER`。 |
| `avio_direct` | boolean | 是否设置 `avioflags=direct`。 |
| `skip_non_ref` | boolean | 是否跳过非参考帧，默认不启用。 |

三档默认策略：

| 模式 | 适用场景 | 特征 |
| --- | --- | --- |
| `low_latency` | 局域网摄像头、云台控制、实时预览。 | 小 probe、小 buffer、低 analyze、开启 nobuffer/direct、decoder 单线程、启用丢旧帧。 |
| `balanced` | 默认推荐。 | 延迟和稳定折中，UDP 有小重排队列，TCP 启用 `tcp_nodelay`。 |
| `stable` | 公网、Wi-Fi 抖动、录制优先。 | 更大 buffer/probe/analyze，不主动丢帧，稳定优先。 |

示例：

```java
long handle = FFmpegNative.createPlayer();
FFmpegNative.setRtspTransport(handle, "udp");
FFmpegNative.setPlayerLatencyMode(handle, "low_latency");
FFmpegNative.setPlayerOption(handle, "drop_late_frame_threshold_us", "150000");
FFmpegNative.preparePlayer(handle, rtspUrl, 3000);
FFmpegNative.startPlayer(handle);
```

## 常见 JSON 示例

`getPlayerLatencyConfig`：

```json
{
  "success": true,
  "sourceType": "RTSP",
  "effectiveRtspTransport": "udp",
  "rtspTransport": "udp",
  "latencyMode": "low_latency",
  "openTimeoutUs": 3000000,
  "readTimeoutUs": 3000000,
  "probesize": 32768,
  "analyzeduration": 0,
  "maxProbePackets": 32,
  "maxDelayUs": 0,
  "reorderQueueSize": 0,
  "socketBufferSize": 102400,
  "fflagsNoBuffer": true,
  "avioDirect": true,
  "lowDelayDecode": true,
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

## 推荐测试用例

1. RTSP TCP + `balanced`：确认基础播放稳定。
2. RTSP UDP + `low_latency`：确认延迟降低，观察是否花屏。
3. RTSP TCP + `low_latency`：确认 TCP 低延迟参数生效。
4. RTSP TCP + `stable`：确认公网或弱网更稳定。
5. 源有音频时录制 `.ts`：确认输出包含音频。
6. 源无音频时录制 `.ts`：确认可生成纯视频文件。
7. 播放中截图 `.png`：确认不重新拉流且图片可打开。
8. 播放中分片录制：确认文件按 pattern 递增。
9. Surface 销毁后调用 `clearPlayerSurface`：确认不崩溃。
10. 播放中查看 `getPlayerStats`：确认 dropped frame 只影响渲染，不影响 record packet count。

## 排错提示

- 如果启动崩溃且路径包含 `base.apk!/lib/...so`，检查 `useLegacyPackaging true` 是否生效，并重新卸载安装 App。
- 如果 UDP low latency 花屏，优先切换 TCP low latency 或 UDP balanced。
- 如果延迟变大，查看 `getPlayerStats` 中 `latencyMode`、`effectiveRtspTransport`、`droppedVideoFrameCount`、`lastVideoDelayUs`。
- 如果截图失败，先确认 `hasLastFrame=true`，并确认输出父目录存在。
- 如果 mp4 录制文件打不开，确认调用了 `stopPlayerRecord` 或 `stopPlayer` 让 FFmpeg 写 trailer。
- 如果 prepare 后再设置 latency mode 返回错误，这是预期行为；请 stop/release 后重新 create/prepare，或在 prepare 前设置。

## 后续建议

- 补完整 Java AudioTrack 播放封装，并让 Native 输出 PCM 到 Java 回调。
- 增加端到端延迟测量。
- 增加 RTSP 自动 UDP/TCP 切换策略。
- 增强断流自动重连和状态恢复。
- 增加录制分片索引和异常恢复。
- 增加 MediaCodec 硬解码低延迟模式。
## V11 FFmpeg MediaCodec Surface 硬解码

V11 增加 FFmpeg `h264_mediacodec` / `hevc_mediacodec` 硬解码到 Android `Surface` 的渲染路径。默认仍然是 `software_rgba`，不会自动启用硬解码。

### 开启方式

```java
long handle = FFmpegNative.createPlayer();

FFmpegNative.setRtspTransport(handle, "udp");
FFmpegNative.setPlayerLatencyMode(handle, "ultra_low_latency");
FFmpegNative.setPlayerOption(handle, "ultra_latency_level", "normal");

FFmpegNative.setHardwareDecode(handle, true);
FFmpegNative.setHardwareRenderMode(handle, "mediacodec_surface");

// 硬解低延迟预览建议关闭音频播放
FFmpegNative.enableAudio(handle, false);

FFmpegNative.setPlayerSurface(handle, holder.getSurface());
FFmpegNative.preparePlayer(handle, rtspUrl, 5000);
FFmpegNative.startPlayer(handle);

String stats = FFmpegNative.getPlayerStats(handle);
```

也可以使用 `setPlayerOption`：

```java
FFmpegNative.setPlayerOption(handle, "enable_hardware_decode", "true");
FFmpegNative.setPlayerOption(handle, "hardware_render_mode", "mediacodec_surface");
```

新增 Java API：

```java
public static native String setHardwareDecode(long handle, boolean enabled);
public static native String setHardwareRenderMode(long handle, String mode);
```

### 渲染模式

| mode | 说明 |
| --- | --- |
| `software_rgba` | 兼容性最好。FFmpeg 软件解码后通过 `sws_scale` 转 RGBA，再用 `ANativeWindow_lock` / RGBA copy / `unlockAndPost` 渲染。支持 native RGBA snapshot。 |
| `mediacodec_surface` | 使用 FFmpeg MediaCodec decoder 输出到 `Surface`，绕过 `sws_scale` 和 RGBA memcpy，低延迟更好。第一版不支持 native RGBA snapshot。 |

支持 decoder：

| 视频编码 | 优先 decoder |
| --- | --- |
| H.264 | `h264_mediacodec` |
| H.265 / HEVC | `hevc_mediacodec` |

硬解失败会自动 fallback 到软件解码路径，并把 `renderMode` 回到 `software_rgba`。常见 fallback 场景包括：设备没有对应 `*_mediacodec` decoder、`av_mediacodec_default_init` 失败、`avcodec_open2` 硬解失败。

### 低延迟建议

- RTSP 优先测试 `udp + ultra_low_latency + mediacodec_surface`。
- HEVC 源优先测试 `hevc_mediacodec`。
- 如果设备 MediaCodec 兼容性不好，关闭 `enableHardwareDecode` 回到 `software_rgba`。
- 当前仍由 FFmpeg demux / remux，不是 Android `MediaPlayer`，也不接入 ExoPlayer。

### 截图限制

`mediacodec_surface` 模式下 native 不再持有 RGBA 帧，因此第一版 `takePlayerSnapshot` 会返回：

```json
{
  "success": false,
  "errorMessage": "Snapshot is not supported in mediacodec_surface mode yet. Use software_rgba mode or implement PixelCopy."
}
```

当前 Demo 的截图按钮已经做了兼容处理：先调用 native `takePlayerSnapshot`；如果返回 `mediacodec_surface` 不支持 native RGBA snapshot，则自动使用 Java `PixelCopy` 从当前 `Surface` 复制画面并保存为 PNG/JPG。native API 本身仍保持保守，不会从 `AV_PIX_FMT_MEDIACODEC` 帧读取 YUV，也不会为了截图强制回退软件解码。

### 统计字段

`getPlayerStats` 增加：

```json
{
  "enableHardwareDecode": true,
  "renderMode": "mediacodec_surface",
  "requestedDecoderName": "hevc_mediacodec",
  "actualDecoderName": "hevc_mediacodec",
  "usingHardwareDecoder": true,
  "hardwareDecodeFallbackUsed": false,
  "hardwareDecodeError": "",
  "videoCodecName": "hevc",
  "decoderName": "hevc_mediacodec",
  "frameFormat": "mediacodec",
  "hardwareDecodedFrameCount": 100,
  "hardwareRenderedFrameCount": 100,
  "hardwareDroppedFrameCount": 0,
  "softwareDecodedFrameCount": 0,
  "softwareRenderedFrameCount": 0,
  "swsScaleEnabled": false,
  "lastSwsScaleCostUs": -1,
  "lastRenderCopyCostUs": -1,
  "snapshotSupported": false
}
```

`getPlayerLatencyConfig` 增加：

```json
{
  "enableHardwareDecode": true,
  "renderMode": "mediacodec_surface",
  "hardwareDecodeAllowFallback": true,
  "preferredH264Decoder": "h264_mediacodec",
  "preferredHevcDecoder": "hevc_mediacodec"
}
```

`runDebugCommand(new String[]{"-hardware-decode-help"})` 可查看硬解开启方式、两种渲染模式区别、fallback 行为和 snapshot 限制。

## V12 OpenGL ES YUV 渲染和 Java MediaCodec 实验

### software_yuv_gl 渲染模式

V12 增加 `software_yuv_gl` 作为软件解码优化路径：

```java
long handle = FFmpegNative.createPlayer();
FFmpegNative.setHardwareDecode(handle, false);
FFmpegNative.setHardwareRenderMode(handle, "software_yuv_gl");
FFmpegNative.setPlayerSurface(handle, holder.getSurface());
FFmpegNative.preparePlayer(handle, url, 5000);
FFmpegNative.startPlayer(handle);
```

也可以用 option：

```java
FFmpegNative.setPlayerOption(handle, "hardware_render_mode", "software_yuv_gl");
```

行为说明：

- FFmpeg 仍负责 demux 和软件解码。
- 对 `YUV420P` / `YUVJ420P` 帧，native 直接上传 Y/U/V 三个平面到 OpenGL ES 纹理，通过 shader 转 RGB 并渲染到同一个 `Surface`。
- 不走 `sws_scale`，不做 RGBA frame copy。
- 如果帧格式不是当前 GL 路径支持的 8-bit 420 planar YUV，自动 fallback 到原 `software_rgba` 渲染。
- native RGBA snapshot 在 `software_yuv_gl` 下不支持；Demo 按钮会自动使用 Java `PixelCopy` 截图。

`getPlayerStats` 新增/可观察字段：

```json
{
  "renderMode": "software_yuv_gl",
  "swsScaleEnabled": false,
  "snapshotSupported": false,
  "yuvGlRenderedFrameCount": 100,
  "yuvGlFallbackFrameCount": 0,
  "lastSwsScaleCostUs": -1,
  "lastRenderCopyCostUs": 900
}
```

### Java MediaCodec 独立解码链路实验

新增 `com.example.motro.ffmpeg.JavaMediaCodecExperiment`，用于和 FFmpeg NativePlayer 做设备硬解行为对照。它使用 Android `MediaExtractor + MediaCodec + Surface`，不接入 ExoPlayer，不接入 Android `MediaPlayer`，也不影响当前 FFmpeg 播放/录制主链路。

示例：

```java
JavaMediaCodecExperiment experiment = new JavaMediaCodecExperiment();
String start = experiment.start(context, videoUrlOrFileUri, holder.getSurface());
String stats = experiment.getStats();
String stop = experiment.stop();
```

限制：

- 这是实验链路，不提供 remux 录制、RTSP 低延迟参数、packet drop/frame drop、AudioTrack 封装。
- 输入源支持取决于 Android `MediaExtractor`，RTSP 兼容性不作为主链路保证。
- 用途是快速验证设备 Java MediaCodec decoder、Surface 渲染和基础耗时/计数。
