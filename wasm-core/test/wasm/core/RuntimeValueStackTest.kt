package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RuntimeValueStackTest {
    @Test
    fun selectKeepsReferenceIdentity() {
        val first = Value.Ref.Host(Any())
        val second = Value.Ref.Host(Any())

        val keepFirst = RuntimeValueStack()
        keepFirst.addLast(first)
        keepFirst.addLast(second)
        keepFirst.selectLast(keepFirst = true)

        val keepSecond = RuntimeValueStack()
        keepSecond.addLast(first)
        keepSecond.addLast(second)
        keepSecond.selectLast(keepFirst = false)

        assertEquals(1, keepFirst.size)
        assertEquals(1, keepSecond.size)
        assertSame(first, keepFirst.last())
        assertSame(second, keepSecond.last())
    }

    @Test
    fun selectPreservesTheNanPayload() {
        val signalling = Float.fromBits(0x7F80_0001.toInt())
        val quiet = Float.fromBits(0x7FC0_1234)

        val stack = RuntimeValueStack()
        stack.addLastF32(signalling)
        stack.addLastF32(quiet)
        stack.selectLast(keepFirst = true)

        assertEquals(signalling.toRawBits(), stack.removeLastF32().toRawBits())
    }

    @Test
    fun numericBitsPopReturnsTheStoredRawBits() {
        val stack = RuntimeValueStack()
        stack.addLastF64(Double.fromBits(0x7FF8_0000_0000_ABCDuL.toLong()))
        stack.addLastI32(Int.MIN_VALUE)

        assertEquals(Int.MIN_VALUE, stack.removeLastNumericBits().toInt())
        assertEquals(0x7FF8_0000_0000_ABCDuL.toLong(), stack.removeLastNumericBits())
        assertEquals(0, stack.size)
    }

    @Test
    fun numericBitsPopRejectsAReferenceSlot() {
        val stack = RuntimeValueStack()
        stack.addLast(Value.Ref.Host(Any()))

        if (CHECK_VALIDATED_TYPED_STACK_TAGS) {
            assertFailsWith<IllegalStateException> { stack.removeLastNumericBits() }
        }
    }

    @Test
    fun dropLastReleasesExactlyTheRequestedSlots() {
        val stack = RuntimeValueStack()
        val kept = Value.Ref.Host(Any())
        stack.addLast(kept)
        stack.addLastI32(1)
        stack.addLast(Value.Ref.Host(Any()))
        stack.addLastI64(2)

        stack.dropLast(3)

        assertEquals(1, stack.size)
        assertSame(kept, stack.last())
        assertFailsWith<IllegalStateException> { stack.dropLast(2) }
    }
}
