package io.heapy.kwasm.wat

import kotlin.test.Test
import kotlin.test.assertContentEquals

class WatComposerTest {
    @Test
    fun resolvesNamedLabelsInFoldedControlInstructions() {
        val named = WatComposer.compose(
            """
            (module
              (func (export "run")
                (block ${'$'}done
                  (loop ${'$'}again
                    i32.const 1
                    br_if ${'$'}done
                    br ${'$'}again))))
            """.trimIndent(),
        )
        val numeric = WatComposer.compose(
            """
            (module
              (func (export "run")
                (block
                  (loop
                    i32.const 1
                    br_if 1
                    br 0))))
            """.trimIndent(),
        )

        assertContentEquals(numeric, named)
    }

    @Test
    fun resolvesNamedLabelsInUnfoldedControlInstructions() {
        val named = WatComposer.compose(
            """
            (module
              (func (export "run")
                block ${'$'}done
                  loop ${'$'}again
                    i32.const 1
                    br_if ${'$'}done
                    br ${'$'}again
                  end
                end))
            """.trimIndent(),
        )
        val numeric = WatComposer.compose(
            """
            (module
              (func (export "run")
                block
                  loop
                    i32.const 1
                    br_if 1
                    br 0
                  end
                end))
            """.trimIndent(),
        )

        assertContentEquals(numeric, named)
    }

    @Test
    fun resolvesNamedParametersAndLocals() {
        val named = WatComposer.compose(
            """
            (module
              (func (export "add") (param ${'$'}left i32) (param ${'$'}right i32) (result i32)
                (local ${'$'}sum i32)
                local.get ${'$'}left
                local.get ${'$'}right
                i32.add
                local.tee ${'$'}sum))
            """.trimIndent(),
        )
        val numeric = WatComposer.compose(
            """
            (module
              (func (export "add") (param i32 i32) (result i32)
                (local i32)
                local.get 0
                local.get 1
                i32.add
                local.tee 2))
            """.trimIndent(),
        )

        assertContentEquals(numeric, named)
    }
}
