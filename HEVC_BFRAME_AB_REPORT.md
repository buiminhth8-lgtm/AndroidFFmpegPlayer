# HEVC B-frame ON/OFF A/B 测试报告

- 配置能力判定：**BFRAME_NOT_CONFIGURABLE**。
- 严格 B-frame ON 300 秒：**NOT_RUN**。
- 严格 B-frame OFF 300 秒：**NOT_RUN**（无法通过 encoder API 明确设置并验证“只改变 B-frame”这一不变量）。
- 当前真实码流本身已经是 B=0；已有同源 300.405 秒 Balanced 数据可作为 **当前 B=0 性能基线**，但不冒充受控 OFF Case。
- 因缺少可配置 B=ON 的同条件源，禁止计算 ON/OFF `Δms` 和 Improvement%。

## 配置能力调查

当前 AndroidFFmpegPlayer 工程只接收/解码/remux RTSP，没有无人机 encoder 控制 SDK 或 B-frame 参数。对连接设备上的控制应用 `com.zhifeng.main`（版本 `1.21-a`）做只读 APK 字符串检查：

| Encoder API 字符串 | APK 中计数 |
|---|---:|
| `getGOP` / `setGOP` | 3 / 5 |
| `getBitrate` / `setBitrate` | 6 / 5 |
| `getFrameRate` / `setFrameRate` | 4 / 8 |
| `BFrame` / `setBFrame` / `maxBFrames` | 0 / 0 / 0 |

可见 SDK 能调 GOP、码率和帧率，但没有找到 B-frame ON/OFF 接口或配置。改变 GOP 长度不等价于改变 B-frame，会破坏“只改变 B-frame”的 A/B 不变量，因此没有调用这些接口，也没有改动分辨率、FPS、码率或 Android 播放器来模拟结果。

## 严格 A/B 状态

| 项目 | B-FRAME ON | B-FRAME OFF |
|---|---:|---:|
| Case status | NOT_RUN | NOT_RUN |
| 原因 | 无可用 ON 配置/API | 无法执行受控 ON/OFF 切换 |
| 300 s strict run | 否 | 否 |
| Bitstream proof | N/A | 当前自然流证明 B=0，但不是设置 API 产生的 Case |
| GOP | N/A | 25：`I + 24P` |
| B-frame count | N/A | 0 / 532（短样本） |
| Max consecutive B | N/A | 0 |
| Δms / Improvement | N/A | N/A |

## 当前 B=0 的真实性能基线

下表来自此前同一设备、同一 RTSP 源、UDP Balanced、`hevc_mediacodec` + `c2.qti.hevc.decoder` + NV12 GL 的有效 300.405 秒正式区间。它说明 **B=0 时仍存在约一帧 Decoder backlog**，但由于没有 B=ON 对照，不能作为严格 A/B 差值。

| 指标 | 当前 B=0 基线 | B=ON 对照 |
|---|---:|---:|
| Source FPS | 25.000 | N/A |
| Actual bitrate | 1.000 Mbit/s | N/A |
| DECODE P50/P95/P99 | 41.595 / 47.850 / 50.001 ms | N/A |
| Decoder backlog P50/P95/P99 | 40.000 / 41.800 / 43.300 ms | N/A |
| TOTAL P50/P95/P99 | 45.776 / 52.279 / 54.789 ms | N/A |
| Render FPS | 24.9996 | N/A |
| Packet / frame / hardware drop | 0 / 0 / 0 | N/A |
| timeout / EOF / read error / reconnect | 0 / 0 / 0 / 0 | N/A |
| PTS backward / clock anomaly | 0 / 0 | N/A |
| CPU avg | 44.81%（单核 100% 口径） | N/A |
| Send API avg/max | 2.516 / 12.440 ms | N/A |
| Receive API avg/max | 0.014 / 10.800 ms | N/A |

该基线的 `avcodec_send_packet()` / `avcodec_receive_frame()` API 调用成本远小于约 40 ms 的中位 pipeline residence；短码流样本又证明 B=0、`PTS=DTS`、`sps_max_num_reorder_pics=0`。所以约一帧驻留不是调用线程同步耗时，也不是 B-frame 呈现重排。

## 根因判断

1. 当前码流实际 B-frame 数为 0，稳定 GOP 是 `I + 24P`，IDR 周期约 1 秒。
2. 当前流的 **B-frame reorder 实际贡献为 0 ms/0 帧**；受控 ON→OFF 延迟差值因无 ON 配置而不可测，不能给出虚构数值。
3. 数据足以排除“当前约一帧延迟主要来自 B-frame reorder”，但不能把所有 HEVC reference buffering 一并排除。
4. SPS 的 `sps_max_dec_pic_buffering_minus1=1` 表明非零 DPB/reference capacity；P 帧仍依赖参考图像。该字段只给上限，不能独立证明 40 ms 来自 DPB。
5. 剩余约一帧延迟更可能位于 Qualcomm Codec2/MediaCodec 固定内部 pipeline，或其 DPB/reference picture 输出策略。后续应保持该 B=0 流，分别验证 MediaCodec low-latency/vendor 配置和 direct-surface 输出，观察 Decoder residence/backlog 是否下降。

## 完成项与限制

- 已完成真实压缩码流抓取、逐帧 I/P/B、NAL/slice、GOP、PTS/DTS 和 SPS DPB/reorder 分析。
- 已提供统一分析入口 `tools/analyze_hevc_gop.py`，原始结果保存于 `artifacts/hevc_bframe_ab/stream_baseline/`。
- 已补充播放器 Decoder stats 字段；硬解 `pict_type` 若为 unknown，仍以 bitstream 结果为准。
- 新 stats 已通过 arm64-v8a/armeabi-v7a 原生编译；设备上的同包名 Debug APK 使用另一张调试证书，Android 以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝覆盖。为避免卸载时清空现有应用数据，本轮没有在设备上运行新字段；既有设备日志中的 decoder/component 信息和 bitstream 证据不受此限制。
- 未运行伪造的 300 秒 ON/OFF Case；状态明确为 `BFRAME_NOT_CONFIGURABLE` / `NOT_RUN`。
