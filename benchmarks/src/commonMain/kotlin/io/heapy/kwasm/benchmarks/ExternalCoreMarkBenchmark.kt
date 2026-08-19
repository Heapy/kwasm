package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.FuncType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Linker
import io.heapy.kwasm.MemoryInstance
import io.heapy.kwasm.Module
import io.heapy.kwasm.ValType
import io.heapy.kwasm.Value
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking

/**
 * Opt-in seam for the checksum-pinned CoreMark Wasm asset recorded in
 * `benchmarks/upstreams.lock.json`.
 *
 * This benchmark is excluded from the default profile because kwasm does not
 * vendor a third-party binary. The harness disables the fixture's adaptive
 * calibration and runs the same fixed work as the paired Chasm row.
 */
@State(Scope.Benchmark)
public open class ExternalCoreMarkBenchmark {
    private lateinit var instance: Instance
    private lateinit var exportName: String
    private val clock = CoreMarkFixture.Clock()

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
                        listOf(Value.I64(clock.readMilliseconds()))
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
        val memory = checkNotNull(instance.exportedMemory("memory")) {
            "CoreMark module has no memory export 'memory'"
        }
        CoreMarkFixture.configureIterations(
            readInt = memory::readIntLittleEndian,
            writeInt = memory::writeIntLittleEndian,
        )
    }

    @Benchmark
    public open fun coreMarkWasm(): Float = runBlocking {
        val result = instance.invoke(exportName).single() as Value.F32
        CoreMarkFixture.requireValidScore(result.v)
    }
}

private fun MemoryInstance.readIntLittleEndian(address: Int): Int {
    val bytes = load(address.toLong(), Int.SIZE_BYTES)
    return (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
}

private fun MemoryInstance.writeIntLittleEndian(address: Int, value: Int) {
    store(
        address.toLong(),
        byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        ),
    )
}
