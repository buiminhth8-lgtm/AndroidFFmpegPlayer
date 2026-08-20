# RTSP Latency — Slice LAT0 — Baseline & Metric Contract

Date: 2026-08-18
Branch: dev (70a016b + LAT0)
Scope: 冻结当前稳定播放基线，审计 FPS/时间基准/renderer timing 语义，建立 LAT1/LAT2 依赖的 Metric Contract，不做任何播放行为/延迟参数修改。

## Scope
仅做指标审计与最小诊断字段修正（metadataFps / measuredDecodeFps / measuredRenderFps），不新增 PTS backlog、不调整 RTSP 参数、不修改播放器算法。

## Current Stable Baseline
从当前源码/既有冻结报告（A0-A8, L0-L6）确认的稳定路径：

- **Pipeline**：RTSP/HEVC → `hevc_mediacodec` → `nv12_cpu` → `nv12_gl` → SurfaceView
- **Resolution**：1280x720（解码格式可动态切换）
- **Runtime（既有长稳记录）**：~25 fps 实际 decode/render、20,000+ 帧、dropped=0、reconnect=0、无 fallback
- **Renderer（既有记录）**：avg NV12 upload ≈ 1.3 ms、avg GL render ≈ 3.0 ms
- **Count gap**：`videoPacketCount - videoFrameCount` 长期约 6
- **Codec2**：日志长期存在 `out=8`（Codec2 buffer channel 观察）
- **Stats fps**：`fps=21`（与实际 ≈25 不一致）

## Video Pipeline
- decoder 选择：`preferredHardwareDecoderName`（HEVC→`hevc_mediacodec`），硬件失败回退软件。
- 帧输出：`AV_PIX_FMT_MEDIACODEC`（CPU-visible NV12 经硬件解码输出）或 `NV12` CPU。
- 渲染：`NativeNv12GlRenderer`（GLES2，Y+UV 双纹理，支持 Thermal）。

## FPS Source Audit
`NativePlayer.cpp`：

| 位置 | 内容 |
|---|---|
| `openInput` 786-790 | `selectedFps = rationalToDouble(stream->avg_frame_rate 若非零，否则 r_frame_rate)`；`<=1.0/NaN/Inf` 时 fallback `25.0` |
| `fps_ = selectedFps` (812) | 存入成员 `fps_`（默认 25.0，line 302/651） |
| `getStats` 1861 | 输出 `"fps": fps`（local `fps` = `fps_`，line 1647） |
| `playbackLoop` 4312 | 仅非 realtime 输入用 `frameDelayMs = clamp(1000/fps_, 5..100)` 做 pacing；realtime（RTSP）`frameDelayMs=0`，**不使用 fps_ 做播放 pacing** |

**结论**：`fps=21` 来自 **AVStream 的 avg_frame_rate（SDP/container 元数据声明帧率）**，不是实际测量值。它是 metadata fps。

## FPS Metric Contract
新增诊断字段（最小，仅 getStats 快照差分计算，非每帧）：

| 字段 | 语义 | 来源 |
|---|---|---|
| `metadataFps` | stream 声明的帧率 | `fps_` = `AVStream::avg_frame_rate` / `r_frame_rate`（fallback 25.0） |
| `fps` | **LEGACY**：与 `metadataFps` 同值，保留兼容 | 同 `metadataFps` |
| `measuredDecodeFps` | 两次 getStats 快照间 `(hardwareDecodedFrameCount+softwareDecodedFrameCount)` 增量 / 单调时间增量 | NativePlayer（新） |
| `measuredRenderFps` | 两次 getStats 快照间 `videoFrameCount` 增量 / 单调时间增量 | NativePlayer（新） |

`metadataFps` 与 `measuredRenderFps` 是**不同概念**：前者是源声明，后者是实际输出速率。仅当快照间隔 ≥1s 才更新（避免短窗噪声），首个快照建立基线。

## Media Timeline Contract
- 视频帧 PTS：`AVFrame::best_effort_timestamp`（fallback `pts`）→ `av_rescale_q(..., videoStream->time_base, AV_TIME_BASE_Q)` 转 us（`renderFrame` 4933 / `shouldDropRealtimeFrame`）。
- 音频帧 PTS：`best_effort_timestamp` 同法（`convertAudioFrameToPcm`）。
- packet PTS：`av_rescale_q(packet->pts, videoStream->time_base, AV_TIME_BASE_Q)`（`shouldDropRealtimePacket` 3842）。
- 统一映射到 media timeline（us）；缺 PTS 用 `AV_NOPTS_VALUE`/`isValidPts` 保护。

## Monotonic Clock Contract
- `steadyNowUs()` = `std::chrono::steady_clock`（monotonic，仅本机测 processing duration）。
- `nowMs()` = `av_gettime_relative()/1000`（monotonic 时间，用于 wall 时间戳字段如 `lastVideoFrameTimeMs`）。
- `wallClockUs_` 实际存储 `steadyNowUs()`（命名误导：是 monotonic，非 CLOCK_REALTIME）。
- realtime master clock = `realtimeFirstPtsUs_ + (steadyNowUs() - realtimeStartWallUs_)`（PTS 锚点 + monotonic 流逝）。

## Wall Clock Contract
- 当前无跨设备 wall-clock（CLOCK_REALTIME/NTP）同步链路；`wallClockUs_` 是 monotonic，不可与远端时间直接相减。
- 严格禁止：`local monotonic - media PTS`、`receiver monotonic - sender monotonic` 作为 latency 度量。

## Existing Player Clock Audit
- `effectiveSyncMaster`：audio 仅当 `syncMaster=audio` 且 `audioPlaybackClockValid` 且非 stale；否则 video。
- `resolveMasterClockUs`：AUDIO→`audioPlaybackClockUs`；VIDEO→realtime clock（PTS+monotonic 流逝）。
- `lastVideoDelayUs`：`masterClockUs - ptsUs`（丢帧/丢包判据），realtime 下基于 monotonic 参考。
- 这些 clock 可被后续 LAT1/LAT2 安全使用（均为统一 media 或统一 monotonic 基准）。

## Packet / Frame Count Gap Semantics
`videoPacketCount - videoFrameCount ≈ 6` 是 **PIPELINE_DEPTH_HEURISTIC**，非 MEASURED_LATENCY：
- 不假设 1 packet = 1 frame：存在 codec parser 聚合、参数/NAL 包、帧内多包等可能。
- 仅作辅助观察；真实 latency 必须由 LAT1 的 PTS 时间戳差测量。

## Codec2 Output Delay Observation
`CODEC_OUTPUT_DELAY_OBSERVATION`：日志 `CCodecBufferChannel ... out=8` 表示 Codec2 缓冲通道的 in/pipeline/out 计数观察，**不能直接解释为 8 帧解码延迟**。是否构成实际 latency 贡献须 LAT1/LAT4 用 PTS A/B 判定。未修改 MediaCodec 配置。

## Renderer Timing Boundary
`NativeNv12GlRenderer.cpp` 计时点：

| 指标 | 测量范围 |
|---|---|
| `copyCostUs` → `lastNv12GlUploadCostUs` | `steadyNowUs()` 在 `glActiveTexture/glBindTexture` 前，结束于两次 `glTexSubImage2D`（Y 与 UV）之后 |
| `postCostUs` | `eglSwapBuffers` 前后 |
| `totalCostUs` → `lastNv12GlRenderCostUs` | 函数入口（含 EGL 上下文准备、纹理上传、绘制、swap 返回）到返回 |

上传仅覆盖 GPU 纹理上传，不含 acquire/surface 等待；渲染含 swap 阻塞（可能包含 vsync 等待）。avg/max 由 `recordCost` 累加，NV12 成功渲染才采样。

## Existing Network Diagnostics
- `lastReadFrameCostUs/avg/max`（av_read_frame 耗时，realtime 下可用）
- `read stall detected`（read cost ≥1s 日志）
- `lastReadPacketTimeMs`、`lastDisconnectTimeMs`、reconnect reason 字段
- 无完整端到端网络延迟统计（属 LAT5）。

## Audio Out-of-Scope Observation
`AUDIO_ANOMALY_OUT_OF_SCOPE`：若出现 `sourceHasAudio=true` / `audioDecodeOpened=true` / `audioPacketCount=0`，仅记录，不在本 Phase 修复（避免扩大范围）。

## LAT1 Metric Contract
LAT0 冻结（不实现）：新增同一 video media timeline PTS 字段：
- `latestVideoPacketPtsUs`
- `latestDecoderInputPtsUs`
- `latestDecodedFramePtsUs`
- `latestRenderedFramePtsUs`
- 对应 validity flags

## LAT2 Metric Contract
LAT0 冻结（不实现）：统一 monotonic clock 的阶段计时事件：T0 read 返回 / T1 decoder submit / T2 decoder output / T3 render begin / T4 render submit-swap 返回。明确 Media PTS backlog 与 monotonic processing cost 分开统计。

## Build
- `git diff --check`：PASS（仅 LF→CRLF 提示）
- `:ffmpegplayer:assembleDebug`：PASS
- `:ffmpegplayer:assembleRelease`：PASS
- `:app:assembleDebug`：PASS
- 修改仅限 NativePlayer.h/.cpp（新增 5 个 atomic 成员 + getStats 快照差分 + 3 个新 JSON 字段），无行为/算法变更。

## Runtime / Log Validation
`NOT_EXECUTED`（无设备/RTSP 环境）。既有长稳日志证据（packet-frame gap≈6、renderer cost、Codec2 out=8、fps=21）为 `OFFLINE_LOG_VALIDATION`。

## Remaining Risks
- `wallClockUs_` 命名误导（实际 monotonic），不影响逻辑但应记录。
- measured FPS 依赖 getStats 轮询频率；Demo 当前约 1s 轮询，满足 ≥1s 窗口。
- 真实 latency 判定需 LAT1 PTS A/B 才能闭环。

## LAT0 Freeze
**YES** — 基线已冻结、fps 来源已定位、FPS/时间基准/计时边界已梳理、packet-frame gap 与 Codec2 out=8 未被误解释、LAT1/LAT2 Contract 已冻结、无播放行为/参数修改、三项构建 PASS。

---
**Answers:**
1. 当前实际长期 render rate：**≈25 fps（既有长稳日志）**
2. 当前 legacy Stats fps：**21**
3. legacy fps 来源：**AVStream::avg_frame_rate（SDP/container 元数据），fallback r_frame_rate/25.0**
4. metadataFps 与 measuredRenderFps 是否为同一概念：**NO**
5. packet-frame count gap 是否可直接视为 latency：**NO（PIPELINE_DEPTH_HEURISTIC）**
6. Codec2 out=8 是否可直接视为 8-frame latency：**NO（CODEC_OUTPUT_DELAY_OBSERVATION）**
7. Media PTS 是否可与 local monotonic 直接相减：**NO**
8. 跨设备 monotonic clock 是否可直接相减：**NO**
9. LAT1 是否将使用统一 video media timeline PTS：**YES**
10. LAT2 是否将使用统一 monotonic clock：**YES**
11. Playback behavior 是否改变：**NO**
12. Low-latency 参数是否修改：**NO**