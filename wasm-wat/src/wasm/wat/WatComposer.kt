package io.heapy.kwasm.wat

import io.heapy.kwasm.wat.Sexpr.*

/**
 * Composes a parsed WAT module S-expression into WebAssembly binary bytes.
 *
 * This implements the text-to-binary translation (wat2wasm) for the constructs
 * used by the WebAssembly spec tests: type/import/func/table/memory/global/export
 * declarations, instructions (both unfolded and folded forms), named locals,
 * labels, and abbreviations.
 *
 * The output bytes are fed back through [wasm.core.ModuleDecoder] so that the
 * core decoder and this composer are validated against each other.
 */
public class WatComposer(private val module: Node) {

    /** Resolve identifiers to indices via these name maps. */
    private val typeNames = mutableMapOf<String, Int>()
    private val funcNames = mutableMapOf<String, Int>()
    private val tableNames = mutableMapOf<String, Int>()
    private val memoryNames = mutableMapOf<String, Int>()
    private val globalNames = mutableMapOf<String, Int>()
    private val elemNames = mutableMapOf<String, Int>()
    private val dataNames = mutableMapOf<String, Int>()

    private val types = mutableListOf<FuncTypeSig>()
    private val importFuncTypeIdx = mutableListOf<Int>()
    private val imports = mutableListOf<ImportSpec>()
    private val funcs = mutableListOf<FuncSpec>()
    private val tables = mutableListOf<TableSpec>()
    private val memories = mutableListOf<MemorySpec>()
    private val globals = mutableListOf<GlobalSpec>()
    private val exports = mutableListOf<ExportSpec>()
    private val elems = mutableListOf<ElemSpec>()
    private val dataSegments = mutableListOf<DataSpec>()
    private var startFunc: Int? = null

    private data class FuncTypeSig(val params: List<Pair<String?, String>>, val results: List<String>)
    private data class ImportSpec(val mod: String, val nm: String, val kind: String, val desc: String, val typeIdx: Int = -1)
    private data class FuncSpec(
        val typeIdx: Int,
        val parameters: List<Pair<String?, String>>,
        val locals: List<Pair<String?, String>>,
        val body: List<Sexpr>,
        val inlineExport: MutableList<String> = mutableListOf(),
    )
    private data class TableSpec(val elemType: String, val min: Long, val max: Long?, val inlineElem: List<List<Sexpr>>? = null, val offset: List<Sexpr>? = null)
    private data class MemorySpec(val min: Long, val max: Long?, val is64: Boolean = false, val shared: Boolean = false, val inlineData: Pair<List<Sexpr>, ByteArray>? = null)
    private data class GlobalSpec(val type: String, val mut: Boolean, val init: List<Sexpr>, val inlineExport: MutableList<String> = mutableListOf())
    private data class ExportSpec(val name: String, val kind: String, val idx: Int)
    private data class ElemSpec(
        val tableIdx: Int, val mode: ElemModeSpec, val items: List<List<Sexpr>>, val elemType: String,
    )
    private sealed class ElemModeSpec {
        data class Active(val offset: List<Sexpr>) : ElemModeSpec()
        object Passive : ElemModeSpec()
        object Declarative : ElemModeSpec()
    }
    private data class DataSpec(val memIdx: Int, val mode: DataModeSpec, val init: ByteArray) {
        override fun equals(other: Any?): Boolean = other is DataSpec && mode == other.mode && init.contentEquals(other.init)
        override fun hashCode(): Int = init.contentHashCode()
    }
    private sealed class DataModeSpec {
        data class Active(val offset: List<Sexpr>) : DataModeSpec()
        object Passive : DataModeSpec()
    }

    public fun compose(): ByteArray {
        // Two passes: first resolve declarations & names, then emit.
        for (item in module.items) {
            if (item is Node) processTopLevel(item)
        }
        assignImportedFunctionIndices()
        return emit()
    }

    private fun processTopLevel(node: Node) {
        val head = node.items.firstOrNull() as? Sym ?: return
        when (head.text) {
            "type" -> processType(node)
            "import" -> processImport(node)
            "func" -> processFunc(node)
            "table" -> processTable(node)
            "memory" -> processMemory(node)
            "global" -> processGlobal(node)
            "export" -> processExport(node)
            "elem" -> processElem(node)
            "data" -> processData(node)
            "start" -> {
                val target = node.items[1]
                startFunc = resolveFuncIdx(target)
            }
            else -> { /* ignore unknown top-level forms */ }
        }
    }

    // ---- type ----
    private fun processType(node: Node) {
        val items = node.items
        // (type $name? (func ...)) or (type $name? (struct/...)) unsupported beyond func
        var i = 1
        var name: String? = null
        if (items[1] is Sym && (items[1] as Sym).text.startsWith("$")) {
            name = (items[1] as Sym).text
            i = 2
        }
        val funcNode = items[i] as? Node ?: throw WatException("expected (func) in type")
        val sig = parseFuncSig(funcNode)
        types.add(sig)
        if (name != null) typeNames[name] = types.size - 1
    }

    private fun parseFuncSig(funcNode: Node): FuncTypeSig {
        val params = ArrayList<Pair<String?, String>>()
        val results = ArrayList<String>()
        for (it in funcNode.items.drop(1)) {
            if (it !is Node) continue
            val h = (it.items.firstOrNull() as? Sym)?.text
            when (h) {
                "param" -> {
                    val rest = it.items.drop(1)
                    if (rest.size == 1 && rest[0] is Sym && !(rest[0] as Sym).isValType()) {
                        // (param $name type)
                        val (n, t) = rest[0] to (rest.getOrNull(1) as? Sym)?.text
                        params.add((n as Sym).text to (t ?: "i32"))
                    } else {
                        // (param type type ...) or (param $name type)
                        val first = rest.firstOrNull()
                        if (first is Sym && first.text.startsWith("$")) {
                            params.add(first.text to ((rest[1] as Sym).text))
                        } else {
                            for (t in rest) params.add(null to (t as Sym).text)
                        }
                    }
                }
                "result" -> for (t in it.items.drop(1)) results.add((t as Sym).text)
            }
        }
        return FuncTypeSig(params, results)
    }

    private fun Sym.isValType(): Boolean = text in setOf("i32", "i64", "f32", "f64", "v128", "funcref", "externref")

    // ---- import ----
    private fun processImport(node: Node) {
        // (import "mod" "name" (func $name? (type N)|(param...)(result...)))
        val mod = (node.items[1] as Str).value
        val nm = (node.items[2] as Str).value
        val descNode = node.items[3] as Node
        val kind = (descNode.items[0] as Sym).text
        when (kind) {
            "func" -> {
                val name = descNode.items.firstOrNull { it is Sym && it.text.startsWith("$") } as? Sym
                val typeIdx = resolveFuncTypeFromFuncDesc(descNode)
                if (name != null) funcNames[name.text] = -(imports.size + 1) // resolved later
                imports.add(ImportSpec(mod, nm, "func", "", typeIdx))
            }
            "table" -> {
                val (et, min, max) = parseTableTypeItems(descNode.items.drop(1))
                val name = descNode.items.firstOrNull { it is Sym && it.text.startsWith("$") } as? Sym
                if (name != null) tableNames[name.text] = imports.count { it.kind == "table" }
                imports.add(ImportSpec(mod, nm, "table", "$et:$min:${max ?: -1}"))
            }
            "memory" -> {
                val (min, max, is64, shared) = parseMemoryTypeItems(descNode.items.drop(1))
                val name = descNode.items.firstOrNull { it is Sym && it.text.startsWith("$") } as? Sym
                if (name != null) memoryNames[name.text] = imports.count { it.kind == "memory" }
                imports.add(ImportSpec(mod, nm, "memory", "$min:${max ?: -1}:$is64:$shared"))
            }
            "global" -> {
                val (type, mut) = parseGlobalTypeItems(descNode.items.drop(1))
                val name = descNode.items.firstOrNull { it is Sym && it.text.startsWith("$") } as? Sym
                if (name != null) globalNames[name.text] = imports.count { it.kind == "global" }
                imports.add(ImportSpec(mod, nm, "global", "$type:$mut"))
            }
        }
    }

    private fun resolveFuncTypeFromFuncDesc(descNode: Node): Int {
        // (func $name? (type N) | (param...) (result...) | body)
        for (it in descNode.items.drop(1)) {
            if (it is Node && it.items.firstOrNull() is Sym && (it.items[0] as Sym).text == "type") {
                val target = it.items[1]
                return resolveTypeIdx(target)
            }
        }
        // Inline signature: build a synthetic type.
        val sig = parseFuncSig(descNode)
        types.add(sig)
        return types.size - 1
    }

    // ---- func ----
    private fun processFunc(node: Node) {
        val items = node.items
        var i = 1
        var name: String? = null
        if (items.getOrNull(1) is Sym && (items[1] as Sym).text.startsWith("$")) {
            name = (items[1] as Sym).text
            i = 2
        }
        var typeIdx = -1
        val inlineParams = ArrayList<Pair<String?, String>>()
        val inlineResults = ArrayList<String>()
        val locals = ArrayList<Pair<String?, String>>()
        val body = ArrayList<Sexpr>()
        val inlineExports = mutableListOf<String>()

        while (i < items.size) {
            val it = items[i]
            if (it is Node) {
                val h = (it.items.firstOrNull() as? Sym)?.text
                when (h) {
                    "type" -> typeIdx = resolveTypeIdx(it.items[1])
                    "param" -> {
                        val rest = it.items.drop(1)
                        val first = rest.firstOrNull()
                        if (first is Sym && first.text.startsWith("$")) {
                            inlineParams.add(first.text to ((rest[1] as Sym).text))
                        } else {
                            for (t in rest) inlineParams.add(null to (t as Sym).text)
                        }
                    }
                    "result" -> for (t in it.items.drop(1)) inlineResults.add((t as Sym).text)
                    "local" -> {
                        val rest = it.items.drop(1)
                        val first = rest.firstOrNull()
                        if (first is Sym && first.text.startsWith("$")) {
                            locals.add(first.text to ((rest[1] as Sym).text))
                        } else {
                            for (t in rest) locals.add(null to (t as Sym).text)
                        }
                    }
                    "export" -> inlineExports.add((it.items[1] as Str).value)
                    else -> body.add(it) // instruction (folded) or remaining body
                }
            } else if (it is Sym) {
                body.add(it) // unfolded instruction atom
            }
            i++
        }

        if (typeIdx < 0) {
            val sig = FuncTypeSig(inlineParams, inlineResults)
            // Deduplicate identical types (spec may share). Simple equality add:
            val existing = types.indexOfFirst { it == sig }
            typeIdx = if (existing >= 0) existing else { types.add(sig); types.size - 1 }
        }

        val spec = FuncSpec(typeIdx, inlineParams, locals, body, inlineExports)
        funcs.add(spec)
        if (name != null) funcNames[name] = funcs.size - 1
    }

    // ---- table ----
    private fun processTable(node: Node) {
        var i = 1
        var name: String? = null
        if (node.items[1] is Sym && (node.items[1] as Sym).text.startsWith("$")) {
            name = (node.items[1] as Sym).text; i = 2
        }
        // Could be (table $n min max funcref) or (table funcref (elem ...)) for inline
        val rest = node.items.drop(i)
        val (et, min, max) = parseTableTypeItems(rest.filter { it !is Node })
        // inline element via (elem ...) subform
        var inline: List<List<Sexpr>>? = null
        var offset: List<Sexpr>? = null
        for (it in rest) {
            if (it is Node && it.items.firstOrNull() is Sym && (it.items[0] as Sym).text == "elem") {
                // (table funcref (elem $f0 $f1 ...)) inline form: collect items.
                inline = it.items.drop(1).map { item -> listOf(item) }
                offset = listOf(Sym(0, "i32.const"), Sym(0, "0"))
            }
        }
        tables.add(TableSpec(et, min, max, inline, offset))
        if (name != null) tableNames[name] = tables.size - 1
    }

    private fun parseTableTypeItems(items: List<Sexpr>): Triple<String, Long, Long?> {
        var et = "funcref"
        var min = 0L; var max: Long? = null
        val nums = ArrayList<Long>()
        for (it in items) {
            if (it is Sym) {
                val t = it.text
                when {
                    t == "funcref" || t == "anyfunc" || t == "func" -> et = "funcref"
                    t == "externref" -> et = "externref"
                    else -> nums.add(parseNat(t))
                }
            }
        }
        min = nums.firstOrNull() ?: 0L
        if (nums.size > 1) max = nums[1]
        return Triple(et, min, max)
    }

    // ---- memory ----
    private fun processMemory(node: Node) {
        var i = 1
        var name: String? = null
        if (node.items[1] is Sym && (node.items[1] as Sym).text.startsWith("$")) {
            name = (node.items[1] as Sym).text; i = 2
        }
        val rest = node.items.drop(i)
        // inline data via (data "...") form
        var inlineData: Pair<List<Sexpr>, ByteArray>? = null
        for (it in rest) {
            if (it is Node && it.items.firstOrNull() is Sym && (it.items[0] as Sym).text == "data") {
                val dataStr = (it.items[1] as Str).value
                inlineData = listOf(Sym(0, "i32.const"), Sym(0, "0")) to dataStr.encodeToByteArray()
            }
        }
        val (min, max, is64, shared) = parseMemoryTypeItems(rest.filter { it !is Node })
        memories.add(MemorySpec(min, max, is64, shared, inlineData))
        if (name != null) memoryNames[name] = memories.size - 1
    }

    private fun parseMemoryTypeItems(items: List<Sexpr>): Quad<Long, Long?, Boolean, Boolean> {
        val nums = ArrayList<Long>()
        var is64 = false
        var shared = false
        for (it in items) {
            if (it is Sym) {
                when (it.text) {
                    "i64" -> is64 = true
                    "shared" -> shared = true
                    else -> nums.add(parseNat(it.text))
                }
            }
        }
        val min = nums.firstOrNull() ?: 0L
        val max = if (nums.size > 1) nums[1] else null
        return Quad(min, max, is64, shared)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // ---- global ----
    private fun processGlobal(node: Node) {
        var i = 1
        var name: String? = null
        if (node.items[1] is Sym && (node.items[1] as Sym).text.startsWith("$")) {
            name = (node.items[1] as Sym).text; i = 2
        }
        val (type, mut) = parseGlobalTypeItems(listOf(node.items[i]))
        i++
        val init = node.items.drop(i)
        val inlineExports = mutableListOf<String>()
        val realInit = init.filter { c ->
            if (c is Node && c.items.firstOrNull() is Sym && (c.items[0] as Sym).text == "export") {
                inlineExports.add((c.items[1] as Str).value); false
            } else true
        }
        globals.add(GlobalSpec(type, mut, realInit, inlineExports))
        if (name != null) globalNames[name] = globals.size - 1
    }

    private fun parseGlobalTypeItems(items: List<Sexpr>): Pair<String, Boolean> {
        // Either "i32" or (mut i32)
        val first = items.first()
        return if (first is Node) {
            val inner = first.items
            ((inner[1] as Sym).text to true)
        } else {
            ((first as Sym).text to false)
        }
    }

    // ---- export ----
    private fun processExport(node: Node) {
        val name = (node.items[1] as Str).value
        val desc = node.items[2] as Node
        val kind = (desc.items[0] as Sym).text
        val idx = when (kind) {
            "func" -> resolveFuncIdx(desc.items[1])
            "table" -> resolveIdx(desc.items[1], tableNames)
            "memory" -> resolveIdx(desc.items[1], memoryNames)
            "global" -> resolveIdx(desc.items[1], globalNames)
            else -> throw WatException("unknown export kind $kind")
        }
        exports.add(ExportSpec(name, kind, idx))
    }

    // ---- elem ----
    private fun processElem(node: Node) {
        val items = node.items
        var i = 1
        var tableIdx = 0
        var mode: ElemModeSpec = ElemModeSpec.Passive
        var elemType = "funcref"
        val useExpr = false
        val itemsList = ArrayList<List<Sexpr>>()

        // First, optional table index ($name or number), then offset (table) | declare | passive
        when {
            items.getOrNull(i) is Sym && (items[i] as Sym).text.startsWith("$") -> {
                tableIdx = resolveIdx(items[i], tableNames); i++
                mode = ElemModeSpec.Active(listOf(items[i] as Sexpr)); i++
            }
            items.getOrNull(i) is Sym && (items[i] as Sym).text == "declare" -> {
                mode = ElemModeSpec.Declarative; i++
            }
            items.getOrNull(i) is Sym && (items[i] as Sym).text == "func" -> {
                // (elem func ...) explicit func kind
                i++
            }
            items.getOrNull(i) is Node -> {
                // offset expression for active elem into table 0
                mode = ElemModeSpec.Active(listOf(items[i] as Sexpr)); i++
            }
            else -> {
                // numeric table index then offset, or passive/declarative keywords
            }
        }

        // Remaining items: optional element-type marker + items
        val rest = items.drop(i)
        for (r in rest) {
            if (r is Sym) {
                when (r.text) { "func" -> elemType = "funcref"; "extern" -> elemType = "externref" }
            } else if (r is Node) {
                // either offset expr (already consumed) or (item ...) or func reference
                val head = (r.items.firstOrNull() as? Sym)?.text
                if (head == "item") {
                    itemsList.add(r.items.drop(1))
                } else if (head == "offset") {
                    // (offset expr...)
                    if (mode is ElemModeSpec.Active) {
                        mode = ElemModeSpec.Active(r.items.drop(1))
                    } else {
                        mode = ElemModeSpec.Active(r.items.drop(1))
                    }
                } else {
                    // Could be a folded const expr for offset (first occurrence) — treat as offset,
                    // or func reference. Heuristic: if mode still Passive/Declarative and this looks
                    // like a const expr, set it as offset.
                    if (mode !is ElemModeSpec.Active) {
                        mode = ElemModeSpec.Active(listOf(r))
                    } else {
                        itemsList.add(listOf(r))
                    }
                }
            } else if (r is Str) {
                // not expected
            }
        }

        elems.add(ElemSpec(tableIdx, mode, itemsList, elemType))
        if (items.getOrNull(1) is Sym && (items[1] as Sym).text.startsWith("$")) {
            elemNames[(items[1] as Sym).text] = elems.size - 1
        }
    }

    // ---- data ----
    private fun processData(node: Node) {
        val items = node.items
        var i = 1
        var memIdx = 0
        var mode: DataModeSpec = DataModeSpec.Active(listOf(Sym(0, "i32.const"), Sym(0, "0")))
        var name: String? = null
        if (items.getOrNull(i) is Sym && (items[i] as Sym).text.startsWith("$")) {
            name = (items[i] as Sym).text; i++
        }
        when {
            items.getOrNull(i) is Node && (items[i] as Node).items.firstOrNull() is Sym &&
                ((items[i] as Node).items[0] as Sym).text == "offset" -> {
                mode = DataModeSpec.Active((items[i] as Node).items.drop(1)); i++
            }
            items.getOrNull(i) is Node -> {
                // folded offset expression
                mode = DataModeSpec.Active(listOf(items[i] as Sexpr)); i++
            }
            items.getOrNull(i) is Sym && (items[i] as Sym).text == "passive" -> {
                mode = DataModeSpec.Passive; i++
            }
            else -> {
                // numeric mem index then offset
                if (items.getOrNull(i) is Sym) {
                    memIdx = parseNat((items[i] as Sym).text).toInt(); i++
                    mode = DataModeSpec.Active(listOf(items[i] as Sexpr)); i++
                }
            }
        }
        // remaining string(s) concatenate
        val sb = StringBuilder()
        for (r in items.drop(i)) {
            if (r is Str) sb.append(r.value)
        }
        // String in WAT data uses byte escapes; encodeUtf8 already preserves bytes for ascii,
        // but data segments need raw bytes. Convert via latin-1-ish per-char since escapes are bytes.
        val bytes = wabtStringToBytes(sb.toString())
        dataSegments.add(DataSpec(memIdx, mode, bytes))
        if (name != null) dataNames[name] = dataSegments.size - 1
    }

    private fun wabtStringToBytes(s: String): ByteArray {
        // WAT string escapes were decoded by the lexer into chars; for data we need raw bytes.
        // Map each char back to a byte (latin-1), since \xx escapes become chars 0..255.
        return ByteArray(s.length) { s[it].code.toByte() }
    }

    // ---- index resolution ----
    private fun resolveFuncIdx(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return funcNames[n] ?: throw WatException("unknown func $n")
            return parseNat(n).toInt()
        }
        throw WatException("expected func index")
    }

    private fun resolveTypeIdx(node: Sexpr): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return typeNames[n] ?: throw WatException("unknown type $n")
            return parseNat(n).toInt()
        }
        throw WatException("expected type index")
    }

    private fun resolveIdx(node: Sexpr, map: Map<String, Int>): Int {
        if (node is Sym) {
            val n = node.text
            if (n.startsWith("$")) return map[n] ?: throw WatException("unknown index $n")
            return parseNat(n).toInt()
        }
        throw WatException("expected index")
    }

    /** After imports are known, finalize funcNames for imported funcs. */
    private fun assignImportedFunctionIndices() {
        val funcImports = imports.withIndex().filter { it.value.kind == "func" }
        // Each func import occupies one index in the func index space; names resolved in order.
        var importIdx = 0
        for ((idx, imp) in imports.withIndex()) {
            if (imp.kind != "func") continue
            // name lookup: search original import node? We didn't store names; rely on funcNames
            // entries with negative placeholder values mapping to import order.
            importIdx++
        }
        // Rewrite negative placeholders to absolute indices (imports come before locals).
        val negativeEntries = funcNames.entries.filter { it.value < 0 }
        // Sort by negative magnitude (insertion order).
        val sortedByOrder = negativeEntries.sortedByDescending { it.value }
        // Map placeholder -(k+1) -> kth imported func index (k is 0-based among func imports).
        val funcImportCount = imports.count { it.kind == "func" }
        for (entry in funcNames.entries.toList()) {
            if (entry.value < 0) {
                // -(k+1) means kth func import
                val k = -entry.value - 1
                funcNames[entry.key] = k
            }
        }
    }

    // ---- emit binary ----
    private fun emit(): ByteArray {
        val w = BinaryWriter()
        // magic + version
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))

        // Type section
        if (types.isNotEmpty()) {
            w.writeSection(1) {
                writeU32(types.size.toLong())
                for (t in types) {
                    writeByte(0x60)
                    writeU32(t.params.size.toLong())
                    for (p in t.params) writeValType(p.second)
                    writeU32(t.results.size.toLong())
                    for (r in t.results) writeValType(r)
                }
            }
        }

        // Import section
        if (imports.isNotEmpty()) {
            w.writeSection(2) {
                writeU32(imports.size.toLong())
                for (imp in imports) {
                    writeName(imp.mod)
                    writeName(imp.nm)
                    when (imp.kind) {
                        "func" -> { writeByte(0x00); writeU32(imp.typeIdx.toLong()) }
                        "table" -> {
                            writeByte(0x01)
                            val parts = imp.desc.split(":")
                            writeTableType(parts[0], parts[1].toLong(), if (parts[2] == "-1") null else parts[2].toLong())
                        }
                        "memory" -> {
                            writeByte(0x02)
                            val parts = imp.desc.split(":")
                            writeMemoryType(parts[0].toLong(), if (parts[1] == "-1") null else parts[1].toLong(), parts[2] == "true", parts[3] == "true")
                        }
                        "global" -> {
                            writeByte(0x03)
                            val parts = imp.desc.split(":")
                            writeGlobalType(parts[0], parts[1] == "true")
                        }
                    }
                }
            }
        }

        // Function section (type indices for local functions)
        if (funcs.isNotEmpty()) {
            w.writeSection(3) {
                writeU32(funcs.size.toLong())
                for (f in funcs) writeU32(f.typeIdx.toLong())
            }
        }

        // Table section
        if (tables.isNotEmpty()) {
            w.writeSection(4) {
                writeU32(tables.size.toLong())
                for (t in tables) writeTableType(t.elemType, t.min, t.max)
            }
        }

        // Memory section
        if (memories.isNotEmpty()) {
            w.writeSection(5) {
                writeU32(memories.size.toLong())
                for (m in memories) writeMemoryType(m.min, m.max, m.is64, m.shared)
            }
        }

        // Global section
        if (globals.isNotEmpty()) {
            w.writeSection(6) {
                writeU32(globals.size.toLong())
                for (g in globals) {
                    writeGlobalType(g.type, g.mut)
                    writeConstExpr(g.init)
                    writeByte(0x0B)
                }
            }
        }

        // Export section: explicit exports + inline exports from funcs/globals
        val allExports = ArrayList(exports)
        var importedFuncBase = 0
        val funcImportCount = imports.count { it.kind == "func" }
        val tableImportCount = imports.count { it.kind == "table" }
        val memImportCount = imports.count { it.kind == "memory" }
        val globalImportCount = imports.count { it.kind == "global" }
        for ((i, f) in funcs.withIndex()) {
            for (ex in f.inlineExport) {
                allExports.add(ExportSpec(ex, "func", funcImportCount + i))
            }
        }
        for ((i, g) in globals.withIndex()) {
            for (ex in g.inlineExport) {
                allExports.add(ExportSpec(ex, "global", globalImportCount + i))
            }
        }
        if (allExports.isNotEmpty()) {
            w.writeSection(7) {
                writeU32(allExports.size.toLong())
                for (e in allExports) {
                    writeName(e.name)
                    val kindByte = when (e.kind) { "func" -> 0; "table" -> 1; "memory" -> 2; "global" -> 3; else -> 0 }
                    writeByte(kindByte)
                    writeU32(e.idx.toLong())
                }
            }
        }

        // Start section
        if (startFunc != null) {
            w.writeSection(8) { writeU32(startFunc!!.toLong()) }
        }

        // Element section
        if (elems.isNotEmpty()) {
            w.writeSection(9) {
                writeU32(elems.size.toLong())
                for (e in elems) {
                    val isPassive = e.mode is ElemModeSpec.Passive
                    val isDecl = e.mode is ElemModeSpec.Declarative
                    val active = e.mode as? ElemModeSpec.Active
                    val tableIdx = if (active != null) e.tableIdx else 0
                    // flags: choose simplest form. Active into table 0, func items: flag 0.
                    // Active into table N: flag 2. Passive: 1. Declarative: 3.
                    val flag = when {
                        active != null && tableIdx == 0 -> 0
                        active != null -> 2
                        isPassive -> 1
                        isDecl -> 3
                        else -> 0
                    }
                    writeU32(flag.toLong())
                    if (active != null && tableIdx != 0) writeU32(tableIdx.toLong())
                    if (active != null) {
                        writeConstExpr(active.offset); writeByte(0x0B)
                    }
                    if (flag == 2) {
                        writeRefType(e.elemType)
                    }
                    if (flag == 1 || flag == 3) writeRefType(e.elemType)
                    // items: each is a func index reference or a const expression.
                    writeU32(e.items.size.toLong())
                    for (item in e.items) {
                        // For flag 0/1/3 func-form, write the func index directly (not an expr).
                        if (flag == 0 || flag == 1 || (flag == 2 && false)) {
                            val idx = if (item.size == 1 && item[0] is Sym) resolveFuncIdx(item[0])
                                else if (item.size == 2 && item[0] is Sym && (item[0] as Sym).text == "ref.func") resolveFuncIdx(item[1])
                                else throw WatException("elem item must be a func ref")
                            writeU32(idx.toLong())
                        } else {
                            writeConstExpr(item); writeByte(0x0B)
                        }
                    }
                }
            }
        }

        // DataCount section (needed if memory.copy/init used); emit if we have data.
        if (dataSegments.isNotEmpty()) {
            w.writeSection(12) { writeU32(dataSegments.size.toLong()) }
        }

        // Code section
        if (funcs.isNotEmpty()) {
            val composer = this
            w.writeSection(10) {
                writeU32(funcs.size.toLong())
                for (f in funcs) {
                    val funcBody = BinaryWriter()
                    // locals grouping
                    val localInstr = funcBody as BinaryWriter
                    val groups = groupLocals(f.locals)
                    localInstr.writeU32(groups.size.toLong())
                    for ((count, type) in groups) {
                        localInstr.writeU32(count.toLong())
                        localInstr.writeValTypeT(type)
                    }
                    val instrWriter = InstrWriter(funcBody, composer)
                    instrWriter.setLocals(f.parameters + f.locals)
                    instrWriter.writeSeq(f.body)
                    funcBody.writeByte(0x0B) // end
                    writeU32(funcBody.size().toLong())
                    writeBytes(funcBody.toByteArray())
                }
            }
        }

        // Data section
        if (dataSegments.isNotEmpty()) {
            w.writeSection(11) {
                writeU32(dataSegments.size.toLong())
                for (d in dataSegments) {
                    val active = d.mode as? DataModeSpec.Active
                    val flag = when { active != null && d.memIdx == 0 -> 0; active != null -> 2; else -> 1 }
                    writeU32(flag.toLong())
                    if (flag == 2) writeU32(d.memIdx.toLong())
                    if (active != null) { writeConstExpr(active.offset); writeByte(0x0B) }
                    writeU32(d.init.size.toLong())
                    writeBytes(d.init)
                }
            }
        }

        return w.toByteArray()
    }

    private fun groupLocals(locals: List<Pair<String?, String>>): List<Pair<Int, String>> {
        val groups = ArrayList<Pair<Int, String>>()
        for ((_, type) in locals) {
            if (groups.isNotEmpty() && groups.last().second == type) {
                groups[groups.lastIndex] = groups.last().first + 1 to type
            } else {
                groups.add(1 to type)
            }
        }
        return groups
    }

    // ---- type encoders ----

    internal fun BinaryWriter.writeValType(type: String) {
        val b = when (type) {
            "i32" -> 0x7F
            "i64" -> 0x7E
            "f32" -> 0x7D
            "f64" -> 0x7C
            "v128" -> 0x7B
            "funcref", "anyfunc" -> 0x70
            "externref" -> 0x6F
            else -> throw WatException("unknown val type $type")
        }
        writeByte(b)
    }

    internal fun BinaryWriter.writeRefType(type: String) {
        val b = when (type) {
            "funcref", "anyfunc" -> 0x70
            "externref" -> 0x6F
            else -> throw WatException("unknown ref type $type")
        }
        writeByte(b)
    }

    private fun BinaryWriter.writeValTypeT(type: String) = writeValType(type)
    private fun BinaryWriter.writeRefTypeT(type: String) = writeRefType(type)

    internal fun BinaryWriter.writeTableType(et: String, min: Long, max: Long?) {
        writeRefTypeT(et)
        writeLimits(min, max)
    }

    internal fun BinaryWriter.writeMemoryType(min: Long, max: Long?, is64: Boolean, shared: Boolean) {
        var flags = 0
        if (max != null) flags = flags or 1
        if (shared) flags = flags or 2
        if (is64) flags = flags or 4
        writeByte(flags)
        if (is64) writeU32(min) else writeU32(min)
        if (max != null) { if (is64) writeU32(max) else writeU32(max) }
    }

    internal fun BinaryWriter.writeGlobalType(type: String, mut: Boolean) {
        writeValTypeT(type)
        writeByte(if (mut) 1 else 0)
    }

    private fun BinaryWriter.writeLimits(min: Long, max: Long?) {
        val flags = if (max != null) 1 else 0
        writeByte(flags)
        writeU32(min)
        if (max != null) writeU32(max)
    }

    /** Write a constant (init) expression's operands (without the terminating 0x0B). */
    internal fun BinaryWriter.writeConstExpr(expr: List<Sexpr>) {
        val iw = InstrWriter(this, this@WatComposer)
        iw.writeSeq(expr)
    }

    // expose name maps for InstrWriter
    internal fun typeIdxName(n: String) = typeNames[n]
    internal fun funcIdxName(n: String) = funcNames[n]
    internal fun globalIdxName(n: String) = globalNames[n]
    internal fun tableIdxName(n: String) = tableNames[n]
    internal fun memoryIdxName(n: String) = memoryNames[n]
    internal fun dataIdxName(n: String) = dataNames[n]
    internal fun elemIdxName(n: String) = elemNames[n]

    internal fun funcImportCount(): Int = imports.count { it.kind == "func" }

    /** Add a synthetic function type from inline params/results, deduplicating. */
    internal fun addInlineType(params: List<String>, results: List<String>): Int {
        val sig = FuncTypeSig(params.map { null to it }, results)
        val existing = types.indexOfFirst { it == sig }
        return if (existing >= 0) existing else { types.add(sig); types.size - 1 }
    }

    /** Parse a non-negative integer literal (decimal or hex with 0x). */
    internal fun parseNat(s: String): Long {
        val t = s.trim()
        return if (t.startsWith("0x") || t.startsWith("-0x")) t.toLong(16) else t.toLong()
    }

    public companion object {
        public fun compose(src: String): ByteArray {
            val tokens = WatLexer(src).tokens
            val sexprs = SexprReader(tokens).readAll()
            // A module is the concatenation of all top-level forms wrapped as one list.
            val items = ArrayList<Sexpr>()
            for (s in sexprs) {
                if (s is Node && s.items.firstOrNull() is Sym && (s.items[0] as Sym).text == "module") {
                    items.addAll(s.items.drop(1))
                } else {
                    items.add(s)
                }
            }
            return WatComposer(Node(0, items)).compose()
        }
    }
}
