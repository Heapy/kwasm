package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import io.heapy.kwasm.Store
import io.heapy.kwasm.Value
import io.heapy.kwasm.bindgen.WasmAbiCodec
import io.heapy.kwasm.bindgen.WasmAbiValue
import io.heapy.kwasm.bindgen.WasmBoundary
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@WasmBoundary(name = "test:runtime")
public interface RuntimeFixture {
    public fun add(left: Int, right: Int): Int

    public suspend fun addSuspend(left: Int, right: Int): Int
}

class KwasmInstanceHostInvokerTest {
    @Test
    fun generatedClientCrossesRealInstanceMemoryForBlockingAndSuspendCalls() {
        val instance = fixtureInstance()
        val client = RuntimeFixtureHostClient(instance.asBindgenHostInvoker())

        assertEquals(42, client.add(19, 23))
        assertEquals(42, runBlocking { client.addSuspend(20, 22) })

        val memory = instance.exportedMemory(KwasmBindgenRuntimeAbi.MEMORY_EXPORT)
            ?: error("fixture memory export missing")
        val firstRequest = memory.data().copyOfRange(
            FIRST_ALLOCATION,
            FIRST_ALLOCATION + firstRequestSize(),
        )
        assertContentEquals(byteArrayOf(0x4B, 0x57, 0x52, 0x51), firstRequest.copyOfRange(0, 4))
        assertEquals(KwasmBindgenRuntimeAbi.VERSION, firstRequest[4].toInt())
        assertEquals("test:runtime", firstRequest.utf8Field(offset = 20, lengthOffset = 8))
        assertEquals(
            "add",
            firstRequest.utf8Field(
                offset = 20 + "test:runtime".encodeToByteArray().size,
                lengthOffset = 12,
            ),
        )

        val argumentsOffset =
            KwasmBindgenRuntimeAbi.REQUEST_HEADER_SIZE +
                "test:runtime".encodeToByteArray().size +
                "add".encodeToByteArray().size
        assertContentEquals(
            WasmAbiCodec.encode(
                WasmAbiValue.Int32(19),
                WasmAbiValue.Int32(23),
            ),
            firstRequest.copyOfRange(argumentsOffset, firstRequest.size),
        )

        val freeCount = (instance.exportedGlobal("free_count")?.value as? Value.I32)?.v
        assertEquals(4, freeCount, "each call must release its request and result allocations")
    }

    @Test
    fun constructorRejectsAnIncompleteGuestContract() {
        val module = Module.decode(
            WatComposer.compose(
                """
                (module
                  (memory ${'$'}memory 1)
                  (func ${'$'}allocate (param i32) (result i32)
                    i32.const 32)
                  (func ${'$'}invoke (param i32 i32) (result i64)
                    i64.const 0)
                  (export "${KwasmBindgenRuntimeAbi.MEMORY_EXPORT}"
                    (memory ${'$'}memory))
                  (export "${KwasmBindgenRuntimeAbi.ALLOCATE_EXPORT}"
                    (func ${'$'}allocate))
                  (export "${KwasmBindgenRuntimeAbi.INVOKE_EXPORT}"
                    (func ${'$'}invoke))
                )
                """.trimIndent(),
            ),
        )
        val failure = assertFailsWith<KwasmBindgenContractException> {
            Instance.instantiate(Store(), module).asBindgenHostInvoker()
        }

        assertEquals(KwasmBindgenRuntimeAbi.FREE_EXPORT, failure.contractElement)
        assertTrue(failure.message.orEmpty().contains("missing"))
    }

    @Test
    fun legacyGeneratedMemoryAliasRemainsAccepted() {
        val instance = fixtureInstance(
            memoryExportName = KwasmBindgenRuntimeAbi.LEGACY_MEMORY_EXPORT,
        )
        val client = RuntimeFixtureHostClient(instance.asBindgenHostInvoker())

        assertEquals(42, client.add(19, 23))
    }

    @Test
    fun argumentsAreLimitedBeforeGuestAllocation() {
        val instance = fixtureInstance()
        val invoker = instance.asBindgenHostInvoker(
            limits = KwasmBindgenRuntimeLimits(maxArgumentsBytes = 1),
        )

        val failure = assertFailsWith<KwasmBindgenLimitExceeded> {
            invoker.invoke(
                boundary = "test:runtime",
                function = "add",
                arguments = byteArrayOf(1, 2),
            )
        }

        assertEquals("maxArgumentsBytes", failure.limit)
        assertEquals(0, (instance.exportedGlobal("free_count")?.value as Value.I32).v)
    }

    @Test
    fun malformedResultRangeStillReleasesTheRequest() {
        val instance = Instance.instantiate(
            store = Store(),
            module = Module.decode(WatComposer.compose(INVALID_RESULT_WAT)),
        )

        val failure = assertFailsWith<KwasmBindgenContractException> {
            instance.asBindgenHostInvoker().invoke("test:runtime", "add", byteArrayOf())
        }

        assertEquals("result allocation", failure.contractElement)
        assertEquals(1, (instance.exportedGlobal("free_count")?.value as Value.I32).v)
    }

    private fun fixtureInstance(
        memoryExportName: String = KwasmBindgenRuntimeAbi.MEMORY_EXPORT,
    ): Instance =
        Instance.instantiate(
            store = Store(),
            module = Module.decode(
                WatComposer.compose(
                    FIXTURE_WAT.replace(
                        "(export \"${KwasmBindgenRuntimeAbi.MEMORY_EXPORT}\"",
                        "(export \"$memoryExportName\"",
                    ),
                ),
            ),
        )

    private fun firstRequestSize(): Int =
        KwasmBindgenRuntimeAbi.REQUEST_HEADER_SIZE +
            "test:runtime".encodeToByteArray().size +
            "add".encodeToByteArray().size +
            WasmAbiCodec.encode(
                WasmAbiValue.Int32(19),
                WasmAbiValue.Int32(23),
            ).size

    private companion object {
        const val FIRST_ALLOCATION: Int = 1024

        val FIXTURE_WAT: String =
            """
            (module
              (memory ${'$'}memory 1)
              (global ${'$'}next (mut i32) (i32.const $FIRST_ALLOCATION))
              (global ${'$'}free_count (mut i32) (i32.const 0))

              (func ${'$'}allocate
                (param i32)
                (result i32)
                (local i32)
                global.get ${'$'}next
                local.tee 1
                global.get ${'$'}next
                local.get 0
                i32.add
                global.set ${'$'}next)

              (func ${'$'}free
                (param i32)
                (param i32)
                global.get ${'$'}free_count
                i32.const 1
                i32.add
                global.set ${'$'}free_count)

              (func ${'$'}invoke
                (param i32)
                (param i32)
                (result i64)
                (local i32)
                (local i32)

                local.get 0
                i32.load offset=8
                local.get 0
                i32.load offset=12
                i32.add
                i32.const ${KwasmBindgenRuntimeAbi.REQUEST_HEADER_SIZE}
                i32.add
                local.get 0
                i32.add
                local.set 2

                i32.const 17
                call ${'$'}allocate
                local.set 3

                local.get 3
                i32.const 0x4241574b
                i32.store
                local.get 3
                i32.const 1
                i32.store8 offset=4
                local.get 3
                i32.const 0
                i32.store8 offset=5
                local.get 3
                i32.const 0
                i32.store16 offset=6
                local.get 3
                i32.const 1
                i32.store offset=8
                local.get 3
                i32.const 1
                i32.store8 offset=12
                local.get 3
                local.get 2
                i32.load offset=13 align=1
                local.get 2
                i32.load offset=18 align=1
                i32.add
                i32.store offset=13 align=1

                local.get 3
                i64.extend_i32_u
                i64.const 17
                i64.const 32
                i64.shl
                i64.or)

              (export "${KwasmBindgenRuntimeAbi.MEMORY_EXPORT}"
                (memory ${'$'}memory))
              (export "${KwasmBindgenRuntimeAbi.ALLOCATE_EXPORT}"
                (func ${'$'}allocate))
              (export "${KwasmBindgenRuntimeAbi.FREE_EXPORT}"
                (func ${'$'}free))
              (export "${KwasmBindgenRuntimeAbi.INVOKE_EXPORT}"
                (func ${'$'}invoke))
              (export "free_count" (global ${'$'}free_count))
            )
            """.trimIndent()

        val INVALID_RESULT_WAT: String =
            """
            (module
              (memory ${'$'}memory 1)
              (global ${'$'}free_count (mut i32) (i32.const 0))
              (func ${'$'}allocate (param i32) (result i32)
                i32.const $FIRST_ALLOCATION)
              (func ${'$'}free (param i32) (param i32)
                global.get ${'$'}free_count
                i32.const 1
                i32.add
                global.set ${'$'}free_count)
              (func ${'$'}invoke (param i32 i32) (result i64)
                i64.const 42949738490)
              (export "${KwasmBindgenRuntimeAbi.MEMORY_EXPORT}" (memory ${'$'}memory))
              (export "${KwasmBindgenRuntimeAbi.ALLOCATE_EXPORT}" (func ${'$'}allocate))
              (export "${KwasmBindgenRuntimeAbi.FREE_EXPORT}" (func ${'$'}free))
              (export "${KwasmBindgenRuntimeAbi.INVOKE_EXPORT}" (func ${'$'}invoke))
              (export "free_count" (global ${'$'}free_count))
            )
            """.trimIndent()
    }
}

private fun ByteArray.readIntLittleEndian(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.utf8Field(offset: Int, lengthOffset: Int): String =
    copyOfRange(offset, offset + readIntLittleEndian(lengthOffset)).decodeToString()
