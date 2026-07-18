package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.PauseHandle
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.snapshot.KwasmSnapshot
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@State(Scope.Benchmark)
public open class Snapshot64MiBBenchmark {
    private lateinit var module: Module
    private lateinit var sourceStore: Store
    private lateinit var sourceInstance: Instance
    private lateinit var pause: PauseHandle
    private lateinit var invocation: Job
    private lateinit var encodedSnapshot: ByteArray

    @Setup
    public fun prepare(): Unit = runBlocking {
        module = Module.decode(BenchmarkFixtures.snapshot64MiBModule)
        sourceStore = Store(SNAPSHOT_CONFIG)
        sourceInstance = Instance(sourceStore, module, ResolvedImports())

        val memory = sourceInstance.memories.single()
        for (page in 0 until 1024) {
            memory.storeByte(page.toLong() * 65_536L, page)
        }

        pause = sourceStore.requestPause()
        invocation = sourceStore.scope.launch {
            sourceInstance.invoke("spin")
        }
        pause.awaitPaused()
        encodedSnapshot = KwasmSnapshot.capture(sourceInstance)
        check(sourceInstance.memories.single().byteSize == CAPTURED_MEMORY_BYTES)
    }

    @Benchmark
    public open fun encode64MiB(): Int =
        KwasmSnapshot.capture(sourceInstance).size

    @Benchmark
    public open fun restore64MiB(): Int {
        val restored = KwasmSnapshot.restore(
            bytes = encodedSnapshot,
            module = module,
            store = Store(SNAPSHOT_CONFIG),
        )
        return restored.memories.single().byteSize
    }

    @TearDown
    public fun cleanup(): Unit = runBlocking {
        pause.resume()
        sourceStore.cancel()
        invocation.cancelAndJoin()
    }

    private companion object {
        const val CAPTURED_MEMORY_BYTES: Int = 64 * 1024 * 1024
        val SNAPSHOT_CONFIG: StoreConfig = StoreConfig(canonicalizeNaNs = true)
    }
}
