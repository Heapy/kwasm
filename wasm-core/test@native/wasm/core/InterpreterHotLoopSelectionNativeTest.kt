package io.heapy.kwasm

import io.heapy.kwasm.Instr.Call
import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InterpreterHotLoopSelectionNativeTest {
    @Test
    fun nativeSelectsHoistedLinearHotLoop() {
        assertTrue(USE_HOISTED_LINEAR_HOT_LOOP)
        assertTrue(USE_FLAT_DIRECT_CALL_METADATA)
        assertFalse(USE_TWO_SLOT_I32_EXPRESSION_PLAN)
    }

    @Test
    fun nativeBuildsDirectCallMetadataForLocalIndexSpace() {
        val importedType = FuncType(emptyList(), emptyList())
        val localType = FuncType(listOf(ValType.I32), listOf(ValType.I32))
        val module = directCallModule(importedType, localType)
        val instance = Instance(
            Store(),
            module,
            ResolvedImports(
                functions = listOf(HostImport(importedType) { emptyList() }),
            ),
        )

        val directCalls = assertNotNull(instance.flatDirectCalls)
        assertEquals(1, directCalls.importedFunctionCount)
        assertSame(module.functions[0], directCalls.functions[0])
        assertSame(module.functions[1], directCalls.functions[1])
        assertEquals(listOf(localType, localType), directCalls.types.toList())
        assertContentEquals(intArrayOf(1, 1), directCalls.parameterCounts)
        assertContentEquals(arrayOf("entry", "add"), directCalls.functionNames)
    }

    @Test
    fun nativeDirectLocalCallPreservesListenerArguments(): Unit = runBlocking {
        val importedType = FuncType(emptyList(), emptyList())
        val localType = FuncType(listOf(ValType.I32), listOf(ValType.I32))
        val calls = mutableListOf<Pair<Int, List<Value>>>()
        val store = Store(
            StoreConfig(
                listener = object : ExecutionListener {
                    override fun onCallStarted(
                        instance: Instance,
                        functionIndex: Int,
                        arguments: List<Value>,
                    ) {
                        calls += functionIndex to arguments
                    }
                },
            ),
        )
        val instance = Instance(
            store,
            directCallModule(importedType, localType),
            ResolvedImports(
                functions = listOf(
                    HostImport(importedType) {
                        error("unused imported function must stay on the fallback path")
                    },
                ),
            ),
        )

        assertEquals(
            listOf(Value.I32(42)),
            instance.invoke("entry", listOf(Value.I32(41))),
        )
        assertEquals(
            listOf(
                1 to listOf<Value>(Value.I32(41)),
                2 to listOf<Value>(Value.I32(41)),
            ),
            calls,
        )
    }

    private fun directCallModule(importedType: FuncType, localType: FuncType): Module =
        ModuleBuilder().apply {
            types += importedType
            types += localType
            imports += Import("host", "unused", ImportDesc.Function(0))
            functions += Function(
                typeIndex = 1,
                locals = emptyList(),
                body = listOf(FcIndex(0x20, 0), Call(2)),
            )
            functions += Function(
                typeIndex = 1,
                locals = listOf(ValType.I64),
                body = listOf(FcIndex(0x20, 0), I32Const(1), Simple(0x6A)),
            )
            exports += Export("entry", ExportDesc.Function(1))
            customSections += CustomSection(
                "name",
                byteArrayOf(
                    0x01,
                    0x0D,
                    0x02,
                    0x01,
                    0x05,
                    0x65,
                    0x6E,
                    0x74,
                    0x72,
                    0x79,
                    0x02,
                    0x03,
                    0x61,
                    0x64,
                    0x64,
                ),
            )
        }.build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
