import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)
}

val hasCoreMarkAsset =
    providers.environmentVariable("KWASM_COREMARK_WASM")
        .map { it.isNotBlank() }
        .orElse(false)
val coreMarkFixedIterations = 100

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    macosArm64()
    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":snapshot"))
            implementation(project(":test-support-wat"))
            implementation(libs.chasm.kmp)
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

benchmark {
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            if (!hasCoreMarkAsset.get()) {
                exclude("ExternalCoreMarkBenchmark")
            }
            exclude("PinnedChasmBenchmark")
            exclude("I32ExpressionPlanBenchmark")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
            advanced("nativeGCAfterIteration", true)
        }
        register("smoke") {
            // Native uses warmup work to calibrate the operation count; zero
            // warmups yields a zero-cycle Infinity/NaN measurement.
            warmups = 1
            iterations = 1
            iterationTime = 50
            iterationTimeUnit = "ms"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            exclude("fib35")
            exclude("Snapshot64MiBBenchmark")
            exclude("ExternalCoreMarkBenchmark")
            exclude("PinnedChasmBenchmark")
            exclude("I32ExpressionPlanBenchmark")
            advanced("jvmForks", 0)
        }
        register("hotSmoke") {
            warmups = 1
            iterations = 1
            iterationTime = 50
            iterationTimeUnit = "ms"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("GuestWorkloadsBenchmark.fib35CheckpointEnabled")
            include("GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled")
            include("GuestWorkloadsBenchmark.jsonParseCheckpointEnabled")
            advanced("jvmForks", 0)
        }
        register("optimizationStudy") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("GuestWorkloadsBenchmark.fib35CheckpointEnabled")
            include("GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled")
            include("GuestWorkloadsBenchmark.jsonParseCheckpointEnabled")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
            advanced("nativeGCAfterIteration", true)
        }
        register("i32ExpressionPlan") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("I32ExpressionPlanBenchmark")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
            advanced("nativeGCAfterIteration", true)
        }
        register("checkpointSmoke") {
            warmups = 1
            iterations = 1
            iterationTime = 50
            iterationTimeUnit = "ms"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("CallBoundaryBenchmark.hostToGuestPlainCheckpoint")
            include("GuestWorkloadsBenchmark.fib35Checkpoint")
            include("GuestWorkloadsBenchmark.sha256LoopCheckpoint")
            include("GuestWorkloadsBenchmark.jsonParseCheckpoint")
            advanced("jvmForks", 0)
        }
        register("external") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("ExternalCoreMarkBenchmark")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
        }
        register("externalComparison") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("GuestWorkloadsBenchmark.fib35CheckpointEnabled")
            include("GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled")
            include("GuestWorkloadsBenchmark.jsonParseCheckpointEnabled")
            include("ExternalCoreMarkBenchmark")
            include("PinnedChasmBenchmark")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
            advanced("nativeGCAfterIteration", true)
        }
        register("compiledReference") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            mode = "avgt"
            reportFormat = "json"
            include("GuestWorkloadsBenchmark.fib35CheckpointEnabled")
            include("GuestWorkloadsBenchmark.sha256LoopCheckpointEnabled")
            include("GuestWorkloadsBenchmark.jsonParseCheckpointEnabled")
            include("ExternalCoreMarkBenchmark")
            advanced("jvmForks", 1)
            advanced("nativeFork", "perBenchmark")
            advanced("nativeGCAfterIteration", true)
        }
    }

    targets {
        register("jvm")
        register("macosArm64")
        register("linuxArm64")
        register("linuxX64")
    }
}

val gateTool = layout.projectDirectory.file("tools/performance_gate.py")
val compiledReferenceTool = layout.projectDirectory.file("tools/compiled_reference.py")
val benchmarkTargets = listOf("jvm", "macosArm64", "linuxArm64", "linuxX64")
val canonicalFixtureDirectory = layout.buildDirectory.dir("compiled-reference/fixtures")
val fibFixture = canonicalFixtureDirectory.map { it.file("fib.wasm") }
val shaFixture = canonicalFixtureDirectory.map { it.file("sha256.wasm") }
val jsonFixture = canonicalFixtureDirectory.map { it.file("json.wasm") }
val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

val exportBenchmarkFixtures = tasks.register<JavaExec>("exportBenchmarkFixtures") {
    group = "benchmark"
    description = "Export the generated canonical Wasm bytes for native runtime references."
    dependsOn(jvmMainCompilation.compileTaskProvider)
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("io.heapy.kwasm.benchmarks.BenchmarkFixtureExporterKt")
    outputs.files(fibFixture, shaFixture, jsonFixture)
    doFirst {
        setArgs(listOf(canonicalFixtureDirectory.get().asFile.absolutePath))
    }
}

benchmarkTargets.forEach { target ->
    val capitalized = target.replaceFirstChar { it.uppercaseChar() }
    val rawReportRoot = layout.buildDirectory.dir("reports/benchmarks/main")
    val rawExternalReportRoot =
        layout.buildDirectory.dir("reports/benchmarks/externalComparison")
    val rawCompiledReferenceReportRoot =
        layout.buildDirectory.dir("reports/benchmarks/compiledReference")
    val normalizedReport = layout.buildDirectory.file("performance/current-$target.json")
    val normalizedExternalReport =
        layout.buildDirectory.file("performance/external-current-$target.json")
    val externalComparisonsReport =
        layout.buildDirectory.file("performance/external-comparisons-$target.json")
    val normalizedCompiledReferenceReport =
        layout.buildDirectory.file("performance/compiled-reference-kwasm-$target.json")
    val rawWasmtimeReferenceReport =
        layout.buildDirectory.file("performance/compiled-reference-wasmtime-raw-$target.json")
    val compiledReferenceReport =
        layout.buildDirectory.file("performance/compiled-reference-$target.json")
    val gateReport = layout.buildDirectory.file("performance/gate-$target.json")

    val normalize = tasks.register<Exec>("normalize${capitalized}Benchmark") {
        group = "verification"
        description = "Normalize the $target kotlinx-benchmark report for history/comparison gates."
        dependsOn("${target}Benchmark")
        inputs.dir(rawReportRoot)
        inputs.file(gateTool)
        outputs.file(normalizedReport)
        commandLine(
            "python3",
            gateTool.asFile.absolutePath,
            "normalize",
            "--input-directory",
            rawReportRoot.get().asFile.absolutePath,
            "--target",
            target,
            "--output",
            normalizedReport.get().asFile.absolutePath,
        )
    }

    val normalizeExternal =
        tasks.register<Exec>("normalize${capitalized}ExternalComparison") {
            group = "verification"
            description =
                "Normalize the paired kwasm/Chasm $target comparison report."
            dependsOn("${target}ExternalComparisonBenchmark")
            inputs.dir(rawExternalReportRoot)
            inputs.file(gateTool)
            outputs.file(normalizedExternalReport)
            commandLine(
                "python3",
                gateTool.asFile.absolutePath,
                "normalize",
                "--input-directory",
                rawExternalReportRoot.get().asFile.absolutePath,
                "--target",
                target,
                "--output",
                normalizedExternalReport.get().asFile.absolutePath,
            )
        }

    val normalizeCompiledReference =
        tasks.register<Exec>("normalize${capitalized}CompiledReference") {
            group = "verification"
            description = "Normalize canonical kwasm rows for the $target compiled reference."
            dependsOn("${target}CompiledReferenceBenchmark")
            inputs.dir(rawCompiledReferenceReportRoot)
            inputs.file(gateTool)
            outputs.file(normalizedCompiledReferenceReport)
            commandLine(
                "python3",
                gateTool.asFile.absolutePath,
                "normalize",
                "--input-directory",
                rawCompiledReferenceReportRoot.get().asFile.absolutePath,
                "--target",
                target,
                "--output",
                normalizedCompiledReferenceReport.get().asFile.absolutePath,
            )
        }

    val runWasmtimeReference =
        tasks.register<Exec>("run${capitalized}WasmtimeReference") {
            val runnerPath = providers.environmentVariable("KWASM_WASMTIME_REFERENCE_RUNNER")
            val coreMarkPath = providers.environmentVariable("KWASM_COREMARK_WASM")
            val runnerPathValue = runnerPath.orNull.orEmpty()
            val coreMarkPathValue = coreMarkPath.orNull.orEmpty()
            group = "verification"
            description = "Measure the pinned Wasmtime/Cranelift reference for $target."
            dependsOn(exportBenchmarkFixtures)
            mustRunAfter(normalizeCompiledReference)
            inputs.file(compiledReferenceTool)
            inputs.files(fibFixture, shaFixture, jsonFixture)
            inputs.property("runnerPath", runnerPathValue)
            inputs.property("coreMarkPath", coreMarkPathValue)
            if (runnerPathValue.isNotEmpty()) {
                inputs.file(runnerPathValue)
            }
            if (coreMarkPathValue.isNotEmpty()) {
                inputs.file(coreMarkPathValue)
            }
            outputs.file(rawWasmtimeReferenceReport)
            doFirst {
                check(runnerPathValue.isNotEmpty()) {
                    "KWASM_WASMTIME_REFERENCE_RUNNER must name the pinned Wasmtime runner"
                }
                check(coreMarkPathValue.isNotEmpty()) {
                    "KWASM_COREMARK_WASM must name the checksum-pinned CoreMark fixture"
                }
                rawWasmtimeReferenceReport.get().asFile.parentFile.mkdirs()
                commandLine(
                    runnerPathValue,
                    rawWasmtimeReferenceReport.get().asFile.absolutePath,
                    target,
                    fibFixture.get().asFile.absolutePath,
                    shaFixture.get().asFile.absolutePath,
                    jsonFixture.get().asFile.absolutePath,
                    coreMarkPathValue,
                    "fixed-coremark-100",
                )
            }
        }

    tasks.register<Exec>("${target}CompiledReferenceReport") {
        val coreMarkPath = providers.environmentVariable("KWASM_COREMARK_WASM")
        val machineDescription =
            providers.environmentVariable("KWASM_BENCHMARK_MACHINE")
                .orElse(
                    "${System.getProperty("os.name")} " +
                        "${System.getProperty("os.arch")} " +
                        "${System.getProperty("os.version")}",
                )
        val coreMarkPathValue = coreMarkPath.orNull.orEmpty()
        group = "verification"
        description =
            "Create the separate informational kwasm/Wasmtime report for $target."
        dependsOn(normalizeCompiledReference, runWasmtimeReference)
        inputs.file(normalizedCompiledReferenceReport)
        inputs.file(rawWasmtimeReferenceReport)
        inputs.file(compiledReferenceTool)
        inputs.file(layout.projectDirectory.file("upstreams.lock.json"))
        inputs.files(fibFixture, shaFixture, jsonFixture)
        inputs.property("coreMarkPath", coreMarkPathValue)
        inputs.property("machineDescription", machineDescription)
        if (coreMarkPathValue.isNotEmpty()) {
            inputs.file(coreMarkPathValue)
        }
        outputs.file(compiledReferenceReport)
        doFirst {
            check(coreMarkPathValue.isNotEmpty()) {
                "KWASM_COREMARK_WASM must name the checksum-pinned CoreMark fixture"
            }
            commandLine(
                "python3",
                compiledReferenceTool.asFile.absolutePath,
                "--kwasm",
                normalizedCompiledReferenceReport.get().asFile.absolutePath,
                "--wasmtime",
                rawWasmtimeReferenceReport.get().asFile.absolutePath,
                "--output",
                compiledReferenceReport.get().asFile.absolutePath,
                "--target",
                target,
                "--fib-wasm",
                fibFixture.get().asFile.absolutePath,
                "--sha-wasm",
                shaFixture.get().asFile.absolutePath,
                "--json-wasm",
                jsonFixture.get().asFile.absolutePath,
                "--coremark-wasm",
                coreMarkPathValue,
                "--machine",
                machineDescription.get(),
                "--measurement-command",
                "./gradlew :benchmarks:${target}CompiledReferenceReport",
                "--upstream-lock",
                layout.projectDirectory.file("upstreams.lock.json").asFile.absolutePath,
            )
        }
    }

    tasks.register<Exec>("${target}ExternalComparisonReport") {
        val coreMarkPath = providers.environmentVariable("KWASM_COREMARK_WASM")
        val machineDescription =
            providers.environmentVariable("KWASM_BENCHMARK_MACHINE")
                .orElse(
                    "${System.getProperty("os.name")} " +
                        "${System.getProperty("os.arch")} " +
                        "${System.getProperty("os.version")}",
                )
        val coreMarkPathValue = coreMarkPath.orNull.orEmpty()
        val machineDescriptionValue = machineDescription.get()
        group = "verification"
        description =
            "Extract checksum-pinned same-process kwasm/Chasm evidence for $target."
        dependsOn(normalizeExternal)
        inputs.file(normalizedExternalReport)
        inputs.file(gateTool)
        inputs.property("coreMarkPath", coreMarkPathValue)
        inputs.property("coreMarkFixedIterations", coreMarkFixedIterations)
        inputs.property("machineDescription", machineDescriptionValue)
        if (coreMarkPathValue.isNotEmpty()) {
            inputs.file(coreMarkPathValue)
        }
        outputs.file(externalComparisonsReport)
        commandLine(
            "python3",
            gateTool.asFile.absolutePath,
            "extract-external",
            "--input",
            normalizedExternalReport.get().asFile.absolutePath,
            "--output",
            externalComparisonsReport.get().asFile.absolutePath,
            "--coremark-wasm",
            coreMarkPathValue,
            "--coremark-iterations",
            coreMarkFixedIterations.toString(),
            "--measurement-command",
            "./gradlew :benchmarks:${target}ExternalComparisonBenchmark",
            "--machine",
            machineDescriptionValue,
            "--upstream-lock",
            "../upstreams.lock.json",
        )
    }

    tasks.register<Exec>("${target}PerformanceGate") {
        val baselinePath = providers.gradleProperty("kwasm.benchmark.baseline")
        val externalComparisonsPath =
            providers.gradleProperty("kwasm.benchmark.externalComparisons")
        val maxRegressionPercent =
            providers.gradleProperty("kwasm.benchmark.maxRegressionPercent").orElse("10")
        val enforceSnapshotTarget =
            providers.gradleProperty("kwasm.benchmark.enforceSnapshotTarget").orElse("false")
        val enforceCheckpointOverhead =
            providers.gradleProperty("kwasm.benchmark.enforceCheckpointOverhead").orElse("true")
        val baselineFile =
            baselinePath.orNull
                ?.takeIf(String::isNotBlank)
                ?.let(rootProject::file)
        val externalComparisonsFile =
            externalComparisonsPath.orNull
                ?.takeIf(String::isNotBlank)
                ?.let(rootProject::file)

        group = "verification"
        description =
            "Enforce checkpoint, startup, and optional self-history gates for $target."
        dependsOn(normalize)
        inputs.file(normalizedReport)
        inputs.file(gateTool)
        inputs.property("baselinePath", baselinePath.orElse(""))
        inputs.property("externalComparisonsPath", externalComparisonsPath.orElse(""))
        inputs.property("maxRegressionPercent", maxRegressionPercent)
        inputs.property("enforceSnapshotTarget", enforceSnapshotTarget)
        inputs.property("enforceCheckpointOverhead", enforceCheckpointOverhead)
        baselineFile?.let(inputs::file)
        externalComparisonsFile?.let(inputs::file)
        outputs.file(gateReport)

        val arguments = mutableListOf(
            "python3",
            gateTool.asFile.absolutePath,
            "verify",
            "--current",
            normalizedReport.get().asFile.absolutePath,
            "--output",
            gateReport.get().asFile.absolutePath,
            "--max-regression-percent",
            maxRegressionPercent.get(),
        )
        baselineFile?.let {
            arguments += listOf("--baseline", it.absolutePath)
        }
        externalComparisonsFile?.let {
            arguments += listOf("--external-comparisons", it.absolutePath)
        }
        if (enforceSnapshotTarget.get().toBooleanStrict()) {
            arguments += "--enforce-snapshot-target"
        }
        if (!enforceCheckpointOverhead.get().toBooleanStrict()) {
            arguments += "--advisory-checkpoint-overhead"
        }
        commandLine(arguments)
    }
}

tasks.register<Exec>("performanceGateToolTest") {
    group = "verification"
    description = "Run deterministic tests for the machine-readable performance tools."
    commandLine(
        "python3",
        "-m",
        "unittest",
        "discover",
        "-s",
        layout.projectDirectory.dir("tools").asFile.absolutePath,
        "-p",
        "test_*.py",
    )
}
