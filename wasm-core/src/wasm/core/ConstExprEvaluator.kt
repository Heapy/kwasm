package io.heapy.kwasm

import io.heapy.kwasm.Instr.*

/**
 * Evaluates a Wasm 3 constant (init) expression over a small value stack.
 *
 * In addition to numeric/reference constants, globals, and extended-const
 * integer arithmetic, this includes the GC constructors and reference
 * conversions classified as constant by the core specification.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public class ConstExprEvaluator(private val instance: Instance) {

    public fun eval(expr: List<Instr>, expected: ValType): Value {
        val stack = ArrayDeque<Value>()
        for (ins in expr) {
            when (ins) {
                is I32Const -> stack.addLast(Value.I32(ins.value))
                is I64Const -> stack.addLast(Value.I64(ins.value))
                is F32Const -> stack.addLast(Value.F32(ins.value))
                is F64Const -> stack.addLast(Value.F64(ins.value))
                is RefNull -> stack.addLast(nullRefFor(ins.heap))
                is RefFunc -> stack.addLast(Value.Ref.Func(ins.funcIndex, instance))
                is FcIndex -> when (ins.opcode) {
                    0x23 -> stack.addLast(instance.globals[ins.index].value) // global.get
                    else -> throw IllegalArgumentException("const expr opcode ${ins.opcode}")
                }
                is Simple -> when (ins.opcode) {
                    0x6A -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a + b)) }
                    0x6B -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a - b)) }
                    0x6C -> { val b = stack.removeLastI32(); val a = stack.removeLastI32(); stack.addLast(Value.I32(a * b)) }
                    0x7C -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a + b)) }
                    0x7D -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a - b)) }
                    0x7E -> { val b = stack.removeLastI64(); val a = stack.removeLastI64(); stack.addLast(Value.I64(a * b)) }
                    else -> throw IllegalArgumentException("const expr simple opcode ${ins.opcode}")
                }
                is Gc -> evalGc(ins, stack)
                else -> throw IllegalArgumentException("const expr instr $ins")
            }
        }
        if (stack.size != 1) throw IllegalArgumentException("const expr produced ${stack.size} values")
        val result = stack.last()
        if (!instance.store.config.canonicalizeNaNs) return result
        return when (result) {
            is Value.F32 ->
                if (result.v.isNaN()) Value.F32(Float.fromBits(0x7FC00000)) else result
            is Value.F64 ->
                if (result.v.isNaN()) Value.F64(Double.fromBits(0x7FF8000000000000L)) else result
            else -> result
        }
    }

    public fun evalRef(expr: List<Instr>, @Suppress("UNUSED_PARAMETER") type: RefType): Value.Ref =
        eval(expr, ValType.I32) as Value.Ref

    private fun nullRefFor(heap: HeapType): Value.Ref = when (heap) {
        HeapType.Extern, HeapType.NoExtern -> Value.NULL_EXTERN
        HeapType.Exn, HeapType.NoExn -> Value.NULL_EXN
        HeapType.Func, HeapType.NoFunc -> Value.NULL_FUNC
        is HeapType.Index ->
            if (instance.module.types.getOrNull(heap.value) is FuncType) {
                Value.NULL_FUNC
            } else {
                Value.NULL_GC
            }
        else -> Value.NULL_GC
    }

    private fun evalGc(instruction: Gc, stack: ArrayDeque<Value>) {
        when (instruction.subOpcode) {
            0 -> {
                val type = structType(instruction.firstIndex)
                val fields = MutableList<Value>(type.fields.size) { Value.I32(0) }
                for (index in type.fields.indices.reversed()) {
                    fields[index] = coerceStoredValue(
                        type.fields[index].storage,
                        stack.removeLast(),
                    )
                }
                stack.addLast(
                    Value.Ref.Gc(
                        StructObject(instance, instruction.firstIndex, fields),
                    ),
                )
            }
            1 -> {
                val type = structType(instruction.firstIndex)
                val fields = type.fields.map {
                    defaultStorageValue(it.storage)
                }.toMutableList()
                stack.addLast(
                    Value.Ref.Gc(
                        StructObject(instance, instruction.firstIndex, fields),
                    ),
                )
            }
            6 -> {
                val type = arrayType(instruction.firstIndex)
                val length = stack.removeLastAllocationLength()
                val initial = coerceStoredValue(type.field.storage, stack.removeLast())
                stack.addLast(
                    Value.Ref.Gc(
                        ArrayObject(
                            instance,
                            instruction.firstIndex,
                            MutableList(length) { initial },
                        ),
                    ),
                )
            }
            7 -> {
                val type = arrayType(instruction.firstIndex)
                val length = stack.removeLastAllocationLength()
                val initial = defaultStorageValue(type.field.storage)
                stack.addLast(
                    Value.Ref.Gc(
                        ArrayObject(
                            instance,
                            instruction.firstIndex,
                            MutableList(length) { initial },
                        ),
                    ),
                )
            }
            8 -> {
                val type = arrayType(instruction.firstIndex)
                val elements = MutableList<Value>(instruction.count) { Value.I32(0) }
                for (index in elements.indices.reversed()) {
                    elements[index] = coerceStoredValue(
                        type.field.storage,
                        stack.removeLast(),
                    )
                }
                stack.addLast(
                    Value.Ref.Gc(
                        ArrayObject(instance, instruction.firstIndex, elements),
                    ),
                )
            }
            26 -> {
                val value = stack.removeLast() as Value.Ref
                stack.addLast(
                    if (value.isNullRef()) {
                        Value.NULL_GC
                    } else {
                        Value.Ref.AnyExtern(value)
                    },
                )
            }
            27 -> {
                val value = stack.removeLast() as Value.Ref
                stack.addLast(
                    when {
                        value.isNullRef() -> Value.NULL_EXTERN
                        value is Value.Ref.AnyExtern -> value.external
                        else -> throw Trap.castFailure()
                    },
                )
            }
            28 -> {
                val value = stack.removeLastI32()
                stack.addLast(Value.Ref.I31(value shl 1 shr 1))
            }
            else -> throw IllegalArgumentException(
                "non-constant GC instruction 0xfb/${instruction.subOpcode}",
            )
        }
    }

    private fun structType(index: Int): StructType =
        instance.module.types.getOrNull(index) as? StructType
            ?: throw IllegalArgumentException("constant expression type $index is not a struct")

    private fun arrayType(index: Int): ArrayType =
        instance.module.types.getOrNull(index) as? ArrayType
            ?: throw IllegalArgumentException("constant expression type $index is not an array")

    private fun defaultStorageValue(storage: StorageType): Value = when (storage) {
        StorageType.I8, StorageType.I16 -> Value.I32(0)
        is StorageType.Value -> zeroOf(storage.type, instance.module)
    }

    private fun coerceStoredValue(storage: StorageType, value: Value): Value = when (storage) {
        StorageType.I8 -> Value.I32(value.asI32() and 0xFF)
        StorageType.I16 -> Value.I32(value.asI32() and 0xFFFF)
        is StorageType.Value -> value
    }

    private fun ArrayDeque<Value>.removeLastAllocationLength(): Int {
        val length = removeLastI32().toUInt()
        if (length > Int.MAX_VALUE.toUInt()) {
            throw Trap.arrayOutOfBounds(Int.MAX_VALUE, 0)
        }
        return length.toInt()
    }

    private fun ArrayDeque<Value>.removeLastI32(): Int = (removeLast() as Value.I32).v
    private fun ArrayDeque<Value>.removeLastI64(): Long = (removeLast() as Value.I64).v
}
