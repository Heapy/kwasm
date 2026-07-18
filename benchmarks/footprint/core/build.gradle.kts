plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    compilerOptions {
        optIn.add("io.heapy.kwasm.ExperimentalKwasmApi")
    }

    iosArm64 {
        binaries.framework {
            baseName = "KwasmFootprintCore"
            isStatic = true
            binaryOption("smallBinary", "true")
            binaryOption("latin1Strings", "true")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
    }
}
