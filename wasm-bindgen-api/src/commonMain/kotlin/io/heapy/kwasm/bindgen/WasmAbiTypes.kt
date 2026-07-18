package io.heapy.kwasm.bindgen

/**
 * Types supported directly by version 1 of the kwasm bindgen ABI.
 *
 * The numeric [tag] is part of the binary ABI and MUST NOT be reassigned.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class WasmAbiType(public val tag: Int) {
    UNIT(0x00),
    INT32(0x01),
    INT64(0x02),
    FLOAT32(0x03),
    FLOAT64(0x04),
    BOOLEAN(0x05),
    STRING(0x06),
    BYTE_ARRAY(0x07),
    /** Versioned kotlinx.serialization payload for an IDL composite type. */
    COMPOSITE(0x08),
}

/** A value that can cross a version 1 kwasm boundary without an adapter. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed interface WasmAbiValue {
    public val type: WasmAbiType

    public data object UnitValue : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.UNIT
    }

    public data class Int32(public val value: Int) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.INT32
    }

    public data class Int64(public val value: Long) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.INT64
    }

    public data class Float32(public val value: Float) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.FLOAT32
    }

    public data class Float64(public val value: Double) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.FLOAT64
    }

    public data class Bool(public val value: Boolean) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.BOOLEAN
    }

    public data class Utf8(public val value: String) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.STRING
    }

    /**
     * Byte-array ABI value with content equality rather than reference
     * equality.
     */
    public class Bytes(public val value: ByteArray) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.BYTE_ARRAY

        override fun equals(other: Any?): Boolean =
            other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(${value.size} bytes)"
    }

    /**
     * A serialized data class or sealed-hierarchy value.
     *
     * The generated binding owns the Kotlin serializer; the outer ABI keeps
     * this payload length-delimited so unknown composite schemas cannot
     * desynchronize the containing call message.
     */
    public class Composite(public val value: ByteArray) : WasmAbiValue {
        override val type: WasmAbiType = WasmAbiType.COMPOSITE

        override fun equals(other: Any?): Boolean =
            other is Composite && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Composite(${value.size} bytes)"
    }
}

/**
 * Resource limits applied while encoding or decoding an ABI message.
 *
 * The limits make the decoder safe to use on guest-controlled bytes. A caller
 * may choose lower limits at a particular boundary.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmAbiLimits(
    public val maxMessageBytes: Int = 16 * 1024 * 1024,
    public val maxValueCount: Int = 65_536,
    public val maxVariableValueBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(maxMessageBytes >= WasmAbiCodec.HEADER_SIZE) {
            "maxMessageBytes must fit the ${WasmAbiCodec.HEADER_SIZE}-byte ABI header"
        }
        require(maxValueCount >= 0) { "maxValueCount must be non-negative" }
        require(maxVariableValueBytes >= 0) {
            "maxVariableValueBytes must be non-negative"
        }
    }
}

/** A decoded, versioned ABI message. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class WasmAbiMessage(
    public val version: Int,
    public val values: List<WasmAbiValue>,
)

/** Base type for deterministic ABI failures. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class WasmAbiException(message: String) : IllegalArgumentException(message)

/** The input is not a well-formed kwasm ABI message. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmAbiDecodingException(
    public val offset: Int,
    detail: String,
) : WasmAbiException("Malformed kwasm ABI message at byte $offset: $detail")

/** A generated binding received the wrong number of values. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmAbiArityException(
    public val expected: Int,
    public val actual: Int,
) : WasmAbiException("ABI value count mismatch: expected $expected, received $actual")

/** A generated binding received a value with the wrong ABI tag. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmAbiTypeMismatchException(
    public val index: Int,
    public val expected: WasmAbiType,
    public val actual: WasmAbiType,
) : WasmAbiException(
    "ABI type mismatch at value $index: expected $expected, received $actual",
)

/**
 * Typed access to a decoded ABI argument/result list.
 *
 * Generated bindings call [requireSize] before any typed accessor, so malformed
 * calls fail at the boundary with a stable error rather than a cast exception.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class WasmAbiArguments(public val values: List<WasmAbiValue>) {
    public fun requireSize(expected: Int) {
        if (values.size != expected) {
            throw WasmAbiArityException(expected = expected, actual = values.size)
        }
    }

    public fun int32(index: Int): Int =
        (requireType(index, WasmAbiType.INT32) as WasmAbiValue.Int32).value

    public fun int64(index: Int): Long =
        (requireType(index, WasmAbiType.INT64) as WasmAbiValue.Int64).value

    public fun float32(index: Int): Float =
        (requireType(index, WasmAbiType.FLOAT32) as WasmAbiValue.Float32).value

    public fun float64(index: Int): Double =
        (requireType(index, WasmAbiType.FLOAT64) as WasmAbiValue.Float64).value

    public fun boolean(index: Int): Boolean =
        (requireType(index, WasmAbiType.BOOLEAN) as WasmAbiValue.Bool).value

    public fun string(index: Int): String =
        (requireType(index, WasmAbiType.STRING) as WasmAbiValue.Utf8).value

    public fun byteArray(index: Int): ByteArray =
        (requireType(index, WasmAbiType.BYTE_ARRAY) as WasmAbiValue.Bytes).value

    public fun composite(index: Int): ByteArray =
        (requireType(index, WasmAbiType.COMPOSITE) as WasmAbiValue.Composite).value

    public fun unit(index: Int) {
        requireType(index, WasmAbiType.UNIT)
    }

    private fun requireType(index: Int, expected: WasmAbiType): WasmAbiValue {
        if (index !in values.indices) {
            throw WasmAbiArityException(expected = index + 1, actual = values.size)
        }
        val value = values[index]
        if (value.type != expected) {
            throw WasmAbiTypeMismatchException(
                index = index,
                expected = expected,
                actual = value.type,
            )
        }
        return value
    }
}
