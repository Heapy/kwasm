package io.heapy.kwasm

/**
 * Flat array-backed LIFO stack for interpreter frames and their reuse pools.
 *
 * The guest frame stack, the per-frame control stack, and both reuse pools are
 * pure push/pop structures. [ArrayDeque] pays for circular index arithmetic and
 * modification counting on every operation, which is measurable on call-heavy
 * guests; a flat array keeps each push and pop to a bounds check and one store.
 */
internal class RuntimeObjectStack<T : Any>(initialCapacity: Int = 8) {
    private var elements: Array<Any?> = arrayOfNulls(initialCapacity)

    var size: Int = 0
        private set

    val lastIndex: Int
        get() = size - 1

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size != 0

    operator fun get(index: Int): T {
        checkIndex(index)
        return elementAt(index)
    }

    fun firstOrNull(): T? = if (size == 0) null else elementAt(0)

    fun last(): T = elementAt(checkedLastIndex())

    fun lastOrNull(): T? = if (size == 0) null else elementAt(size - 1)

    fun addLast(element: T) {
        ensureCapacity(size + 1)
        elements[size] = element
        size++
    }

    fun removeLast(): T {
        val index = checkedLastIndex()
        val element = elementAt(index)
        elements[index] = null
        size = index
        return element
    }

    fun removeLastOrNull(): T? = if (size == 0) null else removeLast()

    fun clear() {
        for (index in 0 until size) {
            elements[index] = null
        }
        size = 0
    }

    @Suppress("UNCHECKED_CAST")
    private fun elementAt(index: Int): T = elements[index] as T

    private fun checkedLastIndex(): Int {
        check(size > 0) { "object stack is empty" }
        return size - 1
    }

    private fun checkIndex(index: Int) {
        check(index in 0 until size) {
            "object stack index $index is outside 0 until $size"
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= elements.size) return
        elements = elements.copyOf(maxOf(required, elements.size.coerceAtLeast(1) * 2))
    }
}
