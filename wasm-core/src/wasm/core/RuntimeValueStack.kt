package io.heapy.kwasm

/**
 * Interpreter value stack with unboxed numeric slots.
 *
 * Numeric [Value] wrappers remain the public boundary representation, but
 * retaining one heap object per intermediate Wasm value makes numeric loops
 * allocation-bound. This stack stores raw bits plus a compact tag and boxes
 * only when a value crosses a public or snapshot boundary.
 */
internal class RuntimeValueStack(initialCapacity: Int = 32) {
    private var tags = ByteArray(initialCapacity)
    private var bits = LongArray(initialCapacity)
    private var objects = arrayOfNulls<Value>(initialCapacity)

    var size: Int = 0
        private set

    val lastIndex: Int
        get() = size - 1

    fun isEmpty(): Boolean = size == 0

    fun addLast(value: Value) {
        ensureCapacity(size + 1)
        store(size, value)
        size++
    }

    fun addLastI32(value: Int) {
        ensureCapacity(size + 1)
        tags[size] = I32
        bits[size] = value.toLong()
        objects[size] = null
        size++
    }

    fun addLastI64(value: Long) {
        ensureCapacity(size + 1)
        tags[size] = I64
        bits[size] = value
        objects[size] = null
        size++
    }

    fun addLastF32(value: Float) {
        ensureCapacity(size + 1)
        tags[size] = F32
        bits[size] = value.toRawBits().toLong()
        objects[size] = null
        size++
    }

    fun addLastF64(value: Double) {
        ensureCapacity(size + 1)
        tags[size] = F64
        bits[size] = value.toRawBits()
        objects[size] = null
        size++
    }

    fun removeLast(): Value {
        val index = checkedLastIndex()
        val value = valueAt(index)
        objects[index] = null
        size = index
        return value
    }

    fun removeLastI32(): Int {
        val index = checkedLastIndex()
        check(tags[index] == I32) { "value stack top is not i32" }
        size = index
        return bits[index].toInt()
    }

    fun removeLastI64(): Long {
        val index = checkedLastIndex()
        check(tags[index] == I64) { "value stack top is not i64" }
        size = index
        return bits[index]
    }

    fun removeLastF32(): Float {
        val index = checkedLastIndex()
        check(tags[index] == F32) { "value stack top is not f32" }
        size = index
        return Float.fromBits(bits[index].toInt())
    }

    fun removeLastF64(): Double {
        val index = checkedLastIndex()
        check(tags[index] == F64) { "value stack top is not f64" }
        size = index
        return Double.fromBits(bits[index])
    }

    fun last(): Value = valueAt(checkedLastIndex())

    operator fun get(index: Int): Value {
        checkIndex(index)
        return valueAt(index)
    }

    fun getI32(index: Int): Int {
        checkIndex(index)
        check(tags[index] == I32) { "value stack slot $index is not i32" }
        return bits[index].toInt()
    }

    fun setI32(index: Int, value: Int) {
        checkIndex(index)
        tags[index] = I32
        bits[index] = value.toLong()
        objects[index] = null
    }

    operator fun set(index: Int, value: Value) {
        checkIndex(index)
        store(index, value)
    }

    fun copyTo(index: Int, target: RuntimeValueStack) {
        checkIndex(index)
        target.ensureCapacity(target.size + 1)
        copySlot(index, target, target.size)
        target.size++
    }

    fun copyTo(index: Int, target: RuntimeValueStack, targetIndex: Int) {
        checkIndex(index)
        target.checkIndex(targetIndex)
        copySlot(index, target, targetIndex)
    }

    fun moveLastTo(target: RuntimeValueStack, targetIndex: Int) {
        val sourceIndex = checkedLastIndex()
        target.checkIndex(targetIndex)
        copySlot(sourceIndex, target, targetIndex)
        objects[sourceIndex] = null
        size = sourceIndex
    }

    fun moveTopTo(target: RuntimeValueStack, count: Int) {
        require(count >= 0) { "count must be non-negative" }
        check(size >= count) {
            "value stack underflow: need $count values, have $size"
        }
        if (count == 0) return
        val sourceStart = size - count
        target.ensureCapacity(target.size + count)
        repeat(count) { offset ->
            copySlot(sourceStart + offset, target, target.size + offset)
        }
        target.size += count
        clearObjects(sourceStart, size)
        size = sourceStart
    }

    fun retainTopAt(base: Int, count: Int) {
        require(count >= 0) { "count must be non-negative" }
        check(base in 0..size) {
            "value stack base $base is outside 0..$size"
        }
        check(size - base >= count) {
            "value stack underflow: need $count values above base $base, have ${size - base}"
        }
        val oldSize = size
        val sourceStart = oldSize - count
        repeat(count) { offset ->
            copySlot(sourceStart + offset, this, base + offset)
        }
        val newSize = base + count
        clearObjects(newSize, oldSize)
        size = newSize
    }

    fun truncate(newSize: Int) {
        check(newSize in 0..size) {
            "value stack height $newSize is outside 0..$size"
        }
        clearObjects(newSize, size)
        size = newSize
    }

    fun clear() {
        clearObjects(0, size)
        size = 0
    }

    fun toList(): List<Value> = List(size, ::valueAt)

    fun toList(fromIndex: Int, count: Int): List<Value> {
        require(count >= 0) { "count must be non-negative" }
        check(fromIndex >= 0 && fromIndex + count <= size) {
            "value stack range $fromIndex until ${fromIndex + count} is outside 0 until $size"
        }
        return List(count) { offset -> valueAt(fromIndex + offset) }
    }

    private fun store(index: Int, value: Value) {
        when (value) {
            is Value.I32 -> {
                tags[index] = I32
                bits[index] = value.v.toLong()
                objects[index] = null
            }
            is Value.I64 -> {
                tags[index] = I64
                bits[index] = value.v
                objects[index] = null
            }
            is Value.F32 -> {
                tags[index] = F32
                bits[index] = value.v.toRawBits().toLong()
                objects[index] = null
            }
            is Value.F64 -> {
                tags[index] = F64
                bits[index] = value.v.toRawBits()
                objects[index] = null
            }
            else -> {
                tags[index] = OBJECT
                bits[index] = 0
                objects[index] = value
            }
        }
    }

    private fun valueAt(index: Int): Value = when (tags[index]) {
        I32 -> Value.I32(bits[index].toInt())
        I64 -> Value.I64(bits[index])
        F32 -> Value.F32(Float.fromBits(bits[index].toInt()))
        F64 -> Value.F64(Double.fromBits(bits[index]))
        OBJECT -> checkNotNull(objects[index]) { "object value stack slot is empty" }
        else -> error("unknown value stack tag ${tags[index]}")
    }

    private fun copySlot(
        sourceIndex: Int,
        target: RuntimeValueStack,
        targetIndex: Int,
    ) {
        target.tags[targetIndex] = tags[sourceIndex]
        target.bits[targetIndex] = bits[sourceIndex]
        target.objects[targetIndex] = objects[sourceIndex]
    }

    private fun clearObjects(fromIndex: Int, toIndex: Int) {
        for (index in fromIndex until toIndex) objects[index] = null
    }

    private fun checkedLastIndex(): Int {
        check(size > 0) { "value stack is empty" }
        return size - 1
    }

    private fun checkIndex(index: Int) {
        check(index in 0 until size) {
            "value stack index $index is outside 0 until $size"
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= tags.size) return
        val capacity = maxOf(required, tags.size.coerceAtLeast(1) * 2)
        tags = tags.copyOf(capacity)
        bits = bits.copyOf(capacity)
        objects = objects.copyOf(capacity)
    }

    private companion object {
        const val I32: Byte = 1
        const val I64: Byte = 2
        const val F32: Byte = 3
        const val F64: Byte = 4
        const val OBJECT: Byte = 5
    }
}
