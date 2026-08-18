import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApiWarning()
    jvmToolchain(17)
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }

    android {
        namespace = "io.heapy.kwasm"
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

    sourceSets {
        commonMain {
            kotlin.srcDir("src")
            dependencies {
                api(project(":annotations"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        androidMain {
            kotlin.srcDir("src@android")
        }
        jvmMain {
            kotlin.srcDir("src@jvm")
        }
        listOf(
            "iosArm64Main",
            "iosSimulatorArm64Main",
            "linuxArm64Main",
            "linuxX64Main",
            "macosArm64Main",
        ).forEach { sourceSetName ->
            getByName(sourceSetName).kotlin.srcDir("src@native")
        }
        commonTest {
            kotlin.srcDir("test")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        listOf(
            "iosArm64Test",
            "iosSimulatorArm64Test",
            "linuxArm64Test",
            "linuxX64Test",
            "macosArm64Test",
        ).forEach { sourceSetName ->
            getByName(sourceSetName).kotlin.srcDir("test@native")
        }
        jvmTest {
            kotlin.srcDir("jvmTest")
            kotlin.srcDir("test@jvm")
        }
    }
}

kotlin.targets.withType<KotlinMetadataTarget>().configureEach {
    compilations.configureEach {
        if (name == "commonMain") {
            compileTaskProvider.configure {
                @Suppress("DEPRECATION")
                (this as KotlinCompileCommon).moduleName.set("kwasm-core_commonMain")
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = if (name == "kotlinMultiplatform") "kwasm-core" else "kwasm-core-$name"
        pom {
            name = "kwasm core"
            description = "Suspendable WebAssembly runtime for Kotlin Multiplatform"
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
