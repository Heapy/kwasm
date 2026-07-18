package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.HostFunction
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Linker
import io.heapy.kwasm.Module
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

@State(Scope.Benchmark)
public open class CallBoundaryBenchmark {
    private lateinit var hostToGuestEnabled: Instance
    private lateinit var hostToGuestCompiledOut: Instance
    private lateinit var guestToHostPlain: Instance
    private lateinit var guestToHostSuspend: Instance

    @Setup
    public fun prepare() {
        hostToGuestEnabled = BenchmarkFixtures.instance(BenchmarkFixtures.hostToGuestModule)
        hostToGuestCompiledOut = BenchmarkFixtures.instance(
            BenchmarkFixtures.hostToGuestModule,
            CheckpointMode.CompiledOutEquivalent,
        )
        guestToHostPlain = importedRoundtripInstance(
            HostFunction { arguments -> listOf(arguments.single()) },
        )
        guestToHostSuspend = importedRoundtripInstance(
            HostFunction { arguments ->
                yield()
                listOf(arguments.single())
            },
        )
    }

    @Benchmark
    public open fun hostToGuestPlainCheckpointEnabled(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(hostToGuestEnabled, "roundtrip", SENTINEL)
    }

    @Benchmark
    public open fun hostToGuestPlainCheckpointCompiledOutEquivalent(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(hostToGuestCompiledOut, "roundtrip", SENTINEL)
    }

    @Benchmark
    public open fun guestToHostPlainRoundtrip(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(guestToHostPlain, "roundtrip", SENTINEL)
    }

    @Benchmark
    public open fun guestToHostSuspendRoundtrip(): Int = runBlocking {
        BenchmarkFixtures.invokeI32(guestToHostSuspend, "roundtrip", SENTINEL)
    }

    private fun importedRoundtripInstance(function: HostFunction): Instance {
        val module = Module.decode(BenchmarkFixtures.guestToHostModule)
        return Linker()
            .defineFunction(
                module = "benchmark",
                name = "roundtrip",
                type = FuncType(listOf(ValType.I32), listOf(ValType.I32)),
                function = function,
            )
            .instantiate(module, Store(StoreConfig()))
    }

    private companion object {
        const val SENTINEL: Int = 0x1234_5678
    }
}
