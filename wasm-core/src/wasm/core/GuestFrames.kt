package io.heapy.kwasm

internal enum class ControlKind {
    Function,
    Block,
    Loop,
    If,
    TryTable,
    LegacyTry,
}

internal sealed class GuestExceptionHandler {
    data class Standard(val catches: List<CatchClause>) : GuestExceptionHandler()
    data class Legacy(
        val catches: List<LegacyCatch>,
        val catchAll: List<Instr>?,
        val delegateDepth: Int?,
    ) : GuestExceptionHandler()
}

internal class GuestControlFrame(
    var kind: ControlKind,
    var body: List<Instr>,
    var pc: Int,
    var stackBase: Int,
    var parameterCount: Int,
    var resultCount: Int,
    var labelArity: Int,
    var exceptionHandler: GuestExceptionHandler? = null,
    var caughtException: GuestException? = null,
    var linearHotCode: LinearHotCode = EMPTY_LINEAR_HOT_CODE,
)

internal class GuestCallFrame(
    var instance: Instance,
    var functionIndex: Int,
    var functionName: String?,
    var type: FuncType,
    var localsBase: Int,
    var localCount: Int,
    var stackBase: Int,
    val controls: RuntimeObjectStack<GuestControlFrame>,
) {
    val currentInstructionIndex: Int
        get() = (controls.lastOrNull()?.pc ?: 1) - 1
}
