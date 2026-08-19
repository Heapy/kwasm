package io.heapy.kwasm

internal actual const val USE_TWO_SLOT_I32_EXPRESSION_PLAN: Boolean = true

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun hasPlannedTwoSlotI32Expression(
    body: List<Instr>,
    start: Int,
): Boolean = hasManagedTwoSlotI32Expression(body, start)

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun executeTwoSlotI32ExpressionPlan(
    control: GuestControlFrame,
    locals: RuntimeValueStack,
    localsBase: Int,
    body: List<Instr>,
    pc: Int,
    executeBinary: (instructionIndex: Int, opcode: Int, left: Int, right: Int) -> Int,
): Boolean = executeManagedTwoSlotI32ExpressionPlan(
    control,
    locals,
    localsBase,
    body,
    pc,
    executeBinary,
)

private fun hasManagedTwoSlotI32Expression(body: List<Instr>, start: Int): Boolean =
    (body.getOrNull(start) as? Instr.FcIndex)?.opcode == 0x20 &&
        body.getOrNull(start + 1).isManagedI32Producer() &&
        (body.getOrNull(start + 2) as? Instr.Simple)?.opcode?.isManagedI32Binary() == true &&
        body.getOrNull(start + 3).isManagedI32Producer() &&
        (body.getOrNull(start + 4) as? Instr.Simple)?.opcode?.isManagedI32Binary() == true &&
        (body.getOrNull(start + 5) as? Instr.FcIndex)?.opcode == 0x21

private inline fun executeManagedTwoSlotI32ExpressionPlan(
    control: GuestControlFrame,
    locals: RuntimeValueStack,
    localsBase: Int,
    body: List<Instr>,
    pc: Int,
    executeBinary: (instructionIndex: Int, opcode: Int, left: Int, right: Int) -> Int,
): Boolean {
    val first = body[pc] as Instr.FcIndex
    var result = executeBinary(
        pc + 2,
        (body[pc + 2] as Instr.Simple).opcode,
        locals.getI32(localsBase + first.index),
        managedI32ProducerValue(body[pc + 1], locals, localsBase),
    )
    result = executeBinary(
        pc + 4,
        (body[pc + 4] as Instr.Simple).opcode,
        result,
        managedI32ProducerValue(body[pc + 3], locals, localsBase),
    )
    val target = body[pc + 5] as Instr.FcIndex
    locals.setI32(localsBase + target.index, result)
    return true
}

private fun Instr?.isManagedI32Producer(): Boolean =
    this is Instr.I32Const || this is Instr.FcIndex && opcode == 0x20

private fun Int.isManagedI32Binary(): Boolean =
    this in 0x46..0x4F || this in 0x6A..0x78

private fun managedI32ProducerValue(
    instruction: Instr,
    locals: RuntimeValueStack,
    localsBase: Int,
): Int = when (instruction) {
    is Instr.I32Const -> instruction.value
    is Instr.FcIndex -> locals.getI32(localsBase + instruction.index)
    else -> error("opcode 0x${instruction.opcode.toString(16)} is not an i32 producer")
}
