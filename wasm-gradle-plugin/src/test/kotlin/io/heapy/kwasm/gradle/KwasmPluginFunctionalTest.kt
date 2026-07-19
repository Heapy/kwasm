package io.heapy.kwasm.gradle

import org.junit.Assume.assumeFalse
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.StringWriter
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

public class KwasmPluginFunctionalTest {
    @Test
    public fun `plugin prepares bindings and embeds an explicit wasm deterministically`() {
        withFixture { fixture ->
            val wasmBytes = minimalWasm()
            fixture.resolve("guest.wasm").writeBytes(wasmBytes)
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("io.heapy.kwasm")
                }

                kwasm {
                    guestPath.set(layout.projectDirectory.file("guest.wasm"))
                    generatedBindingsDirectory.set(
                        layout.buildDirectory.dir("kwasm-test/bindings"),
                    )
                    embeddedResourceOutput.set(
                        layout.buildDirectory.file("kwasm-test/resources/module.wasm"),
                    )
                    devMode.set(true)
                }
                """.trimIndent(),
            )

            val first = runner(fixture, ASSEMBLE_KWASM_TASK_NAME, "--build-cache").build()

            assertSuccessfulOrFromCache(
                first.task(":$PREPARE_KWASM_BINDINGS_TASK_NAME")?.outcome,
            )
            assertSuccessfulOrFromCache(
                first.task(":$EMBED_KWASM_GUEST_TASK_NAME")?.outcome,
            )
            assertTrue(fixture.resolve("build/kwasm-test/bindings").isDirectory)
            assertContentEquals(
                wasmBytes,
                fixture.resolve("build/kwasm-test/resources/module.wasm").readBytes(),
            )
            val firstManifest =
                fixture.resolve("build/generated/kwasm/reload/reload.properties")
            assertTrue(firstManifest.isFile)
            assertTrue(firstManifest.readText().contains("sha256=${sha256(wasmBytes)}"))

            val second = runner(fixture, ASSEMBLE_KWASM_TASK_NAME, "--build-cache").build()

            assertEquals(
                TaskOutcome.UP_TO_DATE,
                second.task(":$PREPARE_KWASM_BINDINGS_TASK_NAME")?.outcome,
            )
            assertEquals(
                TaskOutcome.UP_TO_DATE,
                second.task(":$EMBED_KWASM_GUEST_TASK_NAME")?.outcome,
            )
            assertEquals(
                TaskOutcome.UP_TO_DATE,
                second.task(":$KWASM_DEV_RELOAD_TASK_NAME")?.outcome,
            )
            assertTrue(second.output.contains("Reusing configuration cache."))

            fixture.resolve("build/kwasm-test/bindings").deleteRecursively()
            fixture.resolve("build/kwasm-test/resources/module.wasm").delete()
            firstManifest.delete()
            val restored = runner(fixture, ASSEMBLE_KWASM_TASK_NAME, "--build-cache").build()

            assertEquals(
                TaskOutcome.FROM_CACHE,
                restored.task(":$PREPARE_KWASM_BINDINGS_TASK_NAME")?.outcome,
            )
            assertEquals(
                TaskOutcome.FROM_CACHE,
                restored.task(":$EMBED_KWASM_GUEST_TASK_NAME")?.outcome,
            )
            assertEquals(
                TaskOutcome.FROM_CACHE,
                restored.task(":$KWASM_DEV_RELOAD_TASK_NAME")?.outcome,
            )

            val changedBytes = wasmBytes + byteArrayOf(0x00)
            fixture.resolve("guest.wasm").writeBytes(changedBytes)
            val reloaded = runner(fixture, KWASM_DEV_RELOAD_TASK_NAME, "--build-cache").build()

            assertSuccessfulOrFromCache(
                reloaded.task(":$KWASM_DEV_RELOAD_TASK_NAME")?.outcome,
            )
            val changedManifest = firstManifest.readText()
            assertTrue(changedManifest.contains("sha256=${sha256(changedBytes)}"))
            assertNotEquals(sha256(wasmBytes), sha256(changedBytes))
        }
    }

    @Test
    public fun `continuous reload watches the linked wasm and debounces changes`() {
        withFixture { fixture ->
            val initialBytes = minimalWasm()
            val intermediateBytes = initialBytes + byteArrayOf(0x01)
            val finalBytes = initialBytes + byteArrayOf(0x02, 0x03)
            val guest = fixture.resolve("guest.wasm")
            val embedded = fixture.resolve("build/dev-reload/resources/guest.wasm")
            val manifest = fixture.resolve("build/dev-reload/reload.properties")
            guest.writeBytes(initialBytes)
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("io.heapy.kwasm")
                }

                kwasm {
                    guestPath.set(layout.projectDirectory.file("guest.wasm"))
                    embeddedResourceOutput.set(
                        layout.buildDirectory.file("dev-reload/resources/guest.wasm"),
                    )
                    devReloadManifest.set(
                        layout.buildDirectory.file("dev-reload/reload.properties"),
                    )
                    devMode.set(true)
                }
                """.trimIndent(),
            )

            val standardInput = PipedInputStream()
            val stopSignal = PipedOutputStream(standardInput)
            val liveOutput = StringWriter()
            val executor = Executors.newSingleThreadExecutor()
            val watching = executor.submit(Callable<BuildResult> {
                runner(
                    fixture,
                    KWASM_DEV_RELOAD_TASK_NAME,
                    "--continuous",
                    "-Dorg.gradle.continuous.quietperiod=600",
                )
                    .withContinuousBuildInput(standardInput)
                    .forwardStdOutput(liveOutput)
                    .forwardStdError(liveOutput)
                    .build()
            })
            try {
                awaitCondition("initial reload manifest") {
                    manifest.isFile &&
                        manifest.readText().contains("sha256=${sha256(initialBytes)}")
                }

                awaitCondition("Gradle continuous-build wait loop") {
                    watching.isDone ||
                        liveOutput.toString().contains("Waiting for changes to input files...")
                }
                if (watching.isDone) {
                    val earlyResult = watching.get(5L, TimeUnit.SECONDS)
                    assumeFalse(
                        "Gradle file-system watching is unavailable on this test host",
                        earlyResult.output.contains(
                            "Exiting continuous build as Gradle does not watch " +
                                "any file system locations.",
                        ),
                    )
                    assertTrue(false, "continuous reload exited before its wait loop")
                }

                guest.writeBytes(intermediateBytes)
                Thread.sleep(150L)
                assertTrue(
                    manifest.readText().contains("sha256=${sha256(initialBytes)}"),
                    "the debounce quiet period must not publish an intermediate write",
                )
                guest.writeBytes(finalBytes)

                awaitCondition("debounced reload publication") {
                    manifest.isFile &&
                        embedded.isFile &&
                        manifest.readText().contains("sha256=${sha256(finalBytes)}") &&
                        embedded.readBytes().contentEquals(finalBytes)
                }

                assertTrue(!watching.isDone, "continuous reload exited before cancellation")
                stopSignal.write(4)
                stopSignal.flush()
                val result = watching.get(20L, TimeUnit.SECONDS)
                assertTrue(result.output.contains("Change detected, executing build..."))
                assertTrue(result.output.contains("Build cancelled."))
                assertTrue(
                    fixture.walkTopDown().none { it.name.endsWith(".kwasm-tmp") },
                    "atomic publication must clean temporary siblings",
                )
            } finally {
                if (!watching.isDone) {
                    runCatching {
                        stopSignal.write(4)
                        stopSignal.flush()
                    }
                }
                stopSignal.close()
                standardInput.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(20L, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    public fun `guest project compilation is wired and its wasm output is embedded`() {
        withFixture { fixture ->
            fixture.resolve("guest/source.wasm").writeBytes(minimalWasm())
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"
                include(":guest")

                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                }
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("io.heapy.kwasm")
                }

                kwasm {
                    guestProject(":guest")
                    embeddedResourceOutput.set(
                        layout.buildDirectory.file("embedded/from-guest.wasm"),
                    )
                }
                """.trimIndent(),
            )
            fixture.resolve("guest/build.gradle.kts").writeText(
                """
                tasks.register<Copy>("wasmWasiProductionExecutableCompileSync") {
                    from(layout.projectDirectory.file("source.wasm"))
                    into(layout.buildDirectory.dir("wasm"))
                    rename { "compiled-guest.wasm" }
                    outputs.file(
                        layout.buildDirectory.file("wasm/compiled-guest.wasm"),
                    )
                }
                """.trimIndent(),
            )

            val result = runner(fixture, EMBED_KWASM_GUEST_TASK_NAME).build()

            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":guest:wasmWasiProductionExecutableCompileSync")?.outcome,
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":$EMBED_KWASM_GUEST_TASK_NAME")?.outcome,
            )
            assertContentEquals(
                minimalWasm(),
                fixture.resolve("build/embedded/from-guest.wasm").readBytes(),
            )
        }
    }

    @Test
    public fun `host and wasm guest receive KSP bindgen wiring only for production`() {
        withFixture { fixture ->
            fixture.resolve("guest.wasm").writeBytes(minimalWasm())
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"
                include(":guest")

                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                }
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                    id("io.heapy.kwasm")
                }

                kwasm {
                    guestProject(":guest")
                    guestPath.set(layout.projectDirectory.file("guest.wasm"))
                    bindgenProcessorClasspath.from(
                        files(${kotlinStringLiteral(processorJar().absolutePath)}),
                    )
                }

                gradle.projectsEvaluated {
                    check(rootProject.extensions.findByName("ksp") != null) {
                        "host KSP extension was not registered"
                    }
                    check(
                        rootProject.configurations
                            .getByName("ksp")
                            .allDependencies
                            .isNotEmpty(),
                    ) {
                        "host KSP processor dependency was not wired"
                    }
                    check(rootProject.tasks.findByName("kspKotlin") != null) {
                        "host KSP task was not registered"
                    }

                    val guest = project(":guest")
                    check(guest.extensions.findByName("ksp") != null) {
                        "guest KSP extension was not registered"
                    }
                    check(
                        guest.configurations
                            .getByName("kspWasmWasi")
                            .allDependencies
                            .isNotEmpty(),
                    ) {
                        "guest production KSP processor dependency was not wired"
                    }
                    check(
                        guest.configurations
                            .getByName("kspWasmWasiTest")
                            .allDependencies
                            .isEmpty(),
                    ) {
                        "guest test KSP processor dependency should be empty"
                    }
                    check(guest.tasks.findByName("kspKotlinWasmWasi") != null) {
                        "guest KSP task was not registered"
                    }
                }
                """.trimIndent(),
            )
            fixture.resolve("guest/build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.multiplatform")
                }

                kotlin {
                    wasmWasi()
                }
                """.trimIndent(),
            )

            val first = runner(fixture, "help").build()

            assertEquals(TaskOutcome.SUCCESS, first.task(":help")?.outcome)

            val second = runner(fixture, "help").build()

            assertEquals(TaskOutcome.SUCCESS, second.task(":help")?.outcome)
            assertTrue(second.output.contains("Reusing configuration cache."))
        }
    }

    @Test
    public fun `prepare task runs bindgen and collects its generated Kotlin source`() {
        withFixture { fixture ->
            val processorCoordinates = installProcessor(fixture)
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"

                dependencyResolutionManagement {
                    repositories {
                        maven {
                            url = uri("local-repo")
                        }
                        mavenCentral()
                    }
                }
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                    id("io.heapy.kwasm")
                }

                kwasm {
                    bindgenProcessorDependency.set(
                        ${kotlinStringLiteral(processorCoordinates)},
                    )
                }
                """.trimIndent(),
            )
            fixture.resolve(
                "src/main/kotlin/io/heapy/kwasm/bindgen/Annotations.kt",
            ).apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package io.heapy.kwasm.bindgen

                    @Target(AnnotationTarget.CLASS)
                    annotation class WasmBoundary(val name: String = "")

                    @Target(AnnotationTarget.FUNCTION)
                    annotation class WasmExport(val name: String = "")
                    """.trimIndent(),
                )
            }
            fixture.resolve("src/main/kotlin/example/Greeter.kt").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example

                    import io.heapy.kwasm.bindgen.WasmBoundary
                    import io.heapy.kwasm.bindgen.WasmExport

                    @WasmBoundary("example.greeter")
                    interface Greeter {
                        @WasmExport("greet")
                        fun greet(name: String): String
                    }
                    """.trimIndent(),
                )
            }

            val first = runner(
                fixture,
                PREPARE_KWASM_BINDINGS_TASK_NAME,
                "--build-cache",
            ).build()

            assertSuccessfulOrFromCache(first.task(":kspKotlin")?.outcome)
            assertSuccessfulOrFromCache(
                first.task(":$PREPARE_KWASM_BINDINGS_TASK_NAME")?.outcome,
            )
            val aggregate =
                fixture.resolve(
                    "build/generated/kwasm/bindings/example/GreeterWasmBindings.kt",
                )
            assertTrue(aggregate.isFile)
            assertTrue(aggregate.readText().contains("class `GreeterHostClient`"))
            assertTrue(
                fixture.resolve("build/generated/kwasm/bindings/.kwasm-bindings")
                    .readText()
                    .contains("source=example/GreeterWasmBindings.kt"),
            )

            val second = runner(
                fixture,
                PREPARE_KWASM_BINDINGS_TASK_NAME,
                "--build-cache",
            ).build()

            assertEquals(
                TaskOutcome.UP_TO_DATE,
                second.task(":$PREPARE_KWASM_BINDINGS_TASK_NAME")?.outcome,
            )
        }
    }

    @Test
    public fun `embedded guest directory is consumed by the host jar`() {
        withFixture { fixture ->
            val wasmBytes = minimalWasm()
            fixture.resolve("guest.wasm").writeBytes(wasmBytes)
            fixture.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "kwasm-plugin-fixture"

                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                }
                """.trimIndent(),
            )
            fixture.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                    id("io.heapy.kwasm")
                }

                kwasm {
                    guestPath.set(layout.projectDirectory.file("guest.wasm"))
                    bindgenProcessorClasspath.from(
                        files(${kotlinStringLiteral(processorJar().absolutePath)}),
                    )
                }
                """.trimIndent(),
            )

            val result = runner(fixture, "jar").build()

            assertSuccessfulOrFromCache(
                result.task(":$EMBED_KWASM_GUEST_TASK_NAME")?.outcome,
            )
            val jar = fixture.resolve("build/libs/kwasm-plugin-fixture.jar")
            assertTrue(jar.isFile)
            ZipFile(jar).use { zip ->
                val entry = assertNotNull(zip.getEntry("kwasm/guest.wasm"))
                assertContentEquals(wasmBytes, zip.getInputStream(entry).readBytes())
            }
        }
    }

    private fun runner(projectDirectory: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory)
            .withPluginClasspath()
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    /**
     * TestKit's implementation supports interactive stdin even though the
     * method is not exposed on the public GradleRunner return type.
     */
    private fun GradleRunner.withContinuousBuildInput(input: InputStream): GradleRunner {
        val method = javaClass.getMethod("withStandardInput", InputStream::class.java)
        method.invoke(this, input)
        return this
    }

    private fun withFixture(block: (File) -> Unit) {
        val fixture = Files.createTempDirectory("kwasm-gradle-plugin-test").toFile()
        try {
            fixture.resolve("guest").mkdirs()
            block(fixture)
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun minimalWasm(): ByteArray =
        byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    private fun processorJar(): File =
        File(
            requireNotNull(System.getProperty("kwasm.test.bindgenProcessorJar")) {
                "kwasm bindgen processor test jar was not configured"
            },
        )

    private fun installProcessor(fixture: File): String {
        val group = "io.heapy.kwasm.test"
        val artifact = "bindgen-processor"
        val version = "1.0"
        val moduleDirectory = fixture.resolve(
            "local-repo/${group.replace('.', '/')}/$artifact/$version",
        )
        moduleDirectory.mkdirs()
        processorJar().copyTo(
            moduleDirectory.resolve("$artifact-$version.jar"),
            overwrite = true,
        )
        moduleDirectory.resolve("$artifact-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent(),
        )
        return "$group:$artifact:$version"
    }

    private fun kotlinStringLiteral(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun awaitCondition(
        description: String,
        timeoutMillis: Long = 120_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(25L)
        }
        assertTrue(condition(), "timed out waiting for $description")
    }

    private fun assertSuccessfulOrFromCache(outcome: TaskOutcome?) {
        assertTrue(
            outcome == TaskOutcome.SUCCESS || outcome == TaskOutcome.FROM_CACHE,
            "expected SUCCESS or FROM_CACHE but was $outcome",
        )
    }
}
