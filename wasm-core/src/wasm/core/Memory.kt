package io.heapy.kwasm

/**
 * A linear memory instance. Backed by a growable [ByteArray].
 *
 * WebAssembly linear memory is byte-addressable, little-endian, and grows in
 * 64KiB pages. [pageSize] is fixed by the spec.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class MemoryInstance(initial: MemoryType) {
    private val minimumPages: ULong = initial.limits.min
    public val indexType: IndexType = initial.limits.indexType
    public var sizeInPages: ULong = initial.limits.min
        private set
    public var maxPages: ULong? = initial.limits.max
        private set
    private var bytes: ByteArray =
        try {
            ByteArray(initialByteSize(sizeInPages))
        } catch (failure: Error) {
            throw InstantiationFailure(
                "memory minimum $sizeInPages pages cannot be allocated by this backend",
                failure,
            )
        }

    public val byteSize: Int get() = bytes.size

    public fun data(): ByteArray = bytes

    /** Grow by [delta] pages. Returns the old page count, or -1 on failure. */
    public fun grow(delta: Int): Int {
        if (delta < 0) return -1
        val old = sizeInPages
        val newPageCount = old + delta.toULong()
        val max = maxPages ?: DEFAULT_MAX_PAGES
        if (
            newPageCount > max ||
            newPageCount > HARD_MAX_PAGES ||
            newPageCount > (Int.MAX_VALUE / PAGE_SIZE).toULong()
        ) {
            return -1
        }
        val newBytes =
            try {
                ByteArray((newPageCount * PAGE_SIZE.toULong()).toInt())
            } catch (_: Error) {
                // `memory.grow` reports allocation failure to the guest; a
                // platform allocation error must not escape as the
                // instruction's semantics.
                return -1
            }
        bytes.copyInto(newBytes, 0, 0, bytes.size)
        bytes = newBytes
        sizeInPages = newPageCount
        return old.toInt()
    }

    public fun checkRange(addr: Long, len: Int) {
        if (len < 0 || addr < 0 || addr > bytes.size.toLong() - len.toLong()) {
            throw Trap.oobMemory(addr, len)
        }
    }

    public fun load(addr: Long, len: Int): ByteArray {
        checkRange(addr, len)
        val a = addr.toInt()
        return bytes.copyOfRange(a, a + len)
    }

    public fun store(addr: Long, data: ByteArray) {
        checkRange(addr, data.size)
        val a = addr.toInt()
        data.copyInto(bytes, a, 0, data.size)
    }

    public fun loadByte(addr: Long): Int {
        checkRange(addr, 1); return bytes[addr.toInt()].toInt() and 0xFF
    }

    public fun storeByte(addr: Long, v: Int) {
        checkRange(addr, 1); bytes[addr.toInt()] = v.toByte()
    }

    internal fun validateSnapshotBytes(snapshotBytes: ByteArray) {
        if (snapshotBytes.size % PAGE_SIZE != 0) {
            throw SnapshotStateException(
                "memory byte length ${snapshotBytes.size} is not a multiple of the $PAGE_SIZE-byte page size",
            )
        }
        val pages = (snapshotBytes.size / PAGE_SIZE).toULong()
        val maximum = maxPages ?: DEFAULT_MAX_PAGES
        if (pages < minimumPages || pages > maximum || pages > HARD_MAX_PAGES) {
            throw SnapshotStateException(
                "memory snapshot has $pages pages; permitted range is " +
                    "$minimumPages..${minOf(maximum, HARD_MAX_PAGES)}",
            )
        }
    }

    internal fun restoreSnapshotBytes(snapshotBytes: ByteArray) {
        validateSnapshotBytes(snapshotBytes)
        bytes = snapshotBytes.copyOf()
        sizeInPages = (bytes.size / PAGE_SIZE).toULong()
    }

    public companion object {
        public const val PAGE_SIZE: Int = 65536
        public val DEFAULT_MAX_PAGES: ULong = 65536uL
        public val HARD_MAX_PAGES: ULong = 65536uL
    }
}

/** A table instance holding references from one WebAssembly reference hierarchy. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class TableInstance(
    initial: TableType,
    internal val typeContext: Module? = null,
) {
    private val minimum: Int = checkedTableSize(initial.limits.min)
    public val indexType: IndexType = initial.limits.indexType
    public val elementType: RefType = initial.element
    private var entries: Array<Value.Ref> =
        try {
            Array(minimum) { initialNullReference(elementType, typeContext) }
        } catch (failure: Error) {
            throw InstantiationFailure(
                "table minimum $minimum elements cannot be allocated by this backend",
                failure,
            )
        }
    /**
     * A defensive view retained for callers that need to inspect a whole table.
     * Mutating the returned array cannot bypass [set]'s element-type check.
     */
    public val refs: Array<Value.Ref>
        get() = entries.copyOf()
    public var size: Int = minimum
        private set
    public val max: Int? = initial.limits.max?.let { maximum ->
        minOf(maximum, Int.MAX_VALUE.toULong()).toInt()
    }

    init {
        require(elementType.heap !is HeapType.Index || typeContext != null) {
            "a table with concrete element type $elementType requires its defining module"
        }
    }

    public fun get(i: Int): Value.Ref {
        if (i < 0 || i >= size) throw Trap.oobTable(i, size)
        return entries[i]
    }

    public fun set(i: Int, v: Value.Ref) {
        if (i < 0 || i >= size) throw Trap.oobTable(i, size)
        requireReferenceType(v)
        entries[i] = v
    }

    /** Grow by [delta], filling new slots with [value]. Returns old size or -1. */
    public fun grow(delta: Int, value: Value.Ref): Int {
        if (delta < 0 || delta > Int.MAX_VALUE - size) return -1
        requireReferenceType(value)
        val newSize = size + delta
        val max = max
        if (max != null && newSize > max) return -1
        if (newSize < 0) return -1
        val newRefs =
            try {
                arrayOfNulls<Value.Ref>(newSize)
            } catch (_: Error) {
                // Like memory.grow, table.grow returns -1 on allocation
                // failure and leaves the existing table unchanged.
                return -1
            }
        for (i in 0 until size) newRefs[i] = entries[i]
        for (i in size until newSize) newRefs[i] = value
        @Suppress("UNCHECKED_CAST")
        entries = newRefs as Array<Value.Ref>
        size = newSize
        return size - delta
    }

    internal fun validateSnapshotRefs(snapshotRefs: List<Value.Ref>) {
        if (snapshotRefs.size < minimum || (max != null && snapshotRefs.size > max)) {
            throw SnapshotStateException(
                "table snapshot has ${snapshotRefs.size} elements; permitted range is " +
                    "$minimum..${max ?: Int.MAX_VALUE}",
            )
        }
        snapshotRefs.forEachIndexed { index, value ->
            if (!accepts(value)) {
                throw SnapshotStateException(
                    "table snapshot element $index has the wrong reference type for $elementType",
                )
            }
        }
    }

    internal fun restoreSnapshotRefs(snapshotRefs: List<Value.Ref>) {
        validateSnapshotRefs(snapshotRefs)
        entries = snapshotRefs.map(::copySnapshotRef).toTypedArray()
        size = entries.size
    }

    private fun accepts(value: Value.Ref): Boolean =
        value.matchesReference(elementType, typeContext)

    private fun requireReferenceType(value: Value.Ref) {
        require(accepts(value)) {
            "reference $value does not match table element type $elementType"
        }
    }
}

/** A global instance with mutable/immutable value storage. */
@io.heapy.kwasm.ExperimentalKwasmApi
public class GlobalInstance(
    public val type: GlobalType,
    internal val typeContext: Module? = null,
) {
    public var value: Value = Value.I32(0)
        private set

    public fun set(v: Value) {
        require(type.mutability == Mutability.VAR) { "global is immutable" }
        value = v
    }

    public fun init(v: Value) { value = v }
}

private fun copySnapshotRef(value: Value.Ref): Value.Ref = when (value) {
    is Value.Ref.Func -> value.copy()
    is Value.Ref.Extern -> value.copy()
    is Value.Ref.Host -> Value.Ref.Host(value.value)
    is Value.Ref.I31 -> value.copy()
    is Value.Ref.Gc -> value.copy()
    is Value.Ref.Exn -> value.copy()
    is Value.Ref.AnyExtern -> value.copy()
}

private fun initialByteSize(pages: ULong): Int {
    if (pages > (Int.MAX_VALUE / MemoryInstance.PAGE_SIZE).toULong()) {
        throw InstantiationFailure(
            "memory minimum $pages pages exceeds the contiguous backend limit; " +
                "this runtime backend supports at most ${Int.MAX_VALUE} contiguous bytes",
        )
    }
    return (pages * MemoryInstance.PAGE_SIZE.toULong()).toInt()
}

private fun checkedTableSize(elements: ULong): Int {
    if (elements > Int.MAX_VALUE.toULong()) {
        throw InstantiationFailure(
            "table minimum $elements exceeds this runtime backend's ${Int.MAX_VALUE}-element limit",
        )
    }
    return elements.toInt()
}

private fun initialNullReference(type: RefType, module: Module?): Value.Ref = when (type.heap) {
    HeapType.Func, HeapType.NoFunc -> Value.NULL_FUNC
    HeapType.Extern, HeapType.NoExtern -> Value.NULL_EXTERN
    HeapType.Exn, HeapType.NoExn -> Value.NULL_EXN
    is HeapType.Index ->
        when (module?.types?.getOrNull(type.heap.value)) {
            is FuncType -> Value.NULL_FUNC
            else -> Value.NULL_GC
        }
    else -> Value.NULL_GC
}
