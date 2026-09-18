# RTSP_RECONNECT_MATRIX

执行日期：2026-09-16～2026-09-17

真实设备矩阵结果：**PASS 0 / FAIL 0 / NOT_RUN 32**。

所有 Case 均保持 `NOT_RUN`，因为当前源虽然在 SDP/stream info 中声明 H265 + AAC，但实际采样的 `audioPacketCount` 始终为 0；同时没有可自动控制的源网络、物理链路、RTSP 服务和 404 资源开关。基线或自然 EOF 重连不等同于指定故障注入，因此没有伪造 PASS，也没有把未执行的 Case 标为 FAIL。

## Case 列表

| Case | Transport | Audio | Recording | Fault | Result | 原因 |
|---|---|---:|---:|---|---|---|
| R01 | TCP | OFF | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
| R02 | TCP | OFF | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
| R03 | TCP | ON | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证 PCM/AudioClock |
| R04 | TCP | ON | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证音频播放/录制 |
| R05 | UDP | OFF | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
| R06 | UDP | OFF | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
| R07 | UDP | ON | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证 PCM/AudioClock |
| R08 | UDP | ON | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证音频播放/录制 |
| R09 | TCP | OFF | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
| R10 | TCP | OFF | ON | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
| R11 | TCP | ON | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频恢复 |
| R12 | TCP | ON | ON | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频播放/录制 |
| R13 | UDP | OFF | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
| R14 | UDP | OFF | ON | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
| R15 | UDP | ON | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频恢复 |
| R16 | UDP | ON | ON | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频播放/录制 |
| R17 | TCP | OFF | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
| R18 | TCP | OFF | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
| R19 | TCP | ON | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频恢复 |
| R20 | TCP | ON | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频播放/录制 |
| R21 | UDP | OFF | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
| R22 | UDP | OFF | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
| R23 | UDP | ON | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频恢复 |
| R24 | UDP | ON | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频播放/录制 |
| R25 | TCP | OFF | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
| R26 | TCP | OFF | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
| R27 | TCP | ON | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频恢复 |
| R28 | TCP | ON | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频播放/录制 |
| R29 | UDP | OFF | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
| R30 | UDP | OFF | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
| R31 | UDP | ON | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频恢复 |
| R32 | UDP | ON | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频播放/录制 |

## 已执行的真实设备检查

设备通过 USB adb 连接，RTSP 源可达时完成了 TCP/UDP、Audio ON/OFF、Recording ON/OFF 的基线采样。当前源能解码和渲染 H265：最终 TCP 基线得到 138 帧、UDP 基线得到 141 帧，`effectiveRtspTransport` 分别为 tcp/udp；但所有采样均为 `audioCodec=aac`、`sourceHasAudio=true`、`audioPacketCount=0`，Audio ON 时没有 PCM、AudioTrack write 或有效 AudioClock，录制文件也没有 AAC packet。

还对同一服务器请求了随机不存在的资源路径；服务器仍返回并播放现有视频，没有返回 404。因此当前环境无法产生真实 RTSP 404，首次 404 的代码路径只完成了 host policy 测试，未记为设备 PASS。

该源还会自然返回 EOF。修复前，一次 TCP + Recording 基线在自然 EOF 后成功恢复视频，但 Recorder 在重连后第 3 个包报 `EINVAL` 并停止。修复后，同一设备上观察到连续 15 次自然 EOF/重连，Recorder 保持运行，`writeErrors=0`、`queueDrops=0`；另一次采样成功写入 153 个视频包并完成 2 个 MP4 分片，正常 stop 后队列为 0，ffprobe 可解析视频流。因为这些是源自身 EOF，且无 AAC 数据，所以只作为修复证据，不计入 32 个故障 Case 的 PASS。

## 修复的问题

- 为 `avformat_open_input`、`avformat_find_stream_info` 和 `av_read_frame` 增加独立单调时钟 deadline。UDP 无数据时最多等待配置的 `readTimeoutUs`，interrupt callback 返回后统一按 `ETIMEDOUT` 进入重连，不依靠扩大 timeout。
- 首次 RTSP 404 在启用无限/允许重连策略时进入 `WAITING_SOURCE`，`prepare()` 返回可继续启动的 pending reconnect 状态；后续按既有 1/2/4/5 秒退避重试，资源恢复后继续播放。
- 重连时先推进音频代次、清空 PCM、停止并 join 旧 Audio Worker、使 AudioClock 失效；输入重开成功后才启动新代次，避免旧 PCM、旧时钟和永久静音。
- stop 在播放线程 join 后再次停止 Audio Worker，覆盖 stop 与重连成功并发启动 worker 的窗口；Java `stop()` 只在锁内取得句柄，在锁外执行原生 join，避免播放线程投递事件时反向等待同一 Java 锁，同时保留原有同步事件过滤语义。
- Recorder 重连后的首关键帧可能同时缺失 PTS/DTS。现由 Recorder 显式生成单调 DTS/PTS，并把校正量延续到后续包；只有 mux 成功后才更新 `lastDts`，避免 FFmpeg 隐式补时间戳后本地状态失配导致 `EINVAL`。

## 状态流和判定

正常断线目标流：

```text
PLAYING → DISCONNECTED → RECONNECTING
                         ├─ open 404 → WAITING_SOURCE → retry/backoff
                         └─ open success → RECONNECTED → 首关键帧 → PLAYING
```

首次打开 404：

```text
prepare: 404 → WAITING_SOURCE (pendingReconnect)
start → retry/backoff → source available → RECONNECTED → PLAYING
```

运行器按设备 `elapsedRealtime` 记录 `disconnectDetectedMs` 和 `reconnectElapsedMs`，并输出题目要求的 transport/audio/recording/fault、尝试次数、最终状态、视频/音频/录制恢复、PASS/FAIL/NOT_RUN 和失败原因。Recording Case 还要求分片数增长、AAC 与 H264/H265 均有 packet、零 queue drop/write error、stop 排空以及 ffprobe 通过。

统一入口、配置格式和人工拔线步骤见 `tools/RTSP_RECONNECT_TESTING.md`。原始 URL/凭据不会写入结果；native log 会脱敏。
