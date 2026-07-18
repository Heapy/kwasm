plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "KwasmFootprintBaseline"
            isStatic = true
            binaryOption("smallBinary", "true")
            binaryOption("latin1Strings", "true")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
