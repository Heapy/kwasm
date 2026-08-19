package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertFalse

class InterpreterHotLoopSelectionJvmTest {
    @Test
    fun jvmSelectsOriginalLinearHotLoop() {
        assertFalse(USE_HOISTED_LINEAR_HOT_LOOP)
        assertFalse(USE_FLAT_DIRECT_CALL_METADATA)
    }
}
