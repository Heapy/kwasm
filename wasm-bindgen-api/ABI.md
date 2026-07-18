# kwasm bindgen binary ABI

The bindgen message format is an internal, versioned ABI. It is deliberately
small and deterministic so it can be implemented on every Kotlin target
without reflection or platform libraries.

Version 1 uses the following message header. Every multi-byte integer is
little-endian.

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `KWAB` |
| 4 | 1 | ABI version, currently `1` |
| 5 | 1 | flags, must be zero |
| 6 | 2 | reserved, must be zero |
| 8 | 4 | unsigned value count |
| 12 | … | tagged values |

Each argument or result starts with one tag byte:

| Tag | Kotlin type | Payload |
|---:|---|---|
| `0x00` | `Unit` | none |
| `0x01` | `Int` | 4-byte two's-complement integer |
| `0x02` | `Long` | 8-byte two's-complement integer |
| `0x03` | `Float` | 4 raw IEEE-754 bytes |
| `0x04` | `Double` | 8 raw IEEE-754 bytes |
| `0x05` | `Boolean` | one canonical byte, `0` or `1` |
| `0x06` | `String` | unsigned 4-byte length, then strict UTF-8 |
| `0x07` | `ByteArray` | unsigned 4-byte length, then raw bytes |
| `0x08` | `@Serializable` composite | unsigned 4-byte length, then deterministic CBOR |

Function arguments form one message containing values in declaration order. A
non-`Unit` return forms a message containing one value. A `Unit` return forms
an empty message (value count zero); the `Unit` tag remains reserved for future
composite layouts.

`WasmAbiCodec` rejects unsupported versions, unknown tags, invalid UTF-8,
non-canonical booleans, non-zero reserved fields, truncated values, trailing
bytes, and configured resource-limit violations. Numeric NaN payload bits are
preserved.

The version 1 processor accepts public top-level interfaces whose directly
declared abstract functions use non-null `Int`, `Long`, `Float`, `Double`,
`Boolean`, `String`, `ByteArray`, public non-generic `@Serializable` data
classes, and public non-generic `@Serializable` sealed hierarchies composed
from those types. `Unit` is also allowed as a return. Composite schemas are
resolved statically by generated `KSerializer` calls; there is no reflection.
Generic, inherited, extension, vararg, property, and nullable boundary
signatures are rejected with KSP diagnostics.

This document describes the runtime-agnostic value codec. The concrete kwasm
`Instance` memory/export transport is a separate layer documented in
[`wasm-bindgen-runtime/RUNTIME_ABI.md`](../wasm-bindgen-runtime/RUNTIME_ABI.md).
For `wasmJs` and `wasmWasi`, KSP also emits top-level compiler-recognized
`kotlin.wasm.WasmExport`/`WasmImport` transport glue. Generated guest export
adapters must be installed explicitly with
`FooGuestExports(implementation).install()`. The runtime document specifies
the four linked exports, the two caller-memory imports, allocation ownership,
and blocking-style suspend behavior.
