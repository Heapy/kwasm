package io.heapy.kwasm

/** Compile-time selector for the JVM-only two-slot expression plan. */
internal expect val USE_TWO_SLOT_I32_EXPRESSION_PLAN: Boolean

internal expect inline fun hasPlannedTwoSlotI32Expression(
    body: List<Instr>,
    start: Int,
): Boolean

internal expect inline fun executeTwoSlotI32ExpressionPlan(
    control: GuestControlFrame,
    locals: RuntimeValueStack,
    localsBase: Int,
    body: List<Instr>,
    pc: Int,
    executeBinary: (instructionIndex: Int, opcode: Int, left: Int, right: Int) -> Int,
): Boolean
