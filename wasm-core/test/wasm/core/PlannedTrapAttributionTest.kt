package io.heapy.kwasm

import io.heapy.kwasm.Instr.F32Const
import io.heapy.kwasm.Instr.Br
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
            expectedPlan = (LINEAR_PLAN_I32_EXPRESSION_OFFSET + 6).toByte(),
        )
        assertExactTrapPcAcrossModes(
            module = plannedDivisionTrapModule(trapInSecondOperation = true),
            expectedKind = TrapKind.INTEGER_DIVIDE_BY_ZERO,
            expectedPc = 4,
            expectedPlan = (LINEAR_PLAN_I32_EXPRESSION_OFFSET + 6).toByte(),
        )
        val memoryModule = plannedNestedLoadTrapModule()
        assertExactTrapPcAcrossModes(
            module = memoryModule,
            expectedKind = TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS,
            expectedPc = 1,
            arguments = listOf(Value.I32(65_536)),
            expectedPlan = LINEAR_PLAN_LOCAL_I32_LOAD_LOAD,
        )
        assertExactTrapPcAcrossModes(
            module = memoryModule,
            expectedKind = TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS,
            expectedPc = 2,
            arguments = listOf(Value.I32(0)),
            initialMemoryWord = 65_536,
            expectedPlan = LINEAR_PLAN_LOCAL_I32_LOAD_LOAD,
        )
        assertExactTrapPcAcrossModes(
            module = trappingConversionModule(),
            expectedKind = TrapKind.INVALID_CONVERSION_TO_INTEGER,
            expectedPc = 1,
        )
    }

    @Test
    fun everyTrappingBinaryPlanPublishesItsExactOperationPc(): Unit = runBlocking {
        for (case in forcedPlannedDivisionCases()) {
            val module = forcedPlannedDivisionModule(case)
            for (mode in CheckpointMode.entries) {
                val store = Store(StoreConfig(checkpointMode = mode))
                val body = module.functions.single().body
                val plan = store.linearHotCode(body).plan
                plan.fill(0)
                plan[case.planPc] = case.plan

                val trap = assertFailsWith<ExecutionTrap>("${case.name} in $mode") {
                    Instance(store, module, ResolvedImports()).invoke("run")
                }
                assertEquals(
                    TrapKind.INTEGER_DIVIDE_BY_ZERO,
                    trap.kind,
                    "${case.name} in $mode",
                )
                assertEquals(
                    case.trapPc,
                    trap.guestStack.single().instructionIndex,
                    "${case.name} in $mode",
                )
            }
        }
    }

    private suspend fun assertExactTrapPcAcrossModes(
        module: Module,
        expectedKind: TrapKind,
        expectedPc: Int,
        arguments: List<Value> = emptyList(),
        initialMemoryWord: Int? = null,
        expectedPlan: Byte? = null,
    ) {
        val instances = CheckpointMode.entries.map { mode ->
            val store = Store(StoreConfig(checkpointMode = mode))
            if (expectedPlan != null) {
                assertEquals(
                    expectedPlan,
                    store.linearHotCode(module.functions.single().body).plan.first(),
                    mode.toString(),
                )
            }
            Instance(
                store,
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

    private fun forcedPlannedDivisionModule(case: ForcedPlannedDivision): Module =
        validatedModule {
            types += FuncType(emptyList(), case.results)
            functions += Function(0, listOf(ValType.I32), case.body)
            exports += Export("run", ExportDesc.Function(0))
        }

    private fun forcedPlannedDivisionCases(): List<ForcedPlannedDivision> = buildList {
        add(
            ForcedPlannedDivision(
                name = "STACK_BINARY_SET",
                plan = LINEAR_PLAN_STACK_BINARY_SET,
                planPc = 2,
                trapPc = 2,
                results = emptyList(),
                body = listOf(
                    I32Const(1),
                    I32Const(0),
                    Simple(0x6D),
                    FcIndex(0x21, 0),
                ),
            ),
        )
        addAll(
            pairedForcedPlans(
                name = "CONST_BINARY",
                plan = LINEAR_PLAN_CONST_BINARY,
                setPlan = LINEAR_PLAN_CONST_BINARY_SET,
                planPc = 1,
                trapPc = 2,
                body = listOf(I32Const(1), I32Const(0), Simple(0x6D)),
            ),
        )
        add(
            ForcedPlannedDivision(
                name = "PRODUCERS_BINARY_SET_BR",
                plan = LINEAR_PLAN_PRODUCERS_BINARY_SET_BR,
                planPc = 0,
                trapPc = 2,
                results = emptyList(),
                body = listOf(
                    FcIndex(0x20, 0),
                    I32Const(0),
                    Simple(0x6D),
                    FcIndex(0x21, 0),
                    Br(0),
                ),
            ),
        )
        addAll(
            pairedForcedPlans(
                name = "LOCAL_BINARY",
                plan = LINEAR_PLAN_LOCAL_BINARY,
                setPlan = LINEAR_PLAN_LOCAL_BINARY_SET,
                planPc = 1,
                trapPc = 2,
                body = listOf(I32Const(1), FcIndex(0x20, 0), Simple(0x6D)),
            ),
        )
        addAll(
            pairedForcedPlans(
                name = "PRODUCERS_BINARY",
                plan = LINEAR_PLAN_PRODUCERS_BINARY,
                setPlan = LINEAR_PLAN_PRODUCERS_BINARY_SET,
                planPc = 0,
                trapPc = 2,
                body = listOf(FcIndex(0x20, 0), I32Const(0), Simple(0x6D)),
            ),
        )
        addAll(
            pairedForcedPlans(
                name = "PRODUCERS_BINARY_BINARY",
                plan = LINEAR_PLAN_PRODUCERS_BINARY_BINARY,
                setPlan = LINEAR_PLAN_PRODUCERS_BINARY_BINARY_SET,
                planPc = 1,
                trapPc = 4,
                body = listOf(
                    I32Const(5),
                    FcIndex(0x20, 0),
                    I32Const(0),
                    Simple(0x6A),
                    Simple(0x6D),
                ),
            ),
        )
        addAll(
            pairedForcedPlans(
                name = "PRODUCERS_BINARY_BINARY_BINARY",
                plan = LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY,
                setPlan = LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY_SET,
                planPc = 2,
                trapPc = 6,
                body = listOf(
                    I32Const(7),
                    I32Const(0),
                    FcIndex(0x20, 0),
                    I32Const(1),
                    Simple(0x6A),
                    Simple(0x6C),
                    Simple(0x6D),
                ),
            ),
        )
    }

    private fun pairedForcedPlans(
        name: String,
        plan: Byte,
        setPlan: Byte,
        planPc: Int,
        trapPc: Int,
        body: List<Instr>,
    ): List<ForcedPlannedDivision> = listOf(
        ForcedPlannedDivision(
            name = name,
            plan = plan,
            planPc = planPc,
            trapPc = trapPc,
            results = listOf(ValType.I32),
            body = body,
        ),
        ForcedPlannedDivision(
            name = "${name}_SET",
            plan = setPlan,
            planPc = planPc,
            trapPc = trapPc,
            results = emptyList(),
            body = body + FcIndex(0x21, 0),
        ),
    )

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

    private data class ForcedPlannedDivision(
        val name: String,
        val plan: Byte,
        val planPc: Int,
        val trapPc: Int,
        val results: List<ValType>,
        val body: List<Instr>,
    )

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
