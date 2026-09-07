package io.heapy.kwasm.binary

/**
 * Base class for failures reported by kwasm.
 *
 * Decode, validation, instantiation, execution, and snapshot failures are
 * intentionally distinct so embedders never need to inspect message strings.
 */
// Abstract rather than sealed: direct subclasses live in both this package and
// the runtime package, and a sealed class requires them all in one package. The
// internal constructor keeps the hierarchy closed to embedders.
@io.heapy.kwasm.ExperimentalKwasmApi
public abstract class KwasmException internal constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A malformed binary module. [offset] is the byte offset of the failure. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmDecodeException(
    message: String,
    public val offset: Int,
    cause: Throwable? = null,
) : KwasmException("decode error at byte $offset: $message", cause)

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
