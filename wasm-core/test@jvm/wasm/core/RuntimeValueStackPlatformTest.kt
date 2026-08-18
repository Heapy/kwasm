package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeValueStackPlatformTest {
    @Test
    fun jvmKeepsValidatedTypedTagAssertions() {
        assertTrue(CHECK_VALIDATED_TYPED_STACK_TAGS)

        assertCheckedMismatch(Value.I64(1)) { it.getI32(0) }
        assertCheckedMismatch(Value.I64(1)) { it.removeLastI32() }
        assertCheckedMismatch(Value.I32(1)) { it.removeLastI64() }
        assertCheckedMismatch(Value.F64(1.0)) { it.removeLastF32() }
        assertCheckedMismatch(Value.F32(1.0f)) { it.removeLastF64() }
    }

    private fun assertCheckedMismatch(
        value: Value,
        access: (RuntimeValueStack) -> Unit,
    ) {
        val stack = RuntimeValueStack(initialCapacity = 0)
        stack.addLast(value)

        assertFailsWith<IllegalStateException> { access(stack) }
        assertEquals(listOf(value), stack.toList())
    }
}
