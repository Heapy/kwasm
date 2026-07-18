package io.heapy.kwasm.tck

/** A deliberately small JSON tree used for `wast2json` manifests. */
public sealed class JsonValue {
    public data class Object(public val entries: Map<String, JsonValue>) : JsonValue() {
        public operator fun get(name: String): JsonValue? = entries[name]
    }

    public data class Array(public val values: List<JsonValue>) : JsonValue()
    public data class StringValue(public val value: String) : JsonValue()
    public data class NumberValue(public val source: String) : JsonValue()
    public data class BooleanValue(public val value: Boolean) : JsonValue()
    public data object Null : JsonValue()
}

/** Position-carrying syntax error for malformed TCK manifests. */
public class JsonSyntaxException(
    message: String,
    public val offset: Int,
) : IllegalArgumentException("JSON error at character $offset: $message")

/** Dependency-free, strict JSON parser. Numeric spelling is retained exactly. */
public object Json {
    public fun parse(source: String): JsonValue = Parser(source).parse()
}

private class Parser(private val source: String) {
    private var position: Int = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        if (position != source.length) fail("trailing input")
        return value
    }

    private fun readValue(): JsonValue {
        if (position >= source.length) fail("expected a value")
        return when (source[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.StringValue(readString())
            't' -> { readKeyword("true"); JsonValue.BooleanValue(true) }
            'f' -> { readKeyword("false"); JsonValue.BooleanValue(false) }
            'n' -> { readKeyword("null"); JsonValue.Null }
            '-', in '0'..'9' -> JsonValue.NumberValue(readNumber())
            else -> fail("unexpected '${source[position]}'")
        }
    }

    private fun readObject(): JsonValue.Object {
        position++
        skipWhitespace()
        val entries = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonValue.Object(entries)
        while (true) {
            if (position >= source.length || source[position] != '"') {
                fail("expected an object key")
            }
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            if (key in entries) fail("duplicate object key '$key'")
            entries[key] = readValue()
            skipWhitespace()
            if (consume('}')) break
            expect(',')
            skipWhitespace()
        }
        return JsonValue.Object(entries)
    }

    private fun readArray(): JsonValue.Array {
        position++
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consume(']')) return JsonValue.Array(values)
        while (true) {
            values += readValue()
            skipWhitespace()
            if (consume(']')) break
            expect(',')
            skipWhitespace()
        }
        return JsonValue.Array(values)
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    if (position >= source.length) fail("unterminated escape")
                    when (val escape = source[position++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(readUnicodeEscape())
                        else -> fail("invalid escape '\\$escape'")
                    }
                }
                char.code < 0x20 -> fail("unescaped control character")
                else -> result.append(char)
            }
        }
        fail("unterminated string")
    }

    private fun readUnicodeEscape(): Char {
        if (position + 4 > source.length) fail("short unicode escape")
        var value = 0
        repeat(4) {
            val digit = source[position++].digitToIntOrNull(16)
                ?: fail("invalid unicode escape")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun readNumber(): String {
        val start = position
        consume('-')
        if (consume('0')) {
            if (position < source.length && source[position].isDigit()) {
                fail("leading zero in number")
            }
        } else {
            readDigits(required = true)
        }
        if (consume('.')) readDigits(required = true)
        if (position < source.length && source[position] in "eE") {
            position++
            if (position < source.length && source[position] in "+-") position++
            readDigits(required = true)
        }
        return source.substring(start, position)
    }

    private fun readDigits(required: Boolean) {
        val start = position
        while (position < source.length && source[position].isDigit()) position++
        if (required && start == position) fail("expected a digit")
    }

    private fun readKeyword(keyword: String) {
        if (!source.startsWith(keyword, position)) fail("expected '$keyword'")
        position += keyword.length
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position] in " \t\r\n") position++
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) fail("expected '$expected'")
    }

    private fun consume(expected: Char): Boolean {
        if (position < source.length && source[position] == expected) {
            position++
            return true
        }
        return false
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(message, position)
}
