package io.heapy.kwasm

/**
 * Runtime value. WebAssembly is a stack machine of these typed values.
 *
 * References carry a host-side handle: a function reference stores the absolute
 * function index, an extern reference stores an opaque token (Int).
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class Value {
    public data class I32(public val v: Int) : Value() {
        override fun toString(): String = "i32:${v}"
    }
    public data class I64(public val v: Long) : Value() {
        override fun toString(): String = "i64:${v}"
    }
    public data class F32(public val v: Float) : Value() {
        override fun toString(): String = "f32:${v}"
    }
    public data class F64(public val v: Double) : Value() {
        override fun toString(): String = "f64:${v}"
    }
    public data class V128(public val bytes: ByteArray) : Value() {
        override fun equals(other: Any?): Boolean =
            other is V128 && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "v128"
    }
    public sealed class Ref : Value() {
        public data class Func(
            public val index: Int,
            /** Defining instance; null is used for the null sentinel and restored local refs. */
            public val owner: Instance? = null,
        ) : Ref() {
            override fun toString(): String = if (index < 0) "ref.null" else "ref.func#$index"
        }
        public data class Extern(public val handle: Int) : Ref() {
            override fun toString(): String = "ref.extern#$handle"
        }
        /** Arbitrary host value carried by an externref. Null denotes ref.null extern. */
        public class Host(public val value: Any?) : Ref() {
            override fun equals(other: Any?): Boolean =
                other is Host && (value === other.value || value == other.value)
            override fun hashCode(): Int = value?.hashCode() ?: 0
            override fun toString(): String = if (value == null) "ref.null extern" else "externref"
        }
        /** Unboxed signed 31-bit reference. */
        public data class I31(public val value: Int) : Ref()
        /** A nullable reference to a guest struct or array object. */
        public data class Gc(public val value: GcObject?) : Ref()
        /** A nullable standardized exception reference. */
        public data class Exn(public val value: GuestException?) : Ref()
        /** Identity-preserving result of any.convert_extern. */
        public data class AnyExtern(public val external: Ref) : Ref()
    }

    public companion object {
        public val NULL_FUNC: Ref.Func = Ref.Func(-1)
        public val NULL_EXTERN: Ref.Extern = Ref.Extern(-1)
        public val NULL_GC: Ref.Gc = Ref.Gc(null)
        public val NULL_EXN: Ref.Exn = Ref.Exn(null)
    }
}

/** Host-heap representation of guest GC objects; identity is reference identity. */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class GcObject(
    owner: Instance?,
    public val typeIndex: Int,
) {
    /**
     * Defining instance. Snapshot decoding creates detached objects and the
     * store binds them to the target instance before validating or installing
     * any restored state.
     */
    public var owner: Instance? = owner
        internal set
}

@io.heapy.kwasm.ExperimentalKwasmApi
public class StructObject(
    owner: Instance?,
    typeIndex: Int,
    public val fields: MutableList<Value>,
) : GcObject(owner, typeIndex)

@io.heapy.kwasm.ExperimentalKwasmApi
public class ArrayObject(
    owner: Instance?,
    typeIndex: Int,
    public val elements: MutableList<Value>,
) : GcObject(owner, typeIndex)

/** Guest exception payload coupled to its nominal cross-module tag identity. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class GuestException(
    tag: TagInstance,
    public val arguments: List<Value>,
    /**
     * Index in the owning instance's tag index space when known. Runtime
     * throws and snapshot decoding populate this so nominal identity can be
     * restored without serializing a host object.
     */
    public val tagIndex: Int? = null,
) {
    public var tag: TagInstance = tag
        internal set
}

@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.asI32(): Int = (this as Value.I32).v
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.asI64(): Long = (this as Value.I64).v
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.asF32(): Float = (this as Value.F32).v
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.asF64(): Double = (this as Value.F64).v
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.asFuncRef(): Value.Ref.Func = this as Value.Ref.Func
@io.heapy.kwasm.ExperimentalKwasmApi
public fun Value.isNullRef(): Boolean =
    when (this) {
        is Value.Ref.Func -> index < 0
        is Value.Ref.Extern -> handle < 0
        is Value.Ref.Host -> value == null
        is Value.Ref.Gc -> value == null
        is Value.Ref.Exn -> value == null
        is Value.Ref.I31 -> false
        is Value.Ref.AnyExtern -> false
        else -> false
    }
