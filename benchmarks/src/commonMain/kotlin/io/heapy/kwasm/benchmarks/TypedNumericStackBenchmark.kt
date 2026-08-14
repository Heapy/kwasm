package io.heapy.kwasm.benchmarks

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

/** Focused coverage for numeric result pushes, loads, and conversions. */
@State(Scope.Benchmark)
public open class TypedNumericStackBenchmark {
    private lateinit var instance: Instance

    @Setup
    public fun prepare() {
        val module = Module.decode(WatComposer.compose(MIXED_NUMERIC_WAT))
        instance = Instance(Store(StoreConfig()), module, ResolvedImports())
        check(runBlocking { invokeChecksum() } == EXPECTED_CHECKSUM) {
            "mixed numeric workload produced an unexpected checksum"
        }
    }

    @Benchmark
    public open fun mixedI64F32F64(): Long = runBlocking {
        invokeChecksum()
    }

    private suspend fun invokeChecksum(): Long {
        val result = instance.invoke(
            exportName = "mixed_numeric",
            arguments = listOf(Value.I32(ROUNDS)),
        )
        check(result.size == 1) { "mixed_numeric returned ${result.size} values" }
        return (result.single() as Value.I64).v
    }

    private companion object {
        const val ROUNDS: Int = 4_096
        const val EXPECTED_CHECKSUM: Long = 2_266_456_111_338_158_489L

        val MIXED_NUMERIC_WAT: String =
            """
            (module
              (memory 1)
              (func (export "mixed_numeric") (param i32) (result i64)
                (local i32 i64 f32 f64)

                i32.const 0
                i64.const 7640891576956012809
                i64.store
                i32.const 8
                f32.const 0.125
                f32.store
                i32.const 16
                f64.const 0.0625
                f64.store

                i32.const 0
                local.set 1
                i64.const -7046029254386353131
                local.set 2
                f32.const 1.25
                local.set 3
                f64.const 0.75
                local.set 4

                (block
                  (loop
                    local.get 1
                    local.get 0
                    i32.ge_u
                    br_if 1

                    local.get 2
                    i32.const 0
                    i64.load
                    i64.add
                    local.get 1
                    i64.extend_i32_u
                    i64.xor
                    i64.const 13
                    i64.rotl
                    local.set 2

                    local.get 3
                    i32.const 8
                    f32.load
                    f32.add
                    local.get 1
                    f32.convert_i32_u
                    f32.const 0x1p-15
                    f32.mul
                    f32.add
                    local.set 3

                    local.get 4
                    i32.const 16
                    f64.load
                    f64.add
                    local.get 2
                    f64.convert_i64_s
                    f64.const 0x1p-60
                    f64.mul
                    f64.add
                    local.set 4

                    local.get 1
                    i32.const 1
                    i32.add
                    local.set 1
                    br 0))

                local.get 2
                local.get 3
                i32.reinterpret_f32
                i64.extend_i32_u
                i64.xor
                local.get 4
                i64.reinterpret_f64
                i64.xor))
            """.trimIndent()
    }
}
