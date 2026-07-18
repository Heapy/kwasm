package io.heapy.kwasm

/**
 * WebAssembly value types (binary encoding in brackets).
 *
 * Covers the scalar numeric types, the reference types, and the packed/SIMD
 * types recognized by the current specification.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class ValType {
    public object I32 : ValType() { override fun toString(): String = "i32" }
    public object I64 : ValType() { override fun toString(): String = "i64" }
    public object F32 : ValType() { override fun toString(): String = "f32" }
    public object F64 : ValType() { override fun toString(): String = "f64" }
    public object V128 : ValType() { override fun toString(): String = "v128" }

    /** Abstract reference type shared by the reference-types proposal. */
    public sealed class Ref : ValType() {
        public abstract val nullable: Boolean
        public abstract val heap: HeapType
        public object FuncRef : Ref() {
            override val nullable: Boolean get() = true
            override val heap: HeapType get() = HeapType.Func
            override fun toString(): String = "funcref"
        }
        public object ExternRef : Ref() {
            override val nullable: Boolean get() = true
            override val heap: HeapType get() = HeapType.Extern
            override fun toString(): String = "externref"
        }
    }
}

/**
 * Heap type for the reference-types / function-references / GC proposals.
 * Only the abstract forms (func, extern) and concrete index forms are used here.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class HeapType {
    /** Top of the internal-GC reference hierarchy. */
    public object Any : HeapType() { override fun toString(): String = "any" }
    /** Bottom of the internal-GC reference hierarchy. */
    public object None : HeapType() { override fun toString(): String = "none" }
    public object Eq : HeapType() { override fun toString(): String = "eq" }
    public object I31 : HeapType() { override fun toString(): String = "i31" }
    public object Struct : HeapType() { override fun toString(): String = "struct" }
    public object Array : HeapType() { override fun toString(): String = "array" }
    public object Func : HeapType() { override fun toString(): String = "func" }
    public object NoFunc : HeapType() { override fun toString(): String = "nofunc" }
    public object Extern : HeapType() { override fun toString(): String = "extern" }
    public object NoExtern : HeapType() { override fun toString(): String = "noextern" }
    public object Exn : HeapType() { override fun toString(): String = "exn" }
    public object NoExn : HeapType() { override fun toString(): String = "noexn" }
    public data class Index(val value: Int) : HeapType() { override fun toString(): String = "type[$value]" }
}

/** A (possibly nullable) reference type with an explicit heap type. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class RefType(val heap: HeapType, val nullable: Boolean) : ValType() {
    override fun toString(): String = "(ref${if (nullable) " null" else ""} $heap)"
}

/**
 * Default-null reference abbreviation matching the binary encoding:
 * - funcref  = (ref null func)
 * - externref = (ref null extern)
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public val FuncRefType: RefType = RefType(HeapType.Func, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val ExternRefType: RefType = RefType(HeapType.Extern, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val AnyRefType: RefType = RefType(HeapType.Any, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val EqRefType: RefType = RefType(HeapType.Eq, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val I31RefType: RefType = RefType(HeapType.I31, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val StructRefType: RefType = RefType(HeapType.Struct, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val ArrayRefType: RefType = RefType(HeapType.Array, nullable = true)
@io.heapy.kwasm.ExperimentalKwasmApi
public val ExnRefType: RefType = RefType(HeapType.Exn, nullable = true)

/**
 * A definition in the unified WebAssembly type index space.
 *
 * Finality and declared supertypes live on the definition so flattening
 * recursive groups does not lose the binary type indices used by instructions.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed interface DefinedType {
    public val isFinal: Boolean
    public val supertypes: List<Int>
}

/** Function type: a sequence of parameter and result types. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class FuncType(
    public val params: List<ValType>,
    public val results: List<ValType>,
    override val isFinal: Boolean = true,
    override val supertypes: List<Int> = emptyList(),
) : DefinedType {
    override fun toString(): String =
        "(func${params.joinToString("") { " (param $it)" }}${results.joinToString("") { " (result $it)" }})"
}

/** GC packed field storage types. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class StorageType {
    public data class Value(public val type: ValType) : StorageType()
    public object I8 : StorageType() { override fun toString(): String = "i8" }
    public object I16 : StorageType() { override fun toString(): String = "i16" }
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class FieldType(
    public val storage: StorageType,
    public val mutability: Mutability,
)

@io.heapy.kwasm.ExperimentalKwasmApi
public data class StructType(
    public val fields: List<FieldType>,
    override val isFinal: Boolean = true,
    override val supertypes: List<Int> = emptyList(),
) : DefinedType

@io.heapy.kwasm.ExperimentalKwasmApi
public data class ArrayType(
    public val field: FieldType,
    override val isFinal: Boolean = true,
    override val supertypes: List<Int> = emptyList(),
) : DefinedType

/** A contiguous group of mutually recursive definitions in [Module.types]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class RecursionGroup(
    public val startTypeIndex: Int,
    public val size: Int,
) {
    public val indices: IntRange
        get() = startTypeIndex until startTypeIndex + size
}

/** Memory/page limits. [max] is null for unbounded. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class Limits(
    public val min: ULong,
    public val max: ULong?,
    public val shared: Boolean = false,
    public val indexType: IndexType = IndexType.I32,
) {
    public constructor(
        min: UInt,
        max: UInt?,
        shared: Boolean = false,
        indexType: IndexType = IndexType.I32,
    ) : this(min.toULong(), max?.toULong(), shared, indexType)
}

/** Index width for memory64 / table64. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class IndexType { I32, I64 }

/** Table element type + limits. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class TableType(
    public val element: RefType,
    public val limits: Limits,
) {
    override fun toString(): String = "(table $limits $element)"
}

/** Memory type is just limits in the current model. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class MemoryType(public val limits: Limits) {
    override fun toString(): String = "(memory $limits)"
}

/** Mutability flag for globals. */
@io.heapy.kwasm.ExperimentalKwasmApi
public enum class Mutability { CONST, VAR }
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Mutability.isVar(): Boolean = this == Mutability.VAR

/** Global type: value type + mutability. */
@io.heapy.kwasm.ExperimentalKwasmApi
public data class GlobalType(
    public val type: ValType,
    public val mutability: Mutability,
) {
    override fun toString(): String = "(global ${if (mutability.isVar()) "(mut " else ""}$type${if (mutability.isVar()) ")" else ""})"
}
