# Android FFmpeg Player 真实无人机 RTSP 5 分钟性能/延迟报告

- 结论：**PASS**
- 正式有效时长：300.238 s；1 秒 stats 样本：295；系统资源采样约 5 秒一次。
- RTSP transport：udp；latency mode：balanced；Audio：OFF。
- 视频：hevc，1280×720，PTS 中位间隔 40.000 ms（25.000 fps）。
- 解码：hevc_mediacodec / mediacodec，硬件=True，fallback=False。
- 输出/渲染：nv12_cpu → nv12_gl (mediacodec_nv12_gl)。Android 底层组件由 logcat 确认为 `c2.qti.hevc.decoder`。
- 实测：解码 24.870 fps，渲染 24.874 fps，码率 0.998 Mbit/s。
- 渲染帧间隔：avg 40.410 ms，p95 42.458 ms，max 68.067 ms；逐采样窗口 FPS 标准差 1.623。

## 延迟（ms）

| 阶段 | avg | p50 | p95 | p99 | max | count |
|---|---:|---:|---:|---:|---:|---:|
| READ | 31.806 | 31.834 | 40.512 | 47.497 | 468.222 | 7625 |
| DEMUX | 0.037 | 0.031 | 0.064 | 0.129 | 2.534 | 7489 |
| DECODE | 43.592 | 42.204 | 52.070 | 71.762 | 480.001 | 7489 |
| QUEUE | 0.013 | 0.011 | 0.020 | 0.042 | 1.868 | 7489 |
| RENDER | 3.125 | 2.569 | 5.851 | 12.014 | 26.421 | 7489 |
| TOTAL | 46.769 | 45.579 | 56.674 | 80.041 | 484.603 | 7490 |

READ 是 `av_read_frame` 调用耗时（等待网络/协议栈/解复用），TOTAL 是 T0 包就绪至 T4 提交渲染；二者没有相加。
分位数来自最终一次原生有界分布快照，未对逐秒 percentile 求平均。8192 容量覆盖本次完整正式样本。

## Backlog（ms）

| backlog | avg | p50 | p95 | p99 | max | count |
|---|---:|---:|---:|---:|---:|---:|
| DEMUX | 2.283 | 0.000 | 38.700 | 40.200 | 40.700 | 295 |
| DECODE | 42.649 | 40.000 | 42.000 | 159.800 | 239.500 | 295 |
| RENDER | 0.142 | 0.000 | 0.000 | 0.000 | 42.100 | 295 |
| CLIENT_TOTAL | 45.074 | 40.000 | 79.800 | 159.800 | 239.500 | 295 |

## 资源与稳定性

- CPU（单核 100% 口径）：avg 35.36%，p95 37.04%，max 37.99%。
- PSS：avg 51.59 MiB，max 54.67 MiB；RSS max 152.34 MiB；PSS 线性斜率 -0.045 MiB/min。
- Native heap avg/max：20.04/20.77 MiB；Java used heap avg/max：3.48/4.63 MiB。
- 线程：avg 26.9，max 27。
- 正式区间计数差：`{"hardwareDroppedFrameCount": 0, "reconnectAttemptCount": 0, "reconnectSuccessCount": 0, "readTimeoutCount": 0, "readEofCount": 0, "readErrorCount": 0, "videoPtsBackwardCount": 0, "decoderPtsBackwardCount": 0, "decodedPtsBackwardCount": 0, "renderedPtsBackwardCount": 0, "stageTimingForcedEvictionCount": 0, "stageTimingClockAnomalyCount": 0, "nv12GlFallbackFrameCount": 0, "nv12GlNoSurfaceFrameCount": 0}`。
- AAC 元数据存在，但正式区间音频包增量为 0；因此本次无可量化音频时钟/PCM 恢复指标。
- NV12 上传 avg/max：1.316/9.819 ms；NV12 GL 渲染 avg/max：3.089/61.466 ms。
- EGL context/surface 创建次数：1/1。
- MediaCodec API：最后一次 send/receive 1635/1403 µs；组合 decode API avg/max 234/18786 µs。
- Logcat 检查：FATAL EXCEPTION=0，ANR=0，native fatal signal=0，GC events=0。

## 口径与边界

本次是播放器内部管线延迟，不是玻璃到玻璃延迟。无人机端没有同步时钟/光学触发，因此 sender→receiver 和 camera→screen 无法可靠给出；未用 READ+TOTAL 冒充端到端延迟。
测试在 BASIC 模式预热 20 秒，切到 LATENCY 后等待原生 30 帧稳态门控，再连续记录至少 300 秒；正式期间一旦发现非 PLAYING、reconnect、timeout 或 read error 就立即判失败。
本次 TCP 预探测出现源端重复 EOF，不能形成连续有效区间；UDP 短探测稳定后才从零开始本报告的正式运行。该 TCP 预探测未混入正式样本。
实际码率按正式首尾 `videoPacketBytes` 差值 × 8 / 单调时钟实测秒数计算，未使用 RTSP/SDP 元数据码率。

## 趋势图

- `latency_trend.png`：READ 与 T0→T4 各阶段最后一帧样本。
- `backlog_trend.png`：媒体时间轴 backlog。
- `fps_bitrate_trend.png`：逐区间渲染 FPS 与实际视频码率。
- `cpu_memory_trend.png`：进程 CPU/PSS/RSS。
- `resource_error_trend.png`：线程数、drop/reconnect/read error 累计值。
