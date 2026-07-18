package io.heapy.kwasm.tck

import io.heapy.kwasm.Instance
import io.heapy.kwasm.Machine
import io.heapy.kwasm.Value
import io.heapy.kwasm.wat.WatComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasiTestsuiteTest {
    @Test
    fun legacyOutputIsAssertedOnlyWhenDeclared() {
        val noMetadata = WasiTestsuiteSpec.parse(null)
        assertEquals(null, noMetadata.expectedStdout)
        assertEquals(null, noMetadata.expectedStderr)

        val unspecified = WasiTestsuiteSpec.parse("""{"args":[]}""")
        assertEquals(null, unspecified.expectedStdout)
        assertEquals(null, unspecified.expectedStderr)

        val explicitlyEmpty = WasiTestsuiteSpec.parse(
            """{"stdout":"","stderr":""}""",
        )
        assertEquals("", explicitlyEmpty.expectedStdout)
        assertEquals("", explicitlyEmpty.expectedStderr)
    }

    @Test
    fun parsesLegacyAndOperationMetadata() {
        val legacy = WasiTestsuiteSpec.parse(
            """
            {
              "args": ["one", "two"],
              "env": {"A": "first", "B": "second"},
              "root": "fs-tests.dir",
              "exit_code": 33,
              "stdout": "hello",
              "stderr": "warning"
            }
            """.trimIndent(),
        )
        assertEquals(listOf("one", "two"), legacy.arguments)
        assertEquals(mapOf("A" to "first", "B" to "second"), legacy.environment)
        assertEquals("fs-tests.dir", legacy.root)
        assertEquals(33u, legacy.expectedExitCode)
        assertEquals("hello", legacy.expectedStdout)
        assertEquals("warning", legacy.expectedStderr)

        val operations = WasiTestsuiteSpec.parse(
            """
            {
              "world": "wasi:cli/command",
              "operations": [
                {"type": "run", "args": ["value"], "env": {"MODE": "test"}},
                {"type": "read", "id": "stdout", "payload": "done\n"},
                {"type": "read", "id": "stderr", "payload": ""},
                {"type": "wait", "exit_code": 7}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(listOf("value"), operations.arguments)
        assertEquals(mapOf("MODE" to "test"), operations.environment)
        assertEquals("done\n", operations.expectedStdout)
        assertEquals("", operations.expectedStderr)
        assertEquals(7u, operations.expectedExitCode)

        assertFailsWith<IllegalArgumentException> {
            WasiTestsuiteSpec.parse("""{"operations":[{"type":"connect"}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            WasiTestsuiteSpec.parse("""{"args":[],"unexpected":true}""")
        }
    }

    @Test
    fun exclusionsAreExactTrackedAndDuplicateFree() {
        val exclusions = WasiTestsuiteExclusions.parse(
            """
            # A checked-in, auditable skip.
            tests/rust/testsuite/wasm32-wasip1/example.wasm https://github.com/heapy/kwasm/issues/41
            """.trimIndent(),
        )
        assertTrue(
            exclusions.find(
                "tests/rust/testsuite/wasm32-wasip1/example.wasm",
            ) != null,
        )
        exclusions.requireAllMatch(
            listOf("tests/rust/testsuite/wasm32-wasip1/example.wasm"),
        )
        assertFailsWith<IllegalArgumentException> {
            exclusions.requireAllMatch(
                listOf("tests/rust/testsuite/wasm32-wasip1/renamed.wasm"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WasiTestsuiteExclusions.parse("example.wasm missing-ticket")
        }
        assertFailsWith<IllegalArgumentException> {
            WasiTestsuiteExclusions.parse(
                """
                example.wasm https://github.com/heapy/kwasm/issues/41
                example.wasm https://github.com/heapy/kwasm/issues/42
                """.trimIndent(),
            )
        }
    }

    @Test
    fun runnerAppliesArgsEnvironmentPreopenStreamsAndExitExpectations() = runBlocking {
        val case = WasiTestsuiteCase(
            id = "fixtures/process-contract.wasm",
            moduleBytes = processContractModule(),
            spec = WasiTestsuiteSpec(
                arguments = listOf("user-argument", "second"),
                environment = linkedMapOf("FIRST" to "1", "SECOND" to "2"),
                root = "root.dir",
                expectedExitCode = 33u,
                expectedStdout = "hello",
                expectedStderr = "warning",
            ),
            preopen = WasiTestsuitePreopenImage(
                directories = listOf("empty"),
                files = listOf(WasiTestsuiteFile("input.txt", "fixture".encodeToByteArray())),
            ),
        )

        val runner = WasiTestsuiteRunner()
        val result = runner.run(case)
        result.requireSuccess()
        assertEquals(WasiTestsuiteOutcome.Passed, result.outcome)
        assertEquals(33u, result.execution?.exitCode)
        assertEquals("hello", result.execution?.stdout)
        assertEquals("warning", result.execution?.stderr)
        assertEquals(result, runner.run(case), "fresh process state must make repeat runs deterministic")
    }

    @Test
    fun runnerReportsExactExpectationMismatchesAndHonorsExclusions() = runBlocking {
        val module = processContractModule()
        val mismatch = WasiTestsuiteCase(
            id = "fixtures/mismatch.wasm",
            moduleBytes = module,
            spec = WasiTestsuiteSpec(
                arguments = listOf("user-argument", "second"),
                environment = linkedMapOf("FIRST" to "1", "SECOND" to "2"),
                root = "root.dir",
                expectedExitCode = 0u,
                expectedStdout = "not hello",
                expectedStderr = "not warning",
            ),
            preopen = WasiTestsuitePreopenImage(),
        )
        val failed = WasiTestsuiteRunner().run(mismatch)
        assertEquals(WasiTestsuiteOutcome.Failed, failed.outcome)
        assertEquals(3, failed.failures.size)
        assertFailsWith<WasiTestsuiteRunException> { failed.requireSuccess() }

        val exclusions = WasiTestsuiteExclusions.parse(
            "fixtures/mismatch.wasm https://github.com/heapy/kwasm/issues/99",
        )
        val excluded = WasiTestsuiteRunner(exclusions).run(mismatch)
        assertEquals(WasiTestsuiteOutcome.Excluded, excluded.outcome)
        excluded.requireSuccess()
    }

    @Test
    fun runnerNeverConvertsCoroutineCancellationIntoAReportedFailure() = runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val cancellingMachine = object : Machine {
            override suspend fun invoke(
                instance: Instance,
                functionIndex: Int,
                arguments: List<Value>,
            ): List<Value> = throw cancellation
        }
        val testCase = WasiTestsuiteCase(
            id = "fixtures/cancelled.wasm",
            moduleBytes = WatComposer.compose(
                """
                (module
                  (memory (export "memory") 1)
                  (func (export "_start"))
                )
                """.trimIndent(),
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            WasiTestsuiteRunner(machine = cancellingMachine).run(testCase)
        }
        assertEquals(cancellation, thrown)
    }

    private fun processContractModule(): ByteArray =
        WatComposer.compose(
            """
            (module
              (type ${'$'}two_i32 (func (param i32 i32) (result i32)))
              (type ${'$'}four_i32 (func (param i32 i32 i32 i32) (result i32)))
              (type ${'$'}one_i32 (func (param i32)))
              (import "wasi_snapshot_preview1" "args_sizes_get"
                (func ${'$'}args_sizes_get (type ${'$'}two_i32)))
              (import "wasi_snapshot_preview1" "environ_sizes_get"
                (func ${'$'}environ_sizes_get (type ${'$'}two_i32)))
              (import "wasi_snapshot_preview1" "fd_prestat_get"
                (func ${'$'}fd_prestat_get (type ${'$'}two_i32)))
              (import "wasi_snapshot_preview1" "fd_write"
                (func ${'$'}fd_write (type ${'$'}four_i32)))
              (import "wasi_snapshot_preview1" "proc_exit"
                (func ${'$'}proc_exit (type ${'$'}one_i32)))
              (memory (export "memory") 1)
              (data (i32.const 64) "hello")
              (data (i32.const 80) "warning")
              (func (export "_start")
                (block
                  i32.const 16
                  i32.const 20
                  call ${'$'}args_sizes_get
                  i32.eqz
                  br_if 0
                  unreachable
                )
                (block
                  i32.const 16
                  i32.load
                  i32.const 3
                  i32.eq
                  br_if 0
                  unreachable
                )

                (block
                  i32.const 24
                  i32.const 28
                  call ${'$'}environ_sizes_get
                  i32.eqz
                  br_if 0
                  unreachable
                )
                (block
                  i32.const 24
                  i32.load
                  i32.const 2
                  i32.eq
                  br_if 0
                  unreachable
                )

                (block
                  i32.const 3
                  i32.const 32
                  call ${'$'}fd_prestat_get
                  i32.eqz
                  br_if 0
                  unreachable
                )

                i32.const 40
                i32.const 64
                i32.store
                i32.const 44
                i32.const 5
                i32.store
                i32.const 1
                i32.const 40
                i32.const 1
                i32.const 48
                call ${'$'}fd_write
                drop

                i32.const 52
                i32.const 80
                i32.store
                i32.const 56
                i32.const 7
                i32.store
                i32.const 2
                i32.const 52
                i32.const 1
                i32.const 60
                call ${'$'}fd_write
                drop

                i32.const 33
                call ${'$'}proc_exit))
            """.trimIndent(),
        )
}
