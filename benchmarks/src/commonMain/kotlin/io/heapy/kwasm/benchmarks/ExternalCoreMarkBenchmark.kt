package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Opt-in seam for a license-reviewed, freestanding CoreMark Wasm asset.
 *
 * This benchmark is excluded from the default profile because kwasm does not
 * vendor an upstream-built binary. Set `KWASM_COREMARK_WASM` (or the same JVM
 * system property) and run the `external` benchmark profile.
 */
@State(Scope.Benchmark)
public open class ExternalCoreMarkBenchmark {
    private lateinit var instance: Instance
    private lateinit var exportName: String

    @Setup
    public fun prepare() {
        val path = checkNotNull(PlatformBinary.environment("KWASM_COREMARK_WASM")) {
            "KWASM_COREMARK_WASM must name a reviewed freestanding CoreMark .wasm asset"
        }
        exportName = PlatformBinary.environment("KWASM_COREMARK_EXPORT") ?: "run"
        val module = Module.decode(PlatformBinary.read(path))
        check(module.imports.isEmpty()) {
            "the external CoreMark seam currently accepts only freestanding modules; " +
                "found ${module.imports.size} import(s)"
        }
        instance = Instance(module)
        check(instance.exportedFunction(exportName) != null) {
            "CoreMark module has no function export '$exportName'"
        }
    }

    @Benchmark
    public open fun coreMarkWasm(): Int = runBlocking {
        instance.invoke(exportName).fold(1) { hash, value ->
            31 * hash + value.hashCode()
        }
    }
}
