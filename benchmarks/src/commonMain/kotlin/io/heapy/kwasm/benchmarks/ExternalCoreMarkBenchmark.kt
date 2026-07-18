package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.FuncType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Linker
import io.heapy.kwasm.Module
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/**
 * Opt-in seam for the checksum-pinned CoreMark Wasm asset recorded in
 * `benchmarks/upstreams.lock.json`.
 *
 * This benchmark is excluded from the default profile because kwasm does not
 * vendor a third-party binary. The pinned Chasm fixture imports only
 * `env.clock_ms`; a truly freestanding module is accepted as well.
 */
@State(Scope.Benchmark)
public open class ExternalCoreMarkBenchmark {
    private lateinit var instance: Instance
    private lateinit var exportName: String
    private val clockOrigin = TimeSource.Monotonic.markNow()

    @Setup
    public fun prepare() {
        val path = checkNotNull(PlatformBinary.environment("KWASM_COREMARK_WASM")) {
            "KWASM_COREMARK_WASM must name the checksum-pinned CoreMark .wasm asset"
        }
        exportName = PlatformBinary.environment("KWASM_COREMARK_EXPORT") ?: "run"
        val module = Module.decode(PlatformBinary.read(path))
        instance = when {
            module.imports.isEmpty() -> Instance(module)
            module.imports.size == 1 &&
                module.imports.single().module == "env" &&
                module.imports.single().field == "clock_ms" ->
                Linker()
                    .defineFunction(
                        module = "env",
                        name = "clock_ms",
                        type = FuncType(emptyList(), listOf(ValType.I64)),
                    ) {
                        listOf(Value.I64(clockOrigin.elapsedNow().inWholeMilliseconds))
                    }
                    .instantiate(module)
            else -> error(
                "the reviewed CoreMark module may import only env.clock_ms; " +
                    "found ${module.imports.map { "${it.module}.${it.field}" }}",
            )
        }
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
