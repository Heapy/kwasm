package io.heapy.kwasm.tck

import io.heapy.kwasm.Module
import io.heapy.kwasm.ValidationException
import io.heapy.kwasm.WasmDecodeException

public data class DecoderFuzzReport(
    public val seed: ULong,
    public val iterations: Int,
    public val decoded: Int,
    public val rejectedAtDecode: Int,
    public val rejectedAtValidation: Int,
)

/** Deterministic raw-byte smoke fuzzer suitable for every KMP test target. */
public object DecoderFuzzDriver {
    public fun run(
        seed: ULong = 0x4B5741534DUL,
        iterations: Int = 1_000,
        maximumInputBytes: Int = 4_096,
        onUnexpectedFailure: (Int, ByteArray, Throwable) -> Unit = { _, _, _ -> },
    ): DecoderFuzzReport {
        require(iterations >= 0) { "iterations must not be negative" }
        require(maximumInputBytes >= 0) { "maximumInputBytes must not be negative" }
        require(maximumInputBytes < Int.MAX_VALUE) {
            "maximumInputBytes must leave room for the inclusive length bound"
        }
        val random = SplitMix64(seed)
        var decoded = 0
        var decodeFailures = 0
        var validationFailures = 0
        repeat(iterations) { iteration ->
            val length = if (maximumInputBytes == 0) 0 else
                (random.nextULong() % (maximumInputBytes + 1).toULong()).toInt()
            val bytes = ByteArray(length) { random.nextULong().toByte() }
            // Regularly retain the magic/version prefix, reaching deeper parsers.
            if (iteration % 4 == 0 && length >= 8) {
                byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
                    .copyInto(bytes)
            }
            try {
                Module.decode(bytes)
                decoded++
            } catch (_: WasmDecodeException) {
                decodeFailures++
            } catch (_: ValidationException) {
                validationFailures++
            } catch (failure: Exception) {
                onUnexpectedFailure(iteration, bytes.copyOf(), failure)
                throw failure
            }
            // Errors are intentionally allowed to escape without attempting
            // evidence allocation in a potentially exhausted process.
        }
        return DecoderFuzzReport(seed, iterations, decoded, decodeFailures, validationFailures)
    }

    /** Mutation mode keeps more structurally valid bytes from a seed corpus. */
    public fun mutate(
        corpus: List<ByteArray>,
        seed: ULong = 0x4D5554415445UL,
        iterations: Int = 1_000,
        maximumMutations: Int = 8,
        target: (ByteArray) -> Unit = { Module.decode(it) },
    ) {
        require(corpus.isNotEmpty()) { "mutation corpus must not be empty" }
        require(maximumMutations > 0) { "maximumMutations must be positive" }
        val random = SplitMix64(seed)
        repeat(iterations) {
            val source = corpus[(random.nextULong() % corpus.size.toULong()).toInt()]
            val mutated = source.copyOf()
            val mutationCount = 1 + (random.nextULong() % maximumMutations.toULong()).toInt()
            repeat(mutationCount) {
                if (mutated.isNotEmpty()) {
                    val index = (random.nextULong() % mutated.size.toULong()).toInt()
                    mutated[index] = (mutated[index].toInt() xor (1 shl (random.nextULong() % 8u).toInt())).toByte()
                }
            }
            try {
                target(mutated)
            } catch (_: WasmDecodeException) {
                // Expected rejection.
            } catch (_: ValidationException) {
                // Expected rejection.
            }
        }
    }
}

public data class DifferentialInvocation(
    public val export: String,
    public val arguments: List<String> = emptyList(),
    /** Scalar result tags used to parse a reference CLI's line-oriented output. */
    public val resultTypes: List<String> = emptyList(),
)

/** Normalized engine outcome; adapters preserve raw numeric bit strings. */
public sealed class DifferentialResult {
    public data class Returned(public val values: List<String>) : DifferentialResult()
    public data class Trapped(public val kind: String) : DifferentialResult()
    public data class Rejected(public val phase: String) : DifferentialResult()

    /**
     * The engine declined to produce a comparable outcome for this invocation.
     * Abstained results are excluded from divergence detection in
     * [DifferentialDriver.compare], so an abstained engine can never be the
     * sole cause of a divergence. Example: wasm3 prints floats via lossy
     * `%.7g`/`%.15g` formatting, so it abstains from f32/f64 invocations.
     */
    public data class Abstained(public val reason: String) : DifferentialResult()
}

public fun interface DifferentialEngine {
    public suspend fun execute(module: ByteArray, invocation: DifferentialInvocation): DifferentialResult
}

public data class DifferentialDivergence(
    public val module: ByteArray,
    public val invocation: DifferentialInvocation,
    public val outcomes: Map<String, DifferentialResult>,
) {
    override fun equals(other: Any?): Boolean = other is DifferentialDivergence &&
        module.contentEquals(other.module) && invocation == other.invocation && outcomes == other.outcomes

    override fun hashCode(): Int = 31 * module.contentHashCode() + 31 * invocation.hashCode() + outcomes.hashCode()
}

/** Adapter-level driver for nightly wasm-smith/reference-engine differential jobs. */
public class DifferentialDriver(private val engines: Map<String, DifferentialEngine>) {
    init {
        require(engines.size >= 2) { "differential execution needs at least two engines" }
    }

    public suspend fun compare(
        module: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialDivergence? {
        val results = linkedMapOf<String, DifferentialResult>()
        engines.forEach { (name, engine) -> results[name] = engine.execute(module, invocation) }
        // Abstained engines opt out of a specific invocation (e.g. wasm3 on
        // float results); they are excluded from divergence detection so a
        // single abstained engine can never be the sole cause of a finding.
        val comparable = results.filterValues { it !is DifferentialResult.Abstained }
        return if (comparable.values.distinct().size <= 1) null
        else DifferentialDivergence(module.copyOf(), invocation, results)
    }

    /** Simple delta-debugging pass used before persisting a divergent corpus case. */
    public suspend fun minimize(
        input: ByteArray,
        stillDiverges: suspend (ByteArray) -> Boolean,
    ): ByteArray {
        var candidate = input.copyOf()
        var chunk = candidate.size / 2
        while (chunk > 0) {
            var offset = 0
            var reduced = false
            while (offset < candidate.size) {
                val end = minOf(candidate.size, offset + chunk)
                val attempt = candidate.copyOfRange(0, offset) + candidate.copyOfRange(end, candidate.size)
                if (stillDiverges(attempt)) {
                    candidate = attempt
                    reduced = true
                    break
                }
                offset += chunk
            }
            if (!reduced) chunk /= 2
        }
        return candidate
    }
}

/** Generic snapshot-continuation property seam shared by core and snapshot fuzz jobs. */
public interface ContinuationProperty<State, Snapshot, Result> {
    public suspend fun start(): State
    public suspend fun runSteps(state: State, steps: Int): State
    public suspend fun snapshot(state: State): Snapshot
    public suspend fun restore(snapshot: Snapshot): State
    public suspend fun finish(state: State): Result
}

public object ContinuationPropertyDriver {
    /** Run interrupted and uninterrupted executions and require identical outcomes. */
    public suspend fun <State, Snapshot, Result> check(
        property: ContinuationProperty<State, Snapshot, Result>,
        stepsBeforeSnapshot: Int,
    ): Pair<Result, Result> {
        require(stepsBeforeSnapshot >= 0) { "stepsBeforeSnapshot must not be negative" }
        val uninterrupted = property.finish(property.start())
        val prefix = property.runSteps(property.start(), stepsBeforeSnapshot)
        val resumed = property.finish(property.restore(property.snapshot(prefix)))
        check(uninterrupted == resumed) {
            "snapshot continuation diverged: uninterrupted=$uninterrupted resumed=$resumed"
        }
        return uninterrupted to resumed
    }
}

internal class SplitMix64(seed: ULong) {
    private var state: ULong = seed

    fun nextULong(): ULong {
        state += 0x9E3779B97F4A7C15UL
        var value = state
        value = (value xor (value shr 30)) * 0xBF58476D1CE4E5B9UL
        value = (value xor (value shr 27)) * 0x94D049BB133111EBUL
        return value xor (value shr 31)
    }
}
