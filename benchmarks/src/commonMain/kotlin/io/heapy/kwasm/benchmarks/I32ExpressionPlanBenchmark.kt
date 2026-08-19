package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

@State(Scope.Benchmark)
public open class I32ExpressionPlanBenchmark {
    private lateinit var instance: Instance

    @Setup
    public fun prepare() {
        instance = Instance(Store(), Module.decode(moduleBytes), ResolvedImports())
        runBlocking {
            check(invokeI32("depth2") == EXPECTED_RESULT)
            check(invokeI32("depth3") == EXPECTED_RESULT)
        }
    }

    @Benchmark
    public open fun depth2(): Int = runBlocking {
        invokeI32("depth2")
    }

    @Benchmark
    public open fun depth3(): Int = runBlocking {
        invokeI32("depth3")
    }

    private suspend fun invokeI32(export: String): Int {
        val results = instance.invoke(export, emptyList())
        check(results.size == 1) { "$export returned ${results.size} values" }
        return (results.single() as Value.I32).v
    }

    private companion object {
        const val EXPECTED_RESULT: Int = -1_822_811_169

        val moduleBytes: ByteArray by lazy {
            WatComposer.compose(
                """
                (module
                  (func (export "depth2") (result i32)
                    (local i32 i32)
                    i32.const 0
                    local.set 0
                    i32.const 324508639
                    local.set 1
                    (block
                      (loop
                        local.get 0
                        i32.const 65536
                        i32.ge_u
                        br_if 1
                        local.get 1
                        local.get 0
                        i32.add
                        i32.const 3
                        i32.add
                        local.set 1
                        local.get 0
                        i32.const 1
                        i32.add
                        local.set 0
                        br 0))
                    local.get 1)

                  (func (export "depth3") (result i32)
                    (local i32 i32)
                    i32.const 0
                    local.set 0
                    i32.const 324508639
                    local.set 1
                    (block
                      (loop
                        local.get 0
                        i32.const 65536
                        i32.ge_u
                        br_if 1
                        local.get 1
                        local.get 0
                        i32.const 3
                        i32.add
                        i32.add
                        local.set 1
                        local.get 0
                        i32.const 1
                        i32.add
                        local.set 0
                        br 0))
                    local.get 1))
                """.trimIndent(),
            )
        }
    }
}
