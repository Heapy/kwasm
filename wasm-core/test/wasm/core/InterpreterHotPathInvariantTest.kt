package io.heapy.kwasm

import io.heapy.kwasm.Instr.Block
import io.heapy.kwasm.Instr.BrIf
import io.heapy.kwasm.Instr.Call
import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.I64Const
import io.heapy.kwasm.Instr.Loop
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InterpreterHotPathInvariantTest {
    @Test
    fun signedI32ImmediateRoundTripsThroughPackedExecution(): Unit = runBlocking {
        val body = listOf<Instr>(I32Const(Int.MIN_VALUE))
        val store = Store()
        val module = moduleReturning(body)
        val instance = Instance(store, module, ResolvedImports())
        val storedBody = module.functions.single().body

        val hotCode = store.linearHotCode(storedBody)
        val packedInstructions = assertNotNull(hotCode.packedInstructions)
        assertEquals(Int.MIN_VALUE, (packedInstructions.single() shr 32).toInt())
        assertEquals(listOf(Value.I32(Int.MIN_VALUE)), instance.invoke("value"))
    }

    @Test
    fun hotCodeCacheUsesBodyIdentityAndKeepsArraysAligned() {
        val body = plannedExpressionBody()
        val equalBody = plannedExpressionBody()
        assertEquals(body, equalBody)
        assertTrue(body !== equalBody)

        val store = Store()
        val first = store.linearHotCode(body)
        val cached = store.linearHotCode(body)
        val equalButDistinct = store.linearHotCode(equalBody)
        val firstAfterOtherBody = store.linearHotCode(body)

        assertTrue(first === cached)
        assertTrue(first !== equalButDistinct)
        assertTrue(first === firstAfterOtherBody)
        assertContentEquals(
            assertNotNull(first.packedInstructions),
            assertNotNull(equalButDistinct.packedInstructions),
        )
        assertContentEquals(first.plan, equalButDistinct.plan)
        assertHotCodeIsAligned(body, first)
        assertHotCodeIsAligned(equalBody, equalButDistinct)
    }

    @Test
    fun packedInstructionBudgetRejectsOverflowWithoutOvercommitting() {
        val budget = PackedLinearCodeBudget(maxInstructions = 3, maxBodies = 2)

        assertTrue(budget.tryReserve(2))
        assertEquals(2, budget.usedInstructions)
        assertEquals(1, budget.usedBodies)
        assertFalse(budget.tryReserve(2))
        assertEquals(2, budget.usedInstructions)
        assertTrue(budget.tryReserve(1))
        assertEquals(3, budget.usedInstructions)
        assertEquals(2, budget.usedBodies)
        assertFalse(budget.tryReserve(0))
        assertFalse(budget.tryReserve(1))
    }

    @Test
    fun storeStopsPackingAfterItsInstructionBudgetIsExhausted() {
        val store = Store()
        repeat(MAX_PACKED_LINEAR_INSTRUCTIONS_PER_STORE / MAX_PACKED_LINEAR_BODY_INSTRUCTIONS) {
            val body = List<Instr>(MAX_PACKED_LINEAR_BODY_INSTRUCTIONS) { I32Const(it) }
            assertNotNull(store.linearHotCode(body).packedInstructions)
        }
        val overflowBody = List<Instr>(MAX_PACKED_LINEAR_BODY_INSTRUCTIONS) { I32Const(it) }

        assertNull(store.linearHotCode(overflowBody).packedInstructions)
    }

    @Test
    fun oversizedHotBodyFallsBackToInstructions(): Unit = runBlocking {
        val body = buildList(MAX_PACKED_LINEAR_BODY_INSTRUCTIONS + 1) {
            repeat(MAX_PACKED_LINEAR_BODY_INSTRUCTIONS / 2) {
                add(I32Const(0))
                add(Instr.Drop())
            }
            add(I32Const(7))
        }
        val module = moduleReturning(body)
        val storedBody = module.functions.single().body

        for (mode in CheckpointMode.entries) {
            val store = Store(StoreConfig(checkpointMode = mode))
            assertNull(store.linearHotCode(storedBody).packedInstructions)
            assertEquals(
                listOf(Value.I32(7)),
                Instance(store, module, ResolvedImports()).invoke("value"),
            )
        }
    }

    @Test
    fun sparseBodyExecutesWithoutPackedInstructions(): Unit = runBlocking {
        val module = moduleReturning(
            List<Instr>(80) { index ->
                if (index == 0) I32Const(7) else Instr.Nop
            },
        )
        val body = module.functions.single().body

        for (mode in CheckpointMode.entries) {
            val store = Store(StoreConfig(checkpointMode = mode))
            assertNull(store.linearHotCode(body).packedInstructions)
            assertEquals(
                listOf(Value.I32(7)),
                Instance(store, module, ResolvedImports()).invoke("value"),
            )
        }
    }

    @Test
    fun packingDensityIncludesTheOneEighthBoundary() {
        val atBoundary = List<Instr>(16) { index ->
            if (index < 2) I32Const(index) else Instr.Nop
        }
        val belowBoundary = List<Instr>(17) { index ->
            if (index < 2) I32Const(index) else Instr.Nop
        }

        assertNotNull(Store().linearHotCode(atBoundary).packedInstructions)
        assertNull(Store().linearHotCode(belowBoundary).packedInstructions)
    }

    @Test
    fun plannerFusesTheFixtureComparisonBranchShapesOnly() {
        val fibShape = listOf<Instr>(
            FcIndex(0x20, 0),
            I32Const(2),
            Simple(0x48),
            BrIf(0),
        )
        val loopLimitShape = listOf<Instr>(
            FcIndex(0x20, 0),
            FcIndex(0x20, 1),
            Simple(0x4F),
            BrIf(1),
        )
        val arithmeticShape = listOf<Instr>(
            FcIndex(0x20, 0),
            I32Const(2),
            Simple(0x6A),
            BrIf(0),
        )

        assertEquals(
            LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF,
            Store().linearHotCode(fibShape).plan.first(),
        )
        assertEquals(
            LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF,
            Store().linearHotCode(loopLimitShape).plan.first(),
        )
        assertEquals(
            LINEAR_PLAN_PRODUCERS_BINARY,
            Store().linearHotCode(arithmeticShape).plan.first(),
        )
    }

    @Test
    fun fusedCompareBranchMatchesFallbackResultsAndCheckpointLocations(): Unit = runBlocking {
        for (localLimit in listOf(false, true)) {
            val module = compareBranchModule(localLimit)
            for (mode in CheckpointMode.entries) {
                for (argument in listOf(1, 2)) {
                    val arguments = buildList {
                        add(Value.I32(argument))
                        if (localLimit) add(Value.I32(2))
                    }
                    val fused = invokeCompareBranch(module, mode, arguments, fused = true)
                    val fallback = invokeCompareBranch(module, mode, arguments, fused = false)

                    assertEquals(
                        fallback,
                        fused,
                        "$mode localLimit=$localLimit argument=$argument",
                    )
                    assertEquals(
                        if (argument < 2) Value.I32(42) else Value.I32(100),
                        fused.result,
                    )
                    assertEquals(
                        if (mode == CheckpointMode.Enabled) {
                            listOf(0 to -1, 0 to if (argument < 2) 0 else 4)
                        } else {
                            emptyList()
                        },
                        fused.checkpoints,
                    )
                }
            }
        }
    }

    @Test
    fun takenLoopCompareBranchFallsBackAndPreservesPauseResume(): Unit = runBlocking {
        val module = loopCompareBranchModule()
        val fused = invokeLoopCompareBranchWithPause(module, fused = true)
        val fallback = invokeLoopCompareBranchWithPause(module, fused = false)

        assertEquals(fallback, fused)
        assertEquals(Value.I32(2), fused.result)
        assertEquals(listOf(0 to -1, 0 to -1), fused.checkpoints)
    }

    @Test
    fun takenBlockCompareBranchDoesNotObserveAPendingPause(): Unit = runBlocking {
        val module = importedCompareBranchModule()
        val fused = invokeImportedCompareBranchWithPendingPause(module, fused = true)
        val fallback = invokeImportedCompareBranchWithPendingPause(module, fused = false)

        assertEquals(fallback, fused)
        assertEquals(Value.I32(42), fused.result)
        assertEquals(listOf(1 to -1, 1 to -1), fused.checkpoints)
        assertEquals(StoreStatus.Idle, fused.status)
        assertTrue(fused.pausePending)
    }

    @Test
    fun fusedCompareBranchPreservesTheFallbackPeakStackTrap(): Unit = runBlocking {
        val module = compareBranchModule()

        for (mode in CheckpointMode.entries) {
            val fused = compareBranchTrap(module, mode, fused = true)
            val fallback = compareBranchTrap(module, mode, fused = false)

            assertEquals(TrapKind.STACK_EXHAUSTED, fused)
            assertEquals(fallback, fused, mode.toString())
        }
    }

    @Test
    fun reusedControlReplacesBodyAndItsHotCode() {
        val firstBody = listOf<Instr>(I32Const(1))
        val secondBody = plannedExpressionBody()
        val store = Store()
        val firstControl = store.acquireGuestControl(
            kind = ControlKind.Function,
            body = firstBody,
            pc = 0,
            stackBase = 0,
            parameterCount = 0,
            resultCount = 1,
            labelArity = 1,
        )
        val instance = Instance(store, moduleReturning(firstBody), ResolvedImports())
        val frame = store.acquireGuestFrame(
            instance = instance,
            functionIndex = 0,
            functionName = "value",
            type = FuncType(emptyList(), listOf(ValType.I32)),
            localsBase = 0,
            localCount = 0,
            stackBase = 0,
            root = firstControl,
        )
        store.releaseLastGuestControl(frame)

        val reused = store.acquireGuestControl(
            kind = ControlKind.Block,
            body = secondBody,
            pc = 2,
            stackBase = 3,
            parameterCount = 1,
            resultCount = 1,
            labelArity = 1,
        )

        assertTrue(reused === firstControl)
        assertTrue(reused.body === secondBody)
        assertTrue(reused.linearHotCode === store.linearHotCode(secondBody))
        assertEquals(ControlKind.Block, reused.kind)
        assertEquals(2, reused.pc)
        assertEquals(3, reused.stackBase)
        assertHotCodeIsAligned(secondBody, reused.linearHotCode)
    }

    @Test
    fun typedAndGenericNumericStackPathsPreserveRawValues() {
        val f32NaNBits = 0xFFC12345u.toInt()
        val f64NaNBits = 0xFFF8123456789ABCuL.toLong()
        val negativeZeroF32Bits = Int.MIN_VALUE
        val negativeZeroF64Bits = Long.MIN_VALUE

        val typed = RuntimeValueStack(initialCapacity = 0)
        typed.addLastI32(Int.MIN_VALUE)
        typed.addLastI32(Int.MAX_VALUE)
        typed.addLastI64(Long.MIN_VALUE)
        typed.addLastI64(Long.MAX_VALUE)
        typed.addLastF32(Float.fromBits(f32NaNBits))
        typed.addLastF32(Float.fromBits(negativeZeroF32Bits))
        typed.addLastF64(Double.fromBits(f64NaNBits))
        typed.addLastF64(Double.fromBits(negativeZeroF64Bits))

        assertEquals(
            negativeZeroF64Bits,
            (typed.removeLast() as Value.F64).v.toRawBits(),
        )
        assertEquals(f64NaNBits, (typed.removeLast() as Value.F64).v.toRawBits())
        assertEquals(
            negativeZeroF32Bits,
            (typed.removeLast() as Value.F32).v.toRawBits(),
        )
        assertEquals(f32NaNBits, (typed.removeLast() as Value.F32).v.toRawBits())
        assertEquals(Value.I64(Long.MAX_VALUE), typed.removeLast())
        assertEquals(Value.I64(Long.MIN_VALUE), typed.removeLast())
        assertEquals(Value.I32(Int.MAX_VALUE), typed.removeLast())
        assertEquals(Value.I32(Int.MIN_VALUE), typed.removeLast())

        val generic = RuntimeValueStack(initialCapacity = 0)
        generic.addLast(Value.I32(Int.MIN_VALUE))
        generic.addLast(Value.I32(Int.MAX_VALUE))
        generic.addLast(Value.I64(Long.MIN_VALUE))
        generic.addLast(Value.I64(Long.MAX_VALUE))
        generic.addLast(Value.F32(Float.fromBits(f32NaNBits)))
        generic.addLast(Value.F32(Float.fromBits(negativeZeroF32Bits)))
        generic.addLast(Value.F64(Double.fromBits(f64NaNBits)))
        generic.addLast(Value.F64(Double.fromBits(negativeZeroF64Bits)))

        assertEquals(negativeZeroF64Bits, generic.removeLastF64().toRawBits())
        assertEquals(f64NaNBits, generic.removeLastF64().toRawBits())
        assertEquals(negativeZeroF32Bits, generic.removeLastF32().toRawBits())
        assertEquals(f32NaNBits, generic.removeLastF32().toRawBits())
        assertEquals(Long.MAX_VALUE, generic.removeLastI64())
        assertEquals(Long.MIN_VALUE, generic.removeLastI64())
        assertEquals(Int.MAX_VALUE, generic.removeLastI32())
        assertEquals(Int.MIN_VALUE, generic.removeLastI32())
    }

    @Test
    fun localProgramCounterTracksLoopBackedges(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = listOf(ValType.I32),
                body = listOf(
                    I32Const(3),
                    FcIndex(0x21, 0),
                    Loop(
                        BlockType.Empty,
                        listOf(
                            FcIndex(0x20, 0),
                            I32Const(1),
                            Simple(0x6B),
                            FcIndex(0x22, 0),
                            BrIf(0),
                        ),
                    ),
                    FcIndex(0x20, 0),
                ),
            )
            exports += Export("countdown", ExportDesc.Function(0))
        }

        for (mode in CheckpointMode.entries) {
            val instance = Instance(
                Store(StoreConfig(checkpointMode = mode)),
                module,
                ResolvedImports(),
            )
            assertEquals(listOf(Value.I32(0)), instance.invoke("countdown"))
        }
    }

    @Test
    fun trapAfterLocallyAdvancedProgramCounterReportsCurrentInstruction(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I64))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(I64Const(12), I64Const(0), Simple(0x7F)),
            )
            exports += Export("divide", ExportDesc.Function(0))
        }
        val trap = assertFailsWith<ExecutionTrap> {
            Instance(module).invoke("divide")
        }

        assertEquals(TrapKind.INTEGER_DIVIDE_BY_ZERO, trap.kind)
        assertEquals(2, trap.guestStack.single().instructionIndex)
    }

    @Test
    fun pausedCheckpointPublishesProgramCounterAndCountdownForSnapshotResume(): Unit = runBlocking {
        val module = moduleReturning(
            listOf(
                I32Const(1),
                I32Const(2),
                Simple(0x6A),
                I32Const(4),
                Simple(0x6A),
            ),
        )
        lateinit var pause: PauseHandle
        var pauseRequested = false
        val store = Store(
            StoreConfig(
                checkpointInterval = 2,
                listener = object : ExecutionListener {
                    override fun onCheckpoint(
                        store: Store,
                        functionIndex: Int?,
                        instructionIndex: Int?,
                    ) {
                        if (!pauseRequested && instructionIndex == 0) {
                            pauseRequested = true
                            pause = store.requestPause()
                        }
                    }
                },
            ),
        )
        val instance = Instance(store, module, ResolvedImports())
        val invocation = async { instance.invoke("value") }

        withTimeout(5_000) { store.awaitSnapshotCapturable() }
        val snapshot = store.captureSnapshotState(instance)
        assertEquals(1, snapshot.frames.single().controls.single().pc)
        assertEquals(0, snapshot.instructionsUntilCheckpoint)
        assertEquals(listOf(Value.I32(1)), snapshot.valueStack())

        pause.resume()
        assertEquals(listOf(Value.I32(7)), invocation.await())

        val restoredStore = Store(StoreConfig(checkpointInterval = 2))
        val restored = Instance(restoredStore, module, ResolvedImports())
        restoredStore.restoreSnapshotState(restored, snapshot)
        assertEquals(listOf(Value.I32(7)), restored.resume())
    }

    @Test
    fun importedCallAfterLinearInstructionsRunsExactlyOnce(): Unit = runBlocking {
        val importType = FuncType(listOf(ValType.I32), listOf(ValType.I32))
        val module = validatedModule {
            types += importType
            types += FuncType(emptyList(), listOf(ValType.I32))
            imports += Import("host", "increment", ImportDesc.Function(0))
            functions += Function(
                typeIndex = 1,
                locals = emptyList(),
                body = listOf(
                    I32Const(40),
                    I32Const(1),
                    Simple(0x6A),
                    Call(0),
                ),
            )
            exports += Export("value", ExportDesc.Function(1))
        }
        var calls = 0
        val instance = Instance(
            Store(),
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(importType) { arguments ->
                        calls++
                        listOf(Value.I32(arguments.single().asI32() + 1))
                    },
                ),
            ),
        )

        assertEquals(listOf(Value.I32(42)), instance.invoke("value"))
        assertEquals(1, calls)
    }

    @Test
    fun genericSnapshotAndCopyBoundariesKeepRuntimeTags() {
        val reference = Value.Ref.Host(Any())
        val source = RuntimeValueStack(initialCapacity = 0)
        source.addLastI32(-17)
        source.addLastI64(Long.MAX_VALUE)
        source.addLast(reference)

        val snapshotBoundary = source.toList()
        assertEquals(Value.I32(-17), snapshotBoundary[0])
        assertEquals(Value.I64(Long.MAX_VALUE), snapshotBoundary[1])
        assertSame(reference, snapshotBoundary[2])

        val copied = RuntimeValueStack(initialCapacity = 0)
        source.copyTo(0, copied)
        source.copyTo(1, copied)
        source.copyTo(2, copied)

        assertEquals(Value.I32(-17), copied[0])
        assertEquals(Value.I64(Long.MAX_VALUE), copied[1])
        assertSame(reference, copied[2])
    }

    private fun assertHotCodeIsAligned(body: List<Instr>, hotCode: LinearHotCode) {
        val packedInstructions = assertNotNull(hotCode.packedInstructions)
        assertEquals(body.size, packedInstructions.size)
        assertEquals(body.size, hotCode.plan.size)
        body.indices.forEach { index ->
            assertEquals(body[index].opcode, packedInstructions[index].toInt())
        }
        assertEquals(
            LINEAR_PLAN_I32_EXPRESSION_OFFSET + body.size,
            hotCode.plan.first().toInt(),
        )
    }

    private fun plannedExpressionBody(): List<Instr> = listOf(
        I32Const(Int.MIN_VALUE),
        I32Const(-1),
        Simple(0x6A),
        I32Const(Int.MAX_VALUE),
        Simple(0x73),
        FcIndex(0x21, 0),
    )

    private suspend fun invokeCompareBranch(
        module: Module,
        mode: CheckpointMode,
        arguments: List<Value>,
        fused: Boolean,
    ): CompareBranchTrace {
        val checkpoints = mutableListOf<Pair<Int?, Int?>>()
        val store = Store(
            StoreConfig(
                checkpointInterval = 7,
                checkpointMode = mode,
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
        selectCompareBranchPlan(store, module, fused)
        val result = Instance(store, module, ResolvedImports())
            .invoke("select", arguments)
            .single()
        return CompareBranchTrace(result, checkpoints)
    }

    private suspend fun compareBranchTrap(
        module: Module,
        mode: CheckpointMode,
        fused: Boolean,
    ): TrapKind {
        val store = Store(
            StoreConfig(
                limits = ExecutionLimits(maxValueStackSlots = 2),
                checkpointMode = mode,
            ),
        )
        selectCompareBranchPlan(store, module, fused)
        return assertFailsWith<ExecutionTrap> {
            Instance(store, module, ResolvedImports())
                .invoke("select", listOf(Value.I32(1)))
        }.kind
    }

    private suspend fun invokeLoopCompareBranchWithPause(
        module: Module,
        fused: Boolean,
    ): CompareBranchTrace = coroutineScope {
        val checkpoints = mutableListOf<Pair<Int?, Int?>>()
        val pauseRequested = CompletableDeferred<PauseHandle>()
        val store = Store(
            StoreConfig(
                checkpointInterval = Int.MAX_VALUE,
                listener = object : ExecutionListener {
                    override fun onCheckpoint(
                        store: Store,
                        functionIndex: Int?,
                        instructionIndex: Int?,
                    ) {
                        checkpoints += functionIndex to instructionIndex
                        if (checkpoints.size == 2) {
                            pauseRequested.complete(store.requestPause())
                        }
                    }
                },
            ),
        )
        val functionBody = module.functions.single().body
        val loopBody = (functionBody[2] as Loop).body
        val plan = store.linearHotCode(loopBody).plan
        assertEquals(LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF, plan[4])
        if (!fused) plan[4] = LINEAR_PLAN_PRODUCERS_BINARY

        val invocation = async {
            Instance(store, module, ResolvedImports()).invoke("count").single()
        }
        val pause = pauseRequested.await()
        pause.awaitPaused()
        assertEquals(StoreStatus.Paused, store.status.value)
        pause.resume()
        CompareBranchTrace(invocation.await(), checkpoints)
    }

    private suspend fun invokeImportedCompareBranchWithPendingPause(
        module: Module,
        fused: Boolean,
    ): PendingPauseCompareBranchTrace = coroutineScope {
        val gate = CompletableDeferred<Unit>()
        val checkpoints = mutableListOf<Pair<Int?, Int?>>()
        val store = Store(
            StoreConfig(
                checkpointInterval = Int.MAX_VALUE,
                checkpointMode = CheckpointMode.Enabled,
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
        val compareBranchBody = (module.functions.single().body[1] as Block).body
        val plan = store.linearHotCode(compareBranchBody).plan
        assertEquals(LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF, plan[1])
        if (!fused) plan[1] = LINEAR_PLAN_PRODUCERS_BINARY
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        gate.await()
                        emptyList()
                    },
                ),
            ),
        )
        val invocation = async {
            instance.invoke("select", listOf(Value.I32(1))).single()
        }
        store.status.first { it == StoreStatus.InHostImport }
        val pause = store.requestPause()
        gate.complete(Unit)
        try {
            val result = withTimeout(5_000) { invocation.await() }
            PendingPauseCompareBranchTrace(
                result = result,
                checkpoints = checkpoints.toList(),
                status = store.status.value,
                pausePending = store.controller.hasPauseRequest(),
            )
        } finally {
            pause.resume()
        }
    }

    private fun selectCompareBranchPlan(
        store: Store,
        module: Module,
        fused: Boolean,
    ) {
        val functionBody = module.functions.single().body
        val compareBranchBody = (functionBody.first() as Block).body
        val plan = store.linearHotCode(compareBranchBody).plan
        assertEquals(LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF, plan[1])
        if (!fused) plan[1] = LINEAR_PLAN_PRODUCERS_BINARY
    }

    private fun compareBranchModule(localLimit: Boolean = false): Module = validatedModule {
        val parameters =
            if (localLimit) listOf(ValType.I32, ValType.I32) else listOf(ValType.I32)
        val comparisonRight: Instr =
            if (localLimit) FcIndex(0x20, 1) else I32Const(2)
        types += FuncType(parameters, listOf(ValType.I32))
        functions += Function(
            0,
            emptyList(),
            listOf(
                Block(
                    BlockType.Single(ValType.I32),
                    listOf(
                        I32Const(41),
                        FcIndex(0x20, 0),
                        comparisonRight,
                        Simple(0x48),
                        BrIf(0),
                        Instr.Drop(),
                        I32Const(99),
                    ),
                ),
                I32Const(1),
                Simple(0x6A),
            ),
        )
        exports += Export("select", ExportDesc.Function(0))
    }

    private fun loopCompareBranchModule(): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(
            0,
            listOf(ValType.I32),
            listOf(
                I32Const(0),
                FcIndex(0x21, 0),
                Loop(
                    BlockType.Empty,
                    listOf(
                        FcIndex(0x20, 0),
                        I32Const(1),
                        Simple(0x6A),
                        FcIndex(0x21, 0),
                        FcIndex(0x20, 0),
                        I32Const(2),
                        Simple(0x48),
                        BrIf(0),
                    ),
                ),
                FcIndex(0x20, 0),
            ),
        )
        exports += Export("count", ExportDesc.Function(0))
    }

    private fun importedCompareBranchModule(): Module = validatedModule {
        types += FuncType(emptyList(), emptyList())
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
        imports += Import("host", "gate", ImportDesc.Function(0))
        functions += Function(
            1,
            emptyList(),
            listOf(
                Call(0),
                Block(
                    BlockType.Single(ValType.I32),
                    listOf(
                        I32Const(41),
                        FcIndex(0x20, 0),
                        I32Const(2),
                        Simple(0x48),
                        BrIf(0),
                        Instr.Drop(),
                        I32Const(99),
                    ),
                ),
                I32Const(1),
                Simple(0x6A),
            ),
        )
        exports += Export("select", ExportDesc.Function(1))
    }

    private fun moduleReturning(body: List<Instr>): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(0, emptyList(), body)
        exports += Export("value", ExportDesc.Function(0))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }

    private data class CompareBranchTrace(
        val result: Value,
        val checkpoints: List<Pair<Int?, Int?>>,
    )

    private data class PendingPauseCompareBranchTrace(
        val result: Value,
        val checkpoints: List<Pair<Int?, Int?>>,
        val status: StoreStatus,
        val pausePending: Boolean,
    )
}
