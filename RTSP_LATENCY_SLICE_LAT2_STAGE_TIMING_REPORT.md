# RTSP Latency — Slice LAT2 — Local Monotonic Stage Timing Diagnostics

Date: 2026-08-20
Branch: dev (854e28d + LAT2)
Scope: 使用统一 Android monotonic clock 量化视频主链 5 个阶段（T0 demux return → T1 decoder submit → T2 decoded output → T3 render begin → T4 render submit）的本机处理时间。仅增加诊断，不改变任何播放行为。

## Scope
只做 monotonic timestamp 采集、同 frame/PTS 的阶段关联、local processing timing、Stats 输出与 Report。未修改 RTSP 参数、MediaCodec 配置、frame drop、sync/pacing、decoder、renderer、reconnect、Audio、recording、Thermal。

## LAT1 Baseline
冻结自 `RTSP_LATENCY_SLICE_LAT1_PTS_BACKLOG_REPORT.md`：
- LAT1 是 Media timeline backlog（压着多少媒体时间），字段与语义**保持不变**。
- 实测观测（离线日志）：decode/render ≈25fps、PTS backward=0、renderBacklog≈0、demux backlog≈0(偶发~40ms)、decoder backlog≈39~41ms、clientMediaBacklog≈39~41ms(偶发 80ms/120ms)、无持续 accumulation。
- LAT2 是 monotonic stage duration（某个具体 frame 在本机流水线经历的真实时间），与 LAT1 正交。

## Monotonic Clock Source
`LAT2_MONOTONIC_SOURCE=std::chrono::steady_clock`（`steadyNowUs()`，Linux/Android 对应 `CLOCK_MONOTONIC`）。全 LAT2 仅使用这一种时钟，与既有 renderer/decode cost 计时同一 helper。禁止混用 `System.currentTimeMillis`/`CLOCK_REALTIME`/Media PTS/NTP/wall clock。

## Stage Timing Boundaries
| 事件 | 含义 | 位置 |
|---|---|---|
| T0 packetReadyMonoUs | `av_read_frame` 已返回且确认属于 video stream（DEMUX_RETURN_TIME，非 NIC/RTP socket arrival） | `playbackLoop` video packet 分支 |
| T1 decoderSubmitMonoUs | 该 packet 提交到 decoder（紧邻 `avcodec_send_packet`） | `playbackLoop` send 分支（sendStartUs） |
| T2 decodedOutputMonoUs | 对应 presentation frame 从 decoder 输出 | `playbackLoop` `avcodec_receive_frame` 成功后 |
| T3 renderBeginMonoUs | frame 进入 render 主线 | `renderFrame`（PTS 解析后、drop/sync/renderer dispatch 前） |
| T4 renderSubmitMonoUs | render 路径完成（`eglSwapBuffers` 或等价 submit 返回） | 各成功 render 提交点 |

## Packet Ready T0
`steadyNowUs()` 在确认 `packet_->stream_index == videoStreamIndex_` 后立即采集；用 `packet_->pts` rescale 出的 `packetPtsUs` 作为关联 key。非 NIC 收包时间。

## Decoder Submit T1
`sendStartUs = steadyNowUs()` 紧邻 `avcodec_send_packet` 前采集，复用同一 packet 的 PTS（单线程 playback loop）。

## Decoder Output T2
`avcodec_receive_frame` 成功后 `steadyNowUs()`；用 `decodedFrame_->best_effort_timestamp` rescale 出的 `framePtsUs` 作为关联 key。

## Render Begin T3
`renderFrame` 内 PTS 解析后、drop/sync/renderer dispatch 前采集（单点，覆盖全部 render 路径）。

## Render Submit T4
`recordStageTimingRenderSubmit(ptsUs)` 在成功 render 提交点采集：
- NV12 GL 主路径：`renderNv12GlFrame` 返回后（其内部 `eglSwapBuffers` 已返回）。
- RGBA fallback：`renderer_.renderRgba` 成功后。
- YUV GL：`renderSoftwareYuvGlFrame` 成功后。
- MediaCodec direct surface：`av_mediacodec_release_buffer` 成功后。

## PTS / Generation Correlation
- 关联 key = `videoPtsGeneration`（复用 LAT1）+ 归一化 presentation PTS us，`(generation, ptsUs)`。
- T0/T1 由 packet PTS 建记录；T2/T3/T4 由 `best_effort_timestamp` rescale PTS 匹配。
- 禁止只按 frame index / FIFO；禁止跨 generation 匹配。
- 实现为 `std::deque<VideoStageTiming>`，单线程（playback thread）访问，getStats 只读 atomic。

## Reorder Handling
按 PTS key 关联而非 FIFO，允许 submit 顺序 ≠ presentation output 顺序（B-frame/reorder 安全）。同一 generation 内重复 PTS 采用 last-write-wins（packet 侧覆盖），Report 说明该边界；当前流 PTS backward=0 无重复 PTS。

## Unmatched Timing Handling
- 输出侧无匹配 packet 记录：**不伪造**，`decoderTimingUnmatchedCount++`（T2）/ `renderTimingUnmatchedCount++`（T3）。
- 默认 `stageTimingMatchValid=false`，宁可缺数据不造假 latency。

## Timing Record Bound
`kStageTimingMaxRecords = 256` 硬上限；超出淘汰最旧记录并 `stageTimingEvictionCount++`。无长期内存增长。

## Local Stage Metrics
finalize 时（T0~T4 全部有效）计算：
- `demuxReturnToDecoderSubmitUs = T1 - T0`
- `decoderSubmitToOutputUs = T2 - T1`
- `decodedOutputToRenderBeginUs = T3 - T2`
- `renderBeginToSubmitUs = T4 - T3`
- `packetReadyToRenderSubmitUs = T4 - T0`

单调性校验：同一 monotonic clock 下必须 `T0<=T1<=T2<=T3<=T4`，违反则 `stageTimingClockAnomalyCount++` 且该样本 invalid（不 clamp 0）。每阶段输出 last/avg/max + `stageTimingSampleCount`。

## LAT1 vs LAT2 Comparison
- LAT1 `decoderBacklogUs` = 媒体时间 backlog（压着多少媒体时间）。
- LAT2 `decoderSubmitToOutputUs` = 该 encoded packet 提交到对应 presentation frame 输出的 monotonic residence（含 codec queue/DPB/reorder/hardware processing，非纯 CPU decode cost）。
- **禁止直接相加** `clientMediaBacklogUs + packetReadyToRenderSubmitUs` 得到 client latency：二者是同一 pipeline 的不同观察方式，存在重叠；完整 delay budget 属 LAT3。

## Codec2 out=8 Comparison
未修改 Codec2。`out=8` 与实测 `avgDecoderSubmitToOutputUs` 的对照需 runtime；若 `out=8` 但 `decoderSubmitToOutput≈40ms` 则进一步确认 `out=8 != 8-frame visible latency`。当前 NOT_EXECUTED。

## Renderer Timing Comparison
`lastNv12GlRenderCostUs` = `renderNv12` 内 `steadyNowUs()` 从入口到 `eglSwapBuffers` 返回（含 eglSwapBuffers）。LAT2 `renderBeginToSubmitUs`（T4-T3）边界更宽：含 drop 决策、`waitForAudioMasterIfEarly`、`syncSurface`、AGC、`renderNv12` 与提交后处理。二者相关但边界不同，**未修改** `lastNv12GlRenderCostUs` 语义。

## PipelineWatcher Observation
`onInputBufferReleased: frameIndex not found` 若仍出现：仅记录次数、是否与 timing spike 同时发生；除非直接导致 correlation failure / decoder error / 持续 backlog，否则不修（OBSERVATION_ONLY）。

## Runtime Samples
`NOT_EXECUTED`（无设备 / RTSP 环境）。无 30s / 5min / 结束三个窗口实测 stage timing。LAT1 离线日志证据（~40ms backlog）为 OFFLINE_LOG_VALIDATION。

## Diagnostics Overhead
热路径仅新增：`steadyNowUs()`、少量整数比较、bounded deque 线性查找（≤256）+ push/pop、`recordCost`/atomic 更新。无每帧 JSON/Logcat/String/文件 IO；不持锁跨 blocking call（deque 无锁，单线程）。LAT2 不自造 latency。

## Build
- `git diff --check`：PASS（仅 LF→CRLF 提示，无空白错误）
- `:ffmpegplayer:assembleDebug`：PASS（BUILD SUCCESSFUL）
- `:ffmpegplayer:assembleRelease`：PASS（BUILD SUCCESSFUL）
- `:app:assembleDebug`：PASS（BUILD SUCCESSFUL）
- 无 native/unit test 基础设施（无 src/test / androidTest），未为 LAT2 引入测试框架。

## Remaining Issues
- 实测 stage timing 数值、问题 A/B/C（§28）、Codec2 out=8 与 GL render metric 对照需设备 runtime（LAT2 Runtime Validation PENDING）。
- `decodedFramePtsValid_`/`renderedFramePtsValid_`（LAT1 latest 标志）不影响 LAT2 主公式。
- T3 边界定义为 renderFrame render 主线入口（含 drop/sync 决策），`decodedOutputToRenderBeginUs` 因此包含 pre-renderer 排队/等待，符合 §16 观察意图。

## LAT2 Freeze
**Architecture Freeze: YES**（单一 monotonic clock、T0~T4 边界明确、decoder async correlation 正确、generation 隔离、reorder 安全、unmatched 不伪造、correlation storage bounded、LAT1 语义未变、monotonic 不混 Media PTS、T4 未误定义成 physical display、无性能回归、三项构建 PASS）。
**Runtime Validation: PENDING**（无设备，未实测 timing）。

---

## Answers
1. Monotonic clock source: `std::chrono::steady_clock`（`steadyNowUs()`，CLOCK_MONOTONIC）
2. T0 boundary: `av_read_frame` 返回 + 确认 video stream（DEMUX_RETURN_TIME）
3. T1 boundary: `avcodec_send_packet` 提交（sendStartUs）
4. T2 boundary: `avcodec_receive_frame` 成功（decoded output）
5. T3 boundary: `renderFrame` render 主线入口（drop/sync/renderer dispatch 前）
6. T4 boundary: `eglSwapBuffers` 返回（NV12 GL）/ `av_mediacodec_release_buffer` 返回（direct surface）/ `renderRgba` 返回（RGBA）
7. Decoder input/output correlation: PTS+GENERATION
8. Async/reorder safe: YES
9. Cross-generation matching possible: NO
10. Unmatched sample fabricated: NO
11. Average demux-return -> decoder-submit: NOT_EXECUTED
12. Average decoder-submit -> output: NOT_EXECUTED
13. Average decoded-output -> render-begin: NOT_EXECUTED
14. Average render-begin -> submit: NOT_EXECUTED
15. Average packet-ready -> render-submit: NOT_EXECUTED
16. LAT1 client media backlog: ~39~41ms（LAT1 离线观测；LAT2 runtime NOT_EXECUTED）
17. LAT1 + LAT2 directly summed: NO
18. Physical display latency measured: NO
19. Codec2 out=8 treated as 8-frame latency: NO
20. Playback behavior changed: NO
