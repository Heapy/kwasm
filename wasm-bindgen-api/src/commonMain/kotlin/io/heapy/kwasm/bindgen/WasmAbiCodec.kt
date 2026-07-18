package io.heapy.kwasm.bindgen

/**
 * Versioned fixed binary codec used by generated kwasm bindings.
 *
 * ## Version 1 layout
 *
 * All multi-byte values are little-endian. An encoded message is:
 *
 * ```text
 * byte  0..3   ASCII "KWAB"
 * byte  4      ABI version (1)
 * byte  5      flags (must be zero)
 * byte  6..7   reserved (must be zero)
 * byte  8..11  value count as unsigned 32-bit integer
 * byte 12..    tagged values
 * ```
 *
 * Each value begins with the one-byte [WasmAbiType.tag]. `Int`, `Long`,
 * `Float`, and `Double` use their fixed-width two's-complement/IEEE-754 bit
 * representation. `Boolean` is exactly one byte (`0` or `1`). `String` is an
 * unsigned 32-bit byte length followed by strict UTF-8. `ByteArray` is an
 * unsigned 32-bit byte length followed by its bytes. `Unit` has no payload.
 * A composite is an unsigned 32-bit length followed by a
 * [WasmCompositeCodec] payload whose serializer is fixed by generated code.
 *
 * The decoder rejects unknown tags, non-zero reserved fields, invalid UTF-8,
 * non-canonical booleans, trailing data, and inputs exceeding [WasmAbiLimits].
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public object WasmAbiCodec {
    public const val VERSION: Int = 1
    public const val HEADER_SIZE: Int = 12

    private const val MAGIC_0: Int = 0x4B // K
    private const val MAGIC_1: Int = 0x57 // W
    private const val MAGIC_2: Int = 0x41 // A
    private const val MAGIC_3: Int = 0x42 // B

    /** Encode [values] using ABI [VERSION]. */
    public fun encode(
        values: List<WasmAbiValue>,
        limits: WasmAbiLimits = WasmAbiLimits(),
    ): ByteArray {
        require(values.size <= limits.maxValueCount) {
            "ABI value count ${values.size} exceeds limit ${limits.maxValueCount}"
        }

        val writer = AbiWriter(limits.maxMessageBytes)
        writer.writeByte(MAGIC_0)
        writer.writeByte(MAGIC_1)
        writer.writeByte(MAGIC_2)
        writer.writeByte(MAGIC_3)
        writer.writeByte(VERSION)
        writer.writeByte(0)
        writer.writeByte(0)
        writer.writeByte(0)
        writer.writeInt(values.size)

        values.forEach { value ->
            writer.writeByte(value.type.tag)
            when (value) {
                WasmAbiValue.UnitValue -> Unit
                is WasmAbiValue.Int32 -> writer.writeInt(value.value)
                is WasmAbiValue.Int64 -> writer.writeLong(value.value)
                is WasmAbiValue.Float32 -> writer.writeInt(value.value.toRawBits())
                is WasmAbiValue.Float64 -> writer.writeLong(value.value.toRawBits())
                is WasmAbiValue.Bool -> writer.writeByte(if (value.value) 1 else 0)
                is WasmAbiValue.Utf8 -> {
                    val bytes = value.value.encodeToByteArray()
                    requireVariableSize(bytes.size, limits)
                    writer.writeInt(bytes.size)
                    writer.writeBytes(bytes)
                }
                is WasmAbiValue.Bytes -> {
                    requireVariableSize(value.value.size, limits)
                    writer.writeInt(value.value.size)
                    writer.writeBytes(value.value)
                }
                is WasmAbiValue.Composite -> {
                    requireVariableSize(value.value.size, limits)
                    writer.writeInt(value.value.size)
                    writer.writeBytes(value.value)
                }
            }
        }
        return writer.toByteArray()
    }

    /** Convenience overload for call sites constructing values inline. */
    public fun encode(
        vararg values: WasmAbiValue,
    ): ByteArray = encode(values.asList())

    /** Decode [bytes] and return its values after validating the whole message. */
    public fun decode(
        bytes: ByteArray,
        limits: WasmAbiLimits = WasmAbiLimits(),
    ): List<WasmAbiValue> = decodeMessage(bytes, limits).values

    /** Decode [bytes], retaining the version recorded in the message. */
    public fun decodeMessage(
        bytes: ByteArray,
        limits: WasmAbiLimits = WasmAbiLimits(),
    ): WasmAbiMessage {
        if (bytes.size > limits.maxMessageBytes) {
            throw WasmAbiDecodingException(
                offset = 0,
                detail = "message size ${bytes.size} exceeds limit ${limits.maxMessageBytes}",
            )
        }

        val reader = AbiReader(bytes)
        reader.requireByte(MAGIC_0, "magic byte 0")
        reader.requireByte(MAGIC_1, "magic byte 1")
        reader.requireByte(MAGIC_2, "magic byte 2")
        reader.requireByte(MAGIC_3, "magic byte 3")

        val versionOffset = reader.position
        val version = reader.readByte()
        if (version != VERSION) {
            throw WasmAbiDecodingException(
                versionOffset,
                "unsupported ABI version $version (expected $VERSION)",
            )
        }

        val flagsOffset = reader.position
        if (reader.readByte() != 0) {
            throw WasmAbiDecodingException(flagsOffset, "flags must be zero")
        }
        val reservedOffset = reader.position
        if (reader.readByte() != 0 || reader.readByte() != 0) {
            throw WasmAbiDecodingException(reservedOffset, "reserved bytes must be zero")
        }

        val countOffset = reader.position
        val count = reader.readLength("value count")
        if (count > limits.maxValueCount) {
            throw WasmAbiDecodingException(
                countOffset,
                "value count $count exceeds limit ${limits.maxValueCount}",
            )
        }
        if (count > reader.remaining) {
            throw WasmAbiDecodingException(
                countOffset,
                "value count $count cannot fit in ${reader.remaining} remaining bytes",
            )
        }

        val values = ArrayList<WasmAbiValue>(count)
        repeat(count) {
            val tagOffset = reader.position
            val tag = reader.readByte()
            values += when (tag) {
                WasmAbiType.UNIT.tag -> WasmAbiValue.UnitValue
                WasmAbiType.INT32.tag -> WasmAbiValue.Int32(reader.readInt())
                WasmAbiType.INT64.tag -> WasmAbiValue.Int64(reader.readLong())
                WasmAbiType.FLOAT32.tag ->
                    WasmAbiValue.Float32(Float.fromBits(reader.readInt()))
                WasmAbiType.FLOAT64.tag ->
                    WasmAbiValue.Float64(Double.fromBits(reader.readLong()))
                WasmAbiType.BOOLEAN.tag -> {
                    val booleanOffset = reader.position
                    when (val encoded = reader.readByte()) {
                        0 -> WasmAbiValue.Bool(false)
                        1 -> WasmAbiValue.Bool(true)
                        else -> throw WasmAbiDecodingException(
                            booleanOffset,
                            "Boolean payload must be 0 or 1, received $encoded",
                        )
                    }
                }
                WasmAbiType.STRING.tag -> {
                    val lengthOffset = reader.position
                    val length = reader.readLength("String byte length")
                    requireVariableSize(length, limits, lengthOffset)
                    val payloadOffset = reader.position
                    val payload = reader.readBytes(length)
                    val decoded = try {
                        payload.decodeToString(throwOnInvalidSequence = true)
                    } catch (_: Exception) {
                        throw WasmAbiDecodingException(
                            payloadOffset,
                            "String payload is not valid UTF-8",
                        )
                    }
                    WasmAbiValue.Utf8(decoded)
                }
                WasmAbiType.BYTE_ARRAY.tag -> {
                    val lengthOffset = reader.position
                    val length = reader.readLength("ByteArray length")
                    requireVariableSize(length, limits, lengthOffset)
                    WasmAbiValue.Bytes(reader.readBytes(length))
                }
                WasmAbiType.COMPOSITE.tag -> {
                    val lengthOffset = reader.position
                    val length = reader.readLength("composite payload length")
                    requireVariableSize(length, limits, lengthOffset)
                    WasmAbiValue.Composite(reader.readBytes(length))
                }
                else -> throw WasmAbiDecodingException(
                    tagOffset,
                    "unknown value tag 0x${tag.toString(16).padStart(2, '0')}",
                )
            }
        }

        if (reader.remaining != 0) {
            throw WasmAbiDecodingException(
                reader.position,
                "${reader.remaining} trailing byte(s)",
            )
        }
        return WasmAbiMessage(version = version, values = values)
    }

    private fun requireVariableSize(size: Int, limits: WasmAbiLimits) {
        require(size <= limits.maxVariableValueBytes) {
            "variable ABI value size $size exceeds limit ${limits.maxVariableValueBytes}"
        }
    }

    private fun requireVariableSize(
        size: Int,
        limits: WasmAbiLimits,
        offset: Int,
    ) {
        if (size > limits.maxVariableValueBytes) {
            throw WasmAbiDecodingException(
                offset,
                "variable value size $size exceeds limit ${limits.maxVariableValueBytes}",
            )
        }
    }
}

private class AbiWriter(private val maxSize: Int) {
    private var buffer: ByteArray = ByteArray(minOf(64, maxSize))
    private var size: Int = 0

    fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun writeInt(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
        writeByte(value ushr 16)
        writeByte(value ushr 24)
    }

    fun writeLong(value: Long) {
        writeByte(value.toInt())
        writeByte((value ushr 8).toInt())
        writeByte((value ushr 16).toInt())
        writeByte((value ushr 24).toInt())
        writeByte((value ushr 32).toInt())
        writeByte((value ushr 40).toInt())
        writeByte((value ushr 48).toInt())
        writeByte((value ushr 56).toInt())
    }

    fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, destinationOffset = size)
        size += value.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(additional: Int) {
        if (additional > maxSize - size) {
            throw IllegalArgumentException(
                "encoded ABI message exceeds limit $maxSize bytes",
            )
        }
        val required = size + additional
        if (required <= buffer.size) return

        var next = buffer.size
        while (next < required) {
            next = minOf(maxSize, maxOf(required, next * 2))
        }
        buffer = buffer.copyOf(next)
    }
}

private class AbiReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    val remaining: Int
        get() = bytes.size - position

    fun requireByte(expected: Int, description: String) {
        val byteOffset = position
        val actual = readByte()
        if (actual != expected) {
            throw WasmAbiDecodingException(
                byteOffset,
                "$description mismatch: expected 0x${expected.hex()}, received 0x${actual.hex()}",
            )
        }
    }

    fun readByte(): Int {
        requireAvailable(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readInt(): Int =
        readByte() or
            (readByte() shl 8) or
            (readByte() shl 16) or
            (readByte() shl 24)

    fun readLong(): Long =
        readByte().toLong() or
            (readByte().toLong() shl 8) or
            (readByte().toLong() shl 16) or
            (readByte().toLong() shl 24) or
            (readByte().toLong() shl 32) or
            (readByte().toLong() shl 40) or
            (readByte().toLong() shl 48) or
            (readByte().toLong() shl 56)

    fun readLength(description: String): Int {
        val lengthOffset = position
        val encoded = readInt().toLong() and 0xFFFF_FFFFL
        if (encoded > Int.MAX_VALUE.toLong()) {
            throw WasmAbiDecodingException(
                lengthOffset,
                "$description $encoded exceeds the supported signed 32-bit range",
            )
        }
        return encoded.toInt()
    }

    fun readBytes(length: Int): ByteArray {
        requireAvailable(length)
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || count > remaining) {
            throw WasmAbiDecodingException(
                position,
                "needed $count byte(s), only $remaining remain",
            )
        }
    }
}

private fun Int.hex(): String = toString(16).padStart(2, '0')
