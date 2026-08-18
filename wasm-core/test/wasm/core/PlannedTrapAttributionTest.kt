package io.heapy.kwasm

import io.heapy.kwasm.Instr.F32Const
import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.Load
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlannedTrapAttributionTest {
    @Test
    fun plannedFusionPublishesExactTrapPcAcrossDispatchAndCheckpointModes(): Unit = runBlocking {
        assertExactTrapPcAcrossModes(
            module = plannedDivisionTrapModule(trapInSecondOperation = false),
            expectedKind = TrapKind.INTEGER_DIVIDE_BY_ZERO,
            expectedPc = 2,
        )
        assertExactTrapPcAcrossModes(
            module = plannedDivisionTrapModule(trapInSecondOperation = true),
            expectedKind = TrapKind.INTEGER_DIVIDE_BY_ZERO,
            expectedPc = 4,
        )
        val memoryModule = plannedNestedLoadTrapModule()
        assertExactTrapPcAcrossModes(
            module = memoryModule,
            expectedKind = TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS,
            expectedPc = 1,
            arguments = listOf(Value.I32(65_536)),
        )
        assertExactTrapPcAcrossModes(
            module = memoryModule,
            expectedKind = TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS,
            expectedPc = 2,
            arguments = listOf(Value.I32(0)),
            initialMemoryWord = 65_536,
        )
        assertExactTrapPcAcrossModes(
            module = trappingConversionModule(),
            expectedKind = TrapKind.INVALID_CONVERSION_TO_INTEGER,
            expectedPc = 1,
        )
    }

    private suspend fun assertExactTrapPcAcrossModes(
        module: Module,
        expectedKind: TrapKind,
        expectedPc: Int,
        arguments: List<Value> = emptyList(),
        initialMemoryWord: Int? = null,
    ) {
        val instances = CheckpointMode.entries.map { mode ->
            Instance(
                Store(StoreConfig(checkpointMode = mode)),
                module,
                ResolvedImports(),
            )
        }
        val traps = instances.map { candidate ->
            if (initialMemoryWord != null) {
                val bytes = candidate.memories.single().data()
                bytes[0] = initialMemoryWord.toByte()
                bytes[1] = (initialMemoryWord ushr 8).toByte()
                bytes[2] = (initialMemoryWord ushr 16).toByte()
                bytes[3] = (initialMemoryWord ushr 24).toByte()
            }
            assertFailsWith<ExecutionTrap> { candidate.invoke("run", arguments) }
        }
        val expected = traps.first()
        assertEquals(expectedKind, expected.kind)
        assertEquals(expectedPc, expected.guestStack.single().instructionIndex)
        traps.drop(1).forEach { trap ->
            assertEquals(expected.kind, trap.kind)
            assertEquals(expected.message, trap.message)
            assertEquals(expected.functionIndex, trap.functionIndex)
            assertEquals(expected.functionName, trap.functionName)
            assertEquals(expected.guestStack, trap.guestStack)
        }
    }

    private fun plannedDivisionTrapModule(trapInSecondOperation: Boolean): Module =
        validatedModule {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                listOf(ValType.I32),
                if (trapInSecondOperation) {
                    listOf(
                        I32Const(6),
                        I32Const(2),
                        Simple(0x6D),
                        I32Const(0),
                        Simple(0x6D),
                        FcIndex(0x21, 0),
                    )
                } else {
                    listOf(
                        I32Const(1),
                        I32Const(0),
                        Simple(0x6D),
                        I32Const(1),
                        Simple(0x6A),
                        FcIndex(0x21, 0),
                    )
                },
            )
            exports += Export("run", ExportDesc.Function(0))
        }

    private fun plannedNestedLoadTrapModule(): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
        memories += Memory(MemoryType(Limits(1u, 1u)))
        functions += Function(
            0,
            emptyList(),
            listOf(
                FcIndex(0x20, 0),
                Load(0x28, align = 2, offset = 0uL),
                Load(0x28, align = 2, offset = 0uL),
            ),
        )
        exports += Export("run", ExportDesc.Function(0))
    }

    private fun trappingConversionModule(): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(0, emptyList(), listOf(F32Const(Float.NaN), Simple(0xA8)))
        exports += Export("run", ExportDesc.Function(0))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
