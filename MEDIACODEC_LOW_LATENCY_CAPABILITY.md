# MediaCodec Low-Latency Capability Probe

- 日期：2026-09-29
- 工具：`app/src/debug/.../MediaCodecLowLatencyCapability.java`（只读探测，不调用 `setParameters()`）
- 探测目标：当前生产路径 HEVC 硬解组件 `c2.qti.hevc.decoder`
- 原始证据：`artifacts/mediacodec_low_latency_ab/capability_probe/mediacodec_low_latency/probe_result.json`（Git 忽略）

## Device

| 字段 | 实测值 |
|---|---|
| Model | Bengal for arm64 |
| Manufacturer | QUALCOMM |
| Android | 13 |
| API | 33 |
| SoC/platform | SM6225 |

## HEVC decoder

| 字段 | 实测值 |
|---|---|
| Mime | video/hevc |
| Codec | `c2.qti.hevc.decoder` |
| Found | true |
| Canonical name | `c2.qti.hevc.decoder` |
| Encoder | false |
| Hardware accelerated | true |
| Software only | false |
| Vendor | true |
| Supported types | `[video/hevc]` |

设备上枚举到的全部 HEVC 解码器（按名称排序）：

```text
OMX.google.hevc.decoder
OMX.qcom.video.decoder.hevc
OMX.qcom.video.decoder.hevc.low_latency
c2.android.hevc.decoder
c2.qti.hevc.decoder
c2.qti.hevc.decoder.low_latency
```

关键观察：除生产路径使用的 `c2.qti.hevc.decoder` 外，Qualcomm 还公开了专门的
`c2.qti.hevc.decoder.low_latency` 与 `OMX.qcom.video.decoder.hevc.low_latency` 变体。
本 Slice 只回答基础 codec 的能力问题，`.low_latency` 变体的独立探测留待后续 Slice。

## FEATURE_LowLatency

| 字段 | 实测值 |
|---|---|
| Available | AVAILABLE（API 30+，本机 33） |
| Supported | **UNSUPPORTED** |
| Required | UNSUPPORTED |

`CodecCapabilities.isFeatureSupported(FEATURE_LowLatency)` 对 `c2.qti.hevc.decoder`
返回 `false`。注意：API 常量存在（`FEATURE_LowLatency` 自 API 30）不等于 codec 支持
该能力——基础 codec 未通过该 feature flag 声明 Low-Latency。

## KEY_LOW_LATENCY / PARAMETER_KEY_LOW_LATENCY

| 字段 | 实测值 |
|---|---|
| `MediaFormat.KEY_LOW_LATENCY` | AVAILABLE（API 30+） |
| `MediaCodec.PARAMETER_KEY_LOW_LATENCY` | AVAILABLE（API 30+） |

两者均为编译期常量（值 `"low-latency"`），本机 API 33 可用。可用性仅表示入口存在，
不代表基础 codec 会响应这些 key。

## Vendor parameter probe

| 字段 | 实测值 |
|---|---|
| Probe supported | SUPPORTED（`getSupportedVendorParameters()` 自 API 31） |
| 命中关键词（latency/low/delay/reorder/qti/vendor）的参数数 | 30 |

`c2.qti.hevc.decoder` 公开的 vendor 参数里没有直接的 `low-latency` key，与 reorder 相关的是：

```text
vendor.qti-ext-dec-picture-order.enable
vendor.qti-ext-dec-timestamp-reorder.value
```

其余命中项为 `qti`/`vendor` 前缀的 crop/interlace/QP/码率等只读信息参数，完整列表见
`probe_result.json`。探测使用临时 decoder 实例并在 `finally` 中 `release()`，无泄漏。

## Conclusion

**UNSUPPORTED**

- 生产路径 HEVC 解码器 `c2.qti.hevc.decoder` 未通过 `FEATURE_LowLatency` 声明支持
  MediaCodec Low-Latency（`isFeatureSupported = false`）。
- 但 API 33 上 Low-Latency 的 key / parameter / feature 常量均 AVAILABLE，且设备公开了
  专门的 `c2.qti.hevc.decoder.low_latency` 解码器变体。后续 A/B 若要真正开启 Low-Latency，
  更可能的路径是切换到 `.low_latency` codec，而不是在基础 codec 上设置
  `KEY_LOW_LATENCY`（本 Slice 未做任何开启操作）。

结论仅使用规范允许的取值：SUPPORTED / UNSUPPORTED / UNKNOWN / UNAVAILABLE；未将
“API 存在”等同于“Codec 支持”。
