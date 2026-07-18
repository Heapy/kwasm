package io.heapy.kwasm.cli

import io.heapy.kwasm.*
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.runBlocking

/**
 * End-to-end smoke test: WAT → binary → decode → instantiate → invoke.
 * Run via `kotlin run -m wasm-cli`. Exercises basic arithmetic, control flow,
 * and memory to validate the full pipeline before tackling the spec suite.
 */
fun main(): Unit = runBlocking {
    println("=== kotlin-wasm pipeline smoke test ===")
    var pass = 0; var fail = 0
    fun check(name: String, expected: Any, actual: Any) {
        val ok = expected.toString() == actual.toString()
        if (ok) { pass++; println("PASS $name") }
        else { fail++; println("FAIL $name: expected $expected got $actual") }
    }

    // 1. Simple add function.
    run {
        val wat = """
            (module
              (func (export "add") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add))
        """.trimIndent()
        val r = runWat(wat, "add", listOf(Value.I32(7), Value.I32(35)))
        check("i32.add 7+35", "[i32:42]", r.toString())
    }

    // 2. Control flow: factorial via loop. fact(5)=120 (1*2*3*4*5).
    run {
        val wat = """
            (module
              (func (export "fact") (param i32) (result i32)
                (local i32 i32)   ;; local1=acc, local2=i
                i32.const 1
                local.set 1       ;; acc = 1
                i32.const 1
                local.set 2       ;; i = 1
                (block
                  (loop
                    local.get 2
                    local.get 0
                    i32.gt_s      ;; i > n ?
                    br_if 1        ;; exit block
                    local.get 1
                    local.get 2
                    i32.mul
                    local.set 1    ;; acc *= i
                    local.get 2
                    i32.const 1
                    i32.add
                    local.set 2    ;; i++
                    br 0))
                local.get 1))
        """.trimIndent()
        val r = runWat(wat, "fact", listOf(Value.I32(5)))
        check("factorial(5)", "[i32:120]", r.toString())
    }

    // 3. Memory store/load. store pops value (top) then address.
    run {
        val wat = """
            (module
              (memory 1)
              (func (export "store_load") (param i32) (result i32)
                i32.const 8     ;; address
                local.get 0     ;; value to store
                i32.store
                i32.const 8     ;; address
                i32.load))
        """.trimIndent()
        val r = runWat(wat, "store_load", listOf(Value.I32(12345)))
        check("memory store/load", "[i32:12345]", r.toString())
    }

    // 4. Float arithmetic.
    run {
        val wat = """
            (module
              (func (export "fmul") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.mul))
        """.trimIndent()
        val r = runWat(wat, "fmul", listOf(Value.F32(1.5f), Value.F32(2.0f)))
        check("f32.mul 1.5*2.0", "[f32:3.0]", r.toString())
    }

    // 5. if/else.
    run {
        val wat = """
            (module
              (func (export "abs") (param i32) (result i32)
                local.get 0
                i32.const 0
                i32.ge_s
                (if (result i32)
                  (then local.get 0)
                  (else
                    i32.const 0
                    local.get 0
                    i32.sub))))
        """.trimIndent()
        check("abs(-5)", "[i32:5]", runWat(wat, "abs", listOf(Value.I32(-5))).toString())
        check("abs(9)", "[i32:9]", runWat(wat, "abs", listOf(Value.I32(9))).toString())
    }

    // 6. br 0 continue inside loop with exit via br_if to outer block.
    run {
        val wat = """
            (module
              (func (export "count2") (result i32) (local i32)
                (block
                  (loop
                    local.get 0
                    i32.const 1
                    i32.add
                    local.set 0
                    local.get 0
                    i32.const 3
                    i32.ge_s
                    br_if 1
                    br 0))
                local.get 0))
        """.trimIndent()
        val r = runWat(wat, "count2", emptyList())
        check("br 0 loop count to 3", "[i32:3]", r.toString())
    }

    println("=== $pass passed, $fail failed ===")
    check(fail == 0) { "$fail native/runtime smoke check(s) failed" }
}

private suspend fun runWat(wat: String, export: String, args: List<Value>): List<Value> {
    val bytes = WatComposer.compose(wat)
    val module = ModuleDecoder.decode(bytes)
    val instance = Instance(module, ResolvedImports())
    val interp = Interpreter()
    val ex = instance.export(export) ?: throw IllegalStateException("no export $export")
    val idx = (ex.desc as ExportDesc.Function).index
    return interp.invoke(instance, idx, args)
}
