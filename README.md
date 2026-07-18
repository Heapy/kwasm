# kwasm

[![CI](https://github.com/heapy/kwasm/actions/workflows/ci.yml/badge.svg)](https://github.com/heapy/kwasm/actions/workflows/ci.yml)
[![CodeQL](https://github.com/heapy/kwasm/actions/workflows/codeql.yml/badge.svg)](https://github.com/heapy/kwasm/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

kwasm is a suspendable WebAssembly runtime written in common Kotlin for Kotlin
Multiplatform. Guest execution is an ordinary Kotlin coroutine: host imports
may suspend, cancellation is observed at deterministic checkpoints, fuel can
trap or park execution, and the guest stack is explicit heap state that can be
snapshotted.

> **Project status:** experimental, `0.1.0-SNAPSHOT`, and not yet published as
> a stable release. The repository implements substantial decoder, validator,
> interpreter, snapshot, WASI, bindgen, and TCK foundations, but it does not
> yet claim complete WebAssembly 3.0 conformance. GC, typed references, both
> exception encodings, tail calls, memory64, and multiple memories have
> executable implementations; fixed and relaxed SIMD plus threads are decoded
> and rejected as deferred features. Reproducible full-corpus, cross-target,
> compatibility-matrix, fuzz, and performance release gates remain open. Do
> not deploy kwasm as a security boundary without an independent review.

The implementation contract and requirement IDs live in
[kwasm-spec.md](kwasm-spec.md).

## Why kwasm?

- **Suspension is native.** `Machine.invoke` and every host-import call site
  are `suspend`; an import can wait without occupying a thread.
- **Coroutine lifecycle is part of the VM.** Cancellation poisons the store,
  explicit pause requests produce coordinated safe points, and fuel can be
  used as a scheduling primitive.
- **State is portable data.** Memories, tables, globals, value/call stacks,
  segment state, pending imports, and completed bindgen `begin` results have a
  versioned snapshot format bound to the SHA-256 of the exact module bytes.
- **The runtime is common Kotlin.** Core semantics do not delegate to a
  browser engine or use JVM reflection.
- **Capabilities are explicit.** A guest receives only the host imports and
  WASI preopens supplied by the embedder.

## Modules

| Gradle project | Purpose |
|---|---|
| `:annotations` | Shared pre-1.0 API opt-in marker |
| `:core` | Bounded binary decoder, validator, runtime state, and suspendable interpreter |
| `:snapshot` | Versioned snapshot encode/inspect/restore and host-state hooks |
| `:wasi` | Coroutine-friendly WASI Preview 1 host module plus in-memory and confined host filesystems |
| `:bindgen-api` | Shared boundary annotations and versioned scalar ABI |
| `:bindgen-ksp` | KSP processor for host/guest boundary source generation |
| `:bindgen-runtime` | kwasm `Instance` and linear-memory adapter for generated host clients |
| `:gradle-plugin` | Guest build discovery, binding preparation, and resource embedding |
| `:tck` | `wast2json` model/runner, tracked exclusions, and fuzz/differential seams |
| `:benchmarks` | JVM/Native performance evidence, normalized history, and regression gates |
| `:test-support-wat` | Small WAT composer used only by tests and samples |
| `:samples-cli` | End-to-end JVM smoke program |

The reserved publication coordinates are under `io.heapy.kwasm`, including
`kwasm-core`, `kwasm-snapshot`, `kwasm-wasi`, `kwasm-bindgen-api`,
`kwasm-bindgen-runtime`, and `kwasm-bindgen-ksp`. Until a release is published,
consume the projects from a source checkout or publish the snapshot artifacts
to your local Maven repository.

## API stability

Every public runtime, snapshot, WASI, and bindgen declaration is marked
`@ExperimentalKwasmApi` until kwasm reaches 1.0. The marker is warning-level
for the pre-1.0 period: consumers see unstable API usage during compilation
without an otherwise compatible upgrade becoming a hard compilation failure.
Opt in at the narrowest practical declaration or file:

```kotlin
import io.heapy.kwasm.ExperimentalKwasmApi

@OptIn(ExperimentalKwasmApi::class)
suspend fun runGuest(wasmBytes: ByteArray) {
    // Use kwasm APIs here.
}
```

The marker lives in the lightweight `kwasm-annotations` artifact and is
exported transitively by the public kwasm modules.

## Supported targets

The v1 build declares these targets:

| Family | Targets | Minimum |
|---|---|---|
| JVM | `jvm` | JDK 17 |
| Android | `androidTarget` | API 26 |
| iOS | `iosArm64`, `iosSimulatorArm64` | Current Kotlin-supported Apple SDK |
| macOS | `macosArm64`, `macosX64` | Current Kotlin-supported macOS |
| Linux | `linuxArm64`, `linuxX64` | Current Kotlin/Native toolchain |

CI runs executable tests on JVM, both Linux architectures, both macOS
architectures, and the arm64 iOS simulator. Android and physical-device iOS
artifacts are compile-checked. `:bindgen-api` and its lightweight
`:annotations` dependency additionally declare experimental `wasmJs` and
`wasmWasi` targets. `:bindgen-runtime` declares the same JVM, Android, and
Kotlin/Native host targets as `:core`.

## Build from source

Prerequisites are a JDK 17 installation and the platform SDK required by the
target being built. Use the checked-in Gradle wrapper:

```shell
./gradlew :core:jvmTest \
  :snapshot:jvmTest \
  :wasi:jvmTest \
  :bindgen-api:jvmTest \
  :bindgen-runtime:jvmTest \
  :bindgen-ksp:test \
  :gradle-plugin:check \
  :tck:jvmTest \
  :tck:verifyTckExclusions \
  :tck:verifyWasiTestsuiteExclusions
```

Run the WAT-to-runtime smoke program:

```shell
./gradlew :samples-cli:run
```

Examples of platform-specific verification:

```shell
./gradlew :core:linuxX64Test
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:compileAndroidMain
```

Kotlin/Native downloads its compiler distribution on the first native build.
Apple targets require macOS and Xcode. An iOS device target is compiled in CI
but cannot be run without a provisioned device.

## Quick start: decode and invoke

Within this repository, add `implementation(project(":core"))` to a
`commonMain` source set. For a separate local build, first run
`./gradlew :core:publishToMavenLocal` and depend on
`io.heapy.kwasm:kwasm-core:0.1.0-SNAPSHOT`.

```kotlin
import io.heapy.kwasm.ExecutionLimits
import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ModuleValidationLimits
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import kotlinx.coroutines.withTimeout

suspend fun add(wasmBytes: ByteArray): Int {
    val module = Module.decode(
        wasmBytes,
        validationLimits = ModuleValidationLimits(
            maxModuleSizeBytes = 16L * 1024 * 1024,
            maxMemoryPages = 256,
        ),
    )
    val store = Store(
        StoreConfig(
            limits = ExecutionLimits(
                maxFrames = 4_096,
                maxValueStackSlots = 262_144,
            ),
            checkpointInterval = 4_096,
            fuelEnabled = true,
            initialFuel = 1_000_000,
            fuelExhaustionPolicy = FuelExhaustionPolicy.Trap,
        ),
    )
    val instance = Instance.instantiate(store, module)

    val result = withTimeout(1_000) {
        instance.invoke(
            exportName = "add",
            arguments = listOf(Value.I32(20), Value.I32(22)),
        )
    }
    return (result.single() as Value.I32).v
}
```

`Module.decode` performs decoding and validation; a returned `Module` is safe
to share between stores. `Store` owns all mutable state and is confined to one
executing coroutine. Only its `controller`, exposed through `addFuel` and
`requestPause`, is intended for cross-dispatcher control.

Cancellation deliberately poisons a store. After a timeout or cancelled
invocation, discard the instance/store pair or restore a compatible snapshot;
do not retry the same store.

### Suspendable host imports

Imports are supplied in WebAssembly function-index order. Each import carries
its exact Wasm function type:

```kotlin
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.HostFunction
import io.heapy.kwasm.HostImport
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import kotlinx.coroutines.delay

val lookup = HostImport(
    type = FuncType(
        params = listOf(ValType.I32),
        results = listOf(ValType.I32),
    ),
    fn = HostFunction { arguments ->
        delay(10) // parks the caller; no VM thread is blocked
        val key = (arguments.single() as Value.I32).v
        listOf(Value.I32(key + 1))
    },
)

val imports = ResolvedImports(functions = listOf(lookup))
```

Host exceptions propagate to the embedding caller. They are not guest traps
and must not be translated into guest-catchable failures unless the embedder
does so intentionally.

### WASI Preview 1

WASI exposes no filesystem by default. Preopens, streams, clock, randomness,
arguments, and environment entries are explicit:

The default `random_get` source is cryptographically secure host entropy:
`SecureRandom` on JVM/Android, `SecRandomCopyBytes` on Apple targets, and
`/dev/urandom` on Linux. Supply a custom `WasiRandom` only when deterministic
testing or another embedder-managed source is required.

```kotlin
import io.heapy.kwasm.Module
import io.heapy.kwasm.Store
import io.heapy.kwasm.wasi.BufferWasiOutput
import io.heapy.kwasm.wasi.InMemoryFileSystem
import io.heapy.kwasm.wasi.WasiConfig
import io.heapy.kwasm.wasi.WasiPreopen
import io.heapy.kwasm.wasi.WasiPreview1
import io.heapy.kwasm.wasi.WasiProcessExit

suspend fun runWasi(wasmBytes: ByteArray): Pair<UInt, String> {
    val fileSystem = InMemoryFileSystem().apply {
        writeFile("input/message.txt", "hello".encodeToByteArray())
    }
    val stdout = BufferWasiOutput()
    val wasi = WasiPreview1(
        WasiConfig(
            arguments = listOf("guest.wasm"),
            preopens = listOf(
                WasiPreopen("/data", fileSystem.directory("input")),
            ),
            standardOutput = stdout,
        ),
    )
    val instance = wasi.instantiate(Store(), Module.decode(wasmBytes))

    val exitCode = try {
        instance.invoke("_start")
        0u
    } catch (exit: WasiProcessExit) {
        exit.exitCode
    }
    return exitCode to stdout.text()
}
```

On JVM, `JvmFileSystem(hostRoot)` exposes a `java.nio.file.Path` as the same
kind of explicit directory capability. It canonicalizes the preopen root,
rejects absolute guest paths, normalizes `.`/`..` without allowing a climb
above the capability, and refuses symlink resolution outside that root,
including when creating a file through a linked directory.
On Apple and Linux targets, `NativeFileSystem(hostRoot)` resolves every guest
path component relative to an open directory descriptor with `openat` and
`O_NOFOLLOW`. It can create, inspect, read, and remove links, but requests to
follow one fail closed. Close the native root capability when its WASI instance
is discarded. The `commonMain` in-memory backend supports confined relative
symbolic links, hard links, stable inode identities, and deterministic
timestamps. Absolute guest paths or link targets, and any `..` traversal that
would climb above the granted directory, fail with `NOTCAPABLE`.

### Snapshots

Portable snapshots require `StoreConfig(canonicalizeNaNs = true)` and may be
captured only at a defined suspension point: an observed explicit pause, a
fuel wait, or a suspended host import. `KwasmSnapshot.capture` refuses
incompatible configuration and `restore` verifies the exact module hash before
mutating runtime state.

`externref` values are host-owned. Supply `SnapshotHooks` to convert them to
portable keys and rehydrate them; capture/restore fails if any reference is
unresolved. Snapshot bytes are bounded and structurally checked, but they are
not signed or encrypted.

Open WASI streams, directories, and files are likewise represented by
host-owned keys, never serialized handles. A hook object used for a WASI
snapshot must implement both `SnapshotHooks` and
`WasiSnapshotResourceHooks`. Instantiate or call `WasiPreview1.attach(instance)`
so the descriptor participant is registered, then restore through
`KwasmSnapshot.restore(bytes, existingInstance, hooks)`. The target must use a
fresh, pristine `WasiPreview1`; the host decides which resources each key may
reopen, and pending operating-system I/O itself is not captured. Descriptor
numbers and kinds, reduced rights, flags, offsets, preopen metadata, and
renumbering are preserved independently of those host-owned resource keys.

## Bindgen and Gradle plugin

A shared interface is the boundary definition:

```kotlin
import io.heapy.kwasm.bindgen.WasmBoundary
import io.heapy.kwasm.bindgen.WasmExport

@WasmBoundary("example.greeter")
interface Greeter {
    @WasmExport("greet")
    suspend fun greet(name: String): String
}
```

The processor targets the documented scalar and serialization ABI. Its value
layout is described in [wasm-bindgen-api/ABI.md](wasm-bindgen-api/ABI.md).
On JVM, a generated host facade can invoke a guest instance through the
concrete runtime adapter:

```kotlin
import io.heapy.kwasm.bindgen.runtime.asBindgenHostInvoker

suspend fun greet(instance: Instance): String {
    val client = GreeterHostClient(instance.asBindgenHostInvoker())
    return client.greet("Ada")
}
```

The versioned memory/export contract is documented in
[wasm-bindgen-runtime/RUNTIME_ABI.md](wasm-bindgen-runtime/RUNTIME_ABI.md).
Both directions are implemented. KSP emits real top-level Kotlin/Wasm
imports/exports and a routed guest dispatcher; the runtime invokes guest
exports through `Instance` and registers a host implementation through
caller-aware `begin`/`finish` imports. Variable-length results are retained in
a bounded, caller-scoped pending table and copied into guest memory exactly
once. Linked `wasmJs` and `wasmWasi` binaries verify the compiler-facing
contract, and the `wasmWasi` binary is decoded, validated, instantiated, and
executed by kwasm in the JVM compatibility test. The compiler gate builds that
same fixture with pinned Kotlin `2.4.0` and previous stable `2.3.21` in an
isolated copy, then requires proof that both rows executed; see
[the compatibility guide](wasm-bindgen-runtime/KOTLIN_WASM_COMPATIBILITY.md).

Pending bindgen results are portable, bounded host-participant state. A
snapshot taken between `begin` and `finish` records each opaque call id, its
copied result bytes, caller association, and the next-id allocation sequence.
Before restoring, create a fresh registry with compatible limits, instantiate
the target, and call `registry.attach(target)`. Restoration binds the pending
entries only to that target instance, so `finish` remains caller-isolated and
consumes each result exactly once.

The participant payload is versioned and limited by `maxPendingCalls`,
`maxResultBytes`, and `maxPendingStateBytes`. Malformed, duplicate, oversized,
trailing, or foreign-caller state is rejected before the target registry is
mutated. Pending tables are registered per instance, so independent instances
in one store can be snapshotted without exposing or blocking one another.
Result bytes may contain application data; snapshot confidentiality and
integrity remain the embedder's responsibility.

The Gradle plugin ID is `io.heapy.kwasm`:

```kotlin
plugins {
    id("io.heapy.kwasm")
}

kwasm {
    guestProject.set(":guest")
    devMode.set(false)
}
```

`assembleKwasm` wires the guest Kotlin/Wasm build, applies KSP to relevant
guest compilations, collects generated bindings deterministically, and embeds
the selected `.wasm` output in the host resources.

Development reload uses Gradle's continuous-build lifecycle:

```kotlin
kwasm {
    guestProject.set(":guest")
    devMode.set(true)
}
```

```shell
./gradlew kwasmDevReload --continuous
```

The task directly tracks the linked `guestPath` `.wasm` as well as the guest
compilation graph. Gradle rebuilds after source or artifact changes, coalesces
file notifications using its continuous-build quiet period, and owns Ctrl-C,
Ctrl-D, and Tooling API cancellation. The quiet period is 250 ms by default;
override it for a run with, for example,
`-Dorg.gradle.continuous.quietperiod=500`.

Each iteration snapshots only a stable guest file, atomically replaces the
generated resource, and then atomically replaces
`build/generated/kwasm/reload/reload.properties`. The manifest is the commit
marker and contains the resource path, byte size, and SHA-256. Directory
watching, debounce, and cancellation follow Gradle's supported continuous-build
semantics rather than a plugin-owned background thread. Atomic moves are used
when the output file system supports them. On file systems that reject
`ATOMIC_MOVE`, kwasm falls back to a same-directory replace, whose atomicity is
file-system dependent. Reload consumers should verify the manifest size and
SHA-256 and retry on a mismatch, since a later iteration can advance the
resource while an earlier manifest is being consumed.

The real compiler-output gate and its optional previous-stable artifact row are
documented in
[wasm-bindgen-runtime/KOTLIN_WASM_COMPATIBILITY.md](wasm-bindgen-runtime/KOTLIN_WASM_COMPATIBILITY.md).

## Security model

kwasm treats guest bytes as untrusted and host code as trusted. Decoder,
validator, execution, and snapshot limits are part of the embedding contract,
not optional tuning. A safe deployment should set all of the following:

- conservative `ModuleValidationLimits`;
- `ExecutionLimits`, fuel, and a short checkpoint interval;
- a wall-clock deadline using coroutine cancellation;
- only the exact imports and WASI capabilities the guest needs;
- bounded snapshot limits and authenticated snapshot storage;
- independent limits on host imports, because trusted host code executes with
  the host application's authority.

**In-process execution is not hard CPU or tenant isolation.** Checkpoints are
cooperative, memory allocations consume the host process, and a runtime or host
import bug can affect the containing application. For hostile or mutually
untrusted tenants, run each guest in a disposable worker process or container.
The supervisor should enforce OS-level CPU, address-space, file-descriptor,
process-count, filesystem, and network limits; hold secrets outside the
worker; apply a hard wall-clock kill; and treat worker termination as an
expected failure mode. Coroutine cancellation and fuel remain useful inside
that boundary, but they do not replace it.

See [SECURITY.md](SECURITY.md) for the full threat model, reporting process,
snapshot guidance, and version-support policy.

## Conformance

The common TCK harness consumes WABT `wast2json` output. Upstream corpora are
not vendored. To generate a local checkout and execute every converter-readable
manifest with the JVM and current-host Native adapters:

```shell
./gradlew :tck:jvmTest :tck:macosArm64Test \
  -Pkwasm.tck.wastDir=/path/to/WebAssembly/spec/test/core \
  -Pkwasm.wabt.wast2json=/path/to/wast2json
```

Use `macosX64Test`, `linuxX64Test`, or `linuxArm64Test` on the corresponding
host.

Without `kwasm.tck.wastDir`, the generated-corpus test is a no-op and the
ordinary repository harness tests still run.
Every checked-in exclusion must include a public issue URL; the build rejects
untracked skips. See [wasm-tck/README.md](wasm-tck/README.md) for the harness
contract, explicit proposal switches, Native adapters, and converter
limitations.

The scheduled/manual pinned gate fixes the WebAssembly spec, WABT, and
`wasi-testsuite` commits. Its current JVM evidence is:

- Wasm 3.0 `wg-3.0`: 97 top-level scripts discovered, 89 converted, and
  20,900/20,900 generated commands passed;
- eight named, issue-backed WABT parser failures reported as **not executed**;
- official WASI Preview 1: all 72 pinned C, Rust, and AssemblyScript
  `wasm32-wasip1` fixtures passed with no exclusions.

The workflow repeats the readable core corpus on Linux x64 and macOS arm64
Native, and repeats the complete pinned WASI corpus on macOS arm64. These
numbers deliberately do not claim the eight unconverted scripts or nested
proposal directories.

The same module runs configured official WASI Preview 1 fixtures on JVM and
host-runnable Native targets:

```shell
./gradlew :tck:jvmTest :tck:macosArm64Test \
  -Pkwasm.wasi.testsuiteDir=/path/to/wasi-testsuite
```

Absent corpus properties skip only the external-corpus adapters; the
repository's common harness and deterministic WASI fixture tests still run.
Passing those offline tests alone is not a claim that every upstream corpus or
compiler-matrix row passes.

## License

Copyright 2026 kwasm contributors. Licensed under the
[Apache License, Version 2.0](LICENSE).
