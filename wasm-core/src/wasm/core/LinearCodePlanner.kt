package io.heapy.kwasm

import io.heapy.kwasm.binary.FrozenCodeCache
import io.heapy.kwasm.binary.FrozenInstructions
import io.heapy.kwasm.binary.Instr

/**
 * Immutable dispatch metadata for one instruction body. [packedInstructions]
 * is absent when the body exceeds the cache budget or is too sparse; the
 * interpreter then uses the original instruction objects while retaining the
 * independently useful super-instruction [plan].
 */
internal class LinearHotCode(
    val packedInstructions: LongArray?,
    val plan: ByteArray,
)

internal class LinearHotCodeCache(
    val owner: LinearCodePlanner,
    val code: LinearHotCode,
) : FrozenCodeCache

internal class PackedLinearCodeBudget(
    private val maxInstructions: Int,
    private val maxBodies: Int,
) {
    init {
        require(maxInstructions >= 0) { "maxInstructions must be non-negative" }
        require(maxBodies >= 0) { "maxBodies must be non-negative" }
    }

    var usedInstructions: Int = 0
        private set
    var usedBodies: Int = 0
        private set

    fun tryReserve(instructionCount: Int): Boolean {
        require(instructionCount >= 0) { "instructionCount must be non-negative" }
        if (usedBodies >= maxBodies) return false
        if (instructionCount > maxInstructions - usedInstructions) return false
        usedInstructions += instructionCount
        usedBodies++
        return true
    }
}

internal val EMPTY_LINEAR_HOT_CODE: LinearHotCode = LinearHotCode(null, ByteArray(0))

/**
 * Peephole planner for one instruction body.
 *
 * The plan is a pure function of the body and of compile-time flags, but the
 * packed-instruction budget is per-owner, so one planner owns both the cache
 * and the budget. A frozen body carries an inline cache entry keyed by its
 * planner; the list behind it stays authoritative, so a body is planned and
 * charged against the budget exactly once per planner.
 */
internal class LinearCodePlanner {
    private val budget =
        PackedLinearCodeBudget(
            maxInstructions = MAX_PACKED_LINEAR_INSTRUCTIONS_PER_STORE,
            maxBodies = MAX_PACKED_LINEAR_BODIES_PER_STORE,
        )
    private val plannedBodies: MutableList<Pair<List<Instr>, LinearHotCode>> = mutableListOf()
    private var lastBody: List<Instr>? = null
    private var lastCode: LinearHotCode = EMPTY_LINEAR_HOT_CODE

    fun planFor(body: List<Instr>): LinearHotCode {
        if (body is FrozenInstructions) {
            val cached = body.linearHotCodeCache as? LinearHotCodeCache
            if (cached != null && cached.owner === this) return cached.code
        }
        if (lastBody === body) return lastCode
        plannedBodies.firstOrNull { (candidate, _) -> candidate === body }
            ?.let { (_, code) -> return remember(body, code) }
        val plan = ByteArray(body.size)
        var linearHotInstructionCount = 0
        for (index in body.indices) {
            val first = body[index]
            if (first.opcode.isLinearHotDispatchOpcode()) linearHotInstructionCount++
            val second = body.getOrNull(index + 1)
            val third = body.getOrNull(index + 2)
            if (
                USE_TWO_SLOT_I32_EXPRESSION_PLAN &&
                hasPlannedTwoSlotI32Expression(body, index)
            ) {
                plan[index] = LINEAR_PLAN_TWO_SLOT_I32_EXPRESSION_SET
                continue
            }
            val expressionLength = body.i32ExpressionLengthFrom(index)
            if (expressionLength >= MIN_LINEAR_I32_EXPRESSION_LENGTH) {
                plan[index] = (LINEAR_PLAN_I32_EXPRESSION_OFFSET + expressionLength).toByte()
                continue
            }
            if (
                first is Instr.Simple &&
                first.opcode.isPlannedI32Binary() &&
                second is Instr.FcIndex &&
                second.opcode == 0x21
            ) {
                plan[index] = LINEAR_PLAN_STACK_BINARY_SET
                continue
            }
            if (
                first is Instr.I32Const &&
                second is Instr.Simple &&
                second.opcode.isPlannedI32Binary()
            ) {
                plan[index] =
                    if (third is Instr.FcIndex && third.opcode == 0x21) {
                        LINEAR_PLAN_CONST_BINARY_SET
                    } else {
                        LINEAR_PLAN_CONST_BINARY
                    }
                continue
            }
            if (first !is Instr.FcIndex || first.opcode != 0x20) continue
            if (
                second is Instr.Simple &&
                second.opcode.isPlannedI32Binary()
            ) {
                plan[index] =
                    if (third is Instr.FcIndex && third.opcode == 0x21) {
                        LINEAR_PLAN_LOCAL_BINARY_SET
                    } else {
                        LINEAR_PLAN_LOCAL_BINARY
                    }
                continue
            }
            if (
                second is Instr.Load &&
                second.opcode.isPlannedI32Load()
            ) {
                if (
                    third is Instr.Load &&
                    third.opcode.isPlannedI32Load()
                ) {
                    val fourth = body.getOrNull(index + 3)
                    plan[index] = when {
                        fourth is Instr.FcIndex && fourth.opcode == 0x21 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_SET
                        fourth is Instr.FcIndex && fourth.opcode == 0x22 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_TEE
                        else -> LINEAR_PLAN_LOCAL_I32_LOAD_LOAD
                    }
                } else {
                    plan[index] = when {
                        third is Instr.FcIndex && third.opcode == 0x21 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_SET
                        third is Instr.FcIndex && third.opcode == 0x22 ->
                            LINEAR_PLAN_LOCAL_I32_LOAD_TEE
                        else -> LINEAR_PLAN_LOCAL_I32_LOAD
                    }
                }
                continue
            }
            if (
                second !is Instr.I32Const &&
                (second !is Instr.FcIndex || second.opcode != 0x20)
            ) {
                continue
            }
            val operation = third as? Instr.Simple ?: continue
            if (operation.opcode.isPlannedI32Binary()) {
                val fourth = body.getOrNull(index + 3)
                plan[index] = when {
                    operation.opcode.isPlannedI32Comparison() && fourth is Instr.BrIf ->
                        LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF
                    fourth is Instr.FcIndex && fourth.opcode == 0x21 ->
                        if (body.getOrNull(index + 4) is Instr.Br) {
                            LINEAR_PLAN_PRODUCERS_BINARY_SET_BR
                        } else {
                            LINEAR_PLAN_PRODUCERS_BINARY_SET
                        }
                    fourth is Instr.Simple && fourth.opcode.isPlannedI32Binary() -> {
                        val fifth = body.getOrNull(index + 4)
                        when {
                            fifth is Instr.FcIndex && fifth.opcode == 0x21 ->
                                LINEAR_PLAN_PRODUCERS_BINARY_BINARY_SET
                            fifth is Instr.Simple && fifth.opcode.isPlannedI32Binary() -> {
                                val sixth = body.getOrNull(index + 5)
                                if (sixth is Instr.FcIndex && sixth.opcode == 0x21) {
                                    LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY_SET
                                } else {
                                    LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY
                                }
                            }
                            else -> LINEAR_PLAN_PRODUCERS_BINARY_BINARY
                        }
                    }
                    else -> LINEAR_PLAN_PRODUCERS_BINARY
                }
            }
        }
        val packedInstructions =
            if (
                body.shouldPackLinearHotInstructions(linearHotInstructionCount) &&
                budget.tryReserve(body.size)
            ) {
                LongArray(body.size) { index -> body[index].packLinearHotInstruction() }
            } else {
                null
            }
        val code = LinearHotCode(packedInstructions, plan)
        plannedBodies.add(body to code)
        return remember(body, code)
    }

    private fun remember(body: List<Instr>, code: LinearHotCode): LinearHotCode {
        if (body is FrozenInstructions) {
            body.linearHotCodeCache = LinearHotCodeCache(this, code)
        }
        lastBody = body
        lastCode = code
        return code
    }
}

internal const val MAX_PACKED_LINEAR_BODY_INSTRUCTIONS: Int = 65_536
// LongArray payload: at most 512 KiB per body and 2 MiB per Store. The body
// limit also bounds the aggregate array-header overhead for tiny functions.
internal const val MAX_PACKED_LINEAR_INSTRUCTIONS_PER_STORE: Int = 262_144
internal const val MAX_PACKED_LINEAR_BODIES_PER_STORE: Int = 4_096
private const val PACKED_LINEAR_HOT_DENSITY_DENOMINATOR: Int = 8

internal const val MAX_LINEAR_I32_EXPRESSION_DEPTH: Int = 8
internal const val LINEAR_PLAN_I32_EXPRESSION_OFFSET: Int = 16
private const val MIN_LINEAR_I32_EXPRESSION_LENGTH: Int = 6
private const val MAX_LINEAR_I32_EXPRESSION_LENGTH: Int =
    Byte.MAX_VALUE - LINEAR_PLAN_I32_EXPRESSION_OFFSET

internal const val LINEAR_PLAN_STACK_BINARY_SET: Byte = -2
internal const val LINEAR_PLAN_PRODUCERS_BINARY_SET: Byte = -4
internal const val LINEAR_PLAN_LOCAL_BINARY_SET: Byte = -5
internal const val LINEAR_PLAN_CONST_BINARY_SET: Byte = -6
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_SET: Byte = -7
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY_SET: Byte = -8
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_SET: Byte = -9
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_TEE: Byte = -10
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_SET: Byte = -11
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD_TEE: Byte = -12
internal const val LINEAR_PLAN_PRODUCERS_COMPARE_BR_IF: Byte = -13
internal const val LINEAR_PLAN_PRODUCERS_BINARY_SET_BR: Byte = -14
internal const val LINEAR_PLAN_TWO_SLOT_I32_EXPRESSION_SET: Byte = -15
internal const val LINEAR_PLAN_CONST_BINARY: Byte = 2
internal const val LINEAR_PLAN_PRODUCERS_BINARY: Byte = 3
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY: Byte = 4
internal const val LINEAR_PLAN_LOCAL_BINARY: Byte = 5
internal const val LINEAR_PLAN_PRODUCERS_BINARY_BINARY_BINARY: Byte = 6
internal const val LINEAR_PLAN_LOCAL_I32_LOAD: Byte = 7
internal const val LINEAR_PLAN_LOCAL_I32_LOAD_LOAD: Byte = 8

private fun Instr.packLinearHotInstruction(): Long {
    val immediate = when (this) {
        is Instr.FcIndex -> index
        is Instr.I32Const -> value
        else -> 0
    }
    return (immediate.toLong() shl 32) or opcode.toUInt().toLong()
}

private fun Int.isLinearHotDispatchOpcode(): Boolean =
    this in 0x20..0x26 ||
        this in 0x28..0xC4 ||
        this in 0x02..0x04 ||
        this in 0x0C..0x10 ||
        this in 0x1A..0x1C

private fun List<Instr>.shouldPackLinearHotInstructions(hotInstructionCount: Int): Boolean =
    size <= MAX_PACKED_LINEAR_BODY_INSTRUCTIONS &&
        hotInstructionCount > 0 &&
        (
            size <= PACKED_LINEAR_HOT_DENSITY_DENOMINATOR ||
                hotInstructionCount * PACKED_LINEAR_HOT_DENSITY_DENOMINATOR >= size
        )

private fun Int.isPlannedI32Binary(): Boolean =
    this in 0x46..0x4F || this in 0x6A..0x78

private fun Int.isPlannedI32Comparison(): Boolean = this in 0x46..0x4F

private fun Int.isPlannedI32Load(): Boolean =
    this == 0x28 || this in 0x2C..0x2F

private fun List<Instr>.i32ExpressionLengthFrom(start: Int): Int {
    var depth = 0
    val endExclusive =
        minOf(size, start + MAX_LINEAR_I32_EXPRESSION_LENGTH)
    for (index in start until endExclusive) {
        when (val instruction = this[index]) {
            is Instr.I32Const -> {
                if (depth == MAX_LINEAR_I32_EXPRESSION_DEPTH) return 0
                depth++
            }
            is Instr.FcIndex -> when (instruction.opcode) {
                0x20 -> {
                    if (depth == MAX_LINEAR_I32_EXPRESSION_DEPTH) return 0
                    depth++
                }
                0x21 -> {
                    if (depth != 1) return 0
                    return index - start + 1
                }
                else -> return 0
            }
            is Instr.Simple -> {
                if (!instruction.opcode.isPlannedI32Binary() || depth < 2) return 0
                depth--
            }
            else -> return 0
        }
    }
    return 0
}
