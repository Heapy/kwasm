import copy
import pathlib
import unittest
from unittest import mock

import compiled_reference


def kwasm_report(target="jvm"):
    return {
        "schemaVersion": 1,
        "kind": "kwasm-benchmark-baseline",
        "runtime": "kwasm",
        "target": target,
        "source": "test",
        "measurements": [
            {
                "name": f"io.heapy.kwasm.benchmarks.{suffix}",
                "scoreMsPerOp": float(index + 2),
                "scoreErrorMsPerOp": 0.1,
                "sourceUnit": "ms/op",
            }
            for index, suffix in enumerate(
                compiled_reference.KWASM_WORKLOADS.values(),
            )
        ],
    }


def wasmtime_report(target="jvm"):
    artifact = "wasmtime-v45.0.0-aarch64-macos-c-api.tar.xz"
    artifact_sha = "a" * 64
    return {
        "schemaVersion": 1,
        "kind": "wasmtime-compiled-reference-raw",
        "runtime": "wasmtime",
        "version": compiled_reference.WASMTIME_VERSION,
        "upstreamCommit": compiled_reference.WASMTIME_COMMIT,
        "engine": "cranelift",
        "optimization": "speed",
        "artifact": artifact,
        "artifactSha256": artifact_sha,
        "target": target,
        "warmupIterations": 3,
        "measurementIterations": 5,
        "targetIterationMilliseconds": 1000,
        "engineInitializationMs": 2.0,
        "measurements": [
            {
                "workload": workload,
                "scoreMsPerOp": 1.0,
                "compileInstantiateMs": 3.0,
                "operationsPerSample": 10,
                "samplesMsPerOp": [1.0] * 5,
            }
            for workload in compiled_reference.KWASM_WORKLOADS
        ],
    }


def upstream_lock():
    artifact = "wasmtime-v45.0.0-aarch64-macos-c-api.tar.xz"
    return {
        "sources": {
            "wasmtime": {
                "version": compiled_reference.WASMTIME_VERSION,
                "commit": compiled_reference.WASMTIME_COMMIT,
                "engine": "cranelift",
                "artifacts": {artifact: "a" * 64},
            },
        },
    }


class CompiledReferenceTest(unittest.TestCase):
    def create(self, raw=None, lock=None):
        fixtures = {
            workload: pathlib.Path(f"{workload}.wasm")
            for workload in compiled_reference.KWASM_WORKLOADS
        }

        def fixture_hash(path):
            if path.name == "coremark.wasm":
                return compiled_reference.COREMARK_SHA256
            return "0" * 64

        with mock.patch.object(compiled_reference, "_sha256", fixture_hash):
            return compiled_reference.create_report(
                kwasm_report(),
                raw or wasmtime_report(),
                "jvm",
                fixtures,
                "test machine",
                "./gradlew :benchmarks:jvmCompiledReferenceReport",
                "2026-08-19T00:00:00Z",
                "../upstreams.lock.json",
                lock or upstream_lock(),
            )

    def test_report_is_separate_informational_compiled_reference(self):
        report = self.create()

        self.assertEqual("kwasm-wasmtime-compiled-reference", report["kind"])
        self.assertTrue(report["informational"])
        self.assertEqual("not-a-gate", report["gateStatus"])
        self.assertEqual(4, len(report["records"]))
        self.assertAlmostEqual(
            (2.0 * 3.0 * 4.0 * 5.0) ** 0.25,
            report["geomeanKwasmOverWasmtime"],
        )
        self.assertEqual(100, report["protocol"]["coreMarkIterations"])

    def test_report_rejects_an_unpinned_artifact(self):
        raw = copy.deepcopy(wasmtime_report())
        raw["artifactSha256"] = "b" * 64

        with self.assertRaisesRegex(
            compiled_reference.ReferenceInputError,
            "not checksum-pinned",
        ):
            self.create(raw=raw)

    def test_report_rejects_incomplete_wasmtime_coverage(self):
        raw = copy.deepcopy(wasmtime_report())
        raw["measurements"].pop()

        with self.assertRaisesRegex(
            compiled_reference.ReferenceInputError,
            "all canonical workloads",
        ):
            self.create(raw=raw)

    def test_report_rejects_a_non_mean_score(self):
        raw = copy.deepcopy(wasmtime_report())
        raw["measurements"][0]["scoreMsPerOp"] = 2.0

        with self.assertRaisesRegex(
            compiled_reference.ReferenceInputError,
            "not the mean",
        ):
            self.create(raw=raw)

    def test_report_rejects_a_cross_architecture_native_artifact(self):
        raw = wasmtime_report(target="linuxArm64")
        lock = upstream_lock()
        with self.assertRaisesRegex(
            compiled_reference.ReferenceInputError,
            "does not match the native target",
        ):
            compiled_reference._wasmtime_measurements(
                raw,
                "linuxArm64",
                lock,
            )


if __name__ == "__main__":
    unittest.main()
