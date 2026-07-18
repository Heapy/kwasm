# kwasm bindgen runtime ABI

`kwasm-bindgen-runtime` adapts the runtime-agnostic `WasmHostInvoker` generated
by `:bindgen-ksp` to a kwasm `Instance`. This transport is internal and
versioned independently from Kotlin source APIs. Version 1 supports host calls
to guest exports through one wasm32 linear memory.

## Required guest exports

The instance must export all four entries with these exact names and types:

| Export | WebAssembly type | Ownership |
|---|---|---|
| `memory` | wasm32 memory | Guest-owned; this is the compiler-owned Kotlin/Wasm memory export |
| `__kwasm_bindgen_alloc_v1` | `(i32 size) -> i32 pointer` | Returns a guest allocation owned by the host until `free` |
| `__kwasm_bindgen_free_v1` | `(i32 pointer, i32 size) -> ()` | Releases exactly one previous allocation |
| `__kwasm_bindgen_invoke_v1` | `(i32 requestPointer, i32 requestSize) -> i64` | Consumes the request without taking ownership and returns a distinct result allocation |

Kotlin 2.4 has source annotations for function imports/exports but no annotation
for assigning an alias to linear memory. Generated guests therefore use the
compiler's `memory` export. The host adapter continues to accept the old
`__kwasm_bindgen_memory_v1` alias first for compatibility, then falls back to
`memory`.

The allocator must trap on failure or return an in-bounds allocation of the
requested size. A dispatch result must not overlap the request allocation.
The adapter validates export kinds and signatures at construction, checks
every range against the current memory size, copies the result, and releases
the result and request in that order. Guest traps and cancellation propagate
unchanged.

The `i64` dispatch result packs two unsigned wasm32 fields:

- bits 0–31: result pointer;
- bits 32–63: result byte length.

The current `MemoryInstance` backend is contiguous and Int-indexed, so pointer
or length values above `Int.MAX_VALUE` are rejected even though they fit an
unsigned wasm32 field.

## Request envelope

Every multi-byte value is little-endian.

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `KWRQ` |
| 4 | 1 | Runtime ABI version, currently `1` |
| 5 | 1 | Flags, zero |
| 6 | 2 | Reserved, zero |
| 8 | 4 | Boundary wire-name byte length |
| 12 | 4 | Function wire-name byte length |
| 16 | 4 | Argument-message byte length |
| 20 | … | Strict UTF-8 boundary name, strict UTF-8 function name, then argument message |

The argument bytes and returned result bytes are complete messages in the
`KWAB` format documented by
[`wasm-bindgen-api/ABI.md`](../wasm-bindgen-api/ABI.md). Keeping routing in the
outer envelope lets a guest expose one stable dispatch function without
encoding arbitrary boundary names into WebAssembly export names.

`KwasmBindgenRuntimeLimits` bounds boundary names, function names, arguments,
and results before host allocations or copies. Calls on one adapter are
serialized because a kwasm `Store` is coroutine-confined and non-reentrant.

## Generated Kotlin/Wasm guest glue

For `wasmJs` and `wasmWasi` compilations, KSP emits real top-level
`kotlin.wasm.WasmExport` declarations for `alloc`, `free`, and `invoke`.
Kotlin's linker adds the fourth required export, `memory`. A generated
`FooGuestExports(implementation).install()` call installs the typed handlers
used by the single routed `invoke` export. The linked binary contract is tested
for both Kotlin/Wasm targets.

Suspend IDL members remain suspend functions in Kotlin source, but version 1
guest dispatch is deliberately blocking-style. The generated registry starts
the suspend handler and requires it to complete in the initial call stack. If
it actually yields, dispatch fails with
`WasmGuestSuspensionNotSupportedException`; silently returning an incomplete
result is never allowed.

## Guest-to-host imports

Generated guest clients default to two raw compiler-recognized imports from
module `kwasm:bindgen/v1`:

| Import | WebAssembly type | Contract |
|---|---|---|
| `begin` | `(i32 requestPointer, i32 requestSize) -> i64` | Reads one `KWRQ` request from the caller's memory, executes the host implementation exactly once, stores its result, and returns an opaque call id in bits 0–31 plus the unsigned result byte length in bits 32–63 |
| `finish` | `(i32 callId, i32 resultPointer, i32 resultCapacity) -> i32` | Copies the pending result into caller memory, consumes it, and returns the exact number of bytes written |

`finish(callId, 0, 0)` cancels and releases a pending result. A legitimate
zero-byte result is distinguished by passing a non-zero allocation pointer
with capacity zero. Any failure in the guest after `begin` attempts this
cancellation.

These imports require caller-instance linear-memory context on the host side;
they do not re-enter the guest allocator.
`KwasmBindgenHostImportRegistry.define(linker)` installs caller-aware
implementations that serialize access to the pending-call table, validate
every caller-memory range, reject unknown ids or capacity mismatches, scope ids
to the originating instance, and consume each result exactly once.

Pending results participate in the portable snapshot transaction. Between
`begin` and `finish`, the registry's versioned participant payload contains:

- the opaque positive call id and copied result bytes for every pending call;
- a target-instance caller-association marker for every entry; and
- the next call-id allocation position, preserving wrap/skip sequencing.

Call `registry.attach(instance)` after instantiating a fresh target and before
`KwasmSnapshot.restore`. A fully validated commit rebinds all restored entries
to that exact instance. A different instance cannot finish or cancel them, and
successful `finish` still consumes a result exactly once. Each instance has a
separate Store-owned participant registration, so another live instance in the
same Store neither contributes entries to nor blocks this snapshot.

The pending table is bounded by `maxPendingCalls`, individual results by
`request.maxResultBytes`, and its complete encoded representation by
`maxPendingStateBytes` (64 MiB by default, including metadata). Restore parses
the entire payload before mutation and rejects bad magic/version/reserved
fields, non-positive or duplicate ids, foreign caller markers, truncated or
trailing data, and any configured-limit violation. The target participant must
have no live pending entries. Because result bytes can contain application
data, snapshot signing and encryption remain the embedder's responsibility.

Generated non-suspending interface functions need a target-specific blocking
bridge because core invocation is suspendable. On JVM,
`Instance.asBindgenHostInvoker()` supplies `JvmRunBlockingWasmBridge`.
Generated suspend functions invoke core directly. Other host targets can
construct `KwasmInstanceHostInvoker` with an appropriate `WasmBlockingBridge`.
