#!/usr/bin/env python3
"""Analyze the real HEVC GOP, frame types, and timestamp reorder from a capture."""

from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import statistics
import subprocess
import sys
from collections import Counter
from fractions import Fraction
from pathlib import Path


STREAM_FIELDS = (
    "codec_name,profile,level,width,height,has_b_frames,r_frame_rate,"
    "avg_frame_rate,time_base,duration,bit_rate,nb_frames"
)
FRAME_FIELDS = (
    "media_type,key_frame,pts,pts_time,pkt_dts,pkt_dts_time,"
    "best_effort_timestamp,best_effort_timestamp_time,duration,duration_time,pict_type"
)
PACKET_FIELDS = "pts,pts_time,dts,dts_time,duration,duration_time,size,flags"


def run(command: list[str], *, capture_stderr: bool = False) -> str:
    result = subprocess.run(command, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT if capture_stderr else subprocess.PIPE)
    if result.returncode:
        detail = result.stdout if capture_stderr else result.stderr
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{detail}")
    return result.stdout


def ratio(value: str | None) -> float | None:
    if not value or value == "0/0":
        return None
    try:
        return float(Fraction(value))
    except (ValueError, ZeroDivisionError):
        return None


def number(value: object) -> float | None:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def max_consecutive(values: list[str], target: str) -> int:
    maximum = current = 0
    for value in values:
        current = current + 1 if value == target else 0
        maximum = max(maximum, current)
    return maximum


def parse_header_values(trace: str, name: str) -> list[int]:
    values = []
    pattern = re.compile(rf"\b{re.escape(name)}(?:\[\d+\])?\b.*=\s*(-?\d+)\s*$")
    for line in trace.splitlines():
        match = pattern.search(line)
        if match:
            values.append(int(match.group(1)))
    return values


def analyze(stream: dict, frames: list[dict], packets: list[dict], trace: str) -> dict:
    video_frames = [frame for frame in frames if frame.get("media_type", "video") == "video"]
    types = [frame.get("pict_type", "?") for frame in video_frames]
    type_counts = Counter(types)
    key_indices = [index for index, frame in enumerate(video_frames) if int(frame.get("key_frame", 0)) == 1]
    key_intervals = [right - left for left, right in zip(key_indices, key_indices[1:])]
    # Some devices repeat IDR access units while a client joins. Exclude adjacent
    # startup IDRs from the stable GOP estimate, but preserve them in raw counts.
    stable_intervals = [interval for interval in key_intervals if interval > 1]
    stable_gop = Counter(stable_intervals).most_common(1)[0][0] if stable_intervals else None

    pts_dts_ms = []
    packet_pts_dts_equal = 0
    packet_pts_dts_compared = 0
    packet_order_inversions = 0
    previous_pts = None
    for packet in packets:
        pts = number(packet.get("pts_time"))
        dts = number(packet.get("dts_time"))
        if pts is not None and dts is not None:
            packet_pts_dts_compared += 1
            delta = (pts - dts) * 1000.0
            pts_dts_ms.append(delta)
            if abs(delta) < 0.0005:
                packet_pts_dts_equal += 1
        if pts is not None and previous_pts is not None and pts < previous_pts:
            packet_order_inversions += 1
        if pts is not None:
            previous_pts = pts

    frame_pts_dts_equal = 0
    frame_pts_dts_compared = 0
    frame_deltas_ms = []
    previous_frame_pts = None
    for frame in video_frames:
        pts = number(frame.get("pts_time"))
        dts = number(frame.get("pkt_dts_time"))
        if pts is not None and dts is not None:
            frame_pts_dts_compared += 1
            if abs(pts - dts) < 0.0000005:
                frame_pts_dts_equal += 1
        if pts is not None and previous_frame_pts is not None:
            frame_deltas_ms.append((pts - previous_frame_pts) * 1000.0)
        if pts is not None:
            previous_frame_pts = pts

    nal_counts = Counter()
    for match in re.finditer(r"nal_unit_type:\s*(\d+)\(([^)]+)\)", trace):
        nal_counts[f"{match.group(1)}({match.group(2)})"] += 1
    slice_counts = Counter()
    for value in parse_header_values(trace, "slice_type"):
        slice_counts[{0: "B", 1: "P", 2: "I"}.get(value, str(value))] += 1

    fps = ratio(stream.get("avg_frame_rate")) or ratio(stream.get("r_frame_rate"))
    gop_seconds = stable_gop / fps if stable_gop and fps else None
    sps_reorder = parse_header_values(trace, "sps_max_num_reorder_pics")
    sps_dpb_minus_one = parse_header_values(trace, "sps_max_dec_pic_buffering_minus1")
    level = stream.get("level")
    return {
        "source": {
            "codec": stream.get("codec_name"),
            "profile": stream.get("profile"),
            "levelIdc": level,
            "level": f"{float(level) / 30.0:.1f}" if isinstance(level, int) else None,
            "width": stream.get("width"),
            "height": stream.get("height"),
            "fps": fps,
            "rFrameRate": stream.get("r_frame_rate"),
            "avgFrameRate": stream.get("avg_frame_rate"),
            "timeBase": stream.get("time_base"),
            "durationSec": number(stream.get("duration")),
            "bitRateBps": int(stream["bit_rate"]) if stream.get("bit_rate") else None,
            "hasBFrames": stream.get("has_b_frames"),
        },
        "frames": {
            "total": len(video_frames),
            "counts": dict(sorted(type_counts.items())),
            "keyFrames": len(key_indices),
            "maxConsecutiveB": max_consecutive(types, "B"),
            "hasBFrames": type_counts.get("B", 0) > 0,
            "firstTypes": " ".join(types[:80]),
            "keyFrameIndices": key_indices,
            "keyFrameIntervals": key_intervals,
            "stableGopFrames": stable_gop,
            "stableGopSeconds": gop_seconds,
            "frameDeltaMs": {
                "min": min(frame_deltas_ms) if frame_deltas_ms else None,
                "median": statistics.median(frame_deltas_ms) if frame_deltas_ms else None,
                "max": max(frame_deltas_ms) if frame_deltas_ms else None,
            },
        },
        "timestamps": {
            "packetPtsDtsCompared": packet_pts_dts_compared,
            "packetPtsEqualsDts": packet_pts_dts_equal,
            "packetPtsDtsDeltaMsMin": min(pts_dts_ms) if pts_dts_ms else None,
            "packetPtsDtsDeltaMsMax": max(pts_dts_ms) if pts_dts_ms else None,
            "packetPresentationOrderInversions": packet_order_inversions,
            "framePtsDtsCompared": frame_pts_dts_compared,
            "framePtsEqualsDts": frame_pts_dts_equal,
            "decodeOrderEqualsPresentationOrder": (
                bool(packet_pts_dts_compared)
                and packet_pts_dts_compared == packet_pts_dts_equal
                and packet_order_inversions == 0
            ),
        },
        "bitstreamHeaders": {
            "nalCounts": dict(sorted(nal_counts.items())),
            "sliceTypeCounts": dict(sorted(slice_counts.items())),
            "generalProfileIdc": sorted(set(parse_header_values(trace, "general_profile_idc"))),
            "generalLevelIdc": sorted(set(parse_header_values(trace, "general_level_idc"))),
            "spsMaxNumReorderPics": sorted(set(sps_reorder)),
            "spsMaxDecPicBufferingMinus1": sorted(set(sps_dpb_minus_one)),
        },
    }


def write_text_report(path: Path, result: dict) -> None:
    source, frame, ts, headers = (result[key] for key in ("source", "frames", "timestamps", "bitstreamHeaders"))
    counts = frame["counts"]
    lines = [
        f"codec={source['codec']} profile={source['profile']} level={source['level']}",
        f"resolution={source['width']}x{source['height']} fps={source['fps']:.6f}",
        f"durationSec={source['durationSec']} bitRateBps={source['bitRateBps']}",
        f"has_b_frames={source['hasBFrames']}",
        f"frames={frame['total']} I={counts.get('I', 0)} P={counts.get('P', 0)} B={counts.get('B', 0)}",
        f"keyFrames={frame['keyFrames']} maxConsecutiveB={frame['maxConsecutiveB']}",
        f"stableGopFrames={frame['stableGopFrames']} stableGopSeconds={frame['stableGopSeconds']}",
        f"packetPtsEqualsDts={ts['packetPtsEqualsDts']}/{ts['packetPtsDtsCompared']}",
        f"framePtsEqualsDts={ts['framePtsEqualsDts']}/{ts['framePtsDtsCompared']}",
        f"decodeOrderEqualsPresentationOrder={ts['decodeOrderEqualsPresentationOrder']}",
        f"spsMaxNumReorderPics={headers['spsMaxNumReorderPics']}",
        f"spsMaxDecPicBufferingMinus1={headers['spsMaxDecPicBufferingMinus1']}",
        f"sliceTypeCounts={json.dumps(headers['sliceTypeCounts'], sort_keys=True)}",
        f"firstTypes={frame['firstTypes']}",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Captured MP4/MOV/MKV/TS or elementary stream")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"), help="ffprobe executable")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"), help="ffmpeg executable")
    args = parser.parse_args()
    if not args.ffprobe or not args.ffmpeg:
        parser.error("ffprobe and ffmpeg are required; pass --ffprobe/--ffmpeg when they are not on PATH")
    source = args.input.resolve()
    if not source.is_file():
        parser.error(f"input does not exist: {source}")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

    stream_json = run([args.ffprobe, "-v", "error", "-select_streams", "v:0",
                       "-show_entries", f"stream={STREAM_FIELDS}", "-of", "json", str(source)])
    frames_json = run([args.ffprobe, "-v", "error", "-select_streams", "v:0",
                       "-show_frames", "-show_entries", f"frame={FRAME_FIELDS}", "-of", "json", str(source)])
    packets_json = run([args.ffprobe, "-v", "error", "-select_streams", "v:0",
                        "-show_packets", "-show_entries", f"packet={PACKET_FIELDS}", "-of", "json", str(source)])
    trace = run([args.ffmpeg, "-hide_banner", "-loglevel", "trace", "-i", str(source),
                 "-map", "0:v:0", "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-"],
                capture_stderr=True)
    (output / "stream.json").write_text(stream_json, encoding="utf-8")
    (output / "frames.json").write_text(frames_json, encoding="utf-8")
    (output / "packets.json").write_text(packets_json, encoding="utf-8")
    (output / "trace_headers.log").write_text(trace, encoding="utf-8")

    stream_doc, frames_doc, packets_doc = map(json.loads, (stream_json, frames_json, packets_json))
    streams = stream_doc.get("streams", [])
    if not streams:
        raise RuntimeError("no video stream found")
    result = analyze(streams[0], frames_doc.get("frames", []), packets_doc.get("packets", []), trace)
    result["input"] = str(source)
    (output / "gop_analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8")
    write_text_report(output / "gop_analysis.txt", result)
    with (output / "frames.csv").open("w", newline="", encoding="utf-8") as handle:
        fields = ["index", "key_frame", "pict_type", "pts", "pts_time", "pkt_dts", "pkt_dts_time",
                  "best_effort_timestamp", "best_effort_timestamp_time", "duration_time"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, frame in enumerate(frames_doc.get("frames", [])):
            writer.writerow({field: index if field == "index" else frame.get(field, "") for field in fields})
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
