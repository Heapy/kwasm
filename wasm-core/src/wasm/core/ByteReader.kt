package io.heapy.kwasm

/**
 * Unsigned + signed LEB128 and primitive reader over a byte array.
 *
 * The WebAssembly binary format is little-endian and uses LEB128 for all
 * variable-length integers. This reader tracks a position and bounds-checks
 * every read, throwing [DecodeException] on malformed input.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ByteReader(
    data: ByteArray,
    public val baseOffset: Int = 0,
) {
    internal val data: ByteArray = data.copyOf()
    public var pos: Int = 0
        private set

    public val remaining: Int get() = data.size - pos
    public val size: Int get() = data.size
    public val absolutePosition: Int get() = baseOffset + pos
    public fun isEof(): Boolean = pos >= data.size

    public fun readByte(): Byte {
        if (pos >= data.size) fail("unexpected end of input")
        return data[pos++]
    }

    public fun peekByte(): Byte {
        if (pos >= data.size) fail("unexpected end of input")
        return data[pos]
    }

    public fun readBytes(n: Int): ByteArray {
        if (n < 0 || n > remaining) {
            fail("unexpected end of input reading $n bytes (remaining=$remaining)")
        }
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** Read a bounded child reader while retaining absolute error positions. */
    public fun readReader(n: Int): ByteReader {
        val childOffset = absolutePosition
        return ByteReader(readBytes(n), childOffset)
    }

    /** Read an unsigned vector/section size after proving that it fits [Int]. */
    public fun readSize(label: String, maximum: Int = Int.MAX_VALUE): Int {
        val offset = absolutePosition
        val value = readU32()
        if (value > Int.MAX_VALUE.toUInt()) {
            throw WasmDecodeException("$label is too large: $value", offset)
        }
        val size = value.toInt()
        if (size > maximum) {
            throw WasmDecodeException("$label $size exceeds available/allowed size $maximum", offset)
        }
        return size
    }

    internal fun fail(message: String): Nothing =
        throw WasmDecodeException(message, absolutePosition)

    public fun readU32Le(): UInt {
        val b = readBytes(4)
        val v = ((b[0].toInt() and 0xFF)) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
        return v.toUInt()
    }

    public fun readF32(): Float = Float.fromBits(readU32Le().toInt())

    public fun readF64(): Double {
        val b = readBytes(8)
        var bits = 0L
        for (i in 0 until 8) bits = bits or ((b[i].toLong() and 0xFFL) shl (8 * i))
        return Double.fromBits(bits)
    }

    /** Unsigned LEB128 up to 32 bits. */
    public fun readU32(): UInt {
        val start = absolutePosition
        var result = 0u
        for (index in 0 until 5) {
            val b = readByte().toInt() and 0xFF
            val payload = b and 0x7F
            if (index == 4 && payload > 0x0F) {
                throw WasmDecodeException("u32 LEB128 has non-zero unused bits", start)
            }
            result = result or (payload.toUInt() shl (index * 7))
            if ((b and 0x80) == 0) {
                return result
            }
        }
        throw WasmDecodeException("u32 LEB128 is longer than 5 bytes", start)
    }

    /** Unsigned LEB128 up to 64 bits. */
    public fun readU64(): ULong {
        val start = absolutePosition
        var result = 0UL
        for (index in 0 until 10) {
            val b = readByte().toInt() and 0xFF
            val payload = b and 0x7F
            if (index == 9 && payload > 0x01) {
                throw WasmDecodeException("u64 LEB128 has non-zero unused bits", start)
            }
            result = result or (payload.toULong() shl (index * 7))
            if ((b and 0x80) == 0) {
                return result
            }
        }
        throw WasmDecodeException("u64 LEB128 is longer than 10 bytes", start)
    }

    /** Signed LEB128 up to 32 bits. */
    public fun readS32(): Int {
        return readSignedLeb(32, 5).toInt()
    }

    /** Signed LEB128 up to 33 bits, used by heap and block types. */
    public fun readS33(): Long = readSignedLeb(33, 5)

    /** Signed LEB128 up to 64 bits. */
    public fun readS64(): Long = readSignedLeb(64, 10)

    private fun readSignedLeb(bits: Int, maxBytes: Int): Long {
        val start = absolutePosition
        var result = 0UL
        var shift = 0
        for (index in 0 until maxBytes) {
            val b = readByte().toInt() and 0xFF
            val payload = b and 0x7F
            val remainingBits = bits - shift
            if (remainingBits < 7) {
                val usedMask = (1 shl remainingBits) - 1
                val unused = payload and usedMask.inv() and 0x7F
                val sign = payload and (1 shl (remainingBits - 1))
                val expectedUnused = if (sign == 0) 0 else 0x7F and usedMask.inv()
                if (unused != expectedUnused) {
                    throw WasmDecodeException("s$bits LEB128 has invalid unused sign bits", start)
                }
            }
            if (shift < 64) {
                result = result or (payload.toULong() shl shift)
            }
            shift += 7
            if ((b and 0x80) == 0) {
                if ((b and 0x40) != 0 && shift < bits) {
                    result = result or (ULong.MAX_VALUE shl shift)
                }
                return result.toLong()
            }
        }
        throw WasmDecodeException("s$bits LEB128 is longer than $maxBytes bytes", start)
    }

    /** Read a length-prefixed UTF-8 name. */
    public fun readName(): String {
        val len = readSize("UTF-8 name length", remaining)
        val bytes = readBytes(len)
        return decodeUtf8Strict(bytes, absolutePosition - len)
    }

    public fun readU32AsInt(): Int = readU32().toInt()
    public fun readU64AsLong(): Long = readU64().toLong()
}

/**
 * Strict UTF-8 decoder used by binary names. Invalid, overlong, surrogate, and
 * out-of-range scalar encodings are rejected with their absolute byte offset.
 */
internal fun decodeUtf8Strict(bytes: ByteArray, offset: Int = 0): String {
    val sb = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val (codePoint, length) = when {
            b0 <= 0x7F -> b0 to 1
            b0 in 0xC2..0xDF -> {
                val b1 = continuation(bytes, i + 1, offset)
                (((b0 and 0x1F) shl 6) or (b1 and 0x3F)) to 2
            }
            b0 in 0xE0..0xEF -> {
                val b1 = continuation(bytes, i + 1, offset)
                val b2 = continuation(bytes, i + 2, offset)
                if (b0 == 0xE0 && b1 < 0xA0 || b0 == 0xED && b1 >= 0xA0) {
                    throw WasmDecodeException("invalid UTF-8 scalar value", offset + i)
                }
                (((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)) to 3
            }
            b0 in 0xF0..0xF4 -> {
                val b1 = continuation(bytes, i + 1, offset)
                val b2 = continuation(bytes, i + 2, offset)
                val b3 = continuation(bytes, i + 3, offset)
                if (b0 == 0xF0 && b1 < 0x90 || b0 == 0xF4 && b1 >= 0x90) {
                    throw WasmDecodeException("invalid UTF-8 scalar value", offset + i)
                }
                (
                    ((b0 and 0x07) shl 18) or
                        ((b1 and 0x3F) shl 12) or
                        ((b2 and 0x3F) shl 6) or
                        (b3 and 0x3F)
                    ) to 4
            }
            else -> throw WasmDecodeException("invalid UTF-8 leading byte 0x${b0.toString(16)}", offset + i)
        }
        if (codePoint <= 0xFFFF) {
            sb.append(codePoint.toChar())
        } else {
            val value = codePoint - 0x10000
            sb.append((0xD800 + (value shr 10)).toChar())
            sb.append((0xDC00 + (value and 0x3FF)).toChar())
        }
        i += length
    }
    return sb.toString()
}

private fun continuation(bytes: ByteArray, index: Int, offset: Int): Int {
    if (index >= bytes.size) {
        throw WasmDecodeException("truncated UTF-8 sequence", offset + index)
    }
    val value = bytes[index].toInt() and 0xFF
    if (value !in 0x80..0xBF) {
        throw WasmDecodeException("invalid UTF-8 continuation byte 0x${value.toString(16)}", offset + index)
    }
    return value
}
