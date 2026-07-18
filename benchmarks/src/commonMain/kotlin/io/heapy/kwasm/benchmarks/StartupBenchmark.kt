package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/** Warm decode + validate + instantiate measurement for `NFR-1`. */
@State(Scope.Benchmark)
public open class StartupBenchmark {
    private lateinit var moduleBytes: ByteArray

    @Setup
    public fun prepare() {
        moduleBytes = BenchmarkFixtures.exactSizeModule(FIVE_MIB)
        check(moduleBytes.size == FIVE_MIB)
    }

    @Benchmark
    public open fun decodeValidateInstantiate5MiB(): Int {
        val module = Module.decode(moduleBytes)
        val instance = Instance(module)
        return instance.module.encodedSizeBytes
    }

    private companion object {
        const val FIVE_MIB: Int = 5 * 1024 * 1024
    }
}
