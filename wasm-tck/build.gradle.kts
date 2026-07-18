import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.io.ByteArrayOutputStream
import javax.inject.Inject

private data class ConverterExclusion(
    val script: String,
    val issueUrl: String,
)

private object ConverterExclusionManifest {
    private val windowsAbsolutePath = Regex("^[A-Za-z]:/")

    fun parse(file: File): List<ConverterExclusion> {
        val entries = file.readLines().mapIndexedNotNull { index, source ->
            val line = source.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split(Regex("\\s+")).filter(String::isNotEmpty)
            check(fields.size == 2) {
                "${file.path}:${index + 1}: expected '<relative-script.wast> <issue-url>'"
            }
            val script = fields[0]
            check(
                script.endsWith(".wast") &&
                    '\\' !in script &&
                    !script.startsWith("/") &&
                    !windowsAbsolutePath.containsMatchIn(script) &&
                    script.split('/').none { it.isEmpty() || it == "." || it == ".." },
            ) {
                "${file.path}:${index + 1}: converter exclusion must be a confined " +
                    "slash-separated .wast path without a command line"
            }
            val issueUrl = fields[1]
            check(
                issueUrl.startsWith("https://") &&
                    (
                        issueUrl.contains("/issues/") ||
                            issueUrl.contains("/issue/") ||
                            issueUrl.contains("/browse/")
                    ),
            ) {
                "${file.path}:${index + 1}: converter exclusion must carry an HTTPS issue link"
            }
            ConverterExclusion(script, issueUrl)
        }
        val duplicate = entries.groupBy(ConverterExclusion::script).entries
            .firstOrNull { it.value.size > 1 }
        check(duplicate == null) {
            "${file.path}: duplicate converter exclusion '${duplicate?.key}'"
        }
        check(entries.map(ConverterExclusion::script) == entries.map(ConverterExclusion::script).sorted()) {
            "${file.path}: converter exclusions must be sorted by script path"
        }
        return entries
    }

    fun requirePresent(
        entries: List<ConverterExclusion>,
        inputRoot: File,
    ): Map<String, File> {
        val scripts = inputRoot.walkTopDown()
            .filter { it.isFile && it.extension == "wast" }
            .associateBy { it.relativeTo(inputRoot).invariantSeparatorsPath }
        val stale = entries.filter { it.script !in scripts }
        check(stale.isEmpty()) {
            "Stale converter exclusion(s) do not name scripts in ${inputRoot.absolutePath}: " +
                stale.joinToString { it.script }
        }
        return scripts
    }
}

@CacheableTask
abstract class Wast2JsonTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val wast2jsonExecutable: Property<String>

    /**
     * Content fingerprint for explicitly supplied converter binaries. The
     * executable string remains supported for PATH lookup, while pinned CI
     * cannot reuse cached JSON produced by older bytes at the same path.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wast2jsonBinary: RegularFileProperty

    @get:Input
    abstract val wast2jsonArguments: ListProperty<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val converterExclusions: RegularFileProperty

    private val quotedInvalidModule = Regex(
        """\{"type":\s*"assert_invalid"[^\r\n]*"filename":\s*"([^"]+\.wat)"[^\r\n]*}""",
    )

    private val generatedModule = Regex(
        """"type":\s*"module"[^\r\n]*"filename":\s*"([^"]+\.wasm)"""",
    )

    @TaskAction
    fun convert() {
        val outputRoot = outputDirectory.get().asFile
        check(outputRoot.deleteRecursively() || !outputRoot.exists()) {
            "Could not clear stale generated TCK output at ${outputRoot.absolutePath}"
        }
        val exclusions =
            if (converterExclusions.isPresent) {
                ConverterExclusionManifest.parse(converterExclusions.get().asFile)
            } else {
                emptyList()
            }
        if (!inputDirectory.isPresent) {
            logger.lifecycle(
                "No TCK corpus configured; set -Pkwasm.tck.wastDir=/path/to/spec/test/core " +
                    "to generate wast2json assets.",
            )
            return
        }
        val inputRoot = inputDirectory.get().asFile
        val scripts = ConverterExclusionManifest.requirePresent(exclusions, inputRoot)
        val excludedByScript = exclusions.associateBy(ConverterExclusion::script)
        var converted = 0
        scripts.entries
            .sortedBy { it.key }
            .forEach { (relativeScript, script) ->
                val exclusion = excludedByScript[relativeScript]
                if (exclusion != null) {
                    logger.lifecycle(
                        "WABT converter exclusion: $relativeScript was not converted or executed " +
                            "(${exclusion.issueUrl})",
                    )
                    return@forEach
                }
                val json = outputRoot.resolve(relativeScript.removeSuffix(".wast") + ".json")
                json.parentFile.mkdirs()
                execOperations.exec {
                    executable = wast2jsonExecutable.get()
                    args(script.absolutePath)
                    args(wast2jsonArguments.get())
                    args("-o", json.absolutePath)
                    workingDir = json.parentFile
                }.assertNormalExitValue()
                reencodeQuotedInvalidModules(json)
                converted += 1
            }
        outputRoot.mkdirs()
        outputRoot.resolve("_conversion-summary.txt").writeText(
            buildString {
                appendLine("WebAssembly spec converter summary")
                appendLine("source scripts: ${scripts.size}")
                appendLine("converted scripts: $converted")
                appendLine("converter-excluded scripts (not executed): ${exclusions.size}")
                exclusions.forEach { exclusion ->
                    appendLine("  ${exclusion.script} ${exclusion.issueUrl}")
                }
            },
        )
    }

    /**
     * WABT deliberately preserves `(module quote ...)` assets as text. The
     * common runner consumes binaries, so an `assert_invalid` quote must be
     * encoded without validation before the runtime can perform the asserted
     * validation step. Malformed quoted text remains text and is still
     * rejected by the decoder.
     */
    private fun reencodeQuotedInvalidModules(manifestFile: File) {
        var manifest = manifestFile.readText()
        val filenames = quotedInvalidModule.findAll(manifest)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (filenames.isEmpty()) return

        val outputRoot = manifestFile.parentFile.canonicalFile
        val arguments = wast2jsonArguments.get().let { configured ->
            if ("--no-check" in configured) configured else configured + "--no-check"
        }
        filenames.forEachIndexed { index, filename ->
            val quotedFile = outputRoot.resolve(filename).canonicalFile
            check(quotedFile.toPath().startsWith(outputRoot.toPath())) {
                "Quoted TCK asset escapes generated output: '$filename'"
            }
            check(quotedFile.isFile) { "Missing quoted TCK asset: ${quotedFile.absolutePath}" }

            val helperDirectory = temporaryDir.resolve(
                "${manifestFile.nameWithoutExtension}-$index",
            ).also { it.mkdirs() }
            val helperManifest = helperDirectory.resolve("quoted.json")
            execOperations.exec {
                executable = wast2jsonExecutable.get()
                args(quotedFile.absolutePath)
                args(arguments)
                args("-o", helperManifest.absolutePath)
                workingDir = helperDirectory
            }.assertNormalExitValue()

            val helperFilename = generatedModule.find(helperManifest.readText())?.groupValues?.get(1)
                ?: error("Quoted invalid module did not produce a binary: ${quotedFile.absolutePath}")
            val helperBinary = helperDirectory.resolve(helperFilename)
            check(helperBinary.isFile) {
                "Quoted invalid module binary is missing: ${helperBinary.absolutePath}"
            }
            val binaryFilename = filename.removeSuffix(".wat") + ".wasm"
            val binaryFile = outputRoot.resolve(binaryFilename).canonicalFile
            check(binaryFile.toPath().startsWith(outputRoot.toPath())) {
                "Encoded TCK asset escapes generated output: '$binaryFilename'"
            }
            binaryFile.parentFile.mkdirs()
            helperBinary.copyTo(binaryFile, overwrite = true)
            manifest = manifest.replace(
                "\"filename\": \"$filename\"",
                "\"filename\": \"$binaryFilename\"",
            )
        }
        manifestFile.writeText(manifest)
    }
}

@CacheableTask
abstract class VerifyWast2JsonExclusionsTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val converterExclusions: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:Input
    abstract val wast2jsonExecutable: Property<String>

    @get:Input
    abstract val wast2jsonArguments: ListProperty<String>

    @TaskAction
    fun verify() {
        val manifestFile = converterExclusions.get().asFile
        val entries = ConverterExclusionManifest.parse(manifestFile)
        if (!inputDirectory.isPresent) {
            logger.lifecycle(
                "Verified ${entries.size} converter exclusion(s) for format and uniqueness; " +
                    "configure kwasm.tck.wastDir to verify staleness against a corpus.",
            )
            return
        }

        val inputRoot = inputDirectory.get().asFile
        val scripts = ConverterExclusionManifest.requirePresent(entries, inputRoot)
        entries.forEachIndexed { index, exclusion ->
            val output = temporaryDir.resolve("converter-exclusion-$index.json")
            val capturedStdout = ByteArrayOutputStream()
            val capturedStderr = ByteArrayOutputStream()
            val result = execOperations.exec {
                executable = wast2jsonExecutable.get()
                args(scripts.getValue(exclusion.script).absolutePath)
                args(wast2jsonArguments.get())
                args("-o", output.absolutePath)
                workingDir = temporaryDir
                isIgnoreExitValue = true
                standardOutput = capturedStdout
                errorOutput = capturedStderr
            }
            check(result.exitValue != 0) {
                "Stale converter exclusion '${exclusion.script}': " +
                    "${wast2jsonExecutable.get()} converted it successfully. " +
                    "Remove the exclusion and run the script."
            }
        }
        logger.lifecycle(
            "Verified ${entries.size} converter exclusion(s): every pinned script still " +
                "fails conversion and will be reported as not executed.",
        )
    }
}

@CacheableTask
abstract class VerifyTckExclusionsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exclusionFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        exclusionFiles.files.sortedBy { it.name }.forEach { file ->
            file.readLines().forEachIndexed { index, source ->
                val line = source.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                check(fields.size == 2 && fields[0].contains(".wast")) {
                    "${file.path}:${index + 1}: expected '<script.wast>[:line] <issue-url>'"
                }
                check(
                    fields[1].startsWith("https://") &&
                        (
                            fields[1].contains("/issues/") ||
                                fields[1].contains("/issue/") ||
                                fields[1].contains("/browse/")
                        ),
                ) {
                    "${file.path}:${index + 1}: exclusion must carry an HTTPS issue link"
                }
            }
        }
    }
}

@CacheableTask
abstract class VerifyWasiTestsuiteExclusionsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exclusionFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        exclusionFiles.files.sortedBy { it.name }.forEach { file ->
            val seen = mutableSetOf<String>()
            file.readLines().forEachIndexed { index, source ->
                val line = source.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                check(fields.size == 2) {
                    "${file.path}:${index + 1}: expected " +
                        "'<relative-fixture.wasm> <issue-url>'"
                }
                val fixture = fields[0].replace('\\', '/')
                check(
                    fixture.endsWith(".wasm") &&
                        !fixture.startsWith("/") &&
                        !Regex("^[A-Za-z]:/").containsMatchIn(fixture) &&
                        fixture.split('/').none { it.isEmpty() || it == "." || it == ".." },
                ) {
                    "${file.path}:${index + 1}: fixture must be a confined relative .wasm path"
                }
                check(seen.add(fixture)) {
                    "${file.path}:${index + 1}: duplicate exclusion '$fixture'"
                }
                check(
                    fields[1].startsWith("https://") &&
                        (
                            fields[1].contains("/issues/") ||
                                fields[1].contains("/issue/") ||
                                fields[1].contains("/browse/")
                        ),
                ) {
                    "${file.path}:${index + 1}: exclusion must carry an HTTPS issue link"
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }
    androidLibrary {
        namespace = "io.heapy.kwasm.tck"
        compileSdk = 36
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    linuxArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain {
            resources.srcDir(layout.buildDirectory.dir("generated/tck"))
            dependencies {
                implementation(project(":core"))
                implementation(project(":test-support-wat"))
                implementation(project(":wasi"))
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(project(":snapshot"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

val wasm3Wast2JsonArguments = listOf(
    "--enable-exceptions",
    "--enable-function-references",
    "--enable-tail-call",
    "--enable-annotations",
    "--enable-code-metadata",
    "--enable-gc",
    "--enable-memory64",
    "--enable-multi-memory",
    "--enable-extended-const",
    "--enable-relaxed-simd",
    // WABT is only the text-to-binary converter here. kwasm performs the
    // validation asserted by the spec corpus, including quoted invalid modules.
    "--no-check",
)
val configuredTckCorpus = providers.gradleProperty("kwasm.tck.wastDir")
val configuredConverterExclusions = providers.gradleProperty("kwasm.tck.converterExclusions")
val configuredWast2Json = providers.gradleProperty("kwasm.wabt.wast2json").orElse("wast2json")
val configuredWast2JsonArguments = providers.gradleProperty("kwasm.wabt.arguments")
    .map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    .orElse(wasm3Wast2JsonArguments)
val converterExclusionManifest =
    layout.projectDirectory.file("src/commonMain/resources/converter-exclusions/wabt.txt")

val generateWastJson = tasks.register<Wast2JsonTask>("generateWastJson") {
    group = "verification"
    description = "Convert a configured WebAssembly spec .wast corpus with WABT wast2json"
    configuredTckCorpus.orNull?.let { corpusPath ->
        inputDirectory.set(rootProject.file(corpusPath))
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/tck"))
    wast2jsonExecutable.convention(configuredWast2Json)
    providers.gradleProperty("kwasm.wabt.wast2json").orNull?.let { executablePath ->
        rootProject.file(executablePath).takeIf(File::isFile)?.let { converterFile ->
            wast2jsonBinary.set(converterFile)
        }
    }
    configuredConverterExclusions.orNull?.let { baseline ->
        check(baseline == "wg-3.0") {
            "kwasm.tck.converterExclusions supports only the checked-in 'wg-3.0' manifest"
        }
        converterExclusions.set(converterExclusionManifest)
    }
    // Enable the proposals in kwasm's Wasm 3.0 baseline explicitly. Using
    // --enable-all would also opt into unrelated experimental binary formats
    // such as compact imports and custom page sizes.
    wast2jsonArguments.convention(configuredWast2JsonArguments)
}

tasks.matching { it.name.endsWith("ProcessResources") }.configureEach {
    dependsOn(generateWastJson)
}

val checkedInExclusionFiles = fileTree("src/commonMain/resources/exclusions") {
    include("*.txt")
}
val checkedInWasiExclusionFiles = fileTree("src/commonMain/resources/wasi-exclusions") {
    include("*.txt")
}
val preview1WasiExclusions =
    layout.projectDirectory.file("src/commonMain/resources/wasi-exclusions/preview1.txt")
val configuredWasiTestsuite = providers.gradleProperty("kwasm.wasi.testsuiteDir")
val configuredNativeCorpusTestTasks = setOf(
    "iosSimulatorArm64Test",
    "macosArm64Test",
    "macosX64Test",
    "linuxArm64Test",
    "linuxX64Test",
)

tasks.named<Test>("jvmTest") {
    dependsOn(generateWastJson)

    inputs.property(
        "kwasm.tck.corpusConfigured",
        configuredTckCorpus.map { true }.orElse(false),
    )
    inputs.files(checkedInExclusionFiles)
        .withPropertyName("kwasm.tck.exclusions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty(
        "kwasm.tck.exclusionsDir",
        layout.projectDirectory.dir("src/commonMain/resources/exclusions").asFile.absolutePath,
    )

    if (configuredTckCorpus.isPresent) {
        val generatedDirectory = generateWastJson.flatMap { it.outputDirectory }
        inputs.dir(generatedDirectory)
            .withPropertyName("kwasm.tck.generatedCorpus")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
        systemProperty(
            "kwasm.tck.generatedDir",
            generatedDirectory.get().asFile.absolutePath,
        )
    }

    inputs.property(
        "kwasm.wasi.testsuiteConfigured",
        configuredWasiTestsuite.map { true }.orElse(false),
    )
    inputs.files(checkedInWasiExclusionFiles)
        .withPropertyName("kwasm.wasi.testsuiteExclusions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty(
        "kwasm.wasi.testsuite.exclusions",
        preview1WasiExclusions.asFile.absolutePath,
    )
    configuredWasiTestsuite.orNull?.let { configuredPath ->
        val suiteRoot = rootProject.file(configuredPath)
        inputs.dir(suiteRoot)
            .withPropertyName("kwasm.wasi.testsuite")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty("kwasm.wasi.testsuite.dir", suiteRoot.absolutePath)
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    if (name in configuredNativeCorpusTestTasks) {
        dependsOn(generateWastJson)
        inputs.property(
            "kwasm.tck.corpusConfigured",
            configuredTckCorpus.map { true }.orElse(false),
        )
        if (configuredTckCorpus.isPresent) {
            val generatedDirectory = generateWastJson.flatMap { it.outputDirectory }
            inputs.dir(generatedDirectory)
                .withPropertyName("kwasm.tck.generatedCorpus")
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .optional()
            inputs.files(checkedInExclusionFiles)
                .withPropertyName("kwasm.tck.exclusions")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            environment(
                "KWASM_TCK_GENERATED_DIR",
                generatedDirectory.get().asFile.absolutePath,
            )
            environment(
                "KWASM_TCK_EXCLUSIONS_DIR",
                layout.projectDirectory
                    .dir("src/commonMain/resources/exclusions")
                    .asFile
                    .absolutePath,
            )
        }
    }

    inputs.files(checkedInWasiExclusionFiles)
        .withPropertyName("kwasm.wasi.testsuiteExclusions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    environment(
        "KWASM_WASI_TESTSUITE_EXCLUSIONS",
        preview1WasiExclusions.asFile.absolutePath,
    )
    // Host-native executables and the iOS simulator can consume the configured
    // host corpus. Physical-device tests deliberately remain unconfigured.
    if (name == "macosArm64Test" || name == "macosX64Test") {
        inputs.property(
            "kwasm.wasi.testsuiteConfigured",
            configuredWasiTestsuite.map { true }.orElse(false),
        )
        configuredWasiTestsuite.orNull?.let { configuredPath ->
            val suiteRoot = rootProject.file(configuredPath)
            inputs.dir(suiteRoot)
                .withPropertyName("kwasm.wasi.testsuite")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            environment("KWASM_WASI_TESTSUITE_DIR", suiteRoot.absolutePath)
        }
    }
}

val verifyTckExclusions = tasks.register<VerifyTckExclusionsTask>("verifyTckExclusions") {
    group = "verification"
    description = "Require every non-comment TCK exclusion to carry an HTTPS issue URL"
    exclusionFiles.from(checkedInExclusionFiles)
}

val verifyWast2JsonExclusions =
    tasks.register<VerifyWast2JsonExclusionsTask>("verifyWast2JsonExclusions") {
        group = "verification"
        description =
            "Reject malformed, missing, or now-convertible whole-script WABT exclusions"
        converterExclusions.set(converterExclusionManifest)
        configuredTckCorpus.orNull?.let { corpusPath ->
            inputDirectory.set(rootProject.file(corpusPath))
        }
        wast2jsonExecutable.convention(configuredWast2Json)
        wast2jsonArguments.convention(configuredWast2JsonArguments)
    }

val verifyWasiTestsuiteExclusions =
    tasks.register<VerifyWasiTestsuiteExclusionsTask>("verifyWasiTestsuiteExclusions") {
        group = "verification"
        description = "Require each official WASI fixture exclusion to carry an HTTPS issue URL"
        exclusionFiles.from(checkedInWasiExclusionFiles)
    }

tasks.named("check") {
    dependsOn(verifyTckExclusions)
    dependsOn(verifyWast2JsonExclusions)
    dependsOn(verifyWasiTestsuiteExclusions)
}

val fuzzRawSeed = providers.gradleProperty("kwasm.fuzz.rawSeed").orElse("0x4B5741534D")
val fuzzRawIterations = providers.gradleProperty("kwasm.fuzz.rawIterations").orElse("10000")
val fuzzRawMaximumBytes = providers.gradleProperty("kwasm.fuzz.rawMaxBytes").orElse("4096")
val fuzzSnapshotSeed =
    providers.gradleProperty("kwasm.fuzz.snapshotSeed").orElse("0x534E415053484F54")
val fuzzSnapshotMutationIterations =
    providers.gradleProperty("kwasm.fuzz.snapshotMutationIterations").orElse("1000")
val fuzzSnapshotPropertyIterations =
    providers.gradleProperty("kwasm.fuzz.snapshotPropertyIterations").orElse("16")
val fuzzCorpus = providers.gradleProperty("kwasm.fuzz.corpusDir")
val fuzzRequireDifferential =
    providers.gradleProperty("kwasm.fuzz.requireDifferential").orElse("false")
val fuzzAllowNoCallableExports =
    providers.gradleProperty("kwasm.fuzz.allowNoCallableExports").orElse("false")
val fuzzWasmtime = providers.gradleProperty("kwasm.fuzz.wasmtime").orElse("wasmtime")
val fuzzWasmtimeVersion = providers.gradleProperty("kwasm.fuzz.wasmtimeVersion")
// Optional wasm3 interpreter oracle (github.com/wasm3/wasm3). Unlike
// wasm3Wast2JsonArguments above (WABT flags for the Wasm 3.0 *spec*), these
// properties target the wasm3 *interpreter*. All four are optional: a local
// default run stays offline with no wasm3 binary on PATH.
val fuzzWasm3 = providers.gradleProperty("kwasm.fuzz.wasm3")
val fuzzWasm3Version = providers.gradleProperty("kwasm.fuzz.wasm3Version")
val fuzzWasm3Secondary = providers.gradleProperty("kwasm.fuzz.wasm3Secondary")
val fuzzWasm3SecondaryVersion = providers.gradleProperty("kwasm.fuzz.wasm3SecondaryVersion")
val fuzzArtifacts = providers.gradleProperty("kwasm.fuzz.artifactsDir")
    .map(rootProject::file)
    .orElse(layout.buildDirectory.dir("fuzz-artifacts").map { it.asFile })
val fuzzMaximumModules = providers.gradleProperty("kwasm.fuzz.maxModules").orElse("256")
val fuzzMaximumInvocations =
    providers.gradleProperty("kwasm.fuzz.maxInvocationsPerModule").orElse("4")
val fuzzMaximumModuleBytes =
    providers.gradleProperty("kwasm.fuzz.maxModuleBytes").orElse((1 shl 20).toString())
val fuzzProcessTimeout =
    providers.gradleProperty("kwasm.fuzz.processTimeoutMillis").orElse("5000")
val fuzzExecutionFuel =
    providers.gradleProperty("kwasm.fuzz.executionFuel").orElse("5000000")
val fuzzMaximumRuntimeMemory =
    providers.gradleProperty("kwasm.fuzz.maxRuntimeMemoryBytes").orElse((4L shl 20).toString())
val fuzzMaximumTableElements =
    providers.gradleProperty("kwasm.fuzz.maxTableElements").orElse("1024")
val fuzzMinimizationAttempts =
    providers.gradleProperty("kwasm.fuzz.minimizationAttempts").orElse("32")

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
val nightlyFuzz = tasks.register<JavaExec>("nightlyFuzz") {
    group = "verification"
    description =
        "Run bounded raw, snapshot, and optional wasm-smith/wasmtime differential fuzz gates"
    dependsOn(jvmMainCompilation.compileTaskProvider)
    mainClass.set("io.heapy.kwasm.tck.NightlyFuzzMainKt")
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    jvmArgs("-Xmx768m")

    inputs.property("rawSeed", fuzzRawSeed)
    inputs.property("rawIterations", fuzzRawIterations)
    inputs.property("rawMaximumBytes", fuzzRawMaximumBytes)
    inputs.property("snapshotSeed", fuzzSnapshotSeed)
    inputs.property("snapshotMutationIterations", fuzzSnapshotMutationIterations)
    inputs.property("snapshotPropertyIterations", fuzzSnapshotPropertyIterations)
    inputs.property("requireDifferential", fuzzRequireDifferential)
    inputs.property("allowNoCallableExports", fuzzAllowNoCallableExports)
    inputs.property("wasmtime", fuzzWasmtime)
    inputs.property(
        "wasmtimeVersion",
        fuzzWasmtimeVersion.map { it }.orElse("<not-pinned-by-caller>"),
    )
    inputs.property(
        "wasm3",
        fuzzWasm3.map { it }.orElse("<not-configured>"),
    )
    inputs.property(
        "wasm3Version",
        fuzzWasm3Version.map { it }.orElse("<not-pinned-by-caller>"),
    )
    inputs.property(
        "wasm3Secondary",
        fuzzWasm3Secondary.map { it }.orElse("<not-configured>"),
    )
    inputs.property(
        "wasm3SecondaryVersion",
        fuzzWasm3SecondaryVersion.map { it }.orElse("<not-pinned-by-caller>"),
    )
    inputs.property("maximumModules", fuzzMaximumModules)
    inputs.property("maximumInvocations", fuzzMaximumInvocations)
    inputs.property("maximumModuleBytes", fuzzMaximumModuleBytes)
    inputs.property("processTimeoutMillis", fuzzProcessTimeout)
    inputs.property("executionFuel", fuzzExecutionFuel)
    inputs.property("maximumRuntimeMemoryBytes", fuzzMaximumRuntimeMemory)
    inputs.property("maximumTableElements", fuzzMaximumTableElements)
    inputs.property("minimizationAttempts", fuzzMinimizationAttempts)
    fuzzCorpus.orNull?.let { corpusPath ->
        inputs.dir(rootProject.file(corpusPath))
            .withPropertyName("wasmSmithCorpus")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    providers.gradleProperty("kwasm.fuzz.wasmtime").orNull?.let { executablePath ->
        rootProject.file(executablePath).takeIf(File::isFile)?.let { executable ->
            inputs.file(executable)
                .withPropertyName("wasmtimeBinary")
                .withPathSensitivity(PathSensitivity.NONE)
        }
    }
    fuzzWasm3.orNull?.let { executablePath ->
        rootProject.file(executablePath).takeIf(File::isFile)?.let { executable ->
            inputs.file(executable)
                .withPropertyName("wasm3Binary")
                .withPathSensitivity(PathSensitivity.NONE)
        }
    }
    fuzzWasm3Secondary.orNull?.let { executablePath ->
        rootProject.file(executablePath).takeIf(File::isFile)?.let { executable ->
            inputs.file(executable)
                .withPropertyName("wasm3SecondaryBinary")
                .withPathSensitivity(PathSensitivity.NONE)
        }
    }
    outputs.dir(fuzzArtifacts)
    // A scheduled fuzz gate must execute even when its deterministic inputs
    // match a prior local run; evidence is a result, not a reusable build cache.
    outputs.upToDateWhen { false }

    doFirst {
        val configuredArguments = buildList {
            addAll(listOf("--raw-seed", fuzzRawSeed.get()))
            addAll(listOf("--raw-iterations", fuzzRawIterations.get()))
            addAll(listOf("--raw-max-bytes", fuzzRawMaximumBytes.get()))
            addAll(listOf("--snapshot-seed", fuzzSnapshotSeed.get()))
            addAll(
                listOf(
                    "--snapshot-mutation-iterations",
                    fuzzSnapshotMutationIterations.get(),
                ),
            )
            addAll(
                listOf(
                    "--snapshot-property-iterations",
                    fuzzSnapshotPropertyIterations.get(),
                ),
            )
            addAll(listOf("--wasmtime", fuzzWasmtime.get()))
            addAll(listOf("--artifacts", fuzzArtifacts.get().absolutePath))
            addAll(listOf("--max-modules", fuzzMaximumModules.get()))
            addAll(
                listOf(
                    "--max-invocations-per-module",
                    fuzzMaximumInvocations.get(),
                ),
            )
            addAll(listOf("--max-module-bytes", fuzzMaximumModuleBytes.get()))
            addAll(listOf("--process-timeout-ms", fuzzProcessTimeout.get()))
            addAll(listOf("--execution-fuel", fuzzExecutionFuel.get()))
            addAll(
                listOf(
                    "--max-runtime-memory-bytes",
                    fuzzMaximumRuntimeMemory.get(),
                ),
            )
            addAll(listOf("--max-table-elements", fuzzMaximumTableElements.get()))
            addAll(listOf("--minimization-attempts", fuzzMinimizationAttempts.get()))
            fuzzCorpus.orNull?.let { corpusPath ->
                addAll(listOf("--corpus", rootProject.file(corpusPath).absolutePath))
            }
            fuzzWasmtimeVersion.orNull?.let { version ->
                addAll(listOf("--expected-wasmtime-version", version))
            }
            // wasm3 is an optional oracle; emit its flags only when configured.
            fuzzWasm3.orNull?.let { executable ->
                addAll(listOf("--wasm3", executable))
                fuzzWasm3Version.orNull?.let { version ->
                    addAll(listOf("--expected-wasm3-version", version))
                }
            }
            fuzzWasm3Secondary.orNull?.let { executable ->
                addAll(listOf("--wasm3-secondary", executable))
                fuzzWasm3SecondaryVersion.orNull?.let { version ->
                    addAll(listOf("--expected-wasm3-secondary-version", version))
                }
            }
            if (fuzzRequireDifferential.get().toBooleanStrict()) {
                add("--require-differential")
            }
            if (fuzzAllowNoCallableExports.get().toBooleanStrict()) {
                add("--allow-no-callable-exports")
            }
        }
        setArgs(configuredArguments)
    }
}
