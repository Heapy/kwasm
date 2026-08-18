package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertTrue

class InterpreterHotLoopSelectionNativeTest {
    @Test
    fun nativeSelectsHoistedLinearHotLoop() {
        assertTrue(USE_HOISTED_LINEAR_HOT_LOOP)
    }
}
