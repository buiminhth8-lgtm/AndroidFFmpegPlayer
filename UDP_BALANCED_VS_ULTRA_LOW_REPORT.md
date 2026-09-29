# UDP Balanced vs UDP Ultra Low 严格 A/B 测试

- 总体判定：**PASS**
- Balanced：**PASS**，有效 300.405 s。
- Ultra Low：**PASS**，有效 300.491 s。
- 不变量检查：通过。

## A/B 不变量

| 项目 | BALANCED | ULTRA_LOW |
|---|---|---|
| Source URL SHA-256 | `7b1acfbb13767a5a4156e6e63cf33b43d10894929081d0dd4b20f95866badc75` | `7b1acfbb13767a5a4156e6e63cf33b43d10894929081d0dd4b20f95866badc75` |
| APK SHA-256 | `cb5379cb154ef516e32c18aa920aac635c7af73e70cf7a0ace9b692e72cd3876` | `cb5379cb154ef516e32c18aa920aac635c7af73e70cf7a0ace9b692e72cd3876` |
| 测试时 Git HEAD | `19b20c623221d984c6c188dfadfa8f97b9f3bc98` | `19b20c623221d984c6c188dfadfa8f97b9f3bc98` |
| Codec / resolution | hevc / 1280×720 | hevc / 1280×720 |
| PTS-derived FPS | 25.000 | 25.000 |
| Decoder / hardware | hevc_mediacodec / True | hevc_mediacodec / True |
| Renderer / mode | nv12_gl / mediacodec_nv12_gl | nv12_gl / mediacodec_nv12_gl |
| Audio | OFF | OFF |

## 实际生效配置

| 参数 | BALANCED | ULTRA_LOW |
|---|---:|---:|
| effectiveRtspTransport | udp | udp |
| latencyMode | balanced | ultra_low_latency |
| socketBufferSize | 262144 | 262144 |
| maxDelayUs | 100000 | 0 |
| effectiveFmtCtxMaxDelayUs | 100000 | 0 |
| reorderQueueSize | 4 | 0 |
| fflagsNoBuffer | True | True |
| avioDirect | False | True |
| enablePacketDrop | False | True |
| enableFrameDrop | True | True |
| enableLatestFrameOnly | False | True |
| dropLatePacketThresholdUs | 500000 | 80000 |
| dropLateFrameThresholdUs | 300000 | 80000 |
| probesize | 131072 | 32768 |
| analyzeduration | 100000 | 0 |

## Stage Latency

| Stage | Balanced P50/P95/P99 ms | Ultra P50/P95/P99 ms | ΔP50 ms | Improvement P50 |
|---|---:|---:|---:|---:|
| READ | 31.441/38.573/40.056 | 31.314/38.754/40.731 | -0.127 | +0.40% |
| DEMUX | 0.036/0.049/0.128 | 0.042/0.083/0.188 | +0.006 | -16.67% |
| DECODE | 41.595/47.850/50.001 | 41.745/48.711/52.151 | +0.150 | -0.36% |
| QUEUE | 0.011/0.018/0.056 | 2.678/4.611/6.464 | +2.667 | -24245.45% |
| RENDER | 3.149/4.856/6.245 | 3.487/6.239/8.341 | +0.338 | -10.73% |
| TOTAL | 45.776/52.279/54.789 | 49.020/57.919/64.292 | +3.244 | -7.09% |

| Stage | Balanced avg/max/count | Ultra avg/max/count |
|---|---:|---:|
| READ | 31.100/49.845/7509 | 30.767/58.887/7512 |
| DEMUX | 0.040/1.422/7507 | 0.049/1.687/7493 |
| DECODE | 42.559/60.604/7507 | 42.710/74.673/7493 |
| QUEUE | 0.013/0.452/7507 | 2.765/19.814/7493 |
| RENDER | 3.203/11.195/7507 | 3.627/15.772/7493 |
| TOTAL | 45.817/67.415/7507 | 49.152/84.675/7493 |

READ 单独表示 `av_read_frame` 耗时；TOTAL 为 T0 包就绪到 T4 Surface 提交，未将 READ 与 TOTAL 相加。

## Backlog

| Backlog | Balanced P50/P95/P99 ms | Ultra P50/P95/P99 ms | ΔP50 ms |
|---|---:|---:|---:|
| DEMUX | 0.000/39.700/40.900 | 0.000/40.100/41.600 | +0.000 |
| DECODE | 40.000/41.800/43.300 | 40.000/42.300/43.200 | +0.000 |
| RENDER | 0.000/0.000/0.000 | 0.000/39.400/41.100 | +0.000 |
| CLIENT_TOTAL | 40.000/79.800/81.700 | 40.100/80.600/82.700 | +0.100 |

| Backlog | Balanced avg/max/count | Ultra avg/max/count |
|---|---:|---:|
| DEMUX | 2.890/41.300/291 | 5.387/43.800/291 |
| DECODE | 39.747/43.400/291 | 39.993/44.700/291 |
| RENDER | 0.140/41.000/291 | 2.605/41.500/291 |
| CLIENT_TOTAL | 42.778/83.200/291 | 47.985/82.900/291 |

## 流畅度、稳定性与资源

| Metric | BALANCED | ULTRA_LOW |
|---|---:|---:|
| Source FPS | 25.0 | 25.0 |
| Decode FPS | 24.99625505567484 | 24.945838644085846 |
| Render FPS | 24.999583895074984 | 24.945838644085846 |
| Frame interval P50/P95/P99 ms | 39.885/41.280/41.800 | 39.885/41.640/42.917 |
| Frame interval avg/max ms | 40.011/42.000 | 40.106/44.174 |
| Actual bitrate Mbit/s | 0.9999822905743914 | 1.0004047775141351 |
| CPU avg/p95/max % | 44.81/50.13/51.27 | 46.28/49.65/51.82 |
| PSS avg/max MiB | 61.68/64.31 | 54.75/57.45 |
| PSS slope MiB/min | -0.496 | -0.329 |
| Native heap avg/max MiB | 19.40/19.49 | 20.58/20.71 |
| Java heap avg/max MiB | 3.44/4.66 | 3.48/4.80 |
| Threads avg/max | 26.94/27 | 26.94/27 |
| droppedVideoPacketCount | 0 | 0 |
| packetDropBeforeDecodeCount | 0 | 0 |
| droppedVideoFrameCount | 0 | 1 |
| frameDropBeforeRenderCount | 0 | 1 |
| hardwareDroppedFrameCount | 0 | 0 |
| softwareDroppedFrameCount | 0 | 1 |
| latePacketDropCount | 0 | 0 |
| lateFrameDropCount | 0 | 0 |
| latestFrameReplaceCount | 0 | 1 |
| catchUpDropCount | 0 | 0 |
| dropUntilKeyFrameCount | 0 | 0 |
| readTimeoutCount | 0 | 0 |
| readEagainCount | 0 | 0 |
| readEofCount | 0 | 0 |
| readErrorCount | 0 | 0 |
| reconnectAttemptCount | 0 | 0 |
| reconnectSuccessCount | 0 | 0 |
| stageTimingClockAnomalyCount | 0 | 0 |
| stageTimingForcedEvictionCount | 0 | 0 |
| nv12GlFallbackFrameCount | 0 | 0 |
| nv12GlNoSurfaceFrameCount | 0 | 0 |

## Decoder API 与 NV12/EGL

- Balanced send avg/max：2516/12440 µs；receive avg/max：14/10800 µs。
- Ultra Low send avg/max：2660/16499 µs；receive avg/max：15/6025 µs。
- NV12 upload avg/max（µs）：Balanced 1375/6017，Ultra 1494/10234。
- NV12 GL render avg/max（µs）：Balanced 3149/11128，Ultra 3557/15700。
- EGL context/surface 创建：Balanced 1/1，Ultra 1/1。

## Conclusion

- TOTAL P50：Balanced 45.776 ms，Ultra 49.020 ms，增加 3.244 ms（改善率 -7.09%）。
- TOTAL P95：Balanced 52.279 ms，Ultra 57.919 ms，增加 5.640 ms（改善率 -10.79%）。
- TOTAL P99：Balanced 54.789 ms，Ultra 64.292 ms，增加 9.503 ms（改善率 -17.34%）。
- READ P50 变化 -0.127 ms，P95 变化 +0.181 ms。虽然 Ultra Low 的 `max_delay=0`、`reorder_queue_size=0`、`avioDirect=true` 已生效，本轮没有测得实际输入等待收益。
- DECODE P50 变化 +0.150 ms；Decoder backlog P50 变化 +0.000 ms，P95 变化 +0.500 ms。
- Ultra Low 的 QUEUE P50 增加 2.667 ms，是 TOTAL P50 增加 3.244 ms 的主要可见来源；CLIENT_TOTAL backlog P50/P95 分别变化 +0.100/+0.800 ms。
- Ultra Low 发生 1 次 latest-frame 替换；late packet/frame、硬件 drop、timeout、EOF、read error、reconnect 均为 0。
- Render FPS 变化 -0.054 fps；帧间隔 P95/P99 分别变化 +0.360/+1.117 ms，Ultra Low 有轻微流畅度退化。
- CPU 平均值变化 +1.47 个百分点；PSS 平均值变化 -6.94 MiB。
- Decoder P50 基本不变，说明约一帧 Decoder pipeline latency 与 FFmpeg/RTP buffering 基本无关。

## 图表

`01_stage_latency_comparison.png`、`02_backlog_comparison.png`、`03_fps_comparison.png`、`04_drop_comparison.png`、`05_cpu_memory_comparison.png`、`06_latency_trend_comparison.png`。
