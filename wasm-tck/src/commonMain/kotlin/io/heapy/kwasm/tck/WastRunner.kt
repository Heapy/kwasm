package io.heapy.kwasm.tck

import io.heapy.kwasm.ExportDesc
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.HeapType
import io.heapy.kwasm.Instance
import io.heapy.kwasm.Interpreter
import io.heapy.kwasm.LinkException
import io.heapy.kwasm.Machine
import io.heapy.kwasm.Module
import io.heapy.kwasm.RefType
import io.heapy.kwasm.Store
import io.heapy.kwasm.TrapKind
import io.heapy.kwasm.UncaughtWasmException
import io.heapy.kwasm.ValType
import io.heapy.kwasm.ValidationException
import io.heapy.kwasm.Value
import io.heapy.kwasm.isNullRef
import io.heapy.kwasm.WasmDecodeException
import io.heapy.kwasm.WasmInstantiationException
import io.heapy.kwasm.WasmTrap
import kotlin.coroutines.cancellation.CancellationException

/** Supplies `.wasm` assets named by a `wast2json` manifest. */
public fun interface TckAssetLoader {
    public fun load(filename: String): ByteArray
}

public enum class TckOutcome { Passed, Failed, Excluded }

public data class TckCommandResult(
    public val line: Int,
    public val command: String,
    public val outcome: TckOutcome,
    public val detail: String? = null,
    public val exclusion: TckExclusion? = null,
)

public data class TckReport(
    public val sourceFilename: String,
    public val results: List<TckCommandResult>,
) {
    public val passed: Int get() = results.count { it.outcome == TckOutcome.Passed }
    public val failed: Int get() = results.count { it.outcome == TckOutcome.Failed }
    public val excluded: Int get() = results.count { it.outcome == TckOutcome.Excluded }

    public fun requireSuccess() {
        if (failed == 0) return
        val failures = results.filter { it.outcome == TckOutcome.Failed }
        throw TckRunException(
            buildString {
                append("$sourceFilename: $failed TCK command(s) failed")
                failures.take(20).forEach { failure ->
                    append("\n  line ${failure.line} ${failure.command}: ${failure.detail}")
                }
            },
            this,
        )
    }
}

public class TckRunException(message: String, public val report: TckReport) : AssertionError(message)

private class TckAssertionFailure(message: String) : AssertionError(message)

/**
 * Executes the stateful command stream emitted by WABT.
 *
 * The runner deliberately records failures rather than relying on a particular
 * Kotlin test framework, so the exact same harness runs on JVM and Native.
 */
public class WastRunner(
    private val assets: TckAssetLoader,
    private val exclusions: TckExclusions = TckExclusions.Empty,
    private val registry: TckModuleRegistry = TckModuleRegistry.withSpectest(),
    private val machine: Machine = Interpreter(),
    private val linker: TckLinker = TckLinker(machine),
    private val storeFactory: () -> Store = ::Store,
    private val failFast: Boolean = false,
) {
    public suspend fun run(script: WastScript): TckReport {
        val results = mutableListOf<TckCommandResult>()
        // A spec script has one store. Registrations therefore retain guest
        // function/tag/reference identity across every module in the command
        // stream instead of being adapted through unrelated per-module stores.
        val store = storeFactory()
        for (command in script.commands) {
            val commandName = command.commandName()
            val exclusion = exclusions.find(script.sourceFilename, command.line)
            if (exclusion != null) {
                results += TckCommandResult(
                    command.line,
                    commandName,
                    TckOutcome.Excluded,
                    "excluded by ${exclusion.feature}: ${exclusion.issueUrl}",
                    exclusion,
                )
                continue
            }
            try {
                execute(command, store)
                results += TckCommandResult(command.line, commandName, TckOutcome.Passed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                results += TckCommandResult(
                    command.line,
                    commandName,
                    TckOutcome.Failed,
                    throwable.message ?: throwable::class.simpleName ?: "unknown failure",
                )
                if (failFast) break
            }
        }
        return TckReport(script.sourceFilename, results)
    }

    private suspend fun execute(command: WastCommand, store: Store) {
        when (command) {
            is WastCommand.Module -> {
                val module = decode(command.filename)
                val instance = linker.instantiate(module, registry, store)
                registry.installInstance(command.name, instance, machine)
                instance.runStart(machine)
            }
            is WastCommand.Register -> registry.register(command.alias, command.name, machine)
            is WastCommand.Action -> executeAction(command.action)
            is WastCommand.AssertReturn -> {
                val actual = executeAction(command.action)
                assertValues(command.expected, actual)
            }
            is WastCommand.AssertTrap -> expectFailure<WasmTrap>(command.text) {
                executeAction(command.action)
            }
            is WastCommand.AssertExhaustion -> {
                val failure = captureFailure { executeAction(command.action) }
                val trap = failure as? WasmTrap
                    ?: throw TckAssertionFailure("expected exhaustion, got ${failure.describe()}")
                val exhaustionKind = trap.kind in setOf(
                    TrapKind.CALL_STACK_EXHAUSTED,
                    TrapKind.STACK_EXHAUSTED,
                    TrapKind.OUT_OF_FUEL,
                )
                if (!exhaustionKind && !messageMatches(trap, command.text)) {
                    throw TckAssertionFailure("expected exhaustion '${command.text}', got ${trap.message}")
                }
            }
            is WastCommand.AssertException -> {
                val failure = captureFailure { executeAction(command.action) }
                if (failure !is UncaughtWasmException) {
                    throw TckAssertionFailure("expected uncaught guest exception, got ${failure.describe()}")
                }
                if (command.text != null && !messageMatches(failure, command.text)) {
                    throw TckAssertionFailure("expected '${command.text}', got '${failure.message}'")
                }
            }
            is WastCommand.AssertMalformed -> {
                val failure = captureFailure { decode(command.filename) }
                if (failure !is WasmDecodeException) {
                    throw TckAssertionFailure("expected malformed module, got ${failure.describe()}")
                }
            }
            is WastCommand.AssertInvalid -> {
                val failure = captureFailure { decode(command.filename) }
                if (failure !is ValidationException) {
                    throw TckAssertionFailure("expected invalid module, got ${failure.describe()}")
                }
            }
            is WastCommand.AssertUnlinkable -> {
                val module = decode(command.filename)
                val failure = captureFailure { linker.instantiate(module, registry, store) }
                if (failure !is LinkException) {
                    throw TckAssertionFailure("expected unlinkable module, got ${failure.describe()}")
                }
            }
            is WastCommand.AssertUninstantiable -> {
                val module = decode(command.filename)
                val failure = captureFailure {
                    val instance = linker.instantiate(module, registry, store)
                    instance.runStart(machine)
                }
                if (failure !is WasmTrap && failure !is WasmInstantiationException) {
                    throw TckAssertionFailure("expected uninstantiable module, got ${failure.describe()}")
                }
            }
        }
    }

    private fun decode(filename: String): Module = Module.decode(assets.load(filename))

    private suspend fun executeAction(action: WastAction): List<Value> {
        val instance = registry.resolveActionInstance(action.module)
        return when (action) {
            is WastAction.Invoke -> {
                val export = instance.export(action.field)
                    ?: throw LinkException("unknown export '${action.field}'")
                val function = export.desc as? ExportDesc.Function
                    ?: throw LinkException("export '${action.field}' is not a function")
                val type = instance.module.functionType(function.index)
                val arguments = action.arguments.mapIndexed { index, value ->
                    value.toRuntimeValue(type.params.getOrNull(index), instance.module)
                }
                instance.invoke(action.field, arguments, machine)
            }
            is WastAction.Get -> {
                val export = instance.export(action.field)
                    ?: throw LinkException("unknown export '${action.field}'")
                val global = export.desc as? ExportDesc.Global
                    ?: throw LinkException("export '${action.field}' is not a global")
                listOf(instance.globals[global.index].value)
            }
        }
    }

    private fun assertValues(expected: List<WastValue>, actual: List<Value>) {
        if (expected.size != actual.size) {
            throw TckAssertionFailure("expected ${expected.size} result(s), got ${actual.size}: $actual")
        }
        expected.zip(actual).forEachIndexed { index, (expectedValue, actualValue) ->
            if (!expectedValue.matches(actualValue)) {
                throw TckAssertionFailure("result $index: expected $expectedValue, got $actualValue")
            }
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        expectedText: String,
        action: suspend () -> Unit,
    ) {
        val failure = captureFailure(action)
        if (failure !is T) {
            throw TckAssertionFailure("expected ${T::class.simpleName}, got ${failure.describe()}")
        }
        if (!messageMatches(failure, expectedText)) {
            throw TckAssertionFailure("expected '$expectedText', got '${failure.message}'")
        }
    }

    private suspend inline fun captureFailure(action: suspend () -> Unit): Throwable {
        return try {
            action()
            throw TckAssertionFailure("expected command to fail, but it succeeded")
        } catch (failure: TckAssertionFailure) {
            throw failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            failure
        }
    }
}

private fun WastCommand.commandName(): String = when (this) {
    is WastCommand.Module -> "module"
    is WastCommand.Register -> "register"
    is WastCommand.Action -> "action"
    is WastCommand.AssertReturn -> "assert_return"
    is WastCommand.AssertTrap -> "assert_trap"
    is WastCommand.AssertExhaustion -> "assert_exhaustion"
    is WastCommand.AssertException -> "assert_exception"
    is WastCommand.AssertMalformed -> "assert_malformed"
    is WastCommand.AssertInvalid -> "assert_invalid"
    is WastCommand.AssertUnlinkable -> "assert_unlinkable"
    is WastCommand.AssertUninstantiable -> "assert_uninstantiable"
}

internal fun WastValue.toRuntimeValue(
    expectedType: ValType? = null,
    typeContext: Module? = null,
): Value = when (type.lowercase()) {
    "i32" -> Value.I32(parseIntegerBits(requireValue(), 32).toInt())
    "i64" -> Value.I64(parseIntegerBits(requireValue(), 64).toLong())
    "f32" -> Value.F32(Float.fromBits(float32Bits(requireValue())))
    "f64" -> Value.F64(Double.fromBits(float64Bits(requireValue())))
    "funcref", "ref.func" -> if (isNull()) Value.NULL_FUNC else
        Value.Ref.Func(parseIntegerBits(requireValue(), 32).toInt())
    "externref", "ref.extern" -> if (isNull()) Value.NULL_EXTERN else
        Value.Ref.Extern(parseIntegerBits(requireValue(), 32).toInt())
    "exnref", "ref.exn" -> {
        requireNullReference()
        Value.NULL_EXN
    }
    "i31ref", "ref.i31" -> if (isNull()) {
        Value.NULL_GC
    } else {
        Value.Ref.I31(parseIntegerBits(requireValue(), 32).toInt())
    }
    "anyref", "eqref", "structref", "arrayref" -> {
        requireNullReference()
        Value.NULL_GC
    }
    "ref.host" -> if (isNull()) {
        Value.NULL_GC
    } else {
        Value.Ref.AnyExtern(
            Value.Ref.Extern(parseIntegerBits(requireValue(), 32).toInt()),
        )
    }
    "ref.null" -> nullReferenceForHeap(value, expectedType, typeContext)
    "v128" -> Value.V128(v128Bytes())
    else -> throw TckAssertionFailure("unsupported TCK value type '$type'")
}

internal fun WastValue.matches(actual: Value): Boolean = when (type.lowercase()) {
    "i32" -> actual is Value.I32 && actual.v == parseIntegerBits(requireValue(), 32).toInt()
    "i64" -> actual is Value.I64 && actual.v == parseIntegerBits(requireValue(), 64).toLong()
    "f32" -> actual is Value.F32 && float32Matches(requireValue(), actual.v.toRawBits())
    "f64" -> actual is Value.F64 && float64Matches(requireValue(), actual.v.toRawBits())
    // WABT serializes `(ref.func)` expectations as value `0`; that value is
    // a non-null class marker, not a module-local function index. Reference
    // identity cannot be named by a WAST assertion.
    "funcref", "ref.func" -> actual is Value.Ref.Func &&
        if (isNull()) actual.isNullRef() else !actual.isNullRef()
    "externref", "ref.extern" -> actual is Value.Ref.Extern &&
        if (isNull()) actual.handle < 0 else actual.handle == parseIntegerBits(requireValue(), 32).toInt()
    "exnref", "ref.exn" -> isNull() && actual is Value.Ref.Exn && actual.value == null
    "i31ref", "ref.i31" -> actual is Value.Ref.I31 &&
        !isNull() &&
        actual.value == parseIntegerBits(requireValue(), 32).toInt() ||
        isNull() && actual is Value.Ref.Gc && actual.value == null
    "anyref", "eqref", "structref", "arrayref" ->
        isNull() && actual is Value.Ref && actual.isNullReference()
    "ref.host" -> when {
        isNull() -> actual is Value.Ref && actual.isNullReference()
        actual is Value.Ref.AnyExtern -> {
            val external = actual.external
            external is Value.Ref.Extern &&
                external.handle == parseIntegerBits(requireValue(), 32).toInt()
        }
        else -> false
    }
    "ref.null" ->
        actual is Value.Ref &&
            actual.isNullReference() &&
            nullReferenceKindMatches(value, actual)
    "v128" -> actual is Value.V128 && actual.bytes.contentEquals(v128Bytes())
    else -> false
}

private fun WastValue.requireValue(): String = value
    ?: throw TckAssertionFailure("TCK value '$type' has no value")

private fun WastValue.isNull(): Boolean = value == null || value == "null"

private fun WastValue.requireNullReference() {
    if (!isNull()) {
        throw TckAssertionFailure(
            "TCK value '$type' cannot be materialized from non-null identity '${value}'",
        )
    }
}

private fun Value.Ref.isNullReference(): Boolean = isNullRef()

private fun nullReferenceForHeap(
    heapName: String?,
    expectedType: ValType? = null,
    typeContext: Module? = null,
): Value.Ref = when (heapName?.lowercase()) {
    "extern", "externref", "noextern", "nullexternref" -> Value.NULL_EXTERN
    "exn", "exnref", "noexn", "nullexnref" -> Value.NULL_EXN
    "func", "funcref", "nofunc", "nullfuncref" -> Value.NULL_FUNC
    "any",
    "anyref",
    "eq",
    "eqref",
    "i31",
    "i31ref",
    "struct",
    "structref",
    "array",
    "arrayref",
    "none",
    "nullref",
    -> Value.NULL_GC
    else -> nullReferenceForExpectedType(expectedType, typeContext)
}

private fun nullReferenceKindMatches(heapName: String?, actual: Value.Ref): Boolean =
    when (nullReferenceForHeap(heapName)) {
        is Value.Ref.Extern -> actual is Value.Ref.Extern || actual is Value.Ref.Host
        is Value.Ref.Exn -> actual is Value.Ref.Exn
        is Value.Ref.Func -> actual is Value.Ref.Func
        is Value.Ref.Gc -> actual is Value.Ref.Gc
        else -> false
    }

private fun nullReferenceForExpectedType(
    type: ValType?,
    module: Module?,
): Value.Ref {
    val heap = when (type) {
        is RefType -> type.heap
        is ValType.Ref -> type.heap
        else -> return Value.NULL_GC
    }
    return when (heap) {
        HeapType.Extern, HeapType.NoExtern -> Value.NULL_EXTERN
        HeapType.Exn, HeapType.NoExn -> Value.NULL_EXN
        HeapType.Func, HeapType.NoFunc -> Value.NULL_FUNC
        is HeapType.Index ->
            if (module?.types?.getOrNull(heap.value) is FuncType) {
                Value.NULL_FUNC
            } else {
                Value.NULL_GC
            }
        else -> Value.NULL_GC
    }
}

private fun parseIntegerBits(source: String, width: Int): ULong {
    val text = source.replace("_", "")
    if (text.startsWith("-")) {
        val signed = if (text.startsWith("-0x")) {
            -text.substring(3).toULong(16).toLong()
        } else {
            text.toLong()
        }
        return if (width == 32) signed.toUInt().toULong() else signed.toULong()
    }
    val unsigned = if (text.startsWith("0x")) text.substring(2).toULong(16) else text.toULong()
    if (width == 32 && unsigned > UInt.MAX_VALUE.toULong()) {
        throw TckAssertionFailure("i32 bit pattern is out of range: $source")
    }
    return unsigned
}

private fun float32Bits(source: String): Int = when (source) {
    "nan:canonical", "nan:arithmetic" -> 0x7FC00000
    else -> parseIntegerBits(source, 32).toInt()
}

private fun float64Bits(source: String): Long = when (source) {
    "nan:canonical", "nan:arithmetic" -> 0x7FF8000000000000L
    else -> parseIntegerBits(source, 64).toLong()
}

private fun float32Matches(expected: String, bits: Int): Boolean = when (expected) {
    "nan:canonical" -> bits and 0x7FFFFFFF == 0x7FC00000
    "nan:arithmetic" -> bits and 0x7FC00000 == 0x7FC00000
    else -> bits == float32Bits(expected)
}

private fun float64Matches(expected: String, bits: Long): Boolean = when (expected) {
    "nan:canonical" -> bits and 0x7FFFFFFFFFFFFFFFL == 0x7FF8000000000000L
    "nan:arithmetic" -> bits and 0x7FF8000000000000L == 0x7FF8000000000000L
    else -> bits == float64Bits(expected)
}

private fun WastValue.v128Bytes(): ByteArray {
    val lane = laneType ?: throw TckAssertionFailure("v128 constant has no lane_type")
    val width = when (lane) {
        "i8" -> 1
        "i16" -> 2
        "i32", "f32" -> 4
        "i64", "f64" -> 8
        else -> throw TckAssertionFailure("unsupported v128 lane type '$lane'")
    }
    if (lanes.size * width != 16) {
        throw TckAssertionFailure("v128 $lane needs ${16 / width} lanes, got ${lanes.size}")
    }
    val result = ByteArray(16)
    lanes.forEachIndexed { laneIndex, laneValue ->
        val bits = when (lane) {
            "f32" -> float32Bits(laneValue).toULong()
            "f64" -> float64Bits(laneValue).toULong()
            else -> parseIntegerBits(laneValue, width * 8)
        }
        repeat(width) { byteIndex ->
            result[laneIndex * width + byteIndex] = (bits shr (byteIndex * 8)).toByte()
        }
    }
    return result
}

private fun messageMatches(failure: Throwable, expected: String): Boolean {
    val normalizedExpected = expected.normalizeDiagnostic()
    val candidates = buildList {
        failure.message?.let(::add)
        if (failure is WasmTrap) add(failure.kind.specMessage)
    }
    return candidates.any { candidate ->
        val normalizedCandidate = candidate.normalizeDiagnostic()
        normalizedCandidate.contains(normalizedExpected) || normalizedExpected.contains(normalizedCandidate)
    }
}

private fun String.normalizeDiagnostic(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun Throwable.describe(): String =
    "${this::class.simpleName ?: "Throwable"}${message?.let { ": $it" }.orEmpty()}"
