# Symptom

已知真机日志显示，Surface clear/recreate 附近出现 `eglMakeCurrent(...): EGL_BAD_ACCESS`、BufferQueue disconnect 错误；随后硬解帧计数继续增长，而 `nv12GlRenderedFrameCount` 停止增长。Surface 缺失期间还创建了 1280x720、随后 192x256 的 sws context，说明正式 `mediacodec_nv12_gl` 路径错误进入了 CPU NV12 -> RGBA fallback。

同一 source transition 还记录到约 4.41 秒 late-frame delay，以及一次约 5.019 秒的 `av_read_frame` 阻塞，最终 timeout/reconnect 后恢复。

# Runtime Evidence

修复前证据来自任务提供的真机日志：

- `clearPlayerSurface` 后紧接 `EGL_BAD_ACCESS`。
- `hardwareDecodedFrameCount` 增长而 `nv12GlRenderedFrameCount` 停止。
- Surface 不可用时出现 `sws context ready width=1280 height=720`，随后又出现 192x256。
- `delayUs ~= 4411263` 后触发 stale-latency catch-up。
- `lastReadFrameCostUs ~= 5019286`，之后 `Connection timed out -> reconnect`。

本次环境的 `adb devices -l` 为空，因此没有伪造修复后的真机证据。已在主机临时目录生成 HEVC MPEG-TS 动态格式测试流，ffprobe 确认同时包含 1280x720 和 192x256 frame；该结果只验证测试素材，不算 Android player runtime 验证。

# Root Cause

真实根因是 EGL context 的线程归属与 Surface 控制调用线程不一致：

- `NativePlayer::setSurface()` 运行于 Java serial worker/JNI control thread，并同步调用全部 renderer 的 `setSurface()`。
- 修复前 `NativeNv12GlRenderer::setSurface()` 在 context 已存在时直接执行 EGLSurface detach/create/make-current；`clearSurface()` 直接 full GL/EGL teardown。
- 修复前 `NativeYuvGlRenderer::setSurface()` 和 `release()` 同样会直接 full teardown/recreate。
- 真正 `eglMakeCurrent`、draw、swap 的长期 owner 是 native playback/render thread。

renderer mutex 只能避免两个 C++ 方法同时进入，不能转移 EGLContext 的 thread-current ownership。playback thread 离开 mutex 后 context 仍属于该线程，control thread 随后调用 `eglMakeCurrent`/destroy，因而可产生 `EGL_BAD_ACCESS`。

另有两个独立代码问题：

1. GL Surface 缺失时，`renderFrame()` 跳过 GL 分支后继续执行 sws/RGBA；Surface absence 被错误当作 render failure/fallback。
2. `videoWidth_`/`videoHeight_` 只在 sws context 创建时更新，NV12/YUV GL 成功路径提前返回，因此动态宽高与 stride Stats 可跨 generation 混合。

# Surface Thread Ownership Before Fix

- Surface request 来源：Android main callback -> Java worker -> JNI control thread。
- EGL context 创建/持续 draw：playback/render thread（正式 NV12/YUV GL 路径）。
- EGLSurface rebind/full clear：也可能由 JNI control thread 同步执行。
- 结果：同一 EGLContext 在两个线程之间发生未受 EGL ownership 规则保护的生命周期操作。

# Surface Thread Ownership After Fix

- Java/JNI control thread 只取得一个独立的 `ANativeWindow` 引用，并提交 latest desired attach/detach request。
- NV12/YUV GL 的 `syncSurface()`/render 在 playback thread 应用 request，并在同一 owner thread 执行 EGLSurface detach/rebind、make-current 和 draw/swap。
- Player full release 仍先 stop 并 join playback thread，再销毁剩余 renderer 资源；不会在 playback thread 仍使用 context 时 teardown。
- OES 仍为 experimental：其 prepare caller 创建 OES context，render caller负责运行中 request apply；没有发展 OES decoder/SurfaceTexture 功能。

# Surface Request / Apply Model

三个 GL renderer 使用最小 latest-state 模型：

- `active window`
- `pending window`
- pending action：NONE / ATTACH / DETACH
- requested generation / applied generation

set/clear 不执行 EGL/GL。新的 pending attach 覆盖旧 pending attach 时立即释放旧 pending 引用；active window 只在 owner thread 完成 EGLSurface detach/rebind 后释放。

Surface request 对 Android UI 是异步的：Activity 原本就通过 serial worker 调 JNI；native request 只执行引用获取、短 mutex 和状态替换，不等待 EGL 操作完成。

# EGL Owner Thread

正式路径的 EGL owner 是调用 `syncSurface()`、`renderNv12()` 或 `renderI420()` 的 native playback/render thread。增加的 one-shot 日志和 Stats 包含 request thread、apply owner thread、context create count、surface create count 和 generation。

运行时 surface control entry point 不再直接调用 EGL。full player release 只有在 playback thread join 后才允许最终 context/display teardown。

# Surface Generation Handling

IMPLEMENTED。每次 set/clear 都递增 generation，只保留最新 desired request。快速 `created -> changed -> destroyed -> created -> changed` 不会让旧 pending window 覆盖新 window。Stats 暴露 requested/applied generation 以便真机核验。

# Transient EGLSurface Detach

NV12/YUV GL transient detach 只执行：

- owner thread `eglMakeCurrent(... NO_SURFACE/NO_CONTEXT)`
- `eglDestroySurface`
- 释放 active `ANativeWindow`

正常情况下保留 EGLDisplay、EGLContext、program、textures、Ironbow LUT 和 staging buffers。reattach 只创建新的 EGLSurface、make-current 并更新 viewport。只有真实 rebind/context failure 才允许 full teardown 后在下一次 render 重建。

# Full EGL Release

full release 仍属于 Player release/stop-after-join 安全边界。若 transient detach 后没有 current EGLSurface，release 不执行无 context 的 `glDelete*`；直接销毁 EGLContext 即可释放其 GL objects，避免在错误线程/无 current context 下调用 GL。

# NV12 GL Behavior While Surface Detached

实现语义：

- hardware decode 和 packet/reconnect 流程继续。
- 每个有效 decoded frame 仍提交 format metadata/clock。
- `nv12GlRenderedFrameCount` 可暂停。
- `nv12GlNoSurfaceFrameCount` 增长。
- `nv12GlFallbackFrameCount` 不因 Surface absence 增长。
- 不创建 sws context，不执行 sws_scale，不进入 RGBA/ANativeWindow fallback。
- reattach 后下一帧自动 rebind EGLSurface 并恢复 NV12 GL；无需 Create/Prepare/Start。

# Software YUV GL Behavior While Surface Detached

与 NV12 相同：YUV decoder 继续，`yuvGlNoSurfaceFrameCount` 记录临时不可绘制帧，不增加 `yuvGlFallbackFrameCount`，不因 Surface absence 进入 sws/RGBA。reattach 由 playback thread 应用并继续复用 context/program/LUT。

# OES Lifecycle Audit

OES 仍为 EXPERIMENTAL，没有修改 decoder、SurfaceTexture、external texture、Thermal 或 AGC 算法。仅把 output Surface set/clear 改为 pending request，并让 prepare/render caller 应用 EGLSurface lifecycle，移除 active playback 期间 control-thread immediate rebind/release 的明显风险。

OES 的 context 仍按现有架构在 prepare 阶段创建；本 Fix 不把 OES 扩展为正式路径，也不进行 owner-thread 架构重写。

# Renderer Fallback Semantics

新增明确的 `kRenderErrorNoSurface`。正式 GL path 区分：

- NO_SURFACE：frame 已处理但不可绘制；不 fallback、不增加 fallback count、不设置 fallback reason。
- ERROR 且 Surface attached：保留既有 GL -> sws/RGBA fallback，并设置对应 reason/count。
- SUCCESS：清除旧 fallback reason，renderer 保持/恢复 `nv12_gl` 或 `yuv_gl`。

Stats 的 `renderFallbackUsed` 现在要求 Surface attached、存在真实 fallback reason 且 actual renderer 与 requested renderer 不同；Surface absence 本身不再报告 fallback。

# Dynamic Resolution Handling

IMPLEMENTED。有效 decoded AVFrame 进入 render/drop 决策前先执行统一 format commit。NV12 renderer 保持既有 resize 设计：frame size 变化时只重新分配 Y/UV textures，随后继续 `glTexSubImage2D`；viewport/aspect 每帧按新 frame size 更新。

分辨率变化不会主动 clear Surface、recreate SurfaceView、reconnect、recreate decoder、recompile shader 或 recreate Ironbow LUT。源码全局搜索确认 `clearPlayerSurface` 只来自 `surfaceDestroyed`、显式 Clear Surface 按钮和 Activity destroy。

因此，原日志中的 Surface destroy 是独立 Android/UI lifecycle event，不是 decoded resolution change 直接触发。

# Video Format Commit

`commitDecodedVideoFormatIfChanged()` 在 `shouldDropRealtimeFrame()` 前统一提交：

- width / height
- pixel format name/output type
- Y stride
- color range
- decoded format generation

getStats 在同一个 `mutex_` 临界区快照这些字段，避免 `videoWidth=1280/videoHeight=720/frameYStride=192` 的跨 generation 输出。

sws cache 改为独立的 source width/height/format 字段，避免 format commit 后错误复用旧尺寸 sws context。

resolution/format change 会 reset software/NV12/OES AGC runtime validity/counters，但不修改 `ThermalConfig` 中的 enabled、palette、gamma、manual window 或 agcEnabled。

# Realtime Clock Reset Policy

正常 PLAYING realtime session 中，已成功 decode 的 frame 若 width/height/pixel format 相对上一 committed generation 变化，则在 late-frame 判断前重置 PTS/wall-clock anchor，并清除只属于旧 source 的 stale catch-up gate。

若仍处于 reconnect/startup keyframe wait，则不执行此 format reset，不会取消真实的 reconnect keyframe gate。计数通过 `realtimeClockFormatResetCount` 暴露。

# Source Discontinuity Policy

- 明确 decoded format change：视为 source discontinuity，提交新 generation 并重锚 realtime clock。
- reconnect/transport switch：继续使用现有 reset + startup keyframe wait。
- 同分辨率 source switch：CURRENT EXISTING POLICY；不新增 PTS predictor/jitter heuristic，继续依赖既有 late-frame catch-up 和 timeout/reconnect。
- 单次普通 network jitter 不被猜测为 source switch。

# RTSP Read Stall Analysis

保留全部原有 read cost Stats、timeout 和 reconnect。增加仅在单次 `av_read_frame` >= 1 秒时输出一次的低频 `read stall detected costUs=...` diagnostic，不 busy-poll、不缩短 timeout、不改变 TCP/UDP、reconnect 或 FFmpeg open 策略。

修复前提供的最大证据约为 5,019,286 us。修复后实际 RTSP stall 未测试，因为无 ADB/RTSP runtime 环境。

# Upstream vs Client Stall

- Client-added stall：原 control-thread EGL rebind/full teardown race 与 detached-surface sws fallback 已从代码路径移除。
- Upstream/network stall：server 若真实停止发送约 5 秒，客户端仍会等待现有 timeout，随后按原策略 reconnect；本 Fix 不声称消除该时间。
- 无 post-fix RTSP run，因此不能量化 client stall reduction 或 upstream interruption duration。

# Hardware Source Switch Test

NOT_EXECUTED。无 ADB 设备，无法在 app 中运行 1280x720 -> 192x256 五轮 RTSP switch。主机动态 HEVC/TS 素材已通过 ffprobe 验证包含两种 frame size，但不计为 Android hardware/renderer test。

# Surface Stress Test

NOT_EXECUTED。无法执行 Hardware NV12 GL 10 次 background/foreground 或真实 Surface destroy/recreate，也无法验证 post-fix `EGL_BAD_ACCESS`、永久黑屏、context create count 和 render counter recovery。

# Software GL Regression

源码与 build 通过；software_yuv_gl Surface stress 未执行。没有修改 YUV shader、Thermal math 或 decoder routing。

# Thermal Regression

没有修改 ThermalConfig、range/window/gamma、Ironbow LUT、AGC P2/P98/alpha 或 setter replay。format change 只 reset AGC runtime validity，用户配置保持。真机 source-switch Thermal regression 未执行。

# Fix 1 Regression

Fix 1 的 `applyThermalOptionsToPlayer()`、Create replay、Prepare replay 和 Enabled-last 顺序未改。debug build 通过；fresh-player 硬/软解 Ironbow 真机回归未执行。

# Reconnect Regression

没有改变 `reconnectInput()`、timeout、transport 或 decoder selection。`Connection timed out -> reconnect -> startup keyframe wait` 原策略保留。无 RTSP runtime，结果为 NOT_EXECUTED。

# Build

PASSED。

- `git diff --check`：无 whitespace error（仅 Git LF/CRLF 提示）。
- `.\gradlew.bat :app:assembleDebug`：BUILD SUCCESSFUL。
- arm64-v8a 与 armeabi-v7a CMake/native targets 成功。
- 现有 `app/src/test` 与 `app/src/androidTest` 仅包含模板 Example tests，没有可直接覆盖 EGL owner-thread/Surface lifecycle 的测试；未将模板测试冒充本 Fix 验证。
- 没有处理无关 KAPT/dependency infrastructure。

# Runtime Verification

NOT_EXECUTED。检查时 `adb devices -l` 没有连接设备，因此以下硬门槛无法判 PASS：

- NV12 GL Surface 10 次 stress
- software_yuv_gl Surface 5 次 stress
- post-fix EGL_BAD_ACCESS 搜索
- Surface detached counter/sws/fallback 实测
- dynamic format Stats 实测
- MediaCodec/EGLContext 未重建实测
- Thermal/Fix 1 fresh-player 回归
- RTSP timeout/reconnect 与 upstream interruption 时长

Client Fix Verified: NO（缺少设备运行证据）

# Remaining Issues

- 需要连接 Android 设备和可切换的 RTSP source，执行报告列出的硬件/软件 Surface stress 与 1280x720 <-> 192x256 矩阵。
- 修复前约 5.019 秒 upstream/read stall 的修复后实际时长未知；服务器真实断流仍不在客户端 EGL fix 的消除范围内。
- OES 仍为 experimental，same-resolution source switch 仍使用现有策略。

# Fix Runtime Verified: NO

实现、静态审计和 build 已完成，但用户定义的硬门槛要求真机 Surface/EGL/dynamic-format 运行证据；当前设备不可用，不能诚实标记 YES。
