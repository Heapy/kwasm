package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.Instance
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Compute-heavy guest workloads used both for throughput history and the
 * `SUSP-5` enabled/compiled-out-equivalent checkpoint comparison.
 */
@State(Scope.Benchmark)
public open class GuestWorkloadsBenchmark {
    private lateinit var fibEnabled: Instance
    private lateinit var fibCompiledOut: Instance
    private lateinit var shaEnabled: Instance
    private lateinit var shaCompiledOut: Instance
    private lateinit var jsonEnabled: Instance
    private lateinit var jsonCompiledOut: Instance

    @Setup
    public fun prepare() {
        fibEnabled = BenchmarkFixtures.instance(BenchmarkFixtures.fibModule)
        fibCompiledOut = BenchmarkFixtures.instance(
            BenchmarkFixtures.fibModule,
            CheckpointMode.CompiledOutEquivalent,
        )
        shaEnabled = BenchmarkFixtures.instance(BenchmarkFixtures.sha256LoopModule)
        shaCompiledOut = BenchmarkFixtures.instance(
            BenchmarkFixtures.sha256LoopModule,
            CheckpointMode.CompiledOutEquivalent,
        )
        jsonEnabled = BenchmarkFixtures.instance(BenchmarkFixtures.jsonModule)
        jsonCompiledOut = BenchmarkFixtures.instance(
            BenchmarkFixtures.jsonModule,
            CheckpointMode.CompiledOutEquivalent,
        )
    }

    @Benchmark
    public open fun fib35CheckpointEnabled(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(fibEnabled, "fib", 35)
    }

    @Benchmark
    public open fun fib35CheckpointCompiledOutEquivalent(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(fibCompiledOut, "fib", 35)
    }

    @Benchmark
    public open fun sha256LoopCheckpointEnabled(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(shaEnabled, "sha256_loop", SHA_ROUNDS, SHA_SEED)
    }

    @Benchmark
    public open fun sha256LoopCheckpointCompiledOutEquivalent(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(shaCompiledOut, "sha256_loop", SHA_ROUNDS, SHA_SEED)
    }

    @Benchmark
    public open fun jsonParseCheckpointEnabled(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(
            jsonEnabled,
            "parse_json",
            BenchmarkFixtures.JSON_DOCUMENT.length,
        )
    }

    @Benchmark
    public open fun jsonParseCheckpointCompiledOutEquivalent(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(
            jsonCompiledOut,
            "parse_json",
            BenchmarkFixtures.JSON_DOCUMENT.length,
        )
    }

    private companion object {
        const val SHA_ROUNDS: Int = 16_384
        const val SHA_SEED: Int = 0x6A09_E667
    }
}
