package io.heapy.kwasm.tck

import io.heapy.kwasm.Module
import io.heapy.kwasm.wat.WatComposer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class NightlyFuzzTest {
    @Test
    fun taskConfigurationContractIsStrictAndReproducible() {
        val configured = NightlyFuzzConfiguration.parse(
            listOf(
                "--raw-seed", "0x10",
                "--raw-iterations", "17",
                "--raw-max-bytes", "33",
                "--snapshot-seed", "20",
                "--snapshot-mutation-iterations", "19",
                "--snapshot-property-iterations", "3",
                "--corpus", "corpus",
                "--require-differential",
                "--allow-no-callable-exports",
                "--wasmtime", "/tools/wasmtime",
                "--expected-wasmtime-version", "46.0.1",
                "--artifacts", "evidence",
                "--max-modules", "23",
                "--max-invocations-per-module", "2",
                "--max-module-bytes", "65536",
                "--process-timeout-ms", "900",
                "--execution-fuel", "12345",
                "--max-runtime-memory-bytes", "131072",
                "--max-table-elements", "64",
                "--minimization-attempts", "7",
            ),
        )

        assertEquals(16uL, configured.rawSeed)
        assertEquals(17, configured.rawIterations)
        assertEquals(33, configured.rawMaximumInputBytes)
        assertEquals(20uL, configured.snapshotSeed)
        assertEquals(19, configured.snapshotMutationIterations)
        assertEquals(3, configured.snapshotPropertyIterations)
        assertEquals(Path.of("corpus"), configured.corpusDirectory)
        assertTrue(configured.requireDifferential)
        assertFalse(configured.requireCallableExports)
        assertEquals("/tools/wasmtime", configured.wasmtimeExecutable)
        assertEquals("46.0.1", configured.expectedWasmtimeVersion)
        assertEquals(Path.of("evidence"), configured.artifactDirectory)
        assertEquals(23, configured.maximumModules)
        assertEquals(2, configured.maximumInvocationsPerModule)
        assertEquals(65_536, configured.maximumModuleBytes)
        assertEquals(900, configured.processTimeoutMillis)
        assertEquals(12_345, configured.executionFuel)
        assertEquals(131_072, configured.maximumRuntimeMemoryBytes)
        assertEquals(64, configured.maximumTableElements)
        assertEquals(7, configured.minimizationAttempts)

        assertFailsWith<IllegalArgumentException> {
            NightlyFuzzConfiguration.parse(listOf("--require-differential"))
        }
        assertFailsWith<IllegalStateException> {
            NightlyFuzzConfiguration.parse(listOf("--not-an-option"))
        }
        assertFailsWith<IllegalArgumentException> {
            NightlyFuzzConfiguration.parse(listOf("--raw-iterations", "-1"))
        }
    }

    @Test
    fun parsesWasmtimeScalarOutputIntoStableTypedValues() {
        val parsed = WasmtimeOutputParser.parseInvocation(
            CapturedProcessOutput(
                command = listOf("wasmtime"),
                exitCode = 0,
                timedOut = false,
                stdout = "-1\n9223372036854775807\nNaN\n-0\n",
                stderr =
                    """
                    warning: using `--invoke` with a function that takes arguments is experimental and may break in the future
                    warning: using `--invoke` with a function that returns values is experimental and may break in the future
                    """.trimIndent(),
            ),
            listOf("i32", "i64", "f32", "f64"),
        )

        assertEquals(
            DifferentialResult.Returned(
                listOf(
                    "i32:-1",
                    "i64:9223372036854775807",
                    "f32:nan",
                    "f64:0x8000000000000000",
                ),
            ),
            parsed,
        )
    }

    @Test
    fun normalizesWasmtimeTrapsAndRejectionsWithoutPathsOrBacktraces() {
        val trap = WasmtimeOutputParser.parseInvocation(
            failedWasmtime(
                """
                Error: failed to invoke `run`
                Caused by:
                  1: wasm trap: integer divide by zero
                """.trimIndent(),
            ),
            listOf("i32"),
        )
        assertEquals(DifferentialResult.Trapped("integer_divide_by_zero"), trap)

        val rejected = WasmtimeOutputParser.parseInvocation(
            failedWasmtime("Error: failed to compile: invalid input WebAssembly code"),
            emptyList(),
        )
        assertEquals(DifferentialResult.Rejected("validation"), rejected)

        assertFailsWith<FuzzInfrastructureException> {
            WasmtimeOutputParser.parseInvocation(
                failedWasmtime("totally unfamiliar tool failure"),
                emptyList(),
            )
        }
        assertFailsWith<FuzzInfrastructureException> {
            WasmtimeOutputParser.parseInvocation(
                CapturedProcessOutput(
                    command = listOf("wasmtime"),
                    exitCode = -1,
                    timedOut = true,
                    stdout = "",
                    stderr = "",
                ),
                emptyList(),
            )
        }
    }

    @Test
    fun parsesOnlyStructuredWasmtimeVersionOutput() {
        assertEquals(
            "46.0.1",
            WasmtimeOutputParser.parseVersion(
                CapturedProcessOutput(
                    command = listOf("wasmtime", "--version"),
                    exitCode = 0,
                    timedOut = false,
                    stdout = "wasmtime 46.0.1 (release)\n",
                    stderr = "",
                ),
            ),
        )
        assertFailsWith<FuzzInfrastructureException> {
            WasmtimeOutputParser.parseVersion(
                CapturedProcessOutput(
                    command = listOf("wasmtime", "--version"),
                    exitCode = 0,
                    timedOut = false,
                    stdout = "46.0.1\n",
                    stderr = "",
                ),
            )
        }
    }

    @Test
    fun boundedProcessRunnerCapturesStreamsExitAndTimeout() {
        val runner = BoundedProcessRunner(maximumCapturedBytes = 128)
        val normal = runner.run(
            fixtureCommand("emit", "7"),
            Duration.ofSeconds(5),
        )
        assertEquals(7, normal.exitCode)
        assertEquals("fixture-out", normal.stdout)
        assertEquals("fixture-err", normal.stderr)
        assertFalse(normal.timedOut)

        val truncated = runner.run(
            fixtureCommand("large"),
            Duration.ofSeconds(5),
        )
        assertTrue(truncated.stdoutTruncated)
        assertEquals(128, truncated.stdout.length)

        val timedOut = runner.run(
            fixtureCommand("sleep"),
            Duration.ofMillis(100),
        )
        assertTrue(timedOut.timedOut)
        assertEquals(-1, timedOut.exitCode)
    }

    @Test
    fun realKwasmAdapterReturnsAndTrapsWithTheSharedNormalization() = runBlocking {
        val bytes = WatComposer.compose(
            """
            (module
              (func (export "value")
                (param i32 i64 f32 f64)
                (result i32)
                local.get 0)
              (func (export "boom") (result i32)
                unreachable))
            """.trimIndent(),
        )
        val engine = KwasmDifferentialEngine(
            NightlyFuzzConfiguration(
                rawIterations = 0,
                snapshotMutationIterations = 0,
                snapshotPropertyIterations = 0,
            ),
        )
        assertEquals(
            DifferentialResult.Returned(listOf("i32:-1")),
            engine.execute(
                bytes,
                DifferentialInvocation(
                    export = "value",
                    arguments = listOf("-1", "2", "1.5", "-2.25"),
                    resultTypes = listOf("i32"),
                ),
            ),
        )
        assertEquals(
            DifferentialResult.Trapped("unreachable"),
            engine.execute(
                bytes,
                DifferentialInvocation("boom", resultTypes = listOf("i32")),
            ),
        )
    }

    @Test
    fun scalarInvocationSelectionIsSortedBoundedAndTyped() {
        val module = Module.decode(
            WatComposer.compose(
                """
                (module
                  (func (export "z") (param i32) (result f32)
                    f32.const 1)
                  (func (export "a") (result i64)
                    i64.const 2)
                  (memory (export "memory") 1))
                """.trimIndent(),
            ),
        )
        val selected = selectScalarInvocations(module, maximum = 1, caseIndex = 0)
        assertEquals(1, selected.size)
        assertEquals("a", selected.single().export)
        assertEquals(listOf("i64"), selected.single().resultTypes)
    }

    @Test
    fun realSnapshotPropertyAndHostileMutationSmoke() = runBlocking {
        val temporary = Files.createTempDirectory("kwasm-snapshot-fuzz-test-")
        try {
            val configuration = NightlyFuzzConfiguration(
                rawIterations = 0,
                snapshotSeed = 123u,
                snapshotMutationIterations = 32,
                snapshotPropertyIterations = 3,
                artifactDirectory = temporary,
                processTimeoutMillis = 5_000,
            )
            val report = SnapshotRobustnessDriver(
                configuration,
                FuzzEvidenceStore(temporary),
            ).run()

            assertEquals(3, report.propertyChecks)
            assertEquals(32, report.mutationIterations)
            assertEquals(
                32,
                report.inspectRejections + report.restoreRejections + report.acceptedMutations,
            )
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun fixtureCommand(vararg arguments: String): List<String> =
        buildList {
            add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(ProcessFixture::class.qualifiedName!!)
            addAll(arguments)
        }

    private fun failedWasmtime(stderr: String): CapturedProcessOutput =
        CapturedProcessOutput(
            command = listOf("wasmtime"),
            exitCode = 1,
            timedOut = false,
            stdout = "",
            stderr = stderr,
        )
}

internal object ProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.singleOrNull()) {
            "large" -> print("x".repeat(4_096))
            "sleep" -> Thread.sleep(10_000)
            else -> {
                check(arguments.size == 2 && arguments[0] == "emit")
                print("fixture-out")
                System.err.print("fixture-err")
                kotlin.system.exitProcess(arguments[1].toInt())
            }
        }
    }
}
