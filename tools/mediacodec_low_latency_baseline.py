#!/usr/bin/env python3
"""Freeze the MediaCodec Low-Latency A/B test baseline.

Slice 0 scope: only the *test baseline*, *config snapshot*, and *A/B validity
check*. It does NOT implement the Low-Latency switch, probe KEY_LOW_LATENCY,
touch vendor parameters, or change the decoder.

This module reads real data from an existing latency run directory (produced by
``tools/collect_ffmpeg_latency_5min.py``) and never hardcodes the expected
values as test results. The "expected baseline" below is used only as a
verification checklist, not as the source of the snapshot.

Reused ``summary.json`` field names:
    transport / latencyMode / audio          -> configuration.*
    videoCodec / width / height /
    ptsDerivedFps / actualBitrateBps         -> source.*
    requestedDecoder / actualDecoder /
    decodeBackend / usingHardwareDecoder /
    hardwareFallback / frameOutputType /
    renderer                                 -> pipeline.*
    effectiveRtspTransport                   -> effectiveConfiguration.*

GOP / B-frame come from ``gop_analysis.json`` (bitstream truth, produced by
``tools/analyze_hevc_gop.py``) with a runtime fallback to the last formal stats
sample. Device model / Android version / SoC come from ``device_info.txt``.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

SCHEMA = "mediacodec_low_latency_baseline/v1"

# ---------------------------------------------------------------------------
# Centralized tolerances. Keep continuous-value allowances here so no magic
# number is scattered through the comparison logic.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Tolerances:
    fps_abs: float = 0.5          # PTS-derived FPS may float a little between runs
    gop_frames_abs: int = 1       # GOP is an integer; allow off-by-one sampling
    bitrate_ratio: float = 0.15   # actual bitrate is allowed 15% relative drift


TOLERANCES = Tolerances()

# ---------------------------------------------------------------------------
# Frozen test protocol for the follow-up OFF/ON rounds. Slice 0 keeps the
# current baseline mode (UDP Balanced) and does NOT auto-switch to Ultra Low.
# ---------------------------------------------------------------------------

FIXED_TEST_CONFIG = {
    "warmupSec": 20,
    "durationSec": 300,
    "diagnosticsMode": "latency",
    "steadyStateRequired": True,
    "latencyMode": "balanced",
}

# ---------------------------------------------------------------------------
# Expected baseline (verification ONLY). The snapshot is always built from real
# stats / bitstream / device data; these values are compared against that
# snapshot to flag an unexpected configuration, never written as results.
# ---------------------------------------------------------------------------

EXPECTED_BASELINE = {
    "transport": "udp",
    "latencyMode": "balanced",
    "audioEnabled": False,
    "videoCodec": "hevc",
    "width": 1280,
    "height": 720,
    "ptsDerivedFps": 25.0,
    "gopFrames": 25,
    "hasBFrames": False,
    "requestedDecoder": "hevc_mediacodec",
    "actualDecoder": "hevc_mediacodec",
    "decodeBackend": "mediacodec",
    "hardwareDecode": True,
    "hwCodecComponent": "c2.qti.hevc.decoder",
    "frameOutputType": "nv12_cpu",
    "renderer": "nv12_gl",
}

# ---------------------------------------------------------------------------
# A/B validity check field list (section 4). Each entry maps a human label to a
# snapshot key (or callable) and a comparison kind.
# ---------------------------------------------------------------------------


def _resolution(snapshot: dict) -> str | None:
    width, height = snapshot.get("width"), snapshot.get("height")
    if width is None or height is None:
        return None
    return f"{width}x{height}"


COMPARE_FIELDS: list[tuple[str, str | Callable[[dict], Any], str]] = [
    ("codec", "videoCodec", "exact"),
    ("resolution", _resolution, "exact"),
    ("fps", "ptsDerivedFps", "fps"),
    ("bitrate", "actualBitrateBps", "bitrate"),
    ("gop", "gopFrames", "gop"),
    ("b-frame", "hasBFrames", "bool"),
    ("transport", "effectiveRtspTransport", "exact"),
    ("latencyMode", "latencyMode", "exact"),
    ("decoder", "actualDecoder", "exact"),
    ("decodeBackend", "decodeBackend", "exact"),
    ("hardwareDecode", "hardwareDecode", "bool"),
    ("frameOutputType", "frameOutputType", "exact"),
    ("renderer", "renderer", "exact"),
    ("audioEnabled", "audioEnabled", "bool"),
]

_HW_CODEC_COMPONENT_RE = re.compile(
    r"c2\.[a-z0-9_.-]+\.(?:hevc|h265|avc|h264|vp8|vp9|av1)\.decoder", re.IGNORECASE
)


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------


def _read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        # utf-8-sig tolerates a leading BOM, which PowerShell / Windows editors
        # and some pull tools add to otherwise-valid JSON.
        with path.open("r", encoding="utf-8-sig") as handle:
            return json.load(handle)
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return {}


def _git_head(repo: Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=30, check=True,
        ).stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return ""


def _first(values: list[str]) -> str | None:
    for value in values:
        if value and value.strip():
            return value.strip()
    return None


def parse_device_info(text: str) -> dict:
    """Parse the ``[section]``-formatted device_info.txt into key fields."""
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        if line.startswith("[") and line.endswith("]"):
            current = line[1:-1]
            sections[current] = []
        elif current is not None:
            sections[current].append(line)

    package_text = "\n".join(sections.get("package", []))
    version_name = re.search(r"versionName=([^\s]+)", package_text)
    version_code = re.search(r"versionCode=(\d+)", package_text)

    return {
        "deviceModel": _first(sections.get("model", [])),
        "manufacturer": _first(sections.get("manufacturer", [])),
        "androidVersion": _first(sections.get("android", [])),
        "platform": _first(sections.get("soc", [])) or _first(sections.get("board", [])),
        "appVersion": (version_name.group(1) if version_name else None)
        or (version_code.group(1) if version_code else None),
    }


def extract_hw_codec_components(text: str) -> list[str]:
    return sorted(set(match.group(0).lower() for match in _HW_CODEC_COMPONENT_RE.finditer(text)))


def _runtime_bframes(samples_path: Path) -> bool | None:
    """Best-effort runtime B-frame flag from the last formal stats sample."""
    if not samples_path.exists():
        return None
    last = None
    try:
        for line in samples_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            sample = json.loads(line)
            if sample.get("phase") == "FORMAL":
                last = sample
        if not last:
            return None
        value = last.get("stats", {}).get("videoCodecHasBFrames")
        if value is None or value == -1:
            return None
        return bool(int(value))
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError):
        return None


# ---------------------------------------------------------------------------
# Snapshot build
# ---------------------------------------------------------------------------


def build_snapshot(run_dir: Path, repo: Path | None = None) -> dict:
    """Build a baseline snapshot from a latency run directory.

    Reads summary.json, config.json, device_info.txt, gop_analysis.json,
    raw_logcat.txt and samples.jsonl. Missing optional sources degrade to
    ``None`` / empty rather than fabricating device data.
    """
    run_dir = run_dir.resolve()
    if repo is None:
        repo = Path(__file__).resolve().parents[1]

    summary = _read_json(run_dir / "summary.json")
    config = _read_json(run_dir / "config.json")
    gop = _read_json(run_dir / "gop_analysis.json")
    device_info_text = (
        (run_dir / "device_info.txt").read_text(encoding="utf-8", errors="replace")
        if (run_dir / "device_info.txt").exists()
        else ""
    )
    logcat_text = (
        (run_dir / "raw_logcat.txt").read_text(encoding="utf-8", errors="replace")
        if (run_dir / "raw_logcat.txt").exists()
        else ""
    )

    device = parse_device_info(device_info_text) if device_info_text else {}

    source = summary.get("source", {})
    pipeline = summary.get("pipeline", {})
    configuration = summary.get("configuration", config)
    effective = summary.get("effectiveConfiguration", {})

    # GOP / B-frame: bitstream truth first, runtime stats as fallback.
    gop_frames = (gop.get("frames", {}) or {}).get("stableGopFrames")
    has_bframes = (gop.get("frames", {}) or {}).get("hasBFrames")
    if has_bframes is None:
        has_bframes = gop.get("source", {}).get("hasBFrames")
        if has_bframes is not None:
            has_bframes = bool(int(has_bframes))
    if has_bframes is None:
        has_bframes = _runtime_bframes(run_dir / "samples.jsonl")

    git_commit = (
        config.get("gitHead")
        or configuration.get("gitHead")
        or _git_head(repo)
    )

    # Requested transport vs effective transport: A/B validity uses the
    # effective transport (the one actually negotiated).
    transport = config.get("transport") or configuration.get("transport")
    effective_transport = (
        effective.get("effectiveRtspTransport")
        or config.get("effectiveRtspTransport")
        or transport
    )

    hardware_decode = pipeline.get("usingHardwareDecoder")
    if hardware_decode is None:
        hardware_decode = config.get("hardwareDecode")
    if hardware_decode is None:
        hardware_decode = configuration.get("hardwareDecode")

    audio = config.get("audio")
    if audio is None:
        audio = configuration.get("audio")

    snapshot: dict[str, Any] = {
        "schema": SCHEMA,
        "capturedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "testConfig": dict(FIXED_TEST_CONFIG),
        # transport / latency / audio
        "transport": transport,
        "effectiveRtspTransport": effective_transport,
        "latencyMode": config.get("latencyMode") or configuration.get("latencyMode"),
        "audioEnabled": None if audio is None else bool(audio),
        # source
        "videoCodec": source.get("videoCodec"),
        "width": source.get("width"),
        "height": source.get("height"),
        "ptsDerivedFps": source.get("ptsDerivedFps"),
        "actualBitrateBps": source.get("actualBitrateBps"),
        # bitstream
        "gopFrames": gop_frames,
        "hasBFrames": None if has_bframes is None else bool(has_bframes),
        # pipeline
        "requestedDecoder": pipeline.get("requestedDecoder"),
        "actualDecoder": pipeline.get("actualDecoder"),
        "decodeBackend": pipeline.get("decodeBackend"),
        "hardwareDecode": None if hardware_decode is None else bool(hardware_decode),
        "hardwareFallback": pipeline.get("hardwareFallback"),
        "hwCodecComponent": extract_hw_codec_components(logcat_text),
        "frameOutputType": pipeline.get("frameOutputType"),
        "renderer": pipeline.get("renderer"),
        # device / build
        "deviceModel": device.get("deviceModel"),
        "androidVersion": device.get("androidVersion"),
        "platform": device.get("platform"),
        "appVersion": device.get("appVersion"),
        "gitCommit": git_commit,
    }
    return snapshot


def write_snapshot(run_dir: Path, snapshot: dict) -> Path:
    path = Path(run_dir) / "baseline_snapshot.json"
    path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def load_snapshot(run_dir: Path, repo: Path | None = None) -> dict:
    """Load an existing snapshot or build one from the run directory."""
    path = Path(run_dir) / "baseline_snapshot.json"
    if path.exists():
        return _read_json(path)
    return build_snapshot(run_dir, repo)


# ---------------------------------------------------------------------------
# A/B validity comparison
# ---------------------------------------------------------------------------


def _extract(spec: str | Callable[[dict], Any], snapshot: dict) -> Any:
    if callable(spec):
        return spec(snapshot)
    return snapshot.get(spec)


def _matches(kind: str, a: Any, b: Any, tolerances: Tolerances) -> bool:
    """Return True when ``a`` and ``b`` are comparable for ``kind``."""
    if a is None or b is None:
        return True  # unknown, not inconsistent -> reported as unverified
    if kind == "exact":
        return a == b
    if kind == "bool":
        return bool(a) == bool(b)
    if kind == "fps":
        try:
            return abs(float(a) - float(b)) <= tolerances.fps_abs
        except (TypeError, ValueError):
            return a == b
    if kind == "gop":
        try:
            return abs(int(a) - int(b)) <= tolerances.gop_frames_abs
        except (TypeError, ValueError):
            return a == b
    if kind == "bitrate":
        try:
            a, b = float(a), float(b)
            if a == b:
                return True
            scale = max(abs(a), abs(b))
            if scale == 0:
                return True
            return abs(a - b) / scale <= tolerances.bitrate_ratio
        except (TypeError, ValueError):
            return a == b
    raise ValueError(f"unknown compare kind: {kind}")


def compare(off: dict, on: dict, tolerances: Tolerances = TOLERANCES) -> dict:
    """Compare two snapshots and flag INVALID_COMPARISON on key drift."""
    diffs: list[dict] = []
    unverified: list[dict] = []
    checked: list[str] = []
    for label, spec, kind in COMPARE_FIELDS:
        a, b = _extract(spec, off), _extract(spec, on)
        checked.append(label)
        if a is None or b is None:
            unverified.append({"label": label, "off": a, "on": b})
        elif not _matches(kind, a, b, tolerances):
            diffs.append({"label": label, "off": a, "on": b, "kind": kind})
    status = "INVALID_COMPARISON" if diffs else "VALID"
    return {
        "status": status,
        "diffs": diffs,
        "unverified": unverified,
        "checked": checked,
        "off": off,
        "on": on,
    }


def format_diff(diff: dict) -> str:
    def show(value: Any) -> str:
        return "<missing>" if value is None else str(value)
    return f"{diff['label']}: {show(diff['off'])} -> {show(diff['on'])}"


# ---------------------------------------------------------------------------
# Expected-baseline verification (verification-only checklist)
# ---------------------------------------------------------------------------


def check_expected(
    snapshot: dict, expected: dict = EXPECTED_BASELINE, tolerances: Tolerances = TOLERANCES
) -> list[str]:
    """Return a list of mismatches between the snapshot and the expected
    baseline. Empty list means the snapshot matches the frozen expectations."""
    mismatches: list[str] = []
    for key, want in expected.items():
        got = snapshot.get(key)
        if key == "hwCodecComponent":
            # expected is a single component string; snapshot holds a list.
            if not got or want not in got:
                mismatches.append(f"{key}: {sorted(got or [])} (expected {want!r})")
            continue
        if key in ("width", "height"):
            if got is None or int(got) != int(want):
                mismatches.append(f"{key}: {got} (expected {want})")
            continue
        if key == "ptsDerivedFps":
            if got is None or abs(float(got) - float(want)) > tolerances.fps_abs:
                mismatches.append(f"{key}: {got} (expected {want} +/- {tolerances.fps_abs})")
            continue
        if key == "gopFrames":
            if got is None or abs(int(got) - int(want)) > tolerances.gop_frames_abs:
                mismatches.append(f"{key}: {got} (expected {want} +/- {tolerances.gop_frames_abs})")
            continue
        if isinstance(want, bool):
            if got is None or bool(got) != want:
                mismatches.append(f"{key}: {got} (expected {want})")
            continue
        if got != want:
            mismatches.append(f"{key}: {got} (expected {want!r})")
    return mismatches


# ---------------------------------------------------------------------------
# Report writing
# ---------------------------------------------------------------------------


def write_report(out_dir: Path, result: dict, expected_mismatches_off: list[str],
                 expected_mismatches_on: list[str]) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "comparison.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    off, on = result["off"], result["on"]
    lines = [
        "# MediaCodec Low-Latency OFF vs ON 严格 A/B 测试", "",
        f"- 判定：**{result['status']}**", "",
        "## 冻结测试协议", "",
        f"- warm-up = {FIXED_TEST_CONFIG['warmupSec']} s；formal = {FIXED_TEST_CONFIG['durationSec']} s。",
        f"- Diagnostics = {FIXED_TEST_CONFIG['diagnosticsMode'].upper()}；steady state = required。",
        f"- Latency Mode 基线 = {FIXED_TEST_CONFIG['latencyMode']}（本 Slice 不切换到 Ultra Low）。", "",
        "## Baseline Snapshot", "",
        "| 字段 | OFF | ON |", "|---|---|---|",
    ]
    for label, spec, _kind in COMPARE_FIELDS:
        lines.append(f"| {label} | {_extract(spec, off)} | {_extract(spec, on)} |")
    traceability = [
        ("requestedDecoder", "requestedDecoder"), ("hardwareFallback", "hardwareFallback"),
        ("hwCodecComponent", "hwCodecComponent"), ("actualBitrateBps", "actualBitrateBps"),
        ("deviceModel", "deviceModel"), ("androidVersion", "androidVersion"),
        ("platform", "platform"), ("appVersion", "appVersion"), ("gitCommit", "gitCommit"),
    ]
    for label, key in traceability:
        lines.append(f"| {label} | {off.get(key)} | {on.get(key)} |")
    lines += ["", "## A/B 有效性", ""]
    if result["status"] == "VALID":
        lines.append("关键条件一致，比较有效（VALID）。")
    else:
        lines += ["关键条件不一致，最终比较判定为 INVALID_COMPARISON：", ""]
        for diff in result["diffs"]:
            lines.append(f"- {format_diff(diff)}")
    if result["unverified"]:
        lines += ["", "以下字段在至少一侧缺失，无法校验（不影响一致性判定）：", ""]
        for item in result["unverified"]:
            lines.append(f"- {item['label']}: {item['off']} -> {item['on']}")
    lines += ["", "## 预期基线校验（verification-only）", ""]
    lines.append(f"- OFF 与预期基线差异：{expected_mismatches_off or '无（匹配）'}")
    lines.append(f"- ON 与预期基线差异：{expected_mismatches_on or '无（匹配）'}")
    lines += ["", "本 Slice 仅冻结测试基线，不实现 MediaCodec Low-Latency 开关、不改解码算法。"]
    report_path = out_dir / "MEDIACODEC_LOW_LATENCY_AB_REPORT.md"
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report_path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _print_snapshot(snapshot: dict) -> None:
    print(json.dumps(snapshot, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, metavar="RUN_DIR",
                        help="build and write baseline_snapshot.json for a run directory")
    parser.add_argument("--verify-expected", type=Path, metavar="RUN_DIR",
                        help="build a snapshot and check it against the frozen expected baseline")
    parser.add_argument("--compare", nargs=2, type=Path, metavar=("OFF_DIR", "ON_DIR"),
                        help="compare two run directories and report A/B validity")
    parser.add_argument("--report-dir", type=Path,
                        default=Path(__file__).resolve().parents[1] / "artifacts"
                        / "mediacodec_low_latency_ab",
                        help="output directory for comparison.json and the report")
    args = parser.parse_args()

    repo = Path(__file__).resolve().parents[1]

    if args.snapshot:
        snapshot = build_snapshot(args.snapshot, repo)
        write_snapshot(args.snapshot, snapshot)
        _print_snapshot(snapshot)
        return 0

    if args.verify_expected:
        snapshot = build_snapshot(args.verify_expected, repo)
        mismatches = check_expected(snapshot)
        print(json.dumps({"matchesExpected": not mismatches, "mismatches": mismatches,
                          "snapshot": snapshot}, ensure_ascii=False, indent=2))
        return 0 if not mismatches else 2

    if args.compare:
        off = load_snapshot(args.compare[0], repo)
        on = load_snapshot(args.compare[1], repo)
        result = compare(off, on)
        report_path = write_report(args.report_dir, result,
                                   check_expected(off), check_expected(on))
        print(f"status={result['status']}")
        for diff in result["diffs"]:
            print(f"  {format_diff(diff)}")
        print(f"report={report_path}")
        return 0 if result["status"] == "VALID" else 2

    parser.error("choose one of --snapshot, --verify-expected, or --compare")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
