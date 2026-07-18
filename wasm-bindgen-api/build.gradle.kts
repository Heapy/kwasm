@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.io.File
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp") version "2.3.10"
    `maven-publish`
}

val useNewWasmExceptionProposal =
    providers.gradleProperty("kwasm.kotlinWasmUseNewExceptionProposal")
        .map { it.toBooleanStrict() }
        .orElse(true)

kotlin {
    explicitApiWarning()
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }
    androidLibrary {
        namespace = "io.heapy.kwasm.bindgen"
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
    wasmJs {
        nodejs()
    }
    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":annotations"))
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmWasiTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.withType<Kotlin2JsCompile>().configureEach {
    if (name.endsWith("KotlinWasmWasi")) {
        compilerOptions.freeCompilerArgs.add(
            if (useNewWasmExceptionProposal.get()) {
                "-Xwasm-use-new-exception-proposal"
            } else {
                "-Xwasm-use-new-exception-proposal=false"
            },
        )
    }
}

dependencies {
    add("kspJvmTest", project(":bindgen-ksp"))
    add("kspWasmJsTest", project(":bindgen-ksp"))
    add("kspWasmWasiTest", project(":bindgen-ksp"))
}

val wasmJsBindgenContractBinary = layout.buildDirectory.file(
    "compileSync/wasmJs/test/testDevelopmentExecutable/kotlin/kwasm-bindgen-api-test.wasm",
)
val wasmWasiBindgenContractBinary = layout.buildDirectory.file(
    "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/kwasm-bindgen-api-test.wasm",
)

tasks.named<Test>("jvmTest") {
    dependsOn(
        "wasmJsTestTestDevelopmentExecutableCompileSync",
        "compileTestDevelopmentExecutableKotlinWasmWasi",
    )
    inputs.files(wasmJsBindgenContractBinary, wasmWasiBindgenContractBinary)
    systemProperty(
        "kwasm.bindgen.contract.binaries",
        listOf(
            wasmJsBindgenContractBinary.get().asFile.absolutePath,
            wasmWasiBindgenContractBinary.get().asFile.absolutePath,
        ).joinToString(File.pathSeparator),
    )
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            if (name == "kotlinMultiplatform") {
                "kwasm-bindgen-api"
            } else {
                "kwasm-bindgen-api-$name"
            }
        pom {
            name = "kwasm bindgen API"
            description = "Shared boundary annotations and versioned codec for kwasm bindgen"
            url = "https://github.com/heapy/kwasm"
            licenses {
                license {
                    name = "Apache License 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0"
                }
            }
        }
    }
}
