# Kotlin/Wasm compiler compatibility

The JVM compatibility test consumes a real linked Kotlin/Wasm/WASI test
module, rather than a hand-authored fixture. The required gate follows the
[official Kotlin release history](https://kotlinlang.org/docs/releases.html#release-history):

| Compiler row | EH encoding | Binary source |
| --- | --- | --- |
| Current (`2.4.10`) | Standardized `try_table` | `:bindgen-api` wasmWasi test executable from the working checkout |
| Current (`2.4.10`) | Legacy `try`/`catch` | The exact same source fixture built in an isolated copied checkout |
| Previous stable (`2.3.21`) | Legacy `try`/`catch` | The source fixture built with the pinned previous compiler in an isolated copied checkout |
| Previous stable (`2.3.21`) | Standardized `try_table` | The same pinned previous-compiler checkout rebuilt with `-Xwasm-use-new-exception-proposal` |

Every row must decode, validate, instantiate, run the module start function
and Kotlin test suite, then cross the generated bindgen `begin`/`finish`
imports. The shared guest corpus covers WASI hello-world and standard-library
behavior, text and collections, recursion, JSON serialization round-trip,
typed catch/rethrow/finally control flow, and the generated `@WasmBoundary`
sample.

Run the complete local gate from the repository root:

```shell
bash scripts/verify-kotlin-wasm-compiler-compatibility.sh
```

The script copies source inputs into new `mktemp` directories, excludes
checkout metadata and all build products, and changes the Kotlin version
catalog only in the disposable previous-compiler copy. Each EH row is built
from a clean `:bindgen-api` output with Gradle's build cache disabled so a
linked artifact cannot leak between compiler modes. It then feeds all three
isolated binaries into the current runtime test alongside the working
checkout's default `2.4.10-new-eh` binary. The source checkout is never
rewritten. Set `KWASM_COMPAT_KEEP_WORK=1` to preserve the isolated copies for
diagnosis.

The Gradle seam remains directly usable for a prebuilt row:

```shell
./gradlew :bindgen-runtime:jvmTest \
  --tests io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest \
  -Pkwasm.kotlinWasmCompatibilityBinaries=/tmp/2.4.10-legacy.wasm:/tmp/2.3.21-legacy.wasm:/tmp/2.3.21-new.wasm \
  -Pkwasm.kotlinWasmCompatibilityVersions=2.4.10-legacy-eh,2.3.21-legacy-eh,2.3.21-new-eh \
  -Pkwasm.kotlinWasmCompatibilityRequiredVersions=2.4.10-new-eh,2.4.10-legacy-eh,2.3.21-legacy-eh,2.3.21-new-eh
```

Binary paths use the platform path separator; compiler labels and required
rows use commas. The counts must match, versions and binary paths must be
unique, and configured rows must exactly equal the required ordered list.
Every binary is a tracked test input and must expose the compatibility probe
compiled from `wasm-bindgen-api/src/wasmWasiTest`. The runtime inspects each
decoded instruction tree and rejects a row whose binary does not contain the
EH encoding named by its label.
After each full execution the test writes
`KWASM_KOTLIN_WASM_COMPATIBILITY_PASS compiler=<version>` to its JUnit output.
The local/CI gate independently requires all four compiler/EH markers, so a
missing, mislabeled, skipped, or unexecuted row fails.

The corpus uses `ModuleValidationLimits(allowInertV128Types = true)`: current
Kotlin/Wasm retains `v128` fields in linked runtime types even when it emits no
SIMD execution. SIMD instructions remain rejected, and the normal strict
validation profile still rejects every `v128` declaration.
