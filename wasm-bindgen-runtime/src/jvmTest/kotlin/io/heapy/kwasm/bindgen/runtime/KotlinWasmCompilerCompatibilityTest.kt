package io.heapy.kwasm.bindgen.runtime

import io.heapy.kwasm.ExternalValue
import io.heapy.kwasm.ImportDesc
import io.heapy.kwasm.Instr
import io.heapy.kwasm.Linker
import io.heapy.kwasm.Module
import io.heapy.kwasm.ModuleValidationLimits
import io.heapy.kwasm.Store
import io.heapy.kwasm.Value
import io.heapy.kwasm.bindgen.WasmAbiArguments
import io.heapy.kwasm.bindgen.WasmAbiCodec
import io.heapy.kwasm.bindgen.WasmAbiValue
import io.heapy.kwasm.bindgen.WasmGuestRuntimeAbi
import io.heapy.kwasm.bindgen.WasmImportBinding
import io.heapy.kwasm.wasi.BufferWasiOutput
import io.heapy.kwasm.wasi.WASI_SNAPSHOT_PREVIEW1
import io.heapy.kwasm.wasi.WasiConfig
import io.heapy.kwasm.wasi.WasiPreview1
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinWasmCompilerCompatibilityTest {
    @Test
    fun linkedCompilerOutputsDecodeValidateInstantiateAndExecute() = runBlocking {
        val binaries = checkNotNull(
            System.getProperty(COMPATIBILITY_BINARIES_PROPERTY),
        ) {
            "$COMPATIBILITY_BINARIES_PROPERTY was not configured"
        }.split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::File)
        val versions = requiredProperty(COMPATIBILITY_VERSIONS_PROPERTY)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
        val requiredVersions = requiredProperty(REQUIRED_VERSIONS_PROPERTY)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)

        assertTrue(binaries.isNotEmpty(), "at least the current compiler output is required")
        assertEquals(
            binaries.size,
            versions.size,
            "every Kotlin/Wasm compatibility binary must have one compiler-version label",
        )
        assertEquals(
            versions.size,
            versions.toSet().size,
            "Kotlin/Wasm compiler-version rows must be unique",
        )
        assertEquals(
            requiredVersions,
            versions,
            "configured Kotlin/Wasm rows do not match the required compiler gate",
        )
        assertEquals(
            binaries.size,
            binaries.map { it.canonicalFile }.toSet().size,
            "Kotlin/Wasm compiler rows must use distinct binary files",
        )

        val executedVersions = mutableListOf<String>()
        versions.zip(binaries).forEach { (version, binary) ->
            executeCompilerOutput(CompilerOutput(version, binary))
            executedVersions += version
            println("$PASS_MARKER compiler=$version")
        }
        assertEquals(requiredVersions, executedVersions)
    }

    private suspend fun executeCompilerOutput(output: CompilerOutput) {
        val version = output.compilerVersion
        val binary = output.binary
        assertTrue(binary.isFile, "Kotlin/Wasm compatibility binary is missing: $binary")

        // Module.decode performs both binary decoding and the pure validation
        // phase, so no unvalidated compiler output reaches instantiation.
        val module = Module.decode(
            binary.readBytes(),
            ModuleValidationLimits(allowInertV128Types = true),
        )
        val legacyExceptionInstructions =
            module.countInstructions { it is Instr.LegacyTry }
        val standardizedExceptionInstructions =
            module.countInstructions { it is Instr.TryTable }
        when {
            version.endsWith(LEGACY_EH_SUFFIX) -> assertTrue(
                legacyExceptionInstructions > 0,
                "Kotlin $version output did not contain the required legacy EH encoding " +
                    "(legacy=$legacyExceptionInstructions, " +
                    "standardized=$standardizedExceptionInstructions)",
            )
            version.endsWith(NEW_EH_SUFFIX) -> assertTrue(
                standardizedExceptionInstructions > 0,
                "Kotlin $version output did not contain the standardized EH encoding " +
                    "(legacy=$legacyExceptionInstructions, " +
                    "standardized=$standardizedExceptionInstructions)",
            )
        }
        val standardOutput = BufferWasiOutput()
        val standardError = BufferWasiOutput()
        val wasi = WasiPreview1(
            WasiConfig(
                arguments = listOf(binary.name),
                standardOutput = standardOutput,
                standardError = standardError,
            ),
        )
        var bindgenCalls = 0
        val bindgen = KwasmBindgenHostImportRegistry(
            listOf(
                WasmImportBinding(
                    boundary = "test:wasm-compiler",
                    function = "add",
                    isSuspend = false,
                ) { payload ->
                    val arguments = WasmAbiArguments(WasmAbiCodec.decode(payload))
                    arguments.requireSize(2)
                    bindgenCalls += 1
                    WasmAbiCodec.encode(
                        WasmAbiValue.Int32(
                            arguments.int32(0) + arguments.int32(1),
                        ),
                    )
                },
            ),
        )
        val linker = Linker()
        module.imports
            .filter { it.module == WASI_SNAPSHOT_PREVIEW1 }
            .distinctBy { it.field }
            .forEach { import ->
                check(import.desc is ImportDesc.Function) {
                    "Kotlin/Wasm imported non-function WASI value '${import.field}'"
                }
                linker.define(
                    import.module,
                    import.field,
                    ExternalValue.Function(wasi.hostImport(import.field)),
                )
            }
        bindgen.define(linker)

        val supportedModules = setOf(
            WASI_SNAPSHOT_PREVIEW1,
            WasmGuestRuntimeAbi.HOST_IMPORT_MODULE,
        )
        assertEquals(
            emptySet(),
            module.imports.mapTo(mutableSetOf()) { it.module } - supportedModules,
            "Kotlin $version output introduced an unsupported import module",
        )

        val instance = linker.instantiate(module, Store())
        wasi.attach(instance)
        instance.runStart()
        assertEquals(emptyList(), instance.invoke("startUnitTests"))

        val testOutput = standardOutput.text() + standardError.text()
        assertTrue(
            testOutput.contains("WasmAbiCodecTest"),
            "Kotlin $version test runner did not report the known ABI suite:\n$testOutput",
        )
        assertTrue(
            testOutput.contains(COMPATIBILITY_CORPUS_SUITE),
            "Kotlin $version test runner did not execute the TCK-5 corpus:\n$testOutput",
        )
        assertTrue(
            testOutput.contains(HELLO_MARKER),
            "Kotlin $version did not execute the wasmWasi hello-world probe:\n$testOutput",
        )
        assertTrue(
            testOutput.contains("testFinished"),
            "Kotlin $version test runner produced no completed test event:\n$testOutput",
        )
        assertFalse(
            testOutput.contains("testFailed"),
            "Kotlin $version Wasm test suite reported a failure:\n$testOutput",
        )

        assertEquals(
            listOf(Value.I32(42)),
            instance.invoke(HOST_ROUND_TRIP_EXPORT),
            "Kotlin $version guest begin/finish transport did not return the host result",
        )
        assertEquals(
            1,
            bindgenCalls,
            "Kotlin $version compatibility probe must cross the host boundary once",
        )
    }

    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)) { "$name was not configured" }

    private data class CompilerOutput(
        val compilerVersion: String,
        val binary: File,
    )

    private companion object {
        const val COMPATIBILITY_BINARIES_PROPERTY: String =
            "kwasm.kotlin.wasm.compatibility.binaries"
        const val COMPATIBILITY_VERSIONS_PROPERTY: String =
            "kwasm.kotlin.wasm.compatibility.versions"
        const val REQUIRED_VERSIONS_PROPERTY: String =
            "kwasm.kotlin.wasm.compatibility.required.versions"
        const val PASS_MARKER: String =
            "KWASM_KOTLIN_WASM_COMPATIBILITY_PASS"
        const val HOST_ROUND_TRIP_EXPORT: String =
            "__kwasm_bindgen_host_round_trip_v1"
        const val LEGACY_EH_SUFFIX: String = "-legacy-eh"
        const val NEW_EH_SUFFIX: String = "-new-eh"
        const val COMPATIBILITY_CORPUS_SUITE: String =
            "KotlinWasmCompatibilityCorpusTest"
        const val HELLO_MARKER: String =
            "KWASM_KOTLIN_WASM_WASI_HELLO"
    }
}

private fun Module.countInstructions(predicate: (Instr) -> Boolean): Int =
    functions.sumOf { function ->
        function.body.sumOf { instruction -> instruction.countInstructions(predicate) }
    }

private fun Instr.countInstructions(predicate: (Instr) -> Boolean): Int {
    val nested =
        when (this) {
            is Instr.Block -> body.sumOf { it.countInstructions(predicate) }
            is Instr.Loop -> body.sumOf { it.countInstructions(predicate) }
            is Instr.If ->
                thenBody.sumOf { it.countInstructions(predicate) } +
                    elseBody.sumOf { it.countInstructions(predicate) }
            is Instr.TryTable -> body.sumOf { it.countInstructions(predicate) }
            is Instr.LegacyTry ->
                body.sumOf { it.countInstructions(predicate) } +
                    catches.sumOf { catcher ->
                        catcher.body.sumOf { it.countInstructions(predicate) }
                    } +
                    catchAll.orEmpty().sumOf { it.countInstructions(predicate) }
            else -> 0
        }
    return if (predicate(this)) nested + 1 else nested
}
