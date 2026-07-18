# kwasm performance suite

This module supplies the evidence and gates for `TCK-9`, `SUSP-5`, `SNAP-6`,
and the measurable parts of `NFR-1`. It uses
`org.jetbrains.kotlinx.benchmark` 0.4.17 on JVM and the host Kotlin/Native
target. Reports use average wall time in `ms/op`.

No performance number is claimed from a checked-in placeholder. CI creates
the external-comparison report from both runtimes in one benchmark process
and preserves the raw and normalized evidence.

## Workloads

The default profile contains:

- recursive `fib(35)` inside a decoded kwasm guest;
- a guest SHA-256 compression-style rotate/xor/add loop (16,384 rounds);
- a guest JSON structural parser over a fixed 253-byte document;
- host-to-guest, guest-to-host immediate, and genuinely suspending
  (`yield`) call boundaries;
- warm decode + validate + instantiate of an exactly 5 MiB module;
- snapshot encode and restore with exactly 1,024 Wasm pages (64 MiB).

The JSON workload is a small deterministic Wasm parser generated from WAT so
the same bytes run on JVM and Native. The Kotlin/Wasm compatibility fixture is
not silently reused: it is a generated test executable whose test-runner and
WASI startup dominate a JSON microbenchmark. A dedicated exported
Kotlin/Wasm JSON fixture can be added later as a separately labelled workload.

Each compute workload also has a
`CheckpointMode.CompiledOutEquivalent` row. That explicit benchmark-only
mode selects a separate interpreter control loop with no per-instruction
checkpoint decrement and suppresses call-entry/loop-back-edge checkpoints.
It is unsafe for production because cancellation, pausing, snapshots, and
fuel depend on checkpoints.

## Running and gating

Run the JVM suite and produce normalized, machine-readable evidence:

```shell
./gradlew :benchmarks:jvmBenchmark :benchmarks:normalizeJvmBenchmark
```

Run the strict gate:

```shell
./gradlew :benchmarks:jvmPerformanceGate \
  -Pkwasm.benchmark.baseline=/path/to/previous-jvm.json
```

Host Native task names follow the target, for example:

```shell
./gradlew :benchmarks:macosArm64Benchmark \
  :benchmarks:macosArm64PerformanceGate
```

Raw reports are written below timestamped directories at
`benchmarks/build/reports/benchmarks/main/<timestamp>/`. The normalization
task selects the report produced by the latest benchmark run. Normalized
history records and gate reports are written below
`benchmarks/build/performance/`.

The gate enforces:

- `SUSP-5`: geomean(enabled / compiled-out-equivalent) ≤ 1.05 across fib,
  SHA, JSON, and host-to-guest workloads;
- `NFR-1` JVM startup: 5 MiB decode + validate + instantiate ≤ 150 ms;
- self-history: no row regresses by more than 10% by default (override with
  `kwasm.benchmark.maxRegressionPercent`).

If no history file is supplied, self-history is reported as `bootstrap`, not
as passing. The 1 GiB/s `SNAP-6` encode target is always calculated and
reported. It is advisory because `SNAP-6` is a SHOULD; use
`-Pkwasm.benchmark.enforceSnapshotTarget=true` to make it fatal. The
cross-platform harness has no comparable peak-allocation counter, so the
`SNAP-6` memory-overhead clause is reported as `not-measured` rather than
guessed. Consequently the top-level report remains `partial` even when every
currently active fatal gate passes; a green workflow is not represented as
proof of every performance clause.

Run the deterministic gate-tool tests without executing benchmarks:

```shell
./gradlew :benchmarks:performanceGateToolTest
```

## CoreMark and external runtimes

No third-party benchmark binary is vendored. Repositories and immutable
commits for Sightglass methodology, EEMBC CoreMark, Chasm, and Chicory are in
`upstreams.lock.json`. EEMBC distributes CoreMark under Apache-2.0 together
with its acceptable-use and result-disclosure terms. The comparison uses the
exact `coremark.wasm` fixture shipped by Chasm 1.4.8, records the commit that
introduced it, and rejects any bytes whose SHA-256 is not the locked value.

Prepare the immutable Chasm checkout:

```shell
export KWASM_COREMARK_WASM="$(scripts/prepare-benchmark-upstreams.sh)"
```

Run CoreMark alone:

```shell
KWASM_COREMARK_WASM=/absolute/path/coremark.wasm \
KWASM_COREMARK_EXPORT=run \
./gradlew :benchmarks:jvmExternalBenchmark
```

For the NFR-1 comparison, `externalComparison` runs kwasm and the pinned
Chasm KMP interpreter against the same module bytes and arguments in one
process. It covers fib(35), SHA, JSON, and CoreMark on JVM and every declared
Native benchmark target. The report task verifies the CoreMark checksum and
records both scores, benchmark names, target, machine, command, UTC timestamp,
and Chasm commit:

```shell
./gradlew :benchmarks:jvmExternalComparisonReport

./gradlew :benchmarks:jvmPerformanceGate \
  -Pkwasm.benchmark.externalComparisons=benchmarks/build/performance/external-comparisons-jvm.json
```

Replace `jvm` with `macosArm64`, `macosX64`, `linuxArm64`, or `linuxX64` for
the per-target Native tasks. Same-target Chasm rows for all four workloads
activate the fatal `NFR-1` ≤2.5× geomean gate. A partial set is reported as
`partial` and is not treated as proof. Missing records remain `unmeasured`;
they never become a zero, synthetic baseline, or pass. The schema still
accepts separately collected Chicory interpreter/compiler rows as
informational JVM context.

For comparable runs, use an idle dedicated runner, fixed power/performance
settings, the same commit and benchmark profile, and preserve the raw report,
machine description, command, and timestamp. This follows the core
same-machine principle of the
[Sightglass methodology](https://github.com/bytecodealliance/sightglass).

## Native image

The performance workflow separately builds the real JVM smoke application
with GraalVM `native-image --no-fallback` and executes it. JMH itself is not
used as the native-image proof because its reflective discovery machinery
would test JMH configuration rather than kwasm's zero-reflection runtime.

## iOS incremental footprint

`NFR-2` is enforced with two application-style Kotlin/Native release
frameworks and two final iOS arm64 Mach-O executables:

- the baseline executes a real `runBlocking`/`yield` path, so Kotlin and
  `kotlinx.coroutines` are present in both outputs;
- the core probe internally decodes, validates, instantiates, and executes a
  Wasm function returning `i32.const 42`;
- `:core` is an implementation dependency, not exported through the
  Objective-C API;
- both release frameworks use Kotlin/Native `smallBinary=true` and
  `latin1Strings=true`;
- both final executables use linker dead-code elimination and are stripped
  before their byte sizes are compared.

Run the macOS/Xcode-only gate with:

```shell
bash scripts/verify-ios-core-footprint.sh
```

The machine-readable evidence is written to
`build/ios-footprint/report.json`. The gate requires the core executable minus
the baseline executable to be at most 1,500,000 bytes.
