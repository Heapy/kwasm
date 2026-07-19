import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.benchmark)
}

val hasCoreMarkAsset =
    providers.environmentVariable("KWASM_COREMARK_WASM")
        .map { it.isNotBlank() }
        .orElse(false)

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    macosArm64()
    macosX64()
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
    }

    targets {
        register("jvm")
        register("macosArm64")
        register("macosX64")
        register("linuxArm64")
        register("linuxX64")
    }
}

val gateTool = layout.projectDirectory.file("tools/performance_gate.py")
val benchmarkTargets = listOf("jvm", "macosArm64", "macosX64", "linuxArm64", "linuxX64")

benchmarkTargets.forEach { target ->
    val capitalized = target.replaceFirstChar { it.uppercaseChar() }
    val rawReportRoot = layout.buildDirectory.dir("reports/benchmarks/main")
    val rawExternalReportRoot =
        layout.buildDirectory.dir("reports/benchmarks/externalComparison")
    val normalizedReport = layout.buildDirectory.file("performance/current-$target.json")
    val normalizedExternalReport =
        layout.buildDirectory.file("performance/external-current-$target.json")
    val externalComparisonsReport =
        layout.buildDirectory.file("performance/external-comparisons-$target.json")
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
    description = "Run the deterministic unit tests for the machine-readable performance gate."
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
