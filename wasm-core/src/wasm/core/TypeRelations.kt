package io.heapy.kwasm

/** Core GC/typed-reference subtyping over a validated module's type graph. */
internal fun Module.isHeapSubtype(actual: HeapType, expected: HeapType): Boolean {
    if (actual == expected) return true

    if (actual is HeapType.Index) {
        if (expected is HeapType.Index) {
            return hasDeclaredSupertype(actual.value, expected.value)
        }
        return when (types.getOrNull(actual.value)) {
            is FuncType -> expected === HeapType.Func
            is StructType ->
                expected === HeapType.Struct ||
                    expected === HeapType.Eq ||
                    expected === HeapType.Any
            is ArrayType ->
                expected === HeapType.Array ||
                    expected === HeapType.Eq ||
                    expected === HeapType.Any
            null -> false
        }
    }

    return when (actual) {
        HeapType.NoFunc ->
            expected === HeapType.Func ||
                expected is HeapType.Index && types.getOrNull(expected.value) is FuncType
        HeapType.NoExtern -> expected === HeapType.Extern
        HeapType.NoExn -> expected === HeapType.Exn
        HeapType.None ->
            expected === HeapType.I31 ||
                expected === HeapType.Struct ||
                expected === HeapType.Array ||
                expected === HeapType.Eq ||
                expected === HeapType.Any ||
                expected is HeapType.Index &&
                when (types.getOrNull(expected.value)) {
                    is StructType, is ArrayType -> true
                    else -> false
                }
        HeapType.I31 -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Struct -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Array -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Eq -> expected === HeapType.Any
        else -> false
    }
}

internal fun Module.isValueSubtype(actual: ValType, expected: ValType): Boolean {
    return valueTypeSubtypeAcross(this, actual, this, expected)
}

/**
 * Structural recursive-type equivalence across two module type-index spaces.
 *
 * Raw Kotlin data-class equality is insufficient here because an indexed
 * reference in one module may denote the same recursive shape at a different
 * numeric index in another module. The coinductive pair set also makes this
 * total for mutually recursive function/GC types.
 */
internal fun functionTypesEquivalent(
    leftModule: Module?,
    left: FuncType,
    rightModule: Module?,
    right: FuncType,
): Boolean = CrossModuleTypeEquivalence(leftModule, rightModule).function(left, right)

internal fun valueTypesEquivalent(
    leftModule: Module?,
    left: ValType,
    rightModule: Module?,
    right: ValType,
): Boolean = CrossModuleTypeEquivalence(leftModule, rightModule).valueType(left, right)

internal fun valueTypeSubtypeAcross(
    actualModule: Module?,
    actual: ValType,
    expectedModule: Module?,
    expected: ValType,
): Boolean {
    if (valueTypesEquivalent(actualModule, actual, expectedModule, expected)) return true
    val actualRef = normalizeRefType(actual) as? RefType ?: return false
    val expectedRef = normalizeRefType(expected) as? RefType ?: return false
    if (actualRef.nullable && !expectedRef.nullable) return false

    val actualHeap = actualRef.heap
    val expectedHeap = expectedRef.heap
    return when {
        actualHeap is HeapType.Index && expectedHeap is HeapType.Index -> {
            val actualTypes = actualModule?.types ?: return false
            val expectedTypes = expectedModule?.types ?: return false
            if (actualHeap.value !in actualTypes.indices || expectedHeap.value !in expectedTypes.indices) {
                return false
            }
            val pending = ArrayDeque<Int>()
            val visited = mutableSetOf<Int>()
            pending.addLast(actualHeap.value)
            while (pending.isNotEmpty()) {
                val index = pending.removeLast()
                if (!visited.add(index)) continue
                if (
                    CrossModuleTypeEquivalence(actualModule, expectedModule)
                        .indexedType(index, expectedHeap.value)
                ) {
                    return true
                }
                actualTypes[index].supertypes.forEach { supertype ->
                    if (supertype in actualTypes.indices) pending.addLast(supertype)
                }
            }
            false
        }
        actualHeap is HeapType.Index -> {
            val module = actualModule ?: return false
            module.isHeapSubtype(actualHeap, expectedHeap)
        }
        expectedHeap is HeapType.Index -> {
            val module = expectedModule ?: return false
            module.isHeapSubtype(actualHeap, expectedHeap)
        }
        else -> isAbstractHeapSubtype(actualHeap, expectedHeap)
    }
}

private class CrossModuleTypeEquivalence(
    private val leftModule: Module?,
    private val rightModule: Module?,
) {
    private val compared: MutableSet<Pair<Int, Int>> = mutableSetOf()

    fun function(left: FuncType, right: FuncType): Boolean =
        definitions(left, right)

    fun valueType(left: ValType, right: ValType): Boolean = value(left, right)

    fun indexedType(leftIndex: Int, rightIndex: Int): Boolean =
        indexed(leftIndex, rightIndex)

    private fun definitions(left: DefinedType, right: DefinedType): Boolean {
        if (left.isFinal != right.isFinal || left.supertypes.size != right.supertypes.size) {
            return false
        }
        if (!left.supertypes.indices.all { index ->
                indexed(left.supertypes[index], right.supertypes[index])
            }
        ) {
            return false
        }
        return when {
            left is FuncType && right is FuncType ->
                valueLists(left.params, right.params) &&
                    valueLists(left.results, right.results)
            left is StructType && right is StructType ->
                left.fields.size == right.fields.size &&
                    left.fields.indices.all { field(left.fields[it], right.fields[it]) }
            left is ArrayType && right is ArrayType -> field(left.field, right.field)
            else -> false
        }
    }

    private fun field(left: FieldType, right: FieldType): Boolean =
        left.mutability == right.mutability && storage(left.storage, right.storage)

    private fun storage(left: StorageType, right: StorageType): Boolean =
        when {
            left === StorageType.I8 && right === StorageType.I8 -> true
            left === StorageType.I16 && right === StorageType.I16 -> true
            left is StorageType.Value && right is StorageType.Value ->
                value(left.type, right.type)
            else -> false
        }

    private fun valueLists(left: List<ValType>, right: List<ValType>): Boolean =
        left.size == right.size && left.indices.all { value(left[it], right[it]) }

    private fun value(left: ValType, right: ValType): Boolean {
        val normalizedLeft = normalizeRefType(left)
        val normalizedRight = normalizeRefType(right)
        if (normalizedLeft !is RefType || normalizedRight !is RefType) {
            return normalizedLeft == normalizedRight
        }
        if (normalizedLeft.nullable != normalizedRight.nullable) return false
        return heap(normalizedLeft.heap, normalizedRight.heap)
    }

    private fun heap(left: HeapType, right: HeapType): Boolean =
        if (left is HeapType.Index && right is HeapType.Index) {
            indexed(left.value, right.value)
        } else {
            left == right
        }

    private fun indexed(leftIndex: Int, rightIndex: Int): Boolean {
        val leftTypes = leftModule?.types ?: return false
        val rightTypes = rightModule?.types ?: return false
        if (leftIndex !in leftTypes.indices || rightIndex !in rightTypes.indices) return false
        val pair = leftIndex to rightIndex
        if (!compared.add(pair)) return true
        return definitions(leftTypes[leftIndex], rightTypes[rightIndex])
    }
}

internal fun normalizeRefType(type: ValType): ValType = when (type) {
    is ValType.Ref -> RefType(type.heap, type.nullable)
    else -> type
}

private data class RuntimeHeapType(
    val heap: HeapType,
    /** Module whose unified type index space [heap] belongs to, if it is indexed. */
    val module: Module? = null,
)

private fun describeRuntimeHeapType(value: Value.Ref): RuntimeHeapType? = when (value) {
    is Value.Ref.Func ->
        if (value.index < 0) {
            RuntimeHeapType(HeapType.NoFunc)
        } else {
            val owner = value.owner ?: return RuntimeHeapType(HeapType.Func)
            if (value.index >= owner.functionCount) return null
            RuntimeHeapType(
                HeapType.Index(owner.module.functionTypeIndex(value.index)),
                owner.module,
            )
        }
    is Value.Ref.Extern ->
        RuntimeHeapType(if (value.handle < 0) HeapType.NoExtern else HeapType.Extern)
    is Value.Ref.Host ->
        RuntimeHeapType(if (value.value == null) HeapType.NoExtern else HeapType.Extern)
    is Value.Ref.I31 -> RuntimeHeapType(HeapType.I31)
    is Value.Ref.Gc -> {
        val objectValue = value.value ?: return RuntimeHeapType(HeapType.None)
        val owner = objectValue.owner ?: return null
        val definition = owner.module.types.getOrNull(objectValue.typeIndex) ?: return null
        val representationMatches = when (definition) {
            is StructType -> objectValue is StructObject
            is ArrayType -> objectValue is ArrayObject
            is FuncType -> false
        }
        if (!representationMatches) return null
        RuntimeHeapType(HeapType.Index(objectValue.typeIndex), owner.module)
    }
    is Value.Ref.Exn ->
        RuntimeHeapType(if (value.value == null) HeapType.NoExn else HeapType.Exn)
    is Value.Ref.AnyExtern -> RuntimeHeapType(HeapType.Any)
}

/**
 * Checks a runtime reference against a table, global, or instruction reference type.
 *
 * Indexed heap types are meaningful only in their defining module's type index
 * space. Consequently, a concrete runtime reference is compared nominally only
 * when its owner is [typeContext]. Abstract heap types remain portable between
 * instances and are checked against the reference's defining module.
 */
internal fun Value.Ref.matchesReference(expected: RefType, typeContext: Module?): Boolean {
    if (isNullRef() && !expected.nullable) return false
    val actual = describeRuntimeHeapType(this) ?: return false

    return when {
        expected.heap is HeapType.Index -> {
            val context = typeContext ?: return false
            when {
                actual.heap is HeapType.Index ->
                    valueTypeSubtypeAcross(
                        actualModule = actual.module,
                        actual = RefType(actual.heap, nullable = false),
                        expectedModule = context,
                        expected = RefType(expected.heap, nullable = false),
                    )
                else -> context.isHeapSubtype(actual.heap, expected.heap)
            }
        }
        actual.heap is HeapType.Index -> {
            val owner = actual.module ?: return false
            owner.isHeapSubtype(actual.heap, expected.heap)
        }
        else -> isAbstractHeapSubtype(actual.heap, expected.heap)
    }
}

internal fun Module.runtimeHeapType(value: Value.Ref): HeapType? =
    describeRuntimeHeapType(value)?.heap

internal fun Module.referenceMatches(value: Value.Ref, expected: RefType): Boolean =
    value.matchesReference(expected, this)

private fun isAbstractHeapSubtype(actual: HeapType, expected: HeapType): Boolean {
    if (actual == expected) return true
    return when (actual) {
        HeapType.NoFunc -> expected === HeapType.Func
        HeapType.NoExtern -> expected === HeapType.Extern
        HeapType.NoExn -> expected === HeapType.Exn
        HeapType.None ->
            expected === HeapType.I31 ||
                expected === HeapType.Struct ||
                expected === HeapType.Array ||
                expected === HeapType.Eq ||
                expected === HeapType.Any
        HeapType.I31 -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Struct -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Array -> expected === HeapType.Eq || expected === HeapType.Any
        HeapType.Eq -> expected === HeapType.Any
        is HeapType.Index -> false
        else -> false
    }
}

private fun Module.hasDeclaredSupertype(actual: Int, expected: Int): Boolean {
    if (actual == expected) return true
    if (actual !in types.indices || expected !in types.indices) return false
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque<Int>()
    pending.addLast(actual)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!seen.add(current)) continue
        val supertypes = types[current].supertypes
        if (expected in supertypes) return true
        supertypes.forEach {
            if (it in types.indices) pending.addLast(it)
        }
    }
    return false
}
