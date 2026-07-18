# kwasm TCK harness

The harness consumes the JSON and binary files produced by WABT `wast2json`.
The upstream WebAssembly spec corpus is intentionally not vendored.

Run the JVM adapter against an existing checkout:

```shell
./gradlew :tck:jvmTest \
  -Pkwasm.tck.wastDir=/path/to/spec/test/core \
  -Pkwasm.wabt.wast2json=/path/to/wast2json
```

For the pinned `wg-3.0` top-level corpus, also select its checked-in
whole-script converter manifest:

```shell
-Pkwasm.tck.converterExclusions=wg-3.0
```

That manifest is not enabled for arbitrary corpus revisions. Without an
explicit baseline, every discovered script must be accepted by the configured
converter.

The same generated corpus can run in a standalone Native test executable on
the matching macOS or Linux host:

```shell
# Apple silicon host; use macosX64Test on an Intel Mac.
./gradlew :tck:macosArm64Test \
  -Pkwasm.tck.wastDir=/path/to/spec/test/core \
  -Pkwasm.wabt.wast2json=/path/to/wast2json

# x86-64 Linux host; linuxArm64Test is available on an Arm Linux host.
./gradlew :tck:linuxX64Test \
  -Pkwasm.tck.wastDir=/path/to/spec/test/core \
  -Pkwasm.wabt.wast2json=/path/to/wast2json
```

The configured JVM and host-runnable Native tests depend on
`:tck:generateWastJson`. The generator recursively
converts every `.wast` file, preserving its relative directory, and writes the
manifests and binary assets below `wasm-tck/build/generated/tck`. Each adapter
discovers every generated `.json` manifest, confines asset reads to that
generated root, runs each script in a fresh `WastRunner`, and reports all
script failures together. The POSIX Native loader canonicalizes every path and
rejects symlinks and special files instead of traversing them.

When `kwasm.tck.wastDir` is absent, generated output is cleared and the corpus
test returns without running an upstream suite. This keeps the default
repository test deterministic:

```shell
./gradlew :tck:jvmTest :tck:macosArm64Test
```

`kwasm.wabt.wast2json` defaults to `wast2json` on `PATH`. It can be an absolute
path when several WABT versions are installed. The generator explicitly
enables the proposals in kwasm's Wasm 3.0 baseline; it does not use
`--enable-all`, which would also opt into unrelated experimental binary
formats. Override the comma-separated switches when testing another WABT or
corpus revision:

```shell
-Pkwasm.wabt.arguments=--enable-memory64,--enable-gc
```

The default explicit proposal switches include `--no-check`, because WABT is
only the text-to-binary converter in this harness. kwasm still decodes and
validates every emitted module, including negative validation assertions.
Override `kwasm.wabt.arguments` to test a different converter policy.

Quoted malformed modules remain textual decoder inputs. A quoted
`assert_invalid` module is re-encoded with `--no-check` during generation so
the assertion reaches kwasm's binary validator instead of failing on a raw
`.wat` magic header.

Exclusions live in `src/commonMain/resources/exclusions` and are loaded by
every adapter on each configured corpus run.
Every non-comment exclusion has the strict format
`relative/test.wast[:command-line] https://issue-tracker/.../issues/123`; the
build rejects untracked skips.

Converter failures are a distinct boundary because no JSON manifest exists for
the runner to load. The only whole-script converter skip list is
`src/commonMain/resources/converter-exclusions/wabt.txt`. It contains exactly
the eight `wg-3.0` scripts which the pinned WABT parser cannot consume:
`annotations.wast`, `instance.wast`, `memory.wast`, `ref_null.wast`,
`table.wast`, `type-canon.wast`, `type-equivalence.wast`, and `type-rec.wast`.
They are not converted and are not claimed as executed. Each entry points to
the relevant WABT issue.

`:tck:verifyWast2JsonExclusions` rejects malformed, duplicate, unsorted, or
missing entries. With a corpus and converter configured, it also invokes the
converter for every listed script and fails if any entry has become
convertible. `:tck:generateWastJson` emits
`build/generated/tck/_conversion-summary.txt`, explicitly listing every script
that did not run.

`WastRunner` itself is common Kotlin and accepts a `TckAssetLoader`, so platform
test adapters decide how generated resources are loaded. The nightly
differential job can adapt `wasm-tools smith`, wasmtime, and the reference
interpreter through `DifferentialEngine`; no external process API leaks into
the common harness.

The nightly job also runs the **wasm3 interpreter**
(<https://github.com/wasm3/wasm3>) as a second oracle alongside wasmtime, built
from source under two pinned refs (the stable `v0.5.0` tag and a frozen `main`
commit) for triangulation. Source trees are content-pinned via SHA-256 of the
GitHub archive tarball. wasm3 abstains from `f32`/`f64` invocations because its
CLI prints floats through lossy `%.7g`/`%.15g` formatting; `DifferentialDriver`
excludes abstained engines from divergence detection so they can never be the
sole cause of a finding. Pass `-Pkwasm.fuzz.wasm3=<path>` (and optionally
`-Pkwasm.fuzz.wasm3Secondary=<path>`) to opt in locally.

The default pull-request CI remains offline and does not vendor or download an
upstream corpus. A manually configured local run tests exactly the checkout and
`wast2json` executable supplied by the caller. Unsupported proposal
command/value forms still require tracked runtime exclusions or harness support
before their upstream scripts can pass.

## Pinned scheduled conformance gate

`.github/workflows/pinned-conformance.yml` runs weekly and through
`workflow_dispatch`. Its upstream inputs are immutable and verified after
checkout:

- WebAssembly spec tag `wg-3.0` at
  `9d36019973201a19f9c9ebb0f10828b2fe2374aa`
- WABT at `11602da5423eb9c7517311ed01917ea231594170`
- wasi-testsuite at `caf3b66fa3457cc17156864d971387a7e9f5933b`

The core jobs stage the 97 top-level `test/core` scripts. WABT converts 89;
the eight issue-backed parser exclusions above are reported as not executed.
The generated corpus runs on JVM, Linux x64/arm64 Native, macOS x64/arm64
Native, and the arm64 iOS simulator. Gradle passes the generated corpus and
exclusion roots into the simulator test process explicitly; a configured
simulator run must execute the corpus rather than silently behaving like an
unconfigured test. This gate deliberately does not describe the nested
proposal directories as executed.

The pinned JVM replay performed while implementing this gate passed all
20,900 commands in those 89 manifests. The eight converter exclusions were
also re-probed and still failed conversion. Native legs are workflow-enforced;
they are not implied by that local JVM result.

The WASI jobs run all 72 pinned precompiled C, Rust, and AssemblyScript
`wasm32-wasip1` fixtures on JVM and macOS arm64 Native. The workflow asserts
that exact fixture count before execution, so a partial checkout cannot look
like a passing corpus. Every matrix leg uploads the Gradle log, XML/HTML test
reports, and a reader-facing summary. Core artifacts also contain the
converter summary, so skipped conversion cannot be mistaken for a passing
script.

The implementation-time JVM replay passed all 72 fixtures with zero
exclusions.

## Official WASI Preview 1 testsuite

The same module also contains a dependency-free Kotlin adapter for the official
[`wasi-testsuite`](https://github.com/WebAssembly/wasi-testsuite). Use a pinned
checkout of its `prod/testsuite-base` branch, which contains the precompiled
binaries; kwasm neither downloads nor vendors that repository in a
local/default test run:

```shell
git clone --branch prod/testsuite-base \
  https://github.com/WebAssembly/wasi-testsuite.git /path/to/wasi-testsuite
git -C /path/to/wasi-testsuite rev-parse HEAD

./gradlew :tck:jvmTest :tck:macosArm64Test \
  -Pkwasm.wasi.testsuiteDir=/path/to/wasi-testsuite
```

The property may name the checkout root, another build-output root containing
`wasm32-wasip1` directories, or one `wasm32-wasip1` directory directly.
Discovery is recursive but only Preview 1 `.wasm` fixtures run. An optional
same-basename JSON file is parsed using the upstream legacy
`args/root/env/exit_code/stdout/stderr` schema or its `run`/`read`/`wait`
operation equivalent.

Execution, metadata validation, and result reporting are common Kotlin. The JVM
and Native test adapters only discover and read files. Every declared preopen
is copied to a fresh `InMemoryFileSystem`, and every case receives fresh
streams, a fixed clock, deterministic random bytes, a new `Store`, and only the
declared environment. Assertions compare exact stdout, stderr, and unsigned
exit codes. This avoids mutating the upstream checkout and makes repeat runs
independent.

The configured adapter prints an aggregate fixture summary. Before execution
it also rejects every checked-in exclusion that does not match a discovered
case, so removed or renamed upstream fixtures cannot leave stale skips.

When `kwasm.wasi.testsuiteDir` is absent, the configured-corpus test returns
without running external fixtures, so the default build remains offline.
Skips are allowed only in
`src/commonMain/resources/wasi-exclusions/preview1.txt`, with one exact fixture
path and one HTTPS issue URL per line:

```text
tests/rust/testsuite/wasm32-wasip1/example.wasm https://github.com/heapy/kwasm/issues/123
```

`:tck:verifyWasiTestsuiteExclusions` rejects malformed paths, missing issue
links, and duplicate entries; the configured corpus adapter rejects stale
entries. Unsupported metadata operations and unlisted runtime failures fail
the corpus run rather than silently skipping a test.
