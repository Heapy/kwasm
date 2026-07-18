package io.heapy.kwasm

import io.heapy.kwasm.Instr.Br
import io.heapy.kwasm.Instr.Call
import io.heapy.kwasm.Instr.Loop
import io.heapy.kwasm.Instr.Nop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
