#!/usr/bin/env python3
"""Normalize kotlinx-benchmark output and enforce kwasm performance contracts."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
import sys
from typing import Any


SCHEMA_VERSION = 1
SNAPSHOT_GIB = 64 / 1024
CHECKPOINT_LIMIT = 1.05
STARTUP_JVM_LIMIT_MS = 150.0
SNAPSHOT_TARGET_GIB_PER_SECOND = 1.0

CHECKPOINT_PAIRS = (
    (
        "fib35",
        "GuestWorkloadsBenchmark.fib35CheckpointEnabled",
        "GuestWorkloadsBenchmark.fib35CheckpointCompiledOutEquivalent",
    ),
    (
        "sha256",
        "GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled",
        "GuestWorkloadsBenchmark.sha256LoopCheckpointCompiledOutEquivalent",
    ),
    (
        "json",
        "GuestWorkloadsBenchmark.jsonParseCheckpointEnabled",
        "GuestWorkloadsBenchmark.jsonParseCheckpointCompiledOutEquivalent",
    ),
    (
        "hostToGuest",
        "CallBoundaryBenchmark.hostToGuestPlainCheckpointEnabled",
        "CallBoundaryBenchmark.hostToGuestPlainCheckpointCompiledOutEquivalent",
    ),
)

REQUIRED_SUFFIXES = tuple(
    suffix
    for _, enabled, compiled_out in CHECKPOINT_PAIRS
    for suffix in (enabled, compiled_out)
) + (
    "CallBoundaryBenchmark.guestToHostPlainRoundtrip",
    "CallBoundaryBenchmark.guestToHostSuspendRoundtrip",
    "StartupBenchmark.decodeValidateInstantiate5MiB",
    "Snapshot64MiBBenchmark.encode64MiB",
    "Snapshot64MiBBenchmark.restore64MiB",
)

CANONICAL_EXTERNAL_WORKLOADS = {
    "fib35": "GuestWorkloadsBenchmark.fib35CheckpointEnabled",
    "sha256": "GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled",
    "json": "GuestWorkloadsBenchmark.jsonParseCheckpointEnabled",
    "coremark": "ExternalCoreMarkBenchmark.coreMarkWasm",
}
CHASM_EXTERNAL_WORKLOADS = {
    "fib35": "PinnedChasmBenchmark.fib35",
    "sha256": "PinnedChasmBenchmark.sha256",
    "json": "PinnedChasmBenchmark.json",
    "coremark": "PinnedChasmBenchmark.coreMark",
}
NFR_CHASM_WORKLOADS = frozenset(CANONICAL_EXTERNAL_WORKLOADS)
COREMARK_FIXTURE_SHA256 = (
    "77da1d88a16d432a6c74d3e60d1e239003f2adc1e50b31125507bb8e175af05a"
)
PINNED_EXTERNAL_COMMITS = {
    "chasm": "9e2e2fa50eef63c793473894633a00e5d58bcefe",
    "chicory-interpreter": "e2e2e4058f49fbffeffc5ea92c54b41534cb45d3",
    "chicory-compiler": "e2e2e4058f49fbffeffc5ea92c54b41534cb45d3",
}

UNIT_TO_MS = {
    "ns/op": 1e-6,
    "us/op": 1e-3,
    "µs/op": 1e-3,
    "ms/op": 1.0,
    "s/op": 1e3,
}


class GateInputError(ValueError):
    pass


def _read_json(path: pathlib.Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise GateInputError(f"cannot read JSON {path}: {failure}") from failure


def _write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def _latest_target_report(directory: pathlib.Path, target: str) -> pathlib.Path:
    try:
        candidates = [
            path
            for path in directory.rglob(f"{target}.json")
            if path.is_file()
        ]
    except OSError as failure:
        raise GateInputError(
            f"cannot scan benchmark report directory {directory}: {failure}",
        ) from failure
    if not candidates:
        raise GateInputError(
            f"no {target}.json benchmark report found below {directory}",
        )
    # kotlinx-benchmark uses an ISO local timestamp (with ':' replaced by '.')
    # as the direct parent directory, so lexical order is chronological.
    return max(candidates, key=lambda path: path.parent.name)


def _finite_positive(value: Any, field: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise GateInputError(f"{field} must be numeric") from failure
    if not math.isfinite(number) or number <= 0:
        raise GateInputError(f"{field} must be finite and positive, got {number}")
    return number


def normalize_report(raw: Any, target: str, source: str) -> dict[str, Any]:
    if not isinstance(raw, list) or not raw:
        raise GateInputError("kotlinx-benchmark report must be a non-empty JSON array")
    measurements: list[dict[str, Any]] = []
    names: set[str] = set()
    for index, row in enumerate(raw):
        if not isinstance(row, dict):
            raise GateInputError(f"benchmark row {index} must be an object")
        name = row.get("benchmark")
        metric = row.get("primaryMetric")
        if not isinstance(name, str) or not name:
            raise GateInputError(f"benchmark row {index} has no name")
        if name in names:
            raise GateInputError(f"duplicate benchmark row {name!r}")
        names.add(name)
        if not isinstance(metric, dict):
            raise GateInputError(f"benchmark {name!r} has no primaryMetric")
        unit = metric.get("scoreUnit")
        if unit not in UNIT_TO_MS:
            raise GateInputError(
                f"benchmark {name!r} must report average time; unsupported unit {unit!r}",
            )
        score = _finite_positive(metric.get("score"), f"{name}.primaryMetric.score")
        raw_error = metric.get("scoreError", 0.0)
        error = float(raw_error) if raw_error is not None else 0.0
        if not math.isfinite(error) or error < 0:
            error = 0.0
        factor = UNIT_TO_MS[unit]
        measurements.append(
            {
                "name": name,
                "scoreMsPerOp": score * factor,
                "scoreErrorMsPerOp": error * factor,
                "sourceUnit": unit,
            },
        )
    measurements.sort(key=lambda measurement: measurement["name"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "kwasm-benchmark-baseline",
        "runtime": "kwasm",
        "target": target,
        "source": source,
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "measurements": measurements,
    }


def _validate_normalized(report: Any, label: str) -> dict[str, Any]:
    if not isinstance(report, dict):
        raise GateInputError(f"{label} must be a JSON object")
    if report.get("schemaVersion") != SCHEMA_VERSION:
        raise GateInputError(
            f"{label} schemaVersion must be {SCHEMA_VERSION}, got "
            f"{report.get('schemaVersion')!r}",
        )
    if report.get("kind") != "kwasm-benchmark-baseline":
        raise GateInputError(f"{label} has unsupported kind {report.get('kind')!r}")
    measurements = report.get("measurements")
    if not isinstance(measurements, list) or not measurements:
        raise GateInputError(f"{label} has no measurements")
    names: set[str] = set()
    for index, measurement in enumerate(measurements):
        if not isinstance(measurement, dict):
            raise GateInputError(f"{label} measurement {index} is not an object")
        name = measurement.get("name")
        if not isinstance(name, str) or not name:
            raise GateInputError(f"{label} measurement {index} has no name")
        if name in names:
            raise GateInputError(f"{label} contains duplicate measurement {name!r}")
        names.add(name)
        _finite_positive(measurement.get("scoreMsPerOp"), f"{label}.{name}.scoreMsPerOp")
    return report


def _scores(report: dict[str, Any]) -> dict[str, float]:
    return {
        measurement["name"]: float(measurement["scoreMsPerOp"])
        for measurement in report["measurements"]
    }


def extract_external_comparisons(
    report: dict[str, Any],
    measurement_command: str,
    machine: str,
    measured_at_utc: str,
    coremark_sha256: str,
    upstream_lock: str,
) -> dict[str, Any]:
    report = _validate_normalized(report, "paired external comparison report")
    for field, value in (
        ("measurement command", measurement_command),
        ("machine", machine),
        ("measurement timestamp", measured_at_utc),
        ("upstream lock", upstream_lock),
    ):
        if not isinstance(value, str) or not value:
            raise GateInputError(f"{field} must be a non-empty string")
    if coremark_sha256 != COREMARK_FIXTURE_SHA256:
        raise GateInputError(
            "CoreMark fixture checksum does not match the pinned Chasm asset",
        )
    scores = _scores(report)
    target = str(report.get("target"))
    records = []
    for workload in sorted(NFR_CHASM_WORKLOADS):
        kwasm_name, kwasm_score = _find_suffix(
            scores,
            CANONICAL_EXTERNAL_WORKLOADS[workload],
        )
        chasm_name, chasm_score = _find_suffix(
            scores,
            CHASM_EXTERNAL_WORKLOADS[workload],
        )
        records.append(
            {
                "runtime": "chasm",
                "target": target,
                "workload": workload,
                "scoreMsPerOp": chasm_score,
                "kwasmScoreMsPerOp": kwasm_score,
                "kwasmBenchmark": kwasm_name,
                "externalBenchmark": chasm_name,
                "upstreamCommit": PINNED_EXTERNAL_COMMITS["chasm"],
                "coreMarkSha256": coremark_sha256,
                "measurementCommand": measurement_command,
                "machine": machine,
                "measuredAtUtc": measured_at_utc,
            },
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "kwasm-external-comparisons",
        "upstreamLock": upstream_lock,
        "measurementStatus": "measured",
        "records": records,
    }


def _find_suffix(scores: dict[str, float], suffix: str) -> tuple[str, float]:
    matches = [(name, score) for name, score in scores.items() if name.endswith(suffix)]
    if len(matches) != 1:
        raise GateInputError(
            f"expected exactly one measurement ending in {suffix!r}, found {len(matches)}",
        )
    return matches[0]


def _checkpoint_gate(scores: dict[str, float], enforced: bool) -> dict[str, Any]:
    rows = []
    ratios = []
    for workload, enabled_suffix, compiled_out_suffix in CHECKPOINT_PAIRS:
        enabled_name, enabled = _find_suffix(scores, enabled_suffix)
        compiled_name, compiled_out = _find_suffix(scores, compiled_out_suffix)
        ratio = enabled / compiled_out
        ratios.append(ratio)
        rows.append(
            {
                "workload": workload,
                "enabledBenchmark": enabled_name,
                "compiledOutEquivalentBenchmark": compiled_name,
                "enabledMsPerOp": enabled,
                "compiledOutEquivalentMsPerOp": compiled_out,
                "ratio": ratio,
                "overheadPercent": (ratio - 1.0) * 100.0,
            },
        )
    geomean = math.exp(sum(math.log(ratio) for ratio in ratios) / len(ratios))
    passed = geomean <= CHECKPOINT_LIMIT
    return {
        "requirement": "SUSP-5",
        "status": "pass" if (not enforced or passed) else "fail",
        "enforced": enforced,
        "reason": (
            None
            if enforced
            else "checkpoint overhead is advisory on this run; "
            "the 5% geomean budget needs a quiet dedicated machine"
        ),
        "limitRatio": CHECKPOINT_LIMIT,
        "limitMet": passed,
        "geomeanRatio": geomean,
        "geomeanOverheadPercent": (geomean - 1.0) * 100.0,
        "pairs": rows,
    }


def _startup_gate(scores: dict[str, float], target: str) -> dict[str, Any]:
    name, score = _find_suffix(
        scores,
        "StartupBenchmark.decodeValidateInstantiate5MiB",
    )
    enforced = target == "jvm"
    passed = score <= STARTUP_JVM_LIMIT_MS
    return {
        "requirement": "NFR-1-startup",
        "status": "pass" if (not enforced or passed) else "fail",
        "enforced": enforced,
        "reason": None if enforced else "the 400 ms target is for iOS-class hardware",
        "benchmark": name,
        "scoreMsPerOp": score,
        "limitMsPerOp": STARTUP_JVM_LIMIT_MS if enforced else None,
    }


def _snapshot_gate(
    scores: dict[str, float],
    enforce_target: bool,
) -> dict[str, Any]:
    encode_name, encode_ms = _find_suffix(scores, "Snapshot64MiBBenchmark.encode64MiB")
    restore_name, restore_ms = _find_suffix(scores, "Snapshot64MiBBenchmark.restore64MiB")
    encode_throughput = SNAPSHOT_GIB / (encode_ms / 1000.0)
    restore_throughput = SNAPSHOT_GIB / (restore_ms / 1000.0)
    target_met = encode_throughput >= SNAPSHOT_TARGET_GIB_PER_SECOND
    return {
        "requirement": "SNAP-6",
        "status": "pass" if (not enforce_target or target_met) else "fail",
        "targetEnforced": enforce_target,
        "encodeBenchmark": encode_name,
        "restoreBenchmark": restore_name,
        "capturedGiB": SNAPSHOT_GIB,
        "encodeGiBPerSecond": encode_throughput,
        "restoreGiBPerSecond": restore_throughput,
        "encodeTargetGiBPerSecond": SNAPSHOT_TARGET_GIB_PER_SECOND,
        "encodeTargetMet": target_met,
        "memoryOverhead": {
            "status": "not-measured",
            "reason": (
                "kotlinx-benchmark does not provide a comparable peak-allocation "
                "metric on JVM and Kotlin/Native"
            ),
        },
    }


def _history_gate(
    current: dict[str, Any],
    baseline: dict[str, Any] | None,
    max_regression_percent: float,
) -> dict[str, Any]:
    if baseline is None:
        return {
            "requirement": "TCK-9-self-history",
            "status": "bootstrap",
            "enforced": False,
            "reason": "no prior same-target baseline was supplied",
            "maxRegressionPercent": max_regression_percent,
            "comparisons": [],
        }
    if baseline.get("target") != current.get("target"):
        raise GateInputError(
            f"baseline target {baseline.get('target')!r} does not match "
            f"current target {current.get('target')!r}",
        )
    current_scores = _scores(current)
    baseline_scores = _scores(baseline)
    missing = sorted(set(current_scores) - set(baseline_scores))
    comparisons = []
    failed = False
    for name in sorted(set(current_scores) & set(baseline_scores)):
        current_score = current_scores[name]
        baseline_score = baseline_scores[name]
        regression = (current_score / baseline_score - 1.0) * 100.0
        if regression > max_regression_percent:
            failed = True
        comparisons.append(
            {
                "benchmark": name,
                "baselineMsPerOp": baseline_score,
                "currentMsPerOp": current_score,
                "regressionPercent": regression,
            },
        )
    if not comparisons:
        return {
            "requirement": "TCK-9-self-history",
            "status": "bootstrap",
            "enforced": False,
            "reason": "the supplied baseline has no benchmark names in common",
            "maxRegressionPercent": max_regression_percent,
            "newBenchmarks": missing,
            "comparisons": [],
        }
    return {
        "requirement": "TCK-9-self-history",
        "status": "fail" if failed else "pass",
        "enforced": True,
        "maxRegressionPercent": max_regression_percent,
        "newBenchmarks": missing,
        "comparisons": comparisons,
    }


def _external_gate(
    current: dict[str, Any],
    comparisons: Any | None,
) -> dict[str, Any]:
    if comparisons is None:
        return {
            "requirement": "NFR-1-external",
            "status": "unmeasured",
            "enforced": False,
            "reason": "no pinned external comparison report was supplied",
            "comparisons": [],
        }
    if not isinstance(comparisons, dict) or comparisons.get("schemaVersion") != SCHEMA_VERSION:
        raise GateInputError("external comparison report has an unsupported schema")
    if comparisons.get("kind") != "kwasm-external-comparisons":
        raise GateInputError("external comparison report has an unsupported kind")
    records = comparisons.get("records")
    if not isinstance(records, list):
        raise GateInputError("external comparison report records must be an array")
    current_scores = _scores(current)
    current_target = current.get("target")
    rows = []
    chasm_ratios: dict[str, float] = {}
    for record in records:
        if not isinstance(record, dict) or record.get("target") != current_target:
            continue
        runtime = record.get("runtime")
        workload = record.get("workload")
        if runtime not in {"chasm", "chicory-interpreter", "chicory-compiler"}:
            raise GateInputError(f"unsupported external runtime {runtime!r}")
        if record.get("upstreamCommit") != PINNED_EXTERNAL_COMMITS[runtime]:
            raise GateInputError(
                f"{runtime} record is not from the pinned upstream commit",
            )
        for provenance_field in ("measurementCommand", "machine", "measuredAtUtc"):
            if not isinstance(record.get(provenance_field), str) or not record[provenance_field]:
                raise GateInputError(
                    f"external {runtime}/{workload} record has no {provenance_field}",
                )
        suffix = CANONICAL_EXTERNAL_WORKLOADS.get(workload)
        if suffix is None:
            raise GateInputError(f"unsupported canonical external workload {workload!r}")
        paired_score = record.get("kwasmScoreMsPerOp")
        if paired_score is not None:
            current_name = record.get("kwasmBenchmark")
            if not isinstance(current_name, str) or not current_name.endswith(suffix):
                raise GateInputError(
                    f"external {runtime}/{workload} record has no matching "
                    "kwasmBenchmark",
                )
            external_name = record.get("externalBenchmark")
            external_suffix = CHASM_EXTERNAL_WORKLOADS.get(workload)
            if (
                runtime == "chasm" and
                (
                    not isinstance(external_name, str) or
                    external_suffix is None or
                    not external_name.endswith(external_suffix)
                )
            ):
                raise GateInputError(
                    f"external {runtime}/{workload} record has no matching "
                    "externalBenchmark",
                )
            if (
                runtime == "chasm" and
                record.get("coreMarkSha256") != COREMARK_FIXTURE_SHA256
            ):
                raise GateInputError(
                    f"external {runtime}/{workload} record is not from the "
                    "pinned CoreMark fixture",
                )
            current_score = _finite_positive(
                paired_score,
                f"external.{runtime}.{workload}.kwasmScoreMsPerOp",
            )
        else:
            try:
                current_name, current_score = _find_suffix(current_scores, suffix)
            except GateInputError:
                # CoreMark is intentionally absent from the default profile.
                continue
        external_score = _finite_positive(
            record.get("scoreMsPerOp"),
            f"external.{runtime}.{workload}.scoreMsPerOp",
        )
        ratio = current_score / external_score
        if runtime == "chasm":
            if workload in chasm_ratios:
                raise GateInputError(
                    f"duplicate Chasm comparison for {current_target}/{workload}",
                )
            chasm_ratios[workload] = ratio
        rows.append(
            {
                "runtime": runtime,
                "target": current_target,
                "workload": workload,
                "kwasmBenchmark": current_name,
                "kwasmMsPerOp": current_score,
                "externalMsPerOp": external_score,
                "kwasmToExternalRatio": ratio,
                "enforced": False,
            },
        )
    complete = set(chasm_ratios) == NFR_CHASM_WORKLOADS
    geomean = (
        math.exp(
            sum(math.log(ratio) for ratio in chasm_ratios.values())
            / len(chasm_ratios),
        )
        if chasm_ratios
        else None
    )
    failed = complete and geomean is not None and geomean > 2.5
    return {
        "requirement": "NFR-1-external",
        "status": (
            "fail"
            if failed
            else ("pass" if complete else ("partial" if chasm_ratios else "unmeasured"))
        ),
        "enforced": complete,
        "limitChasmRatio": 2.5,
        "chasmGeomeanRatio": geomean,
        "requiredChasmWorkloads": sorted(NFR_CHASM_WORKLOADS),
        "measuredChasmWorkloads": sorted(chasm_ratios),
        "reason": (
            None
            if complete
            else "a same-target Chasm row is required for every comparable guest workload"
        ),
        "comparisons": rows,
    }


def verify_report(
    current: dict[str, Any],
    baseline: dict[str, Any] | None,
    external_comparisons: Any | None,
    max_regression_percent: float,
    enforce_snapshot_target: bool,
    enforce_checkpoint_overhead: bool = True,
) -> tuple[dict[str, Any], bool]:
    current = _validate_normalized(current, "current report")
    baseline = (
        _validate_normalized(baseline, "baseline report")
        if baseline is not None
        else None
    )
    if not math.isfinite(max_regression_percent) or max_regression_percent < 0:
        raise GateInputError("max regression percent must be finite and non-negative")
    scores = _scores(current)
    for suffix in REQUIRED_SUFFIXES:
        _find_suffix(scores, suffix)

    gates = [
        _checkpoint_gate(scores, enforce_checkpoint_overhead),
        _startup_gate(scores, str(current.get("target"))),
        _snapshot_gate(scores, enforce_snapshot_target),
        _history_gate(current, baseline, max_regression_percent),
        _external_gate(current, external_comparisons),
    ]
    failed = any(gate["status"] == "fail" for gate in gates)
    incomplete = any(
        gate["status"] in {"bootstrap", "partial", "unmeasured"}
        for gate in gates
    ) or gates[2]["memoryOverhead"]["status"] == "not-measured"
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "kwasm-performance-gate",
        "target": current.get("target"),
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "fail" if failed else ("partial" if incomplete else "pass"),
        "activeGatesPassed": not failed,
        "gates": gates,
    }
    return result, failed


def _normalize_command(arguments: argparse.Namespace) -> int:
    input_path = (
        arguments.input
        if arguments.input is not None
        else _latest_target_report(arguments.input_directory, arguments.target)
    )
    raw = _read_json(input_path)
    normalized = normalize_report(raw, arguments.target, str(input_path))
    _write_json(arguments.output, normalized)
    print(f"normalized {len(normalized['measurements'])} benchmark(s) -> {arguments.output}")
    return 0


def _verify_command(arguments: argparse.Namespace) -> int:
    current = _read_json(arguments.current)
    baseline = _read_json(arguments.baseline) if arguments.baseline else None
    comparisons = (
        _read_json(arguments.external_comparisons)
        if arguments.external_comparisons
        else None
    )
    result, failed = verify_report(
        current=current,
        baseline=baseline,
        external_comparisons=comparisons,
        max_regression_percent=arguments.max_regression_percent,
        enforce_snapshot_target=arguments.enforce_snapshot_target,
        enforce_checkpoint_overhead=arguments.enforce_checkpoint_overhead,
    )
    _write_json(arguments.output, result)
    for gate in result["gates"]:
        print(f"{gate['requirement']}: {gate['status']}")
    print(f"performance gate: {result['status']} -> {arguments.output}")
    return 1 if failed else 0


def _extract_external_command(arguments: argparse.Namespace) -> int:
    coremark_bytes = arguments.coremark_wasm.read_bytes()
    coremark_sha256 = hashlib.sha256(coremark_bytes).hexdigest()
    measured_at_utc = (
        arguments.measured_at_utc
        or dt.datetime.now(dt.timezone.utc).isoformat()
    )
    result = extract_external_comparisons(
        report=_read_json(arguments.input),
        measurement_command=arguments.measurement_command,
        machine=arguments.machine,
        measured_at_utc=measured_at_utc,
        coremark_sha256=coremark_sha256,
        upstream_lock=arguments.upstream_lock,
    )
    _write_json(arguments.output, result)
    print(
        f"extracted {len(result['records'])} pinned Chasm comparison(s) "
        f"-> {arguments.output}",
    )
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    normalize = commands.add_parser("normalize")
    normalize_input = normalize.add_mutually_exclusive_group(required=True)
    normalize_input.add_argument("--input", type=pathlib.Path)
    normalize_input.add_argument("--input-directory", type=pathlib.Path)
    normalize.add_argument("--target", required=True)
    normalize.add_argument("--output", type=pathlib.Path, required=True)
    normalize.set_defaults(handler=_normalize_command)

    extract_external = commands.add_parser("extract-external")
    extract_external.add_argument("--input", type=pathlib.Path, required=True)
    extract_external.add_argument("--output", type=pathlib.Path, required=True)
    extract_external.add_argument("--coremark-wasm", type=pathlib.Path, required=True)
    extract_external.add_argument("--measurement-command", required=True)
    extract_external.add_argument("--machine", required=True)
    extract_external.add_argument("--measured-at-utc")
    extract_external.add_argument(
        "--upstream-lock",
        default="../upstreams.lock.json",
    )
    extract_external.set_defaults(handler=_extract_external_command)

    verify = commands.add_parser("verify")
    verify.add_argument("--current", type=pathlib.Path, required=True)
    verify.add_argument("--baseline", type=pathlib.Path)
    verify.add_argument("--external-comparisons", type=pathlib.Path)
    verify.add_argument("--output", type=pathlib.Path, required=True)
    verify.add_argument("--max-regression-percent", type=float, default=10.0)
    verify.add_argument("--enforce-snapshot-target", action="store_true")
    verify.add_argument(
        "--advisory-checkpoint-overhead",
        dest="enforce_checkpoint_overhead",
        action="store_false",
    )
    verify.set_defaults(handler=_verify_command, enforce_checkpoint_overhead=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    try:
        arguments = _parser().parse_args(argv)
        return int(arguments.handler(arguments))
    except GateInputError as failure:
        print(f"performance gate input error: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
