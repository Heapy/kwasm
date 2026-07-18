import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    androidLibrary {
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
    macosX64()

    sourceSets {
        commonMain {
            kotlin.srcDir("src")
            dependencies {
                api(project(":annotations"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            kotlin.srcDir("test")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest {
            kotlin.srcDir("jvmTest")
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
