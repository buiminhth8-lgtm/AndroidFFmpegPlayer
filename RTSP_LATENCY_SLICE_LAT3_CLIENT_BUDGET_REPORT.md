# RTSP Latency — Slice LAT3 — Client Delay Budget & Distribution Freeze

Date: 2026-08-20
Branch: dev (7cad24d + LAT3)
Scope: 将 LAT1/LAT2 瞬时/平均数据升级为有界分布统计（p50/p95/p99），建立 Android Client 两维 Delay Budget，区分 measured/unmeasured 边界，修正一处 LAT2 diagnostics bookkeeping 语义。MEASURE + CLASSIFY + FREEZE，不做任何优化。

## Scope
只做 latency metrics aggregation、percentile/distribution statistics、delay budget classification、diagnostics bookkeeping 语义修正、Stats 输出与 Report。未修改 RTSP/MediaCodec/frame-drop/sync/pacing/decoder/renderer/Audio 行为。

## LAT1 Baseline
冻结自 `RTSP_LATENCY_SLICE_LAT1_PTS_BACKLOG_REPORT.md`：
- Media timeline backlog（压着多少媒体时间）。实测：decoder backlog ≈39~41ms、clientMediaBacklog ≈39~41ms（偶发 80/120ms）、demux/render backlog ≈0、PTS backward=0、无持续 accumulation。
- 语义**保持不变**：`clientMediaBacklogUs` 仍不是 end-to-end latency。

## LAT2 Baseline
冻结自 `RTSP_LATENCY_SLICE_LAT2_STAGE_TIMING_REPORT.md`：
- Local monotonic stage duration（具体 frame 的本机 residence/process）。实测：demux→submit 几十 us、decoder→output ≈40~50ms、output→render 几十 us、render→swap ≈3~5ms、packet→swap ≈45~56ms、clock anomaly=0、unmatched 极少。
- 语义**保持不变**：`packetReadyToRenderSubmitUs` 仍仅覆盖 T0→T4，不含 Pre-T0 / Post-T4。

## Diagnostic Bookkeeping Audit
审计 LAT2 `stageTimingEvictionCount`：原实现完成记录**不退休**，deque 充满 256 后每个新帧都 pop_front，导致 eviction 随 sampleCount 线性增长（sampleCount−eviction≈253~254，固定容量假象）。这是语义 B（正常清理被误计为 eviction）。

**修正（LAT3 唯一允许的 LAT2 diagnostics 语义修正）**：`RenderSubmit` finalize 后立即 `erase` 完成/异常记录，使 deque 只保留 in-flight（未解析）记录。字段更名为 `stageTimingForcedEvictionCount`，仅统计 `UNRESOLVED_RECORD_FORCED_EVICTION`。未改播放器 pipeline。

## Distribution Implementation
- `LatencyDistribution`：固定 `kLatencyDistributionWindow = 1024` 环形窗口，nearest-rank 百分位，`std::mutex` 保护（add 单槽写、snapshot 单次拷贝，critical section 极小）。
- LAT2 stage 分布：per finalized frame 采样（25fps → 1024 ≈ 41s 窗口）。
- LAT1 backlog 分布：getStats 轮询采样（~1s → 1024 ≈ 17min 窗口）。
- 无每帧排序/JSON/String 分配；getStats（~1s）才 snapshot+sort（≤1024 元素）。

## Warm-up vs Steady State
`kStageTimingWarmupSamples = 120`（~25fps ≈5s，越过 freshness flush / keyframe wait / EGL create / MediaCodec warm-up / catch-up）。warm-up 样本排除出 steady-state 分布窗口，但 ALL_TIME `last/avg/max`（recordCost）始终保留全程。`steadyStateValid` 在 sampleCount>120 后置 true。选择依据：~5s 覆盖当前 startup 收敛，且不硬编码更长。

## Media Backlog Distribution
4 个 LAT1 backlog 指标均输出 `p50/p95/p99/avg/max/DistCount`。Runtime 未执行 → 数值 NOT_EXECUTED。

## Local Monotonic Distribution
5 个 LAT2 stage 指标均输出 `p50/p95/p99/DistCount`（last/avg/max 已有）。Runtime 未执行 → NOT_EXECUTED。

## Android Delay Budget
TWO-DIMENSION（不直接相加）：
- **A. Media Backlog Budget**：LAT1 `clientMediaBacklogUs` 等（媒体时间轴领先关系）。
- **B. Local Residence Budget**：LAT2 `packetReadyToRenderSubmitUs = T4−T0`（同一 frame 的本机 monotonic residence）。

`postDemuxLocalResidenceUs = packetReadyToRenderSubmitUs`（T4−T0 合法 total）。**禁止**定义 `trueAndroidDisplayLatencyUs`（Pre-T0/Post-T4 未知）。

## Measured Boundaries
| Stage | 状态 |
|---|---|
| T0→T1 demux return → decoder submit | MEASURED |
| T1→T2 decoder submit → output（MediaCodec residence） | MEASURED |
| T2→T3 decoded output → render begin | MEASURED |
| T3→T4 render begin → EGL submit | MEASURED |
| T0→T4 packet ready → EGL submit | MEASURED |

## Unmeasured Boundaries
- **PRE_T0**（NOT_MEASURED）：socket receive、FFmpeg RTP assembly、RTP reorder waiting、RTSP demux internal buffering、`av_read_frame` blocking/waiting。
- **POST_T4**（NOT_MEASURED）：BufferQueue、SurfaceFlinger、HWC、VSync、scanout、LCD response。

## MediaCodec Residence
`decoderSubmitToOutputUs`（T2−T1）表示 encoded packet 提交到对应 presentation frame 输出的 monotonic residence（含 codec queue / DPB / reorder / hardware processing）。LAT2 离线稳态 ≈40~50ms。

## Renderer Residence
`renderBeginToSubmitUs`（T4−T3）与 `lastNv12GlRenderCostUs` 边界不同（前者含 renderFrame dispatch 开销，后者仅 renderNv12 含 eglSwapBuffers），稳态应大致一致。rendered PTS backlog=0 不代表 GL processing=0，而是无额外整帧媒体队列。

## Codec2 out=8 Conclusion
`CODEC2_OUTPUT_DELAY_VISIBLE_LATENCY_CORRELATION: NOT_CORRELATED_WITH_8_FRAME_VISIBLE_LATENCY` — LAT1/LAT2 持续显示 decoder media backlog ≈1 frame、monotonic residence ≈1 frame，而 out=8；禁止 `8 × frameDuration` 当 latency。

## Packet/Frame Count Gap Conclusion
`PACKET_FRAME_COUNT_GAP = PIPELINE_ACCOUNTING_HEURISTIC`，`NOT LATENCY_METRIC`。count gap 长期多帧但 PTS backlog 仅 ~1 frame，二者不等价。

## Startup Observation
startup 存在 freshness flush / first keyframe wait / EGL context create / MediaCodec warm-up / catch-up，`firstFrameLatency` 与 `steadyState packetReadyToRenderSubmit` 必须分开报告。startup max 不算长期播放延迟。`startToFirstFrameMs` 仍为 lifecycle observation（不修改）。

## Diagnostics Health
- `decoderTimingUnmatchedCount` / `renderTimingUnmatchedCount`：主要来自 AV_NOPTS/startup，应极少。
- `stageTimingForcedEvictionCount`：修正后仅统计 unresolved 强制淘汰，应≈0。
- `stageTimingClockAnomalyCount` / `stageTimingResetCount`：正常应≈0 / 随 generation 递增。

## Performance Regression
无热路径排序；add 仅 1 个 array slot + 1 计数（加短锁）；getStats 才 snapshot+sort。decode/render fps、GL cost、dropped/fallback/reconnect 应不变（需 runtime 确认）。

## Build
- `git diff --check`：PASS（仅 LF→CRLF 提示，无空白错误）
- `:ffmpegplayer:assembleDebug`：PASS
- `:ffmpegplayer:assembleRelease`：PASS
- `:app:assembleDebug`：PASS
- 无 native/unit test 基础设施，未引入测试框架（distribution helper 逻辑由 build + code review 覆盖）。

## Runtime Validation
`NOT_EXECUTED`（无设备 / RTSP 环境）。所有 p50/p95/p99 数值、warm-up 阈值、问题 A~H 需真机 10~15min 验证。

## Remaining Unknowns
PRE_T0（RTSP/network/demux internal）、POST_T4（SurfaceFlinger→LCD）、真实 p50/p95/p99 数值。

## LAT4 vs LAT5 Priority
- MediaCodec residence 稳态仅 ~1 帧、无隐藏 post-demux queue → **LAT4_PRIORITY = LOW / OPTIONAL**（decoder isolation 收益有限）。
- 主要未测区间在 PRE_T0（RTSP transport / RTP / demux buffering）→ **LAT5_PRIORITY = HIGH**（RTSP Transport / Pre-T0 Isolation）。
- 本 Slice 不开始 LAT4/LAT5。

## LAT3 Freeze
**Architecture Freeze: YES**（LAT1/LAT2 语义保留、percentile 有界、warm-up/steady 已区分、media/local 分布可用、measured/unmeasured 边界清晰、client post-demux delay budget 已建立、Codec2 out=8 与 count gap 不再误解释、eviction 语义已修正、无播放行为改变、无性能回归、三项构建 PASS）。
**Runtime Freeze: PENDING**（无设备，未实测分布）。

---

## Delay Budget Tables
| Stage | p50 | p95 | p99 | max | Status |
|---|---:|---:|---:|---:|---|
| Pre av_read_frame / RTP | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | NOT_MEASURED |
| Demux return → decoder submit | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | MEASURED |
| Decoder submit → output | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | MEASURED |
| Decode output → render begin | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | MEASURED |
| Render begin → EGL submit | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | MEASURED |
| Packet ready → EGL submit | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | MEASURED |
| SurfaceFlinger / Display | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | NOT_MEASURED |

| Media Metric | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| Demux backlog | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Decoder backlog | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Render backlog | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |
| Client media backlog | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED | NOT_EXECUTED |

---

## Answers
1. Steady-state measured FPS: ≈25（LAT1/LAT2 离线；LAT3 runtime NOT_EXECUTED）
2. Client media backlog p50: NOT_EXECUTED
3. Client media backlog p95: NOT_EXECUTED
4. Post-demux local p50: NOT_EXECUTED
5. Post-demux local p95: NOT_EXECUTED
6. Decoder residence p50: NOT_EXECUTED
7. Decoder residence p95: NOT_EXECUTED
8. Render submit p50: NOT_EXECUTED
9. Render submit p95: NOT_EXECUTED
10. Sustained latency accumulation: NOT_EXECUTED（离线证据：NO）
11. Hundreds-ms hidden post-demux queue: NOT_EXECUTED（离线证据：NO）
12. Codec2 out=8 equals 8-frame visible latency: NO
13. Packet-frame count gap is latency metric: NO
14. Pre-T0 latency measured: NO
15. Physical display latency measured: NO
16. LAT1 + LAT2 directly summed: NO
17. Playback behavior changed: NO
18. Low-latency optimization performed: NO
19. Recommended next investigation: LAT5
