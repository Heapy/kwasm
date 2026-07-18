# Kotlin/Wasm compiler compatibility

The JVM compatibility test consumes a real linked Kotlin/Wasm/WASI test
module, rather than a hand-authored fixture. The required gate follows the
[official Kotlin release history](https://kotlinlang.org/docs/releases.html#release-history):

| Compiler row | Binary source | Required execution |
| --- | --- | --- |
| Current (`2.4.10`) | `:bindgen-api` wasmWasi test executable from the working checkout | Decode, validate, instantiate, run the module start function and Kotlin test suite, then cross generated bindgen `begin`/`finish` imports |
| Previous stable (`2.3.21`) | The exact same source fixture built in an isolated copied checkout | The identical JVM compatibility test |

Run the complete local gate from the repository root:

```shell
bash scripts/verify-kotlin-wasm-compiler-compatibility.sh
```

The script copies source inputs into a new `mktemp` directory, excludes
checkout metadata and all build products, changes the Kotlin version catalog
only in that disposable copy, and builds the previous row there. It then feeds
the `2.3.21` binary into the current `2.4.10` runtime test. The source checkout
is never rewritten. Set `KWASM_COMPAT_KEEP_WORK=1` to preserve the isolated
copy for diagnosis.

The Gradle seam remains directly usable for a prebuilt row:

```shell
./gradlew :bindgen-runtime:jvmTest \
  --tests io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest \
  -Pkwasm.kotlinWasmCompatibilityBinaries=/path/to/2.3.21/kwasm-bindgen-api-test.wasm \
  -Pkwasm.kotlinWasmCompatibilityVersions=2.3.21 \
  -Pkwasm.kotlinWasmCompatibilityRequiredVersions=2.4.10,2.3.21
```

Binary paths use the platform path separator; compiler labels and required
rows use commas. The counts must match, versions and binary paths must be
unique, and configured rows must exactly equal the required ordered list.
Every binary is a tracked test input and must expose the compatibility probe
compiled from
`wasm-bindgen-api/src/wasmWasiTest/kotlin/io/heapy/kwasm/bindgen/WasmCompilerFixture.kt`.
After each full execution the test writes
`KWASM_KOTLIN_WASM_COMPATIBILITY_PASS compiler=<version>` to its JUnit output.
The local/CI gate independently requires both `2.4.10` and `2.3.21` markers, so
a missing, mislabeled, skipped, or unexecuted row fails.

The corpus uses `ModuleValidationLimits(allowInertV128Types = true)`: current
Kotlin/Wasm retains `v128` fields in linked runtime types even when it emits no
SIMD execution. SIMD instructions remain rejected, and the normal strict
validation profile still rejects every `v128` declaration.
