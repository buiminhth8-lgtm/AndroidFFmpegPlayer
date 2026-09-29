import copy
import json
import tempfile
import unittest
from pathlib import Path

import mediacodec_low_latency_baseline as base


def _summary(overrides=None):
    summary = {
        "status": "PASS",
        "configuration": {
            "transport": "udp", "latencyMode": "balanced",
            "renderMode": "mediacodec_nv12_gl", "hardwareDecode": True,
            "audio": False, "warmupSec": 20, "durationSec": 300,
        },
        "source": {
            "videoCodec": "hevc", "width": 1280, "height": 720,
            "ptsDerivedFps": 25.0, "actualBitrateBps": 997847.28,
        },
        "pipeline": {
            "requestedDecoder": "hevc_mediacodec", "actualDecoder": "hevc_mediacodec",
            "decodeBackend": "mediacodec", "usingHardwareDecoder": True,
            "hardwareFallback": False, "frameOutputType": "nv12_cpu", "renderer": "nv12_gl",
        },
        "effectiveConfiguration": {"effectiveRtspTransport": "udp"},
    }
    if overrides:
        summary.update(overrides)
    return summary


def _config():
    return {
        "transport": "udp", "latencyMode": "balanced", "renderMode": "mediacodec_nv12_gl",
        "hardwareDecode": True, "audio": False, "warmupSec": 20, "durationSec": 300,
        "gitHead": "deadbeef",
    }


def _device_info():
    return "\n".join([
        "[model]", "Bengal for arm64", "",
        "[manufacturer]", "QUALCOMM", "",
        "[android]", "13", "",
        "[soc]", "SM6225", "",
        "[board]", "bengal", "",
        "[package]", "versionCode=6 minSdk=24", "versionName=1.0.0.6", "",
    ])


def _gop():
    return {
        "source": {"hasBFrames": 0},
        "frames": {"stableGopFrames": 25, "hasBFrames": False},
    }


def make_run_dir(tmp):
    run = Path(tmp) / "run"
    (run).mkdir(parents=True)
    (run / "summary.json").write_text(json.dumps(_summary()), encoding="utf-8")
    (run / "config.json").write_text(json.dumps(_config()), encoding="utf-8")
    (run / "device_info.txt").write_text(_device_info(), encoding="utf-8")
    (run / "gop_analysis.json").write_text(json.dumps(_gop()), encoding="utf-8")
    (run / "raw_logcat.txt").write_text(
        "I Codec2Client: config c2.qti.hevc.decoder\n", encoding="utf-8")
    return run


class SnapshotBuildTests(unittest.TestCase):
    def test_build_snapshot_records_all_baseline_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = make_run_dir(tmp)
            snap = base.build_snapshot(run, Path(tmp))
            required = [
                "transport", "latencyMode", "audioEnabled", "videoCodec", "width", "height",
                "ptsDerivedFps", "actualBitrateBps", "gopFrames", "hasBFrames",
                "requestedDecoder", "actualDecoder", "decodeBackend", "hardwareDecode",
                "hardwareFallback", "frameOutputType", "renderer", "deviceModel",
                "androidVersion", "platform", "appVersion", "gitCommit",
            ]
            for key in required:
                self.assertIn(key, snap, f"missing baseline field: {key}")

    def test_snapshot_reads_real_values_not_hardcoded(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = make_run_dir(tmp)
            snap = base.build_snapshot(run, Path(tmp))
            self.assertEqual(snap["videoCodec"], "hevc")
            self.assertEqual(snap["width"], 1280)
            self.assertEqual(snap["height"], 720)
            self.assertAlmostEqual(snap["ptsDerivedFps"], 25.0)
            self.assertEqual(snap["gopFrames"], 25)
            self.assertFalse(snap["hasBFrames"])
            self.assertEqual(snap["actualDecoder"], "hevc_mediacodec")
            self.assertEqual(snap["decodeBackend"], "mediacodec")
            self.assertTrue(snap["hardwareDecode"])
            self.assertEqual(snap["frameOutputType"], "nv12_cpu")
            self.assertEqual(snap["renderer"], "nv12_gl")
            self.assertFalse(snap["audioEnabled"])
            self.assertEqual(snap["deviceModel"], "Bengal for arm64")
            self.assertEqual(snap["androidVersion"], "13")
            self.assertEqual(snap["platform"], "SM6225")
            self.assertEqual(snap["appVersion"], "1.0.0.6")
            self.assertEqual(snap["gitCommit"], "deadbeef")
            self.assertEqual(snap["hwCodecComponent"], ["c2.qti.hevc.decoder"])

    def test_expected_baseline_matches_frozen_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = make_run_dir(tmp)
            snap = base.build_snapshot(run, Path(tmp))
            self.assertEqual(base.check_expected(snap), [])

    def test_expected_baseline_flags_unexpected_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = make_run_dir(tmp)
            snap = base.build_snapshot(run, Path(tmp))
            snap["width"] = 1920
            snap["height"] = 1080
            mismatches = base.check_expected(snap)
            self.assertTrue(any("width" in m for m in mismatches))
            self.assertTrue(any("height" in m for m in mismatches))


class CompareTests(unittest.TestCase):
    def _snap(self, tmp):
        run = make_run_dir(tmp)
        return base.build_snapshot(run, Path(tmp))

    def test_identical_snapshots_are_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = self._snap(tmp)
            result = base.compare(snap, copy.deepcopy(snap))
            self.assertEqual(result["status"], "VALID")
            self.assertEqual(result["diffs"], [])

    def test_resolution_drift_is_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["width"], on["height"] = 1920, 1080
            result = base.compare(off, on)
            self.assertEqual(result["status"], "INVALID_COMPARISON")
            self.assertEqual(base.format_diff(result["diffs"][0]),
                             "resolution: 1280x720 -> 1920x1080")

    def test_fps_drift_is_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["ptsDerivedFps"] = 30.0
            result = base.compare(off, on)
            self.assertEqual(result["status"], "INVALID_COMPARISON")
            self.assertEqual(base.format_diff(result["diffs"][0]), "fps: 25.0 -> 30.0")

    def test_decoder_software_fallback_is_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["actualDecoder"] = "hevc"
            on["decodeBackend"] = "software"
            on["hardwareDecode"] = False
            result = base.compare(off, on)
            self.assertEqual(result["status"], "INVALID_COMPARISON")
            labels = {d["label"] for d in result["diffs"]}
            self.assertIn("decoder", labels)
            self.assertIn("decodeBackend", labels)
            self.assertIn("hardwareDecode", labels)

    def test_fps_within_tolerance_is_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["ptsDerivedFps"] = 25.3
            self.assertEqual(base.compare(off, on)["status"], "VALID")

    def test_gop_off_by_one_is_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["gopFrames"] = 26
            self.assertEqual(base.compare(off, on)["status"], "VALID")

    def test_bitrate_within_tolerance_is_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["actualBitrateBps"] = 1_100_000.0  # ~10% drift
            self.assertEqual(base.compare(off, on)["status"], "VALID")

    def test_bitrate_beyond_tolerance_is_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["actualBitrateBps"] = 2_000_000.0  # >100% drift
            result = base.compare(off, on)
            self.assertEqual(result["status"], "INVALID_COMPARISON")
            self.assertEqual(result["diffs"][0]["label"], "bitrate")

    def test_missing_field_is_unverified_not_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["gopFrames"] = None
            on["hasBFrames"] = None
            result = base.compare(off, on)
            self.assertEqual(result["status"], "VALID")
            labels = {u["label"] for u in result["unverified"]}
            self.assertIn("gop", labels)
            self.assertIn("b-frame", labels)

    def test_report_writes_comparison_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            off = self._snap(tmp)
            on = copy.deepcopy(off)
            on["width"], on["height"] = 1920, 1080
            result = base.compare(off, on)
            out = Path(tmp) / "out"
            report = base.write_report(out, result, [], [])
            self.assertTrue((out / "comparison.json").exists())
            self.assertTrue(report.exists())
            self.assertIn("INVALID_COMPARISON", report.read_text(encoding="utf-8"))


class ToleranceTests(unittest.TestCase):
    def test_tolerances_are_centralized(self):
        self.assertEqual(base.TOLERANCES.fps_abs, 0.5)
        self.assertEqual(base.TOLERANCES.gop_frames_abs, 1)
        self.assertEqual(base.TOLERANCES.bitrate_ratio, 0.15)

    def test_fixed_test_config_is_frozen(self):
        self.assertEqual(base.FIXED_TEST_CONFIG["warmupSec"], 20)
        self.assertEqual(base.FIXED_TEST_CONFIG["durationSec"], 300)
        self.assertEqual(base.FIXED_TEST_CONFIG["diagnosticsMode"], "latency")
        self.assertTrue(base.FIXED_TEST_CONFIG["steadyStateRequired"])
        self.assertEqual(base.FIXED_TEST_CONFIG["latencyMode"], "balanced")


if __name__ == "__main__":
    unittest.main()
