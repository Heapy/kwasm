package io.heapy.kwasm.benchmarks

/** Execution contract for the checksum-pinned Chasm CoreMark fixture. */
internal object CoreMarkFixture {
    const val FIXED_ITERATIONS: Int = 100

    private const val ITERATIONS_POINTER_SLOT: Int = 812
    private const val VIRTUAL_RUN_MILLISECONDS: Long = 10_000L
    private const val EXPECTED_SCORE: Float = 10f

    fun configureIterations(
        readInt: (Int) -> Int,
        writeInt: (Int, Int) -> Unit,
    ) {
        val iterationsPointer = readInt(ITERATIONS_POINTER_SLOT)
        check(iterationsPointer > 0) {
            "CoreMark fixture has an invalid iterations pointer $iterationsPointer"
        }
        check(readInt(iterationsPointer) == 0) {
            "CoreMark fixture iterations must be zero before benchmark setup"
        }
        writeInt(iterationsPointer, FIXED_ITERATIONS)
        check(readInt(iterationsPointer) == FIXED_ITERATIONS) {
            "CoreMark fixture iterations were not configured"
        }
    }

    fun requireValidScore(score: Float): Float {
        check(score == EXPECTED_SCORE) {
            "CoreMark fixture failed validation: expected score $EXPECTED_SCORE, got $score"
        }
        return score
    }

    class Clock {
        private var callCount: Long = 0

        fun readMilliseconds(): Long =
            (callCount * VIRTUAL_RUN_MILLISECONDS).also { callCount += 1 }
    }
}
