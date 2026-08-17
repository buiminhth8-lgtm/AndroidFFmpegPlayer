# Phase 2 Revised Final Architecture

审计日期：2026-08-17
审计基线：`8aee2b1 test(player): validate MediaCodec NV12 thermal pipeline`
目标：在不改变 Thermal 算法、MediaCodec 配置和播放能力的前提下，完成 Phase 2 最终架构审计、死代码清理、资源生命周期核对和冻结确认。

最终主路径：

```text
Java UI/API
  -> registered JNI
  -> NativePlayer
  -> FFmpeg demux/decoder
  -> decoded AVFrame dispatch
     -> mediacodec_nv12_gl: NativeNv12GlRenderer -> NV12 shaders -> Thermal
     -> software_yuv_gl:    NativeYuvGlRenderer  -> YUV shaders  -> Thermal
     -> software_rgba:      sws_scale -> RGBA -> ANativeWindow
     -> mediacodec_surface: MediaCodec direct Surface (legacy compatibility)
     -> mediacodec_oes:     NativeOesRenderer (experimental/future zero-copy)
```

Phase 2 核心结论：

- `mediacodec_nv12_gl` 是 Hardware Decode 开启后的正式 Thermal 主路径。
- `software_yuv_gl` 是 Software Decode 下的 Phase 1 Thermal 主路径。
- MediaCodec/NV12 失败继续回退 software decode；NV12 GL 渲染失败或格式不受支持继续回退 RGBA。
- `software_rgba`、`mediacodec_surface`、`mediacodec_oes` 均保留，角色已明确。
- Thermal 数学、AGC 参数、LUT、shader 逻辑、decoder 配置和录制复用链未修改。

清理前后关键行为基线：

| Behavior | Before cleanup | After cleanup | Result |
|---|---|---|---|
| Original playback | Hardware -> NV12 GL；Software -> YUV GL | 相同 | PRESERVED |
| Phase 1 Thermal | software YUV shader Thermal | 相同 | PRESERVED |
| Phase 2 Thermal | MediaCodec CPU NV12 shader Thermal | 相同 | PRESERVED |
| Decoder fallback | MediaCodec open failure -> software decoder | 相同 | PRESERVED |
| Renderer fallback | NV12/YUV GL failure or unsupported format -> RGBA | 相同 | PRESERVED |
| Snapshot | RGBA native cache；Demo GL/direct path 使用 PixelCopy | 相同 | PRESERVED |
| Recording | 同输入压缩包 remux，不依赖 renderer | 相同 | PRESERVED |
| UI pre-prepare selection | 旧 Thermal guard 与已支持的 HW Thermal 路由冲突 | guard 删除，统一采用正式路由 | STALE RESTRICTION REMOVED |
| OES release | EGL/JNI 释放，但遗漏持有的 window ref | EGL/JNI/window 全部释放 | LIFECYCLE FIXED |

# Audit Scope

本次审计覆盖：

- Slice 0 至 Slice 8 的 Revised 报告与 Slice 8 Freeze 结论。
- Java 控件状态、选项序列化、JNI 注册、`NativePlayer` 路由。
- FFmpeg decoder 选择、MediaCodec/NV12 frame 语义与软件回退。
- Native NV12 GL、YUV GL、OES、RGBA/ANativeWindow 渲染器。
- EGL/Surface/`ANativeWindow` 生命周期。
- JNI local/global reference 生命周期。
- Stats 字段来源、更新点和兼容性字段。
- CMake 源文件纳入、未引用符号、陈旧注释和诊断日志。
- Debug 构建、单元测试入口和设备可用性。

不在本次改动范围：

- Thermal 算法调参或视觉效果调整。
- MediaCodec codec selection/configuration 改造。
- OES experimental 路径产品化。
- native snapshot 新能力。
- 录制/复用行为改造。

# Removed

## REMOVED — stale diagnostics

- Removed item: `[HWCFG]` 配置诊断、packet/frame 周期成功日志、renderer 周期成功日志、`getStats()` 调用日志。
- Why stale: Slice 6 至 Slice 8 的集成定位已经完成；这些日志位于高频路径，冻结后只产生噪声和不必要分支。
- Replacement: 保留错误、回退原因、decoder 打开结果等可操作日志，并保留全部 Stats 原子计数更新。
- Behavior impact: NONE。
- Algorithm change: NO。
- Verified by: 全局日志标记扫描、`git diff --check`、`:app:assembleDebug`。

## REMOVED — zero-call internal APIs

- Removed item: `isMediaCodecDecoderName()`、NV12/OES renderer 的 `hasSurface()`、NV12 renderer 的 `supportsFrameFormat()`。
- Why stale: 全仓调用扫描为零；实际分支由 `NativePlayer` 的 mode/format 检查直接决定。
- Replacement: 现有显式 mode/format 路由。
- Behavior impact: NONE。
- Algorithm change: NO。
- Verified by: `rg` 全仓符号扫描与三 ABI native 编译。

## REMOVED — obsolete render-input abstraction

- Removed item: `VideoRenderInputType`、`videoRenderInputType()`、`videoRenderInputTypeName()`。
- Why stale: 该抽象没有调用方，且未表达已冻结的 NV12 GL 主路径；继续保留会制造第二套、不完整的路由定义。
- Replacement: `VideoDecodeMode` 与 `NativePlayer` 中唯一有效的 frame-dispatch 路由。
- Behavior impact: NONE。
- Algorithm change: NO。
- Verified by: 全仓引用扫描、编译器无遗漏 switch 警告、`:app:assembleDebug`。

## REMOVED — assign-only/log-only state

- Removed item: `maxRealtimeLatencyUs_`、YUV renderer 的 `frameWidth_`/`frameHeight_`/`renderCount_`、RGBA renderer 的 `renderCount_`。
- Why stale: 无行为读取，或只为已删除的周期日志服务。
- Replacement: 实际渲染所需尺寸仍来自当前 frame/window；用户可见计数仍由 `NativePlayer::Stats` 维护。
- Behavior impact: NONE。
- Algorithm change: NO。
- Verified by: 成员读写扫描和 native 编译。

# Consolidated

## CONSOLIDATED — UI routing authority

- Before: Java 中残留 Thermal 开启时禁止勾选 Hardware Decode 的旧保护；prepare 时的正式路由却已经支持 `mediacodec_nv12_gl`。
- After: 删除旧保护，由 `applyDecodeModeOption()` 统一决定 Hardware -> `mediacodec_nv12_gl`、Software -> `software_yuv_gl`。
- Preserved behavior: prepare 后禁止即时切换 decoder 的生命周期约束、显式 legacy/experimental intent mode、全部 native fallback。
- Algorithm change: NO。
- Verified by: UI 事件和 prepare 路由静态追踪、Debug 构建。

## CONSOLIDATED — diagnostics versus Stats

- Before: 持久 Stats 原子计数与多组重复周期日志并存。
- After: Stats 继续作为状态观测来源；删除诊断专用局部计数、PTS 计算和条件分支。
- Preserved behavior: packet/frame/render/drop/fallback 计数及 Java 暴露 schema。
- Algorithm change: NO。
- Verified by: 每个 `fetch_add` 更新点复核和 schema 对照。

## CONSOLIDATED — documentation and role labels

- Before: option help、注释和 README 仍含 Slice 1/5 或“尚无 Window/AGC”的历史描述。
- After: 统一描述 NV12 GL 主路径、software YUV Phase 1 路径、legacy surface 和 experimental OES 的最终角色。
- Preserved behavior: 所有选项字符串和默认值。
- Algorithm change: NO。
- Verified by: stale-marker 全仓扫描。

# Kept Intentionally

## KEPT — software RGBA fallback

- Reason: NV12/YUV GL 不可用、frame format 不支持或 renderer 失败时的最终兼容路径；同时承载当前 snapshot 能力。
- Status: ACTIVE_FALLBACK / LEGACY_COMPATIBILITY。
- Risk if removed: 黑屏、错误退出或 snapshot 回归。

## KEPT — sws conversion

- Reason: 仅在 RGBA fallback/snapshot 需要时执行；MediaCodec NV12 GL 成功路径不经过 `sws_scale`。
- Status: ACTIVE_FALLBACK。
- Risk if removed: 非 NV12/YUV 或 GL 失败场景失去兼容渲染。

## KEPT — mediacodec_surface

- Reason: MediaCodec direct Surface 兼容模式，允许显式 intent 选择；不支持 shader Thermal 属于固有能力边界。
- Status: LEGACY_COMPATIBILITY。
- Risk if removed: 既有 direct-surface 集成方回归。

## KEPT — mediacodec_oes

- Reason: 已有 SurfaceTexture/OES/readback 基础，是未来 zero-copy 方向的实验路径。
- Status: EXPERIMENTAL_FUTURE。
- Risk if removed: 丢失受控实验和后续演进基线。

## KEPT — recording/remux path

- Reason: 与渲染模式解耦，复用输入压缩包；本次审计没有证据表明其冗余。
- Status: ACTIVE。
- Risk if removed: 录制功能回归。

# Final Routing Matrix

| UI / explicit mode | Native mode | Decoder | Frame semantics | Primary renderer | Fallback |
|---|---|---|---|---|---|
| Hardware Decode ON | `mediacodec_nv12_gl` | `hevc_mediacodec`，失败后 software | CPU-addressable NV12 AVFrame | `NativeNv12GlRenderer` | software decoder / RGBA |
| Hardware Decode OFF | `software_yuv_gl` | software FFmpeg decoder | YUV420P/NV12/NV21 | `NativeYuvGlRenderer` | RGBA |
| Explicit legacy | `mediacodec_surface` | MediaCodec direct surface | Surface-owned output | Android Surface | existing decoder failure handling |
| Explicit experimental | `mediacodec_oes` | MediaCodec surface output | external OES texture | `NativeOesRenderer` | existing mode failure handling |
| Explicit fallback | `software_rgba` | software FFmpeg decoder | sws-converted RGBA | `VideoRenderer` / ANativeWindow | error reporting |

# Thermal Capability Matrix

| Mode | Full/Limited range | Manual window | AGC window | Gamma | Grayscale | Ironbow | Thermal state |
|---|---:|---:|---:|---:|---:|---:|---|
| `mediacodec_nv12_gl` | YES | YES | YES | YES | YES | YES | ACTIVE_MAINLINE |
| `software_yuv_gl` | YES | YES | YES | YES | YES | YES | ACTIVE_SOFTWARE |
| `software_rgba` | NO shader Thermal | NO | NO | NO | RGBA source dependent | NO | ACTIVE_FALLBACK |
| `mediacodec_surface` | codec/display owned | NO | NO | NO | NO | NO | LEGACY_COMPATIBILITY |
| `mediacodec_oes` | YES | YES | YES | YES | YES | YES | EXPERIMENTAL_FUTURE |

Frozen Thermal sequence remains:

```text
Y range normalization
  -> manual window or AGC window
  -> gamma
  -> grayscale or shared 256x1 Ironbow LUT
```

AGC parameters remain: 4x4 sampling, every 5 frames, 256 bins, P2/P98, minimum span 0.05, alpha 0.15. 本次没有改动这些值或计算顺序。

# Decoder / Frame / Renderer Semantics

- `mediacodec_nv12_gl` 只在 CPU 可访问的 NV12 frame 语义下进入 NV12 renderer。
- 硬解 decoder 创建/打开失败保留 software decoder fallback；失败原因仍记录。
- NV12 renderer 不接受不匹配格式；失败后保留 sws/RGBA 路径，不会静默丢失兼容性。
- software YUV renderer 继续处理 YUV420P/NV12/NV21；unsupported frame 继续回退 RGBA。
- direct Surface 与 OES mode 不被 Hardware Decode 主开关覆盖，显式 intent 兼容性保留。
- snapshot 仍由 RGBA/native-window 能力承载；NV12 GL native snapshot 未在本 Slice 虚构实现。

# EGL / Surface Audit

审计结果：PASS（修复 1 项生命周期缺口）。

- NV12/YUV/OES renderer 的 EGL display/context/surface/program/texture 按各自 `release()` 路径释放。
- Surface 替换路径会释放旧 `ANativeWindow` 引用。
- 发现 `NativeOesRenderer::release()` 释放 EGL/JNI 资源但遗漏 `window_`；已补充 `ANativeWindow_release(window_)`、置空并重置 surface 尺寸。
- NV12/YUV/RGBA 路径未发现新增 surface 引用泄漏。
- EGL 调用仍依赖既有 renderer 线程所有权约束；本次未做线程模型重构。

# JNI Resource Audit

审计结果：PASS。

- registered JNI -> native player handle 的创建、访问和释放链保持一致。
- OES listener/object 的 per-renderer global references 在 release 中配对删除。
- OES listener class global reference 是进程/库生命周期缓存，保留为 intentional static cache。
- 高频路径未发现新增未释放 local reference；诊断专用 attach/env 代码随 `[HWCFG]` 日志删除。
- `ANativeWindow_fromSurface()` 获取的引用在替换和 release 路径中有对应释放。

# Stats Audit

审计结果：PASS。

- packet、video/audio packet、decoded frame、rendered frame、drop、fallback 等原子计数更新保留。
- 删除日志不会删除或重置 Stats `fetch_add`。
- Java/native Stats schema 与既有字段顺序、字段名保持兼容。
- `hardwareRenderedFrames`、`softwareRenderedFrames` 等历史命名继续作为兼容字段保留，即使内部 renderer 角色已经扩展。
- 未新增只写不读计数器。

# Dead Code Audit

审计结果：PASS。

- Unused members/functions removed: **11**（6 个函数、5 个成员）。
- Dead branches removed: **12**（陈旧 UI guard、10 个诊断条件块、1 个零调用 switch helper）。
- 另删除 1 个零调用内部 enum：`VideoRenderInputType`。
- `hasSurface()`/`supportsFrameFormat()` 等零调用包装已删除；能力判断保留在实际 dispatch 位置。
- CMake 仍正确纳入 NV12/YUV/OES/Thermal 相关源文件；未删除 active renderer。
- 全仓 residual scan 未发现 `[HWCFG]`、`[NV12GL]`、`NOT_READY`；`experimental` 只用于明确标注 OES，Android 模板 XML 中的 TODO 与播放器无关。

# Diagnostics Cleanup

- Stale diagnostics removed: **17** 个 log statements。
- 删除范围：配置探针、packet/frame 周期日志、renderer 成功周期日志、stats 调用日志。
- 保留范围：错误、decoder 打开结果、回退原因、不可恢复渲染失败。
- 高频路径的原子 Stats 计数保持不变。
- 日志 tag 和 error semantics 未做不必要重命名。

# Phase 1 Smoke Test

状态：NOT_EXECUTED（`adb devices -l` 无已连接设备）。

静态/构建确认：

- `software_yuv_gl` 路由与 Phase 1 Thermal shader 路径保留。
- range/window/AGC/gamma/palette 算法及共享 LUT 未修改。
- software YUV -> RGBA fallback 保留。
- 三 ABI native 编译通过。

设备仍需执行：原始画面、Grayscale、Ironbow、Manual Window、AGC、Gamma、Full/Limited、snapshot 和 fallback smoke。

# Phase 2 Smoke Test

状态：NOT_EXECUTED（`adb devices -l` 无已连接设备）。

静态/构建确认：

- Hardware Decode ON 正式路由到 `mediacodec_nv12_gl`。
- MediaCodec/NV12 成功路径无 `sws_scale`。
- NV12 shader Thermal 能力矩阵完整。
- decoder failure 与 renderer/format failure 回退链保留。
- Hardware 开关不再受旧 Thermal guard 阻止。

设备仍需执行：Hardware Decode ON 的 Original/Grayscale/Ironbow、Manual/AGC、Gamma、Full/Limited、弱机、长时间运行、横竖屏/Surface 重建及强制 fallback。

# Build

## Passed

- `git diff --check`：PASSED（仅报告工作区既有 LF/CRLF 转换提示，无 whitespace error）。
- `.\gradlew.bat :app:assembleDebug`：PASSED。
- 构建覆盖：`arm64-v8a`、`armeabi-v7a`、`x86_64` native targets。
- 最终 native 编译无清理前暴露的遗漏 enum switch 警告。

## Test entry result

- `.\gradlew.bat test :app:assembleDebug`：TEST PHASE FAILED at `:app:kaptDebugUnitTestKotlin`。
- 失败信息：生成的 `ExampleUnitTest.java` 中 `@error.NonExistentClass()` 导致 `NonExistentClass cannot be converted to Annotation`。
- 该失败位于现有 unit-test KAPT/stub 基础设施，发生在播放器测试执行前；本次修改未触及 Kotlin、test source 或 KAPT 配置。
- `ffmpegplayer:test` 为 `NO-SOURCE`/`UP-TO-DATE`。
- 独立 `:app:assembleDebug` 在最终代码上通过，因此 Build 结论为 PASSED；测试基础设施问题不被隐藏。

# Remaining Technical Debt

## REMAINING DEBT — device runtime evidence

- Item: 本次无 Android 设备，Phase 1/2 smoke 未执行。
- Why not removed: 运行时 codec、驱动、Surface 和视觉验证不能由 host build 替代。
- Current mitigation: Slice 8 freeze 证据、完整静态路由复核、三 ABI Debug 构建。
- Future action: 在支持目标 HEVC MediaCodec/NV12 的真机执行两套 smoke matrix。
- Risk level: MEDIUM。

## REMAINING DEBT — unit-test KAPT infrastructure

- Item: `:app:kaptDebugUnitTestKotlin` 生成 unresolved annotation stub。
- Why not removed: 与本 Slice 播放器清理无关，修复会扩大到测试/Kotlin 构建配置。
- Current mitigation: 独立 assemble 通过；失败命令和错误已记录。
- Future action: 单独审计 app unit-test 的 KAPT/plugin/test source 配置。
- Risk level: LOW for runtime build, MEDIUM for CI test gate。

## REMAINING DEBT — OES experimental lifecycle/algorithm divergence

- Item: OES 仍是 experimental/future zero-copy，AGC readback 路径与 CPU helper 实现不同。
- Why not removed: 它是显式兼容/实验入口，不是 Phase 2 主路径；删除会破坏已有入口。
- Current mitigation: 明确标签、默认不选用、本次补齐 window release。
- Future action: 产品化前做线程、SurfaceTexture、readback 性能和算法一致性专项验证。
- Risk level: LOW for default path, MEDIUM when explicitly enabled。

## REMAINING DEBT — NV12 native snapshot

- Item: NV12 GL 主路径没有新增 native snapshot capture。
- Why not removed: 属于新能力，不是死代码清理；现有 snapshot 语义由 RGBA fallback 保留。
- Current mitigation: 不虚报支持，README 明确能力边界。
- Future action: 如产品需要，单独设计 FBO/readback snapshot。
- Risk level: LOW。

## REMAINING DEBT — EGL thread ownership hardening

- Item: renderer release/render 依赖既有调用线程约束。
- Why not removed: 本次未发现主路径泄漏，重构线程模型超出 final cleanup 范围。
- Current mitigation: 现有串行 player lifecycle；release 路径已复核。
- Future action: 压力测试 Surface 重建、后台前台切换并增加线程断言。
- Risk level: MEDIUM。

# Remaining Validation Items

1. 真机 Phase 1 全矩阵视觉回归与 snapshot。
2. 真机 Phase 2 NV12：Original、Grayscale、Ironbow、Manual/AGC、Gamma、Full/Limited。
3. 强制 decoder open failure、非 NV12 frame、renderer init/render failure，确认 software/RGBA 回退。
4. 720p/1080p/更高分辨率、padded stride、奇偶尺寸和长时间播放。
5. Surface 重建、横竖屏、前后台、重复 prepare/release 的 EGL/JNI/ANativeWindow 压力验证。
6. 单独修复并恢复 `:app:kaptDebugUnitTestKotlin` 测试 gate。

Cleanup metrics（最终 Git diff；含本报告）：

- Files changed: **15**
- Files deleted: **0**
- Lines added: **392**
- Lines deleted: **187**
- Stale diagnostics removed: **17**
- Dead branches removed: **12**
- Unused members/functions removed: **11**

# Final Phase 2 Cleanup Freeze: YES

Freeze 判定依据：最终主路径唯一、fallback 保留、Phase 1/Phase 2 Thermal 算法未变、资源缺口已修复、死代码/高频诊断已清理、三 ABI Debug 构建通过。Freeze 表示代码与架构清理完成，不代表未连接设备上的运行时矩阵已经补做；上述 Remaining Validation Items 必须在具备设备时继续执行并留档。
