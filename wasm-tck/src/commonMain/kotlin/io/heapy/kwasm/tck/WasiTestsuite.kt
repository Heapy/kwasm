package io.heapy.kwasm.tck

import io.heapy.kwasm.Interpreter
import io.heapy.kwasm.Machine
import io.heapy.kwasm.Module
import io.heapy.kwasm.Store
import io.heapy.kwasm.wasi.BufferWasiOutput
import io.heapy.kwasm.wasi.FixedWasiClock
import io.heapy.kwasm.wasi.InMemoryFileSystem
import io.heapy.kwasm.wasi.WasiConfig
import io.heapy.kwasm.wasi.WasiPreopen
import io.heapy.kwasm.wasi.WasiPreview1
import io.heapy.kwasm.wasi.WasiProcessExit
import io.heapy.kwasm.wasi.WasiRandom
import kotlinx.coroutines.CancellationException

/** Metadata for one Preview 1 binary in the official WASI testsuite. */
public data class WasiTestsuiteSpec(
    public val arguments: List<String> = emptyList(),
    public val environment: Map<String, String> = emptyMap(),
    public val root: String? = null,
    public val expectedExitCode: UInt = 0u,
    public val expectedStdout: String? = null,
    public val expectedStderr: String? = null,
) {
    init {
        root?.let(::validateRelativeRoot)
    }

    public companion object {
        /** Parse an optional same-basename JSON specification from wasi-testsuite. */
        public fun parse(json: String?): WasiTestsuiteSpec =
            if (json == null) WasiTestsuiteSpec() else WasiTestsuiteJson.decode(Json.parse(json))
    }
}

/** One regular file in a deterministic preopen image. */
public class WasiTestsuiteFile(
    public val path: String,
    sourceBytes: ByteArray,
) {
    private val sourceBytes: ByteArray = sourceBytes.copyOf()

    init {
        validateImagePath(path, "file")
    }

    public fun bytes(): ByteArray = sourceBytes.copyOf()
}

/**
 * Immutable input used to build a fresh in-memory preopen for every execution.
 *
 * Empty directories are retained explicitly. Symlinks and special host files
 * are intentionally not representable, so fixture discovery fails closed.
 */
public class WasiTestsuitePreopenImage(
    directories: List<String> = emptyList(),
    files: List<WasiTestsuiteFile> = emptyList(),
) {
    public val directories: List<String> = directories.toList()
    public val files: List<WasiTestsuiteFile> = files.toList()

    init {
        directories.forEach { validateImagePath(it, "directory") }
        val duplicateDirectory = directories.groupBy(::normalizePath).entries
            .firstOrNull { it.value.size > 1 }
        require(duplicateDirectory == null) {
            "duplicate preopen directory '${duplicateDirectory?.key}'"
        }
        val duplicateFile = files.groupBy { normalizePath(it.path) }.entries
            .firstOrNull { it.value.size > 1 }
        require(duplicateFile == null) {
            "duplicate preopen file '${duplicateFile?.key}'"
        }
    }

    internal fun materialize(): InMemoryFileSystem =
        InMemoryFileSystem().also { fileSystem ->
            directories
                .sortedWith(compareBy<String>({ normalizePath(it).count { char -> char == '/' } }, ::normalizePath))
                .forEach(fileSystem::createDirectories)
            files.sortedBy { normalizePath(it.path) }.forEach { file ->
                fileSystem.writeFile(file.path, file.bytes())
            }
        }
}

/** Complete, platform-neutral input for one official Preview 1 test. */
public class WasiTestsuiteCase(
    /** Stable slash-separated path relative to the configured suite root. */
    public val id: String,
    moduleBytes: ByteArray,
    public val spec: WasiTestsuiteSpec = WasiTestsuiteSpec(),
    public val preopen: WasiTestsuitePreopenImage? = null,
) {
    private val moduleBytes: ByteArray = moduleBytes.copyOf()

    init {
        require(id.endsWith(".wasm")) { "WASI testsuite case id must end in .wasm: '$id'" }
        validateImagePath(id, "case")
        require((spec.root == null) == (preopen == null)) {
            "case '$id' must provide a preopen image exactly when its spec declares root"
        }
    }

    public fun wasmBytes(): ByteArray = moduleBytes.copyOf()
}

public enum class WasiTestsuiteOutcome { Passed, Failed, Excluded }

/** Observable process result retained even when an expectation fails. */
public data class WasiTestsuiteExecution(
    public val exitCode: UInt,
    public val stdout: String,
    public val stderr: String,
)

/** Result for one official WASI testsuite binary. */
public data class WasiTestsuiteResult(
    public val caseId: String,
    public val outcome: WasiTestsuiteOutcome,
    public val execution: WasiTestsuiteExecution? = null,
    public val failures: List<String> = emptyList(),
    public val exclusion: WasiTestsuiteExclusion? = null,
) {
    public fun requireSuccess() {
        if (outcome != WasiTestsuiteOutcome.Failed) return
        throw WasiTestsuiteRunException(
            buildString {
                append("$caseId failed")
                failures.forEach { append("\n  $it") }
            },
            this,
        )
    }
}

public class WasiTestsuiteRunException(
    message: String,
    public val result: WasiTestsuiteResult,
) : AssertionError(message)

/**
 * Executes official wasi-testsuite Preview 1 binaries without a Python or
 * subprocess dependency. Each call creates a fresh store, host, streams,
 * deterministic clock/random source, and preopen filesystem.
 */
public class WasiTestsuiteRunner(
    private val exclusions: WasiTestsuiteExclusions = WasiTestsuiteExclusions.Empty,
    private val machine: Machine = Interpreter(),
    private val storeFactory: () -> Store = ::Store,
) {
    public suspend fun run(testCase: WasiTestsuiteCase): WasiTestsuiteResult {
        val exclusion = exclusions.find(testCase.id)
        if (exclusion != null) {
            return WasiTestsuiteResult(
                caseId = testCase.id,
                outcome = WasiTestsuiteOutcome.Excluded,
                failures = listOf("excluded by ${exclusion.issueUrl}"),
                exclusion = exclusion,
            )
        }

        val stdout = BufferWasiOutput()
        val stderr = BufferWasiOutput()
        val preopens = testCase.preopen?.let { image ->
            listOf(WasiPreopen("/", image.materialize().root))
        }.orEmpty()
        val wasi = WasiPreview1(
            WasiConfig(
                // WASI command runtimes expose the module name as argv[0].
                arguments = listOf(testCase.id) + testCase.spec.arguments,
                environment = testCase.spec.environment,
                preopens = preopens,
                standardOutput = stdout,
                standardError = stderr,
                clock = FixedWasiClock(
                    realtimeNanos = 1_700_000_000_000_000_000uL,
                    monotonicNanos = 123_456_789uL,
                    resolution = 1uL,
                ),
                random = DeterministicWasiRandom(),
            ),
        )

        var executionFailure: Throwable?
        var exitCode: UInt
        try {
            val module = Module.decode(testCase.wasmBytes())
            val instance = wasi.instantiate(storeFactory(), module)
            instance.runStart(machine)
            val result = instance.invoke("_start", machine = machine)
            if (result.isNotEmpty()) {
                throw IllegalStateException("WASI _start returned ${result.size} value(s)")
            }
            exitCode = 0u
            executionFailure = null
        } catch (exit: WasiProcessExit) {
            exitCode = exit.exitCode
            executionFailure = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            exitCode = UInt.MAX_VALUE
            executionFailure = failure
        }

        val outputFailure = mutableListOf<String>()
        val stdoutText = decodeOutput("stdout", stdout.bytes(), outputFailure)
        val stderrText = decodeOutput("stderr", stderr.bytes(), outputFailure)
        val execution = WasiTestsuiteExecution(exitCode, stdoutText, stderrText)
        val failures = buildList {
            if (executionFailure != null) {
                add(
                    "execution failed with ${executionFailure::class.simpleName}: " +
                        (executionFailure.message ?: "no detail"),
                )
                if (stdoutText.isNotEmpty()) {
                    add("captured stdout: ${stdoutText.quoted()}")
                }
                if (stderrText.isNotEmpty()) {
                    add("captured stderr: ${stderrText.quoted()}")
                }
            } else if (exitCode != testCase.spec.expectedExitCode) {
                add("exit code: expected ${testCase.spec.expectedExitCode}, got $exitCode")
            }
            testCase.spec.expectedStdout?.let { expected ->
                if (stdoutText != expected) add("stdout: expected ${expected.quoted()}, got ${stdoutText.quoted()}")
            }
            testCase.spec.expectedStderr?.let { expected ->
                if (stderrText != expected) add("stderr: expected ${expected.quoted()}, got ${stderrText.quoted()}")
            }
            addAll(outputFailure)
        }
        return WasiTestsuiteResult(
            caseId = testCase.id,
            outcome = if (failures.isEmpty()) WasiTestsuiteOutcome.Passed else WasiTestsuiteOutcome.Failed,
            execution = execution,
            failures = failures,
        )
    }
}

/** One auditable skip in the official Preview 1 corpus. */
public data class WasiTestsuiteExclusion(
    public val caseId: String,
    public val issueUrl: String,
) {
    init {
        require(caseId.endsWith(".wasm")) { "WASI exclusion must name a .wasm fixture" }
        validateImagePath(caseId, "exclusion")
        require(issueUrl.startsWith("https://") && issueUrl.looksLikeIssueUrl()) {
            "WASI exclusion must carry an HTTPS issue URL"
        }
    }

    public fun matches(candidate: String): Boolean {
        val normalizedCandidate = normalizePath(candidate)
        val normalizedId = normalizePath(caseId)
        return normalizedCandidate == normalizedId || normalizedCandidate.endsWith("/$normalizedId")
    }
}

/** Strict checked-in exclusion manifest for official WASI testsuite fixtures. */
public class WasiTestsuiteExclusions private constructor(
    public val entries: List<WasiTestsuiteExclusion>,
) {
    public fun find(caseId: String): WasiTestsuiteExclusion? =
        entries.firstOrNull { it.matches(caseId) }

    /**
     * Rejects exclusions which do not select a fixture in the configured
     * corpus. This makes a removed or renamed upstream test a hard failure
     * instead of leaving a permanent, silently unused skip.
     */
    public fun requireAllMatch(caseIds: Collection<String>) {
        val stale = entries.filter { exclusion ->
            caseIds.none(exclusion::matches)
        }
        require(stale.isEmpty()) {
            "stale WASI testsuite exclusion(s): " +
                stale.joinToString { it.caseId }
        }
    }

    public companion object {
        public val Empty: WasiTestsuiteExclusions = WasiTestsuiteExclusions(emptyList())

        /**
         * Format: `<relative-fixture.wasm> <https issue URL>`.
         * Blank and `#` comment lines are ignored.
         */
        public fun parse(text: String): WasiTestsuiteExclusions {
            val entries = text.lines().mapIndexedNotNull { index, source ->
                val line = source.trim()
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
                val fields = line.split(Regex("\\s+")).filter(String::isNotEmpty)
                require(fields.size == 2) {
                    "WASI exclusion line ${index + 1} must be " +
                        "'<relative-fixture.wasm> <https-issue-url>'"
                }
                try {
                    WasiTestsuiteExclusion(fields[0], fields[1])
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "invalid WASI exclusion at line ${index + 1}: ${failure.message}",
                        failure,
                    )
                }
            }
            val duplicate = entries.groupBy { normalizePath(it.caseId) }.entries
                .firstOrNull { it.value.size > 1 }
            require(duplicate == null) {
                "duplicate WASI exclusion '${duplicate?.key}'"
            }
            return WasiTestsuiteExclusions(entries)
        }
    }
}

/** Aggregate all failures while retaining exclusions in a configured corpus run. */
public data class WasiTestsuiteReport(
    public val results: List<WasiTestsuiteResult>,
) {
    public val passed: Int get() = results.count { it.outcome == WasiTestsuiteOutcome.Passed }
    public val failed: Int get() = results.count { it.outcome == WasiTestsuiteOutcome.Failed }
    public val excluded: Int get() = results.count { it.outcome == WasiTestsuiteOutcome.Excluded }

    public fun description(): String =
        "kwasm official WASI Preview 1: ${results.size} fixture(s), " +
            "$passed passed, $failed failed, $excluded excluded"

    public fun requireSuccess() {
        if (failed == 0) return
        throw AssertionError(
            buildString {
                append("$failed/${results.size} WASI testsuite fixture(s) failed")
                results.filter { it.outcome == WasiTestsuiteOutcome.Failed }
                    .take(20)
                    .forEach { result ->
                        append("\n  ${result.caseId}: ${result.failures.joinToString("; ")}")
                    }
            },
        )
    }
}

private object WasiTestsuiteJson {
    private val legacyKeys = setOf("args", "root", "env", "exit_code", "stderr", "stdout")
    private val operationKeys = setOf("operations", "proposals", "world")

    fun decode(value: JsonValue): WasiTestsuiteSpec {
        val root = value.asObject("WASI test specification")
        val keys = root.entries.keys
        val usesOperations = keys.any { it in operationKeys }
        require(!(usesOperations && keys.any { it in legacyKeys })) {
            "WASI test specification cannot mix legacy and operation fields"
        }
        return if (usesOperations) decodeOperations(root) else decodeLegacy(root)
    }

    private fun decodeLegacy(root: JsonValue.Object): WasiTestsuiteSpec {
        root.requireOnlyKeys(legacyKeys)
        return WasiTestsuiteSpec(
            arguments = root.stringArray("args").orEmpty(),
            environment = root.stringMap("env").orEmpty(),
            root = root.optionalString("root"),
            expectedExitCode = root.uint("exit_code") ?: 0u,
            expectedStdout =
                if ("stdout" in root.entries) root.optionalString("stdout") else null,
            expectedStderr =
                if ("stderr" in root.entries) root.optionalString("stderr") else null,
        )
    }

    private fun decodeOperations(root: JsonValue.Object): WasiTestsuiteSpec {
        root.requireOnlyKeys(operationKeys)
        val proposals = root.stringArray("proposals").orEmpty()
        require(proposals.isEmpty()) {
            "WASI Preview 1 adapter does not support proposal operations: $proposals"
        }
        val world = root.optionalString("world")
        require(world == null || world == "wasi:cli/command") {
            "WASI Preview 1 adapter supports only the wasi:cli/command world"
        }
        val operations = root.array("operations").orEmpty()
        if (operations.isEmpty()) return WasiTestsuiteSpec()

        var run: JsonValue.Object? = null
        var wait: JsonValue.Object? = null
        var stdout: String? = null
        var stderr: String? = null
        operations.forEachIndexed { index, value ->
            val operation = value.asObject("operations[$index]")
            when (val type = operation.requiredString("type")) {
                "run" -> {
                    require(run == null && wait == null) { "run must occur once and before wait" }
                    operation.requireOnlyKeys(setOf("type", "args", "env", "root"))
                    run = operation
                }
                "read" -> {
                    require(run != null && wait == null) { "read must occur between run and wait" }
                    operation.requireOnlyKeys(setOf("type", "id", "payload"))
                    val id = operation.optionalString("id") ?: "stdout"
                    val payload = operation.optionalString("payload") ?: ""
                    when (id) {
                        "stdout" -> {
                            require(stdout == null) { "stdout may be asserted only once" }
                            stdout = payload
                        }
                        "stderr" -> {
                            require(stderr == null) { "stderr may be asserted only once" }
                            stderr = payload
                        }
                        else -> throw IllegalArgumentException(
                            "WASI Preview 1 adapter cannot read stream '$id'",
                        )
                    }
                }
                "wait" -> {
                    require(run != null && wait == null) { "wait must follow one run operation" }
                    operation.requireOnlyKeys(setOf("type", "exit_code"))
                    wait = operation
                }
                else -> throw IllegalArgumentException(
                    "WASI Preview 1 adapter does not support '$type' operations",
                )
            }
        }
        require(run != null && wait != null) { "operations must contain one run followed by one wait" }
        return WasiTestsuiteSpec(
            arguments = run.stringArray("args").orEmpty(),
            environment = run.stringMap("env").orEmpty(),
            root = run.optionalString("root"),
            expectedExitCode = wait.uint("exit_code") ?: 0u,
            expectedStdout = stdout,
            expectedStderr = stderr,
        )
    }
}

private class DeterministicWasiRandom : WasiRandom {
    private var state: UInt = 0x6D2B_79F5u

    override suspend fun fill(destination: ByteArray) {
        destination.indices.forEach { index ->
            var value = state
            value = value xor (value shl 13)
            value = value xor (value shr 17)
            value = value xor (value shl 5)
            state = value
            destination[index] = value.toByte()
        }
    }
}

private fun decodeOutput(
    name: String,
    bytes: ByteArray,
    failures: MutableList<String>,
): String =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        failures += "$name was not valid UTF-8 (${bytes.size} byte(s))"
        ""
    }

private fun validateRelativeRoot(path: String) {
    require(path.isNotEmpty()) { "WASI test root must not be empty" }
    if (path == ".") return
    validateImagePath(path, "root")
}

private fun validateImagePath(path: String, kind: String) {
    require(path.isNotEmpty()) { "$kind path must not be empty" }
    require('\u0000' !in path) { "$kind path must not contain NUL" }
    val normalized = normalizePath(path)
    require(!normalized.startsWith('/')) { "$kind path must be relative: '$path'" }
    require(!Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
        "$kind path must be relative: '$path'"
    }
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "$kind path must be normalized and confined: '$path'"
    }
}

private fun normalizePath(path: String): String = path.replace('\\', '/').removePrefix("./")

private fun String.looksLikeIssueUrl(): Boolean =
    contains("/issues/") || contains("/issue/") || contains("/browse/")

private fun String.quoted(): String =
    buildString {
        append('"')
        this@quoted.forEach { char ->
            when (char) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(char)
            }
        }
        append('"')
    }

private fun JsonValue.asObject(context: String): JsonValue.Object =
    this as? JsonValue.Object ?: throw IllegalArgumentException("$context must be an object")

private fun JsonValue.Object.requireOnlyKeys(allowed: Set<String>) {
    val unknown = entries.keys - allowed
    require(unknown.isEmpty()) { "unknown WASI test specification field(s): ${unknown.sorted()}" }
}

private fun JsonValue.Object.requiredString(name: String): String =
    optionalString(name) ?: throw IllegalArgumentException("missing string '$name'")

private fun JsonValue.Object.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.StringValue -> value.value
    else -> throw IllegalArgumentException("'$name' must be a string")
}

private fun JsonValue.Object.array(name: String): List<JsonValue>? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.Array -> value.values
    else -> throw IllegalArgumentException("'$name' must be an array")
}

private fun JsonValue.Object.stringArray(name: String): List<String>? =
    array(name)?.mapIndexed { index, value ->
        (value as? JsonValue.StringValue)?.value
            ?: throw IllegalArgumentException("'$name[$index]' must be a string")
    }

private fun JsonValue.Object.stringMap(name: String): Map<String, String>? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.Object -> value.entries.mapValues { (key, entry) ->
        (entry as? JsonValue.StringValue)?.value
            ?: throw IllegalArgumentException("'$name.$key' must be a string")
    }
    else -> throw IllegalArgumentException("'$name' must be an object")
}

private fun JsonValue.Object.uint(name: String): UInt? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.NumberValue -> value.source.toULongOrNull()
        ?.takeIf { it <= UInt.MAX_VALUE.toULong() }
        ?.toUInt()
        ?: throw IllegalArgumentException("'$name' must be an unsigned 32-bit integer")
    else -> throw IllegalArgumentException("'$name' must be a number")
}
