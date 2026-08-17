import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "KwasmFootprintBaseline"
            isStatic = true
            if (buildType == NativeBuildType.RELEASE) {
                binaryOption("smallBinary", "true")
            }
            binaryOption("latin1Strings", "true")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
