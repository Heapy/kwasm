package io.heapy.kwasm

import io.heapy.kwasm.Instr.*
import io.heapy.kwasm.Instr.Store as StoreInstruction

/**
 * Resource ceilings applied by [ModuleValidator].
 *
 * Decoded modules retain their original module and function-body byte sizes,
 * so every ceiling is enforced against the exact decoded value.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class ModuleValidationLimits(
    public val maxModuleSizeBytes: Long = 256L * 1024L * 1024L,
    public val maxFunctions: Long = 1_000_000L,
    public val maxFunctionBodySizeBytes: Long = 7_654_321L,
    public val maxLocalsPerFunction: Long = 50_000L,
    public val maxMemoryPages: Long = 65_536L,
    public val maxTableElements: Long = 10_000_000L,
    public val maxGlobals: Long = 1_000_000L,
    public val maxTypes: Long = 1_000_000L,
    public val maxRecursionGroups: Long = 1_000_000L,
    /**
     * Maximum statically nested structured control instructions.
     *
     * This is deliberately separate from the runtime call-frame limit: it
     * bounds decoder/validator metadata for hostile binaries before execution.
     */
    public val maxControlNesting: Long = 512L,
    /**
     * Permit `v128` in type declarations while SIMD instructions remain
     * unsupported.
     *
     * Current Kotlin/Wasm/WASI compiler output retains inert `v128` fields in
     * its linked runtime even when guest code executes no SIMD instruction.
     * Keep this false for the strict non-SIMD validation profile.
     */
    public val allowInertV128Types: Boolean = false,
)

/** Concise compatibility name for callers configuring module validation. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias ValidationLimits = ModuleValidationLimits

/** Concise compatibility name for callers configuring module validation. */
@io.heapy.kwasm.ExperimentalKwasmApi
public typealias ValidatorLimits = ModuleValidationLimits

/** Pure validation entry point for the retained WebAssembly core model. */
@io.heapy.kwasm.ExperimentalKwasmApi
public object ModuleValidator {
    /**
     * Validate [module], throwing a [ValidationException] on the first error.
     * The module is never mutated.
     */
    public fun validate(
        module: Module,
        limits: ModuleValidationLimits = ModuleValidationLimits(),
    ) {
        Validator(module, limits).validate()
    }
}

private data class ValidatorControlFrame(
    val labelTypes: List<ValType>,
    val endTypes: List<ValType>,
    val height: Int,
    val isLoop: Boolean,
    val enteredFromReachablePath: Boolean,
    val entryInitializedLocals: Set<Int>,
    val isLegacyCatch: Boolean = false,
    var unreachable: Boolean = false,
    var pathUnreachable: Boolean = false,
    var branchInitializedLocals: Set<Int>? = null,
)

private data class ValidatorBlockSignature(
    val params: List<ValType>,
    val results: List<ValType>,
)

private class Validator(
    private val module: Module,
    private val limits: ModuleValidationLimits,
) {
    private val functionTypeIndices: List<Int> = buildList {
        module.imports.forEach { import ->
            val desc = import.desc
            if (desc is ImportDesc.Function) add(desc.typeIndex)
        }
        module.functions.forEach { add(it.typeIndex) }
    }

    private val tableTypes: List<TableType> = buildList {
        module.imports.forEach { import ->
            val desc = import.desc
            if (desc is ImportDesc.Table) add(desc.type)
        }
        module.tables.forEach { add(it.type) }
    }

    private val memoryTypes: List<MemoryType> = buildList {
        module.imports.forEach { import ->
            val desc = import.desc
            if (desc is ImportDesc.Memory) add(desc.type)
        }
        module.memories.forEach { add(it.type) }
    }

    private val globalTypes: List<GlobalType> = buildList {
        module.imports.forEach { import ->
            val desc = import.desc
            if (desc is ImportDesc.Global) add(desc.type)
        }
        module.globals.forEach { add(it.type) }
    }

    /** Function references declared by module initializers, elements, or exports. */
    private val declaredFunctionRefs: Set<Int> = buildSet {
        module.exports.forEach { export ->
            val desc = export.desc
            if (desc is ExportDesc.Function) add(desc.index)
        }
        module.globals.forEach { global -> collectRefFuncs(global.init, this) }
        module.tables.forEach { table -> collectRefFuncs(table.init, this) }
        module.elementSegments.forEach { segment ->
            val mode = segment.mode
            if (mode is ElementMode.Active) collectRefFuncs(mode.offset, this)
            segment.exprs.forEach { collectRefFuncs(it, this) }
        }
    }

    private val tagTypeIndices: List<Int> = buildList {
        module.imports.forEach { import ->
            val desc = import.desc
            if (desc is ImportDesc.Tag) add(desc.typeIndex)
        }
        module.tags.forEach { add(it.typeIndex) }
    }

    fun validate() {
        validateLimitConfiguration()
        checkLimit("module size", module.encodedSizeBytes.toLong(), limits.maxModuleSizeBytes)
        checkLimit("functions", functionTypeIndices.size.toLong(), limits.maxFunctions)
        checkLimit("globals", globalTypes.size.toLong(), limits.maxGlobals)
        checkLimit("types", module.types.size.toLong(), limits.maxTypes)
        checkLimit(
            "recursion groups",
            module.recursionGroups.size.toLong(),
            limits.maxRecursionGroups,
        )

        validateTypes()
        validateImportsAndDefinitions()
        validateExports()
        validateStart()
        validateGlobals()
        validateElements()
        validateDataSegments()
        validateFunctions()
    }

    private fun validateLimitConfiguration() {
        val configured = listOf(
            "maxModuleSizeBytes" to limits.maxModuleSizeBytes,
            "maxFunctions" to limits.maxFunctions,
            "maxFunctionBodySizeBytes" to limits.maxFunctionBodySizeBytes,
            "maxLocalsPerFunction" to limits.maxLocalsPerFunction,
            "maxMemoryPages" to limits.maxMemoryPages,
            "maxTableElements" to limits.maxTableElements,
            "maxGlobals" to limits.maxGlobals,
            "maxTypes" to limits.maxTypes,
            "maxRecursionGroups" to limits.maxRecursionGroups,
            "maxControlNesting" to limits.maxControlNesting,
        )
        configured.forEach { (name, value) ->
            require(value >= 0L) { "$name must be non-negative" }
        }
    }

    private fun validateTypes() {
        module.types.forEachIndexed { index, type ->
            if (type.supertypes.size > 1) invalid("type $index declares more than one supertype")
            type.supertypes.forEach { supertypeIndex ->
                requireIndex(supertypeIndex, module.types.size, "type $index supertype")
                // A declared supertype must be defined before this subtype.
                // Earlier members of the same recursion group are therefore
                // valid; only forward/self edges would make the nominal
                // subtype hierarchy cyclic.
                if (supertypeIndex >= index) {
                    invalid("type $index supertype $supertypeIndex must precede the subtype")
                }
                val supertype = module.types[supertypeIndex]
                if (supertype.isFinal) invalid("type $index extends final type $supertypeIndex")
                validateDeclaredSubtype(index, type, supertypeIndex, supertype)
            }
            when (type) {
                is FuncType -> {
                    type.params.forEach { validateValueType(it, "type $index parameter") }
                    type.results.forEach { validateValueType(it, "type $index result") }
                }
                is StructType -> type.fields.forEachIndexed { fieldIndex, field ->
                    validateFieldType(field, "type $index field $fieldIndex")
                }
                is ArrayType -> validateFieldType(type.field, "type $index array field")
            }
        }
    }

    private fun validateDeclaredSubtype(
        typeIndex: Int,
        subtype: DefinedType,
        supertypeIndex: Int,
        supertype: DefinedType,
    ) {
        val valid = when {
            subtype is FuncType && supertype is FuncType ->
                subtype.params.size == supertype.params.size &&
                    subtype.results.size == supertype.results.size &&
                    subtype.params.indices.all {
                        isSubtype(supertype.params[it], subtype.params[it])
                    } &&
                    subtype.results.indices.all {
                        isSubtype(subtype.results[it], supertype.results[it])
                    }
            subtype is StructType && supertype is StructType ->
                subtype.fields.size >= supertype.fields.size &&
                    supertype.fields.indices.all {
                        isFieldSubtype(subtype.fields[it], supertype.fields[it])
                    }
            subtype is ArrayType && supertype is ArrayType ->
                isFieldSubtype(subtype.field, supertype.field)
            else -> false
        }
        if (!valid) {
            invalid("type $typeIndex is not a valid subtype of type $supertypeIndex")
        }
    }

    private fun isFieldSubtype(actual: FieldType, expected: FieldType): Boolean {
        if (actual.mutability != expected.mutability) return false
        if (actual.mutability == Mutability.VAR) return actual.storage == expected.storage
        return when {
            actual.storage is StorageType.Value && expected.storage is StorageType.Value ->
                isSubtype(actual.storage.type, expected.storage.type)
            else -> actual.storage == expected.storage
        }
    }

    private fun validateFieldType(field: FieldType, location: String) {
        val storage = field.storage
        if (storage is StorageType.Value) validateValueType(storage.type, location)
    }

    private fun validateImportsAndDefinitions() {
        var importedTagIndex = 0
        module.imports.forEachIndexed { importIndex, import ->
            when (val desc = import.desc) {
                is ImportDesc.Function -> requireTypeIndex(desc.typeIndex, "import $importIndex")
                is ImportDesc.Table -> validateTableType(desc.type, "import $importIndex")
                is ImportDesc.Memory -> validateMemoryType(desc.type, "import $importIndex")
                is ImportDesc.Global -> validateGlobalType(desc.type, "import $importIndex")
                is ImportDesc.Tag -> {
                    validateTag(desc.typeIndex, desc.attribute, "imported tag $importedTagIndex")
                    importedTagIndex++
                }
            }
        }

        module.functions.forEachIndexed { index, function ->
            requireTypeIndex(function.typeIndex, "function ${module.importedFunctionCount + index}")
            checkLimit(
                "locals per function",
                function.locals.size.toLong(),
                limits.maxLocalsPerFunction,
                "function ${module.importedFunctionCount + index}",
            )
            checkLimit(
                "function body size",
                if (function.encodedBodySizeBytes > 0) {
                    function.encodedBodySizeBytes.toLong()
                } else {
                    estimateFunctionBodyBytes(function)
                },
                limits.maxFunctionBodySizeBytes,
                "function ${module.importedFunctionCount + index}",
            )
            function.locals.forEach { validateValueType(it, "function ${module.importedFunctionCount + index} local") }
        }
        module.tables.forEachIndexed { index, table ->
            validateTableType(table.type, "table ${module.importedTableCount + index}")
            validateConstantExpression(
                table.init,
                table.type.element,
                "table ${module.importedTableCount + index} initializer",
                availableGlobalCount = module.importedGlobalCount,
            )
        }
        module.memories.forEachIndexed { index, memory ->
            validateMemoryType(memory.type, "memory ${module.importedMemoryCount + index}")
        }
        module.tags.forEachIndexed { index, tag ->
            validateTag(tag.typeIndex, tag.attribute, "tag ${module.importedTagCount + index}")
        }
    }

    private fun validateGlobalType(type: GlobalType, location: String) {
        validateValueType(type.type, "$location type")
    }

    private fun validateTag(typeIndex: Int, attribute: Int, location: String) {
        if (attribute != 0) invalid("$location has unsupported attribute $attribute")
        val type = requireTypeIndex(typeIndex, location)
        if (type.results.isNotEmpty()) invalid("$location type must have no results")
    }

    private fun validateMemoryType(type: MemoryType, location: String) {
        validateLimits(type.limits, isMemory = true, location = location)
    }

    private fun validateTableType(type: TableType, location: String) {
        validateReferenceType(type.element, "$location element type")
        validateLimits(type.limits, isMemory = false, location = location)
    }

    private fun validateLimits(value: Limits, isMemory: Boolean, location: String) {
        if (value.shared) throw UnsupportedFeature("threads", location)

        val min = value.min
        val max = value.max
        if (max != null && min > max) invalid("$location minimum $min exceeds maximum $max")

        if (isMemory) {
            checkUnsignedLimit("memory pages", min, limits.maxMemoryPages, location)
            if (max != null) checkUnsignedLimit("memory pages", max, limits.maxMemoryPages, location)
            if (
                value.indexType == IndexType.I32 &&
                (min > WASM32_MAX_MEMORY_PAGES || (max ?: 0uL) > WASM32_MAX_MEMORY_PAGES)
            ) {
                invalid("$location exceeds the WebAssembly i32 memory maximum of $WASM32_MAX_MEMORY_PAGES pages")
            }
        } else {
            checkUnsignedLimit("table elements", min, limits.maxTableElements, location)
            if (max != null) checkUnsignedLimit("table elements", max, limits.maxTableElements, location)
        }
    }

    private fun validateExports() {
        val names = mutableSetOf<String>()
        module.exports.forEach { export ->
            if (!names.add(export.name)) invalid("duplicate export name '${export.name}'")
            when (val desc = export.desc) {
                is ExportDesc.Function -> requireIndex(desc.index, functionTypeIndices.size, "function export '${export.name}'")
                is ExportDesc.Table -> requireIndex(desc.index, tableTypes.size, "table export '${export.name}'")
                is ExportDesc.Memory -> requireIndex(desc.index, memoryTypes.size, "memory export '${export.name}'")
                is ExportDesc.Global -> requireIndex(desc.index, globalTypes.size, "global export '${export.name}'")
                is ExportDesc.Tag -> requireIndex(desc.index, tagTypeIndices.size, "tag export '${export.name}'")
            }
        }
    }

    private fun validateStart() {
        val start = module.startFunction ?: return
        val type = functionType(start, "start function")
        if (type.params.isNotEmpty() || type.results.isNotEmpty()) {
            invalid("start function $start must have type [] -> []")
        }
    }

    private fun validateGlobals() {
        module.globals.forEachIndexed { index, global ->
            val absoluteIndex = module.importedGlobalCount + index
            validateGlobalType(global.type, "global $absoluteIndex")
            validateConstantExpression(
                global.init,
                global.type.type,
                "global $absoluteIndex initializer",
                availableGlobalCount = absoluteIndex,
            )
        }
    }

    private fun validateElements() {
        module.elementSegments.forEachIndexed { index, segment ->
            val location = "element segment $index"
            val segmentType = when (val mode = segment.mode) {
                is ElementMode.Active -> {
                    val table = tableType(mode.tableIndex, location)
                    if (segment.tableIndex != mode.tableIndex) {
                        invalid("$location has inconsistent table indices ${segment.tableIndex} and ${mode.tableIndex}")
                    }
                    val type = segment.activeType ?: FuncRefType
                    validateReferenceType(type, "$location type")
                    if (!isSubtype(type, table.element)) {
                        invalid("$location type $type is not compatible with table element type ${table.element}")
                    }
                    validateConstantExpression(
                        mode.offset,
                        indexValueType(table.limits.indexType),
                        "$location offset",
                    )
                    type
                }
                is ElementMode.Passive -> {
                    validateReferenceType(mode.type, "$location type")
                    if (segment.activeType != null && !sameType(segment.activeType, mode.type)) {
                        invalid("$location has inconsistent element types ${segment.activeType} and ${mode.type}")
                    }
                    mode.type
                }
                is ElementMode.Declarative -> {
                    validateReferenceType(mode.type, "$location type")
                    if (segment.activeType != null && !sameType(segment.activeType, mode.type)) {
                        invalid("$location has inconsistent element types ${segment.activeType} and ${mode.type}")
                    }
                    mode.type
                }
            }
            segment.exprs.forEachIndexed { exprIndex, expr ->
                validateConstantExpression(expr, segmentType, "$location expression $exprIndex")
            }
        }
    }

    private fun validateDataSegments() {
        val declaredCount = module.dataCount
        if (declaredCount != null) {
            if (declaredCount < 0) invalid("data count must be non-negative")
            if (declaredCount != module.dataSegments.size) {
                invalid("data count $declaredCount does not match ${module.dataSegments.size} data segments")
            }
        }

        module.dataSegments.forEachIndexed { index, segment ->
            when (val mode = segment.mode) {
                is DataMode.Active -> {
                    val memory = memoryType(mode.memoryIndex, "data segment $index")
                    validateConstantExpression(
                        mode.offset,
                        indexValueType(memory.limits.indexType),
                        "data segment $index offset",
                    )
                }
                DataMode.Passive -> Unit
            }
        }
    }

    private fun validateFunctions() {
        module.functions.forEachIndexed { localIndex, function ->
            val absoluteIndex = module.importedFunctionCount + localIndex
            validateControlNesting(function.body, "function $absoluteIndex")
            val type = requireTypeIndex(function.typeIndex, "function $absoluteIndex")
            FunctionValidator(
                owner = this,
                functionIndex = absoluteIndex,
                functionType = type,
                locals = type.params + function.locals,
                parameterCount = type.params.size,
            ).validate(function.body)
        }
    }

    private fun validateControlNesting(body: List<Instr>, location: String) {
        val pending = ArrayDeque<Pair<List<Instr>, Long>>()
        pending.addLast(body to 0L)
        while (pending.isNotEmpty()) {
            val (sequence, parentDepth) = pending.removeLast()
            sequence.forEach { instruction ->
                val children: List<List<Instr>> = when (instruction) {
                    is Block -> listOf(instruction.body)
                    is Loop -> listOf(instruction.body)
                    is If -> listOf(instruction.thenBody, instruction.elseBody)
                    is TryTable -> listOf(instruction.body)
                    is LegacyTry -> buildList {
                        add(instruction.body)
                        instruction.catches.forEach { add(it.body) }
                        instruction.catchAll?.let(::add)
                    }
                    else -> emptyList()
                }
                if (children.isNotEmpty()) {
                    val depth = parentDepth + 1L
                    checkLimit(
                        "control nesting",
                        depth,
                        limits.maxControlNesting,
                        location,
                    )
                    children.forEach { pending.addLast(it to depth) }
                }
            }
        }
    }

    private fun validateConstantExpression(
        expr: List<Instr>,
        expected: ValType,
        location: String,
        availableGlobalCount: Int = globalTypes.size,
    ) {
        validateValueType(expected, "$location expected type")
        val stack = mutableListOf<ValType>()

        fun pop(type: ValType) {
            if (stack.isEmpty()) invalid("$location has an operand stack underflow")
            val actual = stack.removeAt(stack.lastIndex)
            if (!isSubtype(actual, type)) invalid("$location expected $type but found $actual")
        }

        fun fieldValue(field: FieldType): ValType = when (val storage = field.storage) {
            StorageType.I8, StorageType.I16 -> ValType.I32
            is StorageType.Value -> storage.type
        }

        fun structType(index: Int): StructType =
            requireDefinedType(index, location) as? StructType
                ?: invalid("$location type $index is not a struct")

        fun arrayType(index: Int): ArrayType =
            requireDefinedType(index, location) as? ArrayType
                ?: invalid("$location type $index is not an array")

        fun requireDefaultable(field: FieldType) {
            val valueType = (field.storage as? StorageType.Value)?.type ?: return
            if (asReferenceType(valueType)?.nullable == false) {
                invalid("$location non-null field has no default value")
            }
        }

        fun popReference(expectedHeap: HeapType): RefType {
            if (stack.isEmpty()) invalid("$location has an operand stack underflow")
            val actual = stack.removeAt(stack.lastIndex)
            val reference = asReferenceType(actual)
                ?: invalid("$location expected a reference but found $actual")
            if (!heapSubtype(reference.heap, expectedHeap)) {
                invalid("$location expected a $expectedHeap reference but found $actual")
            }
            return reference
        }

        expr.forEachIndexed { instructionIndex, instruction ->
            when (instruction) {
                is I32Const -> stack.add(ValType.I32)
                is I64Const -> stack.add(ValType.I64)
                is F32Const -> stack.add(ValType.F32)
                is F64Const -> stack.add(ValType.F64)
                is RefNull -> {
                    validateHeapType(instruction.heap, "$location instruction $instructionIndex")
                    stack.add(RefType(instruction.heap, nullable = true))
                }
                is RefFunc -> {
                    val typeIndex = functionTypeIndex(instruction.funcIndex, "$location instruction $instructionIndex")
                    requireDeclaredFunctionRef(instruction.funcIndex, "$location instruction $instructionIndex")
                    stack.add(RefType(HeapType.Index(typeIndex), nullable = false))
                }
                is FcIndex -> {
                    if (instruction.opcode != 0x23) {
                        invalid("$location contains non-constant instruction 0x${instruction.opcode.toString(16)}")
                    }
                    if (instruction.index < 0 || instruction.index >= availableGlobalCount) {
                        invalid("$location global.get index ${instruction.index} is not an available preceding global")
                    }
                    val global = globalTypes[instruction.index]
                    if (global.mutability != Mutability.CONST) {
                        invalid("$location global.get index ${instruction.index} is mutable")
                    }
                    stack.add(global.type)
                }
                is Simple -> when (instruction.opcode) {
                    0x6A, 0x6B, 0x6C -> {
                        pop(ValType.I32)
                        pop(ValType.I32)
                        stack.add(ValType.I32)
                    }
                    0x7C, 0x7D, 0x7E -> {
                        pop(ValType.I64)
                        pop(ValType.I64)
                        stack.add(ValType.I64)
                    }
                    else -> invalid("$location contains non-constant instruction 0x${instruction.opcode.toString(16)}")
                }
                is Gc -> when (instruction.subOpcode) {
                    0 -> {
                        val type = structType(instruction.firstIndex)
                        type.fields.asReversed().forEach { pop(fieldValue(it)) }
                        stack.add(
                            RefType(
                                HeapType.Index(instruction.firstIndex),
                                nullable = false,
                            ),
                        )
                    }
                    1 -> {
                        val type = structType(instruction.firstIndex)
                        type.fields.forEach(::requireDefaultable)
                        stack.add(
                            RefType(
                                HeapType.Index(instruction.firstIndex),
                                nullable = false,
                            ),
                        )
                    }
                    6 -> {
                        val type = arrayType(instruction.firstIndex)
                        pop(ValType.I32)
                        pop(fieldValue(type.field))
                        stack.add(
                            RefType(
                                HeapType.Index(instruction.firstIndex),
                                nullable = false,
                            ),
                        )
                    }
                    7 -> {
                        val type = arrayType(instruction.firstIndex)
                        requireDefaultable(type.field)
                        pop(ValType.I32)
                        stack.add(
                            RefType(
                                HeapType.Index(instruction.firstIndex),
                                nullable = false,
                            ),
                        )
                    }
                    8 -> {
                        val type = arrayType(instruction.firstIndex)
                        if (instruction.count < 0) {
                            invalid("$location array.new_fixed has a negative element count")
                        }
                        repeat(instruction.count) { pop(fieldValue(type.field)) }
                        stack.add(
                            RefType(
                                HeapType.Index(instruction.firstIndex),
                                nullable = false,
                            ),
                        )
                    }
                    26 -> {
                        val reference = popReference(HeapType.Extern)
                        stack.add(RefType(HeapType.Any, reference.nullable))
                    }
                    27 -> {
                        val reference = popReference(HeapType.Any)
                        stack.add(RefType(HeapType.Extern, reference.nullable))
                    }
                    28 -> {
                        pop(ValType.I32)
                        stack.add(RefType(HeapType.I31, nullable = false))
                    }
                    else ->
                        invalid(
                            "$location contains non-constant GC instruction " +
                                "0xfb/${instruction.subOpcode}",
                        )
                }
                is RawImmediate -> rejectRawFeature(instruction, location)
                else -> invalid("$location contains non-constant instruction ${instructionName(instruction)}")
            }
        }

        if (stack.size != 1) invalid("$location must produce exactly one value, but produced ${stack.size}")
        val actual = stack.single()
        if (!isSubtype(actual, expected)) invalid("$location has type $actual, expected $expected")
    }

    private fun validateValueType(type: ValType, location: String) {
        if (type === ValType.V128 && !limits.allowInertV128Types) {
            throw UnsupportedFeature("simd", location)
        }
        asReferenceType(type)?.let { validateReferenceType(it, location) }
    }

    private fun validateReferenceType(type: RefType, location: String) {
        validateHeapType(type.heap, location)
    }

    private fun validateHeapType(type: HeapType, location: String) {
        if (type is HeapType.Index) requireDefinedType(type.value, location)
    }

    private fun requireTypeIndex(index: Int, location: String): FuncType {
        return requireDefinedType(index, location) as? FuncType
            ?: invalid("$location type index $index is not a function type")
    }

    private fun requireDefinedType(index: Int, location: String): DefinedType {
        requireIndex(index, module.types.size, "$location type")
        return module.types[index]
    }

    private fun functionType(index: Int, location: String): FuncType =
        requireTypeIndex(functionTypeIndex(index, location), location)

    private fun functionTypeIndex(index: Int, location: String): Int {
        requireIndex(index, functionTypeIndices.size, location)
        return functionTypeIndices[index]
    }

    private fun requireDeclaredFunctionRef(index: Int, location: String) {
        if (index !in declaredFunctionRefs) {
            invalid("$location ref.func index $index is not declared by an initializer, element, or export")
        }
    }

    private fun tableType(index: Int, location: String): TableType {
        requireIndex(index, tableTypes.size, "$location table")
        return tableTypes[index]
    }

    private fun memoryType(index: Int, location: String): MemoryType {
        requireIndex(index, memoryTypes.size, "$location memory")
        return memoryTypes[index]
    }

    private fun globalType(index: Int, location: String): GlobalType {
        requireIndex(index, globalTypes.size, "$location global")
        return globalTypes[index]
    }

    private fun tagType(index: Int, location: String): FuncType {
        requireIndex(index, tagTypeIndices.size, "$location tag")
        return requireTypeIndex(tagTypeIndices[index], location)
    }

    private fun requireIndex(index: Int, size: Int, what: String) {
        if (index < 0 || index >= size) invalid("unknown $what index $index (size $size)")
    }

    private fun checkLimit(name: String, actual: Long, maximum: Long, location: String? = null) {
        if (actual > maximum) throw LimitExceeded(name, actual, maximum, location)
    }

    private fun checkUnsignedLimit(
        name: String,
        actual: ULong,
        maximum: Long,
        location: String? = null,
    ) {
        val maximumUnsigned = maximum.toULong()
        if (actual > maximumUnsigned) {
            throw LimitExceeded(
                name,
                if (actual > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else actual.toLong(),
                maximum,
                location,
            )
        }
    }

    private fun invalid(message: String): Nothing = throw InvalidModule(message)

    private fun rejectRawFeature(instruction: RawImmediate, location: String): Nothing = when (instruction.opcode) {
        0xFD -> throw UnsupportedFeature("simd", location)
        0xFE -> throw UnsupportedFeature("threads", location)
        else -> invalid("$location contains unknown raw instruction 0x${instruction.opcode.toString(16)}")
    }

    private fun estimateFunctionBodyBytes(function: Function): Long =
        saturatingAdd(2L + function.locals.size, estimateInstructionBytes(function.body))

    private fun estimateInstructionBytes(instructions: List<Instr>): Long {
        var size = 0L
        val pending = ArrayDeque<List<Instr>>()
        pending.addLast(instructions)
        while (pending.isNotEmpty()) {
            val body = pending.removeLast()
            body.forEach { instruction ->
                size = saturatingAdd(size, instructionEstimatedSize(instruction))
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
        return size
    }

    private fun instructionEstimatedSize(instruction: Instr): Long = when (instruction) {
        is I32Const, is F32Const, is RefNull, is RefFunc, is Br, is BrIf, is Call, is ReturnCall -> 2L
        is I64Const, is F64Const -> 3L
        is Block, is Loop -> 3L
        is If -> 4L
        is BrTable -> 3L + instruction.rawTargets.size
        is CallIndirect, is ReturnCallIndirect -> 3L
        is CallRef, is ReturnCallRef -> 2L
        is SelectT -> 2L + instruction.types.size
        is FcIndex -> 3L
        is Load, is StoreInstruction -> 3L
        is MemoryInit, is MemoryCopy, is MemoryFill -> 4L
        is RawImmediate -> 2L + instruction.rawImmediates.size + instruction.rawBytes.size
        else -> 1L
    }

    private fun instructionName(instruction: Instr): String =
        "opcode 0x${instruction.opcode.toString(16)}"

    private fun indexValueType(indexType: IndexType): ValType = when (indexType) {
        IndexType.I32 -> ValType.I32
        IndexType.I64 -> ValType.I64
    }

    private fun asReferenceType(type: ValType): RefType? = when (type) {
        is RefType -> type
        is ValType.Ref -> RefType(type.heap, type.nullable)
        else -> null
    }

    private fun sameType(left: ValType, right: ValType): Boolean =
        normalizeRefType(left) == normalizeRefType(right)

    private fun isSubtype(actual: ValType, expected: ValType): Boolean {
        return module.isValueSubtype(actual, expected)
    }

    private fun heapSubtype(actual: HeapType, expected: HeapType): Boolean =
        module.isHeapSubtype(actual, expected)

    private fun joinTypes(left: ValType, right: ValType): ValType? {
        if (isSubtype(left, right)) return right
        if (isSubtype(right, left)) return left
        val leftRef = asReferenceType(left) ?: return null
        val rightRef = asReferenceType(right) ?: return null
        if (!heapSubtype(leftRef.heap, HeapType.Func) || !heapSubtype(rightRef.heap, HeapType.Func)) return null
        return RefType(HeapType.Func, leftRef.nullable || rightRef.nullable)
    }

    private inner class FunctionValidator(
        private val owner: Validator,
        private val functionIndex: Int,
        private val functionType: FuncType,
        private val locals: List<ValType>,
        private val parameterCount: Int,
    ) {
        private val operands = mutableListOf<ValType?>()
        private val controls = mutableListOf<ValidatorControlFrame>()
        private val initializedLocals: MutableSet<Int> = buildSet {
            locals.forEachIndexed { index, type ->
                if (index < parameterCount || isDefaultable(type)) add(index)
            }
        }.toMutableSet()
        private var nextInstructionIndex = 0
        private var currentInstructionIndex = 0

        fun validate(body: List<Instr>) {
            enterControl(
                labelTypes = functionType.results,
                endTypes = functionType.results,
                startTypes = emptyList(),
                isLoop = false,
            )
            validateSequence(body)
            leaveControl(currentInstructionIndex)
            if (operands.size != functionType.results.size) {
                invalidInstruction("function leaves ${operands.size} values, expected ${functionType.results.size}")
            }
        }

        private fun validateSequence(body: List<Instr>) {
            body.forEach { instruction ->
                currentInstructionIndex = nextInstructionIndex++
                validateInstruction(instruction, currentInstructionIndex)
            }
        }

        private fun validateInstruction(instruction: Instr, instructionIndex: Int) {
            when (instruction) {
                is I32Const -> push(ValType.I32)
                is I64Const -> push(ValType.I64)
                is F32Const -> push(ValType.F32)
                is F64Const -> push(ValType.F64)

                is RefNull -> {
                    owner.validateHeapType(instruction.heap, instructionLocation(instructionIndex))
                    push(RefType(instruction.heap, nullable = true))
                }
                is RefFunc -> {
                    val typeIndex = owner.functionTypeIndex(instruction.funcIndex, instructionLocation(instructionIndex))
                    owner.requireDeclaredFunctionRef(instruction.funcIndex, instructionLocation(instructionIndex))
                    push(RefType(HeapType.Index(typeIndex), nullable = false))
                }
                RefIsNull -> {
                    val actual = popAny()
                    if (actual != null && owner.asReferenceType(actual) == null) {
                        invalidInstruction("ref.is_null expected a reference, found $actual", instructionIndex)
                    }
                    push(ValType.I32)
                }
                RefEq -> {
                    pop(EqRefType)
                    pop(EqRefType)
                    push(ValType.I32)
                }
                RefAsNonNull -> {
                    val actual = popAny()
                    val ref = actual?.let(owner::asReferenceType)
                    if (actual != null && ref == null) {
                        invalidInstruction("ref.as_non_null expected a reference, found $actual", instructionIndex)
                    }
                    if (ref != null) push(ref.copy(nullable = false))
                }
                is BrOnNull -> validateBrOnNull(instruction.depth, branchOnNull = true, instructionIndex)
                is BrOnNonNull -> validateBrOnNull(instruction.depth, branchOnNull = false, instructionIndex)

                Unreachable -> markUnreachable()
                Nop -> Unit
                Return -> {
                    popAll(functionType.results)
                    markUnreachable()
                }
                is Drop -> {
                    if (instruction.n < 1) invalidInstruction("drop count must be positive", instructionIndex)
                    repeat(instruction.n) { popAny() }
                }
                Select -> validateUntypedSelect(instructionIndex)
                is SelectT -> validateTypedSelect(instruction.types, instructionIndex)

                is Block -> validateBlock(instruction.blockType, instruction.body, isLoop = false, instructionIndex)
                is Loop -> validateBlock(instruction.blockType, instruction.body, isLoop = true, instructionIndex)
                is If -> validateIf(instruction, instructionIndex)
                Else -> invalidInstruction("standalone else marker", instructionIndex)
                End -> invalidInstruction("standalone end marker", instructionIndex)
                is Br -> {
                    val target = controlAtDepth(instruction.depth, instructionIndex)
                    recordBranchInitialization(target)
                    popAll(target.labelTypes)
                    markUnreachable()
                }
                is BrIf -> {
                    pop(ValType.I32)
                    val target = controlAtDepth(instruction.depth, instructionIndex)
                    recordBranchInitialization(target)
                    popAll(target.labelTypes)
                    pushAll(target.labelTypes)
                }
                is BrTable -> validateBrTable(instruction, instructionIndex)

                is Call -> applyCall(owner.functionType(instruction.funcIndex, instructionLocation(instructionIndex)))
                is CallIndirect -> validateCallIndirect(instruction.typeIndex, instruction.tableIndex, tail = false, ref = false, instructionIndex)
                is ReturnCall -> validateReturnCall(owner.functionType(instruction.funcIndex, instructionLocation(instructionIndex)), instructionIndex)
                is ReturnCallIndirect -> validateCallIndirect(instruction.typeIndex, instruction.tableIndex, tail = true, ref = false, instructionIndex)
                is CallRef -> validateCallRef(instruction.typeIndex, tail = false, instructionIndex)
                is ReturnCallRef -> validateCallRef(instruction.typeIndex, tail = true, instructionIndex)

                is Load -> validateLoad(instruction, instructionIndex)
                is StoreInstruction -> validateStore(instruction, instructionIndex)
                MemorySize -> push(memoryIndexType(0, instructionIndex))
                is MemorySizeAt -> push(memoryIndexType(instruction.memoryIndex, instructionIndex))
                MemoryGrow -> {
                    val addressType = memoryIndexType(0, instructionIndex)
                    applySignature(listOf(addressType), listOf(addressType))
                }
                is MemoryGrowAt -> {
                    val addressType = memoryIndexType(instruction.memoryIndex, instructionIndex)
                    applySignature(listOf(addressType), listOf(addressType))
                }
                is MemoryFill -> validateMemoryFill(instruction, instructionIndex)
                is MemoryCopy -> validateMemoryCopy(instruction, instructionIndex)
                is MemoryInit -> validateMemoryInit(instruction, instructionIndex)
                DataDrop -> {
                    requireDataCount(instructionIndex)
                    owner.requireIndex(0, module.dataSegments.size, "data.drop data segment")
                }

                is Simple -> validateSimple(instruction.opcode, instructionIndex)
                is Plain -> validatePlain(instruction.opcode, instructionIndex)
                is FcIndex -> validateFcIndex(instruction, instructionIndex)
                is RawImmediate -> when (instruction.opcode) {
                    0xFD -> throw UnsupportedFeature("simd", instructionLocation(instructionIndex))
                    0xFE -> throw UnsupportedFeature("threads", instructionLocation(instructionIndex))
                    else -> invalidInstruction("unknown raw instruction 0x${instruction.opcode.toString(16)}", instructionIndex)
                }
                is Throw -> {
                    popAll(owner.tagType(instruction.tagIndex, instructionLocation(instructionIndex)).params)
                    markUnreachable()
                }
                ThrowRef -> {
                    pop(ExnRefType)
                    markUnreachable()
                }
                is TryTable -> validateTryTable(instruction, instructionIndex)
                is LegacyTry -> validateLegacyTry(instruction, instructionIndex)
                is Rethrow -> {
                    val target = controlAtDepth(instruction.depth, instructionIndex)
                    if (!target.isLegacyCatch) {
                        invalidInstruction(
                            "rethrow depth ${instruction.depth} does not target a legacy catch",
                            instructionIndex,
                        )
                    }
                    markUnreachable()
                }
                is Gc -> validateGc(instruction, instructionIndex)
            }
        }

        private fun validateTryTable(instruction: TryTable, instructionIndex: Int) {
            val signature = blockSignature(instruction.blockType, instructionIndex)
            // Catch label indices are resolved in the surrounding context C,
            // before the try_table body extends C.LABELS with its own label.
            instruction.catches.forEach { clause ->
                val payload = when (clause) {
                    is CatchClause.Tagged -> {
                        val values = owner.tagType(
                            clause.tagIndex,
                            instructionLocation(instructionIndex),
                        ).params
                        if (clause.withReference) {
                            values + RefType(HeapType.Exn, nullable = false)
                        } else {
                            values
                        }
                    }
                    is CatchClause.All ->
                        if (clause.withReference) {
                            listOf(RefType(HeapType.Exn, nullable = false))
                        } else {
                            emptyList()
                        }
                }
                val target = controlAtDepth(clause.depth, instructionIndex)
                if (!resultTypesSubtype(payload, target.labelTypes)) {
                    invalidInstruction(
                        "catch payload $payload does not match label ${target.labelTypes}",
                        instructionIndex,
                    )
                }
            }
            enterControl(
                labelTypes = signature.results,
                endTypes = signature.results,
                startTypes = signature.params,
                isLoop = false,
            )
            validateSequence(instruction.body)
            currentInstructionIndex = instructionIndex
            leaveControl(instructionIndex)
        }

        private fun validateLegacyTry(instruction: LegacyTry, instructionIndex: Int) {
            val signature = blockSignature(instruction.blockType, instructionIndex)
            popAll(signature.params)
            val base = operands.toList()
            val baseInitialized = initializedLocals.toSet()
            val enteredReachable = isCurrentPathReachable()

            data class ArmResult(val reachable: Boolean, val initialized: Set<Int>)

            fun validateArm(
                body: List<Instr>,
                initial: List<ValType>,
                isLegacyCatch: Boolean,
            ): ArmResult {
                operands.clear()
                operands.addAll(base)
                initializedLocals.clear()
                initializedLocals.addAll(baseInitialized)
                pushAll(initial)
                controls.add(
                    ValidatorControlFrame(
                        labelTypes = signature.results,
                        endTypes = signature.results,
                        height = base.size,
                        isLoop = false,
                        enteredFromReachablePath = enteredReachable,
                        entryInitializedLocals = baseInitialized,
                        isLegacyCatch = isLegacyCatch,
                    ),
                )
                validateSequence(body)
                currentInstructionIndex = instructionIndex
                val reachable = leaveControl(instructionIndex, propagatePathUnreachable = false)
                return ArmResult(reachable, initializedLocals.toSet())
            }

            val results = mutableListOf<ArmResult>()
            results += validateArm(instruction.body, signature.params, isLegacyCatch = false)
            instruction.catches.forEach { catch ->
                val payload = owner.tagType(
                    catch.tagIndex,
                    instructionLocation(instructionIndex),
                ).params
                results += validateArm(catch.body, payload, isLegacyCatch = true)
            }
            instruction.catchAll?.let {
                results += validateArm(it, emptyList(), isLegacyCatch = true)
            }
            if (instruction.delegateDepth != null) {
                controlAtDepth(instruction.delegateDepth, instructionIndex)
            }

            val reachable = results.filter { it.reachable }
            operands.clear()
            operands.addAll(base)
            initializedLocals.clear()
            if (reachable.isNotEmpty()) {
                pushAll(signature.results)
                initializedLocals.addAll(
                    reachable.drop(1).fold(reachable.first().initialized) { acc, arm ->
                        acc intersect arm.initialized
                    },
                )
            } else {
                initializedLocals.addAll(baseInitialized)
                pushAll(signature.results)
                markPathUnreachable()
            }
        }

        private fun validateGc(instruction: Gc, instructionIndex: Int) {
            val location = instructionLocation(instructionIndex)
            fun struct(): StructType =
                owner.requireDefinedType(instruction.firstIndex, location) as? StructType
                    ?: invalidInstruction("type ${instruction.firstIndex} is not a struct", instructionIndex)
            fun array(index: Int = instruction.firstIndex): ArrayType =
                owner.requireDefinedType(index, location) as? ArrayType
                    ?: invalidInstruction("type $index is not an array", instructionIndex)
            fun fieldValue(field: FieldType): ValType = when (val storage = field.storage) {
                StorageType.I8, StorageType.I16 -> ValType.I32
                is StorageType.Value -> storage.type
            }
            fun objectRef(index: Int, nullable: Boolean = true): RefType =
                RefType(HeapType.Index(index), nullable)
            fun requireDefaultable(field: FieldType) {
                val storage = field.storage
                if (storage is StorageType.Value && !isDefaultable(storage.type)) {
                    invalidInstruction("non-null field has no default value", instructionIndex)
                }
            }
            fun popReference(): RefType? {
                val actual = popAny() ?: return null
                return owner.asReferenceType(actual)
                    ?: invalidInstruction("expected a reference, found $actual", instructionIndex)
            }

            when (instruction.subOpcode) {
                0 -> {
                    val type = struct()
                    popAll(type.fields.map(::fieldValue))
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                1 -> {
                    struct().fields.forEach(::requireDefaultable)
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                in 2..5 -> {
                    val type = struct()
                    val field = type.fields.getOrNull(instruction.secondIndex)
                        ?: invalidInstruction(
                            "unknown struct field ${instruction.secondIndex}",
                            instructionIndex,
                        )
                    if (instruction.subOpcode == 5) {
                        if (field.mutability != Mutability.VAR) {
                            invalidInstruction("struct.set targets an immutable field", instructionIndex)
                        }
                        pop(fieldValue(field))
                        pop(objectRef(instruction.firstIndex))
                    } else {
                        if (
                            instruction.subOpcode in 3..4 &&
                            field.storage !is StorageType.I8 &&
                            field.storage !is StorageType.I16
                        ) {
                            invalidInstruction("signed/unsigned struct.get requires a packed field", instructionIndex)
                        }
                        if (
                            instruction.subOpcode == 2 &&
                            (field.storage is StorageType.I8 || field.storage is StorageType.I16)
                        ) {
                            invalidInstruction("packed struct field requires struct.get_s or struct.get_u", instructionIndex)
                        }
                        pop(objectRef(instruction.firstIndex))
                        push(fieldValue(field))
                    }
                }
                6 -> {
                    val type = array()
                    pop(ValType.I32)
                    pop(fieldValue(type.field))
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                7 -> {
                    requireDefaultable(array().field)
                    pop(ValType.I32)
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                8 -> {
                    val type = array()
                    repeat(instruction.count) { pop(fieldValue(type.field)) }
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                9 -> {
                    val type = array()
                    requireDataCount(instructionIndex)
                    owner.requireIndex(instruction.secondIndex, module.dataSegments.size, "array.new_data data")
                    pop(ValType.I32)
                    pop(ValType.I32)
                    validateDataArrayStorage(type.field.storage, instructionIndex)
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                10 -> {
                    val type = array()
                    owner.requireIndex(
                        instruction.secondIndex,
                        module.elementSegments.size,
                        "array.new_elem element",
                    )
                    validateElementArrayStorage(type.field.storage, instruction.secondIndex, instructionIndex)
                    pop(ValType.I32)
                    pop(ValType.I32)
                    push(objectRef(instruction.firstIndex, nullable = false))
                }
                in 11..14 -> {
                    val type = array()
                    if (instruction.subOpcode == 14) {
                        if (type.field.mutability != Mutability.VAR) {
                            invalidInstruction("array.set targets an immutable field", instructionIndex)
                        }
                        pop(fieldValue(type.field))
                        pop(ValType.I32)
                        pop(objectRef(instruction.firstIndex))
                    } else {
                        if (
                            instruction.subOpcode in 12..13 &&
                            type.field.storage !is StorageType.I8 &&
                            type.field.storage !is StorageType.I16
                        ) {
                            invalidInstruction("signed/unsigned array.get requires a packed field", instructionIndex)
                        }
                        if (
                            instruction.subOpcode == 11 &&
                            (type.field.storage is StorageType.I8 ||
                                type.field.storage is StorageType.I16)
                        ) {
                            invalidInstruction("packed array field requires array.get_s or array.get_u", instructionIndex)
                        }
                        pop(ValType.I32)
                        pop(objectRef(instruction.firstIndex))
                        push(fieldValue(type.field))
                    }
                }
                15 -> {
                    val ref = popReference()
                    if (ref != null && !owner.heapSubtype(ref.heap, HeapType.Array)) {
                        invalidInstruction("array.len expected an array reference", instructionIndex)
                    }
                    push(ValType.I32)
                }
                16 -> {
                    val type = array()
                    if (type.field.mutability != Mutability.VAR) {
                        invalidInstruction("array.fill targets an immutable array", instructionIndex)
                    }
                    pop(ValType.I32)
                    pop(fieldValue(type.field))
                    pop(ValType.I32)
                    pop(objectRef(instruction.firstIndex))
                }
                17 -> {
                    val destination = array()
                    val source = array(instruction.secondIndex)
                    if (
                        destination.field.mutability != Mutability.VAR ||
                        !owner.isFieldSubtype(source.field, destination.field)
                    ) {
                        invalidInstruction("array.copy field types are incompatible", instructionIndex)
                    }
                    pop(ValType.I32)
                    pop(ValType.I32)
                    pop(objectRef(instruction.secondIndex))
                    pop(ValType.I32)
                    pop(objectRef(instruction.firstIndex))
                }
                18 -> {
                    val type = array()
                    if (type.field.mutability != Mutability.VAR) {
                        invalidInstruction("array.init_data targets an immutable array", instructionIndex)
                    }
                    requireDataCount(instructionIndex)
                    owner.requireIndex(instruction.secondIndex, module.dataSegments.size, "array.init_data data")
                    validateDataArrayStorage(type.field.storage, instructionIndex)
                    repeat(3) { pop(ValType.I32) }
                    pop(objectRef(instruction.firstIndex))
                }
                19 -> {
                    val type = array()
                    if (type.field.mutability != Mutability.VAR) {
                        invalidInstruction("array.init_elem targets an immutable array", instructionIndex)
                    }
                    owner.requireIndex(
                        instruction.secondIndex,
                        module.elementSegments.size,
                        "array.init_elem element",
                    )
                    validateElementArrayStorage(type.field.storage, instruction.secondIndex, instructionIndex)
                    repeat(3) { pop(ValType.I32) }
                    pop(objectRef(instruction.firstIndex))
                }
                20, 21 -> validateRefTestOrCast(instruction, cast = false, instructionIndex)
                22, 23 -> validateRefTestOrCast(instruction, cast = true, instructionIndex)
                24, 25 -> validateBrOnCast(instruction, instructionIndex)
                26 -> {
                    val ref = popReference()
                    if (ref != null && !owner.heapSubtype(ref.heap, HeapType.Extern)) {
                        invalidInstruction("any.convert_extern expected externref", instructionIndex)
                    }
                    push(RefType(HeapType.Any, ref?.nullable ?: true))
                }
                27 -> {
                    val ref = popReference()
                    if (ref != null && !owner.heapSubtype(ref.heap, HeapType.Any)) {
                        invalidInstruction("extern.convert_any expected anyref", instructionIndex)
                    }
                    push(RefType(HeapType.Extern, ref?.nullable ?: true))
                }
                28 -> {
                    pop(ValType.I32)
                    push(RefType(HeapType.I31, nullable = false))
                }
                29, 30 -> {
                    pop(I31RefType)
                    push(ValType.I32)
                }
                else -> invalidInstruction("unknown GC opcode ${instruction.subOpcode}", instructionIndex)
            }
        }

        private fun validateDataArrayStorage(storage: StorageType, instructionIndex: Int) {
            val valid = when (storage) {
                StorageType.I8, StorageType.I16 -> true
                is StorageType.Value -> storage.type in setOf(
                    ValType.I32,
                    ValType.I64,
                    ValType.F32,
                    ValType.F64,
                    ValType.V128,
                )
            }
            if (!valid) {
                invalidInstruction("array data instruction requires numeric or packed storage", instructionIndex)
            }
        }

        private fun validateElementArrayStorage(
            storage: StorageType,
            elementIndex: Int,
            instructionIndex: Int,
        ) {
            val valueType = (storage as? StorageType.Value)?.type
            val refType = valueType?.let(owner::asReferenceType)
                ?: invalidInstruction("array element instruction requires reference storage", instructionIndex)
            val segment = module.elementSegments[elementIndex]
            val segmentType = when (val mode = segment.mode) {
                is ElementMode.Active -> segment.activeType ?: FuncRefType
                is ElementMode.Passive -> mode.type
                is ElementMode.Declarative -> mode.type
            }
            if (!owner.isSubtype(segmentType, refType)) {
                invalidInstruction(
                    "element type $segmentType is not compatible with array field $refType",
                    instructionIndex,
                )
            }
        }

        private fun validateBrOnNull(depth: Int, branchOnNull: Boolean, instructionIndex: Int) {
            val target = controlAtDepth(depth, instructionIndex)
            recordBranchInitialization(target)
            if (branchOnNull) {
                val actual = popAny()
                val ref = actual?.let(owner::asReferenceType)
                if (actual != null && ref == null) {
                    invalidInstruction("br_on_null expected a reference, found $actual", instructionIndex)
                }
                popAll(target.labelTypes)
                pushAll(target.labelTypes)
                push(ref?.copy(nullable = false))
            } else {
                val labelRef = target.labelTypes.lastOrNull()?.let(owner::asReferenceType)
                    ?: invalidInstruction(
                        "br_on_non_null label must end in a reference type",
                        instructionIndex,
                    )
                pop(labelRef.copy(nullable = true))
                val prefix = target.labelTypes.dropLast(1)
                popAll(prefix)
                pushAll(prefix)
            }
        }

        private fun validateRefTestOrCast(
            instruction: Gc,
            cast: Boolean,
            instructionIndex: Int,
        ) {
            val target = instruction.targetType
                ?: invalidInstruction("reference cast is missing its target type", instructionIndex)
            owner.validateReferenceType(target, instructionLocation(instructionIndex))
            val actual = popAny()
            val source = actual?.let(owner::asReferenceType)
            if (actual != null && source == null) {
                invalidInstruction("reference cast expected a reference, found $actual", instructionIndex)
            }
            val sourceTop = when {
                owner.heapSubtype(target.heap, HeapType.Any) -> HeapType.Any
                owner.heapSubtype(target.heap, HeapType.Func) -> HeapType.Func
                owner.heapSubtype(target.heap, HeapType.Extern) -> HeapType.Extern
                owner.heapSubtype(target.heap, HeapType.Exn) -> HeapType.Exn
                else -> invalidInstruction(
                    "reference cast target $target has no reference hierarchy",
                    instructionIndex,
                )
            }
            val expectedSource = RefType(sourceTop, nullable = true)
            if (source != null && !owner.isSubtype(source, expectedSource)) {
                invalidInstruction(
                    "reference cast source $source is not in the target hierarchy $expectedSource",
                    instructionIndex,
                )
            }
            push(if (cast) target else ValType.I32)
        }

        private fun validateBrOnCast(instruction: Gc, instructionIndex: Int) {
            val source = instruction.sourceType
                ?: invalidInstruction("br_on_cast is missing its source type", instructionIndex)
            val castType = instruction.targetType
                ?: invalidInstruction("br_on_cast is missing its target type", instructionIndex)
            val location = instructionLocation(instructionIndex)
            owner.validateReferenceType(source, location)
            owner.validateReferenceType(castType, location)
            if (!owner.isSubtype(castType, source)) {
                invalidInstruction(
                    "br_on_cast target $castType is not a subtype of source $source",
                    instructionIndex,
                )
            }

            pop(source)
            val target = controlAtDepth(instruction.depth, instructionIndex)
            val labelRef = target.labelTypes.lastOrNull()?.let(owner::asReferenceType)
                ?: invalidInstruction(
                    "br_on_cast label must end in a reference type",
                    instructionIndex,
                )
            val difference = referenceTypeDifference(source, castType)
            val branchType = if (instruction.subOpcode == 24) castType else difference
            if (!owner.isSubtype(branchType, labelRef)) {
                invalidInstruction(
                    "br_on_cast branch type $branchType does not match label type $labelRef",
                    instructionIndex,
                )
            }

            recordBranchInitialization(target)
            val prefix = target.labelTypes.dropLast(1)
            popAll(prefix)
            pushAll(prefix)
            push(if (instruction.subOpcode == 24) difference else castType)
        }

        private fun referenceTypeDifference(source: RefType, removed: RefType): RefType =
            if (removed.nullable) source.copy(nullable = false) else source

        private fun validateUntypedSelect(instructionIndex: Int) {
            pop(ValType.I32)
            val right = popAny()
            val left = popAny()
            if (left == null || right == null) {
                val known = left ?: right
                if (known != null && owner.asReferenceType(known) != null) {
                    invalidInstruction("untyped select does not accept reference operands", instructionIndex)
                }
                push(known)
                return
            }
            if (owner.asReferenceType(left) != null || owner.asReferenceType(right) != null) {
                invalidInstruction("untyped select does not accept reference operands", instructionIndex)
            }
            val joined = owner.joinTypes(left, right)
                ?: invalidInstruction("select operands have different types $left and $right", instructionIndex)
            push(joined)
        }

        private fun validateTypedSelect(types: List<ValType>, instructionIndex: Int) {
            if (types.size != 1) invalidInstruction("typed select must declare exactly one result type", instructionIndex)
            val type = types.single()
            owner.validateValueType(type, instructionLocation(instructionIndex))
            pop(ValType.I32)
            pop(type)
            pop(type)
            push(type)
        }

        private fun validateBlock(
            blockType: BlockType,
            body: List<Instr>,
            isLoop: Boolean,
            instructionIndex: Int,
        ) {
            val signature = blockSignature(blockType, instructionIndex)
            enterControl(
                labelTypes = if (isLoop) signature.params else signature.results,
                endTypes = signature.results,
                startTypes = signature.params,
                isLoop = isLoop,
            )
            validateSequence(body)
            currentInstructionIndex = instructionIndex
            leaveControl(instructionIndex)
        }

        private fun validateIf(instruction: If, instructionIndex: Int) {
            pop(ValType.I32)
            val signature = blockSignature(instruction.blockType, instructionIndex)
            popAll(signature.params)
            val base = operands.toList()
            val baseInitializedLocals = initializedLocals.toSet()
            val enteredFromReachablePath = isCurrentPathReachable()

            data class ArmResult(val reachable: Boolean, val initialized: Set<Int>)

            fun validateArm(body: List<Instr>): ArmResult {
                operands.clear()
                operands.addAll(base)
                initializedLocals.clear()
                initializedLocals.addAll(baseInitializedLocals)
                pushAll(signature.params)
                controls.add(
                    ValidatorControlFrame(
                        labelTypes = signature.results,
                        endTypes = signature.results,
                        height = base.size,
                        isLoop = false,
                        enteredFromReachablePath = enteredFromReachablePath,
                        entryInitializedLocals = baseInitializedLocals,
                    ),
                )
                validateSequence(body)
                currentInstructionIndex = instructionIndex
                val reachable = leaveControl(instructionIndex, propagatePathUnreachable = false)
                return ArmResult(reachable, initializedLocals.toSet())
            }

            val thenResult = validateArm(instruction.thenBody)
            val elseResult = validateArm(instruction.elseBody)
            val reachableResults = listOf(thenResult, elseResult).filter { it.reachable }
            initializedLocals.clear()
            // Initialization changes are scoped to a structured control
            // frame. Even if every arm sets a local, the block's result type
            // cannot communicate that fact to following instructions.
            initializedLocals.addAll(baseInitializedLocals)
            if (reachableResults.isEmpty() && enteredFromReachablePath) {
                markPathUnreachable()
            }
        }

        private fun validateBrTable(instruction: BrTable, instructionIndex: Int) {
            val defaultFrame = controlAtDepth(instruction.defaultTarget, instructionIndex)
            instruction.rawTargets.forEach { depth ->
                val target = controlAtDepth(depth, instructionIndex)
                if (target.labelTypes.size != defaultFrame.labelTypes.size) {
                    invalidInstruction("br_table targets have different label arities", instructionIndex)
                }
            }
            pop(ValType.I32)
            val branchTypes = ArrayList<ValType?>(defaultFrame.labelTypes.size)
            for (index in defaultFrame.labelTypes.indices.reversed()) {
                branchTypes.add(0, pop(defaultFrame.labelTypes[index]))
            }
            (instruction.rawTargets.asList() + instruction.defaultTarget).forEach { depth ->
                val target = controlAtDepth(depth, instructionIndex)
                recordBranchInitialization(target)
                branchTypes.forEachIndexed { index, actual ->
                    if (actual != null && !owner.isSubtype(actual, target.labelTypes[index])) {
                        invalidInstruction(
                            "br_table operand $actual does not match label type ${target.labelTypes[index]}",
                            instructionIndex,
                        )
                    }
                }
            }
            markUnreachable()
        }

        private fun validateCallIndirect(
            typeIndex: Int,
            tableIndex: Int,
            tail: Boolean,
            ref: Boolean,
            instructionIndex: Int,
        ) {
            val type = owner.requireTypeIndex(typeIndex, instructionLocation(instructionIndex))
            val table = owner.tableType(tableIndex, instructionLocation(instructionIndex))
            if (!isFunctionReference(table.element)) {
                invalidInstruction("call_indirect table $tableIndex does not contain function references", instructionIndex)
            }
            if (!ref) pop(owner.indexValueType(table.limits.indexType))
            if (tail) validateReturnCall(type, instructionIndex) else applyCall(type)
        }

        private fun validateCallRef(typeIndex: Int, tail: Boolean, instructionIndex: Int) {
            val type = owner.requireTypeIndex(typeIndex, instructionLocation(instructionIndex))
            pop(RefType(HeapType.Index(typeIndex), nullable = true))
            if (tail) validateReturnCall(type, instructionIndex) else applyCall(type)
        }

        private fun applyCall(type: FuncType) {
            popAll(type.params)
            pushAll(type.results)
        }

        private fun validateReturnCall(type: FuncType, instructionIndex: Int) {
            if (!resultTypesSubtype(type.results, functionType.results)) {
                invalidInstruction("tail call results ${type.results} do not match function results ${functionType.results}", instructionIndex)
            }
            popAll(type.params)
            markUnreachable()
        }

        private fun validateLoad(instruction: Load, instructionIndex: Int) {
            val (result, naturalAlignment) = when (instruction.opcode) {
                0x28 -> ValType.I32 to 2
                0x29 -> ValType.I64 to 3
                0x2A -> ValType.F32 to 2
                0x2B -> ValType.F64 to 3
                in 0x2C..0x2F -> ValType.I32 to if (instruction.opcode <= 0x2D) 0 else 1
                in 0x30..0x35 -> ValType.I64 to when (instruction.opcode) {
                    0x30, 0x31 -> 0
                    0x32, 0x33 -> 1
                    else -> 2
                }
                else -> invalidInstruction("unknown load opcode 0x${instruction.opcode.toString(16)}", instructionIndex)
            }
            validateAlignment(instruction.align, naturalAlignment, instructionIndex)
            validateMemoryOffset(instruction.memoryIndex, instruction.offset, instructionIndex)
            applySignature(listOf(memoryIndexType(instruction.memoryIndex, instructionIndex)), listOf(result))
        }

        private fun validateStore(instruction: StoreInstruction, instructionIndex: Int) {
            val (value, naturalAlignment) = when (instruction.opcode) {
                0x36 -> ValType.I32 to 2
                0x37 -> ValType.I64 to 3
                0x38 -> ValType.F32 to 2
                0x39 -> ValType.F64 to 3
                0x3A -> ValType.I32 to 0
                0x3B -> ValType.I32 to 1
                0x3C -> ValType.I64 to 0
                0x3D -> ValType.I64 to 1
                0x3E -> ValType.I64 to 2
                else -> invalidInstruction("unknown store opcode 0x${instruction.opcode.toString(16)}", instructionIndex)
            }
            validateAlignment(instruction.align, naturalAlignment, instructionIndex)
            validateMemoryOffset(instruction.memoryIndex, instruction.offset, instructionIndex)
            applySignature(listOf(memoryIndexType(instruction.memoryIndex, instructionIndex), value), emptyList())
        }

        private fun validateAlignment(actual: Int, maximum: Int, instructionIndex: Int) {
            if (actual < 0 || actual > maximum) {
                invalidInstruction("alignment exponent $actual exceeds natural alignment $maximum", instructionIndex)
            }
        }

        private fun validateMemoryOffset(memoryIndex: Int, offset: ULong, instructionIndex: Int) {
            val memory = owner.memoryType(memoryIndex, instructionLocation(instructionIndex))
            if (memory.limits.indexType == IndexType.I32 && offset > UInt.MAX_VALUE.toULong()) {
                invalidInstruction("memory offset $offset does not fit the i32 address type", instructionIndex)
            }
        }

        private fun validateMemoryFill(instruction: MemoryFill, instructionIndex: Int) {
            val address = memoryIndexType(instruction.dstIndex, instructionIndex)
            applySignature(listOf(address, ValType.I32, address), emptyList())
        }

        private fun validateMemoryCopy(instruction: MemoryCopy, instructionIndex: Int) {
            val destination = memoryIndexType(instruction.dstIndex, instructionIndex)
            val source = memoryIndexType(instruction.srcIndex, instructionIndex)
            applySignature(listOf(destination, source, minimumAddressType(destination, source)), emptyList())
        }

        private fun validateMemoryInit(instruction: MemoryInit, instructionIndex: Int) {
            requireDataCount(instructionIndex)
            owner.requireIndex(instruction.dataSegment, module.dataSegments.size, "memory.init data segment")
            val address = memoryIndexType(instruction.memIndex, instructionIndex)
            applySignature(listOf(address, ValType.I32, ValType.I32), emptyList())
        }

        private fun validateFcIndex(instruction: FcIndex, instructionIndex: Int) {
            when (instruction.opcode) {
                0x20 -> push(localType(instruction.index, instructionIndex, requireInitialized = true))
                0x21 -> {
                    pop(localType(instruction.index, instructionIndex, requireInitialized = false))
                    initializeLocal(instruction.index)
                }
                0x22 -> {
                    val type = localType(instruction.index, instructionIndex, requireInitialized = false)
                    pop(type)
                    push(type)
                    initializeLocal(instruction.index)
                }
                0x23 -> push(owner.globalType(instruction.index, instructionLocation(instructionIndex)).type)
                0x24 -> {
                    val global = owner.globalType(instruction.index, instructionLocation(instructionIndex))
                    if (global.mutability != Mutability.VAR) {
                        invalidInstruction("global.set targets immutable global ${instruction.index}", instructionIndex)
                    }
                    pop(global.type)
                }
                0x25 -> {
                    val table = owner.tableType(instruction.index, instructionLocation(instructionIndex))
                    pop(owner.indexValueType(table.limits.indexType))
                    push(table.element)
                }
                0x26 -> {
                    val table = owner.tableType(instruction.index, instructionLocation(instructionIndex))
                    pop(table.element)
                    pop(owner.indexValueType(table.limits.indexType))
                }

                0x00FC, 0x01FC -> applySignature(listOf(ValType.F32), listOf(ValType.I32))
                0x02FC, 0x03FC -> applySignature(listOf(ValType.F64), listOf(ValType.I32))
                0x04FC, 0x05FC -> applySignature(listOf(ValType.F32), listOf(ValType.I64))
                0x06FC, 0x07FC -> applySignature(listOf(ValType.F64), listOf(ValType.I64))

                0x09FC -> {
                    requireDataCount(instructionIndex)
                    owner.requireIndex(instruction.index, module.dataSegments.size, "data.drop data segment")
                }
                0x0CFC -> validateTableInit(instruction.index, instruction.second, instructionIndex)
                0x0DFC -> owner.requireIndex(instruction.index, module.elementSegments.size, "elem.drop element segment")
                0x0EFC -> validateTableCopy(instruction.index, instruction.second, instructionIndex)
                0x0FFC -> validateTableGrow(instruction.index, instructionIndex)
                0x10FC -> {
                    val table = owner.tableType(instruction.index, instructionLocation(instructionIndex))
                    push(owner.indexValueType(table.limits.indexType))
                }
                0x11FC -> validateTableFill(instruction.index, instructionIndex)
                0xFB -> throw UnsupportedFeature("gc", instructionLocation(instructionIndex))
                else -> invalidInstruction("unknown indexed opcode 0x${instruction.opcode.toString(16)}", instructionIndex)
            }
        }

        private fun validateTableInit(elementIndex: Int, tableIndex: Int, instructionIndex: Int) {
            owner.requireIndex(elementIndex, module.elementSegments.size, "table.init element segment")
            val segment = module.elementSegments[elementIndex]
            val segmentType = when (val mode = segment.mode) {
                is ElementMode.Active -> segment.activeType ?: FuncRefType
                is ElementMode.Passive -> mode.type
                is ElementMode.Declarative -> mode.type
            }
            val table = owner.tableType(tableIndex, instructionLocation(instructionIndex))
            if (!owner.isSubtype(segmentType, table.element)) {
                invalidInstruction("table.init element type $segmentType does not match ${table.element}", instructionIndex)
            }
            val address = owner.indexValueType(table.limits.indexType)
            applySignature(listOf(address, ValType.I32, ValType.I32), emptyList())
        }

        private fun validateTableCopy(destinationIndex: Int, sourceIndex: Int, instructionIndex: Int) {
            val destination = owner.tableType(destinationIndex, instructionLocation(instructionIndex))
            val source = owner.tableType(sourceIndex, instructionLocation(instructionIndex))
            if (!owner.isSubtype(source.element, destination.element)) {
                invalidInstruction("table.copy source element type ${source.element} does not match ${destination.element}", instructionIndex)
            }
            val destinationAddress = owner.indexValueType(destination.limits.indexType)
            val sourceAddress = owner.indexValueType(source.limits.indexType)
            applySignature(
                listOf(destinationAddress, sourceAddress, minimumAddressType(destinationAddress, sourceAddress)),
                emptyList(),
            )
        }

        private fun validateTableGrow(tableIndex: Int, instructionIndex: Int) {
            val table = owner.tableType(tableIndex, instructionLocation(instructionIndex))
            val address = owner.indexValueType(table.limits.indexType)
            applySignature(listOf(table.element, address), listOf(address))
        }

        private fun validateTableFill(tableIndex: Int, instructionIndex: Int) {
            val table = owner.tableType(tableIndex, instructionLocation(instructionIndex))
            val address = owner.indexValueType(table.limits.indexType)
            applySignature(listOf(address, table.element, address), emptyList())
        }

        private fun validatePlain(opcode: Int, instructionIndex: Int) {
            when (opcode) {
                0xFD -> throw UnsupportedFeature("simd", instructionLocation(instructionIndex))
                0xFE -> throw UnsupportedFeature("threads", instructionLocation(instructionIndex))
                in 0x45..0xC4 -> validateSimple(opcode, instructionIndex)
                else -> invalidInstruction("unknown plain opcode 0x${opcode.toString(16)}", instructionIndex)
            }
        }

        private fun validateSimple(opcode: Int, instructionIndex: Int) {
            val signature = numericSignature(opcode)
                ?: invalidInstruction("unknown numeric opcode 0x${opcode.toString(16)}", instructionIndex)
            applySignature(signature.first, signature.second)
        }

        private fun numericSignature(opcode: Int): Pair<List<ValType>, List<ValType>>? {
            fun unary(input: ValType, output: ValType = input): Pair<List<ValType>, List<ValType>> =
                listOf(input) to listOf(output)
            fun binary(input: ValType, output: ValType = input): Pair<List<ValType>, List<ValType>> =
                listOf(input, input) to listOf(output)

            return when (opcode) {
                0x45 -> unary(ValType.I32)
                in 0x46..0x4F -> binary(ValType.I32)
                0x50 -> unary(ValType.I64, ValType.I32)
                in 0x51..0x5A -> binary(ValType.I64, ValType.I32)
                in 0x5B..0x60 -> binary(ValType.F32, ValType.I32)
                in 0x61..0x66 -> binary(ValType.F64, ValType.I32)
                in 0x67..0x69 -> unary(ValType.I32)
                in 0x6A..0x78 -> binary(ValType.I32)
                in 0x79..0x7B -> unary(ValType.I64)
                in 0x7C..0x8A -> binary(ValType.I64)
                in 0x8B..0x91 -> unary(ValType.F32)
                in 0x92..0x98 -> binary(ValType.F32)
                in 0x99..0x9F -> unary(ValType.F64)
                in 0xA0..0xA6 -> binary(ValType.F64)
                0xA7 -> unary(ValType.I64, ValType.I32)
                in 0xA8..0xA9 -> unary(ValType.F32, ValType.I32)
                in 0xAA..0xAB -> unary(ValType.F64, ValType.I32)
                in 0xAC..0xAD -> unary(ValType.I32, ValType.I64)
                in 0xAE..0xAF -> unary(ValType.F32, ValType.I64)
                in 0xB0..0xB1 -> unary(ValType.F64, ValType.I64)
                in 0xB2..0xB3 -> unary(ValType.I32, ValType.F32)
                in 0xB4..0xB5 -> unary(ValType.I64, ValType.F32)
                0xB6 -> unary(ValType.F64, ValType.F32)
                in 0xB7..0xB8 -> unary(ValType.I32, ValType.F64)
                in 0xB9..0xBA -> unary(ValType.I64, ValType.F64)
                0xBB -> unary(ValType.F32, ValType.F64)
                0xBC -> unary(ValType.F32, ValType.I32)
                0xBD -> unary(ValType.F64, ValType.I64)
                0xBE -> unary(ValType.I32, ValType.F32)
                0xBF -> unary(ValType.I64, ValType.F64)
                in 0xC0..0xC1 -> unary(ValType.I32)
                in 0xC2..0xC4 -> unary(ValType.I64)
                else -> null
            }
        }

        private fun blockSignature(blockType: BlockType, instructionIndex: Int): ValidatorBlockSignature = when (blockType) {
            BlockType.Empty -> ValidatorBlockSignature(emptyList(), emptyList())
            is BlockType.Single -> {
                owner.validateValueType(blockType.type, instructionLocation(instructionIndex))
                ValidatorBlockSignature(emptyList(), listOf(blockType.type))
            }
            is BlockType.TypeIndex -> {
                val type = owner.requireTypeIndex(blockType.index, instructionLocation(instructionIndex))
                ValidatorBlockSignature(type.params, type.results)
            }
        }

        private fun enterControl(
            labelTypes: List<ValType>,
            endTypes: List<ValType>,
            startTypes: List<ValType>,
            isLoop: Boolean,
        ) {
            val enteredFromReachablePath = isCurrentPathReachable()
            popAll(startTypes)
            val height = operands.size
            pushAll(startTypes)
            controls.add(
                ValidatorControlFrame(
                    labelTypes,
                    endTypes,
                    height,
                    isLoop,
                    enteredFromReachablePath,
                    initializedLocals.toSet(),
                ),
            )
        }

        private fun leaveControl(instructionIndex: Int, propagatePathUnreachable: Boolean = true): Boolean {
            val frame = controls.lastOrNull()
                ?: invalidInstruction("internal control stack underflow", instructionIndex)
            popAll(frame.endTypes)
            if (operands.size != frame.height) {
                invalidInstruction("control block leaves unexpected values on the stack", instructionIndex)
            }
            controls.removeAt(controls.lastIndex)
            pushAll(frame.endTypes)
            val fallthroughInitialized = if (frame.enteredFromReachablePath && !frame.pathUnreachable) {
                initializedLocals.toSet()
            } else {
                null
            }
            val hasReachableExit = fallthroughInitialized != null || frame.branchInitializedLocals != null
            initializedLocals.clear()
            initializedLocals.addAll(frame.entryInitializedLocals)
            if (!hasReachableExit && propagatePathUnreachable && controls.isNotEmpty()) {
                markPathUnreachable()
            }
            return hasReachableExit
        }

        private fun controlAtDepth(depth: Int, instructionIndex: Int): ValidatorControlFrame {
            if (depth < 0 || depth >= controls.size) {
                invalidInstruction("unknown label depth $depth", instructionIndex)
            }
            return controls[controls.lastIndex - depth]
        }

        private fun currentControl(): ValidatorControlFrame = controls.lastOrNull()
            ?: invalidInstruction("instruction outside a control frame")

        private fun markUnreachable() {
            val frame = currentControl()
            while (operands.size > frame.height) operands.removeAt(operands.lastIndex)
            frame.unreachable = true
            frame.pathUnreachable = true
        }

        /**
         * Record that execution cannot reach the following instruction without
         * making the enclosing operand stack polymorphic. A completed nested
         * construct still has its declared result types at its boundary.
         */
        private fun markPathUnreachable() {
            currentControl().pathUnreachable = true
        }

        private fun recordBranchInitialization(target: ValidatorControlFrame) {
            if (!isCurrentPathReachable() || target.isLoop) return
            target.branchInitializedLocals = target.branchInitializedLocals?.intersect(initializedLocals)
                ?: initializedLocals.toSet()
        }

        private fun isCurrentPathReachable(): Boolean = controls.none { it.pathUnreachable }

        private fun pop(expected: ValType): ValType? {
            val actual = popAny()
            if (actual != null && !owner.isSubtype(actual, expected)) {
                invalidInstruction("expected $expected on the stack, found $actual")
            }
            return actual
        }

        private fun popAny(): ValType? {
            val frame = currentControl()
            if (operands.size == frame.height) {
                if (frame.unreachable) return null
                invalidInstruction("operand stack underflow")
            }
            return operands.removeAt(operands.lastIndex)
        }

        private fun popAll(types: List<ValType>) {
            for (index in types.indices.reversed()) pop(types[index])
        }

        private fun push(type: ValType?) {
            operands.add(type)
        }

        private fun pushAll(types: List<ValType>) {
            operands.addAll(types)
        }

        private fun applySignature(params: List<ValType>, results: List<ValType>) {
            popAll(params)
            pushAll(results)
        }

        private fun localType(index: Int, instructionIndex: Int, requireInitialized: Boolean): ValType {
            if (index < 0 || index >= locals.size) {
                invalidInstruction("unknown local index $index (size ${locals.size})", instructionIndex)
            }
            if (requireInitialized && isCurrentPathReachable() && index !in initializedLocals) {
                invalidInstruction("local.get reads uninitialized non-null local $index", instructionIndex)
            }
            return locals[index]
        }

        private fun initializeLocal(index: Int) {
            if (isCurrentPathReachable()) initializedLocals.add(index)
        }

        private fun isDefaultable(type: ValType): Boolean = owner.asReferenceType(type)?.nullable ?: true

        private fun memoryIndexType(index: Int, instructionIndex: Int): ValType {
            val memory = owner.memoryType(index, instructionLocation(instructionIndex))
            return owner.indexValueType(memory.limits.indexType)
        }

        private fun requireDataCount(instructionIndex: Int) {
            if (module.dataCount == null) {
                invalidInstruction("bulk-memory data instruction requires a data-count section", instructionIndex)
            }
        }

        private fun isFunctionReference(type: ValType): Boolean {
            val ref = owner.asReferenceType(type) ?: return false
            return owner.heapSubtype(ref.heap, HeapType.Func)
        }

        private fun minimumAddressType(left: ValType, right: ValType): ValType =
            if (left === ValType.I32 || right === ValType.I32) ValType.I32 else ValType.I64

        private fun resultTypesSubtype(actual: List<ValType>, expected: List<ValType>): Boolean =
            actual.size == expected.size && actual.indices.all { owner.isSubtype(actual[it], expected[it]) }

        private fun instructionLocation(index: Int): String = "function $functionIndex instruction $index"

        private fun invalidInstruction(message: String, index: Int = currentInstructionIndex): Nothing =
            throw InvalidModule(message, functionIndex, index)
    }

    private companion object {
        val WASM32_MAX_MEMORY_PAGES: ULong = 65_536uL

        fun saturatingAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

        fun collectRefFuncs(instructions: List<Instr>, destination: MutableSet<Int>) {
            instructions.forEach { instruction ->
                if (instruction is RefFunc) destination.add(instruction.funcIndex)
            }
        }
    }
}
