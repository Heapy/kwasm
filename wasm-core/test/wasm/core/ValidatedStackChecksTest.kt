package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidatedStackChecksTest {
    @Test
    fun typedAccessorsAssertTagsExactlyWhenChecksAreEnabled() {
        val cases = listOf<Pair<Value, (RuntimeValueStack) -> Unit>>(
            Value.I64(1) to { it.getI32(0) },
            Value.I64(1) to { it.removeLastI32() },
            Value.I32(1) to { it.removeLastI64() },
            Value.F64(1.0) to { it.removeLastF32() },
            Value.F32(1.0f) to { it.removeLastF64() },
            Value.Ref.Host(Any()) to { it.removeLastNumericBits() },
        )

        for ((value, access) in cases) {
            val stack = RuntimeValueStack(initialCapacity = 0)
            stack.addLast(value)

            if (CHECK_VALIDATED_TYPED_STACK_TAGS) {
                assertFailsWith<IllegalStateException> { access(stack) }
                assertEquals(listOf(value), stack.toList())
            } else {
                access(stack)
            }
        }
    }

    @Test
    fun typedAccessorsAssertStackHeightExactlyWhenChecksAreEnabled() {
        val stack = RuntimeValueStack(initialCapacity = 0)

        if (CHECK_VALIDATED_TYPED_STACK_TAGS) {
            assertFailsWith<IllegalStateException> { stack.removeLastI32() }
            assertFailsWith<IllegalStateException> { stack.removeLastNumericBits() }
            assertFailsWith<IllegalStateException> { stack.getI32(0) }
        }

        stack.addLastI32(7)
        assertEquals(7, stack.getI32(0))
        assertEquals(7, stack.removeLastI32())
    }
}
