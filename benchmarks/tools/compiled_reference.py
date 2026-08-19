#!/usr/bin/env python3
"""Build an informational kwasm/Wasmtime compiled-runtime reference report."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
from typing import Any


SCHEMA_VERSION = 1
WASMTIME_VERSION = "45.0.0"
WASMTIME_COMMIT = "377cd917af258d932d55b201a646917ecf193639"
COREMARK_SHA256 = (
    "77da1d88a16d432a6c74d3e60d1e239003f2adc1e50b31125507bb8e175af05a"
)
COREMARK_FIXED_ITERATIONS = 100
WARMUP_ITERATIONS = 3
MEASUREMENT_ITERATIONS = 5
TARGET_ITERATION_MILLISECONDS = 1000
TARGETS = frozenset(("jvm", "macosArm64", "linuxArm64", "linuxX64"))
NATIVE_ARTIFACT_PLATFORM = {
    "macosArm64": "aarch64-macos",
    "linuxArm64": "aarch64-linux",
    "linuxX64": "x86_64-linux",
}
KWASM_WORKLOADS = {
    "fib35": "GuestWorkloadsBenchmark.fib35CheckpointEnabled",
    "sha256": "GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled",
    "json": "GuestWorkloadsBenchmark.jsonParseCheckpointEnabled",
    "coremark": "ExternalCoreMarkBenchmark.coreMarkWasm",
}


class ReferenceInputError(ValueError):
    pass


def _read_json(path: pathlib.Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise ReferenceInputError(f"cannot read JSON {path}: {failure}") from failure


def _write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as failure:
        raise ReferenceInputError(f"cannot hash {path}: {failure}") from failure
    return digest.hexdigest()


def _positive(value: Any, field: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ReferenceInputError(f"{field} must be numeric") from failure
    if not math.isfinite(number) or number <= 0:
        raise ReferenceInputError(f"{field} must be finite and positive")
    return number


def _non_negative(value: Any, field: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ReferenceInputError(f"{field} must be numeric") from failure
    if not math.isfinite(number) or number < 0:
        raise ReferenceInputError(f"{field} must be finite and non-negative")
    return number


def _require_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise ReferenceInputError(f"{field} must be a non-empty string")
    return value


def _normalized_measurements(report: Any, target: str) -> dict[str, dict[str, Any]]:
    if not isinstance(report, dict):
        raise ReferenceInputError("kwasm report must be an object")
    if report.get("schemaVersion") != 1 or report.get("kind") != "kwasm-benchmark-baseline":
        raise ReferenceInputError("kwasm report is not a normalized benchmark report")
    if report.get("runtime") != "kwasm" or report.get("target") != target:
        raise ReferenceInputError("kwasm report runtime/target does not match")
    measurements = report.get("measurements")
    if not isinstance(measurements, list):
        raise ReferenceInputError("kwasm report has no measurements")
    by_workload: dict[str, dict[str, Any]] = {}
    for workload, suffix in KWASM_WORKLOADS.items():
        matches = [
            measurement
            for measurement in measurements
            if isinstance(measurement, dict)
            and isinstance(measurement.get("name"), str)
            and measurement["name"].endswith(suffix)
        ]
        if len(matches) != 1:
            raise ReferenceInputError(
                f"kwasm report must contain exactly one {workload} row",
            )
        measurement = matches[0]
        _positive(measurement.get("scoreMsPerOp"), f"kwasm.{workload}.score")
        _non_negative(
            measurement.get("scoreErrorMsPerOp", 0.0),
            f"kwasm.{workload}.scoreError",
        )
        by_workload[workload] = measurement
    return by_workload


def _wasmtime_measurements(
    report: Any,
    target: str,
    upstream_lock: dict[str, Any],
) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    if not isinstance(report, dict):
        raise ReferenceInputError("Wasmtime report must be an object")
    expected_header = {
        "schemaVersion": 1,
        "kind": "wasmtime-compiled-reference-raw",
        "runtime": "wasmtime",
        "version": WASMTIME_VERSION,
        "upstreamCommit": WASMTIME_COMMIT,
        "engine": "cranelift",
        "optimization": "speed",
        "target": target,
        "warmupIterations": WARMUP_ITERATIONS,
        "measurementIterations": MEASUREMENT_ITERATIONS,
        "targetIterationMilliseconds": TARGET_ITERATION_MILLISECONDS,
    }
    for field, expected in expected_header.items():
        if report.get(field) != expected:
            raise ReferenceInputError(
                f"Wasmtime {field} must be {expected!r}, got {report.get(field)!r}",
            )
    _positive(report.get("engineInitializationMs"), "Wasmtime engine initialization")

    try:
        lock = upstream_lock["sources"]["wasmtime"]
    except (KeyError, TypeError) as failure:
        raise ReferenceInputError("upstream lock has no Wasmtime source") from failure
    if not isinstance(lock, dict):
        raise ReferenceInputError("upstream Wasmtime lock must be an object")
    if lock.get("version") != WASMTIME_VERSION or lock.get("commit") != WASMTIME_COMMIT:
        raise ReferenceInputError("upstream lock does not pin the expected Wasmtime release")
    if lock.get("engine") != "cranelift":
        raise ReferenceInputError("upstream lock does not pin Cranelift")
    artifact = _require_text(report.get("artifact"), "Wasmtime artifact")
    artifact_sha256 = _require_text(
        report.get("artifactSha256"),
        "Wasmtime artifact SHA-256",
    )
    artifacts = lock.get("artifacts")
    if not isinstance(artifacts, dict) or artifacts.get(artifact) != artifact_sha256:
        raise ReferenceInputError("Wasmtime artifact is not checksum-pinned by the lock")
    required_platform = NATIVE_ARTIFACT_PLATFORM.get(target)
    if required_platform is not None and required_platform not in artifact:
        raise ReferenceInputError(
            f"Wasmtime artifact does not match the native target {target}",
        )

    measurements = report.get("measurements")
    if not isinstance(measurements, list):
        raise ReferenceInputError("Wasmtime report has no measurements")
    by_workload: dict[str, dict[str, Any]] = {}
    for measurement in measurements:
        if not isinstance(measurement, dict):
            raise ReferenceInputError("Wasmtime measurement must be an object")
        workload = measurement.get("workload")
        if workload not in KWASM_WORKLOADS or workload in by_workload:
            raise ReferenceInputError(f"unexpected or duplicate Wasmtime workload {workload!r}")
        score = _positive(
            measurement.get("scoreMsPerOp"),
            f"Wasmtime.{workload}.score",
        )
        _positive(
            measurement.get("compileInstantiateMs"),
            f"Wasmtime.{workload}.compileInstantiateMs",
        )
        operations = measurement.get("operationsPerSample")
        if type(operations) is not int or operations <= 0:
            raise ReferenceInputError(
                f"Wasmtime.{workload}.operationsPerSample must be positive",
            )
        samples = measurement.get("samplesMsPerOp")
        if not isinstance(samples, list) or len(samples) != MEASUREMENT_ITERATIONS:
            raise ReferenceInputError(
                f"Wasmtime.{workload} must contain {MEASUREMENT_ITERATIONS} samples",
            )
        sample_values = [
            _positive(value, f"Wasmtime.{workload}.sample") for value in samples
        ]
        sample_mean = sum(sample_values) / len(sample_values)
        if not math.isclose(score, sample_mean, rel_tol=1e-9, abs_tol=1e-12):
            raise ReferenceInputError(
                f"Wasmtime.{workload}.score is not the mean of its samples",
            )
        by_workload[workload] = measurement
    if set(by_workload) != set(KWASM_WORKLOADS):
        raise ReferenceInputError("Wasmtime report does not cover all canonical workloads")
    return by_workload, lock


def create_report(
    kwasm_report: Any,
    wasmtime_report: Any,
    target: str,
    fixture_paths: dict[str, pathlib.Path],
    machine: str,
    measurement_command: str,
    measured_at_utc: str,
    upstream_lock_path: str,
    upstream_lock: dict[str, Any],
) -> dict[str, Any]:
    if target not in TARGETS:
        raise ReferenceInputError(f"unsupported benchmark target {target!r}")
    machine = _require_text(machine, "machine")
    measurement_command = _require_text(measurement_command, "measurement command")
    measured_at_utc = _require_text(measured_at_utc, "measurement timestamp")
    upstream_lock_path = _require_text(upstream_lock_path, "upstream lock path")
    if set(fixture_paths) != set(KWASM_WORKLOADS):
        raise ReferenceInputError("all four canonical fixture paths are required")
    fixture_sha256 = {
        workload: _sha256(path) for workload, path in fixture_paths.items()
    }
    if fixture_sha256["coremark"] != COREMARK_SHA256:
        raise ReferenceInputError("CoreMark fixture does not match the fixed-work lock")

    kwasm = _normalized_measurements(kwasm_report, target)
    wasmtime, lock = _wasmtime_measurements(wasmtime_report, target, upstream_lock)
    records = []
    ratios = []
    for workload in KWASM_WORKLOADS:
        kwasm_measurement = kwasm[workload]
        wasmtime_measurement = wasmtime[workload]
        kwasm_score = float(kwasm_measurement["scoreMsPerOp"])
        wasmtime_score = float(wasmtime_measurement["scoreMsPerOp"])
        ratio = kwasm_score / wasmtime_score
        ratios.append(ratio)
        records.append(
            {
                "workload": workload,
                "fixtureSha256": fixture_sha256[workload],
                "kwasmBenchmark": kwasm_measurement["name"],
                "kwasmScoreMsPerOp": kwasm_score,
                "kwasmScoreErrorMsPerOp": float(
                    kwasm_measurement.get("scoreErrorMsPerOp", 0.0),
                ),
                "wasmtimeScoreMsPerOp": wasmtime_score,
                "wasmtimeSamplesMsPerOp": [
                    float(value)
                    for value in wasmtime_measurement["samplesMsPerOp"]
                ],
                "wasmtimeOperationsPerSample": wasmtime_measurement[
                    "operationsPerSample"
                ],
                "wasmtimeCompileInstantiateMs": float(
                    wasmtime_measurement["compileInstantiateMs"],
                ),
                "kwasmOverWasmtime": ratio,
            },
        )
    geomean = math.exp(sum(math.log(ratio) for ratio in ratios) / len(ratios))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "kwasm-wasmtime-compiled-reference",
        "informational": True,
        "gateStatus": "not-a-gate",
        "comparisonClass": "optimizing-jit-reference",
        "reason": (
            "Wasmtime/Cranelift is a compiled ceiling reference, not an "
            "interpreter NFR peer"
        ),
        "target": target,
        "machine": machine,
        "measurementCommand": measurement_command,
        "measuredAtUtc": measured_at_utc,
        "upstreamLock": upstream_lock_path,
        "protocol": {
            "warmupIterations": WARMUP_ITERATIONS,
            "measurementIterations": MEASUREMENT_ITERATIONS,
            "targetIterationMilliseconds": TARGET_ITERATION_MILLISECONDS,
            "kwasmCheckpointMode": "Enabled",
            "wasmtimeFuelEnabled": False,
            "hostCallBoundary": "checked-public-api",
            "coreMarkIterations": COREMARK_FIXED_ITERATIONS,
            "coreMarkGuestElapsedMilliseconds": 10000,
        },
        "wasmtime": {
            "version": lock["version"],
            "upstreamCommit": lock["commit"],
            "engine": "cranelift",
            "optimization": "speed",
            "artifact": wasmtime_report["artifact"],
            "artifactSha256": wasmtime_report["artifactSha256"],
            "engineInitializationMs": float(
                wasmtime_report["engineInitializationMs"],
            ),
        },
        "geomeanKwasmOverWasmtime": geomean,
        "records": records,
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kwasm", type=pathlib.Path, required=True)
    parser.add_argument("--wasmtime", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--target", choices=sorted(TARGETS), required=True)
    parser.add_argument("--fib-wasm", type=pathlib.Path, required=True)
    parser.add_argument("--sha-wasm", type=pathlib.Path, required=True)
    parser.add_argument("--json-wasm", type=pathlib.Path, required=True)
    parser.add_argument("--coremark-wasm", type=pathlib.Path, required=True)
    parser.add_argument("--machine", required=True)
    parser.add_argument("--measurement-command", required=True)
    parser.add_argument("--upstream-lock", type=pathlib.Path, required=True)
    parser.add_argument("--measured-at-utc")
    return parser


def main(arguments: list[str] | None = None) -> int:
    options = _parser().parse_args(arguments)
    measured_at_utc = options.measured_at_utc or dt.datetime.now(
        dt.timezone.utc,
    ).isoformat()
    try:
        upstream_lock = _read_json(options.upstream_lock)
        report = create_report(
            _read_json(options.kwasm),
            _read_json(options.wasmtime),
            options.target,
            {
                "fib35": options.fib_wasm,
                "sha256": options.sha_wasm,
                "json": options.json_wasm,
                "coremark": options.coremark_wasm,
            },
            options.machine,
            options.measurement_command,
            measured_at_utc,
            str(options.upstream_lock),
            upstream_lock,
        )
        _write_json(options.output, report)
    except ReferenceInputError as failure:
        _parser().error(str(failure))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
