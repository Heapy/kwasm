package io.heapy.kwasm

import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class I32ExpressionPlanTest {
    @Test
    fun twoSlotPlanMatchesScalarCheckpointState(): Unit = runBlocking {
        val module = expressionModule()
        val body = module.functions[0].body
        assertEquals(
            LINEAR_PLAN_TWO_SLOT_I32_EXPRESSION_SET,
            Store().linearHotCode(body).plan[2],
        )

        for (interval in listOf(1, 6, 7, 17)) {
            assertEquals(
                invokeWithTrace(module, interval, fused = false),
                invokeWithTrace(module, interval, fused = true),
                "checkpoint interval $interval",
            )
        }
        assertEquals(
            42,
            invoke(
                module,
                StoreConfig(checkpointMode = CheckpointMode.CompiledOutEquivalent),
            ),
        )
    }

    @Test
    fun depthThreeExpressionKeepsTheExistingScratchPlan(): Unit = runBlocking {
        val module = expressionModule()
        val body = module.functions[1].body

        assertEquals(
            (LINEAR_PLAN_I32_EXPRESSION_OFFSET + 6).toByte(),
            Store().linearHotCode(body).plan[2],
        )
        for (mode in CheckpointMode.entries) {
            assertEquals(42, invoke(module, StoreConfig(checkpointMode = mode), "depth3"))
        }
    }

    private suspend fun invokeWithTrace(
        module: Module,
        checkpointInterval: Int,
        fused: Boolean,
    ): ExecutionTrace {
        val checkpoints = mutableListOf<Pair<Int?, Int?>>()
        val store = Store(
            StoreConfig(
                checkpointInterval = checkpointInterval,
                listener = object : ExecutionListener {
                    override fun onCheckpoint(
                        store: Store,
                        functionIndex: Int?,
                        instructionIndex: Int?,
                    ) {
                        checkpoints += functionIndex to instructionIndex
                    }
                },
            ),
        )
        if (!fused) store.linearHotCode(module.functions[0].body).plan.fill(0)
        return ExecutionTrace(invoke(module, store = store), checkpoints)
    }

    private suspend fun invoke(
        module: Module,
        config: StoreConfig = StoreConfig(),
        export: String = "depth2",
    ): Int = invoke(module, Store(config), export)

    private suspend fun invoke(
        module: Module,
        store: Store,
        export: String = "depth2",
    ): Int = (
        Instance(store, module, ResolvedImports()).invoke(export).single() as Value.I32
    ).v

    private fun expressionModule(): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(
            typeIndex = 0,
            locals = listOf(ValType.I32),
            body = listOf(
                I32Const(20),
                FcIndex(0x21, 0),
                FcIndex(0x20, 0),
                I32Const(6),
                Simple(0x6B),
                I32Const(3),
                Simple(0x6C),
                FcIndex(0x21, 0),
                FcIndex(0x20, 0),
            ),
        )
        functions += Function(
            typeIndex = 0,
            locals = listOf(ValType.I32),
            body = listOf(
                I32Const(20),
                FcIndex(0x21, 0),
                FcIndex(0x20, 0),
                I32Const(6),
                I32Const(16),
                Simple(0x6A),
                Simple(0x6A),
                FcIndex(0x21, 0),
                FcIndex(0x20, 0),
            ),
        )
        exports += Export("depth2", ExportDesc.Function(0))
        exports += Export("depth3", ExportDesc.Function(1))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private data class ExecutionTrace(
        val result: Int,
        val checkpoints: List<Pair<Int?, Int?>>,
    )

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
