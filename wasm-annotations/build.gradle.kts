import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    androidLibrary {
        namespace = "io.heapy.kwasm.annotations"
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
    wasmJs {
        nodejs()
    }
    wasmWasi {
        nodejs()
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId =
            if (name == "kotlinMultiplatform") {
                "kwasm-annotations"
            } else {
                "kwasm-annotations-$name"
            }
        pom {
            name = "kwasm API annotations"
            description = "Shared API-stability annotations for kwasm"
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
