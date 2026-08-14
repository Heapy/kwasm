package io.heapy.kwasm

import io.heapy.kwasm.Instr.FcIndex
import io.heapy.kwasm.Instr.I32Const
import io.heapy.kwasm.Instr.Simple
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterpreterHotPathInvariantTest {
    @Test
    fun signedI32ImmediateRoundTripsThroughPackedExecution(): Unit = runBlocking {
        val body = listOf<Instr>(I32Const(Int.MIN_VALUE))
        val store = Store()
        val module = moduleReturning(body)
        val instance = Instance(store, module, ResolvedImports())
        val storedBody = module.functions.single().body

        val hotCode = store.linearHotCode(storedBody)
        assertEquals(Int.MIN_VALUE, (hotCode.packedInstructions.single() shr 32).toInt())
        assertEquals(listOf(Value.I32(Int.MIN_VALUE)), instance.invoke("value"))
    }

    @Test
    fun hotCodeCacheUsesBodyIdentityAndKeepsArraysAligned() {
        val body = plannedExpressionBody()
        val equalBody = plannedExpressionBody()
        assertEquals(body, equalBody)
        assertTrue(body !== equalBody)

        val store = Store()
        val first = store.linearHotCode(body)
        val cached = store.linearHotCode(body)
        val equalButDistinct = store.linearHotCode(equalBody)

        assertTrue(first === cached)
        assertTrue(first !== equalButDistinct)
        assertContentEquals(first.packedInstructions, equalButDistinct.packedInstructions)
        assertContentEquals(first.plan, equalButDistinct.plan)
        assertHotCodeIsAligned(body, first)
        assertHotCodeIsAligned(equalBody, equalButDistinct)
    }

    @Test
    fun reusedControlReplacesBodyAndItsHotCode() {
        val firstBody = listOf<Instr>(I32Const(1))
        val secondBody = plannedExpressionBody()
        val store = Store()
        val firstControl = store.acquireGuestControl(
            kind = ControlKind.Function,
            body = firstBody,
            pc = 0,
            stackBase = 0,
            parameterCount = 0,
            resultCount = 1,
            labelArity = 1,
        )
        val instance = Instance(store, moduleReturning(firstBody), ResolvedImports())
        val frame = store.acquireGuestFrame(
            instance = instance,
            functionIndex = 0,
            functionName = "value",
            type = FuncType(emptyList(), listOf(ValType.I32)),
            localsBase = 0,
            localCount = 0,
            stackBase = 0,
            root = firstControl,
        )
        store.releaseLastGuestControl(frame)

        val reused = store.acquireGuestControl(
            kind = ControlKind.Block,
            body = secondBody,
            pc = 2,
            stackBase = 3,
            parameterCount = 1,
            resultCount = 1,
            labelArity = 1,
        )

        assertTrue(reused === firstControl)
        assertTrue(reused.body === secondBody)
        assertTrue(reused.linearHotCode === store.linearHotCode(secondBody))
        assertEquals(ControlKind.Block, reused.kind)
        assertEquals(2, reused.pc)
        assertEquals(3, reused.stackBase)
        assertHotCodeIsAligned(secondBody, reused.linearHotCode)
    }

    @Test
    fun typedAndGenericNumericStackPathsPreserveRawValues() {
        val f32NaNBits = 0xFFC12345u.toInt()
        val f64NaNBits = 0xFFF8123456789ABCuL.toLong()
        val negativeZeroF32Bits = Int.MIN_VALUE
        val negativeZeroF64Bits = Long.MIN_VALUE

        val typed = RuntimeValueStack(initialCapacity = 0)
        typed.addLastI32(Int.MIN_VALUE)
        typed.addLastI32(Int.MAX_VALUE)
        typed.addLastI64(Long.MIN_VALUE)
        typed.addLastI64(Long.MAX_VALUE)
        typed.addLastF32(Float.fromBits(f32NaNBits))
        typed.addLastF32(Float.fromBits(negativeZeroF32Bits))
        typed.addLastF64(Double.fromBits(f64NaNBits))
        typed.addLastF64(Double.fromBits(negativeZeroF64Bits))

        assertEquals(
            negativeZeroF64Bits,
            (typed.removeLast() as Value.F64).v.toRawBits(),
        )
        assertEquals(f64NaNBits, (typed.removeLast() as Value.F64).v.toRawBits())
        assertEquals(
            negativeZeroF32Bits,
            (typed.removeLast() as Value.F32).v.toRawBits(),
        )
        assertEquals(f32NaNBits, (typed.removeLast() as Value.F32).v.toRawBits())
        assertEquals(Value.I64(Long.MAX_VALUE), typed.removeLast())
        assertEquals(Value.I64(Long.MIN_VALUE), typed.removeLast())
        assertEquals(Value.I32(Int.MAX_VALUE), typed.removeLast())
        assertEquals(Value.I32(Int.MIN_VALUE), typed.removeLast())

        val generic = RuntimeValueStack(initialCapacity = 0)
        generic.addLast(Value.I32(Int.MIN_VALUE))
        generic.addLast(Value.I32(Int.MAX_VALUE))
        generic.addLast(Value.I64(Long.MIN_VALUE))
        generic.addLast(Value.I64(Long.MAX_VALUE))
        generic.addLast(Value.F32(Float.fromBits(f32NaNBits)))
        generic.addLast(Value.F32(Float.fromBits(negativeZeroF32Bits)))
        generic.addLast(Value.F64(Double.fromBits(f64NaNBits)))
        generic.addLast(Value.F64(Double.fromBits(negativeZeroF64Bits)))

        assertEquals(negativeZeroF64Bits, generic.removeLastF64().toRawBits())
        assertEquals(f64NaNBits, generic.removeLastF64().toRawBits())
        assertEquals(negativeZeroF32Bits, generic.removeLastF32().toRawBits())
        assertEquals(f32NaNBits, generic.removeLastF32().toRawBits())
        assertEquals(Long.MAX_VALUE, generic.removeLastI64())
        assertEquals(Long.MIN_VALUE, generic.removeLastI64())
        assertEquals(Int.MAX_VALUE, generic.removeLastI32())
        assertEquals(Int.MIN_VALUE, generic.removeLastI32())
    }

    private fun assertHotCodeIsAligned(body: List<Instr>, hotCode: LinearHotCode) {
        assertEquals(body.size, hotCode.packedInstructions.size)
        assertEquals(body.size, hotCode.plan.size)
        body.indices.forEach { index ->
            assertEquals(body[index].opcode, hotCode.packedInstructions[index].toInt())
        }
        assertEquals(
            LINEAR_PLAN_I32_EXPRESSION_OFFSET + body.size,
            hotCode.plan.first().toInt(),
        )
    }

    private fun plannedExpressionBody(): List<Instr> = listOf(
        I32Const(Int.MIN_VALUE),
        I32Const(-1),
        Simple(0x6A),
        I32Const(Int.MAX_VALUE),
        Simple(0x73),
        FcIndex(0x21, 0),
    )

    private fun moduleReturning(body: List<Instr>): Module = validatedModule {
        types += FuncType(emptyList(), listOf(ValType.I32))
        functions += Function(0, emptyList(), body)
        exports += Export("value", ExportDesc.Function(0))
    }

    private fun validatedModule(configure: ModuleBuilder.() -> Unit): Module =
        ModuleBuilder().apply(configure).build(WASM_HEADER).also(ModuleValidator::validate)

    private companion object {
        val WASM_HEADER: ByteArray =
            byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    }
}
