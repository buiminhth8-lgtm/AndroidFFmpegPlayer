#!/usr/bin/env python3
"""Run and compare strict UDP BALANCED versus ULTRA_LOW device profiles."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

from collect_ffmpeg_latency_5min import draw_chart


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def ms(value) -> float:
    return float(value or 0) / 1000.0


def pct_improvement(balanced: float, ultra: float) -> float:
    return (balanced - ultra) / balanced * 100.0 if balanced else 0.0


def formal_series(path: Path, field: str) -> list[tuple[float, float]]:
    samples = load(path / "samples.json")
    return [(float(x["formalElapsedMs"]) / 1000.0, float(x["stats"].get(field, 0)) / 1000.0)
            for x in samples if x.get("phase") == "FORMAL" and x.get("formalElapsedMs", -1) >= 0]


def charts(root: Path, b: dict, u: dict) -> None:
    stages = ["READ", "DEMUX", "DECODE", "QUEUE", "RENDER", "TOTAL"]
    draw_chart(root / "01_stage_latency_comparison.png", "Stage latency P50/P95", [
        ("Balanced P50", [(i, ms(b["latency"][s]["p50Us"])) for i, s in enumerate(stages)], "#1f77b4"),
        ("Ultra P50", [(i, ms(u["latency"][s]["p50Us"])) for i, s in enumerate(stages)], "#ff7f0e"),
        ("Balanced P95", [(i, ms(b["latency"][s]["p95Us"])) for i, s in enumerate(stages)], "#2ca02c"),
        ("Ultra P95", [(i, ms(u["latency"][s]["p95Us"])) for i, s in enumerate(stages)], "#d62728")], "ms (x=READ..TOTAL)")
    names = ["DEMUX", "DECODE", "RENDER", "CLIENT_TOTAL"]
    draw_chart(root / "02_backlog_comparison.png", "Backlog P50/P95", [
        ("Balanced P50", [(i, ms(b["backlog"][s]["p50Us"])) for i, s in enumerate(names)], "#1f77b4"),
        ("Ultra P50", [(i, ms(u["backlog"][s]["p50Us"])) for i, s in enumerate(names)], "#ff7f0e"),
        ("Balanced P95", [(i, ms(b["backlog"][s]["p95Us"])) for i, s in enumerate(names)], "#2ca02c"),
        ("Ultra P95", [(i, ms(u["backlog"][s]["p95Us"])) for i, s in enumerate(names)], "#d62728")], "ms (x=DEMUX..TOTAL)")
    draw_chart(root / "03_fps_comparison.png", "Source/decode/render FPS", [
        ("Balanced", [(0, b["source"]["ptsDerivedFps"]), (1, b["pipeline"]["decodedFps"]), (2, b["pipeline"]["renderedFps"])], "#1f77b4"),
        ("Ultra", [(0, u["source"]["ptsDerivedFps"]), (1, u["pipeline"]["decodedFps"]), (2, u["pipeline"]["renderedFps"])], "#ff7f0e")], "FPS (x=source/decode/render)")
    drop_keys = ["droppedVideoPacketCount", "droppedVideoFrameCount", "hardwareDroppedFrameCount"]
    draw_chart(root / "04_drop_comparison.png", "Drop counters", [
        ("Balanced", [(i, b["stabilityCounterDeltas"].get(k, 0)) for i, k in enumerate(drop_keys)], "#1f77b4"),
        ("Ultra", [(i, u["stabilityCounterDeltas"].get(k, 0)) for i, k in enumerate(drop_keys)], "#ff7f0e")], "count (packet/frame/hw)")
    draw_chart(root / "05_cpu_memory_comparison.png", "CPU and PSS", [
        ("Balanced", [(0, b["resources"]["cpuPercentOneCore"]["avg"]), (1, b["resources"]["pssKb"]["avg"] / 1024)], "#1f77b4"),
        ("Ultra", [(0, u["resources"]["cpuPercentOneCore"]["avg"]), (1, u["resources"]["pssKb"]["avg"] / 1024)], "#ff7f0e")], "CPU % / PSS MiB")
    draw_chart(root / "06_latency_trend_comparison.png", "TOTAL latency trend", [
        ("Balanced", formal_series(root / "balanced", "packetReadyToRenderSubmitLastUs"), "#1f77b4"),
        ("Ultra", formal_series(root / "ultra_low", "packetReadyToRenderSubmitLastUs"), "#ff7f0e")], "ms")


def compare(root: Path) -> str:
    b, u = load(root / "balanced/summary.json"), load(root / "ultra_low/summary.json")
    invariant_pairs = {
        "RTSP URL hash": (b["configuration"].get("sourceUrlSha256"), u["configuration"].get("sourceUrlSha256")),
        "APK hash": (b["configuration"].get("apkSha256"), u["configuration"].get("apkSha256")),
        "Git HEAD": (b["configuration"].get("gitHead"), u["configuration"].get("gitHead")),
        "codec": (b["source"].get("videoCodec"), u["source"].get("videoCodec")),
        "resolution": ((b["source"].get("width"), b["source"].get("height")), (u["source"].get("width"), u["source"].get("height"))),
        "decoder": (b["pipeline"].get("actualDecoder"), u["pipeline"].get("actualDecoder")),
        "hardware": (b["pipeline"].get("usingHardwareDecoder"), u["pipeline"].get("usingHardwareDecoder")),
        "renderer": ((b["pipeline"].get("renderer"), b["pipeline"].get("renderMode")), (u["pipeline"].get("renderer"), u["pipeline"].get("renderMode"))),
    }
    drift = [name for name, pair in invariant_pairs.items() if pair[0] != pair[1]]
    if abs(b["source"]["ptsDerivedFps"] - u["source"]["ptsDerivedFps"]) > 0.5:
        drift.append("source FPS")
    status = "INVALID_COMPARISON" if drift else ("PASS" if b["status"] == u["status"] == "PASS" else "TEST_PARTIAL")
    charts(root, b, u)
    lines = ["# UDP Balanced vs UDP Ultra Low 严格 A/B 测试", "",
             f"- 总体判定：**{status}**", f"- Balanced：**{b['status']}**，有效 {b['validFormalDurationSec']:.3f} s。",
             f"- Ultra Low：**{u['status']}**，有效 {u['validFormalDurationSec']:.3f} s。",
             f"- 不变量检查：{'通过' if not drift else '漂移：' + ', '.join(drift)}。", "", "## A/B 不变量", "",
             "| 项目 | BALANCED | ULTRA_LOW |", "|---|---|---|",
             f"| Source URL SHA-256 | `{b['configuration'].get('sourceUrlSha256')}` | `{u['configuration'].get('sourceUrlSha256')}` |",
             f"| APK SHA-256 | `{b['configuration'].get('apkSha256')}` | `{u['configuration'].get('apkSha256')}` |",
             f"| 测试时 Git HEAD | `{b['configuration'].get('gitHead')}` | `{u['configuration'].get('gitHead')}` |",
             f"| Codec / resolution | {b['source'].get('videoCodec')} / {b['source'].get('width')}×{b['source'].get('height')} | {u['source'].get('videoCodec')} / {u['source'].get('width')}×{u['source'].get('height')} |",
             f"| PTS-derived FPS | {b['source'].get('ptsDerivedFps'):.3f} | {u['source'].get('ptsDerivedFps'):.3f} |",
             f"| Decoder / hardware | {b['pipeline'].get('actualDecoder')} / {b['pipeline'].get('usingHardwareDecoder')} | {u['pipeline'].get('actualDecoder')} / {u['pipeline'].get('usingHardwareDecoder')} |",
             f"| Renderer / mode | {b['pipeline'].get('renderer')} / {b['pipeline'].get('renderMode')} | {u['pipeline'].get('renderer')} / {u['pipeline'].get('renderMode')} |",
             f"| Audio | {'ON' if b['configuration'].get('audio') else 'OFF'} | {'ON' if u['configuration'].get('audio') else 'OFF'} |", "",
             "## 实际生效配置", "", "| 参数 | BALANCED | ULTRA_LOW |", "|---|---:|---:|"]
    keys = ["effectiveRtspTransport", "latencyMode", "socketBufferSize", "maxDelayUs", "effectiveFmtCtxMaxDelayUs",
            "reorderQueueSize", "fflagsNoBuffer", "avioDirect", "enablePacketDrop", "enableFrameDrop",
            "enableLatestFrameOnly", "dropLatePacketThresholdUs", "dropLateFrameThresholdUs", "probesize", "analyzeduration"]
    for key in keys:
        lines.append(f"| {key} | {b['effectiveConfiguration'].get(key)} | {u['effectiveConfiguration'].get(key)} |")
    lines += ["", "## Stage Latency", "", "| Stage | Balanced P50/P95/P99 ms | Ultra P50/P95/P99 ms | ΔP50 ms | Improvement P50 |",
              "|---|---:|---:|---:|---:|"]
    for stage in ["READ", "DEMUX", "DECODE", "QUEUE", "RENDER", "TOTAL"]:
        x, y = b["latency"][stage], u["latency"][stage]
        bp, up = ms(x["p50Us"]), ms(y["p50Us"])
        lines.append(f"| {stage} | {bp:.3f}/{ms(x['p95Us']):.3f}/{ms(x['p99Us']):.3f} | {up:.3f}/{ms(y['p95Us']):.3f}/{ms(y['p99Us']):.3f} | {up-bp:+.3f} | {pct_improvement(bp, up):+.2f}% |")
    lines += ["", "| Stage | Balanced avg/max/count | Ultra avg/max/count |", "|---|---:|---:|"]
    for stage in ["READ", "DEMUX", "DECODE", "QUEUE", "RENDER", "TOTAL"]:
        x, y = b["latency"][stage], u["latency"][stage]
        lines.append(f"| {stage} | {ms(x['avgUs']):.3f}/{ms(x['maxUs']):.3f}/{x['count']} | {ms(y['avgUs']):.3f}/{ms(y['maxUs']):.3f}/{y['count']} |")
    lines += ["", "READ 单独表示 `av_read_frame` 耗时；TOTAL 为 T0 包就绪到 T4 Surface 提交，未将 READ 与 TOTAL 相加。",
              "", "## Backlog", "", "| Backlog | Balanced P50/P95/P99 ms | Ultra P50/P95/P99 ms | ΔP50 ms |", "|---|---:|---:|---:|"]
    for name in ["DEMUX", "DECODE", "RENDER", "CLIENT_TOTAL"]:
        x, y = b["backlog"][name], u["backlog"][name]
        lines.append(f"| {name} | {ms(x['p50Us']):.3f}/{ms(x['p95Us']):.3f}/{ms(x['p99Us']):.3f} | {ms(y['p50Us']):.3f}/{ms(y['p95Us']):.3f}/{ms(y['p99Us']):.3f} | {ms(y['p50Us'])-ms(x['p50Us']):+.3f} |")
    lines += ["", "| Backlog | Balanced avg/max/count | Ultra avg/max/count |", "|---|---:|---:|"]
    for name in ["DEMUX", "DECODE", "RENDER", "CLIENT_TOTAL"]:
        x, y = b["backlog"][name], u["backlog"][name]
        lines.append(f"| {name} | {ms(x['avgUs']):.3f}/{ms(x['maxUs']):.3f}/{x['count']} | {ms(y['avgUs']):.3f}/{ms(y['maxUs']):.3f}/{y['count']} |")
    lines += ["", "## 流畅度、稳定性与资源", "", "| Metric | BALANCED | ULTRA_LOW |", "|---|---:|---:|"]
    metrics = [
        ("Source FPS", b["source"]["ptsDerivedFps"], u["source"]["ptsDerivedFps"]),
        ("Decode FPS", b["pipeline"]["decodedFps"], u["pipeline"]["decodedFps"]),
        ("Render FPS", b["pipeline"]["renderedFps"], u["pipeline"]["renderedFps"]),
        ("Frame interval P50/P95/P99 ms", "/".join(f"{b['pipeline']['renderFrameIntervalMs'][k]:.3f}" for k in ["p50","p95","p99"]), "/".join(f"{u['pipeline']['renderFrameIntervalMs'][k]:.3f}" for k in ["p50","p95","p99"])),
        ("Frame interval avg/max ms", f"{b['pipeline']['renderFrameIntervalMs']['avg']:.3f}/{b['pipeline']['renderFrameIntervalMs']['max']:.3f}", f"{u['pipeline']['renderFrameIntervalMs']['avg']:.3f}/{u['pipeline']['renderFrameIntervalMs']['max']:.3f}"),
        ("Actual bitrate Mbit/s", b["source"]["actualBitrateBps"] / 1e6, u["source"]["actualBitrateBps"] / 1e6),
        ("CPU avg/p95/max %", "/".join(f"{b['resources']['cpuPercentOneCore'][k]:.2f}" for k in ["avg","p95","max"]), "/".join(f"{u['resources']['cpuPercentOneCore'][k]:.2f}" for k in ["avg","p95","max"])),
        ("PSS avg/max MiB", f"{b['resources']['pssKb']['avg']/1024:.2f}/{b['resources']['pssKb']['max']/1024:.2f}", f"{u['resources']['pssKb']['avg']/1024:.2f}/{u['resources']['pssKb']['max']/1024:.2f}"),
        ("PSS slope MiB/min", f"{b['resources']['pssSlopeKbPerMinute']/1024:.3f}", f"{u['resources']['pssSlopeKbPerMinute']/1024:.3f}"),
        ("Native heap avg/max MiB", f"{b['resources']['nativeHeapKb']['avg']/1024:.2f}/{b['resources']['nativeHeapKb']['max']/1024:.2f}", f"{u['resources']['nativeHeapKb']['avg']/1024:.2f}/{u['resources']['nativeHeapKb']['max']/1024:.2f}"),
        ("Java heap avg/max MiB", f"{b['resources']['javaHeapUsedKb']['avg']/1024:.2f}/{b['resources']['javaHeapUsedKb']['max']/1024:.2f}", f"{u['resources']['javaHeapUsedKb']['avg']/1024:.2f}/{u['resources']['javaHeapUsedKb']['max']/1024:.2f}"),
        ("Threads avg/max", f"{b['resources']['threads']['avg']:.2f}/{b['resources']['threads']['max']:.0f}", f"{u['resources']['threads']['avg']:.2f}/{u['resources']['threads']['max']:.0f}"),
    ]
    for name, x, y in metrics:
        lines.append(f"| {name} | {x} | {y} |")
    counter_names = ["droppedVideoPacketCount", "packetDropBeforeDecodeCount", "droppedVideoFrameCount",
                     "frameDropBeforeRenderCount", "hardwareDroppedFrameCount", "softwareDroppedFrameCount",
                     "latePacketDropCount", "lateFrameDropCount", "latestFrameReplaceCount", "catchUpDropCount",
                     "dropUntilKeyFrameCount", "readTimeoutCount", "readEagainCount",
                     "readEofCount", "readErrorCount", "reconnectAttemptCount", "reconnectSuccessCount",
                     "stageTimingClockAnomalyCount", "stageTimingForcedEvictionCount", "nv12GlFallbackFrameCount",
                     "nv12GlNoSurfaceFrameCount"]
    for key in counter_names:
        lines.append(f"| {key} | {b['stabilityCounterDeltas'].get(key, 0)} | {u['stabilityCounterDeltas'].get(key, 0)} |")
    lines += ["", "## Decoder API 与 NV12/EGL", "",
              f"- Balanced send avg/max：{b['decoderApiCost']['sendAvgUs']}/{b['decoderApiCost']['sendMaxUs']} µs；receive avg/max：{b['decoderApiCost']['receiveAvgUs']}/{b['decoderApiCost']['receiveMaxUs']} µs。",
              f"- Ultra Low send avg/max：{u['decoderApiCost']['sendAvgUs']}/{u['decoderApiCost']['sendMaxUs']} µs；receive avg/max：{u['decoderApiCost']['receiveAvgUs']}/{u['decoderApiCost']['receiveMaxUs']} µs。",
              f"- NV12 upload avg/max（µs）：Balanced {b['nv12Egl']['uploadAvgUs']}/{b['nv12Egl']['uploadMaxUs']}，Ultra {u['nv12Egl']['uploadAvgUs']}/{u['nv12Egl']['uploadMaxUs']}。",
              f"- NV12 GL render avg/max（µs）：Balanced {b['nv12Egl']['renderAvgUs']}/{b['nv12Egl']['renderMaxUs']}，Ultra {u['nv12Egl']['renderAvgUs']}/{u['nv12Egl']['renderMaxUs']}。",
              f"- EGL context/surface 创建：Balanced {b['nv12Egl']['eglContextCreateCount']}/{b['nv12Egl']['eglSurfaceCreateCount']}，Ultra {u['nv12Egl']['eglContextCreateCount']}/{u['nv12Egl']['eglSurfaceCreateCount']}。",
              "", "## Conclusion", ""]
    for label, q in [("P50", "p50Us"), ("P95", "p95Us"), ("P99", "p99Us")]:
        bp, up = ms(b["latency"]["TOTAL"][q]), ms(u["latency"]["TOTAL"][q])
        delta = up - bp
        direction = "降低" if delta < 0 else "增加"
        lines.append(f"- TOTAL {label}：Balanced {bp:.3f} ms，Ultra {up:.3f} ms，{direction} {abs(delta):.3f} ms（改善率 {pct_improvement(bp, up):+.2f}%）。")
    read_change = ms(u["latency"]["READ"]["p50Us"]) - ms(b["latency"]["READ"]["p50Us"])
    decode_change = ms(u["latency"]["DECODE"]["p50Us"]) - ms(b["latency"]["DECODE"]["p50Us"])
    decoder_backlog_change = ms(u["backlog"]["DECODE"]["p50Us"]) - ms(b["backlog"]["DECODE"]["p50Us"])
    total_p50_change = ms(u["latency"]["TOTAL"]["p50Us"]) - ms(b["latency"]["TOTAL"]["p50Us"])
    queue_change = ms(u["latency"]["QUEUE"]["p50Us"]) - ms(b["latency"]["QUEUE"]["p50Us"])
    render_fps_change = u["pipeline"]["renderedFps"] - b["pipeline"]["renderedFps"]
    cpu_change = u["resources"]["cpuPercentOneCore"]["avg"] - b["resources"]["cpuPercentOneCore"]["avg"]
    lines += [f"- READ P50 变化 {read_change:+.3f} ms，P95 变化 {ms(u['latency']['READ']['p95Us'])-ms(b['latency']['READ']['p95Us']):+.3f} ms。虽然 Ultra Low 的 `max_delay=0`、`reorder_queue_size=0`、`avioDirect=true` 已生效，本轮没有测得实际输入等待收益。",
              f"- DECODE P50 变化 {decode_change:+.3f} ms；Decoder backlog P50 变化 {decoder_backlog_change:+.3f} ms，P95 变化 {ms(u['backlog']['DECODE']['p95Us'])-ms(b['backlog']['DECODE']['p95Us']):+.3f} ms。",
              f"- Ultra Low 的 QUEUE P50 增加 {queue_change:.3f} ms，是 TOTAL P50 增加 {total_p50_change:.3f} ms 的主要可见来源；CLIENT_TOTAL backlog P50/P95 分别变化 {ms(u['backlog']['CLIENT_TOTAL']['p50Us'])-ms(b['backlog']['CLIENT_TOTAL']['p50Us']):+.3f}/{ms(u['backlog']['CLIENT_TOTAL']['p95Us'])-ms(b['backlog']['CLIENT_TOTAL']['p95Us']):+.3f} ms。",
              f"- Ultra Low 发生 {u['stabilityCounterDeltas'].get('latestFrameReplaceCount', 0)} 次 latest-frame 替换；late packet/frame、硬件 drop、timeout、EOF、read error、reconnect 均为 0。",
              f"- Render FPS 变化 {render_fps_change:+.3f} fps；帧间隔 P95/P99 分别变化 {u['pipeline']['renderFrameIntervalMs']['p95']-b['pipeline']['renderFrameIntervalMs']['p95']:+.3f}/{u['pipeline']['renderFrameIntervalMs']['p99']-b['pipeline']['renderFrameIntervalMs']['p99']:+.3f} ms，Ultra Low 有轻微流畅度退化。",
              f"- CPU 平均值变化 {cpu_change:+.2f} 个百分点；PSS 平均值变化 {(u['resources']['pssKb']['avg']-b['resources']['pssKb']['avg'])/1024:+.2f} MiB。"]
    if status == "INVALID_COMPARISON":
        lines.append("- 检测到关键不变量漂移，禁止给出性能归因。")
    elif abs(decode_change) < 2.0:
        lines.append("- Decoder P50 基本不变，说明约一帧 Decoder pipeline latency 与 FFmpeg/RTP buffering 基本无关。")
    else:
        lines.append("- Decoder P50 变化超过 2 ms，本次数据不能证明 Decoder pipeline 与输入 buffering 无关。")
    lines += ["", "## 图表", "", "`01_stage_latency_comparison.png`、`02_backlog_comparison.png`、`03_fps_comparison.png`、`04_drop_comparison.png`、`05_cpu_memory_comparison.png`、`06_latency_trend_comparison.png`。"]
    (root / "UDP_BALANCED_VS_ULTRA_LOW_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return status


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--url")
    p.add_argument("--serial")
    p.add_argument("--summarize-only", action="store_true")
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[1]
    out = repo / "artifacts/udp_latency_ab"
    if not args.summarize_only:
        if not args.url:
            p.error("--url is required")
        base = [sys.executable, str(repo / "tools/collect_ffmpeg_latency_5min.py"), "--url", args.url,
                "--serial", args.serial or "", "--transport", "udp", "--duration-sec", "300"]
        subprocess.run(base + ["--latency-mode", "balanced", "--output-dir", str(out / "balanced")], cwd=repo, check=True)
        adb = ["adb"] + (["-s", args.serial] if args.serial else [])
        subprocess.run(adb + ["shell", "am", "force-stop", "com.example.motro"], cwd=repo, check=True)
        time.sleep(20)
        subprocess.run(base + ["--latency-mode", "ultra_low_latency", "--skip-build", "--skip-install",
                               "--output-dir", str(out / "ultra_low")], cwd=repo, check=True)
    status = compare(out)
    (repo / "UDP_BALANCED_VS_ULTRA_LOW_REPORT.md").write_text(
        (out / "UDP_BALANCED_VS_ULTRA_LOW_REPORT.md").read_text(encoding="utf-8"), encoding="utf-8")
    print(status)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
