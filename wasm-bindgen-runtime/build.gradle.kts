import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("com.google.devtools.ksp") version "2.3.10"
    `maven-publish`
}

kotlin {
    explicitApiWarning()
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }

    androidLibrary {
        namespace = "io.heapy.kwasm.bindgen.runtime"
        compileSdk = 36
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()
    linuxArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":bindgen-api"))
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":snapshot"))
            implementation(project(":test-support-wat"))
            implementation(project(":wasi"))
        }
    }
}

dependencies {
    add("kspJvmTest", project(":bindgen-ksp"))
}

val currentKotlinWasmCompatibilityBinary = project(":bindgen-api").layout.buildDirectory.file(
    "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/kwasm-bindgen-api-test.wasm",
)
val additionalKotlinWasmCompatibilityBinaries: List<File> =
    providers.gradleProperty("kwasm.kotlinWasmCompatibilityBinaries")
        .orNull
        ?.split(File.pathSeparatorChar)
        ?.filter(String::isNotBlank)
        ?.map(rootProject::file)
        .orEmpty()
val additionalKotlinWasmCompatibilityVersions: List<String> =
    providers.gradleProperty("kwasm.kotlinWasmCompatibilityVersions")
        .orNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()
require(
    additionalKotlinWasmCompatibilityBinaries.size ==
        additionalKotlinWasmCompatibilityVersions.size,
) {
    "kwasm.kotlinWasmCompatibilityBinaries and " +
        "kwasm.kotlinWasmCompatibilityVersions must contain the same number of rows"
}
val currentKotlinCompilerVersion = libs.versions.kotlin.get()
val currentKotlinWasmUsesNewExceptionProposal =
    providers.gradleProperty("kwasm.kotlinWasmUseNewExceptionProposal")
        .map { it.toBooleanStrict() }
        .orElse(true)
val currentKotlinCompilerRow =
    "$currentKotlinCompilerVersion-" +
        if (currentKotlinWasmUsesNewExceptionProposal.get()) {
            "new-eh"
        } else {
            "legacy-eh"
        }
val kotlinWasmCompatibilityBinaries =
    listOf(currentKotlinWasmCompatibilityBinary.get().asFile) +
        additionalKotlinWasmCompatibilityBinaries
val kotlinWasmCompatibilityVersions =
    listOf(currentKotlinCompilerRow) +
        additionalKotlinWasmCompatibilityVersions
val requiredKotlinWasmCompatibilityVersions =
    providers.gradleProperty("kwasm.kotlinWasmCompatibilityRequiredVersions")
        .orNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?: listOf(currentKotlinCompilerRow)

tasks.named<Test>("jvmTest") {
    dependsOn(":bindgen-api:compileTestDevelopmentExecutableKotlinWasmWasi")
    inputs.files(kotlinWasmCompatibilityBinaries)
        .withPropertyName("kotlinWasmCompatibilityBinaries")
    inputs.property(
        "kotlinWasmCompatibilityVersions",
        kotlinWasmCompatibilityVersions,
    )
    inputs.property(
        "requiredKotlinWasmCompatibilityVersions",
        requiredKotlinWasmCompatibilityVersions,
    )
    systemProperty(
        "kwasm.kotlin.wasm.compatibility.binaries",
        kotlinWasmCompatibilityBinaries.joinToString(File.pathSeparator),
    )
    systemProperty(
        "kwasm.kotlin.wasm.compatibility.versions",
        kotlinWasmCompatibilityVersions.joinToString(","),
    )
    systemProperty(
        "kwasm.kotlin.wasm.compatibility.required.versions",
        requiredKotlinWasmCompatibilityVersions.joinToString(","),
    )
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            if (name == "kotlinMultiplatform") {
                "kwasm-bindgen-runtime"
            } else {
                "kwasm-bindgen-runtime-$name"
            }
        pom {
            name = "kwasm bindgen runtime adapter"
            description = "Instance-backed transport for generated kwasm bindings"
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
