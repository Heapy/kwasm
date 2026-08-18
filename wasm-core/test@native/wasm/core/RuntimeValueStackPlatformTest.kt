package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeValueStackPlatformTest {
    @Test
    fun nativeSkipsValidatedTypedTagAssertions() {
        assertFalse(CHECK_VALIDATED_TYPED_STACK_TAGS)

        val i32View = RuntimeValueStack(initialCapacity = 0)
        i32View.addLastI64(42)
        assertEquals(42, i32View.getI32(0))
        assertEquals(listOf(Value.I64(42)), i32View.toList())
        assertEquals(42, i32View.removeLastI32())
        assertTrue(i32View.isEmpty())

        val i64View = RuntimeValueStack(initialCapacity = 0)
        i64View.addLastI32(-1)
        assertEquals(-1L, i64View.removeLastI64())

        val f32View = RuntimeValueStack(initialCapacity = 0)
        f32View.addLastI32(1.0f.toRawBits())
        assertEquals(1.0f, f32View.removeLastF32())

        val f64View = RuntimeValueStack(initialCapacity = 0)
        f64View.addLastI64(1.0.toRawBits())
        assertEquals(1.0, f64View.removeLastF64())
    }
}
