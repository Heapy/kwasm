package io.heapy.kwasm

import io.heapy.kwasm.Instr.Br
import io.heapy.kwasm.Instr.Call
import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.If
import io.heapy.kwasm.Instr.Loop
import io.heapy.kwasm.Instr.Nop
import io.heapy.kwasm.Instr.Simple
import io.heapy.kwasm.Instr.TryTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuspensionSemanticsTest {
    @Test
    fun callerCancellationIsObservedAtEveryCheckpointClass(): Unit = runBlocking {
        assertCancellationAtNextCheckpoint(
            checkpointClass = "function call",
            checkpointInterval = Int.MAX_VALUE,
            module = validatedModule {
                types += FuncType(emptyList(), emptyList())
                functions += Function(0, emptyList(), listOf(Call(1)))
                functions += Function(0, emptyList(), emptyList())
                exports += Export("run", ExportDesc.Function(0))
            },
        )
        assertCancellationAtNextCheckpoint(
            checkpointClass = "loop back-edge",
            checkpointInterval = Int.MAX_VALUE,
            module = validatedModule {
                types += FuncType(emptyList(), emptyList())
                functions += Function(
                    0,
                    emptyList(),
                    listOf(Loop(BlockType.Empty, listOf(Br(0)))),
                )
                exports += Export("run", ExportDesc.Function(0))
            },
        )
        assertCancellationAtNextCheckpoint(
            checkpointClass = "straight-line interval",
            checkpointInterval = 2,
            module = validatedModule {
                types += FuncType(emptyList(), emptyList())
                functions += Function(0, emptyList(), listOf(Nop, Nop))
                exports += Export("run", ExportDesc.Function(0))
            },
        )
    }

    @Test
    fun withTimeoutCancelsDeepRecursionAndPoisonsTheStore(): Unit = runBlocking {
        val store = Store()
        val instance = Instance(store, fibModule(), ResolvedImports())

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) { instance.invoke("fib", listOf(Value.I32(40))) }
        }
        assertTrue(store.poisoned, "deep-recursion cancellation must poison the store")
        assertEquals(StoreStatus.Poisoned, store.status.value)
        assertFailsWith<PoisonedStoreException> {
            instance.invoke("fib", listOf(Value.I32(1)))
        }
    }

    @Test
    fun cancellationWhileParkedWaitingForFuelPoisonsTheStore(): Unit = runBlocking {
        val store = Store(
            StoreConfig(
                fuelEnabled = true,
                initialFuel = 0,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Suspend,
            ),
        )
        val instance = Instance(store, constantModule(), ResolvedImports())

        val invocation = async { instance.invoke("value") }
        store.status.first { it == StoreStatus.WaitingForFuel }
        invocation.cancel(CancellationException("cancelled while waiting for fuel"))

        assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(store.poisoned, "fuel-parked cancellation must poison the store")
        assertEquals(StoreStatus.Poisoned, store.status.value)
        assertFailsWith<PoisonedStoreException> { instance.invoke("value") }
    }

    @Test
    fun cancellationWhileExplicitlyPausedPoisonsTheStore(): Unit = runBlocking {
        val store = Store()
        val instance = Instance(store, constantModule(), ResolvedImports())
        val pause = store.requestPause()
        val invocation = async { instance.invoke("value") }

        pause.awaitPaused()
        assertEquals(StoreStatus.Paused, store.status.value)
        invocation.cancel(CancellationException("cancelled while paused"))

        assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(store.poisoned, "pause-parked cancellation must poison the store")
        assertEquals(StoreStatus.Poisoned, store.status.value)
        assertFailsWith<PoisonedStoreException> { instance.invoke("value") }
    }

    @Test
    fun cancellationWhileParkedInHostImportPoisonsTheStore(): Unit = runBlocking {
        val gate = CompletableDeferred<Int>()
        val type = FuncType(emptyList(), listOf(ValType.I32))
        val store = Store()
        val instance = Instance(
            store,
            importedFunctionModule(type),
            ResolvedImports(
                functions = listOf(
                    HostImport(type) {
                        listOf(Value.I32(gate.await()))
                    },
                ),
            ),
        )

        val invocation = async { instance.invoke("host") }
        store.status.first { it == StoreStatus.InHostImport }
        invocation.cancel(CancellationException("cancelled inside a suspended host import"))

        assertFailsWith<CancellationException> { invocation.await() }
        assertTrue(
            store.poisoned,
            "cancellation inside a parked host import must poison the store",
        )
        assertEquals(StoreStatus.Poisoned, store.status.value)
        assertFailsWith<PoisonedStoreException> { instance.invoke("host") }
    }

    @Test
    fun cancellationBypassesGuestCatchAllHandlers(): Unit = runBlocking {
        val module = validatedModule {
            types += FuncType(emptyList(), emptyList())
            functions += Function(
                0,
                emptyList(),
                listOf(
                    TryTable(
                        blockType = BlockType.Empty,
                        catches = listOf(CatchClause.All(depth = 0, withReference = false)),
                        body = listOf(Loop(BlockType.Empty, listOf(Br(0)))),
                    ),
                ),
            )
            exports += Export("spin", ExportDesc.Function(0))
        }
        val store = Store(StoreConfig(checkpointInterval = 1))
        val instance = Instance(store, module, ResolvedImports())

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) { instance.invoke("spin") }
        }
        assertTrue(store.poisoned, "cancellation must bypass guest catch_all handlers")
        assertEquals(StoreStatus.Poisoned, store.status.value)
    }

    private suspend fun assertCancellationAtNextCheckpoint(
        checkpointClass: String,
        checkpointInterval: Int,
        module: Module,
    ): Unit = coroutineScope {
        var observedCheckpoints = 0
        lateinit var invocation: Deferred<List<Value>>
        val store = Store(
            StoreConfig(
                checkpointInterval = checkpointInterval,
                listener = object : ExecutionListener {
                    override fun onCheckpoint(
                        store: Store,
                        functionIndex: Int?,
                        instructionIndex: Int?,
                    ) {
                        observedCheckpoints += 1
                        if (observedCheckpoints == 1) {
                            invocation.cancel(
                                CancellationException(
                                    "cancel before $checkpointClass checkpoint",
                                ),
                            )
                        }
                    }
                },
            ),
        )
        val instance = Instance(store, module, ResolvedImports())
        invocation = async(start = CoroutineStart.LAZY) {
            instance.invoke("run")
        }

        invocation.start()
        assertFailsWith<CancellationException> {
            invocation.await()
        }
        assertEquals(
            1,
            observedCheckpoints,
            "cancellation should abort before notifying the $checkpointClass checkpoint",
        )
        assertTrue(store.poisoned, "$checkpointClass cancellation must poison the store")
        assertEquals(StoreStatus.Poisoned, store.status.value)
    }

    private fun fibModule(): Module = validatedModule {
        types += FuncType(listOf(ValType.I32), listOf(ValType.I32))
        functions += Function(
            typeIndex = 0,
            locals = emptyList(),
            body = listOf(
                FcIndex(0x20, 0),
                I32Const(2),
                Simple(0x48),
                If(
                    BlockType.Single(ValType.I32),
                    thenBody = listOf(FcIndex(0x20, 0)),
                    elseBody = listOf(
                        FcIndex(0x20, 0),
                        I32Const(1),
                        Simple(0x6B),
                        Call(0),
                        FcIndex(0x20, 0),
                        I32Const(2),
                        Simple(0x6B),
                        Call(0),
                        Simple(0x6A),
                    ),
                ),
            ),
        )
        exports += Export("fib", ExportDesc.Function(0))
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
        ModuleBuilder()
            .apply(configure)
            .build(WASM_HEADER)
            .also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
