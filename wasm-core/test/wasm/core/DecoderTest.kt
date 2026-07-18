package io.heapy.kwasm

import io.heapy.kwasm.Instr.Load
import io.heapy.kwasm.Instr.RawImmediate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DecoderTest {
    @Test
    fun emptyModuleIsValidatedAndRetainsExactBytes() {
        val bytes = wasm()
        val module = Module.decode(bytes)

        assertContentEquals(bytes, module.encodedBytes())
        bytes[0] = 1
        assertEquals(0, module.encodedBytes()[0])
    }

    @Test
    fun decodeErrorsCarryTheExactBinaryOffset() {
        assertEquals(
            0,
            assertFailsWith<WasmDecodeException> {
                Module.decode(byteArrayOf(1, 2, 3, 4, 1, 0, 0, 0))
            }.offset,
        )
        assertEquals(
            8,
            assertFailsWith<WasmDecodeException> {
                Module.decode(wasm(unknownSection = true))
            }.offset,
        )
        assertEquals(
            11,
            assertFailsWith<WasmDecodeException> {
                Module.decode(WASM_HEADER + byteArrayOf(0, 2, 1, 0xFF.toByte()))
            }.offset,
        )
    }

    @Test
    fun lebReadersRejectUnusedBitsAndOverlongRepresentations() {
        assertFailsWith<WasmDecodeException> {
            ByteReader(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10))
                .readU32()
        }
        assertFailsWith<WasmDecodeException> {
            ByteReader(
                byteArrayOf(
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0,
                ),
            ).readU32()
        }
        assertFailsWith<WasmDecodeException> {
            ByteReader(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x20))
                .readS33()
        }
    }

    @Test
    fun dataCountHasItsSpecifiedPositionBeforeCode() {
        val bytes = WASM_HEADER +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(0)) +
            section(12, byteArrayOf(0)) +
            section(10, byteArrayOf(0)) +
            section(11, byteArrayOf(0))

        assertEquals(0, Module.decode(bytes).dataCount)
    }

    @Test
    fun aNonEmptyFunctionSectionRequiresACodeSection() {
        val bytes = WASM_HEADER +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(1, 0))

        assertFailsWith<WasmDecodeException> { Module.decode(bytes) }
    }

    @Test
    fun totalLocalCountBeyondU32IsMalformedBeforeHostLimitsApply() {
        val localDeclarations =
            byteArrayOf(2) +
                u32(UInt.MAX_VALUE) + byteArrayOf(0x7F) +
                u32(2u) + byteArrayOf(0x7E)
        val body = localDeclarations + byteArrayOf(0x0B)
        val bytes = WASM_HEADER +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(1, 0)) +
            section(10, byteArrayOf(1) + u32(body.size.toUInt()) + body)

        assertFailsWith<WasmDecodeException> { Module.decode(bytes) }
    }

    @Test
    fun deferredSimdAndThreadsDecodeBeforeValidationRejectsThem() {
        val simdBody =
            byteArrayOf(0xFD.toByte(), 0x0C) + ByteArray(16) + byteArrayOf(0x1A)
        val simd = assertFailsWith<UnsupportedFeature> {
            Module.decode(moduleWithFunction(simdBody))
        }
        assertEquals("simd", simd.feature)

        val atomicFence = assertFailsWith<UnsupportedFeature> {
            Module.decode(moduleWithFunction(byteArrayOf(0xFE.toByte(), 0x03, 0x00)))
        }
        assertEquals("threads", atomicFence.feature)
    }

    @Test
    fun truncatedSimdImmediateIsMalformedInsteadOfUnsupported() {
        val truncatedConst =
            byteArrayOf(0xFD.toByte(), 0x0C) + ByteArray(15)

        assertFailsWith<WasmDecodeException> {
            Module.decode(moduleWithFunction(truncatedConst))
        }
    }

    @Test
    fun multiMemoryMemargRetainsMemoryIndexAnd64BitOffset() {
        val typeSection = section(1, byteArrayOf(1, 0x60, 0, 1, 0x7F))
        val functionSection = section(3, byteArrayOf(1, 0))
        val memorySection = section(5, byteArrayOf(2, 0, 1, 4, 1))
        val body = byteArrayOf(
            0x42,
            0,
            0x28,
            0x40,
            1,
            0x80.toByte(),
            0x80.toByte(),
            0x80.toByte(),
            0x80.toByte(),
            0x10,
        )
        val codeSection = codeSection(body)
        val module = Module.decode(WASM_HEADER + typeSection + functionSection + memorySection + codeSection)

        val load = assertIs<Load>(module.functions.single().body.last())
        assertEquals(1, load.memoryIndex)
        assertEquals(0x1_0000_0000uL, load.offset)
    }

    @Test
    fun configuredLimitsAreCheckedBeforeLargeStructuralAllocation() {
        val body = ByteArray(16) { 0x01 }
        val failure = assertFailsWith<LimitExceeded> {
            Module.decode(
                moduleWithFunction(body),
                ModuleValidationLimits(maxFunctionBodySizeBytes = 8),
            )
        }

        assertEquals("function body size", failure.limit)
        assertTrue(failure.actual > failure.maximum)
    }

    @Test
    fun malformedMutabilityAndSharedLimitsAreDecodeErrors() {
        val badGlobal =
            WASM_HEADER + section(6, byteArrayOf(1, 0x7F, 2, 0x41, 0, 0x0B))
        assertFailsWith<WasmDecodeException> { Module.decode(badGlobal) }

        val sharedWithoutMaximum =
            WASM_HEADER + section(5, byteArrayOf(1, 2, 1))
        assertFailsWith<WasmDecodeException> { Module.decode(sharedWithoutMaximum) }
    }

    @Test
    fun recursiveGcTypesAndInstructionsDecodeValidateAndExecute(): Unit = runBlocking {
        val typeSection = section(
            1,
            byteArrayOf(
                1, // one recursion group
                0x4E, 2, // rec with two definitions
                0x5F, 1, 0x7F, 0, // struct { immutable i32 }
                0x60, 0, 1, 0x7F, // () -> i32
            ),
        )
        val functionSection = section(3, byteArrayOf(1, 1))
        val code = codeSection(
            byteArrayOf(
                0x41, 42,
                0xFB.toByte(), 0, 0, // struct.new type 0
                0xFB.toByte(), 2, 0, 0, // struct.get type 0 field 0
            ),
        )
        val module = Module.decode(WASM_HEADER + typeSection + functionSection + code)

        assertEquals(2, module.types.size)
        assertIs<StructType>(module.types[0])
        assertIs<FuncType>(module.types[1])
        assertEquals(listOf(Value.I32(42)), Interpreter().invoke(Instance(module), 0))
    }

    @Test
    fun standardizedAndLegacyExceptionEncodingsDecodeAndExecute(): Unit = runBlocking {
        val types = section(
            1,
            byteArrayOf(
                2,
                0x60, 1, 0x7F, 0,
                0x60, 0, 1, 0x7F,
            ),
        )
        val functions = section(3, byteArrayOf(1, 1))
        val tags = section(13, byteArrayOf(1, 0, 0))
        val standardized = codeSection(
            byteArrayOf(
                0x1F, 0x7F,
                1, 0, 0, 0,
                0x41, 42,
                0x08, 0,
                0x0B,
            ),
        )
        val legacy = codeSection(
            byteArrayOf(
                0x06, 0x7F,
                0x41, 23,
                0x08, 0,
                0x07, 0,
                0x0B,
            ),
        )

        val standardizedModule = Module.decode(WASM_HEADER + types + functions + tags + standardized)
        val legacyModule = Module.decode(WASM_HEADER + types + functions + tags + legacy)
        assertEquals(
            listOf(Value.I32(42)),
            Interpreter().invoke(Instance(standardizedModule), 0),
        )
        assertEquals(listOf(Value.I32(23)), Interpreter().invoke(Instance(legacyModule), 0))
    }

    @Test
    fun structuredInstructionsUseBoundedHeapFramesInsteadOfTheHostStack() {
        val acceptedDepth = 128
        val acceptedBody =
            ByteArray(acceptedDepth * 2) { index ->
                if (index % 2 == 0) 0x02 else 0x40
            } + ByteArray(acceptedDepth) { 0x0B }
        Module.decode(
            moduleWithFunction(acceptedBody),
            ModuleValidationLimits(maxControlNesting = acceptedDepth.toLong()),
        )

        val rejectedDepth = 513
        val rejectedBody =
            ByteArray(rejectedDepth * 2) { index ->
                if (index % 2 == 0) 0x02 else 0x40
            } + ByteArray(rejectedDepth) { 0x0B }
        val failure = assertFailsWith<LimitExceeded> {
            Module.decode(
                moduleWithFunction(rejectedBody),
                ModuleValidationLimits(maxControlNesting = 512),
            )
        }
        assertEquals("control nesting", failure.limit)
        assertEquals(513, failure.actual)
    }

    @Test
    fun oversizedPrefixedOpcodeIsAPositionedDecodeError() {
        val failure = assertFailsWith<WasmDecodeException> {
            Module.decode(
                moduleWithFunction(
                    byteArrayOf(
                        0xFB.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0x0F,
                    ),
                ),
            )
        }

        assertTrue(failure.offset > 8)
        assertTrue(failure.message.orEmpty().contains("runtime integer range"))
    }

    @Test
    fun constantExpressionInstructionSetIsAValidationRule() {
        val globalWithNopInitializer =
            WASM_HEADER + section(
                6,
                byteArrayOf(
                    1,
                    0x7F,
                    0,
                    0x01,
                    0x0B,
                ),
            )

        assertFailsWith<InvalidModule> {
            Module.decode(globalWithNopInitializer)
        }
    }

    @Test
    fun dataCountConsistencyAndTagAttributesAreBinaryGrammarRules() {
        assertFailsWith<WasmDecodeException> {
            Module.decode(
                WASM_HEADER +
                    section(12, byteArrayOf(1)) +
                    section(11, byteArrayOf(0)),
            )
        }
        assertFailsWith<WasmDecodeException> {
            Module.decode(
                WASM_HEADER + section(13, byteArrayOf(1, 1, 0)),
            )
        }
    }

    @Test
    fun missingDataCountIsAValidationError() {
        assertFailsWith<InvalidModule> {
            Module.decode(
                moduleWithFunction(
                    byteArrayOf(0xFC.toByte(), 8, 0, 0),
                ),
            )
        }
    }

    @Test
    fun missingDataCountForOtherwiseValidDataIndexIsMalformed() {
        val bytes = WASM_HEADER +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(1, 0)) +
            section(5, byteArrayOf(1, 0, 1)) +
            codeSection(byteArrayOf(0xFC.toByte(), 9, 0)) +
            section(11, byteArrayOf(1, 1, 1, 0x37))

        assertFailsWith<WasmDecodeException> { Module.decode(bytes) }
    }

    @Test
    fun wasm32LimitsUseTheWasm3U64GrammarBeforeValidation() {
        val tooLargeForWasm32 = 0x1_0000_0000uL
        val memorySection =
            section(5, byteArrayOf(1, 0) + u64(tooLargeForWasm32))

        assertFailsWith<ValidationException> {
            Module.decode(WASM_HEADER + memorySection)
        }
    }

    @Test
    fun syntacticallyValidIndicesBeyondTheRuntimeCarrierAreValidationLimits() {
        val failure = assertFailsWith<LimitExceeded> {
            Module.decode(
                moduleWithFunction(
                    byteArrayOf(0x10) + u32(UInt.MAX_VALUE),
                ),
            )
        }

        assertEquals("runtime index", failure.limit)
        assertTrue(failure.location.orEmpty().contains("binary byte"))
    }

    private fun wasm(unknownSection: Boolean = false): ByteArray =
        if (unknownSection) WASM_HEADER + byteArrayOf(14, 0) else WASM_HEADER.copyOf()

    private fun moduleWithFunction(body: ByteArray): ByteArray =
        WASM_HEADER +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(1, 0)) +
            codeSection(body)

    private fun codeSection(instructions: ByteArray): ByteArray {
        val functionBody = byteArrayOf(0) + instructions + byteArrayOf(0x0B)
        return section(10, byteArrayOf(1) + u32(functionBody.size.toUInt()) + functionBody)
    }

    private fun section(id: Int, payload: ByteArray): ByteArray =
        byteArrayOf(id.toByte()) + u32(payload.size.toUInt()) + payload

    private fun u32(value: UInt): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            var byte = (remaining and 0x7Fu).toInt()
            remaining = remaining shr 7
            if (remaining != 0u) byte = byte or 0x80
            bytes += byte.toByte()
        } while (remaining != 0u)
        return bytes.toByteArray()
    }

    private fun u64(value: ULong): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            var byte = (remaining and 0x7FuL).toInt()
            remaining = remaining shr 7
            if (remaining != 0uL) byte = byte or 0x80
            bytes += byte.toByte()
        } while (remaining != 0uL)
        return bytes.toByteArray()
    }

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
