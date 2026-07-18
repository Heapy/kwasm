package io.heapy.kwasm

/**
 * Base class for failures reported by kwasm.
 *
 * Decode, validation, instantiation, execution, and snapshot failures are
 * intentionally distinct so embedders never need to inspect message strings.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class KwasmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A malformed binary module. [offset] is the byte offset of the failure. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmDecodeException(
    message: String,
    public val offset: Int,
    cause: Throwable? = null,
) : KwasmException("decode error at byte $offset: $message", cause)

/** Compatibility name retained for the original prototype API. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias DecodeException = WasmDecodeException

/** Base class for pure module-validation failures. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ValidationException(message: String) : KwasmException(message)

/** A well-formed binary whose declarations or instructions are not valid. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class InvalidModule(
    message: String,
    public val functionIndex: Int? = null,
    public val instructionIndex: Int? = null,
) : ValidationException(
    buildString {
        append("validation error")
        if (functionIndex != null) append(" in function $functionIndex")
        if (instructionIndex != null) append(" at instruction $instructionIndex")
        append(": ")
        append(message)
    },
)

/** A decoded feature which this runtime intentionally does not execute. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class UnsupportedFeature(
    public val feature: String,
    public val location: String? = null,
) : ValidationException(
    "unsupported WebAssembly feature '$feature'" +
        if (location == null) "" else " at $location",
)

/** A configurable structural or execution ceiling was exceeded. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class LimitExceeded(
    public val limit: String,
    public val actual: Long,
    public val maximum: Long,
    public val location: String? = null,
) : ValidationException(
    buildString {
        append("limit '$limit' exceeded: $actual > $maximum")
        if (location != null) append(" at $location")
    },
)

/** Base class for linking and instantiation failures. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class WasmInstantiationException(message: String, cause: Throwable? = null) :
    KwasmException(message, cause)

@io.heapy.kwasm.ExperimentalKwasmApi
public class LinkException(
    message: String,
    public val importModule: String? = null,
    public val importName: String? = null,
) : WasmInstantiationException(
    buildString {
        append("link error")
        if (importModule != null && importName != null) append(" for $importModule.$importName")
        append(": ")
        append(message)
    },
)

@io.heapy.kwasm.ExperimentalKwasmApi
public class InstantiationFailure(message: String, cause: Throwable? = null) :
    WasmInstantiationException("instantiation failed: $message", cause)

/** A guest exception that escaped every guest handler. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class UncaughtWasmException(public val exception: GuestException) :
    KwasmException("uncaught WebAssembly exception")

/** One frame in the synthetic guest stack attached to a [WasmTrap]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class GuestStackFrame(
    public val functionIndex: Int,
    public val functionName: String?,
    public val instructionIndex: Int,
) {
    override fun toString(): String =
        "${functionName ?: "function[$functionIndex]"}@$instructionIndex"
}

/** Runtime trap kinds and their canonical specification messages. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class TrapKind(public val specMessage: String) {
    UNREACHABLE("unreachable"),
    INTEGER_DIVIDE_BY_ZERO("integer divide by zero"),
    INTEGER_OVERFLOW("integer overflow"),
    INVALID_CONVERSION_TO_INTEGER("invalid conversion to integer"),
    OUT_OF_BOUNDS_MEMORY_ACCESS("out of bounds memory access"),
    UNDEFINED_ELEMENT("undefined element"),
    UNINITIALIZED_ELEMENT("uninitialized element"),
    INDIRECT_CALL_TYPE_MISMATCH("indirect call type mismatch"),
    INDIRECT_CALL_NULL("uninitialized element"),
    NULL_FUNCTION_REFERENCE("null function reference"),
    NULL_REFERENCE("null reference"),
    CAST_FAILURE("cast failure"),
    ARRAY_OUT_OF_BOUNDS("array out of bounds"),
    TABLE_OUT_OF_BOUNDS("table out of bounds"),
    STACK_EXHAUSTED("value stack exhausted"),
    CALL_STACK_EXHAUSTED("call stack exhausted"),
    OUT_OF_BOUNDS_TABLE_ACCESS("out of bounds table access"),
    OUT_OF_FUEL("out of fuel"),
    UNREACHABLE_PARENT("unreachable parent"),
}

/**
 * A guest-visible WebAssembly trap.
 *
 * Kotlin exceptions thrown by host imports do not derive from this class and
 * therefore cannot be intercepted by guest exception handlers.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class WasmTrap(
    public val kind: TrapKind,
    detail: String? = null,
    public val functionIndex: Int? = null,
    public val functionName: String? = null,
    public val guestStack: List<GuestStackFrame> = emptyList(),
) : KwasmException(
    buildString {
        append(kind.specMessage)
        if (!detail.isNullOrBlank()) {
            append(": ")
            append(detail)
        }
        if (functionIndex != null) {
            append(" in ")
            append(functionName ?: "function[$functionIndex]")
        }
    },
)

/** General execution trap used for the core-spec trap conditions. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ExecutionTrap(
    kind: TrapKind,
    detail: String? = null,
    functionIndex: Int? = null,
    functionName: String? = null,
    guestStack: List<GuestStackFrame> = emptyList(),
) : WasmTrap(kind, detail, functionIndex, functionName, guestStack) {
    public companion object {
        public fun unreachable(): ExecutionTrap = ExecutionTrap(TrapKind.UNREACHABLE)
        public fun divByZero(): ExecutionTrap = ExecutionTrap(TrapKind.INTEGER_DIVIDE_BY_ZERO)
        public fun intOverflow(): ExecutionTrap = ExecutionTrap(TrapKind.INTEGER_OVERFLOW)
        public fun invalidConversion(): ExecutionTrap =
            ExecutionTrap(TrapKind.INVALID_CONVERSION_TO_INTEGER)

        public fun oobMemory(addr: Long, len: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS, "address=$addr, length=$len")

        public fun oobTable(index: Int, size: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.OUT_OF_BOUNDS_TABLE_ACCESS, "index=$index, size=$size")

        public fun undefinedElement(index: Int, size: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.UNDEFINED_ELEMENT, "index=$index, size=$size")

        public fun uninitElement(index: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.UNINITIALIZED_ELEMENT, "index=$index")

        public fun indirectTypeMismatch(expected: Int, actual: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.INDIRECT_CALL_TYPE_MISMATCH, "expected type $expected, actual type $actual")

        public fun indirectNull(): ExecutionTrap =
            ExecutionTrap(TrapKind.INDIRECT_CALL_NULL)

        public fun nullFunctionReference(): ExecutionTrap =
            ExecutionTrap(TrapKind.NULL_FUNCTION_REFERENCE)

        public fun nullReference(): ExecutionTrap =
            ExecutionTrap(TrapKind.NULL_REFERENCE)

        public fun castFailure(): ExecutionTrap =
            ExecutionTrap(TrapKind.CAST_FAILURE)

        public fun arrayOutOfBounds(index: Int, size: Int): ExecutionTrap =
            ExecutionTrap(TrapKind.ARRAY_OUT_OF_BOUNDS, "index=$index, size=$size")

        public fun tableGrowFailed(): ExecutionTrap =
            ExecutionTrap(TrapKind.TABLE_OUT_OF_BOUNDS, "table.grow failed")

        public fun memoryGrowFailed(): ExecutionTrap =
            ExecutionTrap(TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS, "memory.grow failed")
    }
}

/** Compatibility name retained while consumers migrate to [WasmTrap]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias Trap = ExecutionTrap

@io.heapy.kwasm.ExperimentalKwasmApi
public class OutOfFuel(
    functionIndex: Int? = null,
    functionName: String? = null,
    guestStack: List<GuestStackFrame> = emptyList(),
) : WasmTrap(TrapKind.OUT_OF_FUEL, functionIndex = functionIndex, functionName = functionName, guestStack = guestStack)

/** A cancelled store cannot be invoked again until it is restored. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class PoisonedStoreException :
    KwasmException("store is poisoned after cancellation; discard it or restore a snapshot")

/** Base class for snapshot encoding and restoration failures. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class SnapshotException(message: String, cause: Throwable? = null) :
    KwasmException(message, cause)

@io.heapy.kwasm.ExperimentalKwasmApi
public class SnapshotStateException(message: String) :
    SnapshotException("snapshot state error: $message")

@io.heapy.kwasm.ExperimentalKwasmApi
public class SnapshotFormatException(message: String, cause: Throwable? = null) :
    SnapshotException("snapshot format error: $message", cause)

@io.heapy.kwasm.ExperimentalKwasmApi
public class SnapshotModuleMismatch(
    public val expectedHash: ByteArray,
    public val actualHash: ByteArray,
) : SnapshotException("snapshot module hash does not match the module being restored") {
    override fun equals(other: Any?): Boolean =
        other is SnapshotModuleMismatch &&
            expectedHash.contentEquals(other.expectedHash) &&
            actualHash.contentEquals(other.actualHash)

    override fun hashCode(): Int = 31 * expectedHash.contentHashCode() + actualHash.contentHashCode()
}
