package io.heapy.kwasm

import io.heapy.kwasm.Instr.*

/**
 * Decoder for the WebAssembly binary format.
 *
 * Supports MVP plus: sign-extension ops, non-trapping float-to-int,
 * bulk-memory/data/element rework, reference-types, multi-value,
 * extended-const expressions, GC/typed references, exception handling, and
 * syntax-complete parsing of deferred SIMD/threads instructions.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ModuleDecoder(
    private val reader: ByteReader,
    private val validationLimits: ModuleValidationLimits = ModuleValidationLimits(),
) {

    public fun decode(): Module {
        if (reader.data.size.toLong() > validationLimits.maxModuleSizeBytes) {
            throw LimitExceeded(
                limit = "module size",
                actual = reader.data.size.toLong(),
                maximum = validationLimits.maxModuleSizeBytes,
                location = "binary input",
            )
        }
        val magic = reader.readBytes(4)
        if (!magic.contentEquals(byteArrayOf(0x00, 0x61, 0x73, 0x6D))) {
            throw WasmDecodeException("invalid magic header", reader.baseOffset)
        }
        val version = reader.readBytes(4)
        if (!version.contentEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00))) {
            throw WasmDecodeException("unsupported version ${version.joinToString()}", reader.baseOffset + 4)
        }

        val module = ModuleBuilder()
        var lastSectionRank = 0
        var sawCodeSection = false

        while (!reader.isEof()) {
            val sectionOffset = reader.absolutePosition
            val sectionId = reader.readByte().toInt() and 0xFF
            val size = reader.readSize("section $sectionId size", reader.remaining)
            val rank = sectionRank(sectionId)
            if (sectionId != 0 && rank == null) {
                throw WasmDecodeException("unknown section id $sectionId", sectionOffset)
            }
            if (rank != null && rank <= lastSectionRank) {
                throw WasmDecodeException("duplicate or out-of-order section $sectionId", sectionOffset)
            }
            if (rank != null) lastSectionRank = rank

            val sectionReader = reader.readReader(size)
            when (sectionId) {
                0 -> decodeCustomSection(module, sectionReader)
                1 -> decodeTypeSection(module, sectionReader)
                2 -> decodeImportSection(module, sectionReader)
                3 -> decodeFunctionSection(module, sectionReader)
                4 -> decodeTableSection(module, sectionReader)
                5 -> decodeMemorySection(module, sectionReader)
                6 -> decodeGlobalSection(module, sectionReader)
                7 -> decodeExportSection(module, sectionReader)
                8 -> decodeStartSection(module, sectionReader)
                9 -> decodeElementSection(module, sectionReader)
                10 -> {
                    sawCodeSection = true
                    decodeCodeSection(module, sectionReader)
                }
                11 -> decodeDataSection(module, sectionReader)
                12 -> module.dataCount = readIndex(sectionReader, "data count")
                13 -> decodeTagSection(module, sectionReader)
            }
            if (sectionReader.pos != sectionReader.data.size) {
                sectionReader.fail("trailing bytes in section $sectionId")
            }
        }

        if (module.functions.isNotEmpty() && !sawCodeSection) {
            throw WasmDecodeException(
                "function and code section have inconsistent lengths",
                reader.absolutePosition,
            )
        }

        val declaredDataCount = module.dataCount
        if (declaredDataCount != null && declaredDataCount != module.dataSegments.size) {
            throw WasmDecodeException(
                "data count $declaredDataCount does not match " +
                    "${module.dataSegments.size} data segments",
                reader.absolutePosition,
            )
        }
        if (declaredDataCount == null) {
            val dataIndexUses = module.functions.map { inspectDataIndices(it.body, module.dataSegments.size) }
            val hasDataIndex = dataIndexUses.any { it != DataIndexUse.NONE }
            val hasInvalidDataIndex = dataIndexUses.any { it == DataIndexUse.INVALID }
            // A missing count for otherwise valid data indices is malformed
            // binary grammar. If an index is already invalid, defer rejection
            // so text-format assert_invalid modules retain validation identity.
            if (hasDataIndex && !hasInvalidDataIndex) {
                throw WasmDecodeException(
                    "data count section is required by data-index instructions",
                    reader.absolutePosition,
                )
            }
        }

        return module.build(reader.data).also {
            ModuleValidator.validate(it, validationLimits)
        }
    }

    private fun sectionRank(sectionId: Int): Int? = when (sectionId) {
        0 -> null
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        5 -> 5
        13 -> 6
        6 -> 7
        7 -> 8
        8 -> 9
        9 -> 10
        12 -> 11
        10 -> 12
        11 -> 13
        else -> null
    }

    private fun decodeCustomSection(module: ModuleBuilder, r: ByteReader) {
        val name = r.readName()
        val payload = r.readBytes(r.remaining)
        module.customSections.add(CustomSection(name, payload))
    }

    private fun decodeTypeSection(module: ModuleBuilder, r: ByteReader) {
        val recTypeCount = r.readSize("recursive type count", r.remaining)
        checkDeclaredLimit(
            "recursion groups",
            recTypeCount.toLong(),
            validationLimits.maxRecursionGroups,
            "type section",
        )
        repeat(recTypeCount) {
            val start = module.types.size
            val groupSize = if ((r.peekByte().toInt() and 0xFF) == 0x4E) {
                r.readByte()
                r.readSize("recursive group size", r.remaining)
            } else {
                1
            }
            if (groupSize == 0) r.fail("recursive type group must not be empty")
            checkDeclaredLimit(
                "types",
                module.types.size.toLong() + groupSize.toLong(),
                validationLimits.maxTypes,
                "type section",
            )
            repeat(groupSize) { module.types.add(readSubType(r)) }
            module.recursionGroups.add(RecursionGroup(start, groupSize))
        }
    }

    private fun readSubType(r: ByteReader): DefinedType {
        val marker = r.peekByte().toInt() and 0xFF
        val isFinal: Boolean
        val supertypes: List<Int>
        if (marker == 0x50 || marker == 0x4F) {
            r.readByte()
            isFinal = marker == 0x4F
            val count = r.readSize("supertype count", r.remaining)
            supertypes = List(count) { readIndex(r, "supertype") }
        } else {
            isFinal = true
            supertypes = emptyList()
        }

        return when (val form = r.readByte().toInt() and 0xFF) {
            0x60 -> readFuncType(r, isFinal, supertypes)
            0x5F -> {
                val count = r.readSize("struct field count", r.remaining)
                StructType(List(count) { readFieldType(r) }, isFinal, supertypes)
            }
            0x5E -> ArrayType(readFieldType(r), isFinal, supertypes)
            else -> r.fail("malformed composite type 0x${form.toString(16)}")
        }
    }

    private fun readFuncType(
        r: ByteReader,
        isFinal: Boolean = true,
        supertypes: List<Int> = emptyList(),
    ): FuncType {
        val pCount = r.readSize("parameter count", r.remaining)
        val params = ArrayList<ValType>(pCount)
        repeat(pCount) { params.add(readValType(r)) }
        val rCount = r.readSize("result count", r.remaining)
        val results = ArrayList<ValType>(rCount)
        repeat(rCount) { results.add(readValType(r)) }
        return FuncType(params, results, isFinal, supertypes)
    }

    private fun readFieldType(r: ByteReader): FieldType {
        val storage = when (r.peekByte().toInt() and 0xFF) {
            0x78 -> {
                r.readByte()
                StorageType.I8
            }
            0x77 -> {
                r.readByte()
                StorageType.I16
            }
            else -> StorageType.Value(readValType(r))
        }
        val mutabilityOffset = r.absolutePosition
        val mutability = when (val value = r.readByte().toInt() and 0xFF) {
            0 -> Mutability.CONST
            1 -> Mutability.VAR
            else -> throw WasmDecodeException("malformed mutability $value", mutabilityOffset)
        }
        return FieldType(storage, mutability)
    }

    private fun readValType(r: ByteReader): ValType = readValTypeRaw(r)

    private fun readValTypeRaw(r: ByteReader): ValType = when (val b = r.readByte().toInt() and 0xFF) {
        0x7F -> ValType.I32
        0x7E -> ValType.I64
        0x7D -> ValType.F32
        0x7C -> ValType.F64
        0x7B -> ValType.V128
        0x70 -> FuncRefType
        0x6F -> ExternRefType
        0x64 -> RefType(readHeapType(r), nullable = false)
        0x63 -> RefType(readHeapType(r), nullable = true)
        0x6E -> AnyRefType
        0x6D -> EqRefType
        0x6C -> I31RefType
        0x6B -> StructRefType
        0x6A -> ArrayRefType
        0x69 -> ExnRefType
        0x74 -> RefType(HeapType.NoExn, nullable = true)
        0x73 -> RefType(HeapType.NoFunc, nullable = true)
        0x72 -> RefType(HeapType.NoExtern, nullable = true)
        0x71 -> RefType(HeapType.None, nullable = true)
        else -> r.fail("invalid value type 0x${b.toString(16)}")
    }

    private fun readHeapType(r: ByteReader): HeapType {
        val start = r.absolutePosition
        val signed = readS33(r)
        return if (signed >= 0) {
            if (signed > Int.MAX_VALUE) {
                throw LimitExceeded(
                    limit = "runtime index",
                    actual = signed,
                    maximum = Int.MAX_VALUE.toLong(),
                    location = "heap type at binary byte $start",
                )
            }
            HeapType.Index(signed.toInt())
        } else {
            when (signed) {
                -0x0CL -> HeapType.NoExn
                -0x0DL -> HeapType.NoFunc
                -0x0EL -> HeapType.NoExtern
                -0x0FL -> HeapType.None
                -0x10L -> HeapType.Func
                -0x11L -> HeapType.Extern
                -0x12L -> HeapType.Any
                -0x13L -> HeapType.Eq
                -0x14L -> HeapType.I31
                -0x15L -> HeapType.Struct
                -0x16L -> HeapType.Array
                -0x17L -> HeapType.Exn
                else -> throw WasmDecodeException("malformed heap type $signed", start)
            }
        }
    }

    private fun readS33(r: ByteReader): Long {
        return r.readS33()
    }

    private fun readLimits(r: ByteReader): Limits {
        val flagsOffset = r.absolutePosition
        val flags = r.readByte().toInt() and 0xFF
        if ((flags and 0xF8) != 0) {
            throw WasmDecodeException("malformed limits flags 0x${flags.toString(16)}", flagsOffset)
        }
        val hasMax = (flags and 0x01) != 0
        val shared = (flags and 0x02) != 0
        if (shared && !hasMax) {
            throw WasmDecodeException("shared limits require a maximum", flagsOffset)
        }
        val hasIndexType = (flags and 0x04) != 0
        val indexType = if (hasIndexType) IndexType.I64 else IndexType.I32
        // Wasm 3.0 uses u64 for both address widths. Values outside the i32
        // semantic range are syntactically well-formed and rejected later by
        // validation, rather than being misclassified as malformed LEB128.
        val min: ULong = r.readU64()
        val max: ULong? = if (hasMax) r.readU64() else null
        return Limits(min, max, shared, indexType)
    }

    private fun readRefType(r: ByteReader): RefType = when (val b = r.readByte().toInt() and 0xFF) {
        0x70 -> FuncRefType
        0x6F -> ExternRefType
        0x6E -> AnyRefType
        0x6D -> EqRefType
        0x6C -> I31RefType
        0x6B -> StructRefType
        0x6A -> ArrayRefType
        0x69 -> ExnRefType
        0x74 -> RefType(HeapType.NoExn, nullable = true)
        0x73 -> RefType(HeapType.NoFunc, nullable = true)
        0x72 -> RefType(HeapType.NoExtern, nullable = true)
        0x71 -> RefType(HeapType.None, nullable = true)
        0x64 -> RefType(readHeapType(r), nullable = false)
        0x63 -> RefType(readHeapType(r), nullable = true)
        else -> r.fail("invalid ref type 0x${b.toString(16)}")
    }

    private fun readIndex(r: ByteReader, description: String): Int {
        val offset = r.absolutePosition
        val value = r.readU32()
        if (value > Int.MAX_VALUE.toUInt()) {
            throw LimitExceeded(
                limit = "runtime index",
                actual = value.toLong(),
                maximum = Int.MAX_VALUE.toLong(),
                location = "$description at binary byte $offset",
            )
        }
        return value.toInt()
    }

    private fun readU32Int(r: ByteReader, description: String): Int {
        val offset = r.absolutePosition
        val value = r.readU32()
        if (value > Int.MAX_VALUE.toUInt()) {
            throw WasmDecodeException("$description $value exceeds the runtime integer range", offset)
        }
        return value.toInt()
    }

    private fun readGlobalType(r: ByteReader): GlobalType {
        val type = readValType(r)
        val mutabilityOffset = r.absolutePosition
        val mut = r.readByte().toInt() and 0xFF
        val decoded = when (mut) {
            0 -> Mutability.CONST
            1 -> Mutability.VAR
            else -> throw WasmDecodeException("malformed mutability $mut", mutabilityOffset)
        }
        return GlobalType(type, decoded)
    }

    private fun decodeImportSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("import count", r.remaining)
        var importedFunctions = 0L
        var importedGlobals = 0L
        repeat(count) {
            val mod = r.readName()
            val field = r.readName()
            val kind = r.readByte().toInt() and 0xFF
            val desc = when (kind) {
                0x00 -> ImportDesc.Function(readIndex(r, "function type"))
                0x01 -> ImportDesc.Table(TableType(readRefType(r), readLimits(r)))
                0x02 -> ImportDesc.Memory(MemoryType(readLimits(r)))
                0x03 -> ImportDesc.Global(readGlobalType(r))
                0x04 -> {
                    val attributeOffset = r.absolutePosition
                    val attribute = r.readByte().toInt() and 0xFF
                    if (attribute != 0) {
                        throw WasmDecodeException(
                            "malformed tag attribute $attribute; expected zero",
                            attributeOffset,
                        )
                    }
                    ImportDesc.Tag(readIndex(r, "tag type"), attribute)
                }
                else -> r.fail("invalid import kind $kind")
            }
            module.imports.add(Import(mod, field, desc))
            when (desc) {
                is ImportDesc.Function -> {
                    importedFunctions++
                    checkDeclaredLimit(
                        "functions",
                        importedFunctions,
                        validationLimits.maxFunctions,
                        "import section",
                    )
                }
                is ImportDesc.Global -> {
                    importedGlobals++
                    checkDeclaredLimit(
                        "globals",
                        importedGlobals,
                        validationLimits.maxGlobals,
                        "import section",
                    )
                }
                else -> Unit
            }
        }
    }

    private fun decodeFunctionSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("function count", r.remaining)
        val importedFunctions = module.imports.count { it.desc is ImportDesc.Function }
        checkDeclaredLimit(
            "functions",
            importedFunctions.toLong() + count.toLong(),
            validationLimits.maxFunctions,
            "function section",
        )
        repeat(count) {
            module.functions.add(Function(readIndex(r, "function type"), emptyList(), emptyList()))
        }
    }

    private fun decodeTableSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("table count", r.remaining)
        repeat(count) {
            if ((r.peekByte().toInt() and 0xFF) == 0x40) {
                r.readByte()
                val reservedOffset = r.absolutePosition
                val reserved = r.readByte().toInt() and 0xFF
                if (reserved != 0) {
                    throw WasmDecodeException(
                        "table initializer reserved byte must be zero",
                        reservedOffset,
                    )
                }
                val type = TableType(readRefType(r), readLimits(r))
                module.tables.add(Table(type, readConstantExpression(r)))
            } else {
                val type = TableType(readRefType(r), readLimits(r))
                module.tables.add(Table(type, listOf(RefNull(type.element.heap))))
            }
        }
    }

    private fun decodeMemorySection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("memory count", r.remaining)
        repeat(count) { module.memories.add(Memory(MemoryType(readLimits(r)))) }
    }

    private fun decodeGlobalSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("global count", r.remaining)
        val importedGlobals = module.imports.count { it.desc is ImportDesc.Global }
        checkDeclaredLimit(
            "globals",
            importedGlobals.toLong() + count.toLong(),
            validationLimits.maxGlobals,
            "global section",
        )
        repeat(count) {
            val type = readGlobalType(r)
            val init = readConstantExpression(r)
            module.globals.add(Global(type, init))
        }
    }

    private fun decodeExportSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("export count", r.remaining)
        repeat(count) {
            val name = r.readName()
            val kind = r.readByte().toInt() and 0xFF
            val idx = readIndex(r, "export")
            val desc = when (kind) {
                0x00 -> ExportDesc.Function(idx)
                0x01 -> ExportDesc.Table(idx)
                0x02 -> ExportDesc.Memory(idx)
                0x03 -> ExportDesc.Global(idx)
                0x04 -> ExportDesc.Tag(idx)
                else -> r.fail("invalid export kind $kind")
            }
            module.exports.add(Export(name, desc))
        }
    }

    private fun decodeStartSection(module: ModuleBuilder, r: ByteReader) {
        module.startFunction = readIndex(r, "start function")
    }

    @Suppress("unused")
    private fun setStartUnused() = Unit

    private fun decodeElementSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("element segment count", r.remaining)
        repeat(count) {
            val flags = readU32Int(r, "element flags")
            if (flags !in 0..7) r.fail("invalid element flags $flags")

            val expressions = (flags and 0b100) != 0
            val modeBits = flags and 0b011
            val tableIndex: Int =
                if (modeBits == 2) readIndex(r, "element table") else if (modeBits == 0) 0 else -1
            val offset: List<Instr>? =
                if (modeBits == 0 || modeBits == 2) readConstantExpression(r) else null
            val activeType: RefType = when {
                expressions && flags in 5..7 -> readRefType(r)
                !expressions -> readElementKindIfPresent(r, flags)
                else -> FuncRefType
            }
            val exprs: MutableList<List<Instr>> = mutableListOf()
            val elementCount = r.readSize("element count", r.remaining)
            if (expressions) {
                repeat(elementCount) { exprs.add(readConstantExpression(r)) }
            } else {
                repeat(elementCount) { exprs.add(listOf(RefFunc(readIndex(r, "element function")))) }
            }

            val mode = when (modeBits) {
                0, 2 -> ElementMode.Active(tableIndex, offset!!)
                1 -> ElementMode.Passive(activeType)
                3 -> ElementMode.Declarative(activeType)
                else -> error("unreachable")
            }
            module.elementSegments.add(ElementSegment(tableIndex, offset, mode, exprs, activeType))
        }
    }

    private fun readElementKindIfPresent(r: ByteReader, flags: Int): RefType {
        if (flags in 1..3) {
            val kind = r.readByte().toInt() and 0xFF
            if (kind != 0x00) r.fail("invalid element kind 0x${kind.toString(16)}")
        }
        // Legacy function-index element segments contain ref.func values and
        // therefore carry the non-null function reference type.
        return RefType(HeapType.Func, nullable = false)
    }

    private fun decodeCodeSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("code body count", r.remaining)
        if (count != module.functions.size) {
            r.fail("code section count ($count) != function section count (${module.functions.size})")
        }
        repeat(count) { i ->
            val bodySize = r.readSize("function body size", r.remaining)
            checkDeclaredLimit(
                "function body size",
                bodySize.toLong(),
                validationLimits.maxFunctionBodySizeBytes,
                "function $i",
            )
            val bodyReader = r.readReader(bodySize)
            val localGroups = bodyReader.readSize("local group count", bodyReader.remaining)
            val locals = ArrayList<ValType>()
            var localCount = 0L
            val localCountOffset = bodyReader.absolutePosition
            val localLimit = minOf(validationLimits.maxLocalsPerFunction, Int.MAX_VALUE.toLong())
            var localLimitExceeded = false
            repeat(localGroups) {
                val n = bodyReader.readU32()
                localCount += n.toLong()
                val t = readValType(bodyReader)
                if (!localLimitExceeded && localCount <= localLimit) {
                    repeat(n.toInt()) { locals.add(t) }
                } else {
                    // Keep reading the compact local declarations so a sum
                    // beyond the binary grammar's u32 maximum is reported as
                    // malformed, rather than being hidden by a host ceiling.
                    localLimitExceeded = true
                }
            }
            if (localCount > UInt.MAX_VALUE.toLong()) {
                throw WasmDecodeException("too many locals", localCountOffset)
            }
            if (localLimitExceeded) {
                throw LimitExceeded(
                    limit = "locals per function",
                    actual = localCount,
                    maximum = localLimit,
                    location = "function $i",
                )
            }
            val body = readUntilEnd(bodyReader)
            if (!bodyReader.isEof()) bodyReader.fail("trailing bytes in function body")
            module.functions[i] = module.functions[i].copy(
                locals = locals,
                body = body,
                encodedBodySizeBytes = bodySize,
            )
        }
    }

    private fun decodeDataSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("data segment count", r.remaining)
        repeat(count) {
            val flags = readU32Int(r, "data flags")
            val mode = when (flags) {
                0 -> DataMode.Active(0, readConstantExpression(r))
                1 -> DataMode.Passive
                2 -> DataMode.Active(readIndex(r, "data memory"), readConstantExpression(r))
                else -> r.fail("invalid data flags $flags")
            }
            val len = r.readSize("data segment size", r.remaining)
            module.dataSegments.add(DataSegment(mode, r.readBytes(len)))
        }
    }

    private fun decodeTagSection(module: ModuleBuilder, r: ByteReader) {
        val count = r.readSize("tag count", r.remaining)
        repeat(count) {
            val attributeOffset = r.absolutePosition
            val attribute = r.readByte().toInt() and 0xFF
            if (attribute != 0) {
                throw WasmDecodeException(
                    "malformed tag attribute $attribute; expected zero",
                    attributeOffset,
                )
            }
            val typeIndex = readIndex(r, "tag type")
            module.tags.add(Tag(typeIndex, attribute))
        }
    }

    // ---- constant expressions ----

    /**
     * Constant expressions use the normal binary instruction grammar. The
     * much smaller admissible instruction set is a validation rule, so an
     * otherwise well-formed `nop` here is invalid, not malformed.
     */
    private fun readConstantExpression(r: ByteReader): List<Instr> = readUntilEnd(r)

    // ---- instruction sequences ----

    /** Returns null for structural markers consumed internally. */
    private fun readInstr(r: ByteReader): Instr? {
        val op = r.readByte().toInt() and 0xFF
        return when (op) {
            0x00 -> Unreachable
            0x01 -> Nop
            0x02 -> {
                r.fail("internal decoder error: block was not handled by the sequence parser")
            }
            0x03 -> {
                r.fail("internal decoder error: loop was not handled by the sequence parser")
            }
            0x04 -> {
                r.fail("internal decoder error: if was not handled by the sequence parser")
            }
            0x06 -> r.fail("internal decoder error: try was not handled by the sequence parser")
            0x07 -> r.fail("misplaced legacy catch opcode")
            0x08 -> Throw(readIndex(r, "tag"))
            0x09 -> Rethrow(readIndex(r, "label"))
            0x0A -> ThrowRef
            0x0B -> { End } // standalone end (only at top-level); ignore
            0x0C -> Br(readIndex(r, "label"))
            0x0D -> BrIf(readIndex(r, "label"))
            0x0E -> {
                val n = r.readSize("br_table target count", r.remaining)
                val targets = IntArray(n) { readIndex(r, "br_table label") }
                val def = readIndex(r, "br_table default label")
                BrTable(targets, def)
            }
            0x0F -> Return
            0x10 -> Call(readIndex(r, "function"))
            0x11 -> CallIndirect(readIndex(r, "function type"), readIndex(r, "table"))
            0x12 -> ReturnCall(readIndex(r, "function"))
            0x13 -> ReturnCallIndirect(readIndex(r, "function type"), readIndex(r, "table"))
            0x14 -> CallRef(readIndex(r, "function type"))
            0x15 -> ReturnCallRef(readIndex(r, "function type"))

            0x1A -> Drop()
            0x1B -> Select
            0x1C -> {
                val n = r.readSize("typed select result count", r.remaining)
                val types = ArrayList<ValType>(n)
                repeat(n) { types.add(readValType(r)) }
                SelectT(types)
            }
            0x1F -> {
                r.fail("internal decoder error: try_table was not handled by the sequence parser")
            }

            0x20 -> FcIndex(0x20, readIndex(r, "local")) // local.get
            0x21 -> FcIndex(0x21, readIndex(r, "local")) // local.set
            0x22 -> FcIndex(0x22, readIndex(r, "local")) // local.tee
            0x23 -> FcIndex(0x23, readIndex(r, "global")) // global.get
            0x24 -> FcIndex(0x24, readIndex(r, "global")) // global.set
            0x25 -> FcIndex(0x25, readIndex(r, "table")) // table.get
            0x26 -> FcIndex(0x26, readIndex(r, "table")) // table.set

            in 0x28..0x35 -> {
                val memarg = readMemArg(r)
                Load(op, memarg.align, memarg.offset, memarg.memoryIndex)
            }
            in 0x36..0x3E -> {
                val memarg = readMemArg(r)
                Store(op, memarg.align, memarg.offset, memarg.memoryIndex)
            }
            0x3F -> {
                val memoryIndex = readIndex(r, "memory")
                if (memoryIndex == 0) MemorySize else MemorySizeAt(memoryIndex)
            }
            0x40 -> {
                val memoryIndex = readIndex(r, "memory")
                if (memoryIndex == 0) MemoryGrow else MemoryGrowAt(memoryIndex)
            }

            0x41 -> I32Const(r.readS32())
            0x42 -> I64Const(r.readS64())
            0x43 -> F32Const(r.readF32())
            0x44 -> F64Const(r.readF64())

            in 0x45..0xC4 -> Simple(op)

            0xD0 -> RefNull(readHeapType(r))
            0xD1 -> RefIsNull
            0xD2 -> RefFunc(readIndex(r, "function"))
            0xD3 -> RefEq
            0xD4 -> RefAsNonNull
            0xD5 -> BrOnNull(readIndex(r, "label"))
            0xD6 -> BrOnNonNull(readIndex(r, "label"))

            0xFC -> readFcInstr(r)
            0xFB -> readGcInstr(r)
            0xFD -> readSimdInstr(r)
            0xFE -> readThreadsInstr(r)

            else -> r.fail("unknown opcode 0x${op.toString(16)}")
        }
    }

    private fun readCatchClause(r: ByteReader): CatchClause = when (
        val kind = r.readByte().toInt() and 0xFF
    ) {
        0 -> CatchClause.Tagged(
            tagIndex = readIndex(r, "tag"),
            depth = readIndex(r, "label"),
            withReference = false,
        )
        1 -> CatchClause.Tagged(
            tagIndex = readIndex(r, "tag"),
            depth = readIndex(r, "label"),
            withReference = true,
        )
        2 -> CatchClause.All(
            depth = readIndex(r, "label"),
            withReference = false,
        )
        3 -> CatchClause.All(
            depth = readIndex(r, "label"),
            withReference = true,
        )
        else -> r.fail("malformed catch clause kind $kind")
    }

    private fun readUntilEnd(r: ByteReader): List<Instr> {
        val root = RootInstructionFrame()
        val frames = ArrayDeque<InstructionFrame>()
        frames.addLast(root)

        fun activeBody(): MutableList<Instr> = frames.last().activeBody()

        fun push(frame: InstructionFrame) {
            val depth = frames.size.toLong()
            if (depth > validationLimits.maxControlNesting) {
                throw LimitExceeded(
                    limit = "control nesting",
                    actual = depth,
                    maximum = validationLimits.maxControlNesting,
                    location = "binary instruction sequence",
                )
            }
            frames.addLast(frame)
        }

        fun complete(frame: InstructionFrame): Instr = when (frame) {
            is BlockInstructionFrame -> when (frame.opcode) {
                0x02 -> Block(frame.blockType, frame.body)
                0x03 -> Loop(frame.blockType, frame.body)
                else -> error("unknown block frame opcode ${frame.opcode}")
            }
            is IfInstructionFrame ->
                If(frame.blockType, frame.thenBody, frame.elseBody)
            is TryTableInstructionFrame ->
                TryTable(frame.blockType, frame.catches, frame.body)
            is LegacyTryInstructionFrame ->
                LegacyTry(
                    frame.blockType,
                    frame.body,
                    frame.catches.map { LegacyCatch(it.tagIndex, it.body) },
                    frame.catchAll,
                    frame.delegateDepth,
                )
            is RootInstructionFrame ->
                error("the root instruction frame cannot become an instruction")
        }

        while (true) {
            when (val opcode = r.peekByte().toInt() and 0xFF) {
                0x02, 0x03 -> {
                    r.readByte()
                    push(BlockInstructionFrame(opcode, readBlockType(r)))
                }
                0x04 -> {
                    r.readByte()
                    push(IfInstructionFrame(readBlockType(r)))
                }
                0x06 -> {
                    r.readByte()
                    push(LegacyTryInstructionFrame(readBlockType(r)))
                }
                0x1F -> {
                    r.readByte()
                    val blockType = readBlockType(r)
                    val catchCount = r.readSize("catch clause count", r.remaining)
                    val catches = List(catchCount) { readCatchClause(r) }
                    push(TryTableInstructionFrame(blockType, catches))
                }
                0x05 -> {
                    r.readByte()
                    val frame = frames.lastOrNull() as? IfInstructionFrame
                        ?: r.fail("misplaced else opcode")
                    if (frame.inElse) r.fail("duplicate else opcode")
                    frame.inElse = true
                }
                0x07 -> {
                    r.readByte()
                    val frame = frames.lastOrNull() as? LegacyTryInstructionFrame
                        ?: r.fail("misplaced legacy catch opcode")
                    if (frame.catchAll != null || frame.delegateDepth != null) {
                        r.fail("legacy catch after terminal handler")
                    }
                    frame.currentCatch = LegacyCatchInstructionFrame(
                        readIndex(r, "tag"),
                    ).also(frame.catches::add)
                }
                0x19 -> {
                    r.readByte()
                    val frame = frames.lastOrNull() as? LegacyTryInstructionFrame
                        ?: r.fail("misplaced legacy catch_all opcode")
                    if (frame.catchAll != null || frame.delegateDepth != null) {
                        r.fail("duplicate legacy catch_all/delegate")
                    }
                    frame.currentCatch = null
                    frame.catchAll = mutableListOf()
                }
                0x18 -> {
                    r.readByte()
                    val frame = frames.lastOrNull() as? LegacyTryInstructionFrame
                        ?: r.fail("misplaced legacy delegate opcode")
                    if (
                        frame.catches.isNotEmpty() ||
                        frame.catchAll != null ||
                        frame.delegateDepth != null
                    ) {
                        r.fail("legacy delegate cannot follow catch clauses")
                    }
                    frame.delegateDepth = readIndex(r, "label")
                    frames.removeLast()
                    activeBody().add(complete(frame))
                }
                0x0B -> {
                    r.readByte()
                    if (frames.size == 1) return root.body
                    val frame = frames.removeLast()
                    activeBody().add(complete(frame))
                }
                else -> activeBody().add(
                    readInstr(r)
                        ?: r.fail("opcode 0x${opcode.toString(16)} produced no instruction"),
                )
            }
        }
    }

    private sealed interface InstructionFrame {
        fun activeBody(): MutableList<Instr>
    }

    private class RootInstructionFrame(
        val body: MutableList<Instr> = mutableListOf(),
    ) : InstructionFrame {
        override fun activeBody(): MutableList<Instr> = body
    }

    private class BlockInstructionFrame(
        val opcode: Int,
        val blockType: BlockType,
        val body: MutableList<Instr> = mutableListOf(),
    ) : InstructionFrame {
        override fun activeBody(): MutableList<Instr> = body
    }

    private class IfInstructionFrame(
        val blockType: BlockType,
        val thenBody: MutableList<Instr> = mutableListOf(),
        val elseBody: MutableList<Instr> = mutableListOf(),
        var inElse: Boolean = false,
    ) : InstructionFrame {
        override fun activeBody(): MutableList<Instr> =
            if (inElse) elseBody else thenBody
    }

    private class TryTableInstructionFrame(
        val blockType: BlockType,
        val catches: List<CatchClause>,
        val body: MutableList<Instr> = mutableListOf(),
    ) : InstructionFrame {
        override fun activeBody(): MutableList<Instr> = body
    }

    private class LegacyCatchInstructionFrame(
        val tagIndex: Int,
        val body: MutableList<Instr> = mutableListOf(),
    )

    private class LegacyTryInstructionFrame(
        val blockType: BlockType,
        val body: MutableList<Instr> = mutableListOf(),
        val catches: MutableList<LegacyCatchInstructionFrame> = mutableListOf(),
        var currentCatch: LegacyCatchInstructionFrame? = null,
        var catchAll: MutableList<Instr>? = null,
        var delegateDepth: Int? = null,
    ) : InstructionFrame {
        override fun activeBody(): MutableList<Instr> =
            catchAll ?: currentCatch?.body ?: body
    }

    private fun readBlockType(r: ByteReader): BlockType {
        val b = r.peekByte().toInt() and 0xFF
        return when {
            b == 0x40 -> { r.readByte(); BlockType.Empty }
            b in VALUE_TYPE_PREFIXES -> BlockType.Single(readValType(r))
            else -> {
                val offset = r.absolutePosition
                val idx = readS33(r)
                if (idx < 0 || idx > Int.MAX_VALUE) {
                    if (idx < 0) r.fail("malformed block type index $idx")
                    throw LimitExceeded(
                        limit = "runtime index",
                        actual = idx,
                        maximum = Int.MAX_VALUE.toLong(),
                        location = "block type at binary byte $offset",
                    )
                }
                BlockType.TypeIndex(idx.toInt())
            }
        }
    }

    private fun readGcInstr(r: ByteReader): Instr {
        val subOpcodeOffset = r.absolutePosition
        val sub = readU32Int(r, "GC opcode")
        fun index(): Int = readIndex(r, "GC")
        return when (sub) {
            0, 1, 6, 7, 11, 12, 13, 14, 16 -> Gc(sub, firstIndex = index())
            2, 3, 4, 5 -> Gc(sub, firstIndex = index(), secondIndex = index())
            8 -> Gc(sub, firstIndex = index(), count = index())
            9, 10, 17, 18, 19 -> Gc(sub, firstIndex = index(), secondIndex = index())
            15, 26, 27, 28, 29, 30 -> Gc(sub)
            20 -> Gc(sub, targetType = RefType(readHeapType(r), nullable = false))
            21 -> Gc(sub, targetType = RefType(readHeapType(r), nullable = true))
            22 -> Gc(sub, targetType = RefType(readHeapType(r), nullable = false))
            23 -> Gc(sub, targetType = RefType(readHeapType(r), nullable = true))
            24, 25 -> {
                val flagsOffset = r.absolutePosition
                val flags = r.readByte().toInt() and 0xFF
                if ((flags and 0xFC) != 0) {
                    throw WasmDecodeException(
                        "malformed br_on_cast flags 0x${flags.toString(16)}",
                        flagsOffset,
                    )
                }
                val depth = readIndex(r, "label")
                val source = RefType(readHeapType(r), nullable = (flags and 1) != 0)
                val target = RefType(readHeapType(r), nullable = (flags and 2) != 0)
                Gc(
                    subOpcode = sub,
                    flags = flags,
                    depth = depth,
                    sourceType = source,
                    targetType = target,
                )
            }
            else -> throw WasmDecodeException("invalid GC opcode $sub", subOpcodeOffset)
        }
    }

    private fun readFcInstr(r: ByteReader): Instr {
        val sub = readU32Int(r, "FC opcode")
        return when (sub) {
            in 0..7 -> FcIndex(sub shl 8 or 0xFC, 0)
            8 -> {
                val dataIndex = readIndex(r, "data")
                val memoryIndex = readIndex(r, "memory")
                MemoryInit(dataIndex, memoryIndex)
            }
            9 -> FcIndex(0x09_FC, readIndex(r, "data"))
            10 -> {
                val destination = readIndex(r, "destination memory")
                val source = readIndex(r, "source memory")
                MemoryCopy(destination, source)
            }
            11 -> MemoryFill(readIndex(r, "memory"), 0)
            12 -> {
                val elementIndex = readIndex(r, "element")
                val tableIndex = readIndex(r, "table")
                FcIndex(0x0C_FC, elementIndex, tableIndex)
            }
            13 -> FcIndex(0x0D_FC, readIndex(r, "element"))
            14 -> FcIndex(
                0x0E_FC,
                readIndex(r, "destination table"),
                readIndex(r, "source table"),
            )
            15 -> FcIndex(0x0F_FC, readIndex(r, "table"))
            16 -> FcIndex(0x10_FC, readIndex(r, "table"))
            17 -> FcIndex(0x11_FC, readIndex(r, "table"))
            else -> r.fail("invalid FC opcode $sub")
        }
    }

    private data class DecodedMemArg(
        val align: Int,
        val offset: ULong,
        val memoryIndex: Int,
    )

    private fun readMemArg(r: ByteReader): DecodedMemArg {
        val flagsOffset = r.absolutePosition
        val flags = r.readU32()
        if (flags >= 0x80u) {
            throw WasmDecodeException("malformed memory argument flags $flags", flagsOffset)
        }
        val memoryIndex =
            if ((flags and 0x40u) != 0u) readIndex(r, "memory") else 0
        return DecodedMemArg(
            align = (flags and 0x3Fu).toInt(),
            offset = r.readU64(),
            memoryIndex = memoryIndex,
        )
    }

    private fun readSimdInstr(r: ByteReader): Instr {
        val sub = readU32Int(r, "SIMD opcode")
        val plainRange = 0x0E..0x113
        return if (sub in 0x00..0x0B || sub in 0x5C..0x5D) {
            val memarg = readMemArg(r)
            RawImmediate(
                0xFD,
                longArrayOf(
                    sub.toLong(),
                    memarg.memoryIndex.toLong(),
                    memarg.align.toLong(),
                    memarg.offset.toLong(),
                ),
                ByteArray(0),
            )
        } else if (sub == 0x0C) {
            RawImmediate(0xFD, longArrayOf(sub.toLong()), r.readBytes(16))
        } else if (sub == 0x0D) {
            val lanes = ByteArray(16) { r.readByte() }
            RawImmediate(0xFD, longArrayOf(sub.toLong()), lanes)
        } else if (sub in 0x15..0x22) {
            RawImmediate(
                0xFD,
                longArrayOf(sub.toLong(), (r.readByte().toInt() and 0xFF).toLong()),
                ByteArray(0),
            )
        } else if (sub in 0x54..0x5B) {
            val memarg = readMemArg(r)
            val lane = r.readByte().toInt() and 0xFF
            RawImmediate(
                0xFD,
                longArrayOf(
                    sub.toLong(),
                    memarg.memoryIndex.toLong(),
                    memarg.align.toLong(),
                    memarg.offset.toLong(),
                    lane.toLong(),
                ),
                ByteArray(0),
            )
        } else if (sub in plainRange && sub !in SIMD_RESERVED_OPCODES) {
            RawImmediate(0xFD, longArrayOf(sub.toLong()), ByteArray(0))
        } else {
            r.fail("invalid SIMD opcode $sub")
        }
    }

    private fun readThreadsInstr(r: ByteReader): Instr {
        val op = readU32Int(r, "atomic opcode")
        return when (op) {
            0x03 -> {
                val reserved = r.readByte().toInt() and 0xFF
                if (reserved != 0) r.fail("atomic.fence reserved byte must be zero")
                RawImmediate(0xFE, longArrayOf(op.toLong(), 0), ByteArray(0))
            }
            in 0x00..0x02, in 0x10..0x4E -> {
                val memarg = readMemArg(r)
                RawImmediate(
                    0xFE,
                    longArrayOf(
                        op.toLong(),
                        memarg.memoryIndex.toLong(),
                        memarg.align.toLong(),
                        memarg.offset.toLong(),
                    ),
                    ByteArray(0),
                )
            }
            else -> r.fail("invalid atomic opcode $op")
        }
    }

    private fun checkDeclaredLimit(
        name: String,
        actual: Long,
        maximum: Long,
        location: String,
    ) {
        if (actual > maximum) {
            throw LimitExceeded(name, actual, maximum, location)
        }
    }

    private fun inspectDataIndices(root: List<Instr>, dataSegmentCount: Int): DataIndexUse {
        var found = false
        val pending = ArrayDeque<List<Instr>>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val body = pending.removeLast()
            body.forEach { instruction ->
                val dataIndex = when (instruction) {
                    is MemoryInit -> instruction.dataSegment
                    DataDrop -> 0
                    is FcIndex -> if (instruction.opcode == 0x09_FC) instruction.index else null
                    is Gc -> if (instruction.subOpcode == 9 || instruction.subOpcode == 18) {
                        instruction.secondIndex
                    } else {
                        null
                    }
                    else -> null
                }
                if (dataIndex != null) {
                    found = true
                    if (dataIndex < 0 || dataIndex >= dataSegmentCount) return DataIndexUse.INVALID
                }
                when (instruction) {
                    is Block -> pending.addLast(instruction.body)
                    is Loop -> pending.addLast(instruction.body)
                    is If -> {
                        pending.addLast(instruction.thenBody)
                        pending.addLast(instruction.elseBody)
                    }
                    is TryTable -> pending.addLast(instruction.body)
                    is LegacyTry -> {
                        pending.addLast(instruction.body)
                        instruction.catches.forEach { pending.addLast(it.body) }
                        instruction.catchAll?.let(pending::addLast)
                    }
                    else -> Unit
                }
            }
        }
        return if (found) DataIndexUse.VALID else DataIndexUse.NONE
    }

    private enum class DataIndexUse {
        NONE,
        VALID,
        INVALID,
    }

    public companion object {
        private val VALUE_TYPE_PREFIXES: Set<Int> = setOf(
            0x7F,
            0x7E,
            0x7D,
            0x7C,
            0x7B,
            0x74,
            0x73,
            0x72,
            0x71,
            0x70,
            0x6F,
            0x6E,
            0x6D,
            0x6C,
            0x6B,
            0x6A,
            0x69,
            0x64,
            0x63,
        )

        private val SIMD_RESERVED_OPCODES: Set<Int> = setOf(
            0x9A,
            0xA2,
            0xA5,
            0xA6,
            0xAF,
            0xB0,
            0xB2,
            0xB3,
            0xB4,
            0xBB,
            0xC2,
            0xC5,
            0xC6,
            0xCF,
            0xD0,
            0xD2,
            0xD3,
            0xD4,
            0xE2,
            0xEE,
        )

        public fun decode(
            bytes: ByteArray,
            validationLimits: ModuleValidationLimits = ModuleValidationLimits(),
        ): Module = ModuleDecoder(ByteReader(bytes), validationLimits).decode()
    }
}
