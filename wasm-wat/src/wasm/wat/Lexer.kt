package io.heapy.kwasm.wat

/**
 * S-expression tokenizer for the WebAssembly text format.
 *
 * Handles parentheses, string literals, line/inline comments, identifiers,
 * reserved tokens, and numeric literals. The tokenizer is used by [SexprReader]
 * to build a tree of [Sexpr] nodes that the module composer walks.
 */
public class WatLexer(private val src: String) {
    private var pos: Int = 0
    public val tokens: List<Token> = run { tokenize() }

    private fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> pos++
                c == ';' && pos + 1 < src.length && src[pos + 1] == ';' -> {
                    // line comment
                    while (pos < src.length && src[pos] != '\n') pos++
                }
                c == '(' && pos + 1 < src.length && src[pos + 1] == ';' -> {
                    // block comment (nestable)
                    var depth = 1
                    pos += 2
                    while (pos < src.length && depth > 0) {
                        if (src[pos] == '(' && pos + 1 < src.length && src[pos + 1] == ';') { depth++; pos += 2 }
                        else if (src[pos] == ';' && pos + 1 < src.length && src[pos + 1] == ')') { depth--; pos += 2 }
                        else pos++
                    }
                }
                c == '(' -> { out.add(Token.LParen(pos)); pos++ }
                c == ')' -> { out.add(Token.RParen(pos)); pos++ }
                c == '"' -> {
                    val start = pos
                    pos++
                    val sb = StringBuilder()
                    while (pos < src.length && src[pos] != '"') {
                        val ch = src[pos]
                        if (ch == '\\' && pos + 1 < src.length) {
                            val n = src[pos + 1]
                            if (n == 'n') { sb.append('\n'); pos += 2 }
                            else if (n == 't') { sb.append('\t'); pos += 2 }
                            else if (n == 'r') { sb.append('\r'); pos += 2 }
                            else if (n == '"') { sb.append('"'); pos += 2 }
                            else if (n == '\'') { sb.append('\''); pos += 2 }
                            else if (n == '\\') { sb.append('\\'); pos += 2 }
                            else if (n == 'u' && pos + 5 < src.length) {
                                val hex = src.substring(pos + 2, pos + 6)
                                sb.append(hex.toInt(16).toChar()); pos += 6
                            } else if (n in '0'..'9' && pos + 2 < src.length && src[pos + 2] in '0'..'9') {
                                // \xx hex byte escape in strings
                                val hex = src.substring(pos + 1, pos + 3)
                                sb.append(hex.toInt(16).toChar()); pos += 3
                            } else {
                                sb.append(n); pos += 2
                            }
                        } else {
                            sb.append(ch); pos++
                        }
                    }
                    pos++ // closing quote
                    out.add(Token.Str(start, sb.toString()))
                }
                else -> {
                    val start = pos
                    while (pos < src.length) {
                        val cc = src[pos]
                        if (cc == ' ' || cc == '\t' || cc == '\r' || cc == '\n' ||
                            cc == '(' || cc == ')' || cc == '"'
                        ) break
                        if (cc == ';' && pos + 1 < src.length && (src[pos + 1] == ';' || src[pos + 1] == ')')) break
                        pos++
                    }
                    out.add(Token.Atom(start, src.substring(start, pos)))
                }
            }
        }
        return out
    }
}

public sealed class Token {
    public abstract val pos: Int
    public data class LParen(override val pos: Int) : Token()
    public data class RParen(override val pos: Int) : Token()
    public data class Str(override val pos: Int, public val value: String) : Token()
    public data class Atom(override val pos: Int, public val text: String) : Token()
}

/** Either an atom or a nested list. */
public sealed class Sexpr {
    public abstract val pos: Int
    public data class Sym(override val pos: Int, public val text: String) : Sexpr()
    public data class Str(override val pos: Int, public val value: String) : Sexpr()
    public data class Node(override val pos: Int, public val items: kotlin.collections.List<Sexpr>) : Sexpr()
}

/** Reads a flat token stream into an [Sexpr] tree. */
public class SexprReader(tokens: List<Token>) {
    private val it = tokens.iterator()
    private var current: Token? = null

    init { if (it.hasNext()) current = it.next() }

    public fun readAll(): List<Sexpr> {
        val out = ArrayList<Sexpr>()
        while (current != null) {
            val e = readOne() ?: break
            out.add(e)
        }
        return out
    }

    private fun readOne(): Sexpr? {
        val tok = current ?: return null
        return when (tok) {
            is Token.LParen -> {
                val startPos = tok.pos
                advance()
                val items = ArrayList<Sexpr>()
                while (current != null && current !is Token.RParen) {
                    val child = readOne() ?: break
                    items.add(child)
                }
                if (current is Token.RParen) advance()
                else throw WatException("unclosed list at $startPos")
                Sexpr.Node(startPos, items)
            }
            is Token.RParen -> { advance(); throw WatException("unexpected ')' at ${tok.pos}") }
            is Token.Atom -> { advance(); Sexpr.Sym(tok.pos, tok.text) }
            is Token.Str -> { advance(); Sexpr.Str(tok.pos, tok.value) }
        }
    }

    private fun advance() { current = if (it.hasNext()) it.next() else null }
}

public class WatException(message: String) : RuntimeException(message)
