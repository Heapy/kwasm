# kwasm — Suspendable WebAssembly Runtime for Kotlin Multiplatform: Implementation Specification

| | |
|---|---|
| **Status** | Draft 0.1 |
| **Date** | 2026-07-18 |
| **Audience** | Runtime implementers, toolchain contributors |
| **Name** | **kwasm** — Maven group `io.heapy.kwasm`, root package `io.heapy.kwasm`, repo `heapy/kwasm` |
| **License (proposed)** | Apache-2.0 |

---

## 1. Purpose and positioning

kwasm (“the Runtime” throughout this document) is a WebAssembly virtual machine written in pure Kotlin (`commonMain`), targeting Kotlin Multiplatform. Its differentiation is **not** raw throughput; it is being the first Wasm runtime whose execution model is *natively coroutine-based*:

1. **Suspendable execution.** The interpreter loop is a `suspend` function operating on heap-allocated frames. Host imports may be `suspend` functions; guest execution parks instead of blocking a thread.
2. **Structured concurrency.** Instance lifetime, cancellation, and deadlines integrate with `kotlinx.coroutines` (`Job`, `CoroutineScope`, `withTimeout`) as first-class citizens, not wrappers.
3. **Snapshot / durable execution.** Because the guest stack is an ordinary data structure, a suspended instance can be serialized, persisted, migrated across processes *and platforms* (server JVM ↔ iOS device), and resumed.
4. **Kotlin-on-both-sides toolchain.** A shared Kotlin interface in `commonMain` acts as the IDL; KSP generates both host bindings (for this Runtime) and guest bindings (for the Kotlin/Wasm compilation target).

Non-differentiating table stakes (spec conformance, WASI, decent interpreter performance) are treated as hard requirements but not as the product.

## 2. Normative language

The key words MUST, MUST NOT, REQUIRED, SHALL, SHOULD, SHOULD NOT, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

Requirements carry stable identifiers (`[EXEC-3]`, `[SNAP-5]`, …) for traceability from tests and issues.

## 3. Scope

### 3.1 Specification baseline

The normative baseline is **WebAssembly 3.0** (the current "live" evergreen standard, released 2025-09-17):

- Core spec (live draft, includes 3.0): <https://webassembly.github.io/spec/core/>
- W3C Candidate Recommendation (evergreen, updated in place): <https://www.w3.org/TR/wasm-core-2/>
- Wasm 3.0 announcement (feature summary): <https://webassembly.org/news/2025-09-17-wasm-3.0/>
- Feature status matrix across engines: <https://webassembly.org/features/>

### 3.2 Feature matrix

| Feature | Level | Notes | Design doc |
|---|---|---|---|
| Wasm 1.0/2.0 core (numeric, control, memory, tables, multi-value, reference types, bulk memory, sign-ext, non-trapping conversions, mutable globals) | **MUST** | Full semantics, minus v128 (below) | Core spec |
| Fixed-width SIMD `v128` (part of 2.0) | **Deferred** | Decoder MUST parse; validator MUST reject with `UnsupportedFeature("simd")`. Post-1.0. | Core spec §SIMD |
| Garbage collection (struct/array/i31, subtyping) | **MUST** | Required for Kotlin/Wasm guests | <https://github.com/WebAssembly/gc/blob/main/proposals/gc/MVP.md> |
| Typed function references | **MUST** | Prerequisite of GC | <https://github.com/WebAssembly/function-references> |
| Exception handling — standardized (`exnref`, `try_table`, `throw_ref`) | **MUST** | | <https://github.com/WebAssembly/exception-handling> |
| Exception handling — legacy (`try`/`catch`/`delegate`) | **MUST** | Kotlin/Wasm toolchains still emit legacy EH by default in the wild; browsers document the legacy proposal as a baseline (<https://kotlinlang.org/docs/wasm-overview.html>) | same repo, `legacy/` docs |
| Tail calls | **MUST** | Trivial with heap frames | <https://github.com/WebAssembly/tail-call> |
| Extended constant expressions | **MUST** | | <https://github.com/WebAssembly/extended-const> |
| Multiple memories | **MUST** | | <https://github.com/WebAssembly/multi-memory> |
| Branch hinting | **MUST (parse & ignore)** | Hints carry no semantics for an interpreter | <https://github.com/WebAssembly/branch-hinting> |
| Memory64 (`i64` address type) | **SHOULD** | Not needed by Kotlin/Wasm; schedule M5+. Proposal repo archived after merge into core spec | <https://github.com/WebAssembly/memory64> (archived) |
| Relaxed SIMD | **Deferred** | Follows v128 | <https://github.com/WebAssembly/relaxed-simd> |
| Threads / shared memory / atomics | **Deferred** | Interacts with KMP memory model; revisit post-1.0 | <https://github.com/WebAssembly/threads>, <https://github.com/WebAssembly/shared-everything-threads> |
| Stack switching | **Out of scope** | The Runtime's own suspension mechanism covers the host-side use cases; guest-visible stack switching is not exposed | <https://github.com/WebAssembly/stack-switching> |
| Custom `name` section | **SHOULD** | Parsed for diagnostics/stack traces | Core spec appendix |
| Component Model / WIT | **Out of scope for the core VM** | Addressed by the bindgen layer (§5.7, M7) | <https://github.com/WebAssembly/component-model> |

`[SPEC-1]` The Runtime MUST reject unsupported features at **validation** time with a diagnostic naming the feature, never by trapping mid-execution.

`[SPEC-2]` The Runtime MUST be able to instantiate and run unmodified output of the Kotlin/Wasm compiler (`wasm-js` module semantics minus JS interop imports, and `wasm-wasi` target), for the Kotlin versions pinned in the compatibility corpus (§8.4). Kotlin/Wasm docs: <https://kotlinlang.org/docs/wasm-overview.html>, <https://kotlinlang.org/docs/wasm-wasi.html>.

### 3.3 Non-goals (v1)

- JIT/AOT code generation on any target (an interpreter by design; see prior art §11 for why the compiled niche is occupied).
- Browser-engine delegation on JS targets (MAY come later as an alternative `Machine`, but feature parity — snapshots, fuel — requires the pure interpreter anyway).
- Wasm threads, SIMD execution, sockets-level WASI.
- Being a general-purpose sandbox for hostile *host* code — the trust model is: host trusted, guest untrusted.

## 4. Architecture

### 4.1 Module layout (Gradle subprojects)

| Module | Contents | Depends on |
|---|---|---|
| `:core` | Binary decoder, validator, runtime structures (store, instances, GC heap), **suspendable interpreter**, embedding API | `kotlinx-coroutines-core` only |
| `:snapshot` | Snapshot encode/decode, module-hash binding, determinism config | `:core`, `kotlinx-io` (or hand-rolled codec) |
| `:wasi` | WASI Preview 1 host module | `:core` |
| `:bindgen-api` | Runtime-agnostic annotations, descriptors, and versioned value codec | `kotlinx-serialization` |
| `:bindgen-ksp` | KSP processor generating host + guest bindings from shared interfaces | KSP API |
| `:bindgen-runtime` | Concrete kwasm `Instance`/linear-memory adapter for generated host bindings | `:bindgen-api`, `:core` |
| `:gradle-plugin` | Wiring: guest compilation, binding generation, resource packaging | Gradle API |
| `:tck` | wast harness, spec-suite runner, exclusion lists, differential fuzz drivers | `:core`, `:wasi` |

Published artifacts: `io.heapy.kwasm:kwasm-core`, `io.heapy.kwasm:kwasm-snapshot`, `io.heapy.kwasm:kwasm-wasi`, `io.heapy.kwasm:kwasm-bindgen-api`, `io.heapy.kwasm:kwasm-bindgen-ksp`, `io.heapy.kwasm:kwasm-bindgen-runtime`, plus the Gradle plugin (plugin ID `io.heapy.kwasm`). `:tck` is internal and not published.

`[ARCH-1]` `:core` MUST have exactly one third-party dependency: `kotlinx-coroutines-core` (<https://github.com/Kotlin/kotlinx.coroutines>). No reflection, no `java.*` beyond what KMP `jvmMain` needs internally.

`[ARCH-2]` All semantics MUST live in `commonMain`. Platform source sets MAY contain only performance intrinsics (e.g., `java.util.Arrays` copies, `memcpy` via Kotlin/Native `platform.posix`) behind `expect/actual` with identical observable behavior.

### 4.2 Supported Kotlin/platform targets

`[ARCH-3]` v1 targets: `jvm` (JDK 17+), `androidTarget` (API 26+), `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`, `linuxX64`, `linuxArm64`. `mingwX64` and `js`/`wasmJs` are SHOULD, post-1.0.

`[ARCH-4]` Toolchain baseline: Kotlin 2.2+, kotlinx-coroutines 1.10+.

### 4.3 Core object model

- `Module` — immutable, validated, thread-safe, shareable across stores. Produced by `Module.decode(bytes)`.
- `Store` — owns all runtime state (memories, tables, globals, GC heap, fuel). **Not** thread-safe; confined to one coroutine at a time.
- `Instance` — instantiated module inside a store; exposes typed export lookup.
- `Machine` — the execution engine interface (v1: the suspendable interpreter; the seam where alternative backends could plug in later).
- Value representation: `i32→Int`, `i64→Long`, `f32→Float`, `f64→Double`, `funcref`/`externref`/GC refs → sealed `RefValue` hierarchy; `externref` wraps arbitrary host `Any?`. Unsigned semantics implemented on signed carriers per spec.
- GC objects (v1): runtime-internal Kotlin objects on the host heap (slot arrays + shape descriptors), visible to the host only as opaque handles. Direct structural mapping of guest GC types onto host classes is explicitly a post-1.0 research track.

## 5. Functional requirements

### 5.1 Decoding `[DEC]`

`[DEC-1]` The decoder MUST accept the full Wasm 3.0 binary grammar, including sections and opcodes of deferred features (v128, atomics), so that unsupported constructs produce precise *validation* diagnostics rather than parse errors.

`[DEC-2]` Malformed-module handling MUST match the spec's `assert_malformed` corpus: every malformed input in the test suite is rejected at decode time with a position-carrying error. Decoding MUST be total — no exceptions other than the documented error type escape, no unbounded allocation driven by declared (unverified) sizes: section size fields are treated as claims, capped against actual input length.

`[DEC-3]` The `name` custom section SHOULD be decoded lazily and used in traps/stack traces.

### 5.2 Validation `[VAL]`

`[VAL-1]` Validation MUST implement the type system of the core spec (including GC subtyping and the `exnref` typing rules) and MUST be a separate, pure phase: a `Module` object existing implies validation succeeded.

`[VAL-2]` Configurable structural limits enforced at validation/instantiation (defaults in parentheses): max module size (256 MiB), functions (1 M), function body size (7,654,321 bytes — the spec harness ceiling), locals per function (50,000), memory pages per memory (65,536 for i32), table elements (10 M), globals (1 M), recursion groups/types (1 M). Exceeding a limit is a distinct `LimitExceeded` error, not a generic failure.

`[VAL-3]` `assert_invalid` corpus green (see §8.1).

### 5.3 Execution core `[EXEC]`

`[EXEC-1]` The interpreter MUST keep the value stack and call frames as heap data structures in `Store`, never on the host thread stack. Host-stack depth per guest instruction MUST be O(1); guest recursion depth is bounded only by configured limits.

`[EXEC-2]` The top-level entry `suspend fun invoke(...)` and every host-import call site are suspension-capable. Calling a `suspend` host import MUST NOT block a thread while the host function is suspended.

`[EXEC-3]` Guest stack limits are configurable with defaults: max frames = 65,536; max value-stack slots = 1,048,576. Exhaustion produces the spec trap `call stack exhausted`.

`[EXEC-4]` Traps map to a sealed `WasmTrap : Exception` hierarchy carrying: trap kind (spec message string), function index/name, and (when the `name` section is present) a synthetic guest stack trace.

`[EXEC-5]` Exception handling: both legacy and `exnref` instruction sets MUST be executable, including cross-module tag identity. Host-thrown Kotlin exceptions crossing into the guest are NOT catchable by guest `catch_all` — they propagate to the host caller as-is. (Rationale: cancellation and host bugs must not be maskable by the guest; this matches the trap-vs-exception distinction of the EH spec.)

`[EXEC-6]` `CancellationException` MUST bypass guest handlers unconditionally and leave the `Store` in a defined "poisoned" state: further calls fail fast until the instance is discarded or restored from a snapshot.

`[EXEC-7]` Tail calls MUST reuse/replace the current heap frame (O(1) space per tail call).

`[EXEC-8]` GC: precise collection of guest GC objects is delegated to the host GC (guest objects are host objects); `i31ref` MUST be unboxed. Weak/finalization semantics: none in v1 (matches core GC MVP).

`[EXEC-9]` Determinism switch `canonicalizeNaNs: Boolean` (default `false`; forced `true` when snapshots are enabled) — all f32/f64 results with NaN payloads are canonicalized as in the deterministic profile guidance of the core spec.

### 5.4 Suspension, cancellation, fuel `[SUSP]`

`[SUSP-1]` Cooperative checkpoints MUST be executed at least: on every function call, on every loop back-edge, and at least once per N straight-line instructions (N configurable, default 16,384), implemented as a single decrementing counter shared with fuel accounting. Checkpoint work when nothing is pending MUST be a counter decrement + branch.

`[SUSP-2]` At a checkpoint the Runtime MUST observe, in order: (a) cancellation of the calling coroutine's `Job` → throw `CancellationException` per `[EXEC-6]`; (b) fuel exhaustion → behavior per `[SUSP-3]`; (c) an explicit external pause request → suspend at a snapshot-capable point.

`[SUSP-3]` Fuel: `store.fuel` is a `Long`; default cost 1 per instruction; a cost-table override MAY be provided. On exhaustion, configurable policy: `Trap` (throw `OutOfFuel`) or `Suspend` (park the coroutine until `store.addFuel(n)` — turning fuel into a scheduling primitive).

`[SUSP-4]` Fuel disabled (the default) MUST impose zero per-instruction cost beyond the `[SUSP-1]` checkpoint counter.

`[SUSP-5]` Overhead budget: checkpoints enabled vs. a build with checkpoints compiled out MUST cost ≤ 5% geomean on the benchmark suite (§8.6).

### 5.5 Snapshot & restore `[SNAP]`

`[SNAP-1]` A snapshot MAY be taken only when the instance is *suspended at a defined point*: (a) inside a `suspend` host import (captured as "pending import: function index + already-popped args"), (b) fuel-exhaustion suspension, (c) explicit pause `[SUSP-2c]`. Never mid-instruction; never from another thread while running.

`[SNAP-2]` Captured state: all memories, tables, globals, the GC heap reachable from roots, the frame stack + value stack + per-frame pc/locals, pending-import descriptor. NOT captured: host objects behind `externref` (replaced by host-supplied rehydration keys via a `SnapshotHooks` callback; absent hooks → snapshot fails with a diagnostic listing offending refs), open WASI resources (same mechanism, provided by `:wasi`).

`[SNAP-3]` Format: little-endian, versioned, self-describing sections; header contains magic, format version, Runtime semver, feature flags, and the SHA-256 of the exact module bytes. `restore` MUST verify the module hash and refuse mismatches.

`[SNAP-4]` Portability: a snapshot taken on any supported target MUST restore on any other supported target with observably identical continued execution, given: `canonicalizeNaNs=true`, identical limits configuration, and deterministic host imports. This is enforced by cross-platform round-trip tests in CI (§8.5).

`[SNAP-5]` Integrity/confidentiality of snapshot bytes (signing, encryption) is the embedder's responsibility and out of scope; the format MUST however be safe to *decode* against hostile input (fuzzed, §8.5) — restore of malicious bytes may fail but must not corrupt the host.

`[SNAP-6]` Performance target (SHOULD): snapshot encode throughput ≥ 1 GiB/s of linear memory on desktop-class hardware; memory overhead ≤ 1× captured state (streaming encode).

### 5.6 WASI Preview 1 `[WASI]`

Spec: <https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md>. Capability configuration follows the standard model: nothing is reachable unless preopened/granted.

`[WASI-1]` MUST implement: `args_get`, `args_sizes_get`, `environ_get`, `environ_sizes_get`, `clock_res_get`, `clock_time_get`, `fd_close`, `fd_fdstat_get`, `fd_prestat_get`, `fd_prestat_dir_name`, `fd_read`, `fd_seek`, `fd_write`, `path_open`, `proc_exit`, `random_get`, `sched_yield` — the set sufficient for Kotlin/Wasm `wasm-wasi`, Rust `wasm32-wasip1`, and TinyGo hello-world class programs.

`[WASI-2]` SHOULD implement the remaining filesystem family (`fd_*`, `path_*`, `fd_readdir`, `poll_oneoff` for clock subscriptions). `sock_*` and `proc_raise` are MAY/never.

`[WASI-3]` Blocking-capable calls (`fd_read` on host streams, `poll_oneoff`) MUST be implemented as `suspend` host functions — WASI I/O parks coroutines, not threads.

`[WASI-4]` Filesystem backends: real FS (jvm/native) behind preopens, plus an in-memory FS in `commonMain` for tests and iOS-friendly embedding.

### 5.7 Toolchain: shared-interface bindgen `[BIND]`

`[BIND-1]` A Kotlin interface annotated `@WasmBoundary` in `commonMain`, restricted to a documented type subset (Int/Long/Float/Double/Boolean/String/ByteArray, `@Serializable` data classes, sealed hierarchies of those; `suspend` allowed), is the IDL.

`[BIND-2]` KSP (<https://kotlinlang.org/docs/ksp-overview.html>, <https://github.com/google/ksp>) generates: (a) **host side** — a typed facade over `Instance` (exports) and import registration from a host implementation of the interface; (b) **guest side** — `@WasmExport`/import glue for the Kotlin/Wasm target. Serialization of composite types crosses the boundary via `kotlinx.serialization` (<https://github.com/Kotlin/kotlinx.serialization>) with a fixed binary codec; layout is an internal ABI, versioned.

`[BIND-3]` `suspend` in the interface maps to a suspend host import (host side) and to a blocking-style call in the guest (until guest-side async exists in the toolchain).

`[BIND-4]` The Gradle plugin wires: compiling the guest KMP module to `.wasm`, running bindgen, embedding the binary as a resource, and (dev mode) file-watch reload.

`[BIND-5]` WIT/Component Model bindgen (WIT: <https://github.com/WebAssembly/component-model/blob/main/design/mvp/WIT.md>, Canonical ABI: <https://github.com/WebAssembly/component-model/blob/main/design/mvp/CanonicalABI.md>) is a stretch milestone (M7): mapping WIT `variant`→sealed class, `record`→data class, `resource`→`AutoCloseable` handle, future async→`suspend`. The bindgen layer MUST be runtime-agnostic in its core so it can also emit Chicory-targeting host glue on JVM.

## 6. Non-functional requirements `[NFR]`

`[NFR-1]` **Performance**: geomean wall-time on the §8.6 suite ≤ 2.5× of the Chasm interpreter on JVM and Native (measured per-target). Startup: decode+validate+instantiate of a 5 MB module ≤ 150 ms on JVM warm, ≤ 400 ms on iOS-class hardware.

`[NFR-2]` **Footprint**: `:core` adds ≤ 1.5 MB to an iOS release binary; zero reflection so GraalVM native-image and R8 full mode work without configs.

`[NFR-3]` **API stability**: pre-1.0 everything `@ExperimentalKwasmApi`; post-1.0 semver with explicit-API mode on.

`[NFR-4]` **Error taxonomy**: sealed hierarchies for decode/validate/instantiate/trap/limit/snapshot errors; every error message states *what*, *where* (byte offset or function), and *which limit/feature* if applicable.

`[NFR-5]` **Observability**: an optional `ExecutionListener` (per-call, per-checkpoint granularity — not per-instruction in release builds) for metering, tracing, and debuggers.

## 7. Security requirements `[SEC]`

`[SEC-1]` Untrusted-guest model: all guest-reachable state is bounds-checked Kotlin data; no `sun.misc.Unsafe`, no off-heap addressing in v1. A Runtime bug must degrade to a Kotlin exception, never host memory corruption (the "double sandbox" property).

`[SEC-2]` No ambient authority: zero imports ⇒ pure compute. WASI grants are explicit, path-confined (preopen escape via `..`/symlinks MUST be blocked and tested).

`[SEC-3]` Decoder/validator/snapshot-decoder MUST hold up under continuous fuzzing (§8.5) with sanitized allocation limits — hostile inputs may fail, never hang unbounded or allocate unbounded.

`[SEC-4]` Resource ceilings from `[VAL-2]`/`[EXEC-3]` plus wall-clock control via cancellation `[SUSP-2]` are the documented DoS answer; the docs MUST state plainly that hard CPU isolation still requires process isolation.

## 8. Conformance and TCK

### 8.1 Core specification test suite (primary TCK)

- Canonical tests (part of the spec repo): <https://github.com/WebAssembly/spec/tree/main/test/core>
- Aggregated suite incl. proposal snapshots: <https://github.com/WebAssembly/testsuite>
- Reference interpreter (oracle for differential runs): <https://github.com/WebAssembly/spec/tree/main/interpreter>

`[TCK-1]` Harness: `.wast` scripts are converted with `wast2json` from WABT (<https://github.com/WebAssembly/wabt>, tool doc: <https://github.com/WebAssembly/wabt/blob/main/docs/wast2json.md>) into `.json` + `.wasm` at build time; a `commonTest` runner executes all assertion kinds (`assert_return`, `assert_trap`, `assert_malformed`, `assert_invalid`, `assert_unlinkable`, `assert_exhaustion`, registrations/linking).

`[TCK-2]` A single checked-in **exclusion list** (file per feature: `simd.txt`, `threads.txt`, …) is the only mechanism for skipping tests; each entry carries an issue link. CI gate: 100% pass of non-excluded tests **on every supported target** (JVM, macosArm64, linuxX64, iosSimulatorArm64 at minimum).

`[TCK-3]` GC / typed references / EH (both encodings) / tail-call / extended-const / multi-memory tests from the current suite are in scope from M3; memory64 tests activate with M5+.

### 8.2 WASI TCK

- Official suite: <https://github.com/WebAssembly/wasi-testsuite> (Preview 1 branch/assemblies).

`[TCK-4]` The `:wasi` module MUST pass the wasi-testsuite subsets corresponding to `[WASI-1]`/`[WASI-2]`, run via a Kotlin test adapter on JVM and at least one Native target; skips follow the same exclusion-list mechanism.

### 8.3 Kotlin/Wasm compatibility corpus

`[TCK-5]` A pinned corpus (Kotlin version matrix: current stable + previous stable) compiled from source in CI:
1. `wasm-wasi` hello-world + stdlib smoke (collections, text, exceptions incl. `finally`, recursion depth);
2. kotlinx.serialization JSON round-trip inside the guest;
3. an exception-heavy sample built twice: legacy EH and `-Xwasm-use-new-exception-proposal`;
4. a `@WasmBoundary` end-to-end sample through the full bindgen pipeline (from M6).

Docs anchoring the target: <https://kotlinlang.org/docs/wasm-overview.html>, <https://kotlinlang.org/docs/wasm-wasi.html>.

### 8.4 Differential and fuzz testing

`[TCK-6]` Differential execution: modules generated by `wasm-smith` (<https://github.com/bytecodealliance/wasm-tools/tree/main/crates/wasm-smith>, CLI `wasm-tools smith`) run against the reference interpreter and/or wasmtime; any divergence in result/trap kind is a bug. Nightly, with corpus minimization.

`[TCK-7]` Decoder/validator fuzzing on raw bytes; snapshot fuzzing both ways: (a) hostile snapshot bytes into `restore` `[SNAP-5]`; (b) property test — run k steps, snapshot, restore (same and cross-target), run to completion ⇒ state/result identical to an uninterrupted run `[SNAP-4]`.

### 8.5 Suspension-semantics tests (unique to this Runtime)

`[TCK-8]` Dedicated suite: cancellation at every checkpoint class; `withTimeout` around deep recursion and tight loops; suspend imports resuming on different dispatcher threads; fuel `Suspend` policy refuel/resume; poisoned-store behavior `[EXEC-6]`; guest inability to catch host exceptions `[EXEC-5]`.

### 8.6 Performance suite

`[TCK-9]` Benchmarks under `kotlinx-benchmark` + a native-image job: `fib(35)`, CoreMark-wasm, SHA-256 loop, JSON parse inside guest, call-boundary microbench (host↔guest roundtrip, suspend and plain), snapshot encode/restore of 64 MiB. Baselines recorded for Chasm (JVM & Native) and Chicory interpreter/compiler (JVM) for context; regressions gate on self-history, comparisons are informational. Sightglass methodology reference: <https://github.com/bytecodealliance/sightglass>.

## 9. Milestones and acceptance criteria

| M | Deliverable | Gate |
|---|---|---|
| **M0** | Decoder + validator + wast harness | `assert_malformed`/`assert_invalid` green (non-excluded); limits `[VAL-2]` |
| **M1** | Interpreter, Wasm 2.0 minus v128 | §8.1 green on JVM+2 Native targets; `[EXEC-1..4]` |
| **M2** | Suspension: suspend imports, cancellation, fuel | `[SUSP-*]`, §8.5 suite; overhead ≤ 5% `[SUSP-5]` |
| **M3** | GC + typed refs + EH×2 + tail calls | proposal tests green; Kotlin/Wasm corpus items 1–3 `[SPEC-2]` |
| **M4** | Snapshot/restore + determinism | `[SNAP-*]`; cross-target round-trip in CI; fuzz `[TCK-7]` |
| **M5** | WASI p1 subset (+ memory64 optionally) | wasi-testsuite subset green `[TCK-4]` |
| **M6** | KSP bindgen + Gradle plugin | corpus item 4 end-to-end; sample app (server JVM ↔ iOS demo) |
| **M7** *(stretch)* | WIT bindgen prototype; Chicory-targeting host glue; `wasmJs` delegation `Machine` | WIT sample world round-trips; bindgen runs on two runtimes |

Flagship demo (post-M6): a durable Kotlin plugin — starts on a JVM server, is snapshotted mid-`suspend`, restored on an iOS device, finishes there.

## 10. Open questions

1. Build own `:core` vs. propose a suspendable execution engine upstream in Chasm (their loop would need conversion to a suspend machine — effectively a rewrite; maintainer appetite unknown).
2. Long-term EH story: drop legacy encoding once the Kotlin toolchain defaults to `exnref`?
3. GC deep-mapping onto host classes (zero-copy strings, direct `externref` field access) — research track, JVM-first?
4. Threads: wait for shared-everything-threads to settle, or ship bounded "instance-per-coroutine + message passing" guidance instead?
5. WASI 0.3 async (component model) — natural fit for `suspend`; adopt at bindgen level once stabilized?
6. ~~Naming and namespace~~ **Resolved:** group `io.heapy.kwasm`, artifacts `kwasm-*`. Two unrelated projects share the name and MUST be disambiguated in the README rather than by renaming: jasonwyatt/KWasm (<https://github.com/jasonwyatt/KWasm>, dormant Kotlin interpreter — same niche, highest confusion risk) and the KWasm Kubernetes operator (<https://kwasm.sh>, different domain entirely). Maven group ownership keeps the coordinates unambiguous.

## 11. References

**Specifications & proposals**
- Core spec (live): <https://webassembly.github.io/spec/core/> · W3C CR: <https://www.w3.org/TR/wasm-core-2/>
- Wasm 3.0: <https://webassembly.org/news/2025-09-17-wasm-3.0/> · Wasm 2.0: <https://webassembly.org/news/2025-03-20-wasm-2.0/> · Features matrix: <https://webassembly.org/features/>
- GC: <https://github.com/WebAssembly/gc> · Function refs: <https://github.com/WebAssembly/function-references> · EH: <https://github.com/WebAssembly/exception-handling> · Tail call: <https://github.com/WebAssembly/tail-call> · Extended const: <https://github.com/WebAssembly/extended-const> · Multi-memory: <https://github.com/WebAssembly/multi-memory> · Memory64 (archived): <https://github.com/WebAssembly/memory64> · Threads: <https://github.com/WebAssembly/threads> · Relaxed SIMD: <https://github.com/WebAssembly/relaxed-simd> · Branch hinting: <https://github.com/WebAssembly/branch-hinting> · Stack switching: <https://github.com/WebAssembly/stack-switching>
- WASI: <https://github.com/WebAssembly/WASI> · Preview 1: <https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md> · Component Model: <https://github.com/WebAssembly/component-model> (WIT, Canonical ABI under `design/mvp/`)

**Test infrastructure**
- Spec tests: <https://github.com/WebAssembly/spec/tree/main/test/core> · Aggregated: <https://github.com/WebAssembly/testsuite> · Reference interpreter: <https://github.com/WebAssembly/spec/tree/main/interpreter>
- WABT / wast2json: <https://github.com/WebAssembly/wabt> · wasi-testsuite: <https://github.com/WebAssembly/wasi-testsuite> · wasm-tools & wasm-smith: <https://github.com/bytecodealliance/wasm-tools> · Binaryen (guest tooling): <https://github.com/WebAssembly/binaryen> · Sightglass: <https://github.com/bytecodealliance/sightglass>

**Kotlin ecosystem**
- Kotlin/Wasm: <https://kotlinlang.org/docs/wasm-overview.html> · Kotlin/Wasm+WASI: <https://kotlinlang.org/docs/wasm-wasi.html> · KSP: <https://kotlinlang.org/docs/ksp-overview.html>, <https://github.com/google/ksp>
- kotlinx.coroutines: <https://github.com/Kotlin/kotlinx.coroutines> · kotlinx.serialization: <https://github.com/Kotlin/kotlinx.serialization> · kotlinx-io: <https://github.com/Kotlin/kotlinx-io>

**Prior art (context for design decisions)**
- Chasm (KMP interpreter): <https://github.com/CharlieTap/chasm> · Chicory (JVM, interpreter + bytecode compiler): <https://github.com/dylibso/chicory>, <https://chicory.dev> · wazero (pure Go): <https://wazero.dev> · wasm3 (fast C interpreter): <https://github.com/wasm3/wasm3>
- wasmtime fuel & epoch interruption: <https://docs.wasmtime.dev/examples-interrupting-wasm.html>
- Durable execution over Wasm: Golem — <https://github.com/golemcloud/golem> · Plugin systems over Wasm: Extism — <https://extism.org>

**Process**
- RFC 2119: <https://www.rfc-editor.org/rfc/rfc2119> · RFC 8174: <https://www.rfc-editor.org/rfc/rfc8174>
