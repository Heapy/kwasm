package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.Instance
import io.heapy.kwasm.LinkException
import io.heapy.kwasm.Linker
import io.heapy.kwasm.Module
import io.heapy.kwasm.PauseHandle
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import io.heapy.kwasm.bindgen.WasmGuestRuntimeAbi
import io.heapy.kwasm.bindgen.WasmGuestRuntimeLimits
import io.heapy.kwasm.bindgen.WasmGuestRuntimeRequestCodec
import io.heapy.kwasm.bindgen.WasmImportBinding
import io.heapy.kwasm.snapshot.KwasmSnapshot
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KwasmBindgenHostImportsTest {
    @Test
    fun generatedBindingsRoundTripThroughCallerMemoryForSyncAndSuspendHandlers() = runBlocking {
        val suspended = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val registry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding(
                    boundary = "test:imports",
                    function = "sync",
                    isSuspend = false,
                ) { arguments ->
                    arguments.reversedArray()
                },
                WasmImportBinding(
                    boundary = "test:imports",
                    function = "suspend",
                    isSuspend = true,
                ) { arguments ->
                    suspended.complete(Unit)
                    resume.await()
                    arguments + 0x2A
                },
            ),
        )
        val instance = fixtureInstance(registry)
        val memory = requireNotNull(instance.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT))

        val sync = begin(
            instance,
            memory,
            WasmGuestRuntimeRequestCodec.encode(
                boundary = "test:imports",
                function = "sync",
                arguments = byteArrayOf(1, 2, 3),
            ),
        )
        assertEquals(3, sync.resultSize)
        assertEquals(3, finish(instance, sync, RESULT_POINTER))
        assertContentEquals(
            byteArrayOf(3, 2, 1),
            memory.data().copyOfRange(RESULT_POINTER, RESULT_POINTER + 3),
        )

        val suspendedBegin = async {
            begin(
                instance,
                memory,
                WasmGuestRuntimeRequestCodec.encode(
                    boundary = "test:imports",
                    function = "suspend",
                    arguments = byteArrayOf(4, 5),
                ),
            )
        }
        suspended.await()
        assertFalse(suspendedBegin.isCompleted)
        resume.complete(Unit)

        val asyncResult = suspendedBegin.await()
        assertEquals(3, asyncResult.resultSize)
        assertEquals(3, finish(instance, asyncResult, RESULT_POINTER + 16))
        assertContentEquals(
            byteArrayOf(4, 5, 0x2A),
            memory.data().copyOfRange(RESULT_POINTER + 16, RESULT_POINTER + 19),
        )
    }

    @Test
    fun beginRejectsMalformedRangesBeforeInvokingTheBinding() {
        var invocationCount = 0
        val registry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    invocationCount += 1
                    byteArrayOf()
                },
            ),
        )
        val instance = fixtureInstance(registry)

        val negativePointer = assertFailsWith<KwasmBindgenContractException> {
            runBlocking {
                invokeBegin(instance, pointer = -1, size = 20)
            }
        }
        assertEquals(WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT, negativePointer.contractElement)

        assertFailsWith<KwasmBindgenContractException> {
            runBlocking {
                invokeBegin(instance, pointer = 65_530, size = 20)
            }
        }

        assertFailsWith<KwasmBindgenContractException> {
            runBlocking {
                invokeBegin(instance, pointer = REQUEST_POINTER, size = 20)
            }
        }
        assertEquals(0, invocationCount)
    }

    @Test
    fun finishRejectsCapacityRangeAndIdMisuseWithoutLeakingPendingResults() = runBlocking {
        val registry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    byteArrayOf(9, 8, 7, 6)
                },
            ),
        )
        val instance = fixtureInstance(registry)
        val memory = requireNotNull(instance.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT))
        val pending = begin(
            instance,
            memory,
            WasmGuestRuntimeRequestCodec.encode(
                boundary = "test:imports",
                function = "call",
                arguments = byteArrayOf(),
            ),
        )

        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(
                instance,
                callId = pending.callId + 1,
                pointer = RESULT_POINTER,
                capacity = pending.resultSize,
            )
        }
        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(
                instance,
                callId = pending.callId,
                pointer = RESULT_POINTER,
                capacity = pending.resultSize - 1,
            )
        }
        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(
                instance,
                callId = pending.callId,
                pointer = 65_534,
                capacity = pending.resultSize,
            )
        }
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0),
            memory.data().copyOfRange(RESULT_POINTER, RESULT_POINTER + 4),
        )

        assertEquals(
            0,
            invokeFinish(instance, pending.callId, pointer = 0, capacity = 0),
        )
        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(
                instance,
                callId = pending.callId,
                pointer = RESULT_POINTER,
                capacity = pending.resultSize,
            )
        }
        assertEquals(0, registry.cancelPendingCalls(instance))
    }

    @Test
    fun pendingResultsAreCallerScopedAndHaveAnExplicitLifecycleEscapeHatch() = runBlocking {
        val registry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    byteArrayOf(1)
                },
            ),
        )
        val first = fixtureInstance(registry)
        val second = fixtureInstance(registry)
        val firstMemory = requireNotNull(first.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT))
        val pending = begin(
            first,
            firstMemory,
            WasmGuestRuntimeRequestCodec.encode(
                boundary = "test:imports",
                function = "call",
                arguments = byteArrayOf(),
            ),
        )

        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(second, pending.callId, RESULT_POINTER, pending.resultSize)
        }
        assertEquals(1, registry.cancelPendingCalls(first))
        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(first, pending.callId, RESULT_POINTER, pending.resultSize)
        }
        Unit
    }

    @Test
    fun pendingResultSurvivesPauseCaptureRestoreAndFinishesExactlyOnce() = runBlocking {
        lateinit var source: Instance
        val pauseRequested = CompletableDeferred<PauseHandle>()
        val sourceRegistry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    pauseRequested.complete(source.store.requestPause())
                    byteArrayOf(0x5A, 0x6B)
                },
            ),
        )
        source = fixtureInstance(
            registry = sourceRegistry,
            store = Store(
                StoreConfig(
                    checkpointInterval = 1,
                    canonicalizeNaNs = true,
                ),
            ),
        )
        val sourceMemory = requireNotNull(
            source.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT),
        )
        val request = WasmGuestRuntimeRequestCodec.encode(
            boundary = "test:imports",
            function = "call",
            arguments = byteArrayOf(),
        )
        request.copyInto(sourceMemory.data(), destinationOffset = REQUEST_POINTER)

        val sourceInvocation = async {
            (source.invoke(
                "roundTrip",
                listOf(
                    Value.I32(REQUEST_POINTER),
                    Value.I32(request.size),
                    Value.I32(RESULT_POINTER),
                    Value.I32(2),
                ),
            ).single() as Value.I32).v
        }
        val pause = pauseRequested.await()
        pause.awaitPaused()
        val snapshot = KwasmSnapshot.capture(source)

        var targetBindingInvocations = 0
        val targetRegistry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    targetBindingInvocations++
                    byteArrayOf(0x7C)
                },
            ),
        )
        val target = fixtureInstance(
            registry = targetRegistry,
            store = Store(
                StoreConfig(
                    checkpointInterval = 1,
                    canonicalizeNaNs = true,
                ),
            ),
        )
        targetRegistry.attach(target)

        val hostile = snapshot.copyOf()
        val payloadOffset = hostile.lastIndexOf(PENDING_SNAPSHOT_MAGIC)
        check(payloadOffset >= 0) { "bindgen participant payload not found" }
        hostile[payloadOffset + PENDING_SNAPSHOT_HEADER_SIZE] = 2
        val hostileFailure = assertFailsWith<SnapshotFormatException> {
            KwasmSnapshot.restore(hostile, target)
        }
        assertContains(hostileFailure.message.orEmpty(), "foreign caller association")

        KwasmSnapshot.restore(snapshot, target)
        assertEquals(listOf(Value.I32(2)), target.resume())
        val targetMemory = requireNotNull(
            target.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT),
        )
        assertContentEquals(
            byteArrayOf(0x5A, 0x6B),
            targetMemory.data().copyOfRange(RESULT_POINTER, RESULT_POINTER + 2),
        )
        assertEquals(0, targetBindingInvocations)
        assertSuspendFailsWith<KwasmBindgenContractException> {
            invokeFinish(target, callId = 1, pointer = RESULT_POINTER, capacity = 2)
        }

        val next = begin(target, targetMemory, request)
        assertEquals(2, next.callId)
        assertEquals(1, finish(target, next, RESULT_POINTER + 16))
        assertEquals(1, targetBindingInvocations)
        assertEquals(0x7C, targetMemory.data()[RESULT_POINTER + 16].toInt() and 0xFF)

        pause.resume()
        assertEquals(2, sourceInvocation.await())
        assertEquals(0x5A, sourceMemory.data()[RESULT_POINTER].toInt() and 0xFF)
        assertEquals(0x6B, sourceMemory.data()[RESULT_POINTER + 1].toInt() and 0xFF)
    }

    @Test
    fun pendingSnapshotRestoreEnforcesTargetLimitsWithoutMutatingRegistry() = runBlocking {
        lateinit var source: Instance
        val pauseRequested = CompletableDeferred<PauseHandle>()
        val sourceRegistry = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding("test:imports", "call", false) {
                    pauseRequested.complete(source.store.requestPause())
                    byteArrayOf(1, 2, 3, 4)
                },
            ),
        )
        source = fixtureInstance(
            registry = sourceRegistry,
            store = Store(
                StoreConfig(
                    checkpointInterval = 1,
                    canonicalizeNaNs = true,
                ),
            ),
        )
        val sourceMemory = requireNotNull(
            source.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT),
        )
        val request = WasmGuestRuntimeRequestCodec.encode(
            boundary = "test:imports",
            function = "call",
            arguments = byteArrayOf(),
        )
        request.copyInto(sourceMemory.data(), destinationOffset = REQUEST_POINTER)
        val sourceInvocation = async {
            source.invoke(
                "roundTrip",
                listOf(
                    Value.I32(REQUEST_POINTER),
                    Value.I32(request.size),
                    Value.I32(RESULT_POINTER),
                    Value.I32(4),
                ),
            )
        }
        val pause = pauseRequested.await()
        pause.awaitPaused()
        val snapshot = KwasmSnapshot.capture(source)

        val targetRegistry = KwasmBindgenHostImportRegistry(
            bindings = listOf(
                WasmImportBinding("test:imports", "call", false) {
                    byteArrayOf(9, 8, 7)
                },
            ),
            limits = KwasmBindgenHostImportLimits(
                request = WasmGuestRuntimeLimits(maxResultBytes = 3),
            ),
        )
        val target = fixtureInstance(
            registry = targetRegistry,
            store = Store(
                StoreConfig(
                    checkpointInterval = 1,
                    canonicalizeNaNs = true,
                ),
            ),
        )
        targetRegistry.attach(target)
        val limitFailure = assertFailsWith<SnapshotFormatException> {
            KwasmSnapshot.restore(snapshot, target)
        }
        assertContains(limitFailure.message.orEmpty(), "maxResultBytes")

        val targetMemory = requireNotNull(
            target.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT),
        )
        val firstAfterFailure = begin(target, targetMemory, request)
        assertEquals(1, firstAfterFailure.callId)
        assertEquals(3, finish(target, firstAfterFailure, RESULT_POINTER))
        assertContentEquals(
            byteArrayOf(9, 8, 7),
            targetMemory.data().copyOfRange(RESULT_POINTER, RESULT_POINTER + 3),
        )

        pause.resume()
        assertEquals(listOf(Value.I32(4)), sourceInvocation.await())
    }

    @Test
    fun registrationEnforcesRoutesSignaturesMemoryAliasesAndResourceLimits() {
        assertFailsWith<KwasmBindgenContractException> {
            KwasmBindgenHostImportRegistry(
                listOf(
                    WasmImportBinding("duplicate", "route", false) { byteArrayOf() },
                    WasmImportBinding("duplicate", "route", true) { byteArrayOf() },
                ),
            )
        }

        val registry = KwasmBindgenHostImportRegistry(
            bindings = listOf(
                WasmImportBinding("test:imports", "call", false) {
                    byteArrayOf(1, 2)
                },
            ),
            limits = KwasmBindgenHostImportLimits(
                request = WasmGuestRuntimeLimits(maxResultBytes = 1),
            ),
        )
        assertFailsWith<LinkException> {
            registry.define(Linker()).instantiate(module(beginReturnsI32 = true))
        }

        val legacy = fixtureInstance(
            registry = KwasmBindgenHostImportRegistry(
                listOf(
                    WasmImportBinding("test:imports", "call", false) {
                        byteArrayOf()
                    },
                ),
            ),
            memoryExport = WasmGuestRuntimeAbi.LEGACY_MEMORY_EXPORT,
        )
        val legacyMemory = requireNotNull(
            legacy.exportedMemory(WasmGuestRuntimeAbi.LEGACY_MEMORY_EXPORT),
        )
        runBlocking {
            val call = begin(
                legacy,
                legacyMemory,
                WasmGuestRuntimeRequestCodec.encode(
                    boundary = "test:imports",
                    function = "call",
                    arguments = byteArrayOf(),
                ),
            )
            assertEquals(0, call.resultSize)
            // A legal allocator may return address zero. Passing the actual
            // allocation capacity keeps completion distinct from cancellation.
            assertEquals(0, invokeFinish(legacy, call.callId, pointer = 0, capacity = 1))
            assertSuspendFailsWith<KwasmBindgenContractException> {
                invokeFinish(legacy, call.callId, pointer = 0, capacity = 1)
            }
        }

        val limited = fixtureInstance(registry)
        val limitedMemory = requireNotNull(
            limited.exportedMemory(WasmGuestRuntimeAbi.MEMORY_EXPORT),
        )
        val limitFailure = assertFailsWith<KwasmBindgenLimitExceeded> {
            runBlocking {
                begin(
                    limited,
                    limitedMemory,
                    WasmGuestRuntimeRequestCodec.encode(
                        boundary = "test:imports",
                        function = "call",
                        arguments = byteArrayOf(),
                    ),
                )
            }
        }
        assertEquals("maxResultBytes", limitFailure.limit)
    }

    private suspend fun begin(
        instance: Instance,
        memory: io.heapy.kwasm.MemoryInstance,
        request: ByteArray,
    ): PendingCall {
        request.copyInto(memory.data(), destinationOffset = REQUEST_POINTER)
        val packed = invokeBegin(instance, REQUEST_POINTER, request.size)
        return PendingCall(
            callId = packed.toInt(),
            resultSize = (packed.toULong() shr 32).toInt(),
        )
    }

    private suspend fun finish(
        instance: Instance,
        call: PendingCall,
        resultPointer: Int,
    ): Int = invokeFinish(
        instance = instance,
        callId = call.callId,
        pointer = resultPointer,
        capacity = call.resultSize,
    )

    private suspend fun invokeBegin(instance: Instance, pointer: Int, size: Int): Long =
        (instance.invoke(
            "begin",
            listOf(Value.I32(pointer), Value.I32(size)),
        ).single() as Value.I64).v

    private suspend fun invokeFinish(
        instance: Instance,
        callId: Int,
        pointer: Int,
        capacity: Int,
    ): Int =
        (instance.invoke(
            "finish",
            listOf(Value.I32(callId), Value.I32(pointer), Value.I32(capacity)),
        ).single() as Value.I32).v

    private fun fixtureInstance(
        registry: KwasmBindgenHostImportRegistry,
        memoryExport: String = WasmGuestRuntimeAbi.MEMORY_EXPORT,
        store: Store = Store(),
    ): Instance =
        registry.define(Linker()).instantiate(
            module = module(memoryExport = memoryExport),
            store = store,
        )

    private fun module(
        memoryExport: String = WasmGuestRuntimeAbi.MEMORY_EXPORT,
        beginReturnsI32: Boolean = false,
    ): Module = Module.decode(
        WatComposer.compose(
            """
            (module
              (import "${WasmGuestRuntimeAbi.HOST_IMPORT_MODULE}"
                "${WasmGuestRuntimeAbi.HOST_BEGIN_IMPORT}"
                (func ${'$'}begin (param i32 i32) (result ${if (beginReturnsI32) "i32" else "i64"})))
              (import "${WasmGuestRuntimeAbi.HOST_IMPORT_MODULE}"
                "${WasmGuestRuntimeAbi.HOST_FINISH_IMPORT}"
                (func ${'$'}finish (param i32 i32 i32) (result i32)))
              (memory ${'$'}memory 1)
              (func ${'$'}roundTrip
                (param i32 i32 i32 i32)
                (result i32)
                local.get 0
                local.get 1
                call ${'$'}begin
                ${if (beginReturnsI32) "" else "i32.wrap_i64"}
                local.get 2
                local.get 3
                call ${'$'}finish)
              (export "$memoryExport" (memory ${'$'}memory))
              (export "begin" (func 0))
              (export "finish" (func 1))
              (export "roundTrip" (func 2))
            )
            """.trimIndent(),
        ),
    )

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        noinline block: suspend () -> Unit,
    ): T =
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} to be thrown")
        } catch (failure: Throwable) {
            assertTrue(
                failure is T,
                "Expected ${T::class.simpleName}, received ${failure::class.simpleName}: " +
                    failure.message,
            )
            failure as T
        }

    private data class PendingCall(
        val callId: Int,
        val resultSize: Int,
    )

    private companion object {
        const val REQUEST_POINTER: Int = 256
        const val RESULT_POINTER: Int = 4_096
        const val PENDING_SNAPSHOT_HEADER_SIZE: Int = 16
        val PENDING_SNAPSHOT_MAGIC: ByteArray =
            byteArrayOf('K'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    }
}

private fun ByteArray.lastIndexOf(sequence: ByteArray): Int {
    if (sequence.isEmpty()) return size
    for (start in size - sequence.size downTo 0) {
        var matches = true
        for (index in sequence.indices) {
            if (this[start + index] != sequence[index]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}
