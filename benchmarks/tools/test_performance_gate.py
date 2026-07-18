import copy
import pathlib
import tempfile
import unittest

import performance_gate


def normalized(target="jvm", enabled_ratio=1.01):
    scores = {}
    for _, enabled, compiled_out in performance_gate.CHECKPOINT_PAIRS:
        scores[f"io.heapy.kwasm.benchmarks.{compiled_out}"] = 10.0
        scores[f"io.heapy.kwasm.benchmarks.{enabled}"] = 10.0 * enabled_ratio
    scores.update(
        {
            "io.heapy.kwasm.benchmarks.CallBoundaryBenchmark.guestToHostPlainRoundtrip": 0.1,
            "io.heapy.kwasm.benchmarks.CallBoundaryBenchmark.guestToHostSuspendRoundtrip": 0.2,
            "io.heapy.kwasm.benchmarks.StartupBenchmark.decodeValidateInstantiate5MiB": 100.0,
            "io.heapy.kwasm.benchmarks.Snapshot64MiBBenchmark.encode64MiB": 100.0,
            "io.heapy.kwasm.benchmarks.Snapshot64MiBBenchmark.restore64MiB": 125.0,
        },
    )
    return {
        "schemaVersion": 1,
        "kind": "kwasm-benchmark-baseline",
        "runtime": "kwasm",
        "target": target,
        "source": "test",
        "generatedAtUtc": "2026-07-18T00:00:00+00:00",
        "measurements": [
            {
                "name": name,
                "scoreMsPerOp": score,
                "scoreErrorMsPerOp": 0.0,
                "sourceUnit": "ms/op",
            }
            for name, score in sorted(scores.items())
        ],
    }


class PerformanceGateTest(unittest.TestCase):
    def test_latest_target_report_uses_timestamp_directory_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            older = root / "2026-07-18T01.00.00" / "jvm.json"
            newer = root / "2026-07-18T02.00.00" / "jvm.json"
            older.parent.mkdir()
            newer.parent.mkdir()
            older.write_text("[]", encoding="utf-8")
            newer.write_text("[]", encoding="utf-8")

            self.assertEqual(
                newer,
                performance_gate._latest_target_report(root, "jvm"),
            )

    def test_normalize_converts_average_time_units(self):
        report = performance_gate.normalize_report(
            [
                {
                    "benchmark": "sample.Benchmark.run",
                    "primaryMetric": {
                        "score": 2_000_000,
                        "scoreError": 100_000,
                        "scoreUnit": "ns/op",
                    },
                },
            ],
            "jvm",
            "test",
        )

        self.assertEqual(2.0, report["measurements"][0]["scoreMsPerOp"])
        self.assertAlmostEqual(0.1, report["measurements"][0]["scoreErrorMsPerOp"])

    def test_checkpoint_geomean_is_strictly_enforced(self):
        result, failed = performance_gate.verify_report(
            normalized(enabled_ratio=1.051),
            baseline=None,
            external_comparisons=None,
            max_regression_percent=10.0,
            enforce_snapshot_target=False,
        )

        self.assertTrue(failed)
        checkpoint = result["gates"][0]
        self.assertEqual("fail", checkpoint["status"])
        self.assertAlmostEqual(1.051, checkpoint["geomeanRatio"])

    def test_snapshot_target_is_advisory_unless_requested(self):
        advisory, advisory_failed = performance_gate.verify_report(
            normalized(),
            baseline=None,
            external_comparisons=None,
            max_regression_percent=10.0,
            enforce_snapshot_target=False,
        )
        strict, strict_failed = performance_gate.verify_report(
            normalized(),
            baseline=None,
            external_comparisons=None,
            max_regression_percent=10.0,
            enforce_snapshot_target=True,
        )

        self.assertFalse(advisory_failed)
        self.assertEqual("pass", advisory["gates"][2]["status"])
        self.assertFalse(advisory["gates"][2]["encodeTargetMet"])
        self.assertTrue(strict_failed)
        self.assertEqual("fail", strict["gates"][2]["status"])

    def test_self_history_rejects_regressions(self):
        current = normalized()
        baseline = copy.deepcopy(current)
        baseline["measurements"][0]["scoreMsPerOp"] /= 1.2

        result, failed = performance_gate.verify_report(
            current,
            baseline=baseline,
            external_comparisons=None,
            max_regression_percent=10.0,
            enforce_snapshot_target=False,
        )

        self.assertTrue(failed)
        self.assertEqual("fail", result["gates"][3]["status"])

    def test_pinned_chasm_ratio_is_enforced_when_present(self):
        current = normalized()
        current["measurements"].append(
            {
                "name": (
                    "io.heapy.kwasm.benchmarks."
                    "ExternalCoreMarkBenchmark.coreMarkWasm"
                ),
                "scoreMsPerOp": 10.0,
                "scoreErrorMsPerOp": 0.0,
                "sourceUnit": "ms/op",
            },
        )
        comparisons = {
            "schemaVersion": 1,
            "kind": "kwasm-external-comparisons",
            "records": [
                {
                    "runtime": "chasm",
                    "target": "jvm",
                    "workload": workload,
                    "scoreMsPerOp": 3.0,
                    "upstreamCommit": (
                        "9e2e2fa50eef63c793473894633a00e5d58bcefe"
                    ),
                    "measurementCommand": "./gradlew benchmark",
                    "machine": "test machine",
                    "measuredAtUtc": "2026-07-18T00:00:00Z",
                }
                for workload in ("fib35", "sha256", "json", "coremark")
            ],
        }

        result, failed = performance_gate.verify_report(
            current,
            baseline=None,
            external_comparisons=comparisons,
            max_regression_percent=10.0,
            enforce_snapshot_target=False,
        )

        self.assertTrue(failed)
        self.assertEqual("fail", result["gates"][4]["status"])

    def test_partial_chasm_rows_are_not_reported_as_proof(self):
        comparisons = {
            "schemaVersion": 1,
            "kind": "kwasm-external-comparisons",
            "records": [
                {
                    "runtime": "chasm",
                    "target": "jvm",
                    "workload": "fib35",
                    "scoreMsPerOp": 10.0,
                    "upstreamCommit": (
                        "9e2e2fa50eef63c793473894633a00e5d58bcefe"
                    ),
                    "measurementCommand": "./gradlew benchmark",
                    "machine": "test machine",
                    "measuredAtUtc": "2026-07-18T00:00:00Z",
                },
            ],
        }

        result, failed = performance_gate.verify_report(
            normalized(),
            baseline=None,
            external_comparisons=comparisons,
            max_regression_percent=10.0,
            enforce_snapshot_target=False,
        )

        self.assertFalse(failed)
        self.assertEqual("partial", result["gates"][4]["status"])
        self.assertFalse(result["gates"][4]["enforced"])


if __name__ == "__main__":
    unittest.main()
