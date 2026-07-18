package io.heapy.kwasm

import io.heapy.kwasm.Instr.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionTest {
    @Test
    fun guestRecursionUsesConfiguredHeapFrames(): Unit = runBlocking {
        val instance = Instance(recursiveCountdownModule(tail = false))

        assertEquals(
            listOf(Value.I32(0)),
            withTimeout(30_000) {
                instance.invoke("countdown", listOf(Value.I32(10_000)))
            },
        )
    }

    @Test
    fun linkedGuestImportAliasesResolveWithoutUsingTheHostStack(): Unit = runBlocking {
        val type = FuncType(emptyList(), listOf(ValType.I32))
        val baseModule = validatedModule {
            types += type
            functions += Function(0, emptyList(), listOf(I32Const(42)))
            exports += Export("value", ExportDesc.Function(0))
        }
        val aliasModule = validatedModule {
            types += type
            imports += Import("chain", "value", ImportDesc.Function(0))
            exports += Export("value", ExportDesc.Function(0))
        }
        val store = Store()
        var target = Instance(store, baseModule, ResolvedImports())
        repeat(5_000) {
            val address = GuestFunctionAddress(target, 0)
            target = Instance(
                store,
                aliasModule,
                ResolvedImports(
                    functions = listOf(
                        HostImport(
                            type = type,
                            guestAddress = address,
                            fn = HostFunction { error("guest alias fallback must not run") },
                        ),
                    ),
                ),
            )
        }

        assertEquals(listOf(Value.I32(42)), target.invoke("value"))
    }

    @Test
    fun linkedFunctionsCompareRecursiveTypesAcrossModuleIndexSpaces(): Unit = runBlocking {
        val nullableStructAt0 = RefType(HeapType.Index(0), nullable = true)
        val importer = validatedModule {
            types += StructType(emptyList())
            types += FuncType(listOf(nullableStructAt0), listOf(ValType.I32))
            imports += Import("typed", "value", ImportDesc.Function(1))
            exports += Export("value", ExportDesc.Function(0))
        }
        val target = validatedModule {
            // Shift the equivalent struct/function shapes to different indices.
            types += FuncType(emptyList(), emptyList())
            types += StructType(emptyList())
            types += FuncType(
                listOf(RefType(HeapType.Index(1), nullable = true)),
                listOf(ValType.I32),
            )
            functions += Function(2, emptyList(), listOf(I32Const(42)))
            exports += Export("value", ExportDesc.Function(0))
        }
        val store = Store()
        val targetInstance = Instance(store, target, ResolvedImports())
        val importedInstance = Linker()
            .defineInstance("typed", targetInstance)
            .instantiate(importer, store)

        assertEquals(
            listOf(Value.I32(42)),
            importedInstance.invoke("value", listOf(Value.NULL_GC)),
        )
        assertEquals(
            listOf(Value.I32(42)),
            importedInstance.invoke(
                "value",
                listOf(
                    Value.Ref.Gc(
                        StructObject(importedInstance, typeIndex = 0, fields = mutableListOf()),
                    ),
                ),
            ),
        )
    }

    @Test
    fun frameLimitProducesCallStackExhaustionTrap(): Unit = runBlocking {
        val store = Store(StoreConfig(limits = ExecutionLimits(maxFrames = 16)))
        val instance = Instance(store, recursiveCountdownModule(tail = false), ResolvedImports())

        val trap = assertFailsWith<ExecutionTrap> {
            instance.invoke("countdown", listOf(Value.I32(100)))
        }
        assertEquals(TrapKind.CALL_STACK_EXHAUSTED, trap.kind)
        assertTrue(trap.guestStack.isNotEmpty())
    }

    @Test
    fun tailCallsReplaceTheCurrentFrame(): Unit = runBlocking {
        val store = Store(StoreConfig(limits = ExecutionLimits(maxFrames = 1)))
        val instance = Instance(store, recursiveCountdownModule(tail = true), ResolvedImports())

        assertEquals(
            listOf(Value.I32(0)),
            instance.invoke("countdown", listOf(Value.I32(10_000))),
        )
    }

    @Test
    fun suspendHostImportParksWithoutBlockingAndResumes(): Unit = runBlocking {
        val release = CompletableDeferred<Int>()
        val module = importedFunctionModule(FuncType(emptyList(), listOf(ValType.I32)))
        val store = Store()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), listOf(ValType.I32))) {
                        listOf(Value.I32(release.await()))
                    },
                ),
            ),
        )

        val invocation = async { instance.invoke("host") }
        store.status.first { it == StoreStatus.InHostImport }
        assertFalse(invocation.isCompleted)

        release.complete(42)
        assertEquals(listOf(Value.I32(42)), invocation.await())
        assertEquals(StoreStatus.Idle, store.status.value)
    }

    @Test
    fun snapshotCaptureRejectsAHostImportThatHasNotActuallySuspended(): Unit = runBlocking {
        val type = FuncType(emptyList(), emptyList())
        val module = importedFunctionModule(type)
        val store = Store()
        lateinit var instance: Instance
        var captureFailure: SnapshotStateException? = null
        instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(type) {
                        captureFailure = assertFailsWith<SnapshotStateException> {
                            store.captureSnapshotState(instance)
                        }
                        emptyList()
                    },
                ),
            ),
        )

        assertEquals(emptyList(), instance.invoke("host"))
        assertTrue(
            captureFailure?.message.orEmpty().contains("has not parked"),
            captureFailure?.message,
        )
        assertEquals(StoreStatus.Idle, store.status.value)
    }

    @Test
    fun cancellationPoisonsStoreAndFutureCallsFailFast(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(Loop(BlockType.Empty, listOf(Br(0)))),
            )
            exports += Export("spin", ExportDesc.Function(0))
        }
        val store = Store(StoreConfig(checkpointInterval = 1))
        val instance = Instance(store, module, ResolvedImports())

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(50) { instance.invoke("spin") }
        }
        assertTrue(store.poisoned)
        assertEquals(StoreStatus.Poisoned, store.status.value)
        assertFailsWith<PoisonedStoreException> { instance.invoke("spin") }
    }

    @Test
    fun fuelCanTrapOrSuspendUntilTheWholeInstructionCostIsAvailable(): Unit = runBlocking {
        val module = constantModule()
        val trappingStore = Store(
            StoreConfig(
                fuelEnabled = true,
                initialFuel = 0,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Trap,
            ),
        )
        val trappingInstance = Instance(trappingStore, module, ResolvedImports())
        assertEquals(
            TrapKind.OUT_OF_FUEL,
            assertFailsWith<OutOfFuel> { trappingInstance.invoke("value") }.kind,
        )

        val suspendingStore = Store(
            StoreConfig(
                fuelEnabled = true,
                initialFuel = 0,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
                instructionCosts = InstructionCostTable { 2L },
            ),
        )
        val suspendingInstance = Instance(suspendingStore, module, ResolvedImports())
        val invocation = async { suspendingInstance.invoke("value") }
        suspendingStore.status.first { it == StoreStatus.WaitingForFuel }

        suspendingStore.addFuel(1)
        assertEquals(null, withTimeoutOrNull(25) { invocation.await() })
        suspendingStore.addFuel(1)
        assertEquals(listOf(Value.I32(7)), invocation.await())
        assertEquals(0, suspendingStore.fuel)
    }

    @Test
    fun disabledFuelSelectsTheUnmeteredLoopOnce(): Unit = runBlocking {
        var costLookups = 0
        val store = Store(
            StoreConfig(
                fuelEnabled = false,
                instructionCosts = InstructionCostTable {
                    costLookups++
                    100L
                },
            ),
        )

        assertEquals(
            listOf(Value.I32(7)),
            Instance(store, constantModule(), ResolvedImports()).invoke("value"),
        )
        assertEquals(0, costLookups)
        assertEquals(0, store.fuel)
    }

    @Test
    fun compiledOutEquivalentUsesTheCheckpointFreeControlLoop(): Unit = runBlocking {
        var checkpoints = 0
        val store = Store(
            StoreConfig(
                checkpointInterval = 1,
                checkpointMode = CheckpointMode.CompiledOutEquivalent,
                listener = object : ExecutionListener {
                    override fun onCheckpoint(
                        store: Store,
                        functionIndex: Int?,
                        instructionIndex: Int?,
                    ) {
                        checkpoints += 1
                    }
                },
            ),
        )

        assertEquals(
            listOf(Value.I32(7)),
            Instance(store, constantModule(), ResolvedImports()).invoke("value"),
        )
        assertEquals(0, checkpoints)
        assertEquals(StoreStatus.Idle, store.status.value)
    }

    @Test
    fun compiledOutEquivalentCannotBeCombinedWithFuel() {
        assertFailsWith<IllegalArgumentException> {
            StoreConfig(
                checkpointMode = CheckpointMode.CompiledOutEquivalent,
                fuelEnabled = true,
            )
        }
    }

    @Test
    fun explicitPauseIsObservedBeforeTheFirstGuestInstruction(): Unit = runBlocking {
        val store = Store()
        val instance = Instance(store, constantModule(), ResolvedImports())
        val pause = store.requestPause()
        val invocation = async { instance.invoke("value") }

        pause.awaitPaused()
        assertEquals(StoreStatus.Paused, store.status.value)
        assertFalse(invocation.isCompleted)
        pause.resume()

        assertEquals(listOf(Value.I32(7)), invocation.await())
    }

    @Test
    fun pauseFastFlagRemainsSetUntilTheNewestGenerationResumes() {
        val store = Store()
        val first = store.requestPause()
        val second = store.requestPause()

        first.resume()
        assertTrue(store.controller.hasPauseRequest())

        second.resume()
        assertFalse(store.controller.hasPauseRequest())
    }

    @Test
    fun hostExceptionsRemainHostExceptionsAndDoNotPoisonTheStore(): Unit = runBlocking {
        val module = importedFunctionModule(FuncType(emptyList(), emptyList()))
        val store = Store()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(FuncType(emptyList(), emptyList())) {
                        throw IllegalStateException("host failure")
                    },
                ),
            ),
        )

        assertEquals(
            "host failure",
            assertFailsWith<IllegalStateException> { instance.invoke("host") }.message,
        )
        assertFalse(store.poisoned)
        assertEquals(StoreStatus.Idle, store.status.value)
    }

    @Test
    fun deterministicModeCanonicalizesNaNResults(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.F32))
            functions += Function(0, emptyList(), listOf(F32Const(Float.fromBits(0x7FA12345))))
            exports += Export("nan", ExportDesc.Function(0))
        }
        val store = Store(StoreConfig(canonicalizeNaNs = true))
        val result = Instance(store, module, ResolvedImports()).invoke("nan").single()

        assertIs<Value.F32>(result)
        assertEquals(0x7FC00000, result.v.toRawBits())
    }

    @Test
    fun deterministicModeCanonicalizesNaNConstantExpressions() {
        val module = validatedModule {
            globals += Global(
                GlobalType(ValType.F32, Mutability.CONST),
                listOf(F32Const(Float.fromBits(0x7FA12345))),
            )
        }
        val store = Store(StoreConfig(canonicalizeNaNs = true))

        val result = Instance(store, module, ResolvedImports()).globals.single().value

        assertIs<Value.F32>(result)
        assertEquals(0x7FC00000, result.v.toRawBits())
    }

    @Test
    fun floatingAbsClearsOnlyTheSignBitOfNanPayloads(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(listOf(ValType.F32), listOf(ValType.F32))
            types += FuncType(listOf(ValType.F64), listOf(ValType.F64))
            functions += Function(0, emptyList(), listOf(FcIndex(0x20, 0), Simple(0x8B)))
            functions += Function(1, emptyList(), listOf(FcIndex(0x20, 0), Simple(0x99)))
            exports += Export("abs32", ExportDesc.Function(0))
            exports += Export("abs64", ExportDesc.Function(1))
        }
        val instance = Instance(module)
        val f32 = assertIs<Value.F32>(
            instance.invoke("abs32", listOf(Value.F32(Float.fromBits(0xFFC0F1E2u.toInt())))).single(),
        )
        val f64 = assertIs<Value.F64>(
            instance.invoke(
                "abs64",
                listOf(Value.F64(Double.fromBits(0xFFF80000F1E27A6BuL.toLong()))),
            ).single(),
        )

        assertEquals(0x7FC0F1E2, f32.v.toRawBits())
        assertEquals(0x7FF80000F1E27A6BL, f64.v.toRawBits())
    }

    @Test
    fun memory64BulkOperationsUseWideAddressesAndLengths(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32))
            memories += Memory(
                MemoryType(
                    Limits(1uL, 1uL, indexType = IndexType.I64),
                ),
            )
            dataSegments += DataSegment(DataMode.Passive, byteArrayOf(1, 2, 3))
            dataCount = 1
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I64Const(10),
                    I32Const(0),
                    I32Const(3),
                    MemoryInit(0, 0),
                    I64Const(20),
                    I32Const(0x7F),
                    I64Const(3),
                    MemoryFill(0, 0),
                    I64Const(30),
                    I64Const(10),
                    I64Const(3),
                    MemoryCopy(0, 0),
                    I64Const(30),
                    Load(0x28, align = 0, offset = 0uL, memoryIndex = 0),
                ),
            )
            exports += Export("bulk64", ExportDesc.Function(0))
        }

        assertEquals(
            listOf(Value.I32(0x0003_0201)),
            Instance(module).invoke("bulk64"),
        )
    }

    @Test
    fun table64OperationsAndIndirectCallsUseWideIndices(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32))
            types += FuncType(emptyList(), listOf(ValType.I64))
            tables += Table(
                TableType(
                    FuncRefType,
                    Limits(1uL, 4uL, indexType = IndexType.I64),
                ),
            )
            functions += Function(0, emptyList(), listOf(I32Const(42)))
            functions += Function(
                0,
                emptyList(),
                listOf(I64Const(0), CallIndirect(typeIndex = 0, tableIndex = 0)),
            )
            functions += Function(
                1,
                emptyList(),
                listOf(
                    I64Const(0),
                    RefNull(HeapType.Func),
                    I64Const(1),
                    FcIndex(0x11FC, 0),
                    I64Const(0),
                    I64Const(0),
                    I64Const(1),
                    FcIndex(0x0EFC, 0, 0),
                    RefNull(HeapType.Func),
                    I64Const(2),
                    FcIndex(0x0FFC, 0),
                    Drop(),
                    FcIndex(0x10FC, 0),
                ),
            )
            exports += Export("target", ExportDesc.Function(0))
            exports += Export("call64", ExportDesc.Function(1))
            exports += Export("table64", ExportDesc.Function(2))
        }
        val instance = Instance(module)
        instance.tables.single().set(0, Value.Ref.Func(0, instance))

        assertEquals(listOf(Value.I32(42)), instance.invoke("call64"))
        assertEquals(listOf(Value.I64(3)), instance.invoke("table64"))
    }

    @Test
    fun activeAndDeclarativeSegmentsAreDroppedAtInstantiation(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32))
            memories += Memory(MemoryType(Limits(1u, 1u)))
            tables += Table(TableType(FuncRefType, Limits(1u, 1u)))
            functions += Function(0, emptyList(), listOf(I32Const(42)))
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I32Const(8),
                    I32Const(0),
                    I32Const(1),
                    MemoryInit(0, 0),
                    I32Const(1),
                ),
            )
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I32Const(0),
                    I32Const(0),
                    I32Const(1),
                    FcIndex(0x0CFC, 0, 0),
                    I32Const(1),
                ),
            )
            functions += Function(
                0,
                emptyList(),
                listOf(
                    I32Const(0),
                    I32Const(0),
                    I32Const(1),
                    FcIndex(0x0CFC, 1, 0),
                    I32Const(1),
                ),
            )
            dataSegments += DataSegment(
                DataMode.Active(0, listOf(I32Const(0))),
                byteArrayOf(7),
            )
            dataCount = 1
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = listOf(I32Const(0)),
                mode = ElementMode.Active(0, listOf(I32Const(0))),
                exprs = listOf(listOf(RefFunc(0))),
                activeType = FuncRefType,
            )
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = null,
                mode = ElementMode.Declarative(FuncRefType),
                exprs = listOf(listOf(RefFunc(0))),
            )
            exports += Export("activeData", ExportDesc.Function(1))
            exports += Export("activeElement", ExportDesc.Function(2))
            exports += Export("declarativeElement", ExportDesc.Function(3))
        }
        val instance = Instance(module)

        assertEquals(7, instance.memories.single().loadByte(0))
        assertIs<Value.Ref.Func>(instance.tables.single().get(0))
        assertEquals(
            TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS,
            assertFailsWith<WasmTrap> { instance.invoke("activeData") }.kind,
        )
        assertEquals(
            TrapKind.OUT_OF_BOUNDS_TABLE_ACCESS,
            assertFailsWith<WasmTrap> { instance.invoke("activeElement") }.kind,
        )
        assertEquals(
            TrapKind.OUT_OF_BOUNDS_TABLE_ACCESS,
            assertFailsWith<WasmTrap> { instance.invoke("declarativeElement") }.kind,
        )
    }

    @Test
    fun failingActiveElementSegmentDoesNotPartiallyMutateImportedTable(): Unit = runBlocking {
        val tableType = TableType(FuncRefType, Limits(10uL, 10uL))
        val importedTable = TableInstance(tableType)
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            imports += Import("host", "table", ImportDesc.Table(tableType))
            functions += Function(0, emptyList(), emptyList())
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = listOf(I32Const(7)),
                mode = ElementMode.Active(0, listOf(I32Const(7))),
                exprs = listOf(listOf(RefFunc(0))),
                activeType = FuncRefType,
            )
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = listOf(I32Const(8)),
                mode = ElementMode.Active(0, listOf(I32Const(8))),
                exprs = List(5) { listOf(RefFunc(0)) },
                activeType = FuncRefType,
            )
        }

        assertFailsWith<InstantiationFailure> {
            Instance(
                Store(),
                module,
                ResolvedImports(tables = listOf(importedTable)),
            )
        }
        assertIs<Value.Ref.Func>(importedTable.get(7))
        assertTrue(importedTable.get(8).isNullRef())
        assertTrue(importedTable.get(9).isNullRef())
    }

    @Test
    fun emptyActiveElementSegmentPastTableEndFailsInstantiation() {
        val module = validatedModule {
            tables += Table(TableType(FuncRefType, Limits(0uL, 0uL)))
            elementSegments += ElementSegment(
                tableIndex = 0,
                offset = listOf(I32Const(1)),
                mode = ElementMode.Active(0, listOf(I32Const(1))),
                exprs = emptyList(),
                activeType = FuncRefType,
            )
        }

        val failure = assertFailsWith<InstantiationFailure> { Instance(module) }
        assertEquals(
            TrapKind.OUT_OF_BOUNDS_TABLE_ACCESS,
            assertIs<WasmTrap>(failure.cause).kind,
        )
    }

    @Test
    fun indirectCallPastTableEndUsesUndefinedElementTrap(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            tables += Table(TableType(FuncRefType, Limits(1uL, 1uL)))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(I32Const(1), CallIndirect(typeIndex = 0, tableIndex = 0)),
            )
            exports += Export("call", ExportDesc.Function(0))
        }

        assertEquals(
            TrapKind.UNDEFINED_ELEMENT,
            assertFailsWith<WasmTrap> { Instance(module).invoke("call") }.kind,
        )
    }

    @Test
    fun callRefNullUsesTheTypedFunctionReferenceTrap(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            functions += Function(0, emptyList(), listOf(RefNull(HeapType.Index(0)), CallRef(0)))
            functions += Function(
                0,
                emptyList(),
                listOf(RefNull(HeapType.Index(0)), ReturnCallRef(0)),
            )
            exports += Export("call", ExportDesc.Function(0))
            exports += Export("return_call", ExportDesc.Function(1))
        }
        val instance = Instance(module)

        assertEquals(
            TrapKind.NULL_FUNCTION_REFERENCE,
            assertFailsWith<WasmTrap> { instance.invoke("call") }.kind,
        )
        assertEquals(
            TrapKind.NULL_FUNCTION_REFERENCE,
            assertFailsWith<WasmTrap> { instance.invoke("return_call") }.kind,
        )
    }

    private fun recursiveCountdownModule(tail: Boolean): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
        val recurse: Instr =
            if (tail) ReturnCall(0) else Call(0)
        functions += Function(
            typeIndex = 0,
            locals = emptyList(),
            body = listOf(
                FcIndex(0x20, 0),
                Simple(0x45),
                If(
                    BlockType.Single(ValType.I32),
                    thenBody = listOf(I32Const(0)),
                    elseBody = listOf(
                        FcIndex(0x20, 0),
                        I32Const(1),
                        Simple(0x6B),
                        recurse,
                    ),
                ),
            ),
        )
        exports += Export("countdown", ExportDesc.Function(0))
    }

    private fun constantModule(): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(0, emptyList(), listOf(I32Const(7)))
        exports += Export("value", ExportDesc.Function(0))
    }

    private fun importedFunctionModule(type: FuncType): Module = validatedModule {
        types += type
        imports += Import("host", "function", ImportDesc.Function(0))
        exports += Export("host", ExportDesc.Function(0))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
