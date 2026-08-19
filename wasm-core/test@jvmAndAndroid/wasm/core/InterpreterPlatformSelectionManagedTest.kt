package io.heapy.kwasm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterpreterPlatformSelectionManagedTest {
    @Test
    fun managedTargetsKeepOriginalInterpreterPaths() {
        assertFalse(USE_HOISTED_LINEAR_HOT_LOOP)
        assertFalse(USE_FLAT_DIRECT_CALL_METADATA)
        assertTrue(USE_TWO_SLOT_I32_EXPRESSION_PLAN)
    }
}
