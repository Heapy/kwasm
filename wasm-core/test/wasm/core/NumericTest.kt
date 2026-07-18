package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NumericTest {
    @Test
    fun nearestUsesTiesToEvenAndPreservesNegativeZero() {
        assertEquals(2f, roundNearest(2.5f))
        assertEquals(4f, roundNearest(3.5f))
        assertEquals((-0.0f).toRawBits(), roundNearest(-0.25f).toRawBits())

        assertEquals(2.0, roundNearestDouble(2.5))
        assertEquals(4.0, roundNearestDouble(3.5))
        assertEquals((-0.0).toRawBits(), roundNearestDouble(-0.25).toRawBits())
    }

    @Test
    fun roundingOperationsQuietSignalingNans() {
        val signalingF32 = Float.fromBits(0x7FA00000)
        val signalingF64 = Double.fromBits(0x7FF4000000000000L)

        assertEquals(0x7FE00000, truncF(signalingF32).toRawBits())
        assertEquals(0x7FE00000, roundNearest(signalingF32).toRawBits())
        assertEquals(0x7FFC000000000000L, truncF(signalingF64).toRawBits())
        assertEquals(0x7FFC000000000000L, roundNearestDouble(signalingF64).toRawBits())
    }

    @Test
    fun trappingFloatConversionsHonorExactIntegerDomains() {
        assertEquals(Long.MIN_VALUE, truncF32ToI64S(-9_223_372_036_854_775_808f))
        assertEquals(
            TrapKind.INTEGER_OVERFLOW,
            assertFailsWith<ExecutionTrap> {
                truncF32ToI64S(9_223_372_036_854_775_808f)
            }.kind,
        )
        assertEquals(
            TrapKind.INVALID_CONVERSION_TO_INTEGER,
            assertFailsWith<ExecutionTrap> { truncF64ToI32S(Double.NaN) }.kind,
        )
        assertEquals(0, truncF32ToI32U(-Float.MIN_VALUE))
        assertEquals(0, truncF64ToI32U(-0.9))
        assertEquals(Int.MIN_VALUE, truncF64ToI32S(-2_147_483_648.9))
        assertEquals(0L, truncF32ToI64U(-0.9f))
        assertEquals(0L, truncF64ToI64U(-0.9))
        assertEquals(-2_048L, truncF64ToI64U(18_446_744_073_709_549_568.0))
        assertFailsWith<ExecutionTrap> {
            truncF64ToI64U(18_446_744_073_709_551_616.0)
        }
    }

    @Test
    fun unsignedI64ToF32RoundsDirectlyToNearestEven() {
        assertEquals(
            0x5A000001,
            unsignedLongBitsToFloat(0x0020000020000001L).toRawBits(),
        )
        assertEquals(
            0x5EFFFFFF,
            unsignedLongBitsToFloat(0x7FFFFFBFFFFFFFFFL).toRawBits(),
        )
        assertEquals(
            0x5F000001,
            unsignedLongBitsToFloat(0x8000008000000001UL.toLong()).toRawBits(),
        )
        assertEquals(
            0x5F7FFFFF,
            unsignedLongBitsToFloat(0xFFFFFE8000000001UL.toLong()).toRawBits(),
        )
    }

    @Test
    fun saturatingConversionsClampAndMapNanToZero() {
        assertEquals(0, truncSatF64ToI32S(Double.NaN))
        assertEquals(Int.MIN_VALUE, truncSatF64ToI32S(Double.NEGATIVE_INFINITY))
        assertEquals(Int.MAX_VALUE, truncSatF64ToI32S(Double.POSITIVE_INFINITY))
        assertEquals(-1, truncSatF64ToI32U(Double.POSITIVE_INFINITY))
        assertEquals(Long.MIN_VALUE, truncSatF64ToI64S(Double.NEGATIVE_INFINITY))
        assertEquals(Long.MAX_VALUE, truncSatF64ToI64S(Double.POSITIVE_INFINITY))
        assertEquals(-1L, truncSatF64ToI64U(Double.POSITIVE_INFINITY))
    }

    @Test
    fun integerDivisionAndRemainderUseWasmTrapRules() {
        assertEquals(
            TrapKind.INTEGER_DIVIDE_BY_ZERO,
            assertFailsWith<ExecutionTrap> { idiv(1, 0) }.kind,
        )
        assertEquals(
            TrapKind.INTEGER_OVERFLOW,
            assertFailsWith<ExecutionTrap> { idiv(Int.MIN_VALUE, -1) }.kind,
        )
        assertEquals(0, irem(Int.MIN_VALUE, -1))
        assertEquals(-1, udiv(-1, 1))
        assertEquals(0, urem(-1, 1))
    }

    @Test
    fun minMaxAndCopySignHandleSignedZero() {
        assertEquals((-0.0f).toRawBits(), fmin(0.0f, -0.0f).toRawBits())
        assertEquals(0.0f.toRawBits(), fmax(0.0f, -0.0f).toRawBits())
        assertEquals((-1.0).toRawBits(), copysign(1.0, -2.0).toRawBits())
    }
}
