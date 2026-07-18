package io.heapy.kwasm

/**
 * Instruction AST. Each instruction is a flat data object capturing its opcode
 * and immediate operands. Block/control instructions carry their own bodies so
 * the interpreter can branch without re-parsing.
 *
 * [args] holds struct/copy immediates for memory.copy/table.copy etc.
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class Instr {
    public abstract val opcode: Int

    // --- Numeric constants ---
    public data class I32Const(public val value: Int) : Instr() { override val opcode: Int = 0x41 }
    public data class I64Const(public val value: Long) : Instr() { override val opcode: Int = 0x42 }
    public data class F32Const(public val value: Float) : Instr() { override val opcode: Int = 0x43 }
    public data class F64Const(public val value: Double) : Instr() { override val opcode: Int = 0x44 }

    // --- Reference instructions ---
    public data class RefNull(public val heap: HeapType) : Instr() { override val opcode: Int = 0xD0 }
    public object RefIsNull : Instr() { override val opcode: Int = 0xD1 }
    public data class RefFunc(public val funcIndex: Int) : Instr() { override val opcode: Int = 0xD2 }
    public object RefEq : Instr() { override val opcode: Int = 0xD3 }
    public object RefAsNonNull : Instr() { override val opcode: Int = 0xD4 }
    public data class BrOnNull(public val depth: Int) : Instr() { override val opcode: Int = 0xD5 }
    public data class BrOnNonNull(public val depth: Int) : Instr() { override val opcode: Int = 0xD6 }

    // --- Parametric ---
    public object Unreachable : Instr() { override val opcode: Int = 0x00 }
    public object Nop : Instr() { override val opcode: Int = 0x01 }
    public object Return : Instr() { override val opcode: Int = 0x0F }
    public data class Drop(public val n: Int = 1) : Instr() { override val opcode: Int = 0x1A }
    public object Select : Instr() { override val opcode: Int = 0x1B }
    public data class SelectT(public val types: List<ValType>) : Instr() { override val opcode: Int = 0x1C }

    // --- Control ---
    public data class Block(
        public val blockType: BlockType,
        public val body: List<Instr>,
    ) : Instr() { override val opcode: Int = 0x02 }

    public data class Loop(
        public val blockType: BlockType,
        public val body: List<Instr>,
    ) : Instr() { override val opcode: Int = 0x03 }

    public data class If(
        public val blockType: BlockType,
        public val thenBody: List<Instr>,
        public val elseBody: List<Instr>,
    ) : Instr() { override val opcode: Int = 0x04 }

    public object Else : Instr() { override val opcode: Int = 0x05 }
    public object End : Instr() { override val opcode: Int = 0x0B }

    public data class Br(public val depth: Int) : Instr() { override val opcode: Int = 0x0C }
    public data class BrIf(public val depth: Int) : Instr() { override val opcode: Int = 0x0D }
    public class BrTable(
        targets: IntArray,
        public val defaultTarget: Int,
    ) : Instr() {
        private val targetValues: IntArray = targets.copyOf()
        public val targets: IntArray get() = targetValues.copyOf()
        internal val rawTargets: IntArray get() = targetValues
        override val opcode: Int = 0x0E

        override fun equals(other: Any?): Boolean =
            other is BrTable &&
                defaultTarget == other.defaultTarget &&
                targetValues.contentEquals(other.targetValues)

        override fun hashCode(): Int =
            31 * targetValues.contentHashCode() + defaultTarget
    }

    public data class Call(public val funcIndex: Int) : Instr() { override val opcode: Int = 0x10 }
    public data class CallIndirect(
        public val typeIndex: Int,
        public val tableIndex: Int,
    ) : Instr() { override val opcode: Int = 0x11 }
    public data class ReturnCall(public val funcIndex: Int) : Instr() { override val opcode: Int = 0x12 }
    public data class ReturnCallIndirect(
        public val typeIndex: Int,
        public val tableIndex: Int,
    ) : Instr() { override val opcode: Int = 0x13 }
    public data class CallRef(public val typeIndex: Int) : Instr() { override val opcode: Int = 0x14 }
    public data class ReturnCallRef(public val typeIndex: Int) : Instr() { override val opcode: Int = 0x15 }

    // --- Standardized exception handling ---
    public data class Throw(public val tagIndex: Int) : Instr() { override val opcode: Int = 0x08 }
    public object ThrowRef : Instr() { override val opcode: Int = 0x0A }
    public data class TryTable(
        public val blockType: BlockType,
        public val catches: List<CatchClause>,
        public val body: List<Instr>,
    ) : Instr() { override val opcode: Int = 0x1F }

    // --- Legacy exception handling still emitted by Kotlin/Wasm toolchains ---
    public data class LegacyTry(
        public val blockType: BlockType,
        public val body: List<Instr>,
        public val catches: List<LegacyCatch>,
        public val catchAll: List<Instr>?,
        public val delegateDepth: Int?,
    ) : Instr() { override val opcode: Int = 0x06 }
    public data class Rethrow(public val depth: Int) : Instr() { override val opcode: Int = 0x09 }

    // --- Memory ---
    public data class Load(
        override val opcode: Int,
        public val align: Int,
        public val offset: ULong,
        public val memoryIndex: Int = 0,
    ) : Instr() {
        public constructor(opcode: Int, align: Int, offset: UInt, memoryIndex: Int = 0) :
            this(opcode, align, offset.toULong(), memoryIndex)
    }

    public data class Store(
        override val opcode: Int,
        public val align: Int,
        public val offset: ULong,
        public val memoryIndex: Int = 0,
    ) : Instr() {
        public constructor(opcode: Int, align: Int, offset: UInt, memoryIndex: Int = 0) :
            this(opcode, align, offset.toULong(), memoryIndex)
    }
    public object MemorySize : Instr() { override val opcode: Int = 0x3F }
    public object MemoryGrow : Instr() { override val opcode: Int = 0x40 }
    public data class MemorySizeAt(public val memoryIndex: Int) : Instr() {
        override val opcode: Int = 0x3F
    }
    public data class MemoryGrowAt(public val memoryIndex: Int) : Instr() {
        override val opcode: Int = 0x40
    }
    public data class MemoryFill(public val dstIndex: Int, public val srcIndex: Int) : Instr() { override val opcode: Int = 0x0B_FC }
    public data class MemoryCopy(public val dstIndex: Int, public val srcIndex: Int) : Instr() { override val opcode: Int = 0x0A_FC }
    public data class MemoryInit(public val dataSegment: Int, public val memIndex: Int) : Instr() { override val opcode: Int = 0x08_FC }
    public object DataDrop : Instr() { override val opcode: Int = 0x09_FC }

    // --- SIMD lane / memory ops carry their immediates in [args] ---
    public data class Plain(public override val opcode: Int) : Instr()

    // A simple op with no immediates, distinct opcode space.
    public data class Simple(public override val opcode: Int) : Instr()

    // Custom catch-all for FC-prefixed instructions with a single index immediate.
    public data class FcIndex(
        public override val opcode: Int,
        public val index: Int,
        public val second: Int = 0,
    ) : Instr()

    /**
     * Instruction in the 0xFB GC opcode space. The immediate fields are named
     * generically because their meaning is selected by [subOpcode]; unlike a
     * raw byte catch-all, this retains typed heap/ref immediates for validation.
     */
    public data class Gc(
        public val subOpcode: Int,
        public val firstIndex: Int = -1,
        public val secondIndex: Int = -1,
        public val count: Int = -1,
        public val flags: Int = 0,
        public val depth: Int = -1,
        public val sourceType: RefType? = null,
        public val targetType: RefType? = null,
    ) : Instr() {
        override val opcode: Int = 0xFB
    }

    /** SIMD / miscellaneous with raw immediate bytes for full fidelity. */
    public class RawImmediate(
        public override val opcode: Int,
        immediates: LongArray,
        bytes: ByteArray,
    ) : Instr() {
        private val immediateValues: LongArray = immediates.copyOf()
        private val byteValues: ByteArray = bytes.copyOf()
        public val immediates: LongArray get() = immediateValues.copyOf()
        public val bytes: ByteArray get() = byteValues.copyOf()
        internal val rawImmediates: LongArray get() = immediateValues
        internal val rawBytes: ByteArray get() = byteValues

        override fun equals(other: Any?): Boolean =
            other is RawImmediate &&
                opcode == other.opcode &&
                immediateValues.contentEquals(other.immediateValues) &&
                byteValues.contentEquals(other.byteValues)
        override fun hashCode(): Int =
            (opcode * 31 + immediateValues.contentHashCode()) * 31 + byteValues.contentHashCode()
    }
}

@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class CatchClause {
    public abstract val depth: Int

    public data class Tagged(
        public val tagIndex: Int,
        override val depth: Int,
        public val withReference: Boolean,
    ) : CatchClause()

    public data class All(
        override val depth: Int,
        public val withReference: Boolean,
    ) : CatchClause()
}

@io.heapy.kwasm.ExperimentalKwasmApi
public data class LegacyCatch(
    public val tagIndex: Int,
    public val body: List<Instr>,
)

/**
 * Block type for block/loop/if. Either empty (0x40), a single value type,
 * or a type index (for multi-value results).
 */
@io.heapy.kwasm.ExperimentalKwasmApi
public sealed class BlockType {
    public object Empty : BlockType()
    public data class Single(public val type: ValType) : BlockType()
    public data class TypeIndex(public val index: Int) : BlockType()
}
