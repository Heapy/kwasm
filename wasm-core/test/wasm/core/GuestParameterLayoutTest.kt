package io.heapy.kwasm

import io.heapy.kwasm.Instr.Call
import io.heapy.kwasm.Instr.Drop
import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.If
import io.heapy.kwasm.Instr.ReturnCall
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuestParameterLayoutTest {
    @Test
    fun suspendedImportKeepsCallerParametersInPlace(): Unit = runBlocking {
        assertTrue(USE_IN_PLACE_GUEST_PARAMETERS)
        val type = FuncType(listOf(ValType.I32), listOf(ValType.I32))
        val module = validatedModule {
            types += type
            imports += Import("host", "increment", ImportDesc.Function(0))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    FcIndex(0x20, 0),
                    Call(0),
                    FcIndex(0x20, 0),
                    Simple(0x6A),
                ),
            )
            exports += Export("run", ExportDesc.Function(1))
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(type) { arguments ->
                        entered.complete(Unit)
                        release.await()
                        listOf(Value.I32((arguments.single() as Value.I32).v + 1))
                    },
                ),
            ),
        )

        val invocation = async { instance.invoke("run", listOf(Value.I32(7))) }
        withTimeout(5_000) { entered.await() }

        val frame = store.frames.single()
        assertEquals(0, store.localStack.size)
        assertEquals(frame.localCount, store.valueStackLocalSlots)
        assertEquals(Value.I32(7), store.valueStack[frame.localsBase])

        release.complete(Unit)
        assertEquals(listOf(Value.I32(15)), invocation.await())
        assertEquals(0, store.valueStackLocalSlots)
    }

    @Test
    fun pendingImportSnapshotRoundTripsInterleavedOperandPrefixes(): Unit = runBlocking {
        val module = interleavedImportModule(tailEntry = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sourceStore = Store()
        val source = Instance(
            sourceStore,
            module,
            incrementImports {
                entered.complete(Unit)
                release.await()
            },
        )
        val invocation = async { source.invoke("run", listOf(Value.I32(7))) }

        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { sourceStore.awaitSnapshotCapturable() }
        val snapshot = sourceStore.captureSnapshotState(source)
        assertEquals(listOf(Value.I32(40), Value.I32(2)), snapshot.valueStack())
        assertEquals(listOf(0, 1), snapshot.frames.map(RuntimeFrameSnapshot::stackBase))
        assertTrue(snapshot.pendingImport != null)

        val inner = snapshot.frames.last()
        val root = inner.controls.first()
        val malformedRoot = RuntimeControlSnapshot(
            kind = root.kind,
            bodyPath = root.bodyPath,
            pc = root.pc,
            stackBase = 0,
            parameterCount = root.parameterCount,
            resultCount = root.resultCount,
            labelArity = root.labelArity,
            caughtException = root.caughtException,
        )
        val malformedInner = RuntimeFrameSnapshot(
            functionIndex = inner.functionIndex,
            locals = inner.locals(),
            stackBase = inner.stackBase,
            controls = listOf(malformedRoot) + inner.controls.drop(1),
        )
        val malformed = RuntimeStoreSnapshot(
            instance = snapshot.instance,
            valueStack = snapshot.valueStack(),
            frames = snapshot.frames.dropLast(1) + malformedInner,
            pendingImport = snapshot.pendingImport,
            fuel = snapshot.fuel,
            instructionsUntilCheckpoint = snapshot.instructionsUntilCheckpoint,
        )
        val invalidStore = Store()
        val invalid = Instance(invalidStore, module, incrementImports())
        assertFailsWith<SnapshotStateException> {
            invalidStore.restoreSnapshotState(invalid, malformed)
        }

        invocation.cancel()
        assertFailsWith<CancellationException> { invocation.await() }

        val targetStore = Store()
        val target = Instance(targetStore, module, incrementImports())
        targetStore.restoreSnapshotState(target, snapshot)
        assertEquals(listOf(Value.I32(50)), target.resume())
    }

    @Test
    fun tailCallSnapshotRoundTripsInPlaceParameters(): Unit = runBlocking {
        val module = interleavedImportModule(tailEntry = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sourceStore = Store()
        val source = Instance(
            sourceStore,
            module,
            incrementImports {
                entered.complete(Unit)
                release.await()
            },
        )
        val invocation = async { source.invoke("run", listOf(Value.I32(7))) }

        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { sourceStore.awaitSnapshotCapturable() }
        val snapshot = sourceStore.captureSnapshotState(source)
        assertEquals(listOf(Value.I32(2)), snapshot.valueStack())
        assertEquals(1, snapshot.frames.size)
        assertEquals(1, snapshot.frames.single().functionIndex)
        assertEquals(0, snapshot.frames.single().stackBase)

        invocation.cancel()
        assertFailsWith<CancellationException> { invocation.await() }

        val targetStore = Store()
        val target = Instance(targetStore, module, incrementImports())
        targetStore.restoreSnapshotState(target, snapshot)
        assertEquals(listOf(Value.I32(10)), target.resume())
    }

    @Test
    fun nestedFrameSnapshotRoundTripsInPlaceParameters(): Unit = runBlocking {
        val module = recursiveCountdownModule()
        val config = StoreConfig(
            fuelEnabled = true,
            initialFuel = 80,
            fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
        )
        val sourceStore = Store(config)
        val source = Instance(sourceStore, module, ResolvedImports())
        val invocation = async {
            source.invoke("countdown", listOf(Value.I32(40)))
        }

        withTimeout(5_000) { sourceStore.awaitSnapshotCapturable() }
        val snapshot = sourceStore.captureSnapshotState(source)
        assertTrue(snapshot.frames.size > 4)
        assertEquals(snapshot.frames.size, snapshot.frames.sumOf { it.locals().size })

        invocation.cancel()
        assertFailsWith<CancellationException> { invocation.await() }

        val targetStore = Store(config.copy(initialFuel = 0))
        val target = Instance(targetStore, module, ResolvedImports())
        targetStore.restoreSnapshotState(target, snapshot)
        targetStore.addFuel(10_000)

        assertEquals(listOf(Value.I32(0)), target.resume())
    }

    @Test
    fun inPlaceLocalsDoNotConsumeTheOperandStackLimit(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = List(64) { ValType.I64 },
                body = listOf(I32Const(42)),
            )
            exports += Export("value", ExportDesc.Function(0))
        }
        for (limit in listOf(1, Int.MAX_VALUE)) {
            val store = Store(
                StoreConfig(
                    limits = ExecutionLimits(maxValueStackSlots = limit),
                ),
            )
            assertEquals(
                listOf(Value.I32(42)),
                Instance(store, module, ResolvedImports()).invoke("value"),
            )
        }
    }

    @Test
    fun emptyCalleeOperandStackCanonicalizationDoesNotRewriteAParameter(): Unit = runBlocking {
        val payload = 0x7FA1_2345
        val module = validatedModule {
            types += FuncType(listOf(ValType.F32), listOf(ValType.I32))
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    I32Const(0),
                    Drop(),
                    FcIndex(0x20, 0),
                    Simple(0xBC),
                ),
            )
            functions += Function(
                typeIndex = 0,
                locals = emptyList(),
                body = listOf(
                    I32Const(1),
                    FcIndex(0x20, 0),
                    Call(0),
                    Simple(0x6A),
                ),
            )
            exports += Export("value", ExportDesc.Function(1))
        }
        val sourceStore = Store(
            StoreConfig(
                fuelEnabled = true,
                initialFuel = 3,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
                canonicalizeNaNs = false,
            ),
        )
        val source = Instance(sourceStore, module, ResolvedImports())
        val invocation = async {
            source.invoke("value", listOf(Value.F32(Float.fromBits(payload))))
        }

        withTimeout(5_000) { sourceStore.awaitSnapshotCapturable() }
        val entrySnapshot = sourceStore.captureSnapshotState(source)
        assertEquals(listOf(Value.I32(1)), entrySnapshot.valueStack())
        assertEquals(2, entrySnapshot.frames.size)

        invocation.cancel()
        assertFailsWith<CancellationException> { invocation.await() }

        val targetStore = Store(
            StoreConfig(
                fuelEnabled = true,
                initialFuel = 0,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
                canonicalizeNaNs = true,
            ),
        )
        val target = Instance(targetStore, module, ResolvedImports())
        targetStore.restoreSnapshotState(target, entrySnapshot)
        targetStore.addFuel(2)
        val resumed = async { target.resume() }

        withTimeout(5_000) { targetStore.awaitSnapshotCapturable() }
        val afterDrop = targetStore.captureSnapshotState(target)
        assertEquals(listOf(Value.I32(1)), afterDrop.valueStack())
        val parameter = afterDrop.frames.last().locals().single()
        assertEquals(payload, (parameter as Value.F32).v.toRawBits())

        resumed.cancel()
        assertFailsWith<CancellationException> { resumed.await() }
    }

    private fun interleavedImportModule(tailEntry: Boolean): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
        imports += Import("host", "increment", ImportDesc.Function(0))
        functions += Function(
            typeIndex = 0,
            locals = emptyList(),
            body = listOf(
                I32Const(2),
                FcIndex(0x20, 0),
                Call(0),
                Simple(0x6A),
            ),
        )
        functions += Function(
            typeIndex = 0,
            locals = emptyList(),
            body =
                if (tailEntry) {
                    listOf(FcIndex(0x20, 0), ReturnCall(1))
                } else {
                    listOf(
                        I32Const(40),
                        FcIndex(0x20, 0),
                        Call(1),
                        Simple(0x6A),
                    )
                },
        )
        exports += Export("run", ExportDesc.Function(2))
    }

    private fun incrementImports(
        beforeResult: suspend () -> Unit = {},
    ): ResolvedImports = ResolvedImports(
        functions = listOf(
            HostImport(FuncType(listOf(ValType.I32), listOf(ValType.I32))) { arguments ->
                beforeResult()
                listOf(Value.I32((arguments.single() as Value.I32).v + 1))
            },
        ),
    )

    private fun recursiveCountdownModule(): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
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
                        Call(0),
                    ),
                ),
            ),
        )
        exports += Export("countdown", ExportDesc.Function(0))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
