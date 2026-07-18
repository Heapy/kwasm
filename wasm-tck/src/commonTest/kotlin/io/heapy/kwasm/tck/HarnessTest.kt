package io.heapy.kwasm.tck

import io.heapy.kwasm.ExecutionLimits
import io.heapy.kwasm.HeapType
import io.heapy.kwasm.Module
import io.heapy.kwasm.RefType
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessTest {
    @Test
    fun runnerRethrowsCoroutineCancellation() = runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val script = WastScript(
            "cancelled.wast",
            listOf(WastCommand.Module(1, "cancelled.wasm")),
        )

        val thrown = assertFailsWith<CancellationException> {
            WastRunner(
                assets = TckAssetLoader { throw cancellation },
            ).run(script)
        }
        assertEquals(cancellation, thrown)
    }

    @Test
    fun parsesEveryWabtCommandKind() {
        val script = WastScript.parse(
            """
            {
              "source_filename": "core/example.wast",
              "commands": [
                {"type":"module","line":1,"filename":"example.0.wasm","name":"${'$'}m"},
                {"type":"register","line":2,"name":"${'$'}m","as":"M"},
                {"type":"action","line":3,"action":{"type":"invoke","module":"${'$'}m","field":"noop","args":[]}},
                {"type":"assert_return","line":4,"action":{"type":"get","field":"g"},"expected":[{"type":"i32","value":"1"}]},
                {"type":"assert_trap","line":5,"action":{"type":"invoke","field":"trap","args":[]},"text":"unreachable"},
                {"type":"assert_exhaustion","line":6,"action":{"type":"invoke","field":"recurse","args":[]},"text":"call stack exhausted"},
                {"type":"assert_exception","line":7,"action":{"type":"invoke","field":"throw","args":[]}},
                {"type":"assert_malformed","line":8,"filename":"bad.0.wasm","module_type":"binary","text":"magic header not detected"},
                {"type":"assert_invalid","line":9,"filename":"bad.1.wasm","text":"type mismatch"},
                {"type":"assert_unlinkable","line":10,"filename":"bad.2.wasm","text":"unknown import"},
                {"type":"assert_uninstantiable","line":11,"filename":"bad.3.wasm","text":"out of bounds memory access"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("core/example.wast", script.sourceFilename)
        assertEquals(11, script.commands.size)
        assertIs<WastCommand.Module>(script.commands[0])
        assertIs<WastCommand.Register>(script.commands[1])
        assertIs<WastCommand.Action>(script.commands[2])
        assertIs<WastCommand.AssertReturn>(script.commands[3])
        assertIs<WastCommand.AssertTrap>(script.commands[4])
        assertIs<WastCommand.AssertExhaustion>(script.commands[5])
        assertIs<WastCommand.AssertException>(script.commands[6])
        assertIs<WastCommand.AssertMalformed>(script.commands[7])
        assertIs<WastCommand.AssertInvalid>(script.commands[8])
        assertIs<WastCommand.AssertUnlinkable>(script.commands[9])
        assertIs<WastCommand.AssertUninstantiable>(script.commands[10])
    }

    @Test
    fun jsonParserIsStrictAndPositionCarrying() {
        val parsed = Json.parse("{\"a\":[true,null,-1.25e2],\"s\":\"\\u03bb\"}")
        assertIs<JsonValue.Object>(parsed)
        val error = assertFailsWith<JsonSyntaxException> { Json.parse("{\"a\": 1, \"a\": 2}") }
        assertTrue(error.offset > 0)
    }

    @Test
    fun exclusionsRequireIssueLinksAndMatchOptionalLines() {
        val exclusions = TckExclusions.parse(
            "simd",
            """
            # tracked SIMD gap
            simd_i32x4.wast:17 https://github.com/heapy/kwasm/issues/17
            relaxed_simd.wast https://github.com/heapy/kwasm/issues/18
            """.trimIndent(),
        )
        assertEquals(2, exclusions.entries.size)
        assertEquals(17, exclusions.find("core/simd_i32x4.wast", 17)?.line)
        assertNull(exclusions.find("core/simd_i32x4.wast", 18))
        assertTrue(exclusions.find("relaxed_simd.wast", 99) != null)
        assertFailsWith<IllegalArgumentException> {
            TckExclusions.parse("threads", "threads.wast no-ticket")
        }
    }

    @Test
    fun tckReferenceConstantsPreserveTheirNullHierarchy() {
        assertEquals(Value.NULL_FUNC, WastValue("funcref", "null").toRuntimeValue())
        assertEquals(Value.NULL_EXTERN, WastValue("externref", "null").toRuntimeValue())
        assertEquals(Value.NULL_EXN, WastValue("exnref", "null").toRuntimeValue())
        assertEquals(Value.NULL_GC, WastValue("ref.null", "any").toRuntimeValue())

        val indexedFunctionModule = Module.decode(
            WatComposer.compose("(module (type (func)))"),
        )
        assertEquals(
            Value.NULL_FUNC,
            WastValue("ref.null", "0").toRuntimeValue(
                RefType(HeapType.Index(0), nullable = true),
                indexedFunctionModule,
            ),
        )

        assertTrue(WastValue("ref.null", "exn").matches(Value.NULL_EXN))
        assertFalse(WastValue("ref.null", "exn").matches(Value.NULL_FUNC))
        assertTrue(WastValue("ref.i31", "17").matches(Value.Ref.I31(17)))
        assertTrue(WastValue("funcref", "0").matches(Value.Ref.Func(71)))
        assertFalse(WastValue("funcref", "0").matches(Value.NULL_FUNC))
    }

    @Test
    fun runnerExecutesReturnsGetsTrapsAndRejectionAssertions() = runBlocking {
        val valid = WatComposer.compose(
            """
            (module
              (func (export "add") (param i32 i32) (result i32)
                local.get 0 local.get 1 i32.add)
              (func (export "boom") unreachable)
              (global i32 (i32.const 7))
              (export "g" (global 0)))
            """.trimIndent(),
        )
        val invalid = WatComposer.compose(
            """(module (func (result i32) i64.const 0))""",
        )
        val unlinkable = WatComposer.compose(
            """(module (type (func)) (import "missing" "function" (func (type 0))))""",
        )
        val assets = mapOf(
            "valid.wasm" to valid,
            "malformed.wasm" to byteArrayOf(0, 1, 2),
            "invalid.wasm" to invalid,
            "unlinkable.wasm" to unlinkable,
        )
        val script = WastScript(
            "runner.wast",
            listOf(
                WastCommand.Module(1, "valid.wasm"),
                WastCommand.AssertReturn(
                    2,
                    WastAction.Invoke(null, "add", listOf(WastValue("i32", "20"), WastValue("i32", "22"))),
                    listOf(WastValue("i32", "42")),
                ),
                WastCommand.AssertReturn(3, WastAction.Get(null, "g"), listOf(WastValue("i32", "7"))),
                WastCommand.AssertTrap(4, WastAction.Invoke(null, "boom", emptyList()), "unreachable"),
                WastCommand.AssertMalformed(5, "malformed.wasm", "bad magic"),
                WastCommand.AssertInvalid(6, "invalid.wasm", "type mismatch"),
                WastCommand.AssertUnlinkable(7, "unlinkable.wasm", "unknown import"),
            ),
        )

        val report = WastRunner(TckAssetLoader { assets.getValue(it) }).run(script)
        report.requireSuccess()
        assertEquals(7, report.passed)
        assertEquals(0, report.failed)
    }

    @Test
    fun runnerRecognizesCallStackExhaustion() = runBlocking {
        val recursive = WatComposer.compose(
            """(module (func ${'$'}f (export "recurse") call ${'$'}f))""",
        )
        val script = WastScript(
            "exhaustion.wast",
            listOf(
                WastCommand.Module(1, "recursive.wasm"),
                WastCommand.AssertExhaustion(
                    2,
                    WastAction.Invoke(null, "recurse", emptyList()),
                    "call stack exhausted",
                ),
            ),
        )
        val runner = WastRunner(
            assets = TckAssetLoader { recursive },
            storeFactory = {
                Store(StoreConfig(limits = ExecutionLimits(maxFrames = 8, maxValueStackSlots = 128)))
            },
        )
        runner.run(script).requireSuccess()
    }

    @Test
    fun registrationsLinkGuestExportsIntoLaterModules() = runBlocking {
        val producer = WatComposer.compose(
            """
            (module
              (func (export "inc") (param i32) (result i32)
                local.get 0 i32.const 1 i32.add))
            """.trimIndent(),
        )
        val consumer = WatComposer.compose(
            """
            (module
              (type (func (param i32) (result i32)))
              (import "math" "inc" (func ${'$'}inc (type 0)))
              (func (export "run") (param i32) (result i32)
                local.get 0 call ${'$'}inc))
            """.trimIndent(),
        )
        val assets = mapOf("producer.wasm" to producer, "consumer.wasm" to consumer)
        val script = WastScript(
            "linking.wast",
            listOf(
                WastCommand.Module(1, "producer.wasm", name = "${'$'}producer"),
                WastCommand.Register(2, "${'$'}producer", "math"),
                WastCommand.Module(3, "consumer.wasm"),
                WastCommand.AssertReturn(
                    4,
                    WastAction.Invoke(null, "run", listOf(WastValue("i32", "41"))),
                    listOf(WastValue("i32", "42")),
                ),
            ),
        )
        var storesCreated = 0
        WastRunner(
            assets = TckAssetLoader(assets::getValue),
            storeFactory = {
                storesCreated++
                Store()
            },
        ).run(script).requireSuccess()
        assertEquals(1, storesCreated)
    }

    @Test
    fun decoderFuzzSmokeIsDeterministicAndTotalForBoundedInputs() {
        val first = DecoderFuzzDriver.run(seed = 123u, iterations = 200, maximumInputBytes = 128)
        val second = DecoderFuzzDriver.run(seed = 123u, iterations = 200, maximumInputBytes = 128)
        assertEquals(first, second)
        assertEquals(200, first.decoded + first.rejectedAtDecode + first.rejectedAtValidation)
    }

    @Test
    fun differentialDriverReportsAndMinimizesDivergence() = runBlocking {
        val driver = DifferentialDriver(
            mapOf(
                "a" to DifferentialEngine { _, _ -> DifferentialResult.Returned(listOf("i32:1")) },
                "b" to DifferentialEngine { _, _ -> DifferentialResult.Returned(listOf("i32:2")) },
            ),
        )
        assertTrue(driver.compare(byteArrayOf(1, 2), DifferentialInvocation("f")) != null)
        val minimized = driver.minimize(byteArrayOf(0, 1, 2, 3)) { bytes -> 3 in bytes }
        assertTrue(minimized.contentEquals(byteArrayOf(3)))
    }

    @Test
    fun continuationPropertyComparesRestoredAndUninterruptedResults() = runBlocking {
        val property = object : ContinuationProperty<Int, Int, Int> {
            override suspend fun start(): Int = 0
            override suspend fun runSteps(state: Int, steps: Int): Int = state + steps
            override suspend fun snapshot(state: Int): Int = state
            override suspend fun restore(snapshot: Int): Int = snapshot
            override suspend fun finish(state: Int): Int = state + (100 - state)
        }
        val results = ContinuationPropertyDriver.check(property, 37)
        assertEquals(100 to 100, results)
    }
}
