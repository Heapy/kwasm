package io.heapy.kwasm

import kotlin.math.*

// ---- value stack extension helpers ----

internal fun ArrayDeque<Value>.removeLastI32(): Int = (removeLast() as Value.I32).v
internal fun ArrayDeque<Value>.removeLastI64(): Long = (removeLast() as Value.I64).v
internal fun ArrayDeque<Value>.removeLastF32(): Float = (removeLast() as Value.F32).v
internal fun ArrayDeque<Value>.removeLastF64(): Double = (removeLast() as Value.F64).v

internal fun zeroOf(type: ValType, module: Module? = null): Value = when (type) {
    ValType.I32 -> Value.I32(0)
    ValType.I64 -> Value.I64(0L)
    ValType.F32 -> Value.F32(0f)
    ValType.F64 -> Value.F64(0.0)
    ValType.V128 -> Value.V128(ByteArray(16))
    is ValType.Ref -> nullValue(type.heap, module)
    is RefType -> nullValue(type.heap, module)
}

internal fun Value.valueType(): ValType = when (this) {
    is Value.I32 -> ValType.I32
    is Value.I64 -> ValType.I64
    is Value.F32 -> ValType.F32
    is Value.F64 -> ValType.F64
    is Value.V128 -> ValType.V128
    is Value.Ref.Func -> FuncRefType
    is Value.Ref.Extern -> ExternRefType
    is Value.Ref.Host -> ExternRefType
    is Value.Ref.I31 -> RefType(HeapType.I31, nullable = false)
    is Value.Ref.Gc -> value?.let { RefType(HeapType.Index(it.typeIndex), nullable = false) } ?: AnyRefType
    is Value.Ref.Exn -> RefType(HeapType.Exn, nullable = value == null)
    is Value.Ref.AnyExtern -> RefType(HeapType.Any, nullable = false)
}

internal fun Value.matches(type: ValType, module: Module? = null): Boolean = when (type) {
    ValType.I32 -> this is Value.I32
    ValType.I64 -> this is Value.I64
    ValType.F32 -> this is Value.F32
    ValType.F64 -> this is Value.F64
    ValType.V128 -> this is Value.V128
    is ValType.Ref -> matchesReference(type.heap, type.nullable, module)
    is RefType -> matchesReference(type.heap, type.nullable, module)
}

private fun nullValue(heap: HeapType, module: Module?): Value.Ref = when (heap) {
    HeapType.Extern, HeapType.NoExtern -> Value.NULL_EXTERN
    HeapType.Exn, HeapType.NoExn -> Value.NULL_EXN
    HeapType.Func, HeapType.NoFunc -> Value.NULL_FUNC
    is HeapType.Index ->
        when (module?.types?.getOrNull(heap.value)) {
            is FuncType -> Value.NULL_FUNC
            else -> Value.NULL_GC
        }
    else -> Value.NULL_GC
}

private fun Value.matchesReference(
    heap: HeapType,
    nullable: Boolean,
    module: Module?,
): Boolean {
    val ref = this as? Value.Ref ?: return false
    return ref.matchesReference(RefType(heap, nullable), module)
}

// ---- integer division / remainder (with trap checks) ----

internal fun idiv(a: Int, b: Int): Int {
    if (b == 0) throw Trap.divByZero()
    if (a == Int.MIN_VALUE && b == -1) throw Trap.intOverflow()
    return a / b
}

internal fun udiv(a: Int, b: Int): Int {
    if (b == 0) throw Trap.divByZero()
    return (a.toUInt() / b.toUInt()).toInt()
}

internal fun irem(a: Int, b: Int): Int {
    if (b == 0) throw Trap.divByZero()
    if (a == Int.MIN_VALUE && b == -1) return 0
    return a % b
}

internal fun urem(a: Int, b: Int): Int {
    if (b == 0) throw Trap.divByZero()
    return (a.toUInt() % b.toUInt()).toInt()
}

internal fun ldiv(a: Long, b: Long): Long {
    if (b == 0L) throw Trap.divByZero()
    if (a == Long.MIN_VALUE && b == -1L) throw Trap.intOverflow()
    return a / b
}

internal fun udiv64(a: Long, b: Long): Long {
    if (b == 0L) throw Trap.divByZero()
    return (a.toULong() / b.toULong()).toLong()
}

internal fun lrem(a: Long, b: Long): Long {
    if (b == 0L) throw Trap.divByZero()
    if (a == Long.MIN_VALUE && b == -1L) return 0L
    return a % b
}

internal fun lurem(a: Long, b: Long): Long {
    if (b == 0L) throw Trap.divByZero()
    return (a.toULong() % b.toULong()).toLong()
}

// ---- shifts / rotates ----

internal fun clz32(value: Int): Int = value.countLeadingZeroBits()
internal fun ctz32(value: Int): Int = value.countTrailingZeroBits()
internal fun popcnt32(value: Int): Int = value.countOneBits()
internal fun clz64(value: Long): Long = value.countLeadingZeroBits().toLong()
internal fun ctz64(value: Long): Long = value.countTrailingZeroBits().toLong()
internal fun popcnt64(value: Long): Long = value.countOneBits().toLong()

internal fun ishl(a: Int, b: Int): Int = a shl (b and 31)
internal fun isr(a: Int, b: Int): Int = a shr (b and 31)
internal fun ushr(a: Int, b: Int): Int = a ushr (b and 31)
internal fun rotl(a: Int, b: Int): Int {
    val s = b and 31
    return (a shl s) or (a ushr (32 - s and 31))
}
internal fun rotr(a: Int, b: Int): Int {
    val s = b and 31
    return (a ushr s) or (a shl (32 - s and 31))
}

internal fun lshl(a: Long, b: Long): Long = a shl (b.toInt() and 63)
internal fun lshr(a: Long, b: Long): Long = a shr (b.toInt() and 63)
internal fun lushr(a: Long, b: Long): Long = a ushr (b.toInt() and 63)
internal fun lrotl(a: Long, b: Long): Long {
    val s = (b.toInt() and 63)
    return (a shl s) or (a ushr ((64 - s) and 63))
}
internal fun lrotr(a: Long, b: Long): Long {
    val s = (b.toInt() and 63)
    return (a ushr s) or (a shl ((64 - s) and 63))
}

// ---- float rounding ----

/** Round to nearest, ties to even (IEEE 754 default). */
internal fun roundNearest(a: Float): Float {
    if (a.isNaN()) return quietNaN(a)
    if (a.isInfinite()) return a
    val r = rintTiesToEven(a.toDouble())  // ties-to-even
    val result = r.toFloat()
    return if (result == 0f) copysign(0f, a) else result
}

internal fun roundNearestDouble(a: Double): Double {
    if (a.isNaN()) return quietNaN(a)
    if (a.isInfinite()) return a
    val result = rintTiesToEven(a)
    return if (result == 0.0) copysign(0.0, a) else result
}

/** Round half to even, matching IEEE-754 roundTiesToEven. */
internal fun rintTiesToEven(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    val floor = kotlin.math.floor(x)
    val diff = x - floor
    return when {
        diff < 0.5 -> floor
        diff > 0.5 -> floor + 1.0
        else -> {
            // exact tie: round to even
            val asLong = floor.toLong()
            if (asLong % 2L == 0L) floor else floor + 1.0
        }
    }
}

/** Truncate toward zero (matches wasm trunc op). */
internal fun truncF(a: Float): Float {
    if (a.isNaN()) return quietNaN(a)
    if (a.isInfinite()) return a
    return if (a >= 0) kotlin.math.floor(a) else kotlin.math.ceil(a)
}

internal fun truncF(a: Double): Double {
    if (a.isNaN()) return quietNaN(a)
    if (a.isInfinite()) return a
    return if (a >= 0) kotlin.math.floor(a) else kotlin.math.ceil(a)
}

internal fun quietNaN(value: Float): Float =
    if (value.isNaN()) Float.fromBits(value.toRawBits() or 0x00400000) else value

internal fun quietNaN(value: Double): Double =
    if (value.isNaN()) {
        Double.fromBits(value.toRawBits() or 0x0008000000000000L)
    } else {
        value
    }

internal fun copysign(a: Float, b: Float): Float {
    val signBit = b.toRawBits() and SIGN_BIT_F32
    return Float.fromBits((a.toRawBits() and 0x7FFFFFFF) or signBit)
}

private val SIGN_BIT_F32: Int = Int.MIN_VALUE // 0x80000000

internal fun copysign(a: Double, b: Double): Double {
    val signBit = b.toRawBits() and Long.MIN_VALUE
    return Double.fromBits((a.toRawBits() and 0x7FFFFFFFFFFFFFFFL) or signBit)
}

/**
 * WebAssembly f32.min semantics: if either is NaN, return NaN; else arithmetic
 * min, but min(+0,-0) = -0 (the "more negative" zero wins).
 */
internal fun fmin(a: Float, b: Float): Float {
    if (a.isNaN() || b.isNaN()) return Float.NaN
    if (a == 0f && b == 0f) {
        // both zero: result is -0 if either is -0
        return if (copysign(1f, a) < 0 || copysign(1f, b) < 0) -0f else 0f
    }
    return if (a < b) a else b
}

internal fun fmax(a: Float, b: Float): Float {
    if (a.isNaN() || b.isNaN()) return Float.NaN
    if (a == 0f && b == 0f) {
        return if (copysign(1f, a) > 0 || copysign(1f, b) > 0) 0f else -0f
    }
    return if (a > b) a else b
}

internal fun fmin(a: Double, b: Double): Double {
    if (a.isNaN() || b.isNaN()) return Double.NaN
    if (a == 0.0 && b == 0.0) {
        return if (copysign(1.0, a) < 0 || copysign(1.0, b) < 0) -0.0 else 0.0
    }
    return if (a < b) a else b
}

internal fun fmax(a: Double, b: Double): Double {
    if (a.isNaN() || b.isNaN()) return Double.NaN
    if (a == 0.0 && b == 0.0) {
        return if (copysign(1.0, a) > 0 || copysign(1.0, b) > 0) 0.0 else -0.0
    }
    return if (a > b) a else b
}

// ---- float-to-int truncation with trapping ----

internal fun truncF32ToI32S(a: Float): Int {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a >= -2147483648f && a < 2147483648f) return a.toInt()
    throw Trap.intOverflow()
}

internal fun truncF32ToI32U(a: Float): Int {
    if (a.isNaN()) throw Trap.invalidConversion()
    // Truncation happens before the integer-domain check, so values in
    // (-1, 0) are valid and produce zero.
    if (a > -1f && a < TWO_POW_32_F) return a.toLong().toInt()
    throw Trap.intOverflow()
}

internal fun truncF32ToI64S(a: Float): Long {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a >= -TWO_POW_63_F && a < TWO_POW_63_F) return a.toLong()
    throw Trap.intOverflow()
}

internal fun truncF32ToI64U(a: Float): Long {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a > -1f && a < TWO_POW_64_F) return unsignedFloatToLongBits(a.toDouble())
    throw Trap.intOverflow()
}

internal fun truncF64ToI32S(a: Double): Int {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a > -2147483649.0 && a < 2147483648.0) return a.toInt()
    throw Trap.intOverflow()
}

internal fun truncF64ToI32U(a: Double): Int {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a > -1.0 && a < TWO_POW_32) return a.toLong().toInt()
    throw Trap.intOverflow()
}

internal fun truncF64ToI64S(a: Double): Long {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a >= -9.2233720368547758e18 && a < 9.2233720368547758e18) return a.toLong()
    throw Trap.intOverflow()
}

internal fun truncF64ToI64U(a: Double): Long {
    if (a.isNaN()) throw Trap.invalidConversion()
    if (a > -1.0 && a < TWO_POW_64) return unsignedFloatToLongBits(a)
    throw Trap.intOverflow()
}

/**
 * Correctly rounded unsigned i64-to-f32 conversion without an intermediate
 * Double. The intermediate used by [ULong.toFloat] can double-round values
 * near a Float tie on some Kotlin targets.
 */
internal fun unsignedLongBitsToFloat(value: Long): Float =
    if (value >= 0L) {
        value.toFloat()
    } else {
        // Shift into the signed domain while retaining a sticky low bit, then
        // scale by two (which is exact for every finite binary float).
        ((value ushr 1) or (value and 1L)).toFloat() * 2f
    }

internal fun truncSatF32ToI32S(value: Float): Int = when {
    value.isNaN() -> 0
    value <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
    value >= 2_147_483_648f -> Int.MAX_VALUE
    else -> value.toInt()
}

internal fun truncSatF32ToI32U(value: Float): Int = when {
    value.isNaN() || value <= 0f -> 0
    value >= TWO_POW_32_F -> -1
    else -> value.toLong().toInt()
}

internal fun truncSatF64ToI32S(value: Double): Int = when {
    value.isNaN() -> 0
    value <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
    value >= 2_147_483_648.0 -> Int.MAX_VALUE
    else -> value.toInt()
}

internal fun truncSatF64ToI32U(value: Double): Int = when {
    value.isNaN() || value <= 0.0 -> 0
    value >= TWO_POW_32 -> -1
    else -> value.toLong().toInt()
}

internal fun truncSatF32ToI64S(value: Float): Long = when {
    value.isNaN() -> 0L
    value <= Long.MIN_VALUE.toFloat() -> Long.MIN_VALUE
    value >= TWO_POW_63_F -> Long.MAX_VALUE
    else -> value.toLong()
}

internal fun truncSatF32ToI64U(value: Float): Long = when {
    value.isNaN() || value <= 0f -> 0L
    value >= TWO_POW_64_F -> -1L
    else -> unsignedFloatToLongBits(value.toDouble())
}

internal fun truncSatF64ToI64S(value: Double): Long = when {
    value.isNaN() -> 0L
    value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
    value >= TWO_POW_63 -> Long.MAX_VALUE
    else -> value.toLong()
}

internal fun truncSatF64ToI64U(value: Double): Long = when {
    value.isNaN() || value <= 0.0 -> 0L
    value >= TWO_POW_64 -> -1L
    else -> unsignedFloatToLongBits(value)
}

private fun unsignedFloatToLongBits(value: Double): Long =
    if (value < TWO_POW_63) {
        value.toLong()
    } else {
        ((value - TWO_POW_63).toLong() or Long.MIN_VALUE)
    }

private const val TWO_POW_32: Double = 4_294_967_296.0
private const val TWO_POW_63: Double = 9_223_372_036_854_775_808.0
private const val TWO_POW_64: Double = 18_446_744_073_709_551_616.0
private const val TWO_POW_32_F: Float = 4_294_967_296f
private const val TWO_POW_63_F: Float = 9_223_372_036_854_775_808f
private const val TWO_POW_64_F: Float = 18_446_744_073_709_551_616f
