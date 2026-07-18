package io.heapy.kwasm.wat

import io.heapy.kwasm.wat.Sexpr.*
import kotlin.math.*

/**
 * Writes WAT instruction forms into binary. Handles both unfolded
 * (`i32.add`) and folded (`(i32.add (i32.const 1) (i32.const 2))`) syntax,
 * control blocks with labels, and name resolution via the parent composer.
 */
internal class InstrWriter(
    private val out: BinaryWriter,
    private val composer: WatComposer,
) {
    private val labelStack = ArrayDeque<String>()

    private var seqItems: List<Sexpr> = emptyList()
    private var seqPos: Int = 0

    fun writeSeq(items: List<Sexpr>) {
        // Save/restore the cursor so nested folded control structures (which recurse
        // through writeSeq) do not clobber the enclosing instruction stream.
        val savedItems = seqItems
        val savedPos = seqPos
        seqItems = items
        seqPos = 0
        try {
            while (seqPos < items.size) {
                val node = items[seqPos]
                seqPos++
                when (node) {
                    is Sym -> writePlainWithImmediates(node)
                    is Node -> writeFolded(node)
                    is Str -> throw WatException("unexpected string in instruction: ${node.value}")
                }
            }
        } finally {
            seqItems = savedItems
            seqPos = savedPos
        }
    }

    /** Write an unfolded mnemonic, consuming immediate atoms that follow it. */
    private fun writePlainWithImmediates(s: Sym) {
        // Folded blocks (control) reached here mean a bare opcode like "block"; their
        // body/end come as separate tokens in the stream, handled by writeControlPlain.
        if (s.text == "block" || s.text == "loop" || s.text == "if" || s.text == "else" || s.text == "end") {
            writeControlPlain(s.text); return
        }
        val m = matchMnemonic(s.text) ?: throw WatException("unknown instruction '${s.text}'")
        val immediates = consumeImmediates(m)
        writeMatched(m, immediates)
    }

    /**
     * Consume the immediate operands of [m] from the remaining sequence. Immediate
     * count depends on the instruction kind; returns the consumed atoms/nodes.
     */
    private fun consumeImmediates(m: Match): List<Sexpr> {
        val n = immediateCount(m)
        val out = ArrayList<Sexpr>()
        repeat(n) {
            if (seqPos < seqItems.size) {
                out.add(seqItems[seqPos]); seqPos++
            }
        }
        return out
    }

    private fun immediateCount(m: Match): Int = when (m.kind) {
        MatchKind.I32_CONST, MatchKind.I64_CONST, MatchKind.F32_CONST, MatchKind.F64_CONST -> 1
        MatchKind.LOCAL_GET, MatchKind.LOCAL_SET, MatchKind.LOCAL_TEE,
        MatchKind.GLOBAL_GET, MatchKind.GLOBAL_SET -> 1
        MatchKind.BR, MatchKind.BR_IF -> 1
        MatchKind.CALL, MatchKind.RETURN_CALL, MatchKind.REF_FUNC -> 1
        MatchKind.REF_NULL -> 1
        MatchKind.TABLE_GET, MatchKind.TABLE_SET, MatchKind.TABLE_SIZE, MatchKind.TABLE_GROW, MatchKind.TABLE_FILL -> 1
        MatchKind.MEMORY_INIT, MatchKind.DATA_DROP, MatchKind.TABLE_INIT, MatchKind.ELEM_DROP -> 1
        // BR_TABLE, CALL_INDIRECT, RETURN_CALL_INDIRECT, memory ops, table.copy etc. consume variably.
        MatchKind.LOAD_STORE -> {
            // optional offset=/align= keywords
            var c = 0
            while (seqPos + c < seqItems.size && seqItems[seqPos + c] is Sym &&
                ((seqItems[seqPos + c] as Sym).text.startsWith("offset=") ||
                    (seqItems[seqPos + c] as Sym).text.startsWith("align="))
            ) c++
            c
        }
        MatchKind.BR_TABLE -> {
            // all following atoms until end of list or a Node
            var c = 0
            while (seqPos + c < seqItems.size && seqItems[seqPos + c] is Sym) c++
            c
        }
        MatchKind.CALL_INDIRECT, MatchKind.RETURN_CALL_INDIRECT -> {
            // optional $table then (type ...) — consume while next is $-name or a (type...) node
            var c = 0
            while (seqPos + c < seqItems.size) {
                val nx = seqItems[seqPos + c]
                if (nx is Sym && nx.text.startsWith("$")) c++
                else if (nx is Node && nx.items.firstOrNull() is Sym && (nx.items[0] as Sym).text == "type") c++
                else break
            }
            c
        }
        MatchKind.TABLE_COPY, MatchKind.MEMORY_COPY, MatchKind.MEMORY_FILL -> 0 // optional, handled in writeMatched
        else -> 0
    }

    /** Handle unfolded control instructions that span multiple stream tokens. */
    private fun writeControlPlain(kind: String) {
        when (kind) {
            "block" -> {
                out.writeByte(0x02)
                val label = readBlockLabelFromStream()
                val bt = readBlockTypeFromStream()
                writeBlockType(bt)
                labelStack.addLast(label ?: "label${labelStack.size}")
            }
            "loop" -> {
                out.writeByte(0x03)
                val label = readBlockLabelFromStream()
                val bt = readBlockTypeFromStream()
                writeBlockType(bt)
                labelStack.addLast(label ?: "label${labelStack.size}")
            }
            "if" -> {
                out.writeByte(0x04)
                val label = readBlockLabelFromStream()
                val bt = readBlockTypeFromStream()
                writeBlockType(bt)
                labelStack.addLast(label ?: "if${labelStack.size}")
            }
            "else" -> { out.writeByte(0x05) }
            "end" -> {
                out.writeByte(0x0B)
                if (labelStack.isNotEmpty()) labelStack.removeLast()
            }
        }
    }

    private fun readBlockLabelFromStream(): String? {
        val label = seqItems.getOrNull(seqPos) as? Sym
        if (label?.text?.startsWith("$") == true) {
            seqPos++
            return label.text
        }
        return null
    }

    /** Read an optional (result ...) / (type ...) block-type from the stream. */
    private fun readBlockTypeFromStream(): BlockType {
        if (seqPos < seqItems.size && seqItems[seqPos] is Node) {
            val nx = seqItems[seqPos] as Node
            val h = (nx.items.firstOrNull() as? Sym)?.text
            if (h == "type" || h == "result" || h == "param") {
                seqPos++
                return extractBlockType(listOf(nx))
            }
        }
        return BlockType(null, null)
    }

    private fun writeFolded(node: Node) {
        val head = (node.items.firstOrNull() as? Sym)?.text
            ?: throw WatException("expected mnemonic in folded instruction")
        // Control-flow folded forms need special handling: block/loop/if/then/else.
        when (head) {
            "block", "loop" -> {
                out.writeByte(if (head == "block") 0x02 else 0x03)
                val controlItems = node.items.drop(1)
                val bt = extractBlockType(controlItems)
                writeBlockType(bt)
                val body = extractBlockBody(controlItems)
                labelStack.addLast(extractBlockLabel(controlItems) ?: "label${labelStack.size}")
                writeSeq(body)
                labelStack.removeLast()
                out.writeByte(0x0B)
                return
            }
            "if" -> {
                // Folded (if blocktype cond... (then ...) (else ...)).
                // Binary order: cond instructions, then 0x04, blocktype, then-body, [0x05 else-body], 0x0B.
                val controlItems = node.items.drop(1)
                val bt = extractBlockType(controlItems)
                val body = extractBlockBody(controlItems)
                val thenParts = ArrayList<Sexpr>()
                val elseParts = ArrayList<Sexpr>()
                val cond = ArrayList<Sexpr>()
                var hasElse = false
                for (it in body) {
                    if (it is Node && it.items.firstOrNull() is Sym) {
                        val h = (it.items[0] as Sym).text
                        if (h == "then") { thenParts.addAll(it.items.drop(1)); continue }
                        if (h == "else") { hasElse = true; elseParts.addAll(it.items.drop(1)); continue }
                    }
                    // Operand/condition instruction appearing before (then...): it's part of cond.
                    cond.add(it)
                }
                // Condition must precede the if opcode.
                writeSeq(cond)
                out.writeByte(0x04)
                writeBlockType(bt)
                labelStack.addLast(extractBlockLabel(controlItems) ?: "if${labelStack.size}")
                writeSeq(thenParts)
                if (hasElse) {
                    out.writeByte(0x05)
                    writeSeq(elseParts)
                }
                labelStack.removeLast()
                out.writeByte(0x0B)
                return
            }
            "else" -> { out.writeByte(0x05); return }
            "end" -> { out.writeByte(0x0B); return }
        }

        // Generic folded form: the operands are folded children, written first.
        val m = matchMnemonic(head) ?: throw WatException("unknown folded instruction '$head'")
        // Extract folded operand children that are themselves lists (instruction operands).
        // Atoms after the mnemonic are immediates; lists before the (implicit) operands are operands.
        writeMatched(m, node.items.drop(1))
    }

    /**
     * Write an instruction match. [args] are the tokens following the mnemonic
     * in source order: immediates and (for folded forms) operand instructions.
     * For folded forms, operand sub-instructions are written first, then the opcode.
     */
    private fun writeMatched(m: Match, args: List<Sexpr>) {
        when (m.kind) {
            MatchKind.PLAIN -> out.writeByte(m.opcode)
            MatchKind.I32_CONST -> {
                val v = expectInt(args)
                out.writeByte(0x41); out.writeS32(v.toInt())
            }
            MatchKind.I64_CONST -> {
                val v = expectInt(args)
                out.writeByte(0x42); out.writeS64(v)
            }
            MatchKind.F32_CONST -> {
                val v = expectFloat(args)
                out.writeByte(0x43); out.writeF32(v.toFloat())
            }
            MatchKind.F64_CONST -> {
                val v = expectFloat(args)
                out.writeByte(0x44); out.writeF64(v)
            }
            MatchKind.LOCAL_GET, MatchKind.LOCAL_SET, MatchKind.LOCAL_TEE -> {
                val idx = resolveLocal(args.first())
                out.writeByte(m.opcode); out.writeU32(idx.toLong())
            }
            MatchKind.GLOBAL_GET, MatchKind.GLOBAL_SET -> {
                val idx = resolveGlobal(args.first())
                out.writeByte(m.opcode); out.writeU32(idx.toLong())
            }
            MatchKind.BR, MatchKind.BR_IF -> {
                val depth = resolveLabel(args.first())
                out.writeByte(m.opcode); out.writeU32(depth.toLong())
            }
            MatchKind.BR_TABLE -> {
                val labels = args.filter { it is Sym || it is Node }.map { resolveLabel(it) }
                val default = labels.last()
                val targets = labels.dropLast(1)
                out.writeByte(0x0E)
                out.writeU32(targets.size.toLong())
                for (t in targets) out.writeU32(t.toLong())
                out.writeU32(default.toLong())
            }
            MatchKind.CALL -> {
                val idx = resolveFunc(args.first())
                out.writeByte(0x10); out.writeU32(idx.toLong())
            }
            MatchKind.CALL_INDIRECT -> {
                // (call_indirect (type N)) or (call_indirect $table (type N))
                var tableIdx = 0
                var typeIdx = -1
                for (a in args) {
                    if (a is Node && a.items.firstOrNull() is Sym && (a.items[0] as Sym).text == "type") {
                        typeIdx = resolveType(a.items[1])
                    } else if (a is Sym && a.text.startsWith("$")) {
                        tableIdx = resolveTable(a)
                    }
                }
                if (typeIdx < 0) {
                    // build inline type
                    val params = args.filterIsInstance<Node>().filter { (it.items.firstOrNull() as? Sym)?.text == "param" }
                        .flatMap { it.items.drop(1).filterIsInstance<Sym>().map { s -> s.text } }
                    val results = args.filterIsInstance<Node>().filter { (it.items.firstOrNull() as? Sym)?.text == "result" }
                        .flatMap { it.items.drop(1).filterIsInstance<Sym>().map { s -> s.text } }
                    typeIdx = composer.addInlineType(params, results)
                }
                out.writeByte(0x11)
                out.writeU32(typeIdx.toLong()); out.writeU32(tableIdx.toLong())
            }
            MatchKind.RETURN_CALL -> { val idx = resolveFunc(args.first()); out.writeByte(0x12); out.writeU32(idx.toLong()) }
            MatchKind.RETURN_CALL_INDIRECT -> {
                var tableIdx = 0; var typeIdx = -1
                for (a in args) {
                    if (a is Node && a.items.firstOrNull() is Sym && (a.items[0] as Sym).text == "type") typeIdx = resolveType(a.items[1])
                    else if (a is Sym && a.text.startsWith("$")) tableIdx = resolveTable(a)
                }
                out.writeByte(0x13); out.writeU32(typeIdx.toLong()); out.writeU32(tableIdx.toLong())
            }
            MatchKind.REF_NULL -> { out.writeByte(0xD0); out.writeByte(0x70) }
            MatchKind.REF_FUNC -> { val idx = resolveFunc(args.first()); out.writeByte(0xD2); out.writeU32(idx.toLong()) }
            MatchKind.REF_IS_NULL -> out.writeByte(0xD1)
            MatchKind.MEMORY_SIZE -> { out.writeByte(0x3F); out.writeByte(0x00) }
            MatchKind.MEMORY_GROW -> { out.writeByte(0x40); out.writeByte(0x00) }
            MatchKind.MEMORY_COPY -> {
                out.writeByte(0xFC); out.writeU32(10)
                val (d, s) = twoMemArgs(args)
                out.writeU32(d.toLong()); out.writeU32(s.toLong())
            }
            MatchKind.MEMORY_FILL -> {
                out.writeByte(0xFC); out.writeU32(11)
                val (d, s) = twoMemArgs(args)
                out.writeU32(d.toLong()); out.writeU32(s.toLong())
            }
            MatchKind.MEMORY_INIT -> {
                val dataIdx = resolveData(args.first())
                out.writeByte(0xFC); out.writeU32(8)
                out.writeU32(dataIdx.toLong())
                val memIdx = if (args.size > 1 && args[1] is Sym) composer.parseNat((args[1] as Sym).text).toInt() else 0
                out.writeU32(memIdx.toLong())
            }
            MatchKind.DATA_DROP -> {
                val dataIdx = resolveData(args.first())
                out.writeByte(0xFC); out.writeU32(9); out.writeU32(dataIdx.toLong())
            }
            MatchKind.TABLE_GET -> { val idx = resolveTable(args.first()); out.writeByte(0x25); out.writeU32(idx.toLong()) }
            MatchKind.TABLE_SET -> { val idx = resolveTable(args.first()); out.writeByte(0x26); out.writeU32(idx.toLong()) }
            MatchKind.TABLE_SIZE -> { val idx = resolveTable(args.first()); out.writeByte(0xFC); out.writeU32(4); out.writeU32(idx.toLong()) }
            MatchKind.TABLE_GROW -> { val idx = resolveTable(args.first()); out.writeByte(0xFC); out.writeU32(7); out.writeU32(idx.toLong()) }
            MatchKind.TABLE_FILL -> { val idx = resolveTable(args.first()); out.writeByte(0xFC); out.writeU32(5); out.writeU32(idx.toLong()) }
            MatchKind.TABLE_COPY -> {
                out.writeByte(0xFC); out.writeU32(3)
                val (d, s) = twoTableArgs(args)
                out.writeU32(d.toLong()); out.writeU32(s.toLong())
            }
            MatchKind.TABLE_INIT -> {
                out.writeByte(0xFC); out.writeU32(6)
                val elemIdx = resolveElem(args.first())
                val (d, s) = twoTableArgs(args.drop(1))
                out.writeU32(elemIdx.toLong()); out.writeU32(d.toLong())
            }
            MatchKind.ELEM_DROP -> {
                val elemIdx = resolveElem(args.first())
                out.writeByte(0xFC); out.writeU32(1); out.writeU32(elemIdx.toLong())
            }
            MatchKind.LOAD_STORE -> {
                out.writeByte(m.opcode)
                val (align, offset) = memarg(args)
                out.writeU32(align.toLong()); out.writeU32(offset.toLong())
            }
        }
    }

    private fun expectInt(args: List<Sexpr>): Long {
        val v = args.first()
        return if (v is Sym) parseLit(v.text) else throw WatException("expected integer immediate")
    }

    private fun expectFloat(args: List<Sexpr>): Double {
        val v = args.first()
        return if (v is Sym) parseFloatLit(v.text) else throw WatException("expected float immediate")
    }

    /** Parse an integer literal: decimal, hex (0x), signed. Handles int/long ranges. */
    internal fun parseLit(s: String): Long {
        val t = s.trim()
        return when {
            t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLong(16)
            t.startsWith("-0x") || t.startsWith("-0X") -> -t.substring(3).toLong(16)
            else -> t.toLong()
        }
    }

    private fun parseFloatLit(s: String): Double {
        val t = s.trim()
        if (t.startsWith("nan")) return parseNan(t)
        if (t.startsWith("-nan")) return -parseNan(t.substring(1))
        if (t.startsWith("inf") || t == "inf") return Double.POSITIVE_INFINITY
        if (t.startsWith("-inf")) return Double.NEGATIVE_INFINITY
        if (t.startsWith("0x") || t.startsWith("0X") || t.startsWith("-0x") || t.startsWith("-0X")) {
            return parseHexFloat(t)
        }
        return t.toDouble()
    }

    private fun parseNan(t: String): Double {
        // nan, nan:canonical, nan:arithmetic, nan:0x...
        if (t == "nan") return Double.NaN
        if (t.startsWith("nan:0x")) {
            val payload = t.substring(6).toLong(16)
            val bits = (0x7FF8000000000000L) or (payload and 0x0007FFFFFFFFFFFFL)
            return Double.fromBits(bits)
        }
        return Double.NaN
    }

    private fun parseHexFloat(s: String): Double {
        // Best-effort hex float parse for spec test payloads.
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val without0x = body.removePrefix("0x").removePrefix("0X")
        val (mantissaPart, expPart) = if ("p" in without0x.lowercase()) {
            val p = without0x.lowercase().indexOf("p")
            without0x.substring(0, p) to without0x.substring(p + 1)
        } else without0x to "0"
        val (intPart, fracPart) = if ("." in mantissaPart) {
            val dot = mantissaPart.indexOf(".")
            mantissaPart.substring(0, dot) to mantissaPart.substring(dot + 1)
        } else mantissaPart to ""
        var value = 0.0
        for (c in intPart) value = value * 16 + (c.digitToIntOrNull(16) ?: 0)
        var frac = 1.0 / 16.0
        for (c in fracPart) { value += (c.digitToIntOrNull(16) ?: 0) * frac; frac /= 16.0 }
        val exp = expPart.toInt()
        value *= 2.0.pow(exp)
        return if (neg) -value else value
    }

    // ---- index resolution against current function scope ----
    private var currentLocals: List<Pair<String?, String>> = emptyList()

    internal fun setLocals(locals: List<Pair<String?, String>>) { currentLocals = locals }

    private fun resolveLocal(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) {
                val idx = currentLocals.indexOfFirst { it.first == n }
                if (idx >= 0) return idx
                throw WatException("unknown local $n")
            }
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected local index")
    }

    private fun resolveGlobal(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.globalIdxName(n) ?: throw WatException("unknown global $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected global index")
    }

    private fun resolveFunc(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.funcIdxName(n) ?: throw WatException("unknown func $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected func index")
    }

    private fun resolveType(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.typeIdxName(n) ?: throw WatException("unknown type $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected type index")
    }

    private fun resolveTable(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.tableIdxName(n) ?: throw WatException("unknown table $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected table index")
    }

    private fun resolveData(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.dataIdxName(n) ?: throw WatException("unknown data $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected data index")
    }

    private fun resolveElem(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return composer.elemIdxName(n) ?: throw WatException("unknown elem $n")
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected elem index")
    }

    private fun resolveLabel(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) {
                val fromTop = labelStack.toList().asReversed().indexOfFirst { it == n }
                if (fromTop < 0) throw WatException("unknown label $n")
                return fromTop
            }
            return composer.parseNat(n).toInt()
        }
        throw WatException("expected label depth")
    }

    private fun twoMemArgs(args: List<Sexpr>): Pair<Int, Int> {
        // args may be (memory $src $dst) or bare numbers; default (0,0)
        if (args.isEmpty()) return 0 to 0
        val first = args.first() as? Sym ?: return 0 to 0
        if (first.text.startsWith("$")) {
            val d = composer.memoryIdxName(first.text) ?: 0
            val s = if (args.size > 1 && args[1] is Sym) (composer.memoryIdxName((args[1] as Sym).text) ?: 0) else d
            return d to s
        }
        val d = composer.parseNat(first.text).toInt()
        val s = if (args.size > 1 && args[1] is Sym) composer.parseNat((args[1] as Sym).text).toInt() else d
        return d to s
    }

    private fun twoTableArgs(args: List<Sexpr>): Pair<Int, Int> {
        if (args.isEmpty()) return 0 to 0
        val first = args.first() as? Sym ?: return 0 to 0
        val d = if (first.text.startsWith("$")) (composer.tableIdxName(first.text) ?: 0) else composer.parseNat(first.text).toInt()
        val s = if (args.size > 1 && args[1] is Sym) {
            val v = args[1] as Sym
            if (v.text.startsWith("$")) (composer.tableIdxName(v.text) ?: 0) else composer.parseNat(v.text).toInt()
        } else d
        return d to s
    }

    private fun memarg(args: List<Sexpr>): Pair<Int, Long> {
        // args: offset= and align= keywords, or offset immediate.
        var offset = 0L
        var align = -1
        for (a in args) {
            if (a is Sym) {
                if (a.text.startsWith("offset=")) offset = composer.parseNat(a.text.substring(7))
                else if (a.text.startsWith("align=")) align = log2i(composer.parseNat(a.text.substring(6)).toInt())
            }
        }
        if (align < 0) align = 0
        return align to offset
    }

    private fun log2i(n: Int): Int {
        var v = n; var r = 0
        while (v > 1) { v = v shr 1; r++ }
        return r
    }

    // ---- block helpers ----
    private data class BlockType(val typeIdx: Int?, val result: String?)

    private fun extractBlockType(items: List<Sexpr>): BlockType {
        for (it in items) {
            if (it is Node && it.items.firstOrNull() is Sym) {
                val h = (it.items[0] as Sym).text
                if (h == "type") return BlockType(resolveType(it.items[1]), null)
                if (h == "result") return BlockType(null, (it.items[1] as Sym).text)
            }
        }
        return BlockType(null, null)
    }

    private fun extractBlockLabel(items: List<Sexpr>): String? =
        (items.firstOrNull() as? Sym)?.text?.takeIf { it.startsWith("$") }

    private fun extractBlockBody(items: List<Sexpr>): List<Sexpr> {
        // Body = items that are not type/param/result declarations.
        return items
            .drop(if (extractBlockLabel(items) != null) 1 else 0)
            .filterNot { it is Node && (it.items.firstOrNull() as? Sym)?.text in setOf("type", "param", "result") }
    }

    private fun writeBlockType(bt: BlockType) {
        when {
            bt.typeIdx != null -> out.writeS64(bt.typeIdx.toLong()) // type index (signed LEB)
            bt.result != null -> {
                val v = when (bt.result) { "i32" -> 0x7F; "i64" -> 0x7E; "f32" -> 0x7D; "f64" -> 0x7C; "v128" -> 0x7B; else -> 0x7F }
                out.writeByte(v)
            }
            else -> out.writeByte(0x40)
        }
    }
}

/** Instruction match descriptor produced by [matchMnemonic]. */
internal data class Match(val text: String, val kind: MatchKind, val opcode: Int = 0)
internal enum class MatchKind {
    PLAIN, I32_CONST, I64_CONST, F32_CONST, F64_CONST,
    LOCAL_GET, LOCAL_SET, LOCAL_TEE, GLOBAL_GET, GLOBAL_SET,
    BR, BR_IF, BR_TABLE, CALL, CALL_INDIRECT, RETURN_CALL, RETURN_CALL_INDIRECT,
    REF_NULL, REF_FUNC, REF_IS_NULL,
    MEMORY_SIZE, MEMORY_GROW, MEMORY_COPY, MEMORY_FILL, MEMORY_INIT, DATA_DROP,
    TABLE_GET, TABLE_SET, TABLE_SIZE, TABLE_GROW, TABLE_FILL, TABLE_COPY, TABLE_INIT, ELEM_DROP,
    LOAD_STORE,
}

private val MNEMONIC_TABLE: List<Triple<String, MatchKind, Int>> = buildMnemonicTable()

private fun buildMnemonicTable(): List<Triple<String, MatchKind, Int>> {
    val t = ArrayList<Triple<String, MatchKind, Int>>()
    fun plain(name: String, op: Int) { t.add(Triple(name, MatchKind.PLAIN, op)) }
    // control
    plain("unreachable", 0x00); plain("nop", 0x01)
    plain("return", 0x0F); plain("select", 0x1B)
    // comparisons / arithmetic - i32
    val i32 = listOf(
        "eqz" to 0x45, "eq" to 0x46, "ne" to 0x47, "lt_s" to 0x48, "lt_u" to 0x49,
        "gt_s" to 0x4A, "gt_u" to 0x4B, "le_s" to 0x4C, "le_u" to 0x4D, "ge_s" to 0x4E, "ge_u" to 0x4F,
        "add" to 0x6A, "sub" to 0x6B, "mul" to 0x6C, "div_s" to 0x6D, "div_u" to 0x6E,
        "rem_s" to 0x6F, "rem_u" to 0x70, "and" to 0x71, "or" to 0x72, "xor" to 0x73,
        "shl" to 0x74, "shr_s" to 0x75, "shr_u" to 0x76, "rotl" to 0x77, "rotr" to 0x78,
    )
    for ((suf, op) in i32) plain("i32.$suf", op)
    val i64 = listOf(
        "eqz" to 0x50, "eq" to 0x51, "ne" to 0x52, "lt_s" to 0x53, "lt_u" to 0x54,
        "gt_s" to 0x55, "gt_u" to 0x56, "le_s" to 0x57, "le_u" to 0x58, "ge_s" to 0x59, "ge_u" to 0x5A,
        "add" to 0x7C, "sub" to 0x7D, "mul" to 0x7E, "div_s" to 0x7F, "div_u" to 0x80,
        "rem_s" to 0x81, "rem_u" to 0x82, "and" to 0x83, "or" to 0x84, "xor" to 0x85,
        "shl" to 0x86, "shr_s" to 0x87, "shr_u" to 0x88, "rotl" to 0x89, "rotr" to 0x8A,
    )
    for ((suf, op) in i64) plain("i64.$suf", op)
    val f32 = listOf(
        "eq" to 0x5B, "ne" to 0x5C, "lt" to 0x5D, "gt" to 0x5E, "le" to 0x5F, "ge" to 0x60,
        "abs" to 0x8B, "neg" to 0x8C, "ceil" to 0x8D, "floor" to 0x8E, "trunc" to 0x8F,
        "nearest" to 0x90, "sqrt" to 0x91, "add" to 0x92, "sub" to 0x93, "mul" to 0x94,
        "div" to 0x95, "min" to 0x96, "max" to 0x97, "copysign" to 0x98,
    )
    for ((suf, op) in f32) plain("f32.$suf", op)
    val f64 = listOf(
        "eq" to 0x61, "ne" to 0x62, "lt" to 0x63, "gt" to 0x64, "le" to 0x65, "ge" to 0x66,
        "abs" to 0x99, "neg" to 0x9A, "ceil" to 0x9B, "floor" to 0x9C, "trunc" to 0x9D,
        "nearest" to 0x9E, "sqrt" to 0x9F, "add" to 0xA0, "sub" to 0xA1, "mul" to 0xA2,
        "div" to 0xA3, "min" to 0xA4, "max" to 0xA5, "copysign" to 0xA6,
    )
    for ((suf, op) in f64) plain("f64.$suf", op)
    // conversions
    plain("i32.wrap_i64", 0xA7)
    plain("i32.trunc_f32_s", 0xA8); plain("i32.trunc_f32_u", 0xA9)
    plain("i32.trunc_f64_s", 0xAA); plain("i32.trunc_f64_u", 0xAB)
    plain("i64.extend_i32_s", 0xAC); plain("i64.extend_i32_u", 0xAD)
    plain("i64.trunc_f32_s", 0xAE); plain("i64.trunc_f32_u", 0xAF)
    plain("i64.trunc_f64_s", 0xB0); plain("i64.trunc_f64_u", 0xB1)
    plain("f32.convert_i32_s", 0xB2); plain("f32.convert_i32_u", 0xB3)
    plain("f32.convert_i64_s", 0xB4); plain("f32.convert_i64_u", 0xB5)
    plain("f32.demote_f64", 0xB6)
    plain("f64.convert_i32_s", 0xB7); plain("f64.convert_i32_u", 0xB8)
    plain("f64.convert_i64_s", 0xB9); plain("f64.convert_i64_u", 0xBA)
    plain("f64.promote_f32", 0xBB)
    plain("i32.reinterpret_f32", 0xBC); plain("i64.reinterpret_f64", 0xBD)
    plain("f32.reinterpret_i32", 0xBE); plain("f64.reinterpret_i64", 0xBF)
    // sign-extension
    plain("i32.extend8_s", 0xC0); plain("i32.extend16_s", 0xC1)
    plain("i64.extend8_s", 0xC2); plain("i64.extend16_s", 0xC3); plain("i64.extend32_s", 0xC4)
    // non-trapping float-to-int
    plain("i32.trunc_sat_f32_s", -0xFC00); plain("i32.trunc_sat_f32_u", -0xFC01)
    plain("i32.trunc_sat_f64_s", -0xFC02); plain("i32.trunc_sat_f64_u", -0xFC03)
    plain("i64.trunc_sat_f32_s", -0xFC04); plain("i64.trunc_sat_f32_u", -0xFC05)
    plain("i64.trunc_sat_f64_s", -0xFC06); plain("i64.trunc_sat_f64_u", -0xFC07)
    // drop
    plain("drop", 0x1A)
    // memory load/store mnemonics (resolved in matchMnemonic)
    return t
}

internal fun matchMnemonic(text: String): Match? {
    // constants
    if (text == "i32.const") return Match(text, MatchKind.I32_CONST)
    if (text == "i64.const") return Match(text, MatchKind.I64_CONST)
    if (text == "f32.const") return Match(text, MatchKind.F32_CONST)
    if (text == "f64.const") return Match(text, MatchKind.F64_CONST)
    // variable
    if (text == "local.get") return Match(text, MatchKind.LOCAL_GET, 0x20)
    if (text == "local.set") return Match(text, MatchKind.LOCAL_SET, 0x21)
    if (text == "local.tee") return Match(text, MatchKind.LOCAL_TEE, 0x22)
    if (text == "global.get") return Match(text, MatchKind.GLOBAL_GET, 0x23)
    if (text == "global.set") return Match(text, MatchKind.GLOBAL_SET, 0x24)
    // control
    if (text == "br") return Match(text, MatchKind.BR, 0x0C)
    if (text == "br_if") return Match(text, MatchKind.BR_IF, 0x0D)
    if (text == "br_table") return Match(text, MatchKind.BR_TABLE, 0x0E)
    if (text == "call") return Match(text, MatchKind.CALL, 0x10)
    if (text == "call_indirect") return Match(text, MatchKind.CALL_INDIRECT, 0x11)
    if (text == "return_call") return Match(text, MatchKind.RETURN_CALL, 0x12)
    if (text == "return_call_indirect") return Match(text, MatchKind.RETURN_CALL_INDIRECT, 0x13)
    // references
    if (text == "ref.null") return Match(text, MatchKind.REF_NULL, 0xD0)
    if (text == "ref.func") return Match(text, MatchKind.REF_FUNC, 0xD2)
    if (text == "ref.is_null") return Match(text, MatchKind.REF_IS_NULL, 0xD1)
    // memory bulk
    if (text == "memory.size") return Match(text, MatchKind.MEMORY_SIZE)
    if (text == "memory.grow") return Match(text, MatchKind.MEMORY_GROW)
    if (text == "memory.copy") return Match(text, MatchKind.MEMORY_COPY)
    if (text == "memory.fill") return Match(text, MatchKind.MEMORY_FILL)
    if (text == "memory.init") return Match(text, MatchKind.MEMORY_INIT)
    if (text == "data.drop") return Match(text, MatchKind.DATA_DROP)
    // table
    if (text == "table.get") return Match(text, MatchKind.TABLE_GET)
    if (text == "table.set") return Match(text, MatchKind.TABLE_SET)
    if (text == "table.size") return Match(text, MatchKind.TABLE_SIZE)
    if (text == "table.grow") return Match(text, MatchKind.TABLE_GROW)
    if (text == "table.fill") return Match(text, MatchKind.TABLE_FILL)
    if (text == "table.copy") return Match(text, MatchKind.TABLE_COPY)
    if (text == "table.init") return Match(text, MatchKind.TABLE_INIT)
    if (text == "elem.drop") return Match(text, MatchKind.ELEM_DROP)
    // memory load/store
    val memOp = MEM_OPS[text]
    if (memOp != null) return Match(text, MatchKind.LOAD_STORE, memOp)
    // non-trapping trunc_sat
    val sat = SAT_OPS[text]
    if (sat != null) {
        // FC-prefixed: handled specially
        return Match(text, MatchKind.PLAIN, sat)
    }
    // plain table lookup
    for ((name, kind, op) in MNEMONIC_TABLE) {
        if (name == text) {
            if (op < 0) {
                // FC-prefixed saturating op
                return Match(text, MatchKind.PLAIN, op)
            }
            return Match(name, kind, op)
        }
    }
    return null
}

internal val MEM_OPS: Map<String, Int> = mapOf(
    "i32.load" to 0x28, "i64.load" to 0x29, "f32.load" to 0x2A, "f64.load" to 0x2B,
    "i32.load8_s" to 0x2C, "i32.load8_u" to 0x2D, "i32.load16_s" to 0x2E, "i32.load16_u" to 0x2F,
    "i64.load8_s" to 0x30, "i64.load8_u" to 0x31, "i64.load16_s" to 0x32, "i64.load16_u" to 0x33,
    "i64.load32_s" to 0x34, "i64.load32_u" to 0x35,
    "i32.store" to 0x36, "i64.store" to 0x37, "f32.store" to 0x38, "f64.store" to 0x39,
    "i32.store8" to 0x3A, "i32.store16" to 0x3B,
    "i64.store8" to 0x3C, "i64.store16" to 0x3D, "i64.store32" to 0x3E,
)

internal val SAT_OPS: Map<String, Int> = mapOf(
    "i32.trunc_sat_f32_s" to 0, "i32.trunc_sat_f32_u" to 1,
    "i32.trunc_sat_f64_s" to 2, "i32.trunc_sat_f64_u" to 3,
    "i64.trunc_sat_f32_s" to 4, "i64.trunc_sat_f32_u" to 5,
    "i64.trunc_sat_f64_s" to 6, "i64.trunc_sat_f64_u" to 7,
)
