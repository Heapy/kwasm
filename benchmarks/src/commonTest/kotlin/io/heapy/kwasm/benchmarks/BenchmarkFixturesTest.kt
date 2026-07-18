package io.heapy.kwasm.benchmarks

import io.heapy.kwasm.CheckpointMode
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Module
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BenchmarkFixturesTest {
    @Test
    fun fibFixtureExecutesTheRequiredGuestAlgorithm(): Unit = runBlocking {
        val instance = BenchmarkFixtures.instance(BenchmarkFixtures.fibModule)

        assertEquals(55, BenchmarkFixtures.invokeI32(instance, "fib", 10))
    }

    @Test
    fun shaFixtureMatchesTheHostReferenceLoop(): Unit = runBlocking {
        val rounds = 32
        val seed = 0x6A09_E667
        val instance = BenchmarkFixtures.instance(BenchmarkFixtures.sha256LoopModule)

        assertEquals(
            shaReference(rounds, seed),
            BenchmarkFixtures.invokeI32(instance, "sha256_loop", rounds, seed),
        )
    }

    @Test
    fun jsonFixtureScansTheExactEmbeddedDocument(): Unit = runBlocking {
        val instance = BenchmarkFixtures.instance(BenchmarkFixtures.jsonModule)

        assertEquals(
            jsonReference(BenchmarkFixtures.JSON_DOCUMENT),
            BenchmarkFixtures.invokeI32(
                instance,
                "parse_json",
                BenchmarkFixtures.JSON_DOCUMENT.length,
            ),
        )
    }

    @Test
    fun compiledOutEquivalentDoesNotChangeGuestResults(): Unit = runBlocking {
        val enabled = BenchmarkFixtures.instance(BenchmarkFixtures.sha256LoopModule)
        val compiledOut = BenchmarkFixtures.instance(
            BenchmarkFixtures.sha256LoopModule,
            CheckpointMode.CompiledOutEquivalent,
        )

        assertEquals(
            BenchmarkFixtures.invokeI32(enabled, "sha256_loop", 64, 7),
            BenchmarkFixtures.invokeI32(compiledOut, "sha256_loop", 64, 7),
        )
    }

    @Test
    fun startupFixtureHasTheExactRequiredEncodedSize() {
        val bytes = BenchmarkFixtures.exactSizeModule(5 * 1024 * 1024)
        val module = Module.decode(bytes)

        assertEquals(5 * 1024 * 1024, bytes.size)
        assertEquals(bytes.size, module.encodedSizeBytes)
        assertEquals(0, Instance(module).functionCount)
    }

    private fun shaReference(rounds: Int, seed: Int): Int {
        var state = seed
        repeat(rounds) { round ->
            val sigma0 = rotateRight(state, 2) xor
                rotateRight(state, 13) xor
                rotateRight(state, 22)
            val sigma1 = rotateRight(state, 6) xor
                rotateRight(state, 11) xor
                rotateRight(state, 25)
            state = sigma0 + sigma1 + round + 0x428A_2F98
        }
        return state
    }

    private fun rotateRight(value: Int, bits: Int): Int =
        (value ushr bits) or (value shl (32 - bits))

    private fun jsonReference(document: String): Int {
        var depth = 0
        var quotes = 0
        var checksum = 0
        document.forEach { character ->
            checksum = checksum * 31 xor character.code
            when (character) {
                '{', '[' -> depth += 1
                '}', ']' -> depth -= 1
                '"' -> quotes += 1
            }
        }
        return checksum xor depth xor quotes
    }
}
