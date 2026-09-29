# 真实无人机 HEVC GOP / B-frame 分析

- 结论：**当前码流不存在 B-frame**。
- 稳定 GOP：**25 帧，约 1.000 秒**，结构为 `I P P P ... P`（1 个 IDR/I + 24 个 P）。
- `ffprobe has_b_frames=0`；活动 SPS 声明 `sps_max_num_reorder_pics[0]=0`。
- 532/532 个视频包均为 `PTS == DTS`，没有展示顺序倒置，解码顺序与呈现顺序相同。
- 因此当前约 40 ms Decoder backlog 不能归因于 B-frame/reorder。

## 样本与方法

样本由已连接 Android 设备从当前无人机 RTSP 源录制。`PlayerRemuxRecorder` 只 remux 压缩包，不重新编码，因此 MP4 内 HEVC access unit 保留源端帧结构和时间戳。样本时长 21.272 秒，大小 2,428,043 字节，共 532 帧。

分析使用 FFmpeg/ffprobe 8.1.2：

1. `ffprobe -show_streams` 读取 codec/profile/level/`has_b_frames`。
2. `ffprobe -show_frames` 逐帧读取 `pict_type`、key-frame、PTS、packet DTS。
3. `ffprobe -show_packets` 检查包级 PTS/DTS 和呈现顺序。
4. `trace_headers` 直接解析 HEVC VPS/SPS/PPS、NAL type 和 slice type。

可重复工具为 `tools/analyze_hevc_gop.py`。示例：

```powershell
python tools/analyze_hevc_gop.py `
  --input <captured.mp4> `
  --output-dir artifacts/hevc_bframe_ab/stream_baseline `
  --ffprobe <ffprobe.exe> `
  --ffmpeg <ffmpeg.exe>
```

工具输出 `stream.json`、`frames.json`、`packets.json`、`frames.csv`、`trace_headers.log`、`gop_analysis.json` 和 `gop_analysis.txt`。原始样本和大日志位于忽略 Git 的 `artifacts/hevc_bframe_ab/`。

## Stream

| 字段 | 实测值 |
|---|---:|
| Codec | HEVC |
| Profile / level | Main / 5.0 (`general_profile_idc=1`, `general_level_idc=150`) |
| Resolution | 1280×720 |
| Nominal FPS | 25/1 |
| Sample average FPS | 25.010 |
| Time base | 1/90000 |
| Sample duration | 21.272 s |
| Container-reported bitrate | 908,538 bit/s |
| `has_b_frames` | 0 |

## GOP 与帧类型

| 指标 | 实测值 |
|---|---:|
| 总帧数 | 532 |
| I/IDR | 53 |
| P | 479 |
| B | 0 |
| Max consecutive B | 0 |
| 稳定 key-frame interval | 25 帧 |
| 稳定 IDR 周期 | 约 1.000 s |

稳定段 GOP 示例：

```text
I P P P P P P P P P P P P P P P P P P P P P P P P
```

NAL/slice 交叉验证：53 个 `IDR_W_RADL`、479 个 `TRAIL_R`；slice type 为 I=53、P=479、B=0。样本开头有 34 个连续 IDR/I access unit，属于 RTSP 加入/恢复时的源端 IDR burst；之后 key-frame index 为 58、83、108 … 508，间隔全部为 25。稳定 GOP 统计保留启动异常原始计数，只从非相邻 key-frame interval 推导周期。

## PTS / DTS 与顺序

| 检查 | 实测值 |
|---|---:|
| Packet PTS/DTS 可比样本 | 532 |
| Packet `PTS == DTS` | 532 |
| Packet PTS-DTS min/max | 0.000 / 0.000 ms |
| Packet presentation-order inversion | 0 |
| Frame PTS/packet-DTS 可比样本 | 532 |
| Frame `PTS == packet DTS` | 532 |
| Frame interval median | 40.000 ms |
| Frame interval min/max | 36.200 / 44.000 ms |

包级、帧级和 SPS 三条证据一致：当前流不需要呈现重排。

## DPB 与播放器 Decoder

活动 SPS 同时声明：

```text
sps_max_num_reorder_pics[0] = 0
sps_max_dec_pic_buffering_minus1[0] = 1
```

前者表示不需要输出顺序重排；后者允许 temporal layer 0 的 DPB 最多保留 2 张 decoded picture。P 帧仍需要参考图像，所以 **B-frame=0 不等于 DPB=0**。该 DPB 上限不直接证明解码器必须延迟一帧输出。

既有 300 秒真实播放测试确认生产路径使用 `hevc_mediacodec`，Android Codec2 组件为 `c2.qti.hevc.decoder`，输出路径为 `nv12_cpu → nv12_gl`。本次在原生 stats 中补充：

- `videoCodecHasBFrames`、`videoCodecDelay`
- `lastDecodedFramePictType`、`lastDecodedFrameKeyFrame`
- `latestVideoPacketDtsUs`、`lastDecodedFrameDtsUs` 及有效标志

硬解 wrapper 可能不可靠填充 `AVFrame.pict_type`，因此播放器字段用于现场辅助诊断，结论仍以压缩码流逐帧和 HEVC header 分析为主。

## 判断

当前实际流的 B-frame reorder 贡献为 **0 帧**；不存在可形成约 40 ms reorder backlog 的 B 帧或 PTS/DTS 重排。仍可能造成约一帧驻留的路径包括 HEVC P-frame reference/DPB 管理，以及 Qualcomm Codec2/MediaCodec 的内部提交、硬件执行和输出流水线。下一步应针对 MediaCodec low-latency/vendor parameter 与 direct-surface 路径做隔离测试。
