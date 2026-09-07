package io.heapy.kwasm.binary

/**
 * A list implementation that cannot be recovered as a [MutableList] by a
 * caller. Kotlin's ordinary `toList()` may return an ArrayList whose mutation
 * methods remain reachable through a cast, which is unsuitable for a
 * validated, shareable module graph.
 */
internal class FrozenList<T>(source: Iterable<T>) : AbstractList<T>() {
    private val values: Array<Any?> = source.map { it }.toTypedArray()

    override val size: Int
        get() = values.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T = values[index] as T
}

internal fun <T> Iterable<T>.frozen(): List<T> = FrozenList(this)

/**
 * A planner's cache entry for one frozen body. Declared here so the runtime
 * planner, which this package must not reference, can still park a whole entry
 * on the body it planned.
 */
internal interface FrozenCodeCache

/**
 * [linearHotCodeCache] holds a whole cache entry behind a single reference, so
 * two planners racing over a shared module can only lose an entry; neither can
 * read an owner and a plan that belong to different planners.
 */
internal class FrozenInstructions(source: List<Instr>) : AbstractList<Instr>() {
    private val values: Array<Instr> = source.toTypedArray()

    internal var linearHotCodeCache: FrozenCodeCache? = null

    override val size: Int
        get() = values.size

    override fun get(index: Int): Instr = values[index]
}

internal fun freezeDefinedType(type: DefinedType): DefinedType =
    when (type) {
        is FuncType -> type.copy(
            params = type.params.frozen(),
            results = type.results.frozen(),
            supertypes = type.supertypes.frozen(),
        )
        is StructType -> type.copy(
            fields = type.fields.frozen(),
            supertypes = type.supertypes.frozen(),
        )
        is ArrayType -> type.copy(supertypes = type.supertypes.frozen())
    }

internal fun freezeInstructions(instructions: List<Instr>): List<Instr> =
    FrozenInstructions(instructions.map(::freezeInstruction))

private fun freezeInstruction(instruction: Instr): Instr =
    when (instruction) {
        is Instr.SelectT -> instruction.copy(types = instruction.types.frozen())
        is Instr.Block -> instruction.copy(body = freezeInstructions(instruction.body))
        is Instr.Loop -> instruction.copy(body = freezeInstructions(instruction.body))
        is Instr.If -> instruction.copy(
            thenBody = freezeInstructions(instruction.thenBody),
            elseBody = freezeInstructions(instruction.elseBody),
        )
        is Instr.BrTable -> Instr.BrTable(instruction.rawTargets, instruction.defaultTarget)
        is Instr.TryTable -> instruction.copy(
            catches = instruction.catches.frozen(),
            body = freezeInstructions(instruction.body),
        )
        is Instr.LegacyTry -> instruction.copy(
            body = freezeInstructions(instruction.body),
            catches = instruction.catches.map {
                it.copy(body = freezeInstructions(it.body))
            }.frozen(),
            catchAll = instruction.catchAll?.let(::freezeInstructions),
        )
        is Instr.RawImmediate -> Instr.RawImmediate(
            instruction.opcode,
            instruction.rawImmediates,
            instruction.rawBytes,
        )
        else -> instruction
    }

internal fun freezeElementSegment(segment: ElementSegment): ElementSegment {
    val frozenOffset = segment.offset?.let(::freezeInstructions)
    val frozenMode =
        when (val mode = segment.mode) {
            is ElementMode.Active -> mode.copy(offset = freezeInstructions(mode.offset))
            is ElementMode.Passive -> mode
            is ElementMode.Declarative -> mode
        }
    return segment.copy(
        offset = frozenOffset,
        mode = frozenMode,
        exprs = segment.exprs.map(::freezeInstructions).frozen(),
    )
}

internal fun freezeDataSegment(segment: DataSegment): DataSegment {
    val mode =
        when (val current = segment.mode) {
            is DataMode.Active -> current.copy(offset = freezeInstructions(current.offset))
            DataMode.Passive -> current
        }
    return DataSegment(mode, segment.rawInit)
}
