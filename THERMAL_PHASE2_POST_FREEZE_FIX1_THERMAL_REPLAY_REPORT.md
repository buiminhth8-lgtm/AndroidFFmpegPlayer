# Symptom

在 `NativePlayer` 尚未创建时，用户先启用 Thermal 并选择 Ironbow，随后依次执行 Create、Prepare、Start，第一轮播放仍使用 Original/黑白画面。Hardware Decode ON 的 `mediacodec_nv12_gl` 路径和 Hardware Decode OFF 的 `software_yuv_gl` 路径均可复现。播放后手动执行 Thermal OFF -> ON 才会立即出现 Ironbow。

# Root Cause

根因是 pre-create UI 状态与新建 `NativePlayer` 之间缺少生命周期回放，而不是 renderer 或 shader 问题。

Thermal 控件的监听器会先更新 Activity 中现有的目标状态；但当 `playerHandle == 0` 时不会调用 JNI setter。原 Create 流程在新 handle 创建后只回放 surface、播放器选项和 decode/render mode，没有把 Thermal enabled、palette、gamma、manual window、AGC 发送给新 `NativePlayer`。因此新 native 实例继续持有默认 `ThermalConfig`。播放后 Thermal OFF -> ON 时 handle 已有效，原有 Thermal helper 才真正调用 JNI setters，所以配置此时恢复。

代码审计排除了另一种假设：`NativePlayer::prepare()`、`NativePlayer::start()` 和实时输入 start refresh 均没有把 `thermalConfig_` 重置为默认值。

# Affected Lifecycle

受影响边界是：

`pre-create UI desired state -> NativePlayer creation -> decoder/render mode configuration -> prepare -> start`

缺失点位于 NativePlayer creation 后、Prepare 前。运行中 setter 路径本身正常。

# Previous Behavior

- Pre-create 控件保留了 Java/UI 值，但无有效 handle 时不会写入 native。
- Create 只应用 decode/render mode，没有 Thermal replay。
- 新 `NativePlayer` 以默认 Thermal OFF/Original 配置进入 Prepare/Start。
- 播放后 OFF/ON 会在有效 handle 上调用原 helper，因而表现为“第二次才生效”。

# Fixed Behavior

- 新建 handle 后先应用现有 decode/render mode，再用单一 helper 回放完整 Thermal 目标配置。
- 每次 Prepare 前，在 decode/render mode 应用后、`preparePlayer()` 调用前，再幂等回放一次完整 Thermal 配置。
- 运行中 Thermal ON 继续复用同一 helper。
- Thermal OFF 时仍回放 palette/gamma/window/AGC 目标值，但最后写入 enabled=false；palette 不会隐式开启 Thermal。

# Desired State Source of Truth

沿用现有 Activity/UI option 状态，没有引入第二套 desired-state 字段：

- `thermalEnabledSwitch.isChecked()`：Thermal 总开关
- `thermalPalette`：White Hot / Ironbow
- `thermalGamma`：Gamma
- `thermalBlackPoint` / `thermalWhitePoint`：手动窗口
- `thermalAgcSwitch.isChecked()`：AGC 开关

这些值在 player 不存在时仍由原有 UI listener 更新并保留。

# Create-Time Replay

已实现 `applyThermalOptionsToPlayer(handle)`。新建 `NativePlayer` 后的顺序为：

`createPlayer -> applyDecodeModeOption -> applyThermalOptionsToPlayer -> Prepare`

同一个 helper 同时服务 Hardware ON 和 Hardware OFF，没有 renderer-specific workaround。

# Replay Ordering

固定顺序为：

1. Palette
2. Gamma
3. Window
4. AGC
5. Enabled

设备日志在 Create 和 Prepare 边界均记录到完整的同序五次 setter 调用。

Thermal Enabled applied last: YES

# Prepare Reset Audit

`NativePlayer::prepare()` 会执行 `stop()`、`resetStats()`、`clearLastFrame()`、输入/decoder 重建及会话字段初始化，但没有写入或替换 `thermalConfig_`。

`resetStats()` 会清除帧计数、NV12/OES AGC runtime validity、effective window、update counter 等运行态数据；不会清除用户配置中的 enabled、palette、gamma、manual window 或 agcEnabled。这符合“新 session 可重算 AGC runtime window，但用户 AGC 开关不丢失”的要求。

Prepare resets Thermal config: NO

# Start Reset Audit

`NativePlayer::start()` 只校验状态、按需执行实时输入 refresh、初始化时钟/关键帧等待并启动播放线程。`refreshRealtimeInputForStart()` 会释放和重开 FFmpeg 输入、清除 last frame，但不写 `thermalConfig_`。

Start resets Thermal config: NO

# Hardware ON Result

PASS。真机本地 HEVC 首轮播放（Start 后未切换 Thermal）确认：

- `actualDecoderName=hevc_mediacodec`
- `usingHardwareDecoder=true`
- `renderMode=mediacodec_nv12_gl`
- `renderer=nv12_gl`
- `frameOutputType=nv12_cpu`
- `renderInputType=nv12_cpu` / UI `Input: NV12_Y`
- `nv12GlRenderedFrameCount` 和 `nv12ThermalRenderedFrameCount` 持续增长
- `swsScaleEnabled=false`
- Ironbow 首轮即 `render IRONBOW`
- White Hot 首轮即 `render WHITE_HOT`

# Hardware OFF Result

PASS。真机本地 HEVC 首轮播放（Start 后未切换 Thermal）确认：

- `renderMode=software_yuv_gl`
- `decoderName=hevc`
- `frameOutputType=yuv_planes`
- UI frame format 为 `yuv420p_gl`
- `yuvGlRenderedFrameCount` 持续增长且 fallback 为 0
- Ironbow 首轮即 `render IRONBOW`
- White Hot 首轮即 `render WHITE_HOT`

# White Hot Replay

PASS。

- Hardware ON：`Thermal ON | white_hot | render WHITE_HOT`，路由为 `mediacodec_nv12_gl / hevc_mediacodec / NV12_Y`。
- Hardware OFF：`Thermal ON | white_hot | render WHITE_HOT`，路由为 `software_yuv_gl / hevc / yuv420p_gl`。

两例均为 fresh Activity、pre-create 配置、Create -> Prepare -> Start，未在 Start 后切换 Thermal。

# Ironbow Replay

PASS。

- Hardware ON：首轮直接报告 `Thermal ON | ironbow | render IRONBOW`。
- Hardware OFF：首轮直接报告 `Thermal ON | ironbow | render IRONBOW`。

补充 Thermal OFF + 已选 Ironbow 用例同样通过：Create 和 Prepare 均先回放 palette=ironbow，最后回放 enabled=0；播放状态为 `Thermal OFF | ironbow | render NORMAL`。

# Gamma Replay

PASS。Hardware ON、pre-create 设置 Ironbow + Gamma 1.50 后，首轮 `mediacodec_nv12_gl` 播放直接报告 `gamma 1.50 | render IRONBOW`；Create 和 Prepare 日志均记录 `setThermalGamma gamma=1.500`。

# Manual Window Replay

PASS。Hardware ON、AGC OFF、pre-create 设置 0.20/0.80 后，首轮播放直接报告 `Window 0.20 - 0.80`；没有恢复到默认 0.00/1.00。Create 和 Prepare 日志均记录 `blackPoint=0.200 whitePoint=0.800`。

# AGC Replay

PASS。Hardware ON 的 pre-create Ironbow + AGC ON 用例首轮直接报告 `AGC ON`，随后 runtime effective window 从初始无效态正常建立为约 0.11/0.89。Hardware OFF 路径也执行了同一验证并通过。用户 AGC 开关没有因 Prepare/Start 恢复为 OFF。

# Release/Create Replay

PASS。在不重新操作 Thermal 控件的前提下，连续执行至少三轮 `Release -> Create -> Prepare -> Start`。每个新 player 的 Create 和 Prepare 日志都按五步顺序回放，首轮播放均报告 `Thermal ON | ironbow | render IRONBOW`。

# Phase 1 Regression

YES，Phase 1 保持。没有修改 Range Normalize、Manual Window、Gamma、Ironbow LUT、AGC 数学、YUV upload 或 `NativeYuvGlRenderer`。软解 `software_yuv_gl` 真机帧计数持续增长、fallback=0，Ironbow/White Hot 均正常。

Thermal algorithm changed: NO

# Phase 2 Regression

YES，Phase 2 保持。没有修改 MediaCodec configure、NV12 upload、EGL、OES、`NativeNv12GlRenderer` 或 routing。硬解仍为 `hevc_mediacodec -> NV12 -> mediacodec_nv12_gl`，无 software fallback，`swsScaleEnabled=false`。

Renderer algorithm changed: NO

MediaCodec changed: NO

EGL architecture changed: NO

# Build

PASSED。

- `git diff --check`：通过（仅 Git 的 LF/CRLF 提示，无 whitespace error）。
- `.\gradlew.bat :app:assembleDebug`：BUILD SUCCESSFUL；arm64-v8a、armeabi-v7a、x86_64 native targets 均完成。
- 仓库现有 test 仅为模板 Example tests，没有适合本修复的现成生命周期 replay 单元测试；未为本修复新增大型测试框架，也未处理已知的无关 KAPT test infrastructure 问题。

# Runtime Verification

EXECUTED。

验证设备：`Bengal_for_arm64`，ADB serial `34aff35a`。安装的是本次构建的 debug APK。测试源为设备 app-specific 目录中的 8 秒、640x360、HEVC/yuv420p 本地文件，确保真实执行硬解 `hevc_mediacodec` 与软解 `hevc` 两条路径。

完成的 fresh-player 矩阵：

| Case | Decode | Pre-create Thermal | Result |
| --- | --- | --- | --- |
| 1 | Hardware ON | ON, Ironbow | PASS，首轮直接 IRONBOW |
| 2 | Hardware OFF | ON, Ironbow | PASS，首轮直接 IRONBOW |
| 3 | Hardware ON | ON, White Hot | PASS，首轮直接 WHITE_HOT |
| 4 | Hardware OFF | ON, White Hot | PASS，首轮直接 WHITE_HOT |
| 5 | Hardware ON | ON, Ironbow, gamma 1.50, window 0.20/0.80, AGC OFF | PASS，全部首轮生效 |
| 6 | Hardware ON | ON, Ironbow, AGC ON | PASS，首轮 AGC ON 并建立 effective window |
| 7 | Release/Create | 不重新操作 Thermal controls | PASS，至少三轮重建均回放 |

另验证 Thermal OFF + retained Ironbow：PASS，结果为 Original/NORMAL，未被 palette 隐式开启。

# Remaining Issues

- 项目默认 RTSP 地址 `rtsp://192.168.1.101:554/main.mov` 在验证环境中返回 `Connection refused`，所以无法执行该特定 RTSP 源的视觉矩阵。设备、APK、解码器与 renderer 均可用，完整生命周期矩阵已改用本地 HEVC 源执行；此限制不改变已定位的 source-independent Create/Prepare 配置回放根因。
- 未修改或处理已知的无关 KAPT unit-test infrastructure 问题。

# Fix Runtime Verified: YES

硬解/软解的 Ironbow 与 White Hot fresh-player 首轮、Gamma、Manual Window、AGC、Thermal OFF、三轮 Release/Create、Prepare/Start reset audit、既有 routing 和构建均通过。测试全程不需要播放后 Thermal OFF -> ON 补救。
