package io.heapy.kwasm.wat

/**
 * Builds the WebAssembly binary format: little-endian primitives, LEB128
 * variable-length integers, length-prefixed vectors, and section framing.
 *
 * Used by [WatModuleComposer] to translate parsed WAT into bytes that the
 * core [wasm.core.ModuleDecoder] then decodes — the same path a real
 * wat2wasm + load pipeline takes.
 */
public class BinaryWriter {
    private val out = mutableListOf<Byte>()

    public fun toByteArray(): ByteArray = out.toByteArray()

    private fun addByte(b: Int) { out.add((b and 0xFF).toByte()) }

    public fun writeByte(b: Int): BinaryWriter { addByte(b); return this }
    public fun writeBytes(b: ByteArray): BinaryWriter { for (x in b) out.add(x); return this }

    public fun writeU32Le(v: Int): BinaryWriter {
        addByte(v); addByte(v shr 8); addByte(v shr 16); addByte(v shr 24)
        return this
    }

    public fun writeF32(v: Float): BinaryWriter = writeU32Le(v.toRawBits())
    public fun writeF64(v: Double): BinaryWriter {
        val bits = v.toRawBits()
        for (i in 0 until 8) addByte((bits shr (8 * i)).toInt())
        return this
    }

    /** Unsigned LEB128 (32-bit). */
    public fun writeU32(v: UInt): BinaryWriter = writeU32(v.toLong() and 0xFFFFFFFFL)
    public fun writeU32(v: Long): BinaryWriter {
        var value = v
        while (true) {
            var b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value != 0L) b = b or 0x80
            addByte(b)
            if (value == 0L) break
        }
        return this
    }

    /** Unsigned LEB128 of an [Int] value treated as unsigned. */
    public fun writeU32Int(v: Int): BinaryWriter = writeU32(v.toLong() and 0xFFFFFFFFL)

    /** Signed LEB128 (32/64-bit). */
    public fun writeS32(v: Int): BinaryWriter = writeS64(v.toLong())
    public fun writeS64(v: Long): BinaryWriter {
        var value = v
        var more = true
        while (more) {
            var b = (value and 0x7F).toInt()
            value = value shr 7 // arithmetic shift (sign-extends)
            val signBit = b and 0x40
            if ((value == 0L && signBit == 0) || (value == -1L && signBit != 0)) {
                more = false
            } else {
                b = b or 0x80
            }
            addByte(b)
        }
        return this
    }

    public fun writeName(s: String): BinaryWriter {
        val bytes = encodeUtf8(s)
        writeU32(bytes.size.toLong())
        for (x in bytes) out.add(x)
        return this
    }

    /** Write a section: id, size, body. */
    public fun writeSection(id: Int, body: BinaryWriter.() -> Unit): BinaryWriter {
        val tmp = BinaryWriter()
        tmp.body()
        addByte(id)
        writeU32(tmp.size().toLong())
        for (x in tmp.toByteArray()) out.add(x)
        return this
    }

    public fun writeRawSection(id: Int, bytes: ByteArray): BinaryWriter {
        addByte(id)
        writeU32(bytes.size.toLong())
        for (x in bytes) out.add(x)
        return this
    }

    public fun size(): Int = out.size

    public fun writeVector(elements: List<BinaryWriter.() -> Unit>): BinaryWriter {
        writeU32(elements.size.toLong())
        for (e in elements) e(this)
        return this
    }

    public companion object {
        public fun encodeUtf8(s: String): ByteArray {
            val out = mutableListOf<Int>()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                // Decode UTF-16 surrogate pair into a code point.
                val cp: Int = if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    val hi = ch.code - 0xD800
                    val lo = s[i + 1].code - 0xDC00
                    i += 2
                    0x10000 + (hi shl 10) + lo
                } else {
                    i += 1
                    ch.code
                }
                when {
                    cp <= 0x7F -> out.add(cp)
                    cp <= 0x7FF -> {
                        out.add(0xC0 or (cp shr 6)); out.add(0x80 or (cp and 0x3F))
                    }
                    cp <= 0xFFFF -> {
                        out.add(0xE0 or (cp shr 12))
                        out.add(0x80 or ((cp shr 6) and 0x3F))
                        out.add(0x80 or (cp and 0x3F))
                    }
                    else -> {
                        out.add(0xF0 or (cp shr 18))
                        out.add(0x80 or ((cp shr 12) and 0x3F))
                        out.add(0x80 or ((cp shr 6) and 0x3F))
                        out.add(0x80 or (cp and 0x3F))
                    }
                }
            }
            return ByteArray(out.size) { out[it].toByte() }
        }
    }
}
