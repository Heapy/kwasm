package io.heapy.kwasm

internal actual const val USE_TWO_SLOT_I32_EXPRESSION_PLAN: Boolean = false

@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal actual inline fun hasPlannedTwoSlotI32Expression(
    body: List<Instr>,
    start: Int,
): Boolean = false

@Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
internal actual inline fun executeTwoSlotI32ExpressionPlan(
    control: GuestControlFrame,
    locals: RuntimeValueStack,
    localsBase: Int,
    body: List<Instr>,
    pc: Int,
    executeBinary: (instructionIndex: Int, opcode: Int, left: Int, right: Int) -> Int,
): Boolean = false
