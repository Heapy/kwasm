package io.heapy.kwasm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotSuspensionSafetyJvmTest {
    @Test
    fun suspendedHostImportMayResumeOnAnotherThreadWithoutLosingGuestState(): Unit = runBlocking {
        val type = FuncType(emptyList(), listOf(ValType.I32))
        val module = validatedModule {
            types += type
            imports += Import("host", "resume", ImportDesc.Function(0))
            exports += Export("resume", ExportDesc.Function(0))
        }
        val releaseImport = CompletableDeferred<Int>()
        var suspensionThread: Thread? = null
        var resumptionThread: Thread? = null
        val store = Store()
        val instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(type) {
                        suspensionThread = Thread.currentThread()
                        val result = releaseImport.await()
                        resumptionThread = Thread.currentThread()
                        listOf(Value.I32(result))
                    },
                ),
            ),
        )
        val resumerDispatcher =
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "kwasm-host-import-resumer")
                }
                .asCoroutineDispatcher()

        try {
            val invocation = async(Dispatchers.Unconfined) {
                instance.invoke("resume")
            }
            // InHostImport is published before the import's continuation
            // parks, so await the execution gate to guarantee the completion
            // below resumes a parked continuation on the resumer thread
            // instead of finishing the await inline.
            store.awaitSnapshotCapturable()
            assertEquals(StoreStatus.InHostImport, store.status.value)
            assertFalse(invocation.isCompleted)

            withContext(resumerDispatcher) {
                releaseImport.complete(42)
            }

            assertEquals(listOf(Value.I32(42)), invocation.await())
            assertFalse(
                suspensionThread === resumptionThread,
                "the host import should resume on the thread that completes its suspension",
            )
            assertEquals("kwasm-host-import-resumer", resumptionThread?.name)
            assertEquals(StoreStatus.Idle, store.status.value)
        } finally {
            resumerDispatcher.close()
        }
    }

    @Test
    fun hostImportCannotResumeWhileSnapshotTraversalOwnsTheStore(): Unit = runBlocking {
        val type = FuncType(emptyList(), emptyList())
        val module = validatedModule {
            types += type
            imports += Import("host", "wait", ImportDesc.Function(0))
            memories += Memory(MemoryType(Limits(1u, 1u)))
            exports += Export("wait", ExportDesc.Function(0))
        }
        val releaseImport = CompletableDeferred<Unit>()
        val resumedHostCode = AtomicBoolean(false)
        val store = Store()
        val invocationDispatcher =
            Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        lateinit var instance: Instance
        instance = Instance(
            store,
            module,
            ResolvedImports(
                functions = listOf(
                    HostImport(type) {
                        // The switched block is I/O-only. Guest mutation is
                        // deliberately applied after returning to the gated
                        // host continuation.
                        withContext(Dispatchers.IO) {
                            releaseImport.await()
                        }
                        resumedHostCode.set(true)
                        instance.memories.single().storeByte(0, 1)
                        emptyList()
                    },
                ),
            ),
        )

        try {
            val invocation = async(invocationDispatcher) { instance.invoke("wait") }
            // InHostImport is published before the invocation continuation
            // parks, so waiting on status alone would race the capture below.
            store.awaitSnapshotCapturable()
            assertEquals(StoreStatus.InHostImport, store.status.value)

            val captureStarted = CountDownLatch(1)
            val finishCapture = CountDownLatch(1)
            val captured = async(Dispatchers.Default) {
                store.captureSnapshotState(instance) { state ->
                    captureStarted.countDown()
                    assertTrue(finishCapture.await(5, TimeUnit.SECONDS))
                    state
                }
            }
            assertTrue(captureStarted.await(5, TimeUnit.SECONDS))

            releaseImport.complete(Unit)
            assertTrue(
                withTimeout(2_000) {
                    withContext(invocationDispatcher) { true }
                },
                "a ready host continuation must suspend on the gate, not block its dispatcher thread",
            )
            assertFalse(resumedHostCode.get())
            assertEquals(0, instance.memories.single().loadByte(0))

            finishCapture.countDown()
            assertEquals(0, captured.await().instance.memories.single().bytes()[0].toInt())
            assertEquals(emptyList(), invocation.await())
            assertTrue(resumedHostCode.get())
            assertEquals(1, instance.memories.single().loadByte(0))
        } finally {
            invocationDispatcher.close()
        }
    }

    @Test
    fun awaitSnapshotCapturableEnablesCrossThreadCaptureOfAParkedHostImport(): Unit = runBlocking {
        val type = FuncType(emptyList(), emptyList())
        val module = validatedModule {
            types += type
            imports += Import("host", "wait", ImportDesc.Function(0))
            exports += Export("wait", ExportDesc.Function(0))
        }
        val invocationDispatcher =
            Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            repeat(32) {
                val releaseImport = CompletableDeferred<Unit>()
                val store = Store()
                val instance = Instance(
                    store,
                    module,
                    ResolvedImports(
                        functions = listOf(
                            HostImport(type) {
                                releaseImport.await()
                                emptyList()
                            },
                        ),
                    ),
                )

                val invocation = async(invocationDispatcher) { instance.invoke("wait") }
                store.awaitSnapshotCapturable()
                assertEquals(StoreStatus.InHostImport, store.status.value)

                // The import is still blocked, so the guest cannot resume and
                // a capture from another thread must succeed deterministically.
                val snapshot = withContext(Dispatchers.Default) {
                    store.captureSnapshotState(instance)
                }
                assertEquals(0, snapshot.pendingImport?.functionIndex)

                releaseImport.complete(Unit)
                assertEquals(emptyList(), invocation.await())
                assertEquals(StoreStatus.Idle, store.status.value)
            }
        } finally {
            invocationDispatcher.close()
        }
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder()
            .apply(configure)
            .build(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
            .also(ModuleValidator::validate)
}
