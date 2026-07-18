package io.heapy.kwasm.bindgen

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmAbiCodecTest {
    @Serializable
    private data class CompositeFixture(
        val name: String,
        val count: Int,
        val payload: ByteArray,
    )

    @Test
    fun allSupportedValuesRoundTrip() {
        val nanBits = 0x7FC0_1234
        val values = listOf(
            WasmAbiValue.UnitValue,
            WasmAbiValue.Int32(Int.MIN_VALUE),
            WasmAbiValue.Int64(Long.MAX_VALUE),
            WasmAbiValue.Float32(Float.fromBits(nanBits)),
            WasmAbiValue.Float64(-12.5),
            WasmAbiValue.Bool(true),
            WasmAbiValue.Bool(false),
            WasmAbiValue.Utf8("Kotlin \uD83D\uDC1D Wasm"),
            WasmAbiValue.Bytes(byteArrayOf(0, 1, -1, 127)),
            WasmAbiValue.Composite(byteArrayOf(9, 8, 7)),
        )

        val decoded = WasmAbiCodec.decode(WasmAbiCodec.encode(values))

        assertEquals(values.size, decoded.size)
        assertEquals(WasmAbiValue.UnitValue, decoded[0])
        assertEquals(WasmAbiValue.Int32(Int.MIN_VALUE), decoded[1])
        assertEquals(WasmAbiValue.Int64(Long.MAX_VALUE), decoded[2])
        assertEquals(nanBits, assertIs<WasmAbiValue.Float32>(decoded[3]).value.toRawBits())
        assertEquals(WasmAbiValue.Float64(-12.5), decoded[4])
        assertEquals(WasmAbiValue.Bool(true), decoded[5])
        assertEquals(WasmAbiValue.Bool(false), decoded[6])
        assertEquals(WasmAbiValue.Utf8("Kotlin \uD83D\uDC1D Wasm"), decoded[7])
        assertContentEquals(
            byteArrayOf(0, 1, -1, 127),
            assertIs<WasmAbiValue.Bytes>(decoded[8]).value,
        )
        assertContentEquals(
            byteArrayOf(9, 8, 7),
            assertIs<WasmAbiValue.Composite>(decoded[9]).value,
        )
    }

    @Test
    fun serializableCompositePayloadRoundTripsDeterministically() {
        val value = CompositeFixture("worker", 42, byteArrayOf(1, 3, 3, 7))

        val first = WasmCompositeCodec.encode(CompositeFixture.serializer(), value)
        val second = WasmCompositeCodec.encode(CompositeFixture.serializer(), value)
        val restored = WasmCompositeCodec.decode(CompositeFixture.serializer(), first)

        assertContentEquals(first, second)
        assertEquals(value.name, restored.name)
        assertEquals(value.count, restored.count)
        assertContentEquals(value.payload, restored.payload)
    }

    @Test
    fun layoutIsStableAndLittleEndian() {
        val bytes = WasmAbiCodec.encode(
            WasmAbiValue.Int32(0x1234_5678),
            WasmAbiValue.Bool(true),
        )

        assertContentEquals(
            byteArrayOf(
                0x4B, 0x57, 0x41, 0x42,
                0x01, 0x00, 0x00, 0x00,
                0x02, 0x00, 0x00, 0x00,
                0x01, 0x78, 0x56, 0x34, 0x12,
                0x05, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun malformedMessagesAreRejectedWithOffsets() {
        val valid = WasmAbiCodec.encode(WasmAbiValue.Bool(true))

        val badMagic = valid.copyOf().also { it[0] = 0 }
        assertEquals(
            0,
            assertFailsWith<WasmAbiDecodingException> {
                WasmAbiCodec.decode(badMagic)
            }.offset,
        )

        val badBoolean = valid.copyOf().also { it[it.lastIndex] = 2 }
        val error = assertFailsWith<WasmAbiDecodingException> {
            WasmAbiCodec.decode(badBoolean)
        }
        assertEquals(valid.lastIndex, error.offset)
        assertTrue(error.message.orEmpty().contains("0 or 1"))

        val trailing = valid + 0
        assertEquals(
            valid.size,
            assertFailsWith<WasmAbiDecodingException> {
                WasmAbiCodec.decode(trailing)
            }.offset,
        )
    }

    @Test
    fun invalidUtf8AndUnsupportedVersionsAreRejected() {
        val invalidUtf8 = byteArrayOf(
            0x4B, 0x57, 0x41, 0x42,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x06, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(),
        )
        val utf8Error = assertFailsWith<WasmAbiDecodingException> {
            WasmAbiCodec.decode(invalidUtf8)
        }
        assertTrue(utf8Error.message.orEmpty().contains("UTF-8"))

        val futureVersion = WasmAbiCodec.encode().also { it[4] = 2 }
        val versionError = assertFailsWith<WasmAbiDecodingException> {
            WasmAbiCodec.decode(futureVersion)
        }
        assertEquals(4, versionError.offset)
        assertTrue(versionError.message.orEmpty().contains("version"))
    }

    @Test
    fun limitsAreEnforcedBeforeVariablePayloadAllocation() {
        val bytes = WasmAbiCodec.encode(WasmAbiValue.Bytes(ByteArray(32)))

        val error = assertFailsWith<WasmAbiDecodingException> {
            WasmAbiCodec.decode(
                bytes,
                WasmAbiLimits(
                    maxMessageBytes = 128,
                    maxValueCount = 1,
                    maxVariableValueBytes = 8,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("exceeds limit"))
    }

    @Test
    fun typedArgumentsReportArityAndTypeErrors() {
        val arguments = WasmAbiArguments(
            listOf(WasmAbiValue.Int32(7), WasmAbiValue.Utf8("seven")),
        )

        arguments.requireSize(2)
        assertEquals(7, arguments.int32(0))
        assertEquals("seven", arguments.string(1))
        assertFailsWith<WasmAbiArityException> { arguments.requireSize(1) }
        assertFailsWith<WasmAbiTypeMismatchException> { arguments.boolean(0) }
    }
}
