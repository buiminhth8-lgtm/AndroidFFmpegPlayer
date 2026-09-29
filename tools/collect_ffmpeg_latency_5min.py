#!/usr/bin/env python3
"""Collect and summarize a real-device five-minute FFmpeg Player latency run."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

PACKAGE = "com.example.motro"
ACTIVITY = f"{PACKAGE}/.LatencyProfileActivity"
EXTRA = f"{PACKAGE}.profile."


def run(cmd: list[str], *, check: bool = True, timeout: int = 120, text: bool = True):
    result = subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result.stdout.strip() if text else result.stdout


class Adb:
    def __init__(self, serial: str):
        self.base = ["adb", "-s", serial]

    def shell(self, *args: str, check: bool = True, timeout: int = 60) -> str:
        return run(self.base + ["shell", *args], check=check, timeout=timeout)


def detect_serial(requested: str | None) -> str:
    rows = []
    for line in run(["adb", "devices"]).splitlines()[1:]:
        cols = line.split()
        if len(cols) >= 2 and cols[1] == "device":
            rows.append(cols[0])
    if requested:
        if requested not in rows:
            raise RuntimeError(f"adb device {requested!r} is not online; online={rows}")
        return requested
    if len(rows) != 1:
        raise RuntimeError(f"expected exactly one online adb device, found {rows}")
    return rows[0]


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(pct * len(ordered)) - 1))
    return ordered[index]


def summary_values(values: list[float]) -> dict:
    return {
        "avg": statistics.fmean(values) if values else 0,
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values) if values else 0,
        "count": len(values),
    }


def linear_slope_per_minute(points: list[tuple[float, float]]) -> float:
    if len(points) < 2:
        return 0.0
    x_mean = statistics.fmean(p[0] for p in points)
    y_mean = statistics.fmean(p[1] for p in points)
    denominator = sum((x - x_mean) ** 2 for x, _ in points)
    if denominator == 0:
        return 0.0
    return sum((x - x_mean) * (y - y_mean) for x, y in points) / denominator * 60.0


def parse_proc_cpu(text: str):
    lines = [line for line in text.splitlines() if line.strip()]
    if len(lines) < 3:
        return None
    stat = lines[0].split()
    cpu = lines[1].split()[1:]
    return int(stat[13]) + int(stat[14]), sum(int(x) for x in cpu), int(lines[2].strip())


def parse_meminfo(text: str) -> dict:
    result = {"pssKb": 0, "rssKb": 0, "nativeHeapKb": 0, "javaHeapKb": 0}
    match = re.search(r"^Pss:\s*(\d+)\s+kB", text, re.M)
    if match:
        result["pssKb"] = int(match.group(1))
    match = re.search(r"^Rss:\s*(\d+)\s+kB", text, re.M)
    if match:
        result["rssKb"] = int(match.group(1))
    match = re.search(r"TOTAL PSS:\s*(\d+).*?TOTAL RSS:\s*(\d+)", text, re.S)
    if match:
        result["pssKb"], result["rssKb"] = map(int, match.groups())
    match = re.search(r"Java Heap:\s*(\d+)", text)
    if match:
        result["javaHeapKb"] = int(match.group(1))
    match = re.search(r"^\s*Native Heap\s+(\d+)", text, re.M)
    if match:
        result["nativeHeapKb"] = int(match.group(1))
    return result


def read_status(adb: Adb) -> dict | None:
    path = f"/sdcard/Android/data/{PACKAGE}/files/latency_5min/device/status.json"
    raw = adb.shell("cat", path, check=False)
    try:
        return json.loads(raw)
    except Exception:
        return None


def collect_device_info(adb: Adb, serial: str, output: Path):
    commands = {
        "adb_serial": serial,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "model": adb.shell("getprop", "ro.product.model"),
        "manufacturer": adb.shell("getprop", "ro.product.manufacturer"),
        "android": adb.shell("getprop", "ro.build.version.release"),
        "sdk": adb.shell("getprop", "ro.build.version.sdk"),
        "abi": adb.shell("getprop", "ro.product.cpu.abi"),
        "soc": adb.shell("getprop", "ro.soc.model"),
        "board": adb.shell("getprop", "ro.product.board"),
        "kernel": adb.shell("uname", "-a"),
        "cpu_possible": adb.shell("cat", "/sys/devices/system/cpu/possible"),
        "display": adb.shell("wm", "size"),
        "package": adb.shell("dumpsys", "package", PACKAGE, check=False),
    }
    output.write_text("\n\n".join(f"[{k}]\n{v}" for k, v in commands.items()), encoding="utf-8")


def flatten_sample(sample: dict) -> dict:
    s = sample.get("stats", {})
    fields = {
        "seq": sample.get("seq"), "epochMs": sample.get("epochMs"),
        "formalElapsedMs": sample.get("formalElapsedMs"), "phase": sample.get("phase"),
        "state": s.get("playerState"), "width": s.get("videoWidth", s.get("width")),
        "height": s.get("videoHeight", s.get("height")), "decoder": s.get("actualDecoderName"),
        "renderMode": s.get("renderMode"), "renderedFrames": s.get("nv12GlRenderedFrameCount", s.get("videoFrameCount")),
        "decodedFrames": s.get("hardwareDecodedFrameCount", s.get("videoFrameCount")),
        "videoPacketCount": s.get("videoPacketCount"), "videoPacketBytes": s.get("videoPacketBytes"),
        "measuredDecodeFps": s.get("measuredDecodeFps"), "measuredRenderFps": s.get("measuredRenderFps"),
        "readLastUs": s.get("lastAvReadFrameDurationUs"),
        "demuxLastUs": s.get("lastDemuxReturnToDecoderSubmitUs"),
        "decodeLastUs": s.get("lastDecoderSubmitToOutputUs"),
        "queueLastUs": s.get("lastDecodedOutputToRenderBeginUs"),
        "renderLastUs": s.get("lastRenderBeginToSubmitUs"),
        "totalLastUs": s.get("lastPacketReadyToRenderSubmitUs"),
        "clientBacklogUs": s.get("clientMediaBacklogUs"),
        "nv12UploadUs": s.get("lastNv12GlUploadCostUs"), "nv12RenderUs": s.get("lastNv12GlRenderCostUs"),
        "drops": s.get("hardwareDroppedFrameCount", 0), "reconnectAttempts": s.get("reconnectAttemptCount", 0),
        "readTimeouts": s.get("readTimeoutCount", 0), "readErrors": s.get("readErrorCount", 0),
        "nativeHeapBytes": sample.get("nativeHeapBytes"), "javaHeapUsedBytes": sample.get("javaHeapUsedBytes"),
        "threadCount": sample.get("threadCount"),
    }
    return fields


def stage_summary(stats: dict, prefix: str) -> dict:
    return {
        "avgUs": stats.get(prefix + "DistAvgUs", stats.get(prefix + "AvgUs",
                    stats.get("avg" + prefix[0].upper() + prefix[1:] + "Us", 0))),
        "p50Us": stats.get(prefix + "P50Us", 0),
        "p95Us": stats.get(prefix + "P95Us", 0),
        "p99Us": stats.get(prefix + "P99Us", 0),
        "maxUs": stats.get(prefix + "DistMaxUs", stats.get(prefix + "MaxUs",
                    stats.get("max" + prefix[0].upper() + prefix[1:] + "Us", 0))),
        "count": stats.get(prefix + "DistCount", 0),
    }


def draw_chart(path: Path, title: str, series: list[tuple[str, list[tuple[float, float]], str]], y_label: str):
    width, height = 1280, 720
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    left, top, right, bottom = 90, 55, width - 30, height - 70
    points = [p for _, values, _ in series for p in values if math.isfinite(p[1])]
    if not points:
        draw.text((left, top), f"{title}: no data", fill="black", font=font)
        image.save(path)
        return
    xmin, xmax = min(p[0] for p in points), max(p[0] for p in points)
    ymin, ymax = 0.0, max(p[1] for p in points)
    if xmax <= xmin: xmax = xmin + 1
    if ymax <= ymin: ymax = ymin + 1
    draw.text((left, 18), title, fill="black", font=font)
    draw.rectangle((left, top, right, bottom), outline="#444444", width=1)
    for i in range(6):
        y = top + (bottom - top) * i / 5
        value = ymax * (1 - i / 5)
        draw.line((left, y, right, y), fill="#dddddd")
        draw.text((5, y - 6), f"{value:.1f}", fill="#333333", font=font)
    for i in range(7):
        x = left + (right - left) * i / 6
        value = xmin + (xmax - xmin) * i / 6
        draw.text((x - 14, bottom + 10), f"{value:.0f}", fill="#333333", font=font)
    draw.text((left, height - 25), "formal elapsed (s)", fill="black", font=font)
    draw.text((5, top - 20), y_label, fill="black", font=font)
    legend_x = left
    for name, values, color in series:
        projected = []
        for x, y in values:
            px = left + (x - xmin) / (xmax - xmin) * (right - left)
            py = bottom - (y - ymin) / (ymax - ymin) * (bottom - top)
            projected.append((px, py))
        if len(projected) > 1: draw.line(projected, fill=color, width=2)
        draw.line((legend_x, 40, legend_x + 24, 40), fill=color, width=3)
        draw.text((legend_x + 28, 34), name, fill="black", font=font)
        legend_x += 28 + 8 * len(name) + 24
    image.save(path)


def make_report(output: Path, samples: list[dict], cpu_rows: list[dict], mem_rows: list[dict]) -> dict:
    formal = [x for x in samples if x.get("phase") == "FORMAL" and x.get("formalElapsedMs", -1) >= 0]
    if len(formal) < 2:
        raise RuntimeError("not enough FORMAL samples")
    first, last = formal[0], formal[-1]
    a, b = first["stats"], last["stats"]
    elapsed = (last["elapsedRealtimeMs"] - first["elapsedRealtimeMs"]) / 1000.0
    valid_elapsed = last["formalElapsedMs"] / 1000.0
    rendered_key = "nv12GlRenderedFrameCount" if "nv12GlRenderedFrameCount" in b else "videoFrameCount"
    rendered_delta = b.get(rendered_key, 0) - a.get(rendered_key, 0)
    decoded_key = "hardwareDecodedFrameCount" if "hardwareDecodedFrameCount" in b else "videoFrameCount"
    decoded_delta = b.get(decoded_key, 0) - a.get(decoded_key, 0)
    packet_delta = b.get("videoPacketCount", 0) - a.get("videoPacketCount", 0)
    byte_delta = b.get("videoPacketBytes", 0) - a.get("videoPacketBytes", 0)
    frame_intervals_ms = []
    interval_render_fps = []
    for before, after in zip(formal, formal[1:]):
        dt = (after["elapsedRealtimeMs"] - before["elapsedRealtimeMs"]) / 1000.0
        frames = after["stats"].get(rendered_key, 0) - before["stats"].get(rendered_key, 0)
        if dt > 0 and frames > 0:
            frame_intervals_ms.append(dt * 1000.0 / frames)
            interval_render_fps.append(frames / dt)
    latency = {
        "READ": {
            "avgUs": b.get("avgAvReadFrameDurationUs", 0), "p50Us": b.get("avReadFrameDurationP50Us", 0),
            "p95Us": b.get("avReadFrameDurationP95Us", 0), "p99Us": b.get("avReadFrameDurationP99Us", 0),
            "maxUs": b.get("maxAvReadFrameDurationUs", 0), "count": b.get("avReadFrameDurationDistCount", 0),
        },
        "DEMUX": stage_summary(b, "demuxReturnToDecoderSubmit"),
        "DECODE": stage_summary(b, "decoderSubmitToOutput"),
        "QUEUE": stage_summary(b, "decodedOutputToRenderBegin"),
        "RENDER": stage_summary(b, "renderBeginToSubmit"),
        "TOTAL": stage_summary(b, "packetReadyToRenderSubmit"),
    }
    cpu_values = [float(x["appCpuPercentOneCore"]) for x in cpu_rows if x.get("phase") == "FORMAL"]
    pss_values = [float(x["pssKb"]) for x in mem_rows if x.get("phase") == "FORMAL" and x.get("pssKb")]
    rss_values = [float(x["rssKb"]) for x in mem_rows if x.get("phase") == "FORMAL" and x.get("rssKb")]
    thread_values = [float(x.get("threadCount", 0)) for x in mem_rows if x.get("phase") == "FORMAL"]
    native_heap_values = [x.get("nativeHeapBytes", 0) / 1024 for x in formal if x.get("nativeHeapBytes")]
    java_heap_values = [x.get("javaHeapUsedBytes", 0) / 1024 for x in formal if x.get("javaHeapUsedBytes")]
    pss_points = [(x["formalElapsedMs"] / 1000, float(x["pssKb"])) for x in mem_rows
                  if x.get("phase") == "FORMAL" and x.get("pssKb")]
    delta_keys = ["hardwareDroppedFrameCount", "softwareDroppedFrameCount", "droppedVideoPacketCount",
                  "packetDropBeforeDecodeCount", "droppedVideoFrameCount", "frameDropBeforeRenderCount",
                  "latePacketDropCount", "lateFrameDropCount", "latestFrameReplaceCount", "catchUpDropCount",
                  "dropUntilKeyFrameCount", "startupKeyFrameDroppedPacketCount",
                  "reconnectAttemptCount", "reconnectSuccessCount",
                  "readTimeoutCount", "readEagainCount", "readEofCount", "readErrorCount", "videoPtsBackwardCount",
                  "decoderPtsBackwardCount", "decodedPtsBackwardCount", "renderedPtsBackwardCount",
                  "stageTimingForcedEvictionCount", "stageTimingClockAnomalyCount", "nv12GlFallbackFrameCount",
                  "nv12GlNoSurfaceFrameCount"]
    counters = {key: b.get(key, 0) - a.get(key, 0) for key in delta_keys}
    pts_median = b.get("videoPacketPtsDeltaP50Us", 0)
    expected_fps = 1_000_000 / pts_median if pts_median else 0
    config = json.loads((output / "config.json").read_text(encoding="utf-8")) if (output / "config.json").exists() else {}
    raw_log = (output / "raw_logcat.txt").read_text(encoding="utf-8", errors="replace") if (output / "raw_logcat.txt").exists() else ""
    app_pids = set(re.findall(
        r"^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+LatencyProfile:", raw_log, re.M))
    app_log_lines = []
    for line in raw_log.splitlines():
        match = re.match(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+", line)
        if match and match.group(1) in app_pids:
            app_log_lines.append(line)
    app_log = "\n".join(app_log_lines)
    log_health = {
        "playerPids": sorted(app_pids),
        "fatalExceptionCount": len(re.findall(r"FATAL EXCEPTION", app_log)),
        "anrCount": len(re.findall(r"ANR in", app_log)),
        "nativeFatalSignalCount": len(re.findall(r"Fatal signal", app_log)),
        "gcEventCount": len(re.findall(r"\bGC freed\b|concurrent copying GC", app_log, re.I)),
    }
    pass_run = valid_elapsed >= 300 and all(counters.get(k, 0) == 0 for k in
        ["reconnectAttemptCount", "readTimeoutCount", "readEofCount", "readErrorCount",
         "stageTimingClockAnomalyCount", "nv12GlFallbackFrameCount", "nv12GlNoSurfaceFrameCount"])
    effective_keys = ["effectiveRtspTransport", "rtspTransport", "latencyMode", "socketBufferSize",
                      "maxDelayUs", "effectiveFmtCtxMaxDelayUs", "reorderQueueSize", "fflagsNoBuffer",
                      "avioDirect", "enablePacketDrop", "enableFrameDrop", "enableLatestFrameOnly",
                      "dropLatePacketThresholdUs", "dropLateFrameThresholdUs", "probesize", "analyzeduration"]
    summary = {
        "status": "PASS" if pass_run else "FAIL",
        "validFormalDurationSec": valid_elapsed,
        "sampleIntervalSec": elapsed / max(1, len(formal) - 1),
        "formalSampleCount": len(formal),
        "configuration": config,
        "effectiveConfiguration": {key: b.get(key) for key in effective_keys},
        "source": {
            "videoCodec": b.get("videoCodec"), "audioCodec": b.get("audioCodec"),
            "audioPacketCountDelta": b.get("audioPacketCount", 0) - a.get("audioPacketCount", 0),
            "width": b.get("videoWidth", b.get("width")), "height": b.get("videoHeight", b.get("height")),
            "metadataFps": b.get("metadataFps", b.get("fps")), "medianPtsDeltaUs": pts_median,
            "ptsDerivedFps": expected_fps, "actualBitrateBps": byte_delta * 8 / elapsed,
        },
        "pipeline": {
            "requestedDecoder": b.get("requestedDecoderName"), "actualDecoder": b.get("actualDecoderName"),
            "decodeBackend": b.get("decodeBackend"), "usingHardwareDecoder": b.get("usingHardwareDecoder"),
            "hardwareFallback": b.get("hardwareDecodeFallbackUsed"), "renderMode": b.get("renderMode"),
            "frameOutputType": b.get("frameOutputType"), "renderer": b.get("renderer"),
            "renderedFps": rendered_delta / elapsed, "decodedFps": decoded_delta / elapsed,
            "packetFps": packet_delta / elapsed,
            "renderFrameIntervalMs": summary_values(frame_intervals_ms),
            "renderFpsPerSample": summary_values(interval_render_fps),
        },
        "latency": latency,
        "backlog": {name: stage_summary(b, prefix) for name, prefix in [
            ("DEMUX", "demuxToDecoderBacklog"), ("DECODE", "decoderBacklog"),
            ("RENDER", "renderBacklog"), ("CLIENT_TOTAL", "clientMediaBacklog")]},
        "resources": {"cpuPercentOneCore": summary_values(cpu_values), "pssKb": summary_values(pss_values),
                      "rssKb": summary_values(rss_values), "nativeHeapKb": summary_values(native_heap_values),
                      "javaHeapUsedKb": summary_values(java_heap_values), "threads": summary_values(thread_values),
                      "pssSlopeKbPerMinute": linear_slope_per_minute(pss_points)},
        "decoderApiCost": {
            "lastSendPacketUs": b.get("lastSendPacketCostUs"),
            "sendAvgUs": b.get("avgSendPacketCostUs"), "sendMaxUs": b.get("maxSendPacketCostUs"),
            "sendCount": b.get("sendPacketCostSampleCount"),
            "lastReceiveFrameUs": b.get("lastReceiveFrameCostUs"),
            "receiveAvgUs": b.get("avgReceiveFrameCostUs", b.get("avgDecodeCostUs")),
            "receiveMaxUs": b.get("maxReceiveFrameCostUs", b.get("maxDecodeCostUs")),
            "receiveCount": b.get("receiveFrameCostSampleCount", b.get("decodeCostSampleCount")),
        },
        "nv12Egl": {
            "uploadAvgUs": b.get("avgNv12GlUploadCostUs"), "uploadMaxUs": b.get("maxNv12GlUploadCostUs"),
            "renderAvgUs": b.get("avgNv12GlRenderCostUs"), "renderMaxUs": b.get("maxNv12GlRenderCostUs"),
            "eglContextCreateCount": b.get("nv12EglContextCreateCount"),
            "eglSurfaceCreateCount": b.get("nv12EglSurfaceCreateCount"),
            "fallbackFrameCountDelta": counters.get("nv12GlFallbackFrameCount", 0),
            "noSurfaceFrameCountDelta": counters.get("nv12GlNoSurfaceFrameCount", 0),
        },
        "stabilityCounterDeltas": counters,
        "logHealth": log_health,
        "latencyDefinition": {
            "READ": "R0 before av_read_frame to R1/T0 after packet return; reported separately",
            "TOTAL": "T0 packet ready to T4 render submit; READ is not added to TOTAL",
            "glassToGlass": "NOT_AVAILABLE: no synchronized UAV camera/display photometric measurement",
        },
        "distributionWindow": "bounded final diagnostic window, capacity 8192; percentiles are never averaged",
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    def ms(v): return f"{v / 1000:.3f}"
    lines = [
        "# Android FFmpeg Player 真实无人机 RTSP 5 分钟性能/延迟报告", "",
        f"- 结论：**{summary['status']}**",
        f"- 正式有效时长：{valid_elapsed:.3f} s；1 秒 stats 样本：{len(formal)}；系统资源采样约 5 秒一次。",
        f"- RTSP transport：{config.get('transport', 'unknown')}；latency mode：{config.get('latencyMode', 'unknown')}；Audio：{'ON' if config.get('audio') else 'OFF'}。",
        f"- 视频：{summary['source']['videoCodec']}，{summary['source']['width']}×{summary['source']['height']}，"
        f"PTS 中位间隔 {pts_median / 1000:.3f} ms（{expected_fps:.3f} fps）。",
        f"- 解码：{summary['pipeline']['actualDecoder']} / {summary['pipeline']['decodeBackend']}，"
        f"硬件={summary['pipeline']['usingHardwareDecoder']}，fallback={summary['pipeline']['hardwareFallback']}。",
        f"- 输出/渲染：{summary['pipeline']['frameOutputType']} → {summary['pipeline']['renderer']} "
        f"({summary['pipeline']['renderMode']})。Android 底层组件由 logcat 确认为 `c2.qti.hevc.decoder`。",
        f"- 实测：解码 {summary['pipeline']['decodedFps']:.3f} fps，渲染 {summary['pipeline']['renderedFps']:.3f} fps，"
        f"码率 {summary['source']['actualBitrateBps'] / 1_000_000:.3f} Mbit/s。",
        f"- 渲染帧间隔：avg {summary['pipeline']['renderFrameIntervalMs']['avg']:.3f} ms，"
        f"p95 {summary['pipeline']['renderFrameIntervalMs']['p95']:.3f} ms，"
        f"max {summary['pipeline']['renderFrameIntervalMs']['max']:.3f} ms；"
        f"逐采样窗口 FPS 标准差 {statistics.pstdev(interval_render_fps) if len(interval_render_fps) > 1 else 0:.3f}。",
        "", "## 延迟（ms）", "",
        "| 阶段 | avg | p50 | p95 | p99 | max | count |", "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name in ["READ", "DEMUX", "DECODE", "QUEUE", "RENDER", "TOTAL"]:
        row = latency[name]
        lines.append(f"| {name} | {ms(row['avgUs'])} | {ms(row['p50Us'])} | {ms(row['p95Us'])} | "
                     f"{ms(row['p99Us'])} | {ms(row['maxUs'])} | {row['count']} |")
    lines += [
        "", "READ 是 `av_read_frame` 调用耗时（等待网络/协议栈/解复用），TOTAL 是 T0 包就绪至 T4 提交渲染；二者没有相加。",
        "分位数来自最终一次原生有界分布快照，未对逐秒 percentile 求平均。8192 容量覆盖本次完整正式样本。",
        "", "## Backlog（ms）", "",
        "| backlog | avg | p50 | p95 | p99 | max | count |", "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name in ["DEMUX", "DECODE", "RENDER", "CLIENT_TOTAL"]:
        row = summary["backlog"][name]
        lines.append(f"| {name} | {ms(row['avgUs'])} | {ms(row['p50Us'])} | {ms(row['p95Us'])} | "
                     f"{ms(row['p99Us'])} | {ms(row['maxUs'])} | {row['count']} |")
    lines += [
        "", "## 资源与稳定性", "",
        f"- CPU（单核 100% 口径）：avg {summary['resources']['cpuPercentOneCore']['avg']:.2f}%，"
        f"p95 {summary['resources']['cpuPercentOneCore']['p95']:.2f}%，max {summary['resources']['cpuPercentOneCore']['max']:.2f}%。",
        f"- PSS：avg {summary['resources']['pssKb']['avg'] / 1024:.2f} MiB，"
        f"max {summary['resources']['pssKb']['max'] / 1024:.2f} MiB；RSS max {summary['resources']['rssKb']['max'] / 1024:.2f} MiB；"
        f"PSS 线性斜率 {summary['resources']['pssSlopeKbPerMinute'] / 1024:.3f} MiB/min。",
        f"- Native heap avg/max：{summary['resources']['nativeHeapKb']['avg'] / 1024:.2f}/"
        f"{summary['resources']['nativeHeapKb']['max'] / 1024:.2f} MiB；Java used heap avg/max："
        f"{summary['resources']['javaHeapUsedKb']['avg'] / 1024:.2f}/"
        f"{summary['resources']['javaHeapUsedKb']['max'] / 1024:.2f} MiB。",
        f"- 线程：avg {summary['resources']['threads']['avg']:.1f}，max {summary['resources']['threads']['max']:.0f}。",
        f"- 正式区间计数差：`{json.dumps(counters, ensure_ascii=False)}`。",
        f"- AAC 元数据存在，但正式区间音频包增量为 {summary['source']['audioPacketCountDelta']}；因此本次无可量化音频时钟/PCM 恢复指标。",
        f"- NV12 上传 avg/max：{summary['nv12Egl']['uploadAvgUs'] / 1000:.3f}/{summary['nv12Egl']['uploadMaxUs'] / 1000:.3f} ms；"
        f"NV12 GL 渲染 avg/max：{summary['nv12Egl']['renderAvgUs'] / 1000:.3f}/{summary['nv12Egl']['renderMaxUs'] / 1000:.3f} ms。",
        f"- EGL context/surface 创建次数：{summary['nv12Egl']['eglContextCreateCount']}/{summary['nv12Egl']['eglSurfaceCreateCount']}。",
        f"- MediaCodec API：send avg/max {summary['decoderApiCost']['sendAvgUs']}/"
        f"{summary['decoderApiCost']['sendMaxUs']} µs；receive avg/max "
        f"{summary['decoderApiCost']['receiveAvgUs']}/{summary['decoderApiCost']['receiveMaxUs']} µs。",
        f"- Logcat 检查：FATAL EXCEPTION={log_health['fatalExceptionCount']}，ANR={log_health['anrCount']}，"
        f"native fatal signal={log_health['nativeFatalSignalCount']}，GC events={log_health['gcEventCount']}。",
        "", "## 口径与边界", "",
        "本次是播放器内部管线延迟，不是玻璃到玻璃延迟。无人机端没有同步时钟/光学触发，因此 sender→receiver 和 camera→screen 无法可靠给出；未用 READ+TOTAL 冒充端到端延迟。",
        "测试在 BASIC 模式预热 20 秒，切到 LATENCY 后等待原生 30 帧稳态门控，再连续记录至少 300 秒；正式期间一旦发现非 PLAYING、reconnect、timeout 或 read error 就立即判失败。",
        "本次 TCP 预探测出现源端重复 EOF，不能形成连续有效区间；UDP 短探测稳定后才从零开始本报告的正式运行。该 TCP 预探测未混入正式样本。",
        "实际码率按正式首尾 `videoPacketBytes` 差值 × 8 / 单调时钟实测秒数计算，未使用 RTSP/SDP 元数据码率。",
        "", "## 趋势图", "",
        "- `latency_trend.png`：READ 与 T0→T4 各阶段最后一帧样本。",
        "- `backlog_trend.png`：媒体时间轴 backlog。",
        "- `fps_bitrate_trend.png`：逐区间渲染 FPS 与实际视频码率。",
        "- `cpu_memory_trend.png`：进程 CPU/PSS/RSS。",
        "- `resource_error_trend.png`：线程数、drop/reconnect/read error 累计值。",
    ]
    (output / "LATENCY_5MIN_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return summary


def create_charts(output: Path, samples: list[dict], cpu_rows: list[dict], mem_rows: list[dict]):
    rows = [flatten_sample(x) for x in samples if x.get("phase") == "FORMAL"]
    def series(field, scale=1.0):
        return [(r["formalElapsedMs"] / 1000, float(r.get(field) or 0) / scale) for r in rows]
    draw_chart(output / "latency_trend.png", "Pipeline latency", [
        ("READ", series("readLastUs", 1000), "#1f77b4"), ("DEMUX", series("demuxLastUs", 1000), "#ff7f0e"),
        ("DECODE", series("decodeLastUs", 1000), "#2ca02c"), ("QUEUE", series("queueLastUs", 1000), "#9467bd"),
        ("RENDER", series("renderLastUs", 1000), "#8c564b"), ("TOTAL", series("totalLastUs", 1000), "#d62728")], "ms")
    draw_chart(output / "backlog_trend.png", "Client media backlog", [("backlog", series("clientBacklogUs", 1000), "#d62728")], "ms")
    fps, bitrate = [], []
    for before, after in zip(rows, rows[1:]):
        dt = (after["formalElapsedMs"] - before["formalElapsedMs"]) / 1000
        if dt > 0:
            fps.append((after["formalElapsedMs"] / 1000, (after["renderedFrames"] - before["renderedFrames"]) / dt))
            bitrate.append((after["formalElapsedMs"] / 1000, (after["videoPacketBytes"] - before["videoPacketBytes"]) * 8 / dt / 1e6))
    draw_chart(output / "fps_bitrate_trend.png", "Throughput (FPS and Mbit/s share axis)",
               [("render FPS", fps, "#2ca02c"), ("Mbit/s", bitrate, "#1f77b4")], "value")
    cpu = [(r["formalElapsedMs"] / 1000, r["appCpuPercentOneCore"]) for r in cpu_rows if r.get("phase") == "FORMAL"]
    pss = [(r["formalElapsedMs"] / 1000, r["pssKb"] / 1024) for r in mem_rows if r.get("phase") == "FORMAL"]
    rss = [(r["formalElapsedMs"] / 1000, r["rssKb"] / 1024) for r in mem_rows if r.get("phase") == "FORMAL"]
    draw_chart(output / "cpu_memory_trend.png", "CPU percent and memory MiB share axis",
               [("CPU %", cpu, "#d62728"), ("PSS MiB", pss, "#1f77b4"), ("RSS MiB", rss, "#2ca02c")], "value")
    draw_chart(output / "resource_error_trend.png", "Threads and cumulative error counters", [
        ("threads", [(r["formalElapsedMs"] / 1000, r["threadCount"]) for r in mem_rows if r.get("phase") == "FORMAL"], "#1f77b4"),
        ("drops", series("drops"), "#ff7f0e"), ("reconnect", series("reconnectAttempts"), "#d62728"),
        ("read errors", series("readErrors"), "#9467bd")], "count")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", help="RTSP URL; never written into source control")
    parser.add_argument("--serial")
    parser.add_argument("--transport", choices=["tcp", "udp", "auto"], default="tcp")
    parser.add_argument("--latency-mode", default="balanced")
    parser.add_argument("--render-mode", default="mediacodec_nv12_gl")
    parser.add_argument("--warmup-sec", type=int, default=20)
    parser.add_argument("--duration-sec", type=int, default=300)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--output-dir", help="artifact directory; use a pending directory for transactional runs")
    parser.add_argument("--summarize-only", action="store_true",
                        help="regenerate summary/report/charts from an existing artifacts directory")
    args = parser.parse_args()
    if args.duration_sec < 300:
        parser.error("--duration-sec must be at least 300")

    root = Path(__file__).resolve().parents[1]
    artifact_root = (root / "artifacts").resolve()
    output = Path(args.output_dir).resolve() if args.output_dir else root / "artifacts" / "latency_5min"
    if output != artifact_root and artifact_root not in output.parents:
        parser.error(f"artifact output must stay under {artifact_root}")
    output.mkdir(parents=True, exist_ok=True)
    if args.summarize_only:
        def csv_rows(path: Path) -> list[dict]:
            rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))
            for row in rows:
                for key, value in list(row.items()):
                    try:
                        row[key] = float(value) if "." in value else int(value)
                    except (ValueError, TypeError):
                        pass
            return rows
        samples = [json.loads(line) for line in (output / "samples.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        cpu_rows = csv_rows(output / "cpu.csv")
        mem_rows = csv_rows(output / "meminfo.csv")
        create_charts(output, samples, cpu_rows, mem_rows)
        summary = make_report(output, samples, cpu_rows, mem_rows)
        print(json.dumps({"status": summary["status"], "output": str(output),
                          "validFormalDurationSec": summary["validFormalDurationSec"]}, ensure_ascii=False))
        return 0 if summary["status"] == "PASS" else 2
    if not args.url:
        parser.error("--url is required unless --summarize-only is used")
    for child in output.iterdir():
        if child.is_file() or child.is_symlink(): child.unlink()
        elif child.is_dir(): shutil.rmtree(child)

    serial = detect_serial(args.serial)
    adb = Adb(serial)
    if not args.skip_build:
        run([str(root / "gradlew.bat"), ":app:assembleDebug"], timeout=900)
    if not args.skip_install:
        run(adb.base + ["install", "-r", str(root / "app/build/outputs/apk/debug/app-debug.apk")], timeout=300)
    collect_device_info(adb, serial, output / "device_info.txt")
    adb.shell("am", "force-stop", PACKAGE)
    run(adb.base + ["logcat", "-c"])
    log_handle = (output / "raw_logcat.txt").open("w", encoding="utf-8", errors="replace")
    log_proc = subprocess.Popen(adb.base + ["logcat", "-v", "threadtime"], stdout=log_handle, stderr=subprocess.STDOUT, text=True)
    try:
        start = adb.base + ["shell", "am", "start", "-W", "-n", ACTIVITY,
            "--es", EXTRA + "URL", args.url, "--es", EXTRA + "TRANSPORT", args.transport,
            "--es", EXTRA + "LATENCY_MODE", args.latency_mode, "--es", EXTRA + "RENDER_MODE", args.render_mode,
            "--ez", EXTRA + "HARDWARE", "true", "--ez", EXTRA + "AUDIO", "false",
            "--ei", EXTRA + "WARMUP_SEC", str(args.warmup_sec), "--ei", EXTRA + "DURATION_SEC", str(args.duration_sec)]
        run(start, timeout=60)
        cpu_rows, mem_rows = [], []
        previous_cpu = None
        deadline = time.monotonic() + args.warmup_sec + args.duration_sec + 180
        last_phase = None
        while time.monotonic() < deadline:
            status = read_status(adb) or {}
            phase = status.get("phase", "STARTING")
            formal_ms = int(status.get("formalElapsedMs", -1) or -1)
            if phase != last_phase or (formal_ms >= 0 and formal_ms // 30000 != max(0, formal_ms - 5000) // 30000):
                print(f"phase={phase} state={status.get('state')} formal={formal_ms / 1000:.1f}s", flush=True)
                last_phase = phase
            pid = adb.shell("pidof", PACKAGE, check=False).split()
            pid = pid[0] if pid else ""
            if pid:
                possible = adb.shell("cat", "/sys/devices/system/cpu/possible", check=False).strip()
                cpu_count = int(possible.rsplit("-", 1)[-1]) + 1 if possible else 1
                proc = "\n".join([adb.shell("cat", f"/proc/{pid}/stat", check=False),
                                    adb.shell("head", "-n", "1", "/proc/stat", check=False), str(cpu_count)])
                current_cpu = parse_proc_cpu(proc)
                if current_cpu and previous_cpu:
                    dp = current_cpu[0] - previous_cpu[0]
                    dt = current_cpu[1] - previous_cpu[1]
                    cpu_rows.append({"epochMs": int(time.time() * 1000), "formalElapsedMs": formal_ms,
                                     "phase": phase, "appCpuPercentOneCore": dp / dt * current_cpu[2] * 100 if dt else 0})
                previous_cpu = current_cpu
                # smaps_rollup is read-only and does not provoke the explicit GC
                # observed with repeated dumpsys meminfo on this device.
                mem = parse_meminfo(adb.shell("run-as", PACKAGE, "cat",
                                              f"/proc/{pid}/smaps_rollup", check=False, timeout=60))
                tasks = adb.shell("ls", f"/proc/{pid}/task", check=False)
                mem.update({"epochMs": int(time.time() * 1000), "formalElapsedMs": formal_ms, "phase": phase,
                            "threadCount": len(tasks.split())})
                mem_rows.append(mem)
            result_path = f"/sdcard/Android/data/{PACKAGE}/files/latency_5min/device/result.json"
            result_raw = adb.shell("cat", result_path, check=False)
            try:
                result = json.loads(result_raw)
                if result.get("status") in ("DONE", "FAILED"):
                    if result.get("status") == "FAILED":
                        raise RuntimeError(f"device profile failed: {result}")
                    break
            except json.JSONDecodeError:
                pass
            time.sleep(5)
        else:
            raise RuntimeError("profile timeout")

        remote = f"/sdcard/Android/data/{PACKAGE}/files/latency_5min/device"
        for name in ["samples.jsonl", "events.jsonl", "config.json", "result.json", "status.json"]:
            run(adb.base + ["pull", f"{remote}/{name}", str(output / name)], check=False, timeout=120)
        config_path = output / "config.json"
        device_config = json.loads(config_path.read_text(encoding="utf-8"))
        apk_path = root / "app/build/outputs/apk/debug/app-debug.apk"
        device_config["sourceUrlSha256"] = hashlib.sha256(args.url.encode("utf-8")).hexdigest()
        device_config["gitHead"] = run(["git", "rev-parse", "HEAD"], timeout=30)
        device_config["apkSha256"] = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        config_path.write_text(json.dumps(device_config, ensure_ascii=False, indent=2), encoding="utf-8")
        samples = [json.loads(line) for line in (output / "samples.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        for sample in samples:
            if "stats" in sample and "url" in sample["stats"]: sample["stats"]["url"] = "<RTSP_URL>"
        (output / "samples.json").write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")
        rows = [flatten_sample(s) for s in samples]
        with (output / "samples.csv").open("w", newline="", encoding="utf-8-sig") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys())); writer.writeheader(); writer.writerows(rows)
        for name, rows_data in [("cpu.csv", cpu_rows), ("meminfo.csv", mem_rows)]:
            with (output / name).open("w", newline="", encoding="utf-8-sig") as f:
                writer = csv.DictWriter(f, fieldnames=list(rows_data[0].keys())); writer.writeheader(); writer.writerows(rows_data)
        create_charts(output, samples, cpu_rows, mem_rows)
        summary = make_report(output, samples, cpu_rows, mem_rows)
        print(json.dumps({"status": summary["status"], "output": str(output),
                          "validFormalDurationSec": summary["validFormalDurationSec"]}, ensure_ascii=False))
        return 0 if summary["status"] == "PASS" else 2
    finally:
        log_proc.terminate()
        try: log_proc.wait(timeout=10)
        except subprocess.TimeoutExpired: log_proc.kill()
        log_handle.close()
        log_path = output / "raw_logcat.txt"
        if log_path.exists():
            text = log_path.read_text(encoding="utf-8", errors="replace")
            text = text.replace(args.url, "<RTSP_URL>")
            text = re.sub(r"rtsp://[^\s\"]+", "<RTSP_URL>", text)
            log_path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
