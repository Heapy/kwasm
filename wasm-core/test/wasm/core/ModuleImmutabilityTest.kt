package io.heapy.kwasm

import io.heapy.kwasm.Instr.Block
import io.heapy.kwasm.Instr.BrTable
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.RawImmediate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ModuleImmutabilityTest {
    @Test
    fun validatedModuleDeeplyOwnsBodiesTypesAndArrayImmediates(): Unit = runBlocking {
        val parameters = mutableListOf<ValType>()
        val targets = intArrayOf(0)
        val blockBody = mutableListOf<Instr>(I32Const(0), BrTable(targets, 0))
        val functionBody = mutableListOf<Instr>(
            Block(BlockType.Empty, blockBody),
            I32Const(7),
        )
        val builder = ModuleBuilder().apply {
            types += FuncType(parameters, listOf(ValType.I32))
            functions += Function(0, emptyList(), functionBody)
            exports += Export("value", ExportDesc.Function(0))
        }
        val module = builder.build(WASM_HEADER).also(ModuleValidator::validate)

        parameters += ValType.I64
        targets[0] = 99
        blockBody.clear()
        functionBody.clear()
        builder.functions.clear()

        assertEquals(emptyList(), (module.types.single() as FuncType).params)
        assertEquals(listOf(Value.I32(7)), Instance(module).invoke("value"))

        val storedBlock = module.functions.single().body.first() as Block
        val storedTable = storedBlock.body.last() as BrTable
        val exposedTargets = storedTable.targets
        exposedTargets[0] = 77
        assertContentEquals(intArrayOf(0), storedTable.targets)
        assertFails {
            @Suppress("UNCHECKED_CAST")
            (module.functions as MutableList<Function>).clear()
        }
    }

    @Test
    fun rawImmediateArraysAreDefensive() {
        val immediates = longArrayOf(1, 2)
        val bytes = byteArrayOf(3, 4)
        val instruction = RawImmediate(0xFD, immediates, bytes)

        immediates[0] = 9
        bytes[0] = 9
        instruction.immediates[1] = 9
        instruction.bytes[1] = 9

        assertContentEquals(longArrayOf(1, 2), instruction.immediates)
        assertContentEquals(byteArrayOf(3, 4), instruction.bytes)
    }

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
