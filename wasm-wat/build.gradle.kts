import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "io.heapy.kwasm.wat"
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
            kotlin.srcDir("src")
        }
        commonTest {
            kotlin.srcDir("test")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
