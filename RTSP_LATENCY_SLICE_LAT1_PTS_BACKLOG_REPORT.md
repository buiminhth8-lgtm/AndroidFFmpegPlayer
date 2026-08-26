# RTSP Latency — Slice LAT1 — Media PTS Backlog Diagnostics

Date: 2026-08-20
Branch: dev (9285471 + LAT1)
Scope: 建立统一 video media timeline PTS 采集链（packet → decoder submit → decoded → rendered），量化 demux/decoder/render/client 四级 media backlog。仅增加诊断，不改变任何播放行为。

## Scope
只做 Video PTS 采集、归一化、validity/generation 跟踪、media backlog 计算、Stats 输出与 Report。未修改 RTSP 参数、MediaCodec 配置、frame drop、sync、pacing、renderer、reconnect、Audio、recording、Thermal。

## LAT0 Baseline
冻结自 `RTSP_LATENCY_SLICE_LAT0_BASELINE_REPORT.md`：
- Media timeline PTS 与 monotonic clock 分离。
- `packet-frame count gap ≈ 6` = PIPELINE_DEPTH_HEURISTIC，非 latency。
- Codec2 `out=8` = CODEC_OUTPUT_DELAY_OBSERVATION，非 8 帧解码延迟。
- `metadataFps`（AVStream 声明）≠ `measuredDecodeFps`/`measuredRenderFps`（实测）。
- 视频主链：RTSP/HEVC → `hevc_mediacodec` → `nv12_cpu` → `nv12_gl` → SurfaceView。

## Video Media Timeline Source
统一 media timeline：video stream 的 `AVStream::time_base`。所有 LAT1 PTS 以 microseconds 表示并属于同一条 video media timeline（presentation 时间轴）。

## PTS Conversion Contract
- 所有 PTS 统一 `av_rescale_q(ts, videoStream->time_base, AV_TIME_BASE_Q)` → microseconds。
- 新增 helper `rescaleToUs(ts, timeBase)`（NativePlayer.cpp），与既有 `renderFrame`/`shouldDropRealtimePacket` 的转换一致。
- 禁止假设 `time_base == 1/1000000`；禁止 `PTS * 1000` 硬编码转换。

## Packet PTS
- 采集点 P0：`playbackLoop` 中 `av_read_frame` 返回后、`packet_->stream_index == videoStreamIndex_` 分支。
- 来源：`packet_->pts`（demux 输出的 presentation timestamp）。
- `packet_->pts == AV_NOPTS_VALUE` 或 `rescaleToUs` 后非法 → `videoPacketPtsValid_=false`、`latestVideoPacketPtsUs_=-1`。
- 未记录 DTS（本流 demux 已给 presentation PTS，且主公式只用 PTS）。

## Decoder Input PTS
- 采集点 P1：`avcodec_send_packet` 成功后（即该 video packet 真正送入 decoder 之后）。
- 语义：刚送入 decoder 的那个 packet 的 presentation timestamp。
- 来源：复用同一 packet 的 P0 PTS（单线程 playback loop，P1 紧邻 P0 同一 packet，无跨包串扰）。
- packet PTS 无效 → `decoderInputPtsValid_=false`；不伪造 fallback PTS；不使用 wall/monotonic clock。

## Decoded Frame PTS
- 采集点 P2：`avcodec_receive_frame` 成功返回 decoded frame 后。
- 来源：`decodedFrame_->best_effort_timestamp`（presentation-order timestamp，与既有 `renderFrame` 同一字段口径）。
- `best_effort_timestamp == AV_NOPTS_VALUE` → `decodedFramePtsValid_=false`。

## Rendered Frame PTS
- 采集点 P3：`renderFrame` 内、`isValidPts(ptsUs)` 分支（该 AVFrame 正式进入 render 主线）。
- 来源：`ptsUs` = `av_rescale_q(frame->best_effort_timestamp, videoStream->time_base, AV_TIME_BASE_Q)`（media timeline）。
- 不在 Renderer 内用 `frame index × duration` 或 `now()` 重新生成 PTS。
- PTS 无效 → `renderedFramePtsValid_=false`。

## PTS Watermark Strategy
- 除 raw latest 外，维护 `maxVideoPacketPtsUs` / `maxDecoderInputPtsUs` / `maxDecodedFramePtsUs` / `maxRenderedFramePtsUs`（当前 generation 内最大有效 presentation PTS）。
- backlog 用 max-seen watermark，而非 last-arrival − last-output，避免 HEVC/B-frame reorder 制造假负 latency。
- 采用 `updateMax`（atomic CAS）热路径轻量更新。

## Generation / Reset Strategy
新增最小 generation：`videoPtsGeneration_` + `resetVideoPtsDiagnostics()`。调用点：
1. `openInput(..., resetStreamMetadata=true)`（prepare / reconnect / transport switch 的新 input session）。
2. `resetRealtimeClockForFormatDiscontinuity()`（decoded format discontinuity，PTS epoch 可能变化）。
3. `resetStats()`（全量统计重置）。
`resetVideoPtsDiagnostics()` 递增 generation、invalidate 所有 latest/watermark/backlog、重置 backward 计数并递增 `latencyPtsResetCount_`。旧 timeline PTS 绝不跨代相减（防 reconnect 后 800s→0s 的假 backlog）。

## AV_NOPTS Handling
- 任一关键阶段缺 PTS：对应 `*Valid=false`，latest 置 -1，绝不转成 0。
- 不基于 measured fps 伪造 PTS。
- backlog 计算要求四路 watermark 均 `>=0`，否则 backlog 全置 -1 且 `clientMediaBacklogValid_=false`。

## Reorder / Backward PTS Handling
- max-seen watermark 天然避免合法 reorder 产生负 backlog。
- 新增 `videoPtsBackwardCount` / `decoderPtsBackwardCount` / `decodedPtsBackwardCount` / `renderedPtsBackwardCount` 轻量诊断（新 PTS < 当前 max 即 +1）。
- 明显负 backlog 不 `max(0, diff)` 掩盖：backlog 若出现负值（异常时序）保留原值，`clientMediaBacklogValid_` 仅在四路 watermark 有效时 true。

## Media Backlog Metrics
在 `getStats()` 内计算并输出（media timeline us）：
- `demuxToDecoderBacklogUs = maxVideoPacketPtsUs − maxDecoderInputPtsUs`
- `decoderBacklogUs = maxDecoderInputPtsUs − maxDecodedFramePtsUs`
- `renderBacklogUs = maxDecodedFramePtsUs − maxRenderedFramePtsUs`
- `clientMediaBacklogUs = maxVideoPacketPtsUs − maxRenderedFramePtsUs`
四路 watermark 任一无效 → 全部 -1，`clientMediaBacklogValid_=false`。

## Count Gap vs PTS Backlog
- `videoPacketCount − videoFrameCount` 保持原有统计，语义不变。
- 真实 backlog 由 PTS 差测量，绝不用 `frameCount / fps` 生成 latency。
- Runtime 未执行，无法数值对比（见 Runtime Samples）。

## Codec2 out=8 Comparison
- 未修改 Codec2 / MediaCodec。
- `out=8`、count gap `~6` 与实测 `decoderBacklogUs` 的对照需 runtime，当前 `NOT_EXECUTED`，不写 `out=8 = 320ms`。

## Runtime Samples
`NOT_EXECUTED`（无设备 / RTSP 环境）。无 30s / 5min / 结束三个窗口的实测 backlog。既有 LAT0 长稳日志证据（gap≈6、renderer cost、Codec2 out=8、fps=21）为 OFFLINE_LOG_VALIDATION。

## Performance Regression
- 热路径仅新增少量 atomic store/load + CAS（`updateMax`）与整数比较，无 Logcat、无 JSON/字符串分配、无重锁。
- 不改变 decode backend（仍 `hevc_mediacodec`）、frame output（仍 `nv12_cpu`）、renderer（仍 `nv12_gl`）、drop 策略、reconnect 策略。
- 三项构建 PASS，未观察到 diagnostics 引入 latency。

## Build
- `git diff --check`：PASS（仅 LF→CRLF 提示，无空白错误）
- `:ffmpegplayer:assembleDebug`：PASS（BUILD SUCCESSFUL）
- `:ffmpegplayer:assembleRelease`：PASS（BUILD SUCCESSFUL）
- `:app:assembleDebug`：PASS（BUILD SUCCESSFUL）
- 无 native/unit test 基础设施（无 src/test / androidTest），未为 LAT1 引入测试框架。

## Remaining Issues
- 实测 backlog 数值、count-gap≈6 与 Codec2 out=8 的相关性需设备 runtime 验证（LAT1 Runtime Validation PENDING）。
- `wallClockUs_` 命名误导（实际 monotonic）为既有已知项，不影响 LAT1。
- `decodedFramePtsValid_` / `renderedFramePtsValid_` 仅反映 latest 有效态；backlog 主公式只依赖 watermark 有效性。

## LAT1 Freeze
**Architecture Freeze: YES**（packet/decoder-input/decoded/rendered PTS 采集正确、统一 media timeline、AV_NOPTS 正确、generation/reset 正确、reorder/backward 有保护、backlog validity 正确、count gap 与 Codec2 out=8 未误解释、diagnostics 不改变播放行为、三项构建 PASS）。
**Runtime Validation: PENDING**（无设备，实测 backlog 未执行）。

---

## Answers
1. Video PTS time base: `AVStream::time_base`（demuxer 提供，非假定 1/1000000）
2. Packet PTS source: `packet_->pts`（av_read_frame 输出，video stream 分支）
3. Decoder input PTS source: 同一 video packet 的 PTS（avcodec_send_packet 成功后读取）
4. Decoded frame presentation PTS source: `best_effort_timestamp`（presentation-order，与 renderFrame 一致）
5. Rendered PTS source: `frame->best_effort_timestamp` 经 rescale 的 `ptsUs`（renderFrame 主线，非 Renderer 重建）
6. All LAT1 PTS normalized to same media timeline us: YES
7. AV_NOPTS converted to zero: NO
8. Generation reset implemented: YES
9. Reorder handled with watermark strategy: YES
10. Current packet-frame count gap: ~6（LAT0 记录；runtime NOT_EXECUTED）
11. Measured demux backlog: NOT_EXECUTED
12. Measured decoder backlog: NOT_EXECUTED
13. Measured render backlog: NOT_EXECUTED
14. Measured client media backlog: NOT_EXECUTED
15. Does ~6 frame heuristic match measured PTS backlog: NOT_EXECUTED
16. Does Codec2 out=8 match measured decoder backlog: NOT_EXECUTED
17. clientMediaBacklogUs equals end-to-end latency: NO
18. Playback behavior changed: NO
19. Low-latency tuning performed: NO
