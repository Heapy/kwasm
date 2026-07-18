package io.heapy.kwasm.tck

import io.heapy.kwasm.ExecutionLimits
import io.heapy.kwasm.ExportDesc
import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.Instance
import io.heapy.kwasm.LinkException
import io.heapy.kwasm.Module
import io.heapy.kwasm.ModuleValidationLimits
import io.heapy.kwasm.ResolvedImports
import io.heapy.kwasm.Store
import io.heapy.kwasm.StoreConfig
import io.heapy.kwasm.TrapKind
import io.heapy.kwasm.UncaughtWasmException
import io.heapy.kwasm.ValType
import io.heapy.kwasm.ValidationException
import io.heapy.kwasm.Value
import io.heapy.kwasm.WasmDecodeException
import io.heapy.kwasm.WasmInstantiationException
import io.heapy.kwasm.WasmTrap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val DEFAULT_RAW_SEED: ULong = 0x4B5741534DUL
private const val DEFAULT_SNAPSHOT_SEED: ULong = 0x534E415053484F54UL

/**
 * Dedicated JVM entry point for the scheduled robustness gate.
 *
 * A normal `jvmTest` does not execute this main. Gradle's `nightlyFuzz` task
 * supplies explicit arguments and an optional externally generated corpus.
 */
public fun main(arguments: Array<String>): Unit = runBlocking {
    val configuration = NightlyFuzzConfiguration.parse(arguments.toList())
    val summary = NightlyFuzzGate(configuration).run()
    println(summary.describe())
}

internal data class NightlyFuzzConfiguration(
    val rawSeed: ULong = DEFAULT_RAW_SEED,
    val rawIterations: Int = 10_000,
    val rawMaximumInputBytes: Int = 4_096,
    val snapshotSeed: ULong = DEFAULT_SNAPSHOT_SEED,
    val snapshotMutationIterations: Int = 1_000,
    val snapshotPropertyIterations: Int = 16,
    val corpusDirectory: Path? = null,
    val requireDifferential: Boolean = false,
    val requireCallableExports: Boolean = true,
    val wasmtimeExecutable: String = "wasmtime",
    val expectedWasmtimeVersion: String? = null,
    /**
     * Optional primary wasm3 interpreter executable (the interpreter at
     * github.com/wasm3/wasm3, NOT the Wasm 3.0 spec). `null` disables the
     * wasm3 oracle, keeping local runs free of external binary dependencies.
     */
    val wasm3Executable: String? = null,
    val expectedWasm3Version: String? = null,
    /** Optional secondary wasm3 build (typically `main` HEAD) for dual-version triangulation. */
    val wasm3SecondaryExecutable: String? = null,
    val expectedWasm3SecondaryVersion: String? = null,
    val artifactDirectory: Path = Path.of("build", "fuzz-artifacts"),
    val maximumModules: Int = 256,
    val maximumInvocationsPerModule: Int = 4,
    val maximumModuleBytes: Int = 1 shl 20,
    val processTimeoutMillis: Long = 5_000,
    val executionFuel: Long = 5_000_000,
    val maximumRuntimeMemoryBytes: Long = 4L shl 20,
    val maximumTableElements: Int = 1_024,
    val minimizationAttempts: Int = 32,
) {
    init {
        require(rawIterations >= 0) { "raw iterations must not be negative" }
        require(rawMaximumInputBytes in 0 until Int.MAX_VALUE) {
            "raw maximum input bytes must be in 0..<Int.MAX_VALUE"
        }
        require(snapshotMutationIterations >= 0) {
            "snapshot mutation iterations must not be negative"
        }
        require(snapshotPropertyIterations >= 0) {
            "snapshot property iterations must not be negative"
        }
        require(maximumModules > 0) { "maximum modules must be positive" }
        require(maximumInvocationsPerModule > 0) {
            "maximum invocations per module must be positive"
        }
        require(maximumModuleBytes in 8..(16 shl 20)) {
            "maximum module bytes must be between 8 and 16 MiB"
        }
        require(processTimeoutMillis in 100..60_000) {
            "process timeout must be between 100 and 60000 milliseconds"
        }
        require(executionFuel in 1..100_000_000) {
            "execution fuel must be between 1 and 100000000"
        }
        require(maximumRuntimeMemoryBytes in 65_536..(64L shl 20)) {
            "maximum runtime memory must be between 64 KiB and 64 MiB"
        }
        require(maximumTableElements in 0..1_000_000) {
            "maximum table elements must be between 0 and 1000000"
        }
        require(minimizationAttempts in 0..256) {
            "minimization attempts must be between 0 and 256"
        }
        require(wasmtimeExecutable.isNotBlank()) {
            "wasmtime executable must not be blank"
        }
        require(expectedWasmtimeVersion?.isNotBlank() != false) {
            "expected wasmtime version must not be blank"
        }
        // wasm3 is an optional oracle; when configured, both executable and
        // expected version must be present (a version-less reference runtime
        // cannot be reproduced). Secondary is only allowed together with primary.
        require(wasm3Executable?.isNotBlank() != false) {
            "wasm3 executable must not be blank"
        }
        require(wasm3SecondaryExecutable?.isNotBlank() != false) {
            "wasm3 secondary executable must not be blank"
        }
        // Cross-field wasm3 relationships are validated in
        // [validateCrossFieldOptions] at the end of [parse], because the
        // incremental copy() in parse re-runs init on every flag and the
        // expected-version flag may legitimately not have been seen yet.
    }

    /**
     * Cross-field checks that depend on more than one option at once. Called
     * once at the end of [parse]; safe to call on a default-constructed
     * instance (it passes trivially when wasm3 is unconfigured).
     */
    internal fun validateCrossFieldOptions() {
        require(wasm3Executable == null || !expectedWasm3Version.isNullOrBlank()) {
            "--wasm3 requires --expected-wasm3-version"
        }
        require(wasm3SecondaryExecutable == null || wasm3Executable != null) {
            "--wasm3-secondary requires --wasm3"
        }
        require(wasm3SecondaryExecutable == null || !expectedWasm3SecondaryVersion.isNullOrBlank()) {
            "--wasm3-secondary requires --expected-wasm3-secondary-version"
        }
        require(!requireDifferential || corpusDirectory != null) {
            "--require-differential requires --corpus"
        }
    }

    val validationLimits: ModuleValidationLimits
        get() = ModuleValidationLimits(
            maxModuleSizeBytes = maximumModuleBytes.toLong(),
            maxFunctions = 256,
            maxFunctionBodySizeBytes = 64L * 1024L,
            maxLocalsPerFunction = 2_048,
            maxMemoryPages = maximumRuntimeMemoryBytes / 65_536,
            maxTableElements = maximumTableElements.toLong(),
            maxGlobals = 256,
            maxTypes = 256,
            maxRecursionGroups = 256,
            maxControlNesting = 256,
            allowInertV128Types = false,
        )

    companion object {
        fun parse(arguments: List<String>): NightlyFuzzConfiguration {
            var configuration = NightlyFuzzConfiguration()
            var index = 0
            fun value(option: String): String {
                require(index + 1 < arguments.size) { "$option requires a value" }
                return arguments[++index]
            }
            while (index < arguments.size) {
                val option = arguments[index]
                configuration =
                    when (option) {
                        "--raw-seed" ->
                            configuration.copy(rawSeed = parseUnsigned(value(option), option))
                        "--raw-iterations" ->
                            configuration.copy(rawIterations = value(option).toInt())
                        "--raw-max-bytes" ->
                            configuration.copy(rawMaximumInputBytes = value(option).toInt())
                        "--snapshot-seed" ->
                            configuration.copy(snapshotSeed = parseUnsigned(value(option), option))
                        "--snapshot-mutation-iterations" ->
                            configuration.copy(snapshotMutationIterations = value(option).toInt())
                        "--snapshot-property-iterations" ->
                            configuration.copy(snapshotPropertyIterations = value(option).toInt())
                        "--corpus" ->
                            configuration.copy(corpusDirectory = Path.of(value(option)))
                        "--require-differential" ->
                            configuration.copy(requireDifferential = true)
                        "--allow-no-callable-exports" ->
                            configuration.copy(requireCallableExports = false)
                        "--wasmtime" ->
                            configuration.copy(wasmtimeExecutable = value(option))
                        "--expected-wasmtime-version" ->
                            configuration.copy(expectedWasmtimeVersion = value(option))
                        "--wasm3" ->
                            configuration.copy(wasm3Executable = value(option))
                        "--expected-wasm3-version" ->
                            configuration.copy(expectedWasm3Version = value(option))
                        "--wasm3-secondary" ->
                            configuration.copy(wasm3SecondaryExecutable = value(option))
                        "--expected-wasm3-secondary-version" ->
                            configuration.copy(expectedWasm3SecondaryVersion = value(option))
                        "--artifacts" ->
                            configuration.copy(artifactDirectory = Path.of(value(option)))
                        "--max-modules" ->
                            configuration.copy(maximumModules = value(option).toInt())
                        "--max-invocations-per-module" ->
                            configuration.copy(maximumInvocationsPerModule = value(option).toInt())
                        "--max-module-bytes" ->
                            configuration.copy(maximumModuleBytes = value(option).toInt())
                        "--process-timeout-ms" ->
                            configuration.copy(processTimeoutMillis = value(option).toLong())
                        "--execution-fuel" ->
                            configuration.copy(executionFuel = value(option).toLong())
                        "--max-runtime-memory-bytes" ->
                            configuration.copy(maximumRuntimeMemoryBytes = value(option).toLong())
                        "--max-table-elements" ->
                            configuration.copy(maximumTableElements = value(option).toInt())
                        "--minimization-attempts" ->
                            configuration.copy(minimizationAttempts = value(option).toInt())
                        else -> error("unknown nightly fuzz option '$option'")
                    }
                index++
            }
            // Cross-field checks run once after all flags are parsed; running
            // them in init would trip on the incremental copy() steps above.
            configuration.validateCrossFieldOptions()
            return configuration
        }

        private fun parseUnsigned(value: String, option: String): ULong =
            try {
                if (value.startsWith("0x", ignoreCase = true)) {
                    value.drop(2).toULong(16)
                } else {
                    value.toULong()
                }
            } catch (failure: NumberFormatException) {
                throw IllegalArgumentException("$option is not an unsigned 64-bit integer: '$value'", failure)
            }
    }
}

internal data class CapturedProcessOutput(
    val command: List<String>,
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
)

/** Shell-free, bounded subprocess adapter used for the pinned reference CLI. */
internal class BoundedProcessRunner(
    private val maximumCapturedBytes: Int = 1 shl 20,
) {
    init {
        require(maximumCapturedBytes > 0) { "maximum captured bytes must be positive" }
    }

    fun run(
        command: List<String>,
        timeout: Duration,
        workingDirectory: Path? = null,
    ): CapturedProcessOutput {
        require(command.isNotEmpty()) { "process command must not be empty" }
        require(!timeout.isZero && !timeout.isNegative) { "process timeout must be positive" }
        val builder = ProcessBuilder(command)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        val process =
            try {
                builder.start()
            } catch (failure: Exception) {
                throw FuzzInfrastructureException(
                    "could not start '${command.first()}': ${failure.message ?: failure::class.simpleName}",
                    failure,
                )
            }
        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.readBounded(maximumCapturedBytes)
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.readBounded(maximumCapturedBytes)
        }
        val completed =
            try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                throw FuzzInfrastructureException("interrupted while waiting for '${command.first()}'", interrupted)
            }
        if (!completed) {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        val capturedStdout = stdout.get(5, TimeUnit.SECONDS)
        val capturedStderr = stderr.get(5, TimeUnit.SECONDS)
        return CapturedProcessOutput(
            command = command.toList(),
            exitCode = if (completed) process.exitValue() else -1,
            timedOut = !completed,
            stdout = capturedStdout.text,
            stderr = capturedStderr.text,
            stdoutTruncated = capturedStdout.truncated,
            stderrTruncated = capturedStderr.truncated,
        )
    }

    private data class BoundedText(val text: String, val truncated: Boolean)

    private fun InputStream.readBounded(maximum: Int): BoundedText {
        val retained = ByteArrayOutputStream(minOf(maximum, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        use { source ->
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                val remaining = maximum - retained.size()
                if (remaining > 0) retained.write(buffer, 0, minOf(remaining, count))
                total += count
            }
        }
        return BoundedText(
            retained.toString(StandardCharsets.UTF_8),
            truncated = total > maximum,
        )
    }
}

internal object WasmtimeOutputParser {
    private val version = Regex("""^wasmtime\s+([0-9]+\.[0-9]+\.[0-9]+)(?:\s.*)?$""")
    private val allowedSuccessWarning = Regex(
        """^warning: using `--invoke` with a function that (?:takes arguments|returns values) .*$""",
    )

    fun parseVersion(output: CapturedProcessOutput): String {
        requireInfrastructureCompletion(output, "wasmtime --version")
        if (output.exitCode != 0 || output.stderr.isNotBlank()) {
            throw FuzzInfrastructureException(
                "wasmtime --version failed: ${stableProcessDiagnostic(output)}",
            )
        }
        val line = output.stdout.trim()
        return version.matchEntire(line)?.groupValues?.get(1)
            ?: throw FuzzInfrastructureException(
                "unrecognized wasmtime version output: '${line.take(256)}'",
            )
    }

    fun parseInvocation(
        output: CapturedProcessOutput,
        resultTypes: List<String>,
    ): DifferentialResult {
        requireInfrastructureCompletion(output, "wasmtime invocation")
        if (output.exitCode == 0) {
            val unexpectedStderr = output.stderr.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { allowedSuccessWarning.matches(it) }
                .toList()
            if (unexpectedStderr.isNotEmpty()) {
                throw FuzzInfrastructureException(
                    "wasmtime succeeded with unrecognized stderr: " +
                        unexpectedStderr.joinToString(" | ").take(512),
                )
            }
            val lines =
                if (output.stdout.isBlank()) emptyList()
                else output.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (lines.size != resultTypes.size) {
                throw FuzzInfrastructureException(
                    "wasmtime returned ${lines.size} value line(s), expected ${resultTypes.size}: " +
                        output.stdout.take(512),
                )
            }
            return DifferentialResult.Returned(
                lines.zip(resultTypes).map { (value, type) ->
                    normalizeReferenceScalar(type, value)
                },
            )
        }

        val diagnostic = (output.stderr + "\n" + output.stdout).lowercase()
        canonicalTrap(diagnostic)?.let { return DifferentialResult.Trapped(it) }
        val phase =
            when {
                "failed to parse webassembly" in diagnostic ||
                    "magic header" in diagnostic ||
                    "unexpected end-of-file" in diagnostic -> "decode"
                "failed to compile" in diagnostic ||
                    "invalid input webassembly" in diagnostic ||
                    "type mismatch" in diagnostic -> "validation"
                "unknown import" in diagnostic ||
                    "incompatible import" in diagnostic ||
                    "link error" in diagnostic -> "link"
                "failed to instantiate" in diagnostic ||
                    "instantiation" in diagnostic -> "instantiation"
                "unknown export" in diagnostic ||
                    "failed to find function export" in diagnostic -> "export"
                else -> null
            }
        if (phase != null) return DifferentialResult.Rejected(phase)
        throw FuzzInfrastructureException(
            "unrecognized wasmtime failure (exit ${output.exitCode}): ${stableProcessDiagnostic(output)}",
        )
    }

    fun requireSuccessfulCompilation(output: CapturedProcessOutput) {
        requireInfrastructureCompletion(output, "wasmtime compile")
        if (output.exitCode != 0) {
            throw FuzzInfrastructureException(
                "pinned wasmtime rejected a supposedly valid generated module: " +
                    stableProcessDiagnostic(output),
            )
        }
    }
}

internal class KwasmDifferentialEngine(
    private val configuration: NightlyFuzzConfiguration,
) : DifferentialEngine {
    override suspend fun execute(
        module: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialResult =
        try {
            withTimeout(configuration.processTimeoutMillis) {
                executeBounded(module, invocation)
            }
        } catch (_: TimeoutCancellationException) {
            throw FuzzInfrastructureException(
                "kwasm invocation exceeded ${configuration.processTimeoutMillis} ms",
            )
        }

    private suspend fun executeBounded(
        bytes: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialResult {
        val module =
            try {
                Module.decode(bytes, configuration.validationLimits)
            } catch (_: WasmDecodeException) {
                return DifferentialResult.Rejected("decode")
            } catch (_: ValidationException) {
                return DifferentialResult.Rejected("validation")
            }
        val store = Store(
            StoreConfig(
                limits = ExecutionLimits(
                    maxFrames = 4_096,
                    maxValueStackSlots = 65_536,
                ),
                checkpointInterval = 256,
                canonicalizeNaNs = true,
                fuelEnabled = true,
                initialFuel = configuration.executionFuel,
                fuelExhaustionPolicy = FuelExhaustionPolicy.Trap,
            ),
        )
        val instance =
            try {
                Instance(store, module, ResolvedImports())
            } catch (_: LinkException) {
                return DifferentialResult.Rejected("link")
            } catch (_: WasmInstantiationException) {
                return DifferentialResult.Rejected("instantiation")
            }
        return try {
            instance.runStart()
            val function = instance.exportedFunction(invocation.export)
                ?: return DifferentialResult.Rejected("export")
            if (function.type.results.map(::scalarTypeName) != invocation.resultTypes) {
                throw FuzzInfrastructureException(
                    "kwasm export '${invocation.export}' no longer has the selected scalar signature",
                )
            }
            val arguments = function.type.params.zip(invocation.arguments).map { (type, value) ->
                parseKwasmArgument(type, value)
            }
            if (arguments.size != function.type.params.size) {
                throw FuzzInfrastructureException(
                    "selected invocation '${invocation.export}' has the wrong argument count",
                )
            }
            DifferentialResult.Returned(function.invoke(arguments).map(::normalizeKwasmValue))
        } catch (trap: WasmTrap) {
            DifferentialResult.Trapped(canonicalTrap(trap.kind))
        } catch (_: UncaughtWasmException) {
            DifferentialResult.Trapped("uncaught_exception")
        }
    }
}

// Note: this parser is for the wasm3 *interpreter* (github.com/wasm3/wasm3).
// It is unrelated to wasm3Wast2JsonArguments in build.gradle.kts, which are
// WABT feature flags for the Wasm 3.0 *spec proposal*.

internal class WasmtimeDifferentialEngine(
    private val configuration: NightlyFuzzConfiguration,
    private val processRunner: BoundedProcessRunner,
    private val temporaryDirectory: Path,
) : DifferentialEngine {
    override suspend fun execute(
        module: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialResult {
        val moduleFile = Files.createTempFile(temporaryDirectory, "invoke-", ".wasm")
        try {
            Files.write(moduleFile, module)
            val output = processRunner.run(
                command = buildList {
                    add(configuration.wasmtimeExecutable)
                    add("run")
                    add("-W")
                    add(wasmtimeLimits(configuration))
                    add("--invoke")
                    add(invocation.export)
                    add(moduleFile.toAbsolutePath().pathString)
                    addAll(invocation.arguments)
                },
                timeout = Duration.ofMillis(configuration.processTimeoutMillis + 1_000),
                workingDirectory = temporaryDirectory,
            )
            return WasmtimeOutputParser.parseInvocation(output, invocation.resultTypes)
        } finally {
            Files.deleteIfExists(moduleFile)
        }
    }

    fun verifyVersion(): String {
        val output = processRunner.run(
            listOf(configuration.wasmtimeExecutable, "--version"),
            Duration.ofMillis(configuration.processTimeoutMillis),
        )
        val actual = WasmtimeOutputParser.parseVersion(output)
        val expected = configuration.expectedWasmtimeVersion
        if (expected != null && actual != expected) {
            throw FuzzInfrastructureException(
                "wasmtime version mismatch: expected $expected, found $actual",
            )
        }
        return actual
    }

    fun requireValid(module: ByteArray) {
        val moduleFile = Files.createTempFile(temporaryDirectory, "validate-", ".wasm")
        val outputFile = Files.createTempFile(temporaryDirectory, "validate-", ".cwasm")
        try {
            Files.write(moduleFile, module)
            val output = processRunner.run(
                listOf(
                    configuration.wasmtimeExecutable,
                    "compile",
                    "-W",
                    wasmtimeLimits(configuration),
                    "-o",
                    outputFile.toAbsolutePath().pathString,
                    moduleFile.toAbsolutePath().pathString,
                ),
                Duration.ofMillis(configuration.processTimeoutMillis + 1_000),
                temporaryDirectory,
            )
            WasmtimeOutputParser.requireSuccessfulCompilation(output)
        } finally {
            Files.deleteIfExists(moduleFile)
            Files.deleteIfExists(outputFile)
        }
    }
}

/**
 * Differential engine backed by the wasm3 interpreter CLI
 * (`wasm3 --func <name> <module> [args...]`). Unrelated to the
 * `wasm3Wast2JsonArguments` build flag, which targets the Wasm 3.0 spec.
 *
 * wasm3 has no CLI knobs for fuel or memory caps (it is an embedded runtime;
 * such limits are API-only), so execution is bounded only by
 * [NightlyFuzzConfiguration.processTimeoutMillis]. This is acceptable for a
 * nightly oracle whose purpose is cross-engine agreement, not DoS hardening.
 */
internal class Wasm3DifferentialEngine(
    private val configuration: NightlyFuzzConfiguration,
    private val processRunner: BoundedProcessRunner,
    private val temporaryDirectory: Path,
    private val executable: String,
    private val expectedVersion: String? = null,
) : DifferentialEngine {
    override suspend fun execute(
        module: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialResult {
        // wasm3 prints floats via lossy `%.7g`/`%.15g` formatting in its
        // repl_call path, which loses denormals and trailing precision bits.
        // Abstain from float-bearing invocations rather than emit false
        // divergences; DifferentialDriver.compare filters abstained engines.
        if (invocation.resultTypes.any { it == "f32" || it == "f64" }) {
            return DifferentialResult.Abstained("wasm3-prints-float-with-lossy-precision")
        }
        return try {
            withTimeout(configuration.processTimeoutMillis) {
                executeBounded(module, invocation)
            }
        } catch (_: TimeoutCancellationException) {
            throw FuzzInfrastructureException(
                "wasm3 invocation exceeded ${configuration.processTimeoutMillis} ms",
            )
        }
    }

    private suspend fun executeBounded(
        module: ByteArray,
        invocation: DifferentialInvocation,
    ): DifferentialResult {
        val moduleFile = Files.createTempFile(temporaryDirectory, "wasm3-invoke-", ".wasm")
        try {
            Files.write(moduleFile, module)
            val output = processRunner.run(
                command = buildList {
                    add(executable)
                    add("--func")
                    add(invocation.export)
                    add(moduleFile.toAbsolutePath().pathString)
                    addAll(invocation.arguments)
                },
                timeout = Duration.ofMillis(configuration.processTimeoutMillis + 1_000),
                workingDirectory = temporaryDirectory,
            )
            return Wasm3OutputParser.parseInvocation(output, invocation.resultTypes)
        } finally {
            Files.deleteIfExists(moduleFile)
        }
    }

    fun verifyVersion(): String {
        val output = processRunner.run(
            listOf(executable, "--version"),
            Duration.ofMillis(configuration.processTimeoutMillis),
        )
        val actual = Wasm3OutputParser.parseVersion(output)
        if (expectedVersion != null && actual != expectedVersion) {
            throw FuzzInfrastructureException(
                "wasm3 version mismatch: expected $expectedVersion, found $actual",
            )
        }
        return actual
    }
}

internal object Wasm3OutputParser {
    private val version = Regex("""^\s*wasm3\s+v?([0-9]+\.[0-9]+\.[0-9]+)(?:\s.*)?$""", RegexOption.IGNORE_CASE)

    fun parseVersion(output: CapturedProcessOutput): String {
        requireInfrastructureCompletion(output, "wasm3 --version")
        if (output.exitCode != 0) {
            throw FuzzInfrastructureException(
                "wasm3 --version failed: ${stableProcessDiagnostic(output)}",
            )
        }
        val line = output.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
            ?: throw FuzzInfrastructureException("wasm3 --version produced no output")
        return version.matchEntire(line)?.groupValues?.get(1)
            ?: throw FuzzInfrastructureException(
                "unrecognized wasm3 version output: '${line.take(256)}'",
            )
    }

    /**
     * wasm3 prints scalar results to **stderr** (not stdout) with the prefix
     * `Result: ` via its `repl_call` path (`platforms/app/main.c`). Multi-value
     * results are comma-separated on a single line. Traps appear as
     * `[trap] <text>` on stderr; decode/validation/link errors use other shapes.
     */
    fun parseInvocation(
        output: CapturedProcessOutput,
        resultTypes: List<String>,
    ): DifferentialResult {
        requireInfrastructureCompletion(output, "wasm3 invocation")
        if (output.exitCode == 0) {
            // wasm3 writes `Result: <v>` per result line on stderr; stdout is
            // unused by --func invocations and any non-empty line there is
            // treated as infrastructure noise.
            if (output.stdout.isNotBlank()) {
                throw FuzzInfrastructureException(
                    "wasm3 wrote unexpected stdout: ${output.stdout.take(512)}",
                )
            }
            val resultLines = output.stderr.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("Result:") }
                .toList()
            // Each emitted result line may carry multiple comma-separated
            // values; flatten them preserving order.
            val values = resultLines.flatMap { line ->
                line.removePrefix("Result:").trim()
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
            if (values.size != resultTypes.size) {
                throw FuzzInfrastructureException(
                    "wasm3 returned ${values.size} value(s), expected ${resultTypes.size}: " +
                        output.stderr.take(512),
                )
            }
            return DifferentialResult.Returned(
                values.zip(resultTypes).map { (value, type) ->
                    normalizeReferenceScalar(type, value)
                },
            )
        }

        val diagnostic = (output.stderr + "\n" + output.stdout).lowercase()
        canonicalWasm3Trap(diagnostic)?.let { return DifferentialResult.Trapped(it) }
        val phase =
            when {
                "incompatible wasm binary version" in diagnostic ||
                    "invalid module" in diagnostic ||
                    "section" in diagnostic && "out of range" in diagnostic -> "decode"
                "missing required" in diagnostic ||
                    "validation" in diagnostic ||
                    "type mismatch" in diagnostic -> "validation"
                "unresolved import" in diagnostic ||
                    "import" in diagnostic && "not linked" in diagnostic -> "link"
                "module not loaded" in diagnostic -> "instantiation"
                "missing function" in diagnostic ||
                    "function not found" in diagnostic -> "export"
                else -> null
            }
        if (phase != null) return DifferentialResult.Rejected(phase)
        throw FuzzInfrastructureException(
            "unrecognized wasm3 failure (exit ${output.exitCode}): ${stableProcessDiagnostic(output)}",
        )
    }

    fun requireSuccessfulLoad(output: CapturedProcessOutput) {
        requireInfrastructureCompletion(output, "wasm3 load")
        if (output.exitCode != 0) {
            throw FuzzInfrastructureException(
                "pinned wasm3 rejected a supposedly valid generated module: " +
                    stableProcessDiagnostic(output),
            )
        }
    }
}

/**
 * Maps wasm3's `[trap] ...` strings to the canonical trap vocabulary shared
 * with [canonicalTrap]. Separate from the wasmtime-oriented [canonicalTrap]
 * overload because wasm3 uses stable, prefixed strings (`[trap] ...`) rather
 * than prose, so exact matching is preferred over substring heuristics.
 */
internal fun canonicalWasm3Trap(diagnostic: String): String? {
    val trapMarker = "[trap]"
    val trapIndex = diagnostic.indexOf(trapMarker)
    if (trapIndex < 0) return null
    val rest = diagnostic.substring(trapIndex + trapMarker.length).trim()
    return when {
        rest.startsWith("out of bounds memory access") -> "out_of_bounds_memory"
        rest.startsWith("integer divide by zero") -> "integer_divide_by_zero"
        rest.startsWith("integer overflow") -> "integer_overflow"
        rest.startsWith("invalid conversion to integer") -> "invalid_conversion_to_integer"
        rest.startsWith("indirect call type mismatch") -> "indirect_call_type_mismatch"
        rest.startsWith("undefined element") -> "undefined_element"
        rest.startsWith("null table element") -> "uninitialized_element"
        rest.startsWith("unreachable executed") -> "unreachable"
        rest.startsWith("stack overflow") -> "call_stack_exhausted"
        // program called exit/abort are wasm3-specific signals for WASI
        // proc_exit/abort; recorded as distinct canonicals so a divergence
        // on them is minimized as a real finding rather than an infra error.
        rest.startsWith("program called exit") -> "program_exit"
        rest.startsWith("program called abort") -> "program_abort"
        else -> null
    }
}

internal class NightlyFuzzGate(
    private val configuration: NightlyFuzzConfiguration,
) {
    suspend fun run(): NightlyFuzzSummary {
        val evidence = FuzzEvidenceStore(configuration.artifactDirectory)
        val rawReport =
            try {
                DecoderFuzzDriver.run(
                    seed = configuration.rawSeed,
                    iterations = configuration.rawIterations,
                    maximumInputBytes = configuration.rawMaximumInputBytes,
                    onUnexpectedFailure = { iteration, input, failure ->
                        evidence.writeFinding(
                            kind = "raw-decoder",
                            case = iteration,
                            extension = "bin",
                            input = input,
                            metadata = linkedMapOf(
                                "seed" to configuration.rawSeed.toString(),
                                "iteration" to iteration.toString(),
                                "failure" to stableFailure(failure),
                            ),
                        )
                    },
                )
            } catch (failure: Exception) {
                evidence.writeRunFailure("raw decoder fuzz failed", failure)
                throw failure
            }

        val snapshotReport =
            try {
                SnapshotRobustnessDriver(configuration, evidence).run()
            } catch (failure: Exception) {
                evidence.writeRunFailure("snapshot robustness fuzz failed", failure)
                throw failure
            }

        val corpus = configuration.corpusDirectory
        if (corpus == null) {
            val summary = NightlyFuzzSummary(
                raw = rawReport,
                snapshots = snapshotReport,
                differentialModules = 0,
                differentialInvocations = 0,
                differentialDivergences = 0,
                modulesWithoutCallableExports = 0,
                wasmtimeVersion = null,
                wasm3Versions = emptyList(),
                corpusTruncated = false,
            )
            evidence.writeSummary(summary.describe())
            return summary
        }

        val corpusRoot =
            try {
                corpus.toRealPath()
            } catch (failure: Exception) {
                throw FuzzInfrastructureException(
                    "configured corpus does not exist or is unreadable: $corpus",
                    failure,
                )
            }
        require(Files.isDirectory(corpusRoot)) {
            "configured corpus is not a directory: $corpusRoot"
        }
        val modules = Files.walk(corpusRoot).use { paths ->
            paths.filter { path ->
                path.isRegularFile() && path.extension.equals("wasm", ignoreCase = true)
            }
                .map(Path::toRealPath)
                .peek { path ->
                    require(path.startsWith(corpusRoot)) {
                        "corpus module escapes configured root: $path"
                    }
                }
                .sorted(compareBy { corpusRoot.relativize(it).pathString.replace('\\', '/') })
                .toList()
        }
        require(modules.isNotEmpty()) {
            "configured differential corpus contains no .wasm modules: $corpusRoot"
        }

        val temporaryDirectory = Files.createTempDirectory("kwasm-reference-")
        var moduleCount = 0
        var invocationCount = 0
        var divergenceCount = 0
        var noCallableCount = 0
        val processRunner = BoundedProcessRunner()
        val wasmtime = WasmtimeDifferentialEngine(
            configuration,
            processRunner,
            temporaryDirectory,
        )
        val wasmtimeVersion =
            try {
                wasmtime.verifyVersion()
            } catch (failure: Exception) {
                evidence.writeRunFailure("reference runtime verification failed", failure)
                temporaryDirectory.toFile().deleteRecursively()
                throw failure
            }
        // Optional wasm3 oracle (primary + optional secondary build). When
        // absent the differential comparison stays kwasm-vs-wasmtime; when
        // present each configured build is version-verified before any module
        // runs, so a stale or mislabeled wasm3 binary fails fast.
        val wasm3Engines = linkedMapOf<String, DifferentialEngine>()
        val wasm3Versions: List<String> =
            try {
                val primaryPath = configuration.wasm3Executable
                if (primaryPath != null) {
                    val primary = Wasm3DifferentialEngine(
                        configuration,
                        processRunner,
                        temporaryDirectory,
                        executable = primaryPath,
                        expectedVersion = configuration.expectedWasm3Version,
                    )
                    val primaryVersion = primary.verifyVersion()
                    wasm3Engines["wasm3-$primaryVersion"] = primary
                    val secondaryPath = configuration.wasm3SecondaryExecutable
                    if (secondaryPath != null) {
                        val secondary = Wasm3DifferentialEngine(
                            configuration,
                            processRunner,
                            temporaryDirectory,
                            executable = secondaryPath,
                            expectedVersion = configuration.expectedWasm3SecondaryVersion,
                        )
                        val secondaryVersion = secondary.verifyVersion()
                        wasm3Engines["wasm3-$secondaryVersion"] = secondary
                        listOf(primaryVersion, secondaryVersion)
                    } else {
                        listOf(primaryVersion)
                    }
                } else {
                    emptyList()
                }
            } catch (failure: Exception) {
                evidence.writeRunFailure("wasm3 reference runtime verification failed", failure)
                temporaryDirectory.toFile().deleteRecursively()
                throw failure
            }
        val driver = DifferentialDriver(
            linkedMapOf(
                "kwasm" to KwasmDifferentialEngine(configuration),
                "wasmtime-$wasmtimeVersion" to wasmtime,
            ).apply { putAll(wasm3Engines) },
        )
        try {
            modules.take(configuration.maximumModules).forEachIndexed { moduleIndex, path ->
                val relative = corpusRoot.relativize(path).pathString.replace('\\', '/')
                val size = Files.size(path)
                require(size in 8..configuration.maximumModuleBytes.toLong()) {
                    "generated module '$relative' has $size bytes; allowed range is " +
                        "8..${configuration.maximumModuleBytes}"
                }
                val bytes = Files.readAllBytes(path)
                moduleCount++
                try {
                    wasmtime.requireValid(bytes)
                    val decoded =
                        try {
                            Module.decode(bytes, configuration.validationLimits)
                        } catch (_: WasmDecodeException) {
                            divergenceCount++
                            evidence.writeGeneratedModuleRejection(
                                moduleIndex,
                                relative,
                                bytes,
                                "decode",
                                wasmtimeVersion,
                            )
                            return@forEachIndexed
                        } catch (_: ValidationException) {
                            divergenceCount++
                            evidence.writeGeneratedModuleRejection(
                                moduleIndex,
                                relative,
                                bytes,
                                "validation",
                                wasmtimeVersion,
                            )
                            return@forEachIndexed
                        }
                    require(decoded.imports.isEmpty()) {
                        "generated module '$relative' has imports; the differential corpus " +
                            "must be generated with max-imports=0"
                    }
                    val invocations = selectScalarInvocations(
                        decoded,
                        configuration.maximumInvocationsPerModule,
                        moduleIndex,
                    )
                    if (invocations.isEmpty()) {
                        noCallableCount++
                        if (configuration.requireCallableExports) {
                            evidence.writeFinding(
                                kind = "no-callable-export",
                                case = moduleIndex,
                                extension = "wasm",
                                input = bytes,
                                metadata = linkedMapOf(
                                    "corpusPath" to relative,
                                    "moduleIndex" to moduleIndex.toString(),
                                ),
                            )
                            throw FuzzInfrastructureException(
                                "generated module '$relative' has no callable scalar export",
                            )
                        }
                    }
                    invocations.forEach { invocation ->
                        invocationCount++
                        val divergence = driver.compare(bytes, invocation) ?: return@forEach
                        divergenceCount++
                        var attempts = 0
                        val minimized =
                            if (configuration.minimizationAttempts == 0) {
                                bytes
                            } else {
                                driver.minimize(bytes) { candidate ->
                                    if (attempts >= configuration.minimizationAttempts) {
                                        false
                                    } else {
                                        attempts++
                                        try {
                                            driver.compare(candidate, invocation)?.outcomes ==
                                                divergence.outcomes
                                        } catch (_: Exception) {
                                            false
                                        }
                                    }
                                }
                            }
                        evidence.writeDivergence(
                            moduleIndex = moduleIndex,
                            corpusPath = relative,
                            divergence = divergence,
                            minimized = minimized,
                            minimizationAttempts = attempts,
                            wasmtimeVersion = wasmtimeVersion,
                        )
                    }
                } catch (failure: Exception) {
                    evidence.writeFinding(
                        kind = "differential-infrastructure",
                        case = moduleIndex,
                        extension = "wasm",
                        input = bytes,
                        metadata = linkedMapOf(
                            "corpusPath" to relative,
                            "moduleIndex" to moduleIndex.toString(),
                            "failure" to stableFailure(failure),
                        ),
                    )
                    throw failure
                }
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }

        val summary = NightlyFuzzSummary(
            raw = rawReport,
            snapshots = snapshotReport,
            differentialModules = moduleCount,
            differentialInvocations = invocationCount,
            differentialDivergences = divergenceCount,
            modulesWithoutCallableExports = noCallableCount,
            wasmtimeVersion = wasmtimeVersion,
            wasm3Versions = wasm3Versions,
            corpusTruncated = modules.size > configuration.maximumModules,
        )
        evidence.writeSummary(summary.describe())
        check(divergenceCount == 0) {
            "differential fuzz found $divergenceCount divergence(s); " +
                "evidence is in ${configuration.artifactDirectory.toAbsolutePath()}"
        }
        return summary
    }
}

internal data class NightlyFuzzSummary(
    val raw: DecoderFuzzReport,
    val snapshots: SnapshotRobustnessReport,
    val differentialModules: Int,
    val differentialInvocations: Int,
    val differentialDivergences: Int,
    val modulesWithoutCallableExports: Int,
    val wasmtimeVersion: String?,
    val wasm3Versions: List<String> = emptyList(),
    val corpusTruncated: Boolean,
) {
    fun describe(): String =
        buildString {
            appendLine(
                "kwasm raw decoder fuzz: seed=${raw.seed}, iterations=${raw.iterations}, " +
                    "decoded=${raw.decoded}, decodeRejected=${raw.rejectedAtDecode}, " +
                    "validationRejected=${raw.rejectedAtValidation}",
            )
            appendLine(snapshots.describe())
            if (wasmtimeVersion == null) {
                append("kwasm differential fuzz: not configured")
            } else {
                val wasm3Part =
                    if (wasm3Versions.isEmpty()) ""
                    else ", wasm3=${wasm3Versions.joinToString(",")}"
                append(
                    "kwasm differential fuzz: wasmtime=$wasmtimeVersion$wasm3Part, " +
                        "modules=$differentialModules, invocations=$differentialInvocations, " +
                        "divergences=$differentialDivergences, " +
                        "withoutCallableExports=$modulesWithoutCallableExports, " +
                        "corpusTruncated=$corpusTruncated",
                )
            }
        }.trimEnd()
}

internal class FuzzEvidenceStore(private val root: Path) {
    init {
        Files.createDirectories(root)
    }

    fun writeFinding(
        kind: String,
        case: Int,
        extension: String,
        input: ByteArray,
        metadata: LinkedHashMap<String, String>,
    ) {
        val digest = sha256Hex(input)
        val directory = root.resolve("${safeName(kind)}-${digest.take(16)}-case-$case")
        Files.createDirectories(directory)
        Files.write(
            directory.resolve("input.$extension"),
            input,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        writeMetadata(
            directory.resolve("metadata.json"),
            linkedMapOf(
                "kind" to kind,
                "sha256" to digest,
            ) + metadata,
        )
    }

    fun writeGeneratedModuleRejection(
        moduleIndex: Int,
        corpusPath: String,
        module: ByteArray,
        phase: String,
        wasmtimeVersion: String,
    ) {
        writeFinding(
            kind = "generated-module-rejection",
            case = moduleIndex,
            extension = "wasm",
            input = module,
            metadata = linkedMapOf(
                "corpusPath" to corpusPath,
                "moduleIndex" to moduleIndex.toString(),
                "kwasm" to "rejected:$phase",
                "reference" to "wasmtime-$wasmtimeVersion:accepted",
            ),
        )
    }

    fun writeDivergence(
        moduleIndex: Int,
        corpusPath: String,
        divergence: DifferentialDivergence,
        minimized: ByteArray,
        minimizationAttempts: Int,
        wasmtimeVersion: String,
    ) {
        val digest = sha256Hex(divergence.module)
        val directory = root.resolve(
            "differential-${digest.take(16)}-${safeName(divergence.invocation.export)}",
        )
        Files.createDirectories(directory)
        Files.write(directory.resolve("original.wasm"), divergence.module)
        if (!minimized.contentEquals(divergence.module)) {
            Files.write(directory.resolve("minimized.wasm"), minimized)
        }
        val outcomes = divergence.outcomes.entries.joinToString("; ") { (engine, outcome) ->
            "$engine=${renderOutcome(outcome)}"
        }
        writeMetadata(
            directory.resolve("metadata.json"),
            linkedMapOf(
                "kind" to "differential",
                "moduleIndex" to moduleIndex.toString(),
                "corpusPath" to corpusPath,
                "sha256" to digest,
                "export" to divergence.invocation.export,
                "arguments" to divergence.invocation.arguments.joinToString(","),
                "resultTypes" to divergence.invocation.resultTypes.joinToString(","),
                "outcomes" to outcomes,
                "wasmtimeVersion" to wasmtimeVersion,
                "minimizationAttempts" to minimizationAttempts.toString(),
                "minimizedBytes" to minimized.size.toString(),
            ),
        )
    }

    fun writeRunFailure(context: String, failure: Throwable) {
        writeMetadata(
            root.resolve("run-failure.json"),
            linkedMapOf(
                "context" to context,
                "failure" to stableFailure(failure),
            ),
        )
    }

    fun writeSummary(summary: String) {
        Files.writeString(
            root.resolve("summary.txt"),
            summary + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun writeMetadata(path: Path, metadata: Map<String, String>) {
        val json = metadata.entries.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n}\n",
        ) { (key, value) ->
            "  \"${jsonEscape(key)}\": \"${jsonEscape(value)}\""
        }
        Files.writeString(
            path,
            json,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}

internal class FuzzInfrastructureException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private fun wasmtimeLimits(configuration: NightlyFuzzConfiguration): String =
    "fuel=${configuration.executionFuel}," +
        "timeout=${configuration.processTimeoutMillis}ms," +
        "max-memory-size=${configuration.maximumRuntimeMemoryBytes}," +
        "max-table-elements=${configuration.maximumTableElements}"

internal fun selectScalarInvocations(
    module: Module,
    maximum: Int,
    caseIndex: Int,
): List<DifferentialInvocation> =
    module.exports.asSequence()
        .sortedBy { it.name }
        .mapNotNull { export ->
            val function = export.desc as? ExportDesc.Function ?: return@mapNotNull null
            val type = module.functionType(function.index)
            if (!type.params.all(::isScalarType) || !type.results.all(::isScalarType)) {
                return@mapNotNull null
            }
            DifferentialInvocation(
                export = export.name,
                arguments = type.params.mapIndexed { parameterIndex, valueType ->
                    deterministicArgument(valueType, caseIndex + parameterIndex)
                },
                resultTypes = type.results.map(::scalarTypeName),
            )
        }
        .take(maximum)
        .toList()

private fun deterministicArgument(type: ValType, selector: Int): String =
    when (type) {
        ValType.I32 -> listOf("0", "1", "-1", "2147483647")[selector and 3]
        ValType.I64 -> listOf("0", "1", "-1", "9223372036854775807")[selector and 3]
        ValType.F32 -> listOf("0", "-0", "1.5", "-2.25")[selector and 3]
        ValType.F64 -> listOf("0", "-0", "1.5", "-2.25")[selector and 3]
        else -> error("non-scalar invocation parameter $type")
    }

private fun isScalarType(type: ValType): Boolean =
    type == ValType.I32 || type == ValType.I64 || type == ValType.F32 || type == ValType.F64

internal fun scalarTypeName(type: ValType): String =
    when (type) {
        ValType.I32 -> "i32"
        ValType.I64 -> "i64"
        ValType.F32 -> "f32"
        ValType.F64 -> "f64"
        else -> throw FuzzInfrastructureException("unsupported differential scalar type '$type'")
    }

private fun parseKwasmArgument(type: ValType, source: String): Value =
    when (type) {
        ValType.I32 -> Value.I32(source.toInt())
        ValType.I64 -> Value.I64(source.toLong())
        ValType.F32 -> Value.F32(source.toFloat())
        ValType.F64 -> Value.F64(source.toDouble())
        else -> throw FuzzInfrastructureException("unsupported kwasm argument type '$type'")
    }

internal fun normalizeKwasmValue(value: Value): String =
    when (value) {
        is Value.I32 -> "i32:${value.v}"
        is Value.I64 -> "i64:${value.v}"
        is Value.F32 -> normalizeF32(value.v)
        is Value.F64 -> normalizeF64(value.v)
        else -> throw FuzzInfrastructureException(
            "selected scalar export returned unsupported value '$value'",
        )
    }

private fun normalizeF32(value: Float): String =
    if (value.isNaN()) {
        "f32:nan"
    } else {
        "f32:0x${value.toRawBits().toUInt().toString(16).padStart(8, '0')}"
    }

private fun normalizeF64(value: Double): String =
    if (value.isNaN()) {
        "f64:nan"
    } else {
        "f64:0x${value.toRawBits().toULong().toString(16).padStart(16, '0')}"
    }

/**
 * Shared infrastructure checks used by every reference CLI parser
 * ([WasmtimeOutputParser], [Wasm3OutputParser]). Ensures the subprocess
 * neither timed out nor overflowed the captured-output budget before any
 * caller inspects its streams.
 */
internal fun requireInfrastructureCompletion(output: CapturedProcessOutput, operation: String) {
    if (output.timedOut) {
        throw FuzzInfrastructureException("$operation exceeded its host process timeout")
    }
    if (output.stdoutTruncated || output.stderrTruncated) {
        throw FuzzInfrastructureException("$operation exceeded its captured-output limit")
    }
}

/** Stable, path- and backtrace-free one-line summary of a captured CLI failure. */
internal fun stableProcessDiagnostic(output: CapturedProcessOutput): String =
    (output.stderr.ifBlank { output.stdout })
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(8)
        .joinToString(" | ")
        .take(1_024)

/**
 * Normalizes a textual scalar emitted by a reference CLI (wasmtime or wasm3)
 * into the canonical `type:bitpattern` form used for cross-engine comparison.
 * Shared so that both engines go through identical parsing/normalization.
 */
internal fun normalizeReferenceScalar(type: String, source: String): String =
    when (type) {
        "i32" -> "i32:${source.toInt()}"
        "i64" -> "i64:${source.toLong()}"
        "f32" -> normalizeF32(parseF32(source))
        "f64" -> normalizeF64(parseF64(source))
        else -> throw FuzzInfrastructureException("unsupported reference result type '$type'")
    }

internal fun parseF32(source: String): Float =
    when (source.lowercase()) {
        "nan", "+nan", "-nan" -> Float.NaN
        "inf", "+inf", "infinity", "+infinity" -> Float.POSITIVE_INFINITY
        "-inf", "-infinity" -> Float.NEGATIVE_INFINITY
        else -> source.toFloat()
    }

internal fun parseF64(source: String): Double =
    when (source.lowercase()) {
        "nan", "+nan", "-nan" -> Double.NaN
        "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
        "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
        else -> source.toDouble()
    }

internal fun canonicalTrap(kind: TrapKind): String =
    when (kind) {
        TrapKind.UNREACHABLE -> "unreachable"
        TrapKind.INTEGER_DIVIDE_BY_ZERO -> "integer_divide_by_zero"
        TrapKind.INTEGER_OVERFLOW -> "integer_overflow"
        TrapKind.INVALID_CONVERSION_TO_INTEGER -> "invalid_conversion_to_integer"
        TrapKind.OUT_OF_BOUNDS_MEMORY_ACCESS -> "out_of_bounds_memory"
        TrapKind.UNDEFINED_ELEMENT -> "undefined_element"
        TrapKind.UNINITIALIZED_ELEMENT,
        TrapKind.INDIRECT_CALL_NULL,
        -> "uninitialized_element"
        TrapKind.INDIRECT_CALL_TYPE_MISMATCH -> "indirect_call_type_mismatch"
        TrapKind.NULL_FUNCTION_REFERENCE -> "null_function_reference"
        TrapKind.NULL_REFERENCE -> "null_reference"
        TrapKind.CAST_FAILURE -> "cast_failure"
        TrapKind.ARRAY_OUT_OF_BOUNDS -> "array_out_of_bounds"
        TrapKind.TABLE_OUT_OF_BOUNDS,
        TrapKind.OUT_OF_BOUNDS_TABLE_ACCESS,
        -> "out_of_bounds_table"
        TrapKind.STACK_EXHAUSTED -> "stack_exhausted"
        TrapKind.CALL_STACK_EXHAUSTED -> "call_stack_exhausted"
        TrapKind.OUT_OF_FUEL -> "execution_limit"
        TrapKind.UNREACHABLE_PARENT -> "invalid_runtime_state"
    }

private fun canonicalTrap(diagnostic: String): String? =
    when {
        "all fuel consumed" in diagnostic ||
            "out of fuel" in diagnostic ||
            "epoch deadline" in diagnostic ||
            "timeout exceeded" in diagnostic -> "execution_limit"
        "integer divide by zero" in diagnostic -> "integer_divide_by_zero"
        "integer overflow" in diagnostic -> "integer_overflow"
        "invalid conversion to integer" in diagnostic -> "invalid_conversion_to_integer"
        "out of bounds memory access" in diagnostic ||
            "memory out of bounds" in diagnostic -> "out_of_bounds_memory"
        "out of bounds table access" in diagnostic ||
            "table out of bounds" in diagnostic -> "out_of_bounds_table"
        "uninitialized element" in diagnostic -> "uninitialized_element"
        "indirect call type mismatch" in diagnostic -> "indirect_call_type_mismatch"
        "null function reference" in diagnostic -> "null_function_reference"
        "null reference" in diagnostic -> "null_reference"
        "cast failure" in diagnostic -> "cast_failure"
        "call stack exhausted" in diagnostic ||
            "call stack overflow" in diagnostic ||
            "wasm stack overflow" in diagnostic -> "call_stack_exhausted"
        "unreachable" in diagnostic && "wasm trap" in diagnostic -> "unreachable"
        "uncaught exception" in diagnostic -> "uncaught_exception"
        else -> null
    }

private fun renderOutcome(outcome: DifferentialResult): String =
    when (outcome) {
        is DifferentialResult.Returned -> "returned:${outcome.values.joinToString(",")}"
        is DifferentialResult.Trapped -> "trapped:${outcome.kind}"
        is DifferentialResult.Rejected -> "rejected:${outcome.phase}"
        is DifferentialResult.Abstained -> "abstained:${outcome.reason}"
    }

private fun stableFailure(failure: Throwable): String =
    "${failure::class.qualifiedName}: ${failure.message.orEmpty()}".take(2_048)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

private fun safeName(source: String): String =
    source.map { character ->
        if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
    }.joinToString("").take(80).ifBlank { "unnamed" }

private fun jsonEscape(source: String): String =
    buildString(source.length + 16) {
        source.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
    }
