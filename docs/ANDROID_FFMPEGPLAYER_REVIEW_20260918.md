# AndroidFFmpegPlayer 最新源码审阅报告

**审阅日期：2026-09-18｜源码提交：049e39b729d70e993846fea79c4d489891d72508**  
**范围：Demo、Java 门面、JNI、C++ 播放链、渲染、音频、录制、截图、诊断、测试与构建交付。**

## 01｜总体结论

这份工程已经不是仅包装 FFmpeg 调用的播放 Demo，而是一套以实时视频预览为中心、包含软硬解、多渲染后端、音频监听、断流恢复、压缩码流录像和诊断工具的播放器实现。最近两项工作有真实代码成果：**Recorder 已通过独立有界 Packet Queue 与 Remux Thread 隔离逐包写盘；网络打开、探测、读取也增加了单调时钟 deadline，并加强了首次 404 和重连时的音频代次清理。**［E06、E07、E17、E19］

本轮不建议继续以“再拆多少类、再减多少行”为目标。优先事项应转为：**生命周期互锁、录制时间戳正确性、画面比例契约，以及真实 AAC 断线重连验收。** NativePlayer.cpp 仍有 6,245 行，但它的风险不只是体积，而是生命周期、音频、渲染和事件回调在同一控制流程中的交互。

本次得出的三个最重要判断：

- **已经完成且值得保留：**独立 Recorder 工作线程、输入参数快照、有界 PCM 队列、JNI 在途操作寿命守卫、Surface generation、诊断有效性门禁，以及不把未执行故障矩阵写成 PASS 的测试报告。
- **已经发现需要修复：**Recorder 独立归零 PTS/DTS 和各流首时间戳；多 AAC 流覆盖单个 BSF 指针；分段文件模板直接进入可变参数格式化；Demo 在后台动作中读写 View。前两项通过链接真实 Recorder 的主机端探针获得了直接观测。
- **尚不能宣称完成：**Android/NDK 整体构建、真实 MediaCodec/EGL 运行、带有效 AAC 数据的 32 场景故障矩阵、崩溃后录像可读性，以及真实相机到屏幕端到端延迟。

**验收建议：可继续作为受控实时预览项目的基础版本，不宜直接标记为“通用播放器与全部弱网音视频场景已验证完成”。** 以下结论均区分“源码确认”“本次主机验证”和“仓库历史记录”。

## 02｜源码基线、范围与证据规则

上传文件为 `d64efee5-e27b-4d0d-a1cc-2642962a8c86.zip`，大小 104,338,903 字节；包含 2,240 个归档条目，解压内容合计 211,005,157 字节。SHA-256 为：

```text
9b87e5a4c3ef5219434104818a2964022821a1c68e913b45296dfab41aa6ca23
```

Git HEAD 为 `049e39b`，提交说明为 `test(rtsp): validate AAC reconnect matrix`，提交时间为 2026-09-17 09:39:16 +08:00。**提交标题不等于全部矩阵已通过，实际结果以测试材料为准。**

本次完成项目自有 Java/C++ 源码清单统计、核心调用链精读、构建配置与随包二进制检查，执行可独立运行的主机测试，并编写仅用于审阅的边界探针。FFmpeg 公共头文件、历史 build 输出和缓存不按项目自有业务代码逐行评审。原生产源码没有被修改。

证据分为四类：

| 标记 | 含义 | 不能据此推出的结论 |
|---|---|---|
| 源码确认 | 当前提交存在明确实现或可达条件分支 | 不能等同于真机必然出现相同现象 |
| 主机验证 | 本次实际编译并运行测试或探针 | 不能替代 Android/JNI/EGL/真实 FFmpeg 封装验证 |
| 仓库记录 | 随包文档描述的既往测试 | 不是本次重新执行的设备结果 |
| 待验证风险 | 由调用关系或边界条件推导 | 必须用针对性测试继续确认 |

文中［E01］等指向配套《源码证据索引》中的原文件路径和物理行号。独立探针、测试命令与原始日志放在交付包 `validation/`，统计数据放在 `inventory/`。

## 03｜模块结构与代码规模

工程实际包含两个 Gradle 模块：`:app` 和 `:ffmpegplayer`。Demo 依赖播放器 Library；Library 自身包含 JNI、C++、FFmpeg 头文件和 ARM 预编译动态库，没有反向依赖 Demo。［E01］

```text
:app
  MediaPlayerActivity / LatencyStatsFormatter
  Debug: RtspMatrixActivity
       |
       v
:ffmpegplayer
  FFmpegPlayer -> FFmpegNative -> JNI handle registry
       |                              |
  LiveAudioPcmSink                 NativePlayer
       ^                         /     |      \
       |                 Renderers   Recorder  Snapshot
       |                       FFmpeg / Android NDK
```

项目自有代码的统计口径为物理行，包含注释和空白；排除第三方 FFmpeg 头文件：

| 范围 | 文件数 | 物理行数 | 说明 |
|---|---:|---:|---|
| Demo main Java | 2 | 2,198 | Activity 与指标格式化 |
| Demo debug Java | 1 | 175 | 真实故障矩阵入口 |
| Library main Java | 3 | 770 | 门面、JNI 声明、PCM Sink |
| Library main C++/头文件 | 28 | 15,681 | 播放、渲染、音频、录像、诊断 |
| Demo Java 测试 | 2 | 350 | 静态统计 15 个 @Test，未由 Gradle 执行 |
| Library C++ 测试及替身 | 9 | 1,051 | 主机测试、Fake FFmpeg |

主生产源码合计 **34 个文件、18,824 行**；其中 main 部分 33 个文件、18,649 行，debug 入口另计 175 行。另有两个 Python 工具文件共 489 行、143 个随包 FFmpeg 头文件，以及 10 个 ARM FFmpeg SO，共 33,516,336 字节。统计明细可在 inventory JSON 中复核。

核心文件规模：NativePlayer.cpp 6,245 行；MediaPlayerActivity.java 1,692 行；JNI 1,289 行；NativeOesRenderer.cpp 1,268 行；PlayerRemuxRecorder.cpp 1,221 行；NativeNv12GlRenderer.cpp 939 行；PlayerOptions.cpp 786 行；NativeYuvGlRenderer.cpp 756 行。

这些数字说明复杂度主要集中在 Native 播放编排，而不是 Java 多层模板。Java 门面仅 371 行；不能把先前宿主工程中的 Adapter 膨胀结论直接套用到本仓库。

## 04｜Java / JNI 接口与生命周期

### 4.1 正常调用方式

公开入口是 `FFmpegPlayer`，它持有 native handle、LiveAudioPcmSink 和内部事件桥。常规调用为：创建实例、设置 listener、绑定 Surface、配置 transport/latency/decode、prepare、start，最后 stop/release。返回值主要是 JSON 字符串，暂未建立完整强类型异步结果体系。［E02、E29］

```text
宿主线程 / Demo 工作线程
  -> FFmpegPlayer.prepare(url, timeoutMs)
  -> FFmpegNative.preparePlayer(handle, ...)
  -> acquirePlayer / PlayerOperationGuard
  -> NativePlayer.prepare
  -> stop 旧会话 -> 打开输入 -> 探测 -> 打开解码器
  -> Prepared 或 WaitingSource / Error

start -> 创建播放线程 -> 解封装/解码/渲染
stop  -> 发出停止信号 -> join -> 清理输入/音频/录制
release -> 句柄失效 -> JNI 等待在途操作 -> 销毁资源
```

必须区分“API 返回 success”“输入已经 Prepared”“播放线程已经启动”“首个视频帧真正完成提交”。首次 404 的特定策略下，prepare 可以返回 `success:true`，但同时带 `state:waiting_source` 和 `pendingReconnect:true`；不能将其当作输入已经成功打开。［E04］

### 4.2 JNI 寿命守卫是正确改进，但不是操作串行器

JNI 注册表按逻辑 handle 保存 PlayerEntry；每次调用通过 activeOperations 计数保护 NativePlayer 的存活，release 先标记 closing 并移出注册表，再在不持有全局注册表锁的情况下等待在途计数归零。这比直接把地址作为不受保护的 long 句柄更清晰。［E03］

但该守卫只解决**对象在调用期间不被提前销毁**，并不保证两个 start/stop/prepare 互斥执行，也不能自动解决“回调里调用 release 等待自身在途操作”的重入问题。宿主必须有明确的播放器操作归属线程，库也应明确其并发契约。

### 4.3 prepare 仍存在 Java 锁与原生 join 的条件性互锁

源码链路是：FFmpegPlayer.prepare 在 `synchronized(lock)` 内进入 Native；NativePlayer.prepare 先调用 stop；stop 等待旧 playbackThread.join；旧播放线程若正在投递事件，Java internalListener 又要取得同一个 lock。［E02、E04、E05］

```text
调用线程：持有 Java lock -> Native.prepare -> stop -> 等旧播放线程退出
旧播放线程：投递事件 -> internalListener -> 等同一个 Java lock
```

本次 Java 边界探针使用**原样 FFmpegPlayer.java**，替换 Android/JNI 边界，观测到 prepare 持锁期间模拟播放回调线程为 `BLOCKED`，prepare 返回后回调才能继续。探针用有限等待避免挂死。这证明门面的阻塞条件；真实 Android/Native 完整死锁尚未复现。

最新提交已将显式 stop 移到 Java 锁外，但 **prepare 隐含 stop 仍保留相同风险**。建议将这一条列为高优先级修复，并补“播放中重新 prepare + 恰有事件回调”“prepare 中取消/释放”的回归测试。

### 4.4 回调中直接 stop/release 的边界不安全

NativePlayer.notifyPlayerEvent 直接通过 CallVoidMethod 同步调用 Java，FFmpegPlayer 再在同一线程调用业务 listener。Native stop 无播放线程自 join 判断。若业务在播放线程事件回调里同步 stop，存在 self-join 风险；若在仍持有 JNI 操作守卫的同步事件回调里 release，则存在等待自身在途操作的风险。［E03、E04、E05］

这不是“所有回调都会死锁”的结论，而是未被契约限制或实现防护的可达边界。Demo 先 post 到主线程再处理事件，避免了直接在原生事件栈内操作 UI。推荐库提供明确的事件投递 executor 或异步生命周期请求，不能仅依赖调用者自行猜测安全线程。

## 05｜输入、解码与实时播放链

### 5.1 当前实际流水线

NativePlayer 承担输入打开、流选择、软硬解选择、音视频时钟、断流恢复和渲染路由。播放主线程执行 av_read_frame、音视频解码和视频渲染；音频输出和录制输出已分离到各自工作线程。**不是 demux/video-decode/video-render 三个独立队列的流水线。**［E18］

```text
av_read_frame
  |-> Recorder.onPacket：引用压缩包并入队
  |        -> Remux Worker -> 封装写盘
  |
  |-> Video：解码 -> 时钟/丢帧策略 -> 选定渲染器
  |
  `-> Audio：解码 -> swresample -> PCM Queue
                    -> Audio Worker -> Java AudioTrack
```

Recorder 在暂停预览和实时丢包/丢帧策略之前接收输入包，这是“预览策略与原始码流录制分离”的重要边界。实时 pause 仍可继续读包、丢弃预览解码并保持录制；不要误解为整个媒体输入完全冻结。［E18］

### 5.2 默认值与 Demo 配置不是一回事

裸 PlayerOptions 默认是 **RTSP TCP、BALANCED、硬解关闭、SOFTWARE_RGBA**；Demo 在创建/prepare 时显式应用自己的硬解开关和渲染选择，硬解主路径选择 NV12 GL，软件主路径选择 YUV GL。因此“Demo 默认硬解”不能写成“FFmpegPlayer 构造后天然硬解”。［E20、E21］

四档 latency profile 会重建一组参数，再恢复部分 transport、decoder 和 reconnect 配置。这意味着选项设置顺序有实际意义：先设高级阈值，再切换 profile，部分阈值可能被 profile 默认值覆盖。setPlayerOption 采用已知 key 分支，未知 key 返回错误；它不是任意 FFmpeg AVDictionary 选项的通用透传接口。［E20］

LOW_LATENCY 代码中打开/读超时为 3 秒、probesize 32768、analyzeduration 0、maxDelay 0、单解码线程；UDP 的重排序队列设为 0。ULTRA_LOW_LATENCY 进一步启用 packet drop、latest-frame-only，并使用 VIDEO master。这是代码策略，不是已验证的固定端到端延迟承诺。

### 5.3 功能定位限制

输入按 URL 类型识别 RTSP/其他网络源/文件等，但“识别 URL”不等于每种协议、封装和编解码器组合都已真实验收。当前路径更适合低延迟实时预览，而非完整通用点播播放器。

本地 EOF 路径没有体现完整的视频/音频解码尾帧 drain，文件视频节奏也存在基于 fps 的等待逻辑。仓库没有完整 seek、倍速、轨道切换和 VOD 状态体系。应把这些列为范围限制，不应把实时 Demo 包装成全面替代通用媒体播放框架的产品。［E18］

## 06｜渲染后端与“画面未全屏”的直接结论

### 6.1 五条渲染路径

| 模式 | 主要路径 | 审阅结论 |
|---|---|---|
| software_rgba | 软件解码 -> sws_scale -> RGBA -> ANativeWindow | 兼容回退与原生截图路径，存在像素复制 |
| software_yuv_gl | 软件 YUV420P/YUVJ420P -> YUV 纹理 -> GLES | 避免 RGBA 转换，当前使用全 viewport |
| mediacodec_nv12_gl | FFmpeg MediaCodec 可读 NV12 -> 纹理上传 -> GLES | 当前硬解主路径；不是端到端零拷贝 |
| mediacodec_surface | MediaCodec 直接 Surface | 保留的兼容模式，截图依赖宿主 Surface 捕获 |
| mediacodec_oes | MediaCodec -> SurfaceTexture/OES -> GLES | 仓库将其定位 experimental/future zero-copy |

NV12 GL 没有 sws_scale/RGBA 转换，不代表“没有 CPU 可见数据与纹理上传”。此区别应在性能文档中保留，避免对链路延迟做错误归因。［E11、E12、E20、E24］

### 6.2 黑边确实可能来自 Native，而不是 View 尺寸

NativeNv12GlRenderer 根据 `contentAspect` 与 surface 宽高比缩小 viewport，再居中绘制；NativeOesRenderer 有同类 aspect-fit 逻辑。NativeYuvGlRenderer 则直接用整个 surface viewport，并绘制满屏四边形。［E11］

因此当前存在**后端之间的比例策略不一致**：NV12/OES 等比例留边，YUV GL 铺满 viewport。对于前面提到的“视频容器已经全屏但画面仍有黑边”，这次库源码已经提供了明确候选原因。

按代码几何计算，1920×1080 的输入放进 720×720 的 surface，aspect-fit 区域约为 720×405，剩余高度形成上下黑边。这只是计算示例，不是设备截图。更换 TextureView 为 SurfaceView 并不会自动改变 Native 中的 viewport 算法。

建议新增一个明确、跨后端一致的显示比例契约，例如 FIT / CENTER_CROP / STRETCH；保留旧默认行为，再由宿主显式选择。不应为了“全屏”直接破坏比例或暗中裁剪画面。

### 6.3 Surface 生命周期已有措施，但绑定结果可进一步收敛

多个 GL renderer 通过待处理 Surface 和 generation 机制，将 EGL 资源切换落实在对应渲染路径上，避免任意 UI 线程直接销毁当前渲染资源。NativePlayer.setSurface 会向多个后端传播 Surface，但当前主要返回 RGBA renderer 的结果，其他 GL 后端绑定错误仅记录日志。［E12］

因此 setSurface 返回 success 并不足以单独证明当前选中的 GL 后端已经能显示。建议后续对“配置后端”“实际使用后端”“绑定结果”“首帧提交”分别报告，减少宿主误判。

## 07｜音频链路与同步

### 7.1 已具备的设计

音频解码在播放主线程，resample 后形成 S16 / 48kHz / 双声道 / interleaved PCM；AudioPcmQueue 目标约 150ms、最大约 250ms，溢出淘汰旧块以维持实时边缘。Audio Worker 从队列取块并调用 LiveAudioPcmSink，后者不依赖 Activity。［E16］

AudioTrack 使用 WRITE_NON_BLOCKING，0 字节写入时进行短暂重试，单块等待窗口最多 250ms。lifecycleEpoch 与 native audio generation 用于 stop/pause/reconnect 后拒绝旧 PCM。这里不能把“non-blocking write”描述成“整个 onAudioPcm 永不等待”。

时钟读取 AudioTrack playback head，Native 设置失效检查与视频最大等待约束；重连前清空 PCM、停止旧 worker 并使旧 AudioClock 失效，重开成功后才重建音频输出。音频监听开关与压缩音频录制相互独立。［E16、E17］

### 7.2 仍需真实验证的组合

SDP/流信息中声明 AAC 仅能说明存在音频轨道描述。必须实际收到 AAC 包、解码出 PCM、写入 AudioTrack，才能验证监听链；还需要输出文件中确有音频包，才能验证录制链。

当前仓库矩阵记录 `audioPacketCount=0`，因此不能证明最新重连改动已经解决真实音频恢复。需要实际音频源覆盖音频 ON/OFF、录像 ON/OFF、TCP/UDP、断网/物理断链/服务重启/404 四种故障。［E26］

建议增加声画同步基准：源端具备可识别的同一声画事件，录后检查 packet 时间戳，再做实际听音和画面对齐。仅观察 audioClockValid 和无写错误不足以证明声画同步正确。

## 08｜Recorder：隔离工作已落地，但时间轴与边界还需修复

### 8.1 独立 Queue + Remux Thread 已经实现

PlayerRemuxRecorder 将 codec parameters、extradata、timebase 等复制成输入快照。播放线程 onPacket 仅引用压缩包并入队，worker 独占输出格式上下文、BSF 和写盘操作。队列限制为 **512 包或 16MiB**；超限会结束本轮录制，而不是静默丢掉任意参考帧后继续生成看似完整文件。正常 stop 请求会排空已入队数据后退出。［E06、E07］

该设计解决的是“逐包磁盘 I/O 阻塞实时播放线程”。startWithConfig 仍等待 worker 创建输出并回报结果；stop/stopAndJoin 仍等待 drain/join。因此 **Java startRecord/stopRecord 仍是可能阻塞的 API，宿主必须放在工作线程。** 将它们写成“已经全异步，UI 可直接调用”是不准确的。［E02、E07］

输入重连后，Recorder 比较流布局、codec、尺寸、采样率、声道和 extradata；兼容时重新等关键帧并连续化输出时间戳，不兼容则仅使录制进入错误，而非强行混写不同格式。该行为比保留旧输入 AVFormatContext 裸指针更安全。

### 8.2 已复现：时间戳独立归零丢失原始关系

writePacketLocked 分别保存每个流 firstPts 和 firstDts，再独立相减。这样不仅让每个轨道独立从零开始，还会改变同一个视频包的 `PTS-DTS`。［E08］

本次链接真实 PlayerRemuxRecorder.cpp 与仓库 FakeFFmpeg，输入时间基为 1/1000：

```text
输入视频首关键包：PTS=1040，DTS=1000
输入音频首包：    PTS=1140，DTS=1140

Recorder 交给 fake muxer 的结果：
视频：PTS=0，DTS=0
音频：PTS=0，DTS=0

视频 composition offset：40 -> 0
音频相对视频 PTS 差：   100 -> 0
```

这直接证明当前 packet 重写改变了时间轴关系；**尚未在真 MP4/AAC 输出中测出可见/可闻的偏移量**。不能由此推断所有无 B 帧、同时起始的实时源都会错，但已有 B 帧重排或音视频不同起始时间的输入存在明确风险。

建议用共同媒体时基定义统一 epoch，保留每包 PTS-DTS 和音视频相对偏移；重连、分段、缺失时间戳和单调修正必须纳入同一测试模型。不要仅以“DTS 单调、ffprobe 可打开”作为时间轴正确的充分证据。

### 8.3 已复现：多个 AAC 流覆盖单个 BSF 所有权

openOutput 的循环会为每个 AAC 音频流调用 `av_bsf_alloc(..., &audioBitstreamFilter_)`，而类只持有一个 audioBitstreamFilter 指针和一个 audioInputStreamIndex。第二个 AAC 流覆盖前一个指针，close 仅释放最后一个。［E09］

本次构造一个视频流、两个 AAC 流，真实 Recorder start 返回 success；Recorder 析构后，FakeFFmpeg 仍报告 **filters=1**，context 和 packet buffer 均为 0。这是主机替身的分配/释放计数验证，不是设备内存剖析。

应选择一种明确范围：仅录制已选择的一条音频流，并拒绝/忽略其他轨道；或者建立按 stream index 管理的 BSF 集合。现状不宜对多音轨输入宣称完整支持。

### 8.4 分段文件模板校验不足

hasPrintfIntegerPlaceholder 只要找到任意 `%d/%i/%u` 就返回 true；makeSegmentPathLocked 随后将整个 pattern 作为 snprintf 格式字符串，却只传入一个整数。即使只是误写成两个序号占位符，也会出现参数数量不匹配。［E10］

建议只允许一个受控序号占位符，完整验证其余百分号转义；更直接的方案是字符串替换而非通用 printf。该结论来自代码边界分析，本次没有执行非法格式字符串以触发崩溃。

### 8.5 其余录制验收边界

分段按视频关键帧触发，分段时长不是严格到秒的裁切承诺。fMP4 逐包 flush 有助于降低未落盘窗口，但会增加写调用；独立 worker 和队列监控比仅调大队列更重要。

返回 JSON 中的 `abnormalExitReadable:true` 来自格式/配置判断，不是该文件经历断电或进程终止后已验证的结果。需要真实故障恢复测试，不能将布尔值直接展示为“保证任意崩溃后可播放”。［E06、E07、主机探针日志］

## 09｜断流恢复与 AAC 矩阵

### 9.1 新改动具有实际价值

播放链针对 avformat_open_input、avformat_find_stream_info、av_read_frame 设置独立 deadline，并通过 interrupt callback 响应停止/超时。这比仅向字典传超时参数更明确。首次符合策略的 RTSP 404 可进入 WaitingSource；后续持续等待或按配置限次恢复。［E04、E19］

重连主路径在释放旧输入前推进音频 generation、清空队列、stop/join 音频 worker，再重开输入。stop 在 join 播放线程后再次收束音频 worker，覆盖“重连成功正准备启动新 audio worker，同时外部 stop”的窗口。Recorder 不因每次重开输入立刻关闭输出，而由输入快照兼容规则决定是否继续。［E17］

### 9.2 32 个真实故障场景仍然全部未执行

`RTSP_RECONNECT_MATRIX.md:5–7` 写得很明确：**PASS 0 / FAIL 0 / NOT_RUN 32**。原因是源虽然声明 H.265 + AAC，但没有实际 AAC 包，而且缺乏可自动控制的网络、物理链路、服务和资源上下线。［E26］

该文档还记录 TCP/UDP 基线视频帧数，以及自然 EOF 重连后录制继续的历史观察；这些可以作为局部回归材料，但不等价于指定故障注入、有效音频恢复或本次审阅重新执行的设备结果。

Python runner 的门禁检查实际音频包、恢复后视频/音频/录制进展，并将尚未人工听音确认的场景留在未完成状态。这是正确的测试口径；13 个 Python 单元测试通过，只能证明这些判断逻辑的测试通过。［E27］

### 9.3 需要继续补的边界

重点补“无限等待源时用户取消”“stop 与重连成功同时发生”“重连中格式变化”“输运方式切换时音频代次”“Recorder 继续等待但 Player 已终止”等组合。不可仅凭 normal reconnect 代码已有 generation 就推断所有分支一致。

JNI 的独立 `probeUrl` 仍直接 open/find，并未安装 NativePlayer 同等级 deadline interrupt_callback。它传 timeout 字典，不代表所有阶段都能按同一预算中断。建议统一 probe 的取消/总预算契约，但不要为了统一再创建庞大调度框架。［E19］

## 10｜截图、Surface 与内存开销

原生截图路径基于缓存 RGBA 帧，支持 PNG/JPEG。GL 或 direct-Surface 模式返回明确的 `SNAPSHOT_REQUIRES_SURFACE_CAPTURE`，由 Demo PixelCopy 处理；库没有假装自己能直接截取所有硬解后端。［E13］

Demo 只在指定错误码触发 fallback，并检查 Surface ready、有效性、尺寸与 generation；等待 PixelCopy 的超时路径不会立刻 recycle 仍可能被异步写入的 Bitmap。这些生命周期处理值得保留。当前 Demo 的 PNG/JPEG 编码按路径选择，不能把先前其他宿主工程的“PNG 后缀写 JPEG”问题套到本 Demo。［E14］

SnapshotManager 的 PNG 实现构造原始扫描行、未压缩 DEFLATE 数据及最终 PNG 向量；再叠加已有帧缓存和截图 frameCopy，会形成多份图像数据。1920×1080 RGBA 一份约 7.91MiB，若几个缓冲同时在作用域内，总像素数据可到数十 MiB。**这是代码路径和尺寸估算，不是 Android allocation 实测。** 不应高频自动截图，更不应把截图缓存当每帧 UI 数据通道。［E15］

建议后续优化先测峰值，再决定流式编码、标准编码器或缓存策略；不建议立即引入对象池和跨线程共享可变 Bitmap。

## 11｜诊断、延迟与热成像

### 11.1 诊断体系是工程优势

工程区分 OFF/BASIC/LATENCY，保留 Pre-T0、demux 后阶段耗时、队列、丢包/丢帧、首帧与时钟等指标；采样关联容量为 256，稳态分位数排除前 120 个预热样本。相关逻辑具备主机测试。［E23、E28］

特别值得肯定的是，代码明确将 `kE2EClockSyncValid=false`、估计误差设为 -1：仅有 RTP/NTP 映射，不能证明源端与接收端 wall clock 同步。这避免输出看似精确却无有效时钟依据的端到端延迟。

应保持三个层次的术语区分：网络读包/处理阶段耗时、demux 到提交渲染阶段耗时、真实相机采集到屏幕显示延迟。eglSwapBuffers 或 ANativeWindow 提交完成并不构成屏幕物理显示时刻的设备实测。

### 11.2 热成像是图像处理，不是温度测量系统

ThermalConfig、LUT 和 shader 提供原图/White Hot/Ironbow，支持窗口、Gamma、AGC。AGC 代码按每 5 帧更新、4×4 像素采样、2%/98% 分位数、0.15 平滑系数等实现亮度窗口估计。主要作用在支持的 YUV/NV12/OES 路径。［E23、E24］

本 ZIP 没有辐射测温标定、温度元数据解析或温度值映射链。文档应称为“热图/亮度伪彩后处理能力”，不能据此承诺真实温度读数。不同渲染路径的支持边界也需要 UI 显式提示。

## 12｜Demo 与公共 API 的可维护性

### 12.1 工作线程正确隔离 Native 操作，但 View 参数应先在 UI 线程采集

runNative 把 action 放到单线程 worker 执行，但多处 action 内仍调用控件 getText/isChecked/getCheckedRadioButtonId；`requireRecordPath`、`requireSnapshotPath` 等在路径为空时还直接 setText。［E21］

这形成“Native 调用移出主线程，但 View 访问也被一起移过去”的问题。代码可以明确确认线程路径；是否每种设备每次都会抛异常不能由静态审阅断言。

建议在点击监听中先形成不可变请求快照：URL、超时、开关、模式、输出路径等均在 UI 线程读取；worker 只处理快照和播放器；完成后再 post 更新 View。无需新增多个 Manager，只需切开参数采集与后台执行的职责。

### 12.2 Activity 仍大，但不宜立刻继续拆很多层

MediaPlayerActivity 同时承载控制面板、Surface 回调、指标刷新、日志、截图、热图和测试入口。已有单 worker、mainHandler、stats in-flight 防积压机制，能说明它不是完全无控制的异步堆叠。onDestroy 将释放任务排入 worker 并 shutdown，但释放仍可能排在先前阻塞任务之后。［E22］

优先修复线程参数快照和取消/释放契约，再考虑把纯指标展示或面板参数读取整理成小组件。NativePlayer 的音频/诊断职责也应在明确测试边界后再局部抽离，不建议继续生成大量一行转发类。

### 12.3 JSON API 的语义需要更清晰

prepare JSON 当前重复输出 `videoStreamIndex` 与 `audioStreamIndex`；不同返回的 `success` 可能表示配置已接受、等待源可继续、录制 worker 已启动，而非首帧或首包已经写入。［E04］

建议先建立 JSON schema/contract tests，逐个明确 prepared、waiting_source、playing、recording、first_frame、record_start 的含义；再评估是否给宿主提供轻量 typed facade。不要通过 JSON 字段堆叠继续模糊业务事实。

## 13｜日志、构建与交付

### 13.1 URL 脱敏并未覆盖全部 Native/Java 路径

矩阵文档写“native log 会脱敏”，Python 工具也确实对收集到的文本执行 redact。但 Native prepare 直接输出 url，notifyPlayerEvent JSON 携带原始 URL，Demo 又直接记录 eventJson；JNI probe 的失败日志同样打印 URL。［E04、E05、E19、E26、E27］

因此工具端产物脱敏不等于设备原始日志已经脱敏。若 URL 包含 userinfo 或查询令牌，这些路径会把它写进日志。建议在 native 日志、公共事件 JSON 和 Demo 日志入口统一处理，并保留不含凭据的源标识。审阅未收集或展示任何真实凭据。

### 13.2 构建不是自带完整 FFmpeg 来源的可再生产链

Library 使用 FFmpeg 头文件和两套 ARM SO；从 ffversion.h 与二进制字符串可识别 8.0.1。Native 还依赖随包头文件的 `AV_PKT_DATA_RTCP_SR` 与 `AVRTCPSenderReport` 布局。ZIP 没有 FFmpeg 完整源树、对应变更清单和从源码生成这些 SO 的构建脚本。［E25、E29］

据此不能证明“任意名为 8.0.1 的 FFmpeg SO 都可以替换”。交付应固定头文件、SO、配置选项、来源提交和必要补丁为一套版本化制品。报告不对其许可证合规作结论，只指出来源和可复现性材料需要补齐。

全部 10 个随包 FFmpeg SO 的 ELF LOAD alignment 均为 `0x4000`，已生成 SHA-256/SONAME/NEEDED 清单。**该 ELF 检查不等于完整 APK 的 16KiB 运行兼容验收**：本次没有重建 native-ffmpeg.so、检查最终 APK zip alignment 或在相应设备上运行。

SO 使用通用名称如 libavcodec.so。若宿主同时含其他 SDK 的同名 FFmpeg 库，应核对制品与 ABI/符号兼容，不能把“打包 pickFirst 通过”当成运行安全依据。此为集成风险提示，本次没有在 DJI 等其他 SDK 组合中测试。

### 13.3 Gradle、环境和仓库整洁度

App compileSdk 36 / targetSdk 35，Library compileSdk 35 / targetSdk 35，二者 minSdk 24；App Java 17。根声明 AGP 8.1.2、Kotlin 2.1.20 等，Wrapper 为 Gradle 8.14.3，NDK 版本没有在模块中固定。这里记录实际配置，不凭静态版本号断言整套工具链不兼容。［E01］

本次直接 bash 执行 gradlew 受到 CRLF 行尾影响；绕过 shell、直接调用 Wrapper 主类后，下载镜像域名解析失败，没有进入 Android 编译。环境中亦未发现可用 Android SDK/NDK/adb。**这是未完成验证，不是已经发现 Android 源码编译错误。**

归档包含 .git、.idea、.kotlin 历史日志和 build 下旧产物。初始 Git 状态有 25 项变动，忽略行尾空白的 diff 无内容，表现为行尾噪声。建议单独处理行尾与源码交付清单，不与播放器 Bug Fix 混合提交。

consumer-rules.pro 已覆盖 JNI 注册类、事件监听器、OES 回调构造器和 PCM sink 的精确方法名，这是可取的交付措施；但 Demo release minify 未开启，不能据此说已经完成混淆后的 AAR 宿主验证。［E29］

## 14｜本次验证结果与复现材料

| 验证项目 | 本次结果 | 覆盖边界 |
|---|---|---|
| CMake 主机测试 | 7/7 CTest 目标通过 | Recorder + deadline/diagnostics/E2E/PreT0/test-hook policy；不构建 NativePlayer/JNI/GL |
| Recorder 内部用例 | 21 个通过 | 真实 Recorder + FakeFFmpeg；已包含在上述 CTest 中，不能重复相加 |
| Recorder ASan | 21 个通过，无 ASan 报告 | 同一测试集合的另一构建，非额外 21 项功能 |
| Python 矩阵工具测试 | 13/13 通过 | 门禁、判定、报告等合成数据逻辑 |
| 时间戳审阅探针 | 观测到 PTS/DTS 与 A/V 初始差归零 | 真实 Recorder；捕获 fake muxer 入参，未输出真实媒体文件 |
| 多音轨审阅探针 | 析构后残留 1 个 BSF 分配计数 | 真实 Recorder + 分配计数替身 |
| Java 锁边界探针 | prepare 持锁时回调线程 BLOCKED | 原样门面 + Android/JNI 替身；非原生死锁实测 |
| App 15 个 Java @Test | 未由 Gradle 执行 | 15 是源码注解数量，不是通过数 |
| Android/NDK 构建 | 未完成 | Wrapper 下载失败，未进入 Android 构建 |
| 真实 AAC 32 故障矩阵 | 本次未执行 | 仓库既往报告也为 32 个 NOT_RUN |

主机测试原始命令：

```bash
cmake -S ffmpegplayer/src/test/cpp -B <outside-repo>/host-build -DCMAKE_BUILD_TYPE=Debug
cmake --build <outside-repo>/host-build -j4
ctest --test-dir <outside-repo>/host-build --output-on-failure

cmake -S ffmpegplayer/src/test/cpp -B <outside-repo>/asan-build \
  -DCMAKE_BUILD_TYPE=Debug -DRECORDER_ASAN=ON
cmake --build <outside-repo>/asan-build -j4
ASAN_OPTIONS=detect_leaks=1 <outside-repo>/asan-build/RecorderAsyncTest

cd tools
python -m unittest -v test_rtsp_reconnect_matrix
```

这些测试的成功与新增探针发现问题并不矛盾：已有测试覆盖了一些队列/重连/资源边界，却没有约束“不同音视频初始时间关系”和“多 AAC 流 BSF 所有权”。应扩充测试，而不是因为旧测试全绿就忽略新观测。

## 15｜问题清单、优先级与建议顺序

此处 P1 表示建议在下一次库交付前优先关闭的正确性/集成风险；P2 表示后续可维护性、适用范围或性能优化。优先级是本次工程建议，不代表全部问题已经发生真机故障。

| 编号 | 事项 | 证据强度 | 建议 |
|---|---|---|---|
| R01 | prepare 持 Java 锁，隐含 stop/join，回调反向取锁 | 源码 + Java 边界探针 | P1；补重入与取消测试后修复 |
| R02 | 回调内同步 stop/release 的 self-join/自等待 | 源码条件路径 | P1；明确 callback executor 与生命周期契约 |
| R03 | Recorder 独立归零 PTS/DTS 与每轨起点 | 真实 Recorder 主机探针 | P1；统一时间轴并保留原始关系 |
| R04 | 多 AAC 轨覆盖单个 BSF | 真实 Recorder 主机探针 | P1；明确单轨范围或按轨管理 |
| R05 | 分段 pattern 直接用于通用 snprintf | 源码边界分析 | P1；完整校验或受控替换 |
| R06 | Demo worker 内读写 View | 源码调用链 | P1；UI 线程采集不可变参数 |
| R07 | Native/Event/Demo 日志仍含原始 URL | 源码确认 | P1；集中脱敏，区别工具产物与原始日志 |
| R08 | NV12/OES fit、YUV 铺满，比例策略不一致 | 源码确认 | P1 集成问题；显式跨后端 scale policy |
| R09 | AAC 故障恢复未形成真机验收 | 仓库明确 NOT_RUN | 发布门禁；不能用单元测试替代 |
| R10 | probe 缺统一 deadline；JSON 重复 key；截图多缓冲；大类 | 源码确认/性能估算 | P2；逐项按实测收益处理 |

建议仅开六个边界清晰的后续任务，不再继续无止境瘦身：

**任务 A：生命周期正确性。** 处理 R01/R02，覆盖 prepare/reprepare、callback 内请求停止、并行 stop/release、输入阻塞中取消；规定一条明确操作执行链。保持公开调用结果兼容，补 fail-fast 或异步投递，不依赖无限等待。

**任务 B：Recorder 时间轴与输入边界。** 把本次两个探针加入正式回归；覆盖 B 帧、A/V 不同起点、多音轨、缺失时间戳、重连新 epoch、分段和非法 pattern。增加真实 FFmpeg 输出文件与 ffprobe packet 校验，再做实际声画检查。

**任务 C：跨后端画面比例。** 统一 FIT/CROP/STRETCH 契约，覆盖横/竖/正方形 surface、源尺寸改变、旋转、Surface 重绑；不要以替换 View 类型替代 scale 设计。

**任务 D：Demo 与日志。** 采集 UI 参数快照后再进入 worker；统一 URL 脱敏；保留诊断所需非敏感源标识。补数据脱敏和空路径截图/录像测试。

**任务 E：真实 AAC 故障矩阵。** 准备真正输出 AAC 包的 H.264/H.265 RTSP 源与可控故障，执行 TCP/UDP × Audio ON/OFF × Recording ON/OFF × 四类故障。记录恢复首帧、音频时钟、人工听音、文件音视频包、stop 排空与资源稳定性；不具备条件的场景继续标 NOT_RUN。

**任务 F：可复现制品与文档。** 固定 SDK/NDK/Gradle 环境和 FFmpeg 头文件/SO 来源；清理交付噪声；加入混淆宿主、两个 ARM ABI、最终 APK 与新旧宿主兼容验证。前五项稳定后，再考虑局部整理 NativePlayer 中音频/诊断职责。

## 16｜给宿主项目的接入结论

这份库可供当前无人机 App 的 Motro 集成层继续使用，但集成时至少要守住以下边界：

**所有可能阻塞的 prepare/stop/release/录像启停/截图/探测放到受控工作线程。** Library 有播放线程和 Recorder worker，并不表示它的公开方法都立即返回。

**不要在 Native 回调栈中同步销毁播放器。** 收到事件先投递到约定业务/控制线程，再根据当前会话代次做操作；仅有 handle 有效性检查不足以隔离同一 handle 上旧 prepare 的迟到事件。

**明确区分输入恢复、开始播放和首帧显示。** WaitingSource、Reconnected、Playing 和首个有效渲染帧不可混用；音频也要以实际 PCM/AudioClock 恢复判断。

**重连中 Recorder 有自己的状态机。** 对于相同流参数，它可能继续录像；对于不兼容流可能单独错误。宿主不能一收到 video reconnect 就无条件清空或保留录像状态，应该读取真实 recorder state 并处理结果。

**黑边需要从 Native scale policy 核对。** 本版本 NV12/OES 已明确实施 aspect-fit；容器 match_parent 或换 SurfaceView 不等于画面裁切铺满。

**截图支持按后端区分。** RGBA 可原生截图，GL/direct 路径由宿主 PixelCopy；本库没有替宿主保管全部 View 级截图生命周期。

以上是由当前库代码推导的接入要求，不是已经把它与前一个无人机 ZIP 联合构建、联调通过的结论。

## 17｜最终判断与交付清单

**这次版本最有价值的成果，是把“实时播放”和“逐包录制磁盘 I/O”真正分开，并把网络恢复、音频代次和诊断有效性做成了可追踪的机制。** 这些成果应该保留，不必因为发现新问题就推倒重写。

下一轮更重要的是修复时间轴和生命周期条件路径，并补真机 AAC 故障证据。仅继续精简 NativePlayer 行数，不能证明重连、录像和回调已经正确；同样，575 项等其他宿主项目的测试数，也与本库的验证范围无关。

本次交付包括：本中文报告的 Markdown 与 Word 版、源码证据索引、代码/二进制清单、真实测试日志、三类审阅探针及复现说明。没有提交生产代码变更，没有将历史设备数据写成本次实测，也没有将 test fake 的结果写成真实 FFmpeg/Android 全链路结果。

**建议结论：源码结构具备继续开发基础；存在明确待修复项，真实 AAC 重连及 Android/Native 完整交付验收仍待完成。**
