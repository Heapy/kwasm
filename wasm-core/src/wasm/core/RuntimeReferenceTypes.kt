package io.heapy.kwasm

import io.heapy.kwasm.binary.ArrayType
import io.heapy.kwasm.binary.FuncType
import io.heapy.kwasm.binary.HeapType
import io.heapy.kwasm.binary.Module
import io.heapy.kwasm.binary.RefType
import io.heapy.kwasm.binary.StructType
import io.heapy.kwasm.binary.isAbstractHeapSubtype
import io.heapy.kwasm.binary.isHeapSubtype
import io.heapy.kwasm.binary.valueTypeSubtypeAcross

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
