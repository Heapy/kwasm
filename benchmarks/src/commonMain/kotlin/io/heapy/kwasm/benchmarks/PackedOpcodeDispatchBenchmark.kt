package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/** Measures primitive local-transfer dispatch without arithmetic superinstructions. */
@State(Scope.Benchmark)
public open class PackedOpcodeDispatchBenchmark {
    private lateinit var module: Module
    private lateinit var checkpointEnabled: Instance
    private lateinit var checkpointCompiledOut: Instance
    private val hotArguments: List<Value> = listOf(Value.I32(LOOP_COUNT), Value.I32(SEED))
    private val coldArguments: List<Value> = listOf(Value.I32(COLD_LOOP_COUNT), Value.I32(SEED))

    @Setup
    public fun prepare() {
        module = Module.decode(WatComposer.compose(localTransferModule()))
        checkpointEnabled = instance(module, CheckpointMode.Enabled)
        checkpointCompiledOut = instance(module, CheckpointMode.CompiledOutEquivalent)

        val expected = SEED xor LOOP_COUNT
        check(runBlocking { invoke(checkpointEnabled) } == expected)
        check(runBlocking { invoke(checkpointCompiledOut) } == expected)
    }

    @Benchmark
    public open fun localTransferCheckpointEnabled(): Int = runBlocking {
        invoke(checkpointEnabled)
    }

    @Benchmark
    public open fun localTransferCheckpointCompiledOutEquivalent(): Int = runBlocking {
        invoke(checkpointCompiledOut)
    }

    /** Includes Store/Instance creation and the first per-body hot-code build. */
    @Benchmark
    public open fun firstInvokeCheckpointEnabled(): Int = runBlocking {
        val result = invoke(instance(module, CheckpointMode.Enabled), coldArguments)
        check(result == (SEED xor COLD_LOOP_COUNT))
        result
    }

    private fun instance(module: Module, checkpointMode: CheckpointMode): Instance =
        Instance(
            Store(StoreConfig(checkpointMode = checkpointMode)),
            module,
            ResolvedImports(),
        )

    private suspend fun invoke(
        instance: Instance,
        arguments: List<Value> = hotArguments,
    ): Int {
        val results = instance.invoke(EXPORT, arguments)
        check(results.size == 1) { "$EXPORT returned ${results.size} values" }
        return (results.single() as Value.I32).v
    }

    private fun localTransferModule(): String {
        val transfers = buildString {
            repeat(TRANSFER_GROUPS) {
                appendLine("local.get 1")
                appendLine("local.set 2")
                appendLine("local.get 2")
                appendLine("local.set 1")
            }
        }
        return """
            (module
              (func (export "$EXPORT") (param i32 i32) (result i32)
                (local i32 i32)
                i32.const 0
                local.set 3
                (block
                  (loop
                    local.get 3
                    local.get 0
                    i32.ge_u
                    br_if 1
                    $transfers
                    local.get 3
                    i32.const 1
                    i32.add
                    local.set 3
                    br 0))
                local.get 1
                local.get 3
                i32.xor))
        """.trimIndent()
    }

    private companion object {
        const val EXPORT: String = "local_transfer"
        const val COLD_LOOP_COUNT: Int = 1
        const val LOOP_COUNT: Int = 4_096
        const val SEED: Int = 0x1357_9BDF
        const val TRANSFER_GROUPS: Int = 64
    }
}
