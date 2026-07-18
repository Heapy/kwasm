package io.heapy.kwasm.bindgen

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-compiled Kotlin/Wasm compatibility corpus required by TCK-5.
 *
 * CI compiles this wasmWasi-only suite with the current and previous stable Kotlin
 * compilers, once with each supported exception encoding. The resulting
 * wasmWasi executables are then decoded and executed by kwasm on the JVM.
 */
public class KotlinWasmCompatibilityCorpusTest {
    @Test
    public fun wasiHelloWorldAndStdlibSmoke() {
        println(HELLO_MARKER)

        val words = listOf("Kotlin", "Wasm", "coroutines", "snapshots")
        val indexedLengths = words
            .associateWith(String::length)
            .entries
            .sortedByDescending { it.value }
            .joinToString(separator = "|") { (word, length) -> "$word:$length" }
        assertEquals(
            "coroutines:10|snapshots:9|Kotlin:6|Wasm:4",
            indexedLengths,
        )

        val unicode = "κotlin/wasm \uD83D\uDE80"
        assertEquals(unicode, unicode.encodeToByteArray().decodeToString())
        assertTrue(unicode.uppercase().startsWith("ΚOTLIN"))

        assertEquals(32_896, recursiveSum(256))
    }

    @Test
    public fun kotlinxSerializationJsonRoundTripInsideGuest() {
        val input = WasmCompilerProfile(
            name = "guest-\u03BAotlin",
            score = 42,
        )
        val encoded = Json.encodeToString(input)
        assertEquals(input, Json.decodeFromString<WasmCompilerProfile>(encoded))
    }

    @Test
    public fun exceptionHeavySampleExercisesCatchRethrowAndFinally() {
        var finallyVisits = 0

        fun descend(depth: Int): Int =
            try {
                if (depth == 0) throw CompatibilityCorpusException(37)
                descend(depth - 1) + 1
            } catch (failure: CompatibilityCorpusException) {
                if (depth == 5) {
                    failure.code + depth
                } else {
                    throw failure
                }
            } finally {
                finallyVisits += 1
            }

        assertEquals(49, descend(12))
        assertEquals(13, finallyVisits)
    }

    private fun recursiveSum(value: Int): Int =
        if (value == 0) 0 else value + recursiveSum(value - 1)

    private class CompatibilityCorpusException(
        val code: Int,
    ) : Exception("compatibility corpus exception $code")

    public companion object {
        public const val HELLO_MARKER: String = "KWASM_KOTLIN_WASM_WASI_HELLO"
    }
}
